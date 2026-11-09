package com.keeper.vault.app

import com.keeper.vault.auth.IntruderWarningBridge
import com.keeper.vault.auth.UnlockCaptureHost
import com.keeper.vault.auth.UnlockCaptureUiState
import com.keeper.vault.auth.UnlockCaptureWarning
import com.keeper.vault.contracts.intruder.IntruderSettingsStore
import com.keeper.vault.duress.intruder.CaptureConfirmationGate
import com.keeper.vault.duress.intruder.IntruderCoordinator
import com.keeper.vault.duress.intruder.IntruderWarningRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * App-owned two-phase capture host.
 *
 * The unlock screen sees a warning; only [confirmCapture] asks the coordinator to run. The
 * coordinator checks the persisted failure counter and rate limits again, and the capture service
 * requires a one-shot confirmation token.
 */
class UnlockIntruderHost(
    private val coordinator: IntruderCoordinator,
    private val settings: IntruderSettingsStore,
    private val consentAccepted: () -> Boolean,
    private val scope: CoroutineScope,
    private val issueToken: () -> String = {
        CaptureConfirmationGate.issue(System.currentTimeMillis())
    },
    private val revokeToken: (String) -> Unit = { CaptureConfirmationGate.revoke(it) },
) : UnlockCaptureHost {
    private val _warning = MutableStateFlow<UnlockCaptureWarning?>(null)
    private val _captureState = MutableStateFlow<UnlockCaptureUiState>(UnlockCaptureUiState.Idle)

    override val warning: StateFlow<UnlockCaptureWarning?> = _warning.asStateFlow()
    override val captureState: StateFlow<UnlockCaptureUiState> = _captureState.asStateFlow()

    /** Called when the persisted failure counter increases. It only offers a warning. */
    fun onFailureCount(failures: Int) {
        if (_warning.value != null) return
        if (!consentAccepted()) return
        scope.launch {
            val request = try {
                coordinator.requestWarning(failures)
            } catch (_: Exception) {
                null
            } ?: return@launch
            if (_warning.value == null) {
                _warning.value = UnlockCaptureWarning(request.failures)
            }
        }
    }

    override fun confirmCapture() {
        val pending = _warning.value ?: return
        _warning.value = null
        _captureState.value = UnlockCaptureUiState.Capturing
        // The token is created only here, after the explicit UI confirmation. The service
        // consumes it before starting the camera.
        val token = issueToken()
        scope.launch {
            val event = try {
                coordinator.confirmCapture(
                    request = IntruderWarningRequest(pending.failures),
                    confirmationToken = token,
                )
            } catch (_: Exception) {
                null
            }
            if (event == null) {
                revokeToken(token)
                _captureState.value =
                    UnlockCaptureUiState.Failure("Capture was not started.")
            } else {
                _captureState.value = UnlockCaptureUiState.Started(
                    "Capture started. Check the intruder log for the result.",
                )
            }
            delay(RESULT_VISIBLE_MILLIS)
            if (_captureState.value is UnlockCaptureUiState.Started ||
                _captureState.value is UnlockCaptureUiState.Failure
            ) {
                _captureState.value = UnlockCaptureUiState.Idle
            }
        }
    }

    override fun cancelWarning() {
        _warning.value = null
    }

    override fun disableCapture() {
        _warning.value = null
        _captureState.value = UnlockCaptureUiState.Disabled("Intruder capture is now off.")
        scope.launch {
            try {
                settings.update {
                    it.copy(enabled = false, captureOnFaceMismatch = false)
                }
            } catch (_: Exception) {
                _captureState.value =
                    UnlockCaptureUiState.Failure("Could not turn off intruder capture.")
            }
            delay(RESULT_VISIBLE_MILLIS)
            if (_captureState.value is UnlockCaptureUiState.Disabled ||
                _captureState.value is UnlockCaptureUiState.Failure
            ) {
                _captureState.value = UnlockCaptureUiState.Idle
            }
        }
    }

    /** Clears a pending warning and transient result after unlock or counter reset. */
    fun clear() {
        _warning.value = null
        _captureState.value = UnlockCaptureUiState.Idle
    }

    companion object {
        const val RESULT_VISIBLE_MILLIS: Long = 4_000L
    }
}
