package com.keeper.vault.crypto

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.crypto.CryptoConstants
import com.keeper.vault.contracts.crypto.VaultKey
import com.keeper.vault.contracts.platform.KeeperException
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AesGcmVaultCipherTest {
    private val cipher = AesGcmVaultCipher(DefaultSecureRandomSource())
    private val key = VaultKey.from(ByteArray(CryptoConstants.VAULT_KEY_BYTES) { (it + 1).toByte() })

    @Test
    fun `round trips 1 KB`() {
        assertRoundTrip(1 * 1024)
    }

    @Test
    fun `round trips 1 MB`() {
        assertRoundTrip(1 * 1024 * 1024)
    }

    @Test
    fun `round trips 10 MB`() {
        assertRoundTrip(10 * 1024 * 1024)
    }

    @Test
    fun `round trips an empty payload`() {
        val plaintext = ByteArray(0)
        val ciphertext = encrypt(plaintext)

        assertThat(ciphertext).hasLength(17 + 16)
        assertThat(decrypt(ciphertext)).isEmpty()
    }

    @Test
    fun `writes the KPR1 container header and a fresh nonce`() {
        val first = encrypt(byteArrayOf(1, 2, 3))
        val second = encrypt(byteArrayOf(1, 2, 3))

        assertThat(String(first, 0, 4, Charsets.US_ASCII)).isEqualTo("KPR1")
        assertThat(first[4].toInt()).isEqualTo(CryptoConstants.CONTAINER_VERSION)
        assertThat(first.copyOfRange(0, 17)).isNotEqualTo(second.copyOfRange(0, 17))
        assertThat(first.copyOfRange(5, 17)).isNotEqualTo(ByteArray(12))
    }

    @Test
    fun `rejects a wrong key`() {
        val ciphertext = encrypt(byteArrayOf(1, 2, 3))
        val wrong = VaultKey.from(ByteArray(32) { (it + 9).toByte() })
        val out = ByteArrayOutputStream()

        assertThrows(KeeperException.Crypto::class.java) {
            cipher.decrypt(ByteArrayInputStream(ciphertext), out, wrong)
        }
        assertThat(out.size()).isEqualTo(0)
        wrong.close()
    }

    @Test
    fun `rejects tampering in every container region`() {
        val ciphertext = encrypt(ByteArray(1024) { (it % 251).toByte() })

        listOf(
            0, // magic
            4, // version
            5, // nonce
            17, // ciphertext
            ciphertext.lastIndex, // tag
        ).forEach { index ->
            val tampered = ciphertext.copyOf()
            tampered[index] = (tampered[index] + 1).toByte()
            val out = ByteArrayOutputStream()

            assertThrows(KeeperException.Crypto::class.java) {
                cipher.decrypt(ByteArrayInputStream(tampered), out, key)
            }
            assertThat(out.size()).isEqualTo(0)
        }
    }

    @Test
    fun `rejects a truncated container`() {
        assertThrows(KeeperException.Crypto::class.java) {
            cipher.decrypt(ByteArrayInputStream(ByteArray(5)), ByteArrayOutputStream(), key)
        }
    }

    @Test
    fun `does not close caller streams`() {
        val input = TrackingInputStream(byteArrayOf(1, 2, 3))
        val output = TrackingOutputStream()

        cipher.encrypt(input, output, key)
        assertThat(input.closed).isFalse()
        assertThat(output.closed).isFalse()

        val encrypted = output.toByteArray()
        val decryptInput = TrackingInputStream(encrypted)
        val decryptOutput = TrackingOutputStream()
        cipher.decrypt(decryptInput, decryptOutput, key)

        assertThat(decryptInput.closed).isFalse()
        assertThat(decryptOutput.closed).isFalse()
    }

    private fun assertRoundTrip(size: Int) {
        val plaintext = ByteArray(size) { (it % 251).toByte() }
        val ciphertext = encrypt(plaintext)
        val decrypted = decrypt(ciphertext)

        assertThat(ciphertext).hasLength(17 + size + 16)
        assertThat(decrypted).isEqualTo(plaintext)
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        cipher.encrypt(ByteArrayInputStream(plaintext), out, key)
        return out.toByteArray()
    }

    private fun decrypt(ciphertext: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        cipher.decrypt(ByteArrayInputStream(ciphertext), out, key)
        return out.toByteArray()
    }

    private class TrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed: Boolean = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class TrackingOutputStream : ByteArrayOutputStream() {
        var closed: Boolean = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }
}
