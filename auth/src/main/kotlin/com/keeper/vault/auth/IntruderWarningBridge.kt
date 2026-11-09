package com.keeper.vault.auth

import kotlinx.coroutines.flow.StateFlow

/** A confirmed-capture warning is pending for the unlock screen. */
data class UnlockCaptureWarning(val failures: Int)

/** UI state for the post-confirmation capture attempt. */
sealed interface UnlockCaptureUiState {
    data object Idle : UnlockCaptureUiState
    data object Capturing : UnlockCaptureUiState

    data class Started(val message: String) : UnlockCaptureUiState

    data class Failure(val message: String) : UnlockCaptureUiState

    data class Disabled(val message: String) : UnlockCaptureUiState
}

/**
 * App/duress-owned bridge. The unlock screen only renders the warning and forwards the explicit
 * user actions; it never starts the camera itself.
 */
interface UnlockCaptureHost {
    val warning: StateFlow<UnlockCaptureWarning?>
    val captureState: StateFlow<UnlockCaptureUiState>

    fun confirmCapture()

    fun cancelWarning()

    fun disableCapture()
}

/**
 * Installed once by the app container. A null host means intruder capture is unavailable and the
 * unlock screen shows no warning.
 */
object IntruderWarningBridge {
    @Volatile
    var host: UnlockCaptureHost? = null
}
