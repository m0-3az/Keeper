package com.keeper.vault.duress.duress

import android.content.Context
import androidx.room.Room
import com.keeper.vault.contracts.platform.AppFileStore
import com.keeper.vault.contracts.platform.Clock
import com.keeper.vault.contracts.platform.DispatcherProvider
import com.keeper.vault.contracts.platform.KeeperException
import com.keeper.vault.contracts.vault.VaultRepository
import com.keeper.vault.crypto.AesGcmVaultCipher
import com.keeper.vault.vault.data.EncryptedVaultFileStore
import com.keeper.vault.vault.data.EncryptedVaultRepository
import com.keeper.vault.vault.data.RoomVaultMetadataStore
import com.keeper.vault.vault.data.VaultDatabase
import com.keeper.vault.vault.platform.UuidIdGenerator
import com.keeper.vault.vault.session.VaultSessionHolder
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The decoy vault's storage and live key holder.
 *
 * It reuses the public `:vault` classes with its own Room database name and its own file
 * directory, so no metadata row, file id, or key can be shared with the real vault. The app can
 * install [repository] and route a duress unlock to it; v1 never deletes or modifies the real
 * vault.
 */
interface DecoyVault {
    /** Live decoy key. The holder owns the key and zeroes it on lock. */
    val session: VaultSessionHolder

    fun repository(): VaultRepository

    /** Deletes the decoy database, every decoy file, and the live decoy key. */
    suspend fun destroy()
}

/**
 * Room/file-backed decoy vault.
 *
 * Paths:
 * - database: `keeper-vault-decoy.db` (separate from `keeper-vault.db`);
 * - content: `filesDir/duress/decoy-vault` (separate from `filesDir/vault`).
 *
 * The decoy key envelope itself lives at `filesDir/duress/decoy` and is owned by the duress key
 * manager, not by this class.
 */
class RoomDecoyVault(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DecoyVault {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, DIRECTORY_NAME)

    override val session: VaultSessionHolder = VaultSessionHolder()

    @Volatile
    private var database: VaultDatabase? = null

    override fun repository(): VaultRepository {
        val db = database()
        return EncryptedVaultRepository(
            metadataStore = RoomVaultMetadataStore(db.vaultEntryDao()),
            fileStore = EncryptedVaultFileStore(
                files = DecoyFileStore(directory),
                cipher = AesGcmVaultCipher(),
            ),
            sessionProvider = session,
            clock = Clock { System.currentTimeMillis() },
            idGenerator = UuidIdGenerator(),
            dispatcherProvider = DecoyDispatchers(ioDispatcher),
        )
    }

    override suspend fun destroy() {
        session.lock()
        synchronized(this) {
            database?.close()
            database = null
        }
        deleteDatabaseFiles()
        if (directory.exists() && !directory.deleteRecursively()) {
            throw KeeperException.Duress("Could not delete the decoy vault files")
        }
    }

    @Synchronized
    private fun database(): VaultDatabase {
        database?.let { return it }
        return Room.databaseBuilder(
            appContext,
            VaultDatabase::class.java,
            DATABASE_NAME,
        ).build().also { database = it }
    }

    private fun deleteDatabaseFiles() {
        val dbFile = appContext.getDatabasePath(DATABASE_NAME)
        for (file in listOf(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-shm"))) {
            if (file.exists() && !file.delete()) {
                throw KeeperException.Duress("Could not delete the decoy database")
            }
        }
    }

    private class DecoyFileStore(
        private val directory: File,
    ) : AppFileStore {
        override fun vaultDirectory(): File = directory

        override fun intruderDirectory(): File = directory
    }

    private class DecoyDispatchers(
        ioDispatcher: CoroutineDispatcher,
    ) : DispatcherProvider {
        override val io: CoroutineDispatcher = ioDispatcher
        override val default: CoroutineDispatcher = Dispatchers.Default
        override val main: CoroutineDispatcher = Dispatchers.Main
    }

    companion object {
        const val DATABASE_NAME: String = "keeper-vault-decoy.db"
        const val DIRECTORY_NAME: String = "duress/decoy-vault"
    }
}
