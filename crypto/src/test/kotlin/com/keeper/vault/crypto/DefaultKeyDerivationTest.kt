package com.keeper.vault.crypto

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.crypto.CryptoConstants
import com.keeper.vault.contracts.platform.SecretZeroizer
import org.junit.Assert.assertThrows
import org.junit.Test

class DefaultKeyDerivationTest {
    private val subject = DefaultKeyDerivation()

    @Test
    fun `matches the independent PBKDF2-HMAC-SHA256 vector at 600k`() {
        val out = subject.derive(
            secret = "password".toCharArray(),
            salt = "0123456789abcdef".toByteArray(),
            iterations = CryptoConstants.MIN_PBKDF2_ITERATIONS,
        )

        assertThat(out.toHex()).isEqualTo(
            "996d7c90f74a4a169cf7adef42b06848f7d1bac3e568d1cc94d4f79be1ee0263",
        )
        assertThat(out).hasLength(CryptoConstants.VAULT_KEY_BYTES)
        SecretZeroizer.zero(out)
    }

    @Test
    fun `is deterministic for the same secret salt and iterations`() {
        val first = subject.derive("correct horse".toCharArray(), ByteArray(16) { 3 }, 600_000)
        val second = subject.derive("correct horse".toCharArray(), ByteArray(16) { 3 }, 600_000)

        assertThat(first).isEqualTo(second)
        SecretZeroizer.zero(first)
        SecretZeroizer.zero(second)
    }

    @Test
    fun `rejects iterations below the 600k floor`() {
        assertThrows(IllegalArgumentException::class.java) {
            subject.derive("secret".toCharArray(), ByteArray(16), 599_999)
        }
    }

    @Test
    fun `rejects a short salt`() {
        assertThrows(IllegalArgumentException::class.java) {
            subject.derive("secret".toCharArray(), ByteArray(8), 600_000)
        }
    }

    @Test
    fun `rejects an empty secret`() {
        assertThrows(IllegalArgumentException::class.java) {
            subject.derive(CharArray(0), ByteArray(16), 600_000)
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
