package com.keeper.vault.duress.intruder

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.platform.KeeperException
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IntruderImageStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var directory: File
    private lateinit var store: FileIntruderImageStore

    @Before
    fun setUp() {
        directory = File(temp.root, "intruder")
        store = FileIntruderImageStore(directory)
    }

    @Test
    fun `write creates the directory and read returns the ciphertext`() {
        val ciphertext = byteArrayOf(1, 2, 3, 4)

        store.write("one.enc", ciphertext)

        assertThat(directory.isDirectory).isTrue()
        assertThat(store.read("one.enc")).isEqualTo(ciphertext)
        assertThat(store.listCiphertext()).containsExactly("one.enc")
    }

    @Test
    fun `invalid file names are rejected`() {
        val invalid = listOf("", ".", "..", "one.txt", "../one.enc", "dir/one.enc", "one\\two.enc")

        for (name in invalid) {
            val failure = runCatching { store.write(name, byteArrayOf(1)) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(KeeperException.Crypto::class.java)
        }
        assertThat(directory.exists()).isFalse()
    }

    @Test
    fun `empty ciphertext is rejected`() {
        val failure = runCatching { store.write("one.enc", ByteArray(0)) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a missing file fails closed`() {
        val failure = runCatching { store.read("missing.enc") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(KeeperException.Crypto::class.java)
    }

    @Test
    fun `delete is idempotent`() {
        store.write("one.enc", byteArrayOf(1))

        assertThat(store.delete("one.enc")).isTrue()
        assertThat(store.delete("one.enc")).isTrue()
    }

    @Test
    fun `deleteAll removes only capture files and counts them`() {
        store.write("one.enc", byteArrayOf(1))
        store.write("two.enc", byteArrayOf(2))
        File(directory, "key.bin").writeText("wrapped key")
        File(directory, "note.txt").writeText("keep")

        val deleted = store.deleteAll()

        assertThat(deleted).isEqualTo(2)
        assertThat(store.listCiphertext()).isEmpty()
        assertThat(File(directory, "key.bin").exists()).isTrue()
        assertThat(File(directory, "note.txt").exists()).isTrue()
    }

    @Test
    fun `deleteAll on a missing directory is a no-op`() {
        assertThat(store.listCiphertext()).isEmpty()
        assertThat(store.deleteAll()).isEqualTo(0)
    }
}
