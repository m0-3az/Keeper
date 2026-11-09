package com.keeper.vault.contracts.vault

import kotlinx.coroutines.flow.Flow
import java.io.InputStream

/**
 * Vault entry metadata. This is the only vault data allowed in Room.
 *
 * [displayName] and [mimeType] are user-visible metadata and are **not encrypted** by the vault
 * key. File content is always ciphertext. See `LIMITATIONS.md`.
 *
 * @property id opaque identifier; also the on-disk ciphertext filename stem.
 * @property sizeBytes size of the original plaintext, for display only.
 */
data class VaultEntry(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAtMillis: Long,
    val modifiedAtMillis: Long,
)

/**
 * An open, decrypted view of one entry.
 *
 * The [plaintext] stream is backed by RAM, not a temporary file. Closing the session zeroes or
 * releases the decrypted buffer as far as the JVM allows and closes the stream.
 *
 * Callers must keep the session open only while the content is on screen and must close it when
 * the vault locks.
 */
interface VaultReadSession : AutoCloseable {
    val entry: VaultEntry
    val plaintext: InputStream

    override fun close()
}

/**
 * The vault's only public data boundary.
 *
 * Invariants:
 * - every write goes through `VaultCipher.encrypt` before touching disk;
 * - every read decrypts into RAM and never into a cache/temp file;
 * - Room holds metadata only;
 * - deleting an entry removes both metadata and ciphertext atomically enough that a crash cannot
 *   leave a dangling plaintext file (there is never one).
 *
 * All methods are safe to call from `Dispatchers.IO` and must be cancellable.
 */
interface VaultRepository {
    /** Emits metadata only. Never emits content. */
    fun observeEntries(): Flow<List<VaultEntry>>

    /**
     * Copies [source] into the vault as AES-256-GCM ciphertext.
     *
     * The caller supplies a SAF stream; this method must not create a plaintext temporary file.
     * The original file outside the vault is not deleted.
     */
    suspend fun importFile(
        displayName: String,
        mimeType: String,
        source: InputStream,
    ): VaultEntry

    /** Opens one entry for in-memory reading. */
    suspend fun open(id: String): VaultReadSession

    /** Deletes metadata and ciphertext. Missing entries are a no-op. */
    suspend fun delete(id: String)

    /** Renames metadata only. Content is unchanged. */
    suspend fun rename(id: String, displayName: String)
}
