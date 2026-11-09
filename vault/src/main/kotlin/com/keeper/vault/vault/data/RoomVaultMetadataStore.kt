package com.keeper.vault.vault.data

import com.keeper.vault.contracts.vault.VaultEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Room-backed metadata store. The DAO is the only persistence surface. */
class RoomVaultMetadataStore(
    private val dao: VaultEntryDao,
) : VaultMetadataStore {
    override fun observeEntries(): Flow<List<VaultEntry>> =
        dao.observeAll().map { rows -> rows.map { it.toVaultEntry() } }

    override suspend fun find(id: String): VaultEntry? =
        dao.findById(id)?.toVaultEntry()

    override suspend fun insert(entry: VaultEntry) {
        dao.insert(entry.toEntity())
    }

    override suspend fun delete(id: String): Boolean = dao.deleteById(id) > 0

    override suspend fun rename(
        id: String,
        displayName: String,
        modifiedAtMillis: Long,
    ): Boolean = dao.rename(id, displayName, modifiedAtMillis) > 0
}
