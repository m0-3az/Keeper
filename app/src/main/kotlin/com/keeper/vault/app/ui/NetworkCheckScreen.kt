package com.keeper.vault.app.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.keeper.vault.app.network.NETWORK_CHECK_DISCLAIMER
import com.keeper.vault.app.network.NetworkSettingTarget
import com.keeper.vault.app.network.NetworkStatus
import com.keeper.vault.app.network.onOff

/**
 * Read-only network status and public settings shortcuts. There is no toggle, no radio control,
 * and no claim that Keeper can restore connectivity.
 */
@Composable
fun NetworkCheckScreen(
    status: NetworkStatus,
    message: String?,
    onRefresh: () -> Unit,
    onOpen: (NetworkSettingTarget) -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("network_check_screen"),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Network check",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onClose,
                    modifier = Modifier.testTag("network_check_close"),
                ) {
                    Text("Close")
                }
            }
            Text(
                text = "Keeper only reads the current state. It cannot change it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            StatusRow(
                label = "Wi-Fi",
                value = onOff(status.wifiConnected),
                tag = "network_wifi_status",
            )
            StatusRow(
                label = "Mobile data",
                value = onOff(status.mobileConnected, "Connected (best effort)", "Not connected"),
                tag = "network_mobile_status",
            )
            StatusRow(
                label = "VPN",
                value = onOff(status.vpnActive, "Active (best effort)", "Not active"),
                tag = "network_vpn_status",
            )
            StatusRow(
                label = "Airplane mode",
                value = onOff(status.airplaneMode, "On", "Off"),
                tag = "network_airplane_status",
            )
            StatusRow(
                label = "Bluetooth",
                value = "Open settings to check",
                tag = "network_bluetooth_status",
            )
            StatusRow(
                label = "NFC",
                value = "Open settings to check",
                tag = "network_nfc_status",
            )
            StatusRow(
                label = "Hotspot / tethering",
                value = "Open settings to check",
                tag = "network_hotspot_status",
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            Text(
                text = "Checklist",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "1. Decide which radios you want off. 2. Open the matching settings screen. " +
                    "3. Change them yourself. 4. Return here and refresh to confirm the visible " +
                    "state.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onRefresh,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("network_refresh"),
            ) {
                Text("Refresh read-only status")
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            Text(
                text = "Open system settings",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            for (target in NetworkSettingTarget.entries) {
                Button(
                    onClick = { onOpen(target) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("network_open_${target.name.lowercase()}"),
                ) {
                    Text(target.label)
                }
            }

            if (message != null) {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("network_message"),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = NETWORK_CHECK_DISCLAIMER,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("network_disclaimer"),
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, tag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
