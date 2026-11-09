package com.keeper.vault.presence.enrollment

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.crypto.VaultKey
import com.keeper.vault.contracts.platform.KeeperException
import com.keeper.vault.contracts.presence.FaceEmbedding
import com.keeper.vault.crypto.AesGcmVaultCipher
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

class EncryptedFaceEnrollmentStoreTest {
    private val cipher = AesGcmVaultCipher()
    private val key = VaultKey.from(ByteArray(32) { 7 })
    private val directory: File = Files.createTempDirectory("keeper-enrollment").toFile()

    @After
    fun cleanup() {
        directory.deleteRecursively()
        key.close()
    }

    private fun storeForKey(
        keyProvider: VaultKeyProvider = VaultKeyProvider { key },
    ): EncryptedFaceEnrollmentStore = EncryptedFaceEnrollmentStore(
        directory = directory,
        cipher = cipher,
        keyProvider = keyProvider,
        dispatcher = Dispatchers.Default,
    )

    @Test
    fun saveWritesOnlyCiphertextAndLoadRoundTrips() = runTest {
        val embedding = FaceEmbedding(floatArrayOf(0.25f, -1.5f, 3.75f))
        val store = storeForKey()

        store.save(embedding)

        val file = File(directory, EncryptedFaceEnrollmentStore.FILE_NAME)
        assertThat(file.exists()).isTrue()
        assertThat(file.readBytes().take(4).toByteArray().toString(Charsets.US_ASCII))
            .isEqualTo("KPR1")
        assertThat(file.readBytes().size).isGreaterThan(4 + 16 + 12)

        val loaded = store.load()
        assertThat(loaded).isNotNull()
        assertThat(loaded!!.values().toList()).containsExactly(0.25f, -1.5f, 3.75f).inOrder()
    }

    @Test
    fun noPlaintextFloatArrayAppearsInTheFile() = runTest {
        val values = floatArrayOf(1234.5f, -987.25f, 42f, 3.5f)
        val store = storeForKey()

        store.save(FaceEmbedding(values))

        val bytes = File(directory, EncryptedFaceEnrollmentStore.FILE_NAME).readBytes()
        val plain = java.nio.ByteBuffer.allocate(values.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .putFloat(1234.5f)
            .putFloat(-987.25f)
            .putFloat(42f)
            .putFloat(3.5f)
            .array()
        assertThat(bytes.indexOfSubsequence(plain)).isEqualTo(-1)
    }

    @Test
    fun saveIsReplacedOnASecondCapture() = runTest {
        val store = storeForKey()
        store.save(FaceEmbedding(floatArrayOf(1f, 1f)))
        store.save(FaceEmbedding(floatArrayOf(9f, 9f)))

        assertThat(store.load()!!.values().toList()).containsExactly(9f, 9f).inOrder()
    }

    @Test
    fun wrongKeyFailsAuthentication() = runTest {
        storeForKey().save(FaceEmbedding(floatArrayOf(1f, 2f)))
        val wrongKey = VaultKey.from(ByteArray(32) { 8 })
        try {
            val error = runCatching {
                storeForKey(VaultKeyProvider { wrongKey }).load()
            }.exceptionOrNull()
            assertThat(error).isInstanceOf(KeeperException.Crypto::class.java)
        } finally {
            wrongKey.close()
        }
    }

    @Test
    fun tamperedCiphertextFailsAuthentication() = runTest {
        val store = storeForKey()
        store.save(FaceEmbedding(floatArrayOf(1f, 2f)))
        val file = File(directory, EncryptedFaceEnrollmentStore.FILE_NAME)
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        file.writeBytes(bytes)

        val error = runCatching { store.load() }.exceptionOrNull()

        assertThat(error).isInstanceOf(KeeperException.Crypto::class.java)
    }

    @Test
    fun missingEnrollmentReturnsNull() = runTest {
        assertThat(storeForKey().load()).isNull()
    }

    @Test
    fun missingKeyRefusesToWriteOrRead() = runTest {
        val store = storeForKey(VaultKeyProvider { null })

        assertThat(runCatching { store.save(FaceEmbedding(floatArrayOf(1f))) }.exceptionOrNull())
            .isInstanceOf(KeeperException.Crypto::class.java)
        // No file exists: "not enrolled yet" is null, not a key error.
        assertThat(store.load()).isNull()

        storeForKey().save(FaceEmbedding(floatArrayOf(1f)))
        assertThat(runCatching { store.load() }.exceptionOrNull())
            .isInstanceOf(KeeperException.Crypto::class.java)
    }

    @Test
    fun clearRemovesTheCiphertext() = runTest {
        val store = storeForKey()
        store.save(FaceEmbedding(floatArrayOf(1f)))

        store.clear()

        assertThat(store.load()).isNull()
        assertThat(File(directory, EncryptedFaceEnrollmentStore.FILE_NAME).exists()).isFalse()
    }

    private fun ByteArray.indexOfSubsequence(needle: ByteArray): Int {
        if (needle.isEmpty()) return 0
        for (start in 0..(size - needle.size)) {
            var match = true
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) {
                    match = false
                    break
                }
            }
            if (match) return start
        }
        return -1
    }
}
