package com.keeper.vault.duress.intruder

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import com.keeper.vault.contracts.intruder.IntruderEvent
import com.keeper.vault.contracts.intruder.IntruderTrigger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Metadata for one intruder capture.
 *
 * The image itself is never in Room: only an opaque filename, timestamp, trigger, and id. The
 * metadata is not encrypted with the vault key (it contains no vault content) but it is also not
 * a secret; see `LIMITATIONS.md`.
 */
@Entity(tableName = "intruder_events")
data class IntruderEventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "captured_at") val capturedAtMillis: Long,
    @ColumnInfo(name = "trigger") val trigger: String,
    @ColumnInfo(name = "file_name") val encryptedFileName: String,
) {
    fun toContract(): IntruderEvent? {
        val parsed = IntruderTrigger.entries.firstOrNull { it.name == trigger } ?: return null
        return IntruderEvent(
            id = id,
            capturedAtMillis = capturedAtMillis,
            trigger = parsed,
            encryptedFileName = encryptedFileName,
        )
    }
}

@Dao
interface IntruderEventDao {
    @Query("SELECT * FROM intruder_events ORDER BY captured_at DESC")
    fun observeAll(): Flow<List<IntruderEventEntity>>

    @Query("SELECT * FROM intruder_events WHERE id = :id LIMIT 1")
    suspend fun find(id: String): IntruderEventEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: IntruderEventEntity)

    @Query("SELECT * FROM intruder_events WHERE captured_at < :cutoffMillis")
    suspend fun olderThan(cutoffMillis: Long): List<IntruderEventEntity>

    @Query("SELECT * FROM intruder_events")
    suspend fun all(): List<IntruderEventEntity>

    @Query("DELETE FROM intruder_events WHERE id = :id")
    suspend fun delete(id: String): Int
}

@Database(
    entities = [IntruderEventEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class IntruderDatabase : RoomDatabase() {
    abstract fun intruderEventDao(): IntruderEventDao
}

/**
 * Metadata boundary for the intruder log.
 *
 * This interface exists so the log, retention, and coordinator tests never need Room. The Room
 * adapter is a thin mapping layer covered by the instrumented build.
 */
interface IntruderEventStore {
    fun observe(): Flow<List<IntruderEvent>>

    suspend fun insert(event: IntruderEvent)

    suspend fun find(id: String): IntruderEvent?

    suspend fun olderThan(cutoffMillis: Long): List<IntruderEvent>

    suspend fun all(): List<IntruderEvent>

    suspend fun delete(id: String): Boolean
}

/** Room-backed [IntruderEventStore]. */
class RoomIntruderEventStore(
    private val dao: IntruderEventDao,
) : IntruderEventStore {
    override fun observe(): Flow<List<IntruderEvent>> =
        dao.observeAll().map { rows -> rows.mapNotNull { it.toContract() } }

    override suspend fun insert(event: IntruderEvent) {
        dao.insert(
            IntruderEventEntity(
                id = event.id,
                capturedAtMillis = event.capturedAtMillis,
                trigger = event.trigger.name,
                encryptedFileName = event.encryptedFileName,
            ),
        )
    }

    override suspend fun find(id: String): IntruderEvent? = dao.find(id)?.toContract()

    override suspend fun olderThan(cutoffMillis: Long): List<IntruderEvent> =
        dao.olderThan(cutoffMillis).mapNotNull { it.toContract() }

    override suspend fun all(): List<IntruderEvent> = dao.all().mapNotNull { it.toContract() }

    override suspend fun delete(id: String): Boolean = dao.delete(id) > 0
}
