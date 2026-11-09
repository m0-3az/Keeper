package com.keeper.vault.duress

import com.keeper.vault.contracts.crypto.KeyEnvelope
import com.keeper.vault.contracts.crypto.KeystoreEnvelope
import com.keeper.vault.contracts.crypto.VaultKey
import com.keeper.vault.contracts.crypto.VaultKeyManager
import com.keeper.vault.contracts.intruder.IntruderEvent
import com.keeper.vault.contracts.intruder.IntruderSettings
import com.keeper.vault.contracts.intruder.IntruderTrigger
import com.keeper.vault.contracts.isolation.TamperLog
import com.keeper.vault.contracts.isolation.TamperSignal
import com.keeper.vault.contracts.platform.KeeperException
import com.keeper.vault.duress.intruder.IntruderEventStore
import com.keeper.vault.duress.settings.IntruderSettingsPersistence
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * JVM-only Keystore stand-in for unit tests.
 *
 * The real [com.keeper.vault.crypto.AndroidKeystoreEnvelope] is deliberately not on the JVM test
 * classpath behaviour: it needs a device Keystore. This fake keeps the same wrap/unwrap contract
 * (AES-GCM with a non-auth key) and supports key deletion. The real adapter is exercised by the
 * instrumented tests on a device.
 */
class FakeKeystoreEnvelope : KeystoreEnvelope {
    private var key: SecretKey? = newKey()

    override fun wrap(layerA: ByteArray): ByteArray {
        require(layerA.isNotEmpty())
        if (key == null) key = newKey()
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, currentKey(), GCMParameterSpec(128, nonce))
        val ciphertext = cipher.doFinal(layerA)
        return ByteArray(2 + nonce.size + ciphertext.size).also { out ->
            out[0] = VERSION
            out[1] = nonce.size.toByte()
            nonce.copyInto(out, 2)
            ciphertext.copyInto(out, 2 + nonce.size)
        }
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        if (wrapped.size < 2 + NONCE_BYTES + TAG_BYTES) {
            throw KeeperException.Crypto("Invalid wrapped layer")
        }
        val ivLength = wrapped[1].toInt() and 0xFF
        if (ivLength != NONCE_BYTES) throw KeeperException.Crypto("Invalid IV")
        val nonce = wrapped.copyOfRange(2, 2 + ivLength)
        val ciphertext = wrapped.copyOfRange(2 + ivLength, wrapped.size)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, currentKey(), GCMParameterSpec(128, nonce))
            cipher.doFinal(ciphertext)
        } catch (e: GeneralSecurityException) {
            throw KeeperException.Crypto("Unwrap failed", e)
        }
    }

    override fun deleteKey() {
        key = null
    }

    private fun currentKey(): SecretKey =
        key ?: throw KeeperException.Crypto("Keystore key missing")

    private fun newKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_BYTES = 12
        const val TAG_BYTES = 16
        const val VERSION: Byte = 1
    }
}

/** In-memory settings persistence. */
class InMemoryIntruderSettingsPersistence(
    initial: String? = null,
) : IntruderSettingsPersistence {
    var raw: String? = initial
    var failWrites: Boolean = false
    var writes: Int = 0
        private set

    override fun loadRaw(): String? = raw

    override fun saveRaw(value: String) {
        if (failWrites) throw IOException("disk full")
        raw = value
        writes++
    }
}

/** Mutable clock for retention and rate-limit tests. */
class MutableClock(var nowMillis: Long) : com.keeper.vault.contracts.platform.Clock {
    override fun nowMillis(): Long = nowMillis
}

/** In-memory tamper log; records what the coordinator writes. */
class RecordingTamperLog : TamperLog {
    val signals = mutableListOf<TamperSignal>()
    private val state = MutableStateFlow<List<TamperSignal>>(emptyList())

    override suspend fun append(signal: TamperSignal) {
        signals += signal
        state.value = signals.toList()
    }

    override fun observe(): Flow<List<TamperSignal>> = state

    override suspend fun clear() {
        signals.clear()
        state.value = emptyList()
    }
}

/** In-memory metadata store with the same ordering as the Room query. */
class FakeIntruderEventStore : IntruderEventStore {
    private val state = MutableStateFlow<List<IntruderEvent>>(emptyList())
    var failInserts: Boolean = false
    var insertCount: Int = 0
        private set

    val rows: List<IntruderEvent>
        get() = state.value

    override fun observe(): Flow<List<IntruderEvent>> = state

    override suspend fun insert(event: IntruderEvent) {
        if (failInserts) throw KeeperException.Vault("insert failed")
        insertCount++
        state.value = (state.value + event).sortedByDescending { it.capturedAtMillis }
    }

    override suspend fun find(id: String): IntruderEvent? = state.value.firstOrNull { it.id == id }

    override suspend fun olderThan(cutoffMillis: Long): List<IntruderEvent> =
        state.value.filter { it.capturedAtMillis < cutoffMillis }

    override suspend fun all(): List<IntruderEvent> = state.value

    override suspend fun delete(id: String): Boolean {
        val next = state.value.filterNot { it.id == id }
        val removed = next.size != state.value.size
        state.value = next
        return removed
    }
}

/** In-memory decoy key manager; production uses [com.keeper.vault.crypto.DefaultVaultKeyManager]. */
class FakeVaultKeyManager : VaultKeyManager {
    private var secret: CharArray? = null
    private var keyBytes: ByteArray = ByteArray(32)
    private var created: Int = 0
    var failCreate: Boolean = false

    val isConfigured: Boolean
        get() = secret != null

    override suspend fun exists(): Boolean = secret != null

    override suspend fun create(secret: CharArray): VaultKey {
        if (failCreate) throw KeeperException.Crypto("create failed")
        check(this.secret == null) { "already configured" }
        this.secret = secret.copyOf()
        created++
        keyBytes = ByteArray(32) { offset -> (created + offset).toByte() }
        return VaultKey.from(keyBytes)
    }

    override suspend fun unlock(secret: CharArray): VaultKey {
        val current = this.secret ?: throw KeeperException.Crypto("no vault")
        if (!current.contentEquals(secret)) throw KeeperException.Crypto("wrong secret")
        return VaultKey.from(keyBytes)
    }

    override suspend fun changeSecret(oldSecret: CharArray, newSecret: CharArray) {
        val current = this.secret ?: throw KeeperException.Crypto("no vault")
        if (!current.contentEquals(oldSecret)) throw KeeperException.Crypto("wrong secret")
        this.secret?.fill('\u0000')
        this.secret = newSecret.copyOf()
    }

    override suspend fun destroy() {
        secret?.fill('\u0000')
        secret = null
        keyBytes.fill(0)
    }

    override fun envelope(): KeyEnvelope? = null
}

/** Configurable settings snapshot for coordinator tests. */
fun enabledSettings(
    threshold: Int = 5,
    mismatch: Boolean = false,
    retentionDays: Int? = 30,
): IntruderSettings = IntruderSettings(
    enabled = true,
    captureOnFaceMismatch = mismatch,
    failureThreshold = threshold,
    retentionDays = retentionDays,
)

/** Convenience event for coordinator tests. */
fun testEvent(trigger: IntruderTrigger, nowMillis: Long, id: String = "event-$nowMillis"): IntruderEvent =
    IntruderEvent(
        id = id,
        capturedAtMillis = nowMillis,
        trigger = trigger,
        encryptedFileName = "$id.enc",
    )
