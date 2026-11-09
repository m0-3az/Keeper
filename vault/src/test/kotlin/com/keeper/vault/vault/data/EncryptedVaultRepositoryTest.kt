package com.keeper.vault.vault.data

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.crypto.VaultKey
import com.keeper.vault.contracts.platform.Clock
import com.keeper.vault.contracts.platform.IdGenerator
import com.keeper.vault.contracts.platform.KeeperException
import com.keeper.vault.contracts.vault.VaultEntry
import com.keeper.vault.contracts.vault.VaultReadSession
import com.keeper.vault.crypto.AesGcmVaultCipher
import com.keeper.vault.vault.session.VaultSessionHolder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
class EncryptedVaultRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesRoot: File
    private lateinit var metadata: FakeVaultMetadataStore
    private lateinit var fileStore: EncryptedVaultFileStore
    private lateinit var session: VaultSessionHolder
    private lateinit var key: VaultKey
    private lateinit var repository: EncryptedVaultRepository
    private lateinit var dispatcher: CoroutineDispatcher
    private var now: Long = 1_000L
    private var idCounter: Int = 0

    @Before
    fun setUp() {
        filesRoot = tempFolder.newFolder("files")
        metadata = FakeVaultMetadataStore()
        fileStore = EncryptedVaultFileStore(TestAppFileStore(filesRoot), AesGcmVaultCipher())
        key = testKey()
        session = VaultSessionHolder().apply { setKey(key) }
        dispatcher = UnconfinedTestDispatcher()
        repository = buildRepository()
    }

    @After
    fun tearDown() {
        session.lock()
    }

    private fun buildRepository(
        metadataStore: VaultMetadataStore = metadata,
        fileStoreOverride: VaultFileStore = fileStore,
    ): EncryptedVaultRepository = EncryptedVaultRepository(
        metadataStore = metadataStore,
        fileStore = fileStoreOverride,
        sessionProvider = session,
        clock = Clock { now },
        idGenerator = IdGenerator { "id-${++idCounter}" },
        dispatcherProvider = TestDispatcherProvider(dispatcher),
    )

    private suspend fun readAll(session: VaultReadSession): ByteArray =
        session.use { it.plaintext.readBytes() }

    @Test
    fun importsOneKilobyteAsCiphertextAndOpensFromRam() = runTest(dispatcher) {
        val plaintext = markerBytes(1024)

        val entry = repository.importFile("note.txt", "text/plain", ByteArrayInputStream(plaintext))

        assertThat(entry.id).isEqualTo("id-1")
        assertThat(entry.displayName).isEqualTo("note.txt")
        assertThat(entry.mimeType).isEqualTo("text/plain")
        assertThat(entry.sizeBytes).isEqualTo(1024L)
        assertThat(entry.createdAtMillis).isEqualTo(1_000L)
        assertThat(metadata.entries).containsExactly(entry)
        assertThat(repository.observeEntries().first()).containsExactly(entry)

        val file = vaultFile(filesRoot, entry.id)
        assertThat(file.isFile).isTrue()
        assertThat(String(file.readBytes(), Charsets.ISO_8859_1)).doesNotContain("KEEPER_TEST_MARKER")

        val opened = repository.open(entry.id)
        assertThat(opened.entry).isEqualTo(entry)
        assertThat(readAll(opened)).isEqualTo(plaintext)
        assertThat(File(filesRoot, "vault").listFiles()!!.map { it.name }).containsExactly("id-1.enc")
    }

    @Test
    fun importsAndOpensTenMegabytes() = runTest(dispatcher) {
        val plaintext = markerBytes(10 * 1024 * 1024)

        val entry = repository.importFile("large.bin", "application/octet-stream", ByteArrayInputStream(plaintext))

        assertThat(entry.sizeBytes).isEqualTo(10L * 1024L * 1024L)
        assertThat(readAll(repository.open(entry.id))).isEqualTo(plaintext)
        assertThat(vaultFile(filesRoot, entry.id).length()).isGreaterThan(plaintext.size.toLong())

        repository.delete(entry.id)

        assertThat(metadata.entries).isEmpty()
        assertThat(vaultFile(filesRoot, entry.id).exists()).isFalse()
    }

    @Test
    fun openingWithWrongKeyFails() = runTest(dispatcher) {
        val entry = repository.importFile("note.txt", "text/plain", ByteArrayInputStream(markerBytes(1024)))
        session.setKey(testKey(seed = 9))

        val failure = runCatching { repository.open(entry.id) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(KeeperException.Crypto::class.java)
        assertThat(vaultFile(filesRoot, entry.id).exists()).isTrue()
    }

    @Test
    fun tamperedCiphertextFailsToOpen() = runTest(dispatcher) {
        val entry = repository.importFile("note.txt", "text/plain", ByteArrayInputStream(markerBytes(1024)))
        val file = vaultFile(filesRoot, entry.id)
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        file.writeBytes(bytes)

        val failure = runCatching { repository.open(entry.id) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(KeeperException.Crypto::class.java)
    }

    @Test
    fun deleteRemovesMetadataAndCiphertext() = runTest(dispatcher) {
        val entry = repository.importFile("note.txt", "text/plain", ByteArrayInputStream(markerBytes(1024)))

        repository.delete(entry.id)

        assertThat(metadata.entries).isEmpty()
        assertThat(vaultFile(filesRoot, entry.id).exists()).isFalse()
        assertThat(repository.observeEntries().first()).isEmpty()
    }

    @Test
    fun deleteMissingEntryIsANoOp() = runTest(dispatcher) {
        repository.delete("never-existed")

        assertThat(metadata.entries).isEmpty()
    }

    @Test
    fun deleteFailureKeepsMetadata() = runTest(dispatcher) {
        val refusingFileStore = object : VaultFileStore by fileStore {
            override fun delete(id: String): Boolean = false
        }
        val refusingRepository = buildRepository(fileStoreOverride = refusingFileStore)
        val entry = refusingRepository.importFile(
            "note.txt",
            "text/plain",
            ByteArrayInputStream(markerBytes(32)),
        )

        val failure = runCatching { refusingRepository.delete(entry.id) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(KeeperException.Vault::class.java)
        assertThat(metadata.entries).containsExactly(entry)
    }

    @Test
    fun closingAReadSessionTwiceIsSafe() = runTest(dispatcher) {
        val entry = repository.importFile("note.txt", "text/plain", ByteArrayInputStream(markerBytes(32)))

        val opened = repository.open(entry.id)
        opened.close()
        opened.close()

        assertThat(opened.entry).isEqualTo(entry)
    }

    @Test
    fun closingAReadSessionZeroesTheRemainingBuffer() = runTest(dispatcher) {
        val plaintext = markerBytes(64)
        val entry = repository.importFile("note.txt", "text/plain", ByteArrayInputStream(plaintext))

        val opened = repository.open(entry.id)
        assertThat(opened.plaintext.read()).isEqualTo(plaintext[0].toInt() and 0xFF)
        opened.close()
        val remaining = opened.plaintext.readBytes()

        assertThat(remaining.toList()).containsExactlyElementsIn(List(remaining.size) { 0.toByte() })
    }

    @Test
    fun renameChangesMetadataOnly() = runTest(dispatcher) {
        val entry = repository.importFile(
            "note.txt",
            "text/plain",
            ByteArrayInputStream(markerBytes(128)),
        )
        now = 2_000L

        repository.rename(entry.id, "renamed.txt")

        val renamed = metadata.entries.single()
        assertThat(renamed.displayName).isEqualTo("renamed.txt")
        assertThat(renamed.modifiedAtMillis).isEqualTo(2_000L)
        assertThat(renamed.createdAtMillis).isEqualTo(1_000L)
        assertThat(renamed.sizeBytes).isEqualTo(128L)
        assertThat(readAll(repository.open(entry.id))).isEqualTo(markerBytes(128))
    }

    @Test
    fun renameMissingEntryFails() = runTest(dispatcher) {
        val failure = runCatching { repository.rename("missing", "name.txt") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(KeeperException.Vault::class.java)
    }

    @Test
    fun lockedSessionRejectsImportAndOpen() = runTest(dispatcher) {
        val entry = repository.importFile("note.txt", "text/plain", ByteArrayInputStream(markerBytes(32)))
        session.lock()

        val importFailure = runCatching {
            repository.importFile("locked.txt", "text/plain", ByteArrayInputStream(markerBytes(8)))
        }.exceptionOrNull()
        val openFailure = runCatching { repository.open(entry.id) }.exceptionOrNull()

        assertThat(importFailure).isInstanceOf(KeeperException.Auth::class.java)
        assertThat(openFailure).isInstanceOf(KeeperException.Auth::class.java)
        assertThat(File(filesRoot, "vault").listFiles()!!.map { it.name }).containsExactly("id-1.enc")
    }

    @Test
    fun openMissingEntryFails() = runTest(dispatcher) {
        val failure = runCatching { repository.open("missing") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(KeeperException.Vault::class.java)
    }

    @Test
    fun sourceFailureLeavesNeitherMetadataNorCiphertext() = runTest(dispatcher) {
        val failing = object : InputStream() {
            private var remaining = 32

            override fun read(): Int {
                if (remaining > 0) {
                    remaining--
                    return 3
                }
                throw IOException("source failed")
            }
        }

        val failure = runCatching {
            repository.importFile("broken.bin", "application/octet-stream", failing)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(KeeperException.Vault::class.java)
        assertThat(metadata.entries).isEmpty()
        assertThat(File(filesRoot, "vault").listFiles().orEmpty().toList()).isEmpty()
    }

    @Test
    fun metadataFailureRemovesCiphertext() = runTest(dispatcher) {
        val failingMetadata = object : VaultMetadataStore by metadata {
            override suspend fun insert(entry: VaultEntry) {
                throw KeeperException.Vault("metadata write failed")
            }
        }
        val failingRepository = buildRepository(failingMetadata)

        val failure = runCatching {
            failingRepository.importFile("note.txt", "text/plain", ByteArrayInputStream(markerBytes(32)))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(KeeperException.Vault::class.java)
        assertThat(metadata.entries).isEmpty()
        assertThat(File(filesRoot, "vault").listFiles().orEmpty().toList()).isEmpty()
    }

    @Test
    fun roomEntityContainsMetadataFieldsOnly() {
        val fieldTypes = VaultEntryEntity::class.java.declaredFields
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.type }

        assertThat(fieldTypes).containsExactly(
            String::class.java,
            String::class.java,
            String::class.java,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
        )
        assertThat(fieldTypes).containsNoneOf(ByteArray::class.java, InputStream::class.java)
    }
}
