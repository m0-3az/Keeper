package com.keeper.vault.vault.data

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.crypto.VaultCipher
import com.keeper.vault.contracts.crypto.VaultKey
import com.keeper.vault.contracts.platform.KeeperException
import com.keeper.vault.crypto.AesGcmVaultCipher
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class EncryptedVaultFileStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesRoot: File
    private lateinit var store: EncryptedVaultFileStore
    private lateinit var key: VaultKey

    @Before
    fun setUp() {
        filesRoot = tempFolder.newFolder("files")
        store = EncryptedVaultFileStore(
            files = TestAppFileStore(filesRoot),
            cipher = AesGcmVaultCipher(),
        )
        key = testKey()
    }

    @After
    fun tearDown() {
        key.close()
    }

    @Test
    fun writesOnlyCiphertextAndRoundTripsOneKilobyte() {
        val plaintext = markerBytes(1024)

        val written = store.write("one", ByteArrayInputStream(plaintext), key)

        assertThat(written).isEqualTo(1024L)
        val file = vaultFile(filesRoot, "one")
        assertThat(file.isFile).isTrue()
        val ciphertext = file.readBytes()
        assertThat(ciphertext.copyOfRange(0, 4)).isEqualTo("KPR1".toByteArray(Charsets.US_ASCII))
        assertThat(String(ciphertext, Charsets.ISO_8859_1)).doesNotContain("KEEPER_TEST_MARKER")
        assertThat(store.read("one", key)).isEqualTo(plaintext)
        assertThat(store.exists("one")).isTrue()
        assertThat(File(filesRoot, "vault").listFiles()!!.map { it.name }).containsExactly("one.enc")
    }

    @Test
    fun roundTripsTenMegabytes() {
        val plaintext = markerBytes(10 * 1024 * 1024)

        val written = store.write("large", ByteArrayInputStream(plaintext), key)

        assertThat(written).isEqualTo(10L * 1024L * 1024L)
        assertThat(store.read("large", key)).isEqualTo(plaintext)
        assertThat(vaultFile(filesRoot, "large").length()).isGreaterThan(plaintext.size.toLong())

        assertThat(store.delete("large")).isTrue()
        assertThat(vaultFile(filesRoot, "large").exists()).isFalse()
    }

    @Test
    fun rejectsWrongKeyWithoutWritingPlaintext() {
        store.write("one", ByteArrayInputStream(markerBytes(1024)), key)
        val other = testKey(seed = 99)
        try {
            val failure = runCatching { store.read("one", other) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(KeeperException.Crypto::class.java)
        } finally {
            other.close()
        }
    }

    @Test
    fun rejectsTamperedCiphertext() {
        store.write("one", ByteArrayInputStream(markerBytes(1024)), key)
        val file = vaultFile(filesRoot, "one")
        val tampered = file.readBytes()
        tampered[tampered.lastIndex] = (tampered.last().toInt() xor 0x01).toByte()
        file.writeBytes(tampered)

        val failure = runCatching { store.read("one", key) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(KeeperException.Crypto::class.java)
    }

    @Test
    fun deleteRemovesCiphertextAndIsIdempotent() {
        store.write("one", ByteArrayInputStream(markerBytes(32)), key)

        assertThat(store.delete("one")).isTrue()
        assertThat(vaultFile(filesRoot, "one").exists()).isFalse()
        assertThat(store.delete("one")).isTrue()
    }

    @Test
    fun rejectsPathTraversalIdentifiers() {
        val failure = runCatching {
            store.write("../escape", ByteArrayInputStream(markerBytes(16)), key)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(KeeperException.Vault::class.java)
        assertThat(File(filesRoot, "escape.enc").exists()).isFalse()
    }

    @Test
    fun sourceFailureLeavesNoCiphertextFile() {
        val failing = object : InputStream() {
            private var remaining = 64

            override fun read(): Int {
                if (remaining > 0) {
                    remaining--
                    return 7
                }
                throw IOException("source failed")
            }
        }

        val failure = runCatching { store.write("broken", failing, key) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(KeeperException.Vault::class.java)
        assertThat(vaultFile(filesRoot, "broken").exists()).isFalse()
    }

    @Test
    fun missingCiphertextIsAVaultFailure() {
        val failure = runCatching { store.read("missing", key) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(KeeperException.Vault::class.java)
    }

    @Test
    fun missingCiphertextDoesNotExist() {
        assertThat(store.exists("missing")).isFalse()
    }

    @Test
    fun rejectsControlCharacterIdentifiers() {
        val failure = runCatching { store.exists("bad\u0000id") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(KeeperException.Vault::class.java)
    }

    @Test
    fun vaultPathThatIsAFileIsAVaultFailure() {
        File(filesRoot, "vault").writeText("not a directory")

        val failure = runCatching {
            store.write("one", ByteArrayInputStream(markerBytes(16)), key)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(KeeperException.Vault::class.java)
    }

    @Test
    fun targetThatIsADirectoryIsAWrappedWriteFailure() {
        val vault = File(filesRoot, "vault")
        assertThat(vault.mkdirs()).isTrue()
        assertThat(File(vault, "one.enc").mkdirs()).isTrue()

        val failure = runCatching {
            store.write("one", ByteArrayInputStream(markerBytes(16)), key)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(KeeperException.Vault::class.java)
    }

    @Test
    fun cipherRuntimeFailureDeletesCiphertext() {
        val brokenCipher = object : VaultCipher {
            override fun encrypt(plaintext: InputStream, ciphertext: OutputStream, key: VaultKey) {
                ciphertext.write(byteArrayOf(1, 2, 3))
                throw IllegalStateException("cipher failed")
            }

            override fun decrypt(ciphertext: InputStream, plaintext: OutputStream, key: VaultKey) {
                throw IllegalStateException("not used")
            }
        }
        val brokenStore = EncryptedVaultFileStore(TestAppFileStore(filesRoot), brokenCipher)

        val failure = runCatching {
            brokenStore.write("one", ByteArrayInputStream(markerBytes(16)), key)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(vaultFile(filesRoot, "one").exists()).isFalse()
    }

    @Test
    fun cipherFailureWhileReadingIsPropagated() {
        val vault = File(filesRoot, "vault")
        assertThat(vault.mkdirs()).isTrue()
        vaultFile(filesRoot, "one").writeBytes(byteArrayOf(1, 2, 3))
        val brokenCipher = object : VaultCipher {
            override fun encrypt(plaintext: InputStream, ciphertext: OutputStream, key: VaultKey) {
                throw IllegalStateException("not used")
            }

            override fun decrypt(ciphertext: InputStream, plaintext: OutputStream, key: VaultKey) {
                plaintext.write(byteArrayOf(9, 9, 9))
                throw IllegalStateException("cipher failed")
            }
        }
        val brokenStore = EncryptedVaultFileStore(TestAppFileStore(filesRoot), brokenCipher)

        val failure = runCatching { brokenStore.read("one", key) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun directoryAtCiphertextPathIsAVaultFailure() {
        val vault = File(filesRoot, "vault")
        assertThat(vault.mkdirs()).isTrue()
        assertThat(vaultFile(filesRoot, "one").mkdirs()).isTrue()

        val failure = runCatching { store.read("one", key) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(KeeperException.Vault::class.java)
    }
}
