package com.keeper.vault.duress.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.keeper.vault.contracts.intruder.IntruderSettings
import com.keeper.vault.duress.IntruderCopy

/**
 * Opt-in screen. Capture starts OFF and only the explicit switch can turn it on.
 *
 * The disclosure is always visible, before the switch, and includes the mandatory legal
 * responsibility sentence. Retention values are 7/30/90 days or forever; the default is 30.
 */
@Composable
fun IntruderSettingsScreen(
    settings: IntruderSettings,
    cameraGranted: Boolean,
    eventCount: Int,
    error: String?,
    onEnabledChange: (Boolean) -> Unit,
    onThresholdChange: (Int) -> Unit,
    onRetentionChange: (Int?) -> Unit,
    onOpenLog: () -> Unit,
    onDeleteAll: () -> Unit,
    onGrantCamera: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(IntruderCopy.SETTINGS_TITLE, style = MaterialTheme.typography.headlineSmall)
            Text(IntruderCopy.DEFAULT_OFF, style = MaterialTheme.typography.bodyMedium)
            Text(IntruderCopy.DISCLOSURE, style = MaterialTheme.typography.bodySmall)

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    IntruderCopy.ENABLE,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                Switch(
                    checked = settings.enabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.testTag("intruder_enable_switch"),
                )
            }

            if (settings.enabled && !cameraGranted) {
                Text(
                    IntruderCopy.CAMERA_MISSING,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = onGrantCamera) {
                    Text(IntruderCopy.GRANT_CAMERA)
                }
            }

            Text(IntruderCopy.THRESHOLD_TITLE, style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (threshold in listOf(5, 10, 15)) {
                    FilterChip(
                        selected = settings.failureThreshold == threshold,
                        onClick = { onThresholdChange(threshold) },
                        label = { Text(threshold.toString()) },
                        enabled = settings.enabled,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    IntruderCopy.MISMATCH,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                IntruderCopy.MISMATCH_DISABLED,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("intruder_mismatch_disabled"),
            )

            Text(IntruderCopy.RETENTION_TITLE, style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (days in listOf(7, 30, 90)) {
                    FilterChip(
                        selected = settings.retentionDays == days,
                        onClick = { onRetentionChange(days) },
                        label = { Text("$days ${IntruderCopy.DAYS}") },
                    )
                }
                FilterChip(
                    selected = settings.retentionDays == null,
                    onClick = { onRetentionChange(null) },
                    label = { Text(IntruderCopy.FOREVER) },
                )
            }

            Text(IntruderCopy.NEVER_UPLOADED, style = MaterialTheme.typography.bodySmall)

            if (error != null) {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            HorizontalDivider()

            Text(
                "Stored captures: $eventCount",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onOpenLog) {
                    Text(IntruderCopy.OPEN_LOG)
                }
                OutlinedButton(onClick = onDeleteAll) {
                    Text(IntruderCopy.DELETE_ALL)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
