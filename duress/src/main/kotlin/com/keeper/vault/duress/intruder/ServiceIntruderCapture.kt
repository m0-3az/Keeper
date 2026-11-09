package com.keeper.vault.duress.intruder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.keeper.vault.contracts.intruder.IntruderCapture
import com.keeper.vault.contracts.intruder.IntruderEvent
import com.keeper.vault.contracts.intruder.IntruderTrigger
import com.keeper.vault.contracts.isolation.TamperKind
import com.keeper.vault.contracts.isolation.TamperLog
import com.keeper.vault.contracts.isolation.TamperSignal
import com.keeper.vault.contracts.platform.Clock
import com.keeper.vault.duress.IntruderCaptureService
import com.keeper.vault.duress.intruder.CaptureConfirmationGate
import com.keeper.vault.duress.intruder.ConfirmedIntruderCapture
import com.keeper.vault.duress.settings.IntruderConsentStore
import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * Starts [IntruderCaptureService] and returns the reserved event metadata.
 *
 * Why a reservation: CameraX delivers its result asynchronously inside the service, but the
 * coordinator must rate-limit repeated dispatches immediately. The reserved event carries the id,
 * timestamp, trigger and filename; the service fills in the ciphertext and metadata row. If the
 * service never runs, nothing is written. The return value therefore means "a capture was
 * dispatched", not "an image is already on disk"; the service records its own failure separately.
 *
 * The service checks the permission again and fails closed, so a revoked permission between this
 * check and the service call cannot crash the unlock screen.
 */
class ServiceIntruderCapture(
    context: Context,
    private val tamperLog: TamperLog?,
    private val clock: Clock,
) : IntruderCapture, ConfirmedIntruderCapture {
    private val appContext = context.applicationContext

    /** Unconfirmed callers are rejected; use [captureConfirmed] only after user confirmation. */
    override suspend fun capture(trigger: IntruderTrigger): IntruderEvent? {
        appendFailure("capture requires explicit confirmation")
        return null
    }

    override suspend fun captureConfirmed(trigger: IntruderTrigger, token: String): IntruderEvent? {
        if (!IntruderConsentStore(appContext).isAccepted()) {
            appendFailure("intruder consent missing")
            return null
        }
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            appendFailure("camera permission missing")
            return null
        }
        if (!CaptureConfirmationGate.isValid(token, clock.nowMillis())) {
            appendFailure("capture not confirmed")
            return null
        }
        val id = UUID.randomUUID().toString()
        val event = IntruderEvent(
            id = id,
            capturedAtMillis = clock.nowMillis(),
            trigger = trigger,
            encryptedFileName = "$id.enc",
        )
        val intent = Intent(appContext, IntruderCaptureService::class.java)
            .putExtra(IntruderCaptureService.EXTRA_TRIGGER, trigger.name)
            .putExtra(IntruderCaptureService.EXTRA_EVENT_ID, event.id)
            .putExtra(IntruderCaptureService.EXTRA_CAPTURED_AT, event.capturedAtMillis)
            .putExtra(IntruderCaptureService.EXTRA_FILE_NAME, event.encryptedFileName)
            .putExtra(IntruderCaptureService.EXTRA_CONFIRMATION_TOKEN, token)
        return try {
            ContextCompat.startForegroundService(appContext, intent)
            event
        } catch (e: CancellationException) {
            CaptureConfirmationGate.revoke(token)
            throw e
        } catch (_: Exception) {
            CaptureConfirmationGate.revoke(token)
            appendFailure("foreground service start refused")
            null
        }
    }

    private suspend fun appendFailure(reason: String) {
        val log = tamperLog ?: return
        try {
            log.append(
                TamperSignal(
                    kind = TamperKind.UNKNOWN,
                    detectedAtMillis = clock.nowMillis(),
                    detail = "intruder capture unavailable: $reason",
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Never break the unlock screen because the advisory log is full.
        }
    }
}
