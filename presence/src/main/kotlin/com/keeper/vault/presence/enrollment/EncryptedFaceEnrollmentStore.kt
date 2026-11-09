package com.keeper.vault.presence.enrollment

import com.keeper.vault.contracts.crypto.VaultCipher
import com.keeper.vault.contracts.crypto.VaultKey
import com.keeper.vault.contracts.platform.KeeperException
import com.keeper.vault.contracts.presence.FaceEmbedding
import com.keeper.vault.contracts.presence.FaceEnrollmentStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Encrypted owner-template store.
 *
 * The embedding is serialized into a zeroable in-memory buffer and passed straight to the injected
 * [VaultCipher]. Only ciphertext ever reaches the app-private file; there is no plaintext temp or
 * cache file. The cipher produces the standard `KPR1` container, so a wrong key or a tampered byte
 * fails the GCM tag with [KeeperException.Crypto].
 *
 * The store is behavior-layer code: it never derives or persists a key. The app supplies the
 * current [VaultKey] through [VaultKeyProvider].
 */
class EncryptedFaceEnrollmentStore(
    private val directory: File,
    private val cipher: VaultCipher,
    private val keyProvider: VaultKeyProvider,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FaceEnrollmentStore {
    private val mutex = Mutex()

    override suspend fun save(embedding: FaceEmbedding) = mutex.withLock {
        val plaintext = encode(embedding)
        try {
            withContext(dispatcher) {
                writeCiphertext(plaintext)
            }
        } finally {
            plaintext.fill(0)
        }
    }

    override suspend fun load(): FaceEmbedding? = mutex.withLock {
        withContext(dispatcher) {
            val file = File(directory, FILE_NAME)
            if (!file.isFile) return@withContext null
            val key = keyProvider.currentKey() ?: throw KeeperException.Crypto(
                "The vault is locked; the owner template cannot be read",
            )

            val output = ZeroableByteArrayOutputStream()
            try {
                // KEEPER_ENCRYPTED_WRITE: only the injected cipher writes this file.
                Files.newInputStream(file.toPath()).use { input ->
                    cipher.decrypt(input, output, key)
                }
                val plaintext = output.toByteArray()
                try {
                    decode(plaintext)
                } finally {
                    plaintext.fill(0)
                }
            } finally {
                output.zero()
            }
        }
    }

    override suspend fun clear() = mutex.withLock {
        withContext(dispatcher) {
            Files.deleteIfExists(File(directory, FILE_NAME).toPath())
            Files.deleteIfExists(File(directory, TEMP_NAME).toPath())
            Unit
        }
    }

    private fun writeCiphertext(plaintext: ByteArray) {
        val key = keyProvider.currentKey() ?: throw KeeperException.Crypto(
            "The vault is locked; the owner template cannot be written",
        )
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw KeeperException.Crypto("Cannot create the owner-template directory")
        }
        val target = File(directory, FILE_NAME)
        val temp = File(directory, TEMP_NAME)
        try {
            // KEEPER_ENCRYPTED_WRITE: the only bytes on disk are the cipher's container.
            Files.newOutputStream(
                temp.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            ).use { output ->
                cipher.encrypt(ByteArrayInputStream(plaintext), output, key)
            }
            moveIntoPlace(temp, target)
        } catch (error: Throwable) {
            Files.deleteIfExists(temp.toPath())
            throw error
        }
    }

    private fun moveIntoPlace(temp: File, target: File) {
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun encode(embedding: FaceEmbedding): ByteArray {
        val values = embedding.values()
        try {
            val buffer = ByteBuffer.allocate(HEADER_BYTES + values.size * Float.SIZE_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
            buffer.putInt(values.size)
            for (value in values) {
                buffer.putFloat(value)
            }
            return buffer.array()
        } finally {
            values.fill(0f)
        }
    }

    private fun decode(plaintext: ByteArray): FaceEmbedding {
        if (plaintext.size < HEADER_BYTES) {
            throw KeeperException.Crypto("Invalid owner-template payload")
        }
        val buffer = ByteBuffer.wrap(plaintext).order(ByteOrder.BIG_ENDIAN)
        val count = buffer.int
        if (count !in 1..MAX_EMBEDDING_VALUES) {
            throw KeeperException.Crypto("Invalid owner-template size")
        }
        if (plaintext.size != HEADER_BYTES + count * Float.SIZE_BYTES) {
            throw KeeperException.Crypto("Invalid owner-template length")
        }
        val values = FloatArray(count)
        buffer.asFloatBuffer().get(values)
        return try {
            FaceEmbedding(values)
        } finally {
            values.fill(0f)
        }
    }

    private class ZeroableByteArrayOutputStream : ByteArrayOutputStream() {
        fun zero() {
            buf.fill(0)
            reset()
        }
    }

    companion object {
        /** Ciphertext file name inside the app-private directory. */
        const val FILE_NAME: String = "owner-face.enc"

        private const val TEMP_NAME: String = "$FILE_NAME.tmp"
        private const val HEADER_BYTES: Int = 4
        private const val MAX_EMBEDDING_VALUES: Int = 4_096
    }
}
