package com.keeper.vault.vault.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Metadata-only DAO. It has no column that could hold file content. */
@Dao
interface VaultEntryDao {
    @Query("SELECT * FROM vault_entries ORDER BY created_at_millis DESC, id DESC")
    fun observeAll(): Flow<List<VaultEntryEntity>>

    @Query("SELECT * FROM vault_entries WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): VaultEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: VaultEntryEntity)

    @Query("DELETE FROM vault_entries WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query(
        "UPDATE vault_entries SET display_name = :displayName, modified_at_millis = :modifiedAtMillis " +
            "WHERE id = :id",
    )
    suspend fun rename(id: String, displayName: String, modifiedAtMillis: Long): Int
}
