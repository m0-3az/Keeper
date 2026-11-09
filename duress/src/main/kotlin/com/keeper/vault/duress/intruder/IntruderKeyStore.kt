package com.keeper.vault.duress.intruder

import android.content.Context
import com.keeper.vault.contracts.crypto.KeystoreEnvelope
import com.keeper.vault.contracts.intruder.IntruderKeyStore
import com.keeper.vault.contracts.platform.KeeperException
import com.keeper.vault.contracts.platform.SecureRandomSource
import com.keeper.vault.contracts.platform.SecretZeroizer
import com.keeper.vault.crypto.AndroidKeystoreEnvelope
import com.keeper.vault.crypto.DefaultSecureRandomSource
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Intruder image key store.
 *
 * A random 32-byte `IntruderKey` is generated on first use and wrapped by a dedicated non-auth
 * Android Keystore key (`keeper.intruder.v1`, StrongBox preferred, TEE fallback). Only the wrapped
 * blob is persisted, at `filesDir/intruder/key.bin`. Captures are sealed with the IntruderKey and
 * exist on disk only as `KPI1` ciphertext.
 *
 * **Honest limitation:** this key is protected by the Keystore only, not by the user's secret.
 * Failed-unlock capture happens before any secret-derived `VaultKey` exists, so using the vault
 * key here would be impossible. A compromised app process can decrypt these images; that is
 * documented in `LIMITATIONS.md`.
 *
 * This is deliberately separate from [com.keeper.vault.contracts.crypto.VaultCipher] and never
 * receives a vault key.
 */
class KeystoreIntruderKeyStore(
    private val envelope: KeystoreEnvelope,
    private val keyFile: File,
    private val captureDirectory: File,
    private val random: SecureRandomSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : IntruderKeyStore {
    private val cipher = IntruderImageCipher(random)
    private val mutex = Mutex()

    override suspend fun encrypt(plaintext: ByteArray): ByteArray = withContext(ioDispatcher) {
        require(plaintext.isNotEmpty()) { "Intruder image must not be empty" }
        val key = keyBytes(createIfMissing = true)
        try {
            cipher.seal(key, plaintext)
        } finally {
            SecretZeroizer.zero(key)
        }
    }

    override suspend fun decrypt(ciphertext: ByteArray): ByteArray = withContext(ioDispatcher) {
        if (ciphertext.isEmpty()) throw KeeperException.Crypto("Intruder image is empty")
        val key = keyBytes(createIfMissing = false)
        try {
            cipher.open(key, ciphertext)
        } finally {
            SecretZeroizer.zero(key)
        }
    }

    override suspend fun destroy() {
        withContext(ioDispatcher) {
            var failure: Throwable? = null
            try {
                mutex.withLock {
                    if (keyFile.exists() && !keyFile.delete()) {
                        failure = KeeperException.Crypto("Could not delete the intruder key")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            }
            try {
                envelope.deleteKey()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (failure == null) failure = e
            }
            try {
                deleteCapturesLocked()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (failure == null) failure = e
            }
            failure?.let {
                throw KeeperException.Crypto("Could not destroy the intruder key", it)
            }
        }
    }

    private suspend fun keyBytes(createIfMissing: Boolean): ByteArray {
        mutex.withLock {
            val wrapped = readWrappedKey()
            if (wrapped == null) {
                if (!createIfMissing) {
                    throw KeeperException.Crypto("Intruder key is missing")
                }
                return createKeyLocked()
            }
            val key = try {
                envelope.unwrap(wrapped)
            } catch (e: CancellationException) {
                throw e
            } catch (e: KeeperException.Crypto) {
                throw e
            } catch (e: Exception) {
                throw KeeperException.Crypto("Could not unwrap the intruder key", e)
            }
            if (key.size != IntruderImageCipher.KEY_BYTES) {
                SecretZeroizer.zero(key)
                throw KeeperException.Crypto("Intruder key has an invalid length")
            }
            return key
        }
    }

    private fun createKeyLocked(): ByteArray {
        val key = random.nextBytes(IntruderImageCipher.KEY_BYTES)
        val wrapped = try {
            envelope.wrap(key)
        } catch (e: CancellationException) {
            SecretZeroizer.zero(key)
            throw e
        } catch (e: Exception) {
            SecretZeroizer.zero(key)
            throw KeeperException.Crypto("Could not wrap the intruder key", e)
        }
        try {
            writeWrappedKey(wrapped)
        } catch (e: Exception) {
            SecretZeroizer.zero(key)
            throw e
        }
        return key
    }

    private fun readWrappedKey(): ByteArray? {
        if (!keyFile.exists()) return null
        val bytes = try {
            keyFile.readBytes()
        } catch (e: IOException) {
            throw KeeperException.Crypto("Could not read the intruder key", e)
        }
        if (bytes.size < MAGIC.size + 1 + 4) {
            throw KeeperException.Crypto("Corrupt intruder key file")
        }
        if (!bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw KeeperException.Crypto("Corrupt intruder key magic")
        }
        if (bytes[MAGIC.size].toInt() != VERSION) {
            throw KeeperException.Crypto("Unsupported intruder key version")
        }
        val length = ByteBuffer.wrap(bytes, MAGIC.size + 1, 4).int
        if (length <= 0 || length != bytes.size - (MAGIC.size + 1 + 4)) {
            throw KeeperException.Crypto("Corrupt intruder key length")
        }
        return bytes.copyOfRange(MAGIC.size + 1 + 4, bytes.size)
    }

    private fun writeWrappedKey(wrapped: ByteArray) {
        val directory = keyFile.parentFile
        if (directory != null && !directory.isDirectory && !directory.mkdirs() &&
            !directory.isDirectory
        ) {
            throw KeeperException.Crypto("Could not create the intruder directory")
        }
        val out = ByteArray(MAGIC.size + 1 + 4 + wrapped.size)
        MAGIC.copyInto(out)
        out[MAGIC.size] = VERSION.toByte()
        ByteBuffer.wrap(out, MAGIC.size + 1, 4).putInt(wrapped.size)
        wrapped.copyInto(out, MAGIC.size + 1 + 4)
        try {
            // KEEPER_ENCRYPTED_WRITE: only the Keystore-wrapped intruder key is written here.
            keyFile.writeBytes(out)
        } catch (e: IOException) {
            throw KeeperException.Crypto("Could not write the intruder key", e)
        } finally {
            out.fill(0)
        }
    }

    private fun deleteCapturesLocked() {
        val files = captureDirectory.listFiles { file ->
            file.isFile && file.name.endsWith(CAPTURE_SUFFIX)
        } ?: return
        for (file in files) {
            if (!file.delete()) {
                throw KeeperException.Crypto("Could not delete an intruder capture")
            }
        }
    }

    private companion object {
        const val VERSION = 1
        const val CAPTURE_SUFFIX = ".enc"
        val MAGIC = "KIW1".toByteArray(Charsets.US_ASCII)
    }
}

/**
 * Android wiring for the dedicated intruder key.
 *
 * The alias is frozen: `keeper.intruder.v1`. It is a non-auth key, so it works on the unlock
 * screen before a user secret has been entered. StrongBox is preferred, TEE is the fallback,
 * exactly like the vault's Layer A adapter.
 */
object AndroidIntruderKeys {
    const val ALIAS: String = "keeper.intruder.v1"

    /** Stills live here; every file is `*.enc` ciphertext. */
    const val IMAGE_DIRECTORY_NAME: String = "intruder"

    /** The wrapped key lives outside the image directory so that tree holds ciphertext only. */
    const val KEY_DIRECTORY_NAME: String = "duress"
    const val KEY_FILE_NAME: String = "intruder-key.bin"

    fun create(
        context: Context,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): IntruderKeyStore {
        val filesDir = context.applicationContext.filesDir
        return KeystoreIntruderKeyStore(
            envelope = AndroidKeystoreEnvelope(ALIAS),
            keyFile = File(File(filesDir, KEY_DIRECTORY_NAME), KEY_FILE_NAME),
            captureDirectory = File(filesDir, IMAGE_DIRECTORY_NAME),
            random = DefaultSecureRandomSource(),
            ioDispatcher = ioDispatcher,
        )
    }
}
