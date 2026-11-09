package com.keeper.vault.crypto

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.crypto.CryptoConstants
import com.keeper.vault.contracts.crypto.VaultKey
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class AesGcmVaultCipherFileTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val cipher = AesGcmVaultCipher(DefaultSecureRandomSource())
    private val key = VaultKey.from(ByteArray(CryptoConstants.VAULT_KEY_BYTES) { (it + 5).toByte() })

    @Test
    fun `round trips 1 KB through a ciphertext file without a plaintext cache`() {
        assertFileRoundTrip(1 * 1024)
    }

    @Test
    fun `round trips 10 MB through a ciphertext file without a plaintext cache`() {
        assertFileRoundTrip(10 * 1024 * 1024)
    }

    private fun assertFileRoundTrip(size: Int) {
        val directory = temporaryFolder.newFolder("cipher-${System.nanoTime()}")
        val ciphertextFile = File(directory, "entry.enc")
        val marker = "KEEPER-PLAINTEXT-MARKER".toByteArray(Charsets.US_ASCII)
        val plaintext = ByteArray(size).also { bytes ->
            marker.copyInto(bytes)
            for (i in marker.size until bytes.size) {
                bytes[i] = (i % 251).toByte()
            }
        }

        FileOutputStream(ciphertextFile).use { output ->
            cipher.encrypt(plaintext.inputStream(), output, key)
        }
        val decrypted = FileInputStream(ciphertextFile).use { input ->
            val out = java.io.ByteArrayOutputStream()
            cipher.decrypt(input, out, key)
            out.toByteArray()
        }

        assertThat(decrypted).isEqualTo(plaintext)
        assertThat(directory.listFiles()!!.map { it.name }).containsExactly("entry.enc")
        assertThat(ciphertextFile.readBytes().indexOfSlice(marker)).isEqualTo(-1)
    }

    private fun ByteArray.indexOfSlice(needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > size) return -1
        outer@ for (i in 0..(size - needle.size)) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
