package com.keeper.vault.duress.intruder

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One-shot deadline for a camera capture.
 *
 * CameraX can keep retrying a camera that the OS has disabled (for example when the device's
 * sensor-privacy toggle is on) without ever invoking `onCaptureSuccess` or `onError`. The capture
 * service therefore arms this watchdog before touching CameraX and cancels it on every terminal
 * path. The callback runs at most once per [start].
 *
 * The scope and timeout are injected so the timeout path is unit-testable with virtual time.
 */
class CaptureWatchdog(
    private val scope: CoroutineScope,
    private val timeoutMillis: Long,
    private val onTimeout: () -> Unit,
) {
    init {
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
    }

    private var job: Job? = null

    @Volatile
    private var fired: Boolean = false

    /** Cancels any previous deadline and arms a fresh one. */
    @Synchronized
    fun start() {
        job?.cancel()
        fired = false
        job = scope.launch {
            delay(timeoutMillis)
            if (fired) return@launch
            fired = true
            onTimeout()
        }
    }

    /** Cancels the pending deadline. Safe to call more than once. */
    @Synchronized
    fun cancel() {
        job?.cancel()
        job = null
    }
}
