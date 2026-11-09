package com.keeper.vault.vault.data

import com.keeper.vault.contracts.crypto.VaultKey
import com.keeper.vault.contracts.platform.Clock
import com.keeper.vault.contracts.platform.DispatcherProvider
import com.keeper.vault.contracts.platform.IdGenerator
import com.keeper.vault.contracts.platform.KeeperException
import com.keeper.vault.contracts.platform.SecretZeroizer
import com.keeper.vault.contracts.vault.VaultEntry
import com.keeper.vault.contracts.vault.VaultReadSession
import com.keeper.vault.contracts.vault.VaultRepository
import com.keeper.vault.vault.session.VaultSessionProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Default [VaultRepository].
 *
 * Metadata goes to [VaultMetadataStore]; content goes only to [VaultFileStore] as ciphertext.
 * Decrypted content exists only in an [InMemoryVaultReadSession] buffer.
 */
class EncryptedVaultRepository(
    private val metadataStore: VaultMetadataStore,
    private val fileStore: VaultFileStore,
    private val sessionProvider: VaultSessionProvider,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    private val dispatcherProvider: DispatcherProvider,
) : VaultRepository {
    override fun observeEntries(): Flow<List<VaultEntry>> = metadataStore.observeEntries()

    override suspend fun importFile(
        displayName: String,
        mimeType: String,
        source: InputStream,
    ): VaultEntry = withContext(dispatcherProvider.io) {
        val key = requireKey()
        val id = idGenerator.newId()
        val now = clock.nowMillis()
        val sizeBytes = fileStore.write(id, source, key)
        val entry = VaultEntry(
            id = id,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            createdAtMillis = now,
            modifiedAtMillis = now,
        )
        try {
            metadataStore.insert(entry)
        } catch (failure: Throwable) {
            deleteQuietly(id)
            throw failure
        }
        entry
    }

    override suspend fun open(id: String): VaultReadSession = withContext(dispatcherProvider.io) {
        val key = requireKey()
        val entry = metadataStore.find(id)
            ?: throw KeeperException.Vault("Entry not found")
        val plaintext = fileStore.read(id, key)
        InMemoryVaultReadSession(entry, plaintext)
    }

    override suspend fun delete(id: String) = withContext(dispatcherProvider.io) {
        if (!fileStore.delete(id)) {
            throw KeeperException.Vault("Could not delete encrypted entry")
        }
        metadataStore.delete(id)
        Unit
    }

    override suspend fun rename(id: String, displayName: String) = withContext(dispatcherProvider.io) {
        val updated = metadataStore.rename(id, displayName, clock.nowMillis())
        if (!updated) {
            throw KeeperException.Vault("Entry not found")
        }
        Unit
    }

    private fun requireKey(): VaultKey =
        sessionProvider.currentKey() ?: throw KeeperException.Auth("Vault is locked")

    private fun deleteQuietly(id: String) {
        try {
            fileStore.delete(id)
        } catch (_: Exception) {
            // The original metadata failure is the useful error; the orphan is ciphertext only.
        }
    }
}

/** RAM-backed session. Closing zeroes the decrypted buffer as far as the JVM allows. */
internal class InMemoryVaultReadSession(
    override val entry: VaultEntry,
    plaintextBytes: ByteArray,
) : VaultReadSession {
    private val buffer: ByteArray = plaintextBytes
    private val stream = ByteArrayInputStream(buffer)
    private var closed = false

    override val plaintext: InputStream
        get() = stream

    override fun close() {
        if (closed) return
        closed = true
        stream.close()
        SecretZeroizer.zero(buffer)
    }
}
