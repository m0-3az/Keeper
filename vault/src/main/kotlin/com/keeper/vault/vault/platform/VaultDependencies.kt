package com.keeper.vault.vault.platform

import android.content.Context
import androidx.room.Room
import com.keeper.vault.contracts.platform.Clock
import com.keeper.vault.contracts.vault.VaultRepository
import com.keeper.vault.crypto.AesGcmVaultCipher
import com.keeper.vault.vault.data.EncryptedVaultFileStore
import com.keeper.vault.vault.data.EncryptedVaultRepository
import com.keeper.vault.vault.data.RoomVaultMetadataStore
import com.keeper.vault.vault.data.VaultDatabase
import com.keeper.vault.vault.session.VaultSession
import com.keeper.vault.vault.session.VaultSessionProvider

/**
 * Manual dependency wiring for Phase 2.
 *
 * The Activity calls this with the process-wide [VaultSession.provider]. After merge, `:app`
 * may call [repository] directly with the auth-owned session provider.
 */
object VaultDependencies {
    @Volatile
    private var database: VaultDatabase? = null

    fun repository(
        context: Context,
        sessionProvider: VaultSessionProvider = VaultSession.provider,
    ): VaultRepository {
        val appContext = context.applicationContext
        return EncryptedVaultRepository(
            metadataStore = RoomVaultMetadataStore(database(appContext).vaultEntryDao()),
            fileStore = EncryptedVaultFileStore(
                files = ContextAppFileStore(appContext),
                cipher = AesGcmVaultCipher(),
            ),
            sessionProvider = sessionProvider,
            clock = Clock { System.currentTimeMillis() },
            idGenerator = UuidIdGenerator(),
            dispatcherProvider = AndroidDispatcherProvider(),
        )
    }

    /** Closes the singleton database. Intended for instrumentation tests and diagnostics. */
    fun closeDatabase() {
        synchronized(this) {
            database?.close()
            database = null
        }
    }

    private fun database(context: Context): VaultDatabase {
        database?.let { return it }
        return synchronized(this) {
            database ?: Room.databaseBuilder(
                context,
                VaultDatabase::class.java,
                DATABASE_NAME,
            ).build().also { database = it }
        }
    }

    private const val DATABASE_NAME = "keeper-vault.db"
}
