package com.keeper.vault.vault.data

import androidx.room.Database
import androidx.room.RoomDatabase

/** Vault metadata database. Content is not part of this schema. */
@Database(
    entities = [VaultEntryEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun vaultEntryDao(): VaultEntryDao
}
