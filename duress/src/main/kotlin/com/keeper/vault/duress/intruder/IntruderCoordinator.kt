package com.keeper.vault.duress.intruder

import com.keeper.vault.contracts.intruder.IntruderEvent
import com.keeper.vault.contracts.intruder.IntruderSettingsStore
import com.keeper.vault.contracts.intruder.IntruderTrigger
import com.keeper.vault.contracts.isolation.TamperKind
import com.keeper.vault.contracts.isolation.TamperLog
import com.keeper.vault.contracts.isolation.TamperSignal
import com.keeper.vault.contracts.platform.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Pending warning produced by a failed-attempt count; it does not capture by itself. */
data class IntruderWarningRequest(val failures: Int)

/**
 * Decides when a capture may be *offered* and when it may actually run.
 *
 * The unlock screen calls [requestWarning] when the persisted failure counter reaches the
 * configured threshold. That only exposes a warning. [confirmCapture] runs only after the user
 * explicitly confirms, and is guarded against double confirmation here and by the one-shot
 * [CaptureConfirmationGate] in the service.
 *
 * Face-mismatch capture is disabled in this release: there is no visible confirmation flow for a
 * background mismatch, so [onFaceMismatch] never captures.
 *
 * Rate limits are unchanged. A cancelled warning consumes nothing; only a successful capture
 * advances the rate limiter and the daily cap.
 */
class IntruderCoordinator(
    private val settings: IntruderSettingsStore,
    private val capture: ConfirmedIntruderCapture,
    private val tamperLog: TamperLog?,
    private val clock: Clock,
    private val repeatIntervalMillis: Long = DEFAULT_REPEAT_INTERVAL_MILLIS,
    private val maxCapturesPerDay: Int = DEFAULT_MAX_CAPTURES_PER_DAY,
) {
    init {
        require(repeatIntervalMillis >= 0L) { "repeatIntervalMillis must not be negative" }
        require(maxCapturesPerDay > 0) { "maxCapturesPerDay must be positive" }
    }

    private val mutex = Mutex()
    private val captureTimes = mutableListOf<Long>()
    private var lastCaptureAtMillis: Long? = null
    private var captureInFlight = false

    /**
     * Returns a warning request when the failure counter has reached the threshold and the
     * feature is enabled. It does not consume rate limits and does not start the camera.
     */
    suspend fun requestWarning(failures: Int): IntruderWarningRequest? = mutex.withLock {
        if (failures < 1) return@withLock null
        val config = settings.settings.value
        if (!config.enabled) return@withLock null
        if (failures < config.failureThreshold) return@withLock null
        if (!withinLimits(clock.nowMillis())) return@withLock null
        IntruderWarningRequest(failures)
    }

    /**
     * Runs a capture only after the unlock UI confirmed the warning. The settings and rate limits
     * are checked again so a stale confirmation cannot bypass a later disable/limit.
     */
    suspend fun confirmCapture(
        request: IntruderWarningRequest,
        confirmationToken: String,
    ): IntruderEvent? = mutex.withLock {
        if (captureInFlight) {
            CaptureConfirmationGate.revoke(confirmationToken)
            return@withLock null
        }
        val config = settings.settings.value
        if (!config.enabled || request.failures < config.failureThreshold) {
            CaptureConfirmationGate.revoke(confirmationToken)
            return@withLock null
        }
        if (!withinLimits(clock.nowMillis())) {
            CaptureConfirmationGate.revoke(confirmationToken)
            return@withLock null
        }

        captureInFlight = true
        try {
            captureLocked(
                trigger = IntruderTrigger.FAILED_UNLOCK,
                confirmationToken = confirmationToken,
            )
        } finally {
            captureInFlight = false
        }
    }

    /** Face-mismatch capture is intentionally disabled in this release. */
    suspend fun onFaceMismatch(): IntruderEvent? = null

    /** Clears in-memory rate-limit state. The app may call this after a successful unlock. */
    fun resetWindow() {
        lastCaptureAtMillis = null
        captureTimes.clear()
    }

    private fun withinLimits(now: Long): Boolean {
        val last = lastCaptureAtMillis
        // A backwards clock is treated as "inside the window": conservative, not bypassable.
        if (last != null && now - last < repeatIntervalMillis) return false
        captureTimes.removeAll { now - it >= IntruderRetention.MILLIS_PER_DAY }
        return captureTimes.size < maxCapturesPerDay
    }

    private suspend fun captureLocked(
        trigger: IntruderTrigger,
        confirmationToken: String,
    ): IntruderEvent? {
        val now = clock.nowMillis()
        if (!withinLimits(now)) {
            CaptureConfirmationGate.revoke(confirmationToken)
            return null
        }

        val event = try {
            capture.captureConfirmed(trigger, confirmationToken)
        } catch (e: CancellationException) {
            CaptureConfirmationGate.revoke(confirmationToken)
            throw e
        } catch (_: Exception) {
            null
        }
        if (event == null) {
            CaptureConfirmationGate.revoke(confirmationToken)
            return null
        }

        lastCaptureAtMillis = now
        captureTimes += now
        appendTamper(trigger, now)
        return event
    }

    private suspend fun appendTamper(trigger: IntruderTrigger, now: Long) {
        val log = tamperLog ?: return
        try {
            log.append(
                TamperSignal(
                    kind = TamperKind.UNKNOWN,
                    detectedAtMillis = now,
                    detail = "intruder capture ${trigger.name}",
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Evidence is still stored; a full tamper log must not break the capture path.
        }
    }

    companion object {
        /** Five minutes between captures unless the app explicitly overrides it. */
        const val DEFAULT_REPEAT_INTERVAL_MILLIS: Long = 5L * 60L * 1000L

        /** Hard bound on captures per rolling day, independent of the trigger. */
        const val DEFAULT_MAX_CAPTURES_PER_DAY: Int = 20
    }
}
