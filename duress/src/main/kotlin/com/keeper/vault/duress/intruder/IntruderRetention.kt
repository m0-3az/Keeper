package com.keeper.vault.duress.intruder

import com.keeper.vault.contracts.intruder.IntruderLog
import com.keeper.vault.contracts.intruder.IntruderSettings

/**
 * Retention policy arithmetic, kept separate from WorkManager.
 *
 * `retentionDays == null` means forever: no cutoff and no purge. The cutoff is inclusive-exclusive
 * in the safe direction — [IntruderLog.purgeBefore] deletes events strictly older than the cutoff,
 * so an event exactly at the boundary survives until the next run.
 */
object IntruderRetention {
    const val MILLIS_PER_DAY: Long = 24L * 60L * 60L * 1000L

    fun cutoffFor(settings: IntruderSettings, nowMillis: Long): Long? =
        settings.retentionDays?.let { days -> nowMillis - days * MILLIS_PER_DAY }

    /** Purges according to [settings]. Returns the number of events removed. */
    suspend fun purge(log: IntruderLog, settings: IntruderSettings, nowMillis: Long): Int {
        val cutoff = cutoffFor(settings, nowMillis) ?: return 0
        return log.purgeBefore(cutoff)
    }
}
