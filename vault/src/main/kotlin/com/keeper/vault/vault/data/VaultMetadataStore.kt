package com.keeper.vault.vault.data

import com.keeper.vault.contracts.crypto.VaultKey
import com.keeper.vault.contracts.vault.VaultEntry
import kotlinx.coroutines.flow.Flow
import java.io.InputStream

/**
 * Metadata boundary for the vault. Implementations may only persist [VaultEntry] fields.
 *
 * This interface exists so repository tests can prove that no content ever reaches metadata
 * storage, and so Room stays a replaceable adapter.
 */
interface VaultMetadataStore {
    fun observeEntries(): Flow<List<VaultEntry>>

    suspend fun find(id: String): VaultEntry?

    suspend fun insert(entry: VaultEntry)

    /** @return true when a row was removed. Missing rows are not an error. */
    suspend fun delete(id: String): Boolean

    /** @return true when a row was updated. Missing rows are not an error. */
    suspend fun rename(id: String, displayName: String, modifiedAtMillis: Long): Boolean
}

/**
 * Encrypted-content boundary. Implementations write only AES-GCM ciphertext under the app-private
 * vault directory and return decrypted bytes in RAM.
 */
interface VaultFileStore {
    /** Encrypts [plaintext] to `<id>.enc` and returns the number of plaintext bytes consumed. */
    fun write(id: String, plaintext: InputStream, key: VaultKey): Long

    /** Decrypts `<id>.enc` into RAM. Never creates a cache or temporary plaintext file. */
    fun read(id: String, key: VaultKey): ByteArray

    /** @return true when the ciphertext is gone (deleted or was already absent). */
    fun delete(id: String): Boolean

    fun exists(id: String): Boolean
}
