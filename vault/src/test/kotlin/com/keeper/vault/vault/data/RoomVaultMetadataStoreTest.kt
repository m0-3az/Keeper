package com.keeper.vault.vault.data

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.vault.VaultEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RoomVaultMetadataStoreTest {
    private val dao = FakeVaultEntryDao()
    private val store = RoomVaultMetadataStore(dao)

    @Test
    fun observeMapsRowsToContractEntries() = runTest {
        dao.insert(VaultEntryEntity("a", "Alpha", "text/plain", 1, 10, 11))
        dao.insert(VaultEntryEntity("b", "Beta", "image/png", 2, 20, 21))

        val entries = store.observeEntries().first()

        assertThat(entries).containsExactly(
            VaultEntry("a", "Alpha", "text/plain", 1, 10, 11),
            VaultEntry("b", "Beta", "image/png", 2, 20, 21),
        )
    }

    @Test
    fun insertAndFindMapBothDirections() = runTest {
        val entry = VaultEntry("a", "Alpha", "text/plain", 7, 10, 11)

        store.insert(entry)

        assertThat(dao.rows.value.single().displayName).isEqualTo("Alpha")
        assertThat(store.find("a")).isEqualTo(entry)
        assertThat(store.find("missing")).isNull()
    }

    @Test
    fun deleteReportsWhetherARowWasRemoved() = runTest {
        dao.insert(VaultEntryEntity("a", "Alpha", "text/plain", 1, 10, 11))

        assertThat(store.delete("a")).isTrue()
        assertThat(store.delete("a")).isFalse()
        assertThat(dao.rows.value).isEmpty()
    }

    @Test
    fun renameUpdatesDisplayNameAndTimestampOnly() = runTest {
        dao.insert(VaultEntryEntity("a", "Alpha", "text/plain", 1, 10, 11))

        assertThat(store.rename("a", "Renamed", 99)).isTrue()
        assertThat(store.rename("missing", "Nope", 99)).isFalse()

        val row = dao.rows.value.single()
        assertThat(row.displayName).isEqualTo("Renamed")
        assertThat(row.modifiedAtMillis).isEqualTo(99)
        assertThat(row.createdAtMillis).isEqualTo(10)
        assertThat(row.sizeBytes).isEqualTo(1)
    }

    private class FakeVaultEntryDao : VaultEntryDao {
        val rows = MutableStateFlow<List<VaultEntryEntity>>(emptyList())

        override fun observeAll(): Flow<List<VaultEntryEntity>> = rows

        override suspend fun findById(id: String): VaultEntryEntity? =
            rows.value.firstOrNull { it.id == id }

        override suspend fun insert(entity: VaultEntryEntity) {
            rows.value = rows.value.filterNot { it.id == entity.id } + entity
        }

        override suspend fun deleteById(id: String): Int {
            val before = rows.value
            rows.value = before.filterNot { it.id == id }
            return before.size - rows.value.size
        }

        override suspend fun rename(
            id: String,
            displayName: String,
            modifiedAtMillis: Long,
        ): Int {
            var changed = 0
            rows.value = rows.value.map {
                if (it.id == id) {
                    changed = 1
                    VaultEntryEntity(
                        id = it.id,
                        displayName = displayName,
                        mimeType = it.mimeType,
                        sizeBytes = it.sizeBytes,
                        createdAtMillis = it.createdAtMillis,
                        modifiedAtMillis = modifiedAtMillis,
                    )
                } else {
                    it
                }
            }
            return changed
        }
    }
}
