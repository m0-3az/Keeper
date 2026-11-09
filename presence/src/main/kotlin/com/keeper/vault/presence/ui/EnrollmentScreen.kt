package com.keeper.vault.presence.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** UI state for owner enrollment. */
sealed interface EnrollmentUiState {
    data object NeedsPermission : EnrollmentUiState

    /** ML Kit and LiteRT are loading; the camera starts as soon as they are ready. */
    data object Preparing : EnrollmentUiState

    data class Scanning(
        val captured: Int,
        val maximum: Int,
        val hint: String?,
        val previewAvailable: Boolean = true,
    ) : EnrollmentUiState

    data object Saving : EnrollmentUiState

    data object Complete : EnrollmentUiState

    /**
     * A user-visible failure.
     *
     * [retryable] is false for permanent hardware/model problems: retrying the same dead engine
     * only wastes the user's time. The skip action is always offered so the vault stays usable.
     */
    data class Failed(
        val message: String,
        val retryable: Boolean = true,
    ) : EnrollmentUiState
}

/**
 * Compose enrollment screen. The activity owns the camera; this screen only renders state and
 * forwards user actions. It never sees an embedding or a key.
 */
@Composable
fun EnrollmentScreen(
    state: EnrollmentUiState,
    onRequestPermission: () -> Unit,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    preview: @Composable () -> Unit,
) {
    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Owner enrollment",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                when (state) {
                    EnrollmentUiState.NeedsPermission -> {
                        Text(
                            text = "The camera is used to capture your face for on-device " +
                                "presence matching. Frames never leave this device.",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Button(onClick = onRequestPermission) {
                            Text("Enable camera")
                        }
                    }

                    EnrollmentUiState.Preparing -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            text = "Starting face detection...",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }

                    is EnrollmentUiState.Scanning -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (state.previewAvailable) {
                                preview()
                            } else {
                                Text(
                                    text = "Camera preview is unavailable on this device, but " +
                                        "face analysis is running. Hold still in front of the " +
                                        "front camera.",
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                            }
                        }
                        Text(
                            text = "Look straight at the camera - " +
                                "${state.captured} / ${state.maximum} frames",
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        LinearProgressIndicator(
                            progress = {
                                if (state.maximum == 0) 0f
                                else state.captured.toFloat() / state.maximum
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        state.hint?.let { hint ->
                            Text(
                                text = hint,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    EnrollmentUiState.Saving -> {
                        Text(
                            text = "Encrypting and saving your enrollment...",
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }

                    EnrollmentUiState.Complete -> {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .background(Color(0xFF2E7D32), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text = "OK", color = Color.White)
                        }
                        Text(
                            text = "Enrollment complete. The encrypted template is stored in " +
                                "the vault.",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }

                    is EnrollmentUiState.Failed -> {
                        Text(
                            text = state.message,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("enrollment_error"),
                        )
                        if (state.retryable) {
                            Button(onClick = onRetry) {
                                Text("Try again")
                            }
                        }
                    }
                }

                // Always available: an unusable camera or model must never trap the user in
                // this screen. Skipping leaves presence matching disabled, not the vault.
                TextButton(
                    onClick = onSkip,
                    modifier = Modifier.testTag("skip_enrollment"),
                ) {
                    Text("Skip Face Enrollment")
                }
            }
        }
    }
}
