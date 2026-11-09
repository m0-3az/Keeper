package com.keeper.vault.vault.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.keeper.vault.contracts.vault.VaultEntry

/**
 * Room row for one vault entry.
 *
 * Every column is metadata. There is intentionally no content, BLOB, path, or stream column:
 * ciphertext lives only under `filesDir/vault/<id>.enc`.
 */
@Entity(tableName = "vault_entries")
class VaultEntryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    @ColumnInfo(name = "created_at_millis")
    val createdAtMillis: Long,
    @ColumnInfo(name = "modified_at_millis")
    val modifiedAtMillis: Long,
)

fun VaultEntryEntity.toVaultEntry(): VaultEntry = VaultEntry(
    id = id,
    displayName = displayName,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    createdAtMillis = createdAtMillis,
    modifiedAtMillis = modifiedAtMillis,
)

fun VaultEntry.toEntity(): VaultEntryEntity = VaultEntryEntity(
    id = id,
    displayName = displayName,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    createdAtMillis = createdAtMillis,
    modifiedAtMillis = modifiedAtMillis,
)
