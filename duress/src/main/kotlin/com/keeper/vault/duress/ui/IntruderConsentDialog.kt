package com.keeper.vault.duress.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.keeper.vault.duress.IntruderCopy

/**
 * Blocking activation consent. The caller must not request the camera permission or set
 * `enabled = true` until [onAccept] runs.
 */
@Composable
fun IntruderConsentDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    var checked by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDecline,
        modifier = Modifier.testTag("intruder_consent_dialog"),
        title = { Text(IntruderCopy.ACTIVATION_TITLE) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = IntruderCopy.ACTIVATION_CONSENT,
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { checked = it },
                        modifier = Modifier.testTag("intruder_consent_checkbox"),
                    )
                    Text(
                        text = IntruderCopy.ACTIVATION_CHECKBOX,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onAccept,
                enabled = checked,
                modifier = Modifier.testTag("intruder_consent_accept"),
            ) {
                Text("Enable intruder capture")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDecline,
                modifier = Modifier.testTag("intruder_consent_decline"),
            ) {
                Text("Cancel")
            }
        },
    )
}
