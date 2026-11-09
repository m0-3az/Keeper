package com.keeper.vault.crypto

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.platform.KeeperException
import org.junit.Assert.assertThrows
import org.junit.Test

class AesGcmBoxTest {
    private val box = AesGcmBox(DefaultSecureRandomSource())
    private val key = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun `seals and opens a payload`() {
        val plaintext = "layer A payload".toByteArray()

        val sealed = box.seal(key, plaintext)
        val opened = box.open(key, sealed)

        assertThat(opened).isEqualTo(plaintext)
    }

    @Test
    fun `uses a fresh nonce for every seal`() {
        val first = box.seal(key, byteArrayOf(1, 2, 3))
        val second = box.seal(key, byteArrayOf(1, 2, 3))

        assertThat(first.copyOfRange(0, 12)).isNotEqualTo(second.copyOfRange(0, 12))
    }

    @Test
    fun `rejects a wrong key`() {
        val sealed = box.seal(key, byteArrayOf(1, 2, 3))
        val wrong = ByteArray(32) { (it + 9).toByte() }

        assertThrows(KeeperException.Crypto::class.java) {
            box.open(wrong, sealed)
        }
    }

    @Test
    fun `rejects tampered nonce and tag`() {
        val sealed = box.seal(key, byteArrayOf(1, 2, 3))

        val badNonce = sealed.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertThrows(KeeperException.Crypto::class.java) {
            box.open(key, badNonce)
        }

        val badTag = sealed.copyOf().also { it[it.lastIndex] = (it[it.lastIndex] + 1).toByte() }
        assertThrows(KeeperException.Crypto::class.java) {
            box.open(key, badTag)
        }
    }

    @Test
    fun `rejects a truncated payload`() {
        assertThrows(KeeperException.Crypto::class.java) {
            box.open(key, ByteArray(20))
        }
    }
}
