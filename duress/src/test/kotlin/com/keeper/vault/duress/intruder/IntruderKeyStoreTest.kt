package com.keeper.vault.duress.intruder

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.platform.KeeperException
import com.keeper.vault.crypto.DefaultSecureRandomSource
import com.keeper.vault.duress.FakeKeystoreEnvelope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IntruderKeyStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var directory: File
    private lateinit var keyFile: File
    private lateinit var envelope: FakeKeystoreEnvelope
    private lateinit var store: KeystoreIntruderKeyStore

    @Before
    fun setUp() {
        directory = temp.newFolder("intruder")
        keyFile = File(directory, "key.bin")
        envelope = FakeKeystoreEnvelope()
        store = KeystoreIntruderKeyStore(
            envelope = envelope,
            keyFile = keyFile,
            captureDirectory = directory,
            random = DefaultSecureRandomSource(),
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @Test
    fun `encrypt then decrypt round trips`() = runTest {
        val plaintext = "still-image-bytes".toByteArray()

        val ciphertext = store.encrypt(plaintext)
        val recovered = store.decrypt(ciphertext)

        assertThat(recovered).isEqualTo(plaintext)
        assertThat(ciphertext).isNotEqualTo(plaintext)
        assertThat(keyFile.isFile).isTrue()
    }

    @Test
    fun `each image gets a fresh nonce`() = runTest {
        val plaintext = ByteArray(64) { it.toByte() }

        val first = store.encrypt(plaintext)
        val second = store.encrypt(plaintext)

        assertThat(first).isNotEqualTo(second)
        assertThat(store.decrypt(first)).isEqualTo(plaintext)
        assertThat(store.decrypt(second)).isEqualTo(plaintext)
    }

    @Test
    fun `ciphertext does not contain the plaintext marker`() = runTest {
        val marker = "SECRET-JPEG-PAYLOAD".toByteArray()
        val plaintext = marker + ByteArray(128) { 7 }

        val ciphertext = store.encrypt(plaintext)

        assertThat(ciphertext.containsSubsequence(marker)).isFalse()
    }

    @Test
    fun `a tampered image fails authentication`() = runTest {
        val ciphertext = store.encrypt("image".toByteArray())
        ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 0xFF).toByte()

        val failure = runCatching { store.decrypt(ciphertext) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(KeeperException.Crypto::class.java)
    }

    @Test
    fun `a missing or deleted keystore key fails closed`() = runTest {
        val ciphertext = store.encrypt("image".toByteArray())
        envelope.deleteKey()

        val failure = runCatching { store.decrypt(ciphertext) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(KeeperException.Crypto::class.java)
    }

    @Test
    fun `a different key cannot decrypt the image`() = runTest {
        val ciphertext = store.encrypt("image".toByteArray())
        val otherDirectory = temp.newFolder("other")
        val otherStore = KeystoreIntruderKeyStore(
            envelope = FakeKeystoreEnvelope(),
            keyFile = File(otherDirectory, "key.bin"),
            captureDirectory = otherDirectory,
            random = DefaultSecureRandomSource(),
            ioDispatcher = Dispatchers.Unconfined,
        )
        otherStore.encrypt("warm up".toByteArray())

        val failure = runCatching { otherStore.decrypt(ciphertext) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(KeeperException.Crypto::class.java)
    }

    @Test
    fun `a corrupt wrapped key file fails closed instead of silently rotating`() = runTest {
        store.encrypt("image".toByteArray())
        keyFile.writeText("not a wrapped key")

        val failure = runCatching { store.encrypt("next".toByteArray()) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(KeeperException.Crypto::class.java)
    }

    @Test
    fun `destroy deletes the wrapped key and every capture ciphertext`() = runTest {
        val ciphertext = store.encrypt("image".toByteArray())
        File(directory, "one.enc").writeBytes(ciphertext)
        File(directory, "two.enc").writeBytes(ciphertext)
        File(directory, "unrelated.txt").writeText("keep me")

        store.destroy()

        assertThat(keyFile.exists()).isFalse()
        assertThat(File(directory, "one.enc").exists()).isFalse()
        assertThat(File(directory, "two.enc").exists()).isFalse()
        assertThat(File(directory, "unrelated.txt").exists()).isTrue()
    }

    @Test
    fun `capture after destroy is re-encrypted with a new key`() = runTest {
        val first = store.encrypt("image".toByteArray())
        store.destroy()

        val second = store.encrypt("image".toByteArray())

        assertThat(store.decrypt(second)).isEqualTo("image".toByteArray())
        val failure = runCatching { store.decrypt(first) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(KeeperException.Crypto::class.java)
    }

    private fun ByteArray.containsSubsequence(sequence: ByteArray): Boolean {
        if (sequence.isEmpty()) return true
        if (size < sequence.size) return false
        outer@ for (start in 0..(size - sequence.size)) {
            for (offset in sequence.indices) {
                if (this[start + offset] != sequence[offset]) continue@outer
            }
            return true
        }
        return false
    }
}
