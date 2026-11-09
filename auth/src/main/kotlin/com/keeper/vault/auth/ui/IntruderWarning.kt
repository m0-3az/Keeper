package com.keeper.vault.auth.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.keeper.vault.auth.UnlockCaptureUiState

const val INTRUDER_WARNING_TEXT: String =
    "Warning: a photo will be taken and stored encrypted on this device after this failed " +
        "unlock attempt. The camera indicator will turn on. Continue?"

/**
 * Blocking confirmation shown before every intruder capture. There is deliberately no
 * "remember my choice" path: cancelling means no camera and no capture.
 */
@Composable
fun IntruderWarningDialog(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onDisable: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag("intruder_warning_dialog"),
        title = {
            Text(
                text = "Intruder capture warning",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = INTRUDER_WARNING_TEXT,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("intruder_warning_text"),
                )
                TextButton(
                    onClick = onDisable,
                    modifier = Modifier.testTag("intruder_warning_disable"),
                ) {
                    Text("Turn off intruder capture")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("intruder_warning_confirm"),
            ) {
                Text("Take photo")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.testTag("intruder_warning_cancel"),
            ) {
                Text("Cancel")
            }
        },
    )
}

/** In-app indicator and short result; no camera work happens here. */
@Composable
fun IntruderCaptureStatus(state: UnlockCaptureUiState) {
    val text = when (state) {
        UnlockCaptureUiState.Idle -> return
        UnlockCaptureUiState.Capturing -> "Capturing a photo now…"
        is UnlockCaptureUiState.Started -> state.message
        is UnlockCaptureUiState.Failure -> state.message
        is UnlockCaptureUiState.Disabled -> state.message
    }
    val tag = if (state is UnlockCaptureUiState.Capturing) {
        "intruder_capture_indicator"
    } else {
        "intruder_capture_result"
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.testTag(tag),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}
