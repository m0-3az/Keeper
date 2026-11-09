package com.keeper.vault.duress.intruder

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.duress.MutableClock
import com.keeper.vault.duress.enabledSettings
import kotlinx.coroutines.test.runTest
import org.junit.Test

class IntruderRetentionTest {

    @Test
    fun `seven day retention uses a seven day cutoff`() {
        val now = 1_700_000_000_000L

        val cutoff = IntruderRetention.cutoffFor(enabledSettings(retentionDays = 7), now)

        assertThat(cutoff).isEqualTo(now - 7 * IntruderRetention.MILLIS_PER_DAY)
    }

    @Test
    fun `thirty and ninety day retention are exact`() {
        val now = 1_700_000_000_000L

        assertThat(IntruderRetention.cutoffFor(enabledSettings(retentionDays = 30), now))
            .isEqualTo(now - 30 * IntruderRetention.MILLIS_PER_DAY)
        assertThat(IntruderRetention.cutoffFor(enabledSettings(retentionDays = 90), now))
            .isEqualTo(now - 90 * IntruderRetention.MILLIS_PER_DAY)
    }

    @Test
    fun `forever retention has no cutoff and purges nothing`() = runTest {
        val log = ImmediateIntruderLog()

        val purged = IntruderRetention.purge(
            log = log,
            settings = enabledSettings(retentionDays = null),
            nowMillis = 1_700_000_000_000L,
        )

        assertThat(IntruderRetention.cutoffFor(enabledSettings(retentionDays = null), 1L)).isNull()
        assertThat(purged).isEqualTo(0)
        assertThat(log.purges).isEmpty()
    }

    @Test
    fun `purge delegates the computed cutoff to the log`() = runTest {
        val now = 1_700_000_000_000L
        val log = ImmediateIntruderLog(purged = 3)

        val purged = IntruderRetention.purge(
            log = log,
            settings = enabledSettings(retentionDays = 30),
            nowMillis = now,
        )

        assertThat(purged).isEqualTo(3)
        assertThat(log.purges).containsExactly(now - 30 * IntruderRetention.MILLIS_PER_DAY)
    }
}

/** Minimal [com.keeper.vault.contracts.intruder.IntruderLog] test double. */
class ImmediateIntruderLog(
    private val purged: Int = 0,
) : com.keeper.vault.contracts.intruder.IntruderLog {
    val purges = mutableListOf<Long>()

    override fun observe(): kotlinx.coroutines.flow.Flow<List<com.keeper.vault.contracts.intruder.IntruderEvent>> =
        kotlinx.coroutines.flow.flowOf(emptyList())

    override suspend fun readImage(eventId: String): ByteArray = ByteArray(0)

    override suspend fun purgeBefore(cutoffMillis: Long): Int {
        purges += cutoffMillis
        return purged
    }

    override suspend fun deleteAll() = Unit
}
