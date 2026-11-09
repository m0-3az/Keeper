package com.keeper.vault.auth

import com.keeper.vault.contracts.platform.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Applies a grace period before an app-background event locks the vault.
 *
 * Backgrounding happens for benign reasons that keep the user in control: the Storage Access
 * Framework picker, a permission dialog, or the biometric prompt. Locking on the first `ON_STOP`
 * wiped the live key while the user was still choosing a file, so the import always failed.
 *
 * The guard keeps the vault unlocked only for [graceMillis]; if the app does not return in time
 * the pending job calls [onGraceExpired], which locks and zeroes the key. Returning to the
 * foreground cancels the timer. Screen-off, failed attempts, and manual locks remain immediate.
 */
class BackgroundLockGuard(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val graceMillis: Long = DEFAULT_GRACE_MILLIS,
    private val onGraceExpired: () -> Unit,
) {
    init {
        require(graceMillis > 0L) { "graceMillis must be positive" }
    }

    private var pending: Job? = null

    @Volatile
    private var backgroundedAtMillis: Long? = null

    /** Starts (or restarts) the grace timer. Safe to call repeatedly. */
    @Synchronized
    fun onBackgrounded() {
        cancelTimer()
        backgroundedAtMillis = clock.nowMillis()
        pending = scope.launch {
            delay(graceMillis)
            val startedAt = backgroundedAtMillis ?: return@launch
            if (clock.nowMillis() - startedAt >= graceMillis) {
                onGraceExpired()
            }
        }
    }

    /** Cancels a pending lock because the user returned within the grace period. */
    @Synchronized
    fun onForegrounded() {
        cancelTimer()
    }

    /** Cancels the timer permanently; used when the installer is removed. */
    @Synchronized
    fun cancel() {
        cancelTimer()
    }

    private fun cancelTimer() {
        pending?.cancel()
        pending = null
        backgroundedAtMillis = null
    }

    companion object {
        /** Time the vault tolerates being backgrounded before the key is wiped. */
        const val DEFAULT_GRACE_MILLIS: Long = 30_000L
    }
}
