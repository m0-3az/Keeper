package com.keeper.vault.duress.intruder

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.intruder.IntruderEvent
import com.keeper.vault.contracts.intruder.IntruderTrigger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Room adapter tests without Room: the DAO is an interface, so a deterministic in-memory DAO
 * exercises every mapping method on the JVM. The generated database itself is excluded as a
 * platform adapter and is covered by the instrumented build.
 */
class IntruderEventStoreTest {

    private class InMemoryDao : IntruderEventDao {
        val rows = MutableStateFlow<List<IntruderEventEntity>>(emptyList())

        override fun observeAll(): Flow<List<IntruderEventEntity>> = rows

        override suspend fun find(id: String): IntruderEventEntity? =
            rows.value.firstOrNull { it.id == id }

        override suspend fun insert(entity: IntruderEventEntity) {
            rows.value = (rows.value.filterNot { it.id == entity.id } + entity)
                .sortedByDescending { it.capturedAtMillis }
        }

        override suspend fun olderThan(cutoffMillis: Long): List<IntruderEventEntity> =
            rows.value.filter { it.capturedAtMillis < cutoffMillis }

        override suspend fun all(): List<IntruderEventEntity> = rows.value

        override suspend fun delete(id: String): Int {
            val next = rows.value.filterNot { it.id == id }
            val removed = next.size != rows.value.size
            rows.value = next
            return if (removed) 1 else 0
        }
    }

    private val dao = InMemoryDao()
    private val store = RoomIntruderEventStore(dao)

    private fun event(id: String, at: Long, trigger: IntruderTrigger = IntruderTrigger.FAILED_UNLOCK) =
        IntruderEvent(id, at, trigger, "$id.enc")

    @Test
    fun `insert maps every field and observe returns metadata only`() = runTest {
        val event = event("one", 10L, IntruderTrigger.FACE_MISMATCH)

        store.insert(event)

        assertThat(dao.rows.value).hasSize(1)
        assertThat(dao.rows.value.single().trigger).isEqualTo("FACE_MISMATCH")
        assertThat(store.observe().first()).containsExactly(event)
    }

    @Test
    fun `find and all decode rows`() = runTest {
        store.insert(event("one", 1L))
        store.insert(event("two", 2L))

        assertThat(store.find("two")).isEqualTo(event("two", 2L))
        assertThat(store.find("missing")).isNull()
        assertThat(store.all()).containsExactly(event("one", 1L), event("two", 2L))
    }

    @Test
    fun `olderThan returns only rows before the cutoff`() = runTest {
        store.insert(event("old", 1_000L))
        store.insert(event("new", 2_000L))

        assertThat(store.olderThan(1_500L)).containsExactly(event("old", 1_000L))
    }

    @Test
    fun `delete reports whether a row existed`() = runTest {
        store.insert(event("one", 1L))

        assertThat(store.delete("one")).isTrue()
        assertThat(store.delete("one")).isFalse()
        assertThat(store.all()).isEmpty()
    }

    @Test
    fun `rows with an unknown trigger are skipped instead of crashing`() = runTest {
        dao.rows.value = listOf(
            IntruderEventEntity("bad", 1L, "NOT_A_TRIGGER", "bad.enc"),
            IntruderEventEntity("good", 2L, "FAILED_UNLOCK", "good.enc"),
        )

        assertThat(store.observe().first()).containsExactly(event("good", 2L))
        assertThat(store.find("bad")).isNull()
        assertThat(store.all()).containsExactly(event("good", 2L))
        assertThat(store.olderThan(3L)).containsExactly(event("good", 2L))
    }
}
