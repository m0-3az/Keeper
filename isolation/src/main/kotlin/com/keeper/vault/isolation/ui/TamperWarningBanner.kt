package com.keeper.vault.isolation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keeper.vault.contracts.isolation.IsolationStatus
import com.keeper.vault.contracts.isolation.TamperKind
import com.keeper.vault.isolation.R
import com.keeper.vault.isolation.hasWarnings

const val TAMPER_BANNER_TAG: String = "tamper_warning_banner"

/**
 * Advisory warning banner. It renders nothing on a clean, hardened device and never blocks UI.
 *
 * False positives are expected: an enabled screen reader, a developer build, or a vendor ROM with
 * a `su` binary all trigger a warning. The copy says plainly that Keeper cannot block a compromised
 * device, so the warning is not mistaken for a guarantee.
 */
@Composable
fun TamperWarningBanner(
    status: IsolationStatus,
    modifier: Modifier = Modifier,
    onDetailsClick: (() -> Unit)? = null,
) {
    if (!status.hasWarnings()) return

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TAMPER_BANNER_TAG),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.tamper_banner_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            if (status.signals.isNotEmpty()) {
                val summaryParts = mutableListOf<String>()
                for ((kind, signals) in status.signals.groupBy { it.kind }) {
                    summaryParts += "${kindLabel(kind)} (${signals.size})"
                }
                Text(
                    text = summaryParts.joinToString(separator = " · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (!status.backupsDisabled) {
                Text(
                    text = stringResource(R.string.tamper_banner_backups_enabled),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (status.debuggable) {
                Text(
                    text = stringResource(R.string.tamper_banner_debuggable),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Text(
                text = stringResource(R.string.tamper_banner_limit),
                style = MaterialTheme.typography.bodySmall,
            )

            if (onDetailsClick != null) {
                TextButton(onClick = onDetailsClick) {
                    Text(text = stringResource(R.string.tamper_banner_details))
                }
            }
        }
    }
}

@Composable
internal fun kindLabel(kind: TamperKind): String = stringResource(
    when (kind) {
        TamperKind.ROOT -> R.string.tamper_kind_root
        TamperKind.DEBUGGER -> R.string.tamper_kind_debugger
        TamperKind.ACCESSIBILITY_SERVICE -> R.string.tamper_kind_accessibility
        TamperKind.EMULATOR -> R.string.tamper_kind_emulator
        TamperKind.HOOK_FRAMEWORK -> R.string.tamper_kind_hook
        TamperKind.UNKNOWN -> R.string.tamper_kind_unknown
    },
)
