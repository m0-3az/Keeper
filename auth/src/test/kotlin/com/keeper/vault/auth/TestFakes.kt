package com.keeper.vault.auth

import android.content.SharedPreferences
import com.keeper.vault.contracts.auth.SecretType
import com.keeper.vault.contracts.crypto.BiometricEnvelope
import com.keeper.vault.contracts.crypto.BiometricVaultKeyStore
import com.keeper.vault.contracts.crypto.KeyEnvelope
import com.keeper.vault.contracts.crypto.VaultKey
import com.keeper.vault.contracts.crypto.VaultKeyManager
import com.keeper.vault.contracts.platform.Clock
import com.keeper.vault.contracts.platform.KeeperException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

internal class FakeClock(var now: Long = 1_000L) : Clock {
    override fun nowMillis(): Long = now
}

internal class FakeSecretTypeStore(var type: SecretType? = null) : SecretTypeStore {
    override fun load(): SecretType? = type

    override fun save(type: SecretType) {
        this.type = type
    }

    override fun clear() {
        type = null
    }
}

internal class InMemoryAttemptStateStore(
    var state: PersistedAttempts = PersistedAttempts(0),
) : AttemptStateStore {
    var writes: Int = 0

    override fun read(): PersistedAttempts = state

    override fun write(state: PersistedAttempts) {
        this.state = state
        writes++
    }
}

/**
 * Test-only vault key manager. Secrets are reduced to a SHA-256 digest so no secret survives the
 * test in a readable form.
 */
internal class FakeVaultKeyManager : VaultKeyManager {
    val keyBytes: ByteArray = ByteArray(32) { (it + 1).toByte() }
    var configured: Boolean = false
        private set
    var createCount: Int = 0
        private set
    var unlockCount: Int = 0
        private set
    var changeCount: Int = 0
        private set
    var destroyed: Boolean = false
        private set
    private var secretDigest: ByteArray? = null

    override suspend fun exists(): Boolean = configured

    override suspend fun create(secret: CharArray): VaultKey {
        createCount++
        if (configured) throw KeeperException.Crypto("Vault already exists")
        secretDigest = digest(secret)
        configured = true
        return VaultKey.from(keyBytes)
    }

    override suspend fun unlock(secret: CharArray): VaultKey {
        unlockCount++
        val expected = secretDigest ?: throw KeeperException.Crypto("Unlock failed")
        val actual = digest(secret)
        try {
            if (!MessageDigest.isEqual(expected, actual)) {
                throw KeeperException.Crypto("Unlock failed")
            }
            return VaultKey.from(keyBytes)
        } finally {
            actual.fill(0)
        }
    }

    override suspend fun changeSecret(oldSecret: CharArray, newSecret: CharArray) {
        changeCount++
        unlock(oldSecret).close()
        secretDigest = digest(newSecret)
    }

    override suspend fun destroy() {
        destroyed = true
        configured = false
        secretDigest = null
    }

    override fun envelope(): KeyEnvelope? = null

    fun accepts(secret: CharArray): Boolean {
        val expected = secretDigest ?: return false
        val actual = digest(secret)
        return try {
            MessageDigest.isEqual(expected, actual)
        } finally {
            actual.fill(0)
        }
    }

    private fun digest(secret: CharArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        for (char in secret) {
            digest.update((char.code ushr 8).toByte())
            digest.update(char.code.toByte())
        }
        return digest.digest()
    }
}

internal class FakeBiometricVaultKeyStore : BiometricVaultKeyStore, BiometricCipherWrapper {
    var available: Boolean = true
    var deleted: Boolean = false
        private set
    var unwrapFailure: Boolean = false
    var wrapFailure: Boolean = false
    var wrapCount: Int = 0
        private set

    override fun isAvailable(): Boolean = available && !deleted

    override fun wrap(vaultKey: ByteArray): BiometricEnvelope {
        wrapCount++
        if (wrapFailure) throw KeeperException.Crypto("wrap failed")
        return BiometricEnvelope(
            iv = ByteArray(12) { 0x2A },
            ciphertext = vaultKey.copyOf(),
        )
    }

    override fun createDecryptCipher(envelope: BiometricEnvelope): Cipher = dummyCipher()

    override fun unwrap(cipher: Cipher, envelope: BiometricEnvelope): ByteArray {
        if (unwrapFailure) throw KeeperException.Crypto("unwrap failed")
        return envelope.ciphertext.copyOf()
    }

    override fun deleteKey() {
        deleted = true
    }

    override fun createEncryptCipher(): Cipher = dummyCipher()

    override fun wrapWithCipher(cipher: Cipher, vaultKey: ByteArray): BiometricEnvelope =
        wrap(vaultKey)

    private fun dummyCipher(): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(ByteArray(16) { 3 }, "AES"))
        }
}

internal class FakeBiometricEnvelopeStore(
    var envelope: BiometricEnvelope? = null,
) : BiometricEnvelopeStore {
    override fun load(): BiometricEnvelope? = envelope

    override fun save(envelope: BiometricEnvelope) {
        this.envelope = envelope
    }

    override fun clear() {
        envelope = null
    }
}

/** Minimal in-memory SharedPreferences, enough for the two small preference adapters. */
internal class FakeSharedPreferences : SharedPreferences {
    val values: MutableMap<String, Any?> = linkedMapOf()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(key: String, defValue: String?): String? =
        values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? MutableSet<String>) ?: defValues

    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor(this)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) = Unit
}

private class FakeEditor(
    private val prefs: FakeSharedPreferences,
) : SharedPreferences.Editor {
    private val pending: MutableMap<String, Any?> = linkedMapOf()
    private val removals: MutableSet<String> = linkedSetOf()
    private var clearRequested: Boolean = false

    override fun putString(key: String, value: String?): SharedPreferences.Editor =
        apply { pending[key] = value }

    override fun putStringSet(
        key: String,
        values: MutableSet<String>?,
    ): SharedPreferences.Editor = apply { pending[key] = values }

    override fun putInt(key: String, value: Int): SharedPreferences.Editor =
        apply { pending[key] = value }

    override fun putLong(key: String, value: Long): SharedPreferences.Editor =
        apply { pending[key] = value }

    override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
        apply { pending[key] = value }

    override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
        apply { pending[key] = value }

    override fun remove(key: String): SharedPreferences.Editor = apply { removals += key }

    override fun clear(): SharedPreferences.Editor = apply { clearRequested = true }

    override fun commit(): Boolean {
        apply()
        return true
    }

    override fun apply() {
        if (clearRequested) prefs.values.clear()
        removals.forEach { prefs.values.remove(it) }
        pending.forEach { (key, value) -> prefs.values[key] = value }
    }
}
