package com.keeper.vault.crypto

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.crypto.CryptoConstants
import com.keeper.vault.contracts.crypto.KeyEnvelope
import com.keeper.vault.contracts.platform.KeeperException
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

class FileEnvelopeStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var baseDir: File
    private lateinit var store: FileEnvelopeStore

    @Before
    fun setUp() {
        baseDir = temporaryFolder.newFolder("keeper")
        store = FileEnvelopeStore(baseDir)
    }

    @Test
    fun `returns null when no envelope exists`() {
        assertThat(store.load()).isNull()
    }

    @Test
    fun `saves and loads every envelope field`() {
        val envelope = envelope(iterations = 700_000, marker = 7)
        store.save(envelope)

        val loaded = store.load()

        assertThat(loaded).isNotNull()
        assertThat(loaded!!.version).isEqualTo(1)
        assertThat(loaded.iterations).isEqualTo(700_000)
        assertThat(loaded.salt).isEqualTo(envelope.salt)
        assertThat(loaded.wrappedLayerA).isEqualTo(envelope.wrappedLayerA)
        assertThat(File(baseDir, "envelope.bin").exists()).isTrue()
    }

    @Test
    fun `overwrites an existing envelope`() {
        store.save(envelope(iterations = 600_000, marker = 1))
        store.save(envelope(iterations = 650_000, marker = 2))

        val loaded = store.load()
        assertThat(loaded!!.iterations).isEqualTo(650_000)
        assertThat(loaded.wrappedLayerA.last()).isEqualTo(2)
    }

    @Test
    fun `clear removes the envelope`() {
        store.save(envelope(iterations = 600_000, marker = 3))
        store.clear()

        assertThat(store.load()).isNull()
        assertThat(File(baseDir, "envelope.bin").exists()).isFalse()
    }

    @Test
    fun `rejects a corrupt magic`() {
        store.save(envelope(iterations = 600_000, marker = 4))
        val file = File(baseDir, "envelope.bin")
        val bytes = file.readBytes()
        bytes[0] = 'X'.code.toByte()
        FileOutputStream(file).use { it.write(bytes) }

        assertThrows(KeeperException.Crypto::class.java) {
            store.load()
        }
    }

    @Test
    fun `rejects a truncated envelope`() {
        val file = File(baseDir, "envelope.bin")
        FileOutputStream(file).use { it.write(byteArrayOf(1, 2, 3)) }

        assertThrows(KeeperException.Crypto::class.java) {
            store.load()
        }
    }

    @Test
    fun `rejects an unsupported version`() {
        store.save(envelope(iterations = 600_000, marker = 5))
        patch(offset = 4, value = 2)

        assertThrows(KeeperException.Crypto::class.java) {
            store.load()
        }
    }

    @Test
    fun `rejects a wrong salt length`() {
        store.save(envelope(iterations = 600_000, marker = 6))
        patch(offset = 5, value = 8)

        assertThrows(KeeperException.Crypto::class.java) {
            store.load()
        }
    }

    @Test
    fun `rejects iterations below the floor`() {
        store.save(envelope(iterations = 600_000, marker = 7))
        patchInt(offset = 22, value = 200_000)

        assertThrows(KeeperException.Crypto::class.java) {
            store.load()
        }
    }

    @Test
    fun `rejects an empty wrapped payload`() {
        store.save(envelope(iterations = 600_000, marker = 8))
        patchInt(offset = 26, value = 0)

        assertThrows(KeeperException.Crypto::class.java) {
            store.load()
        }
    }

    @Test
    fun `rejects trailing bytes`() {
        store.save(envelope(iterations = 600_000, marker = 9))
        val file = File(baseDir, "envelope.bin")
        FileOutputStream(file, true).use { it.write(0x42) }

        assertThrows(KeeperException.Crypto::class.java) {
            store.load()
        }
    }

    private fun patch(offset: Int, value: Int) {
        val file = File(baseDir, "envelope.bin")
        val bytes = file.readBytes()
        bytes[offset] = value.toByte()
        FileOutputStream(file).use { it.write(bytes) }
    }

    private fun patchInt(offset: Int, value: Int) {
        val file = File(baseDir, "envelope.bin")
        val bytes = file.readBytes()
        java.nio.ByteBuffer.wrap(bytes).putInt(offset, value)
        FileOutputStream(file).use { it.write(bytes) }
    }

    private fun envelope(iterations: Int, marker: Int): KeyEnvelope = KeyEnvelope(
        version = 1,
        salt = ByteArray(CryptoConstants.SALT_BYTES) { marker.toByte() },
        iterations = iterations,
        wrappedLayerA = ByteArray(40) { marker.toByte() },
    )
}
