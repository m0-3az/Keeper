package com.keeper.vault.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** The acknowledgement that must be accepted before the home/setup UI is composed. */
const val TERMS_ACKNOWLEDGEMENT_TEXT: String =
    "Keeper is a local encrypted vault. It declares no INTERNET permission.\n\n" +
        "File content is encrypted with AES-256-GCM, but file names, sizes, timestamps, and " +
        "vault metadata are not encrypted.\n\n" +
        "Face templates and app settings are stored locally on this device.\n\n" +
        "Face matching and blink liveness are deterrents, not authentication; photos or video " +
        "replay may fool them.\n\n" +
        "Intruder capture is OFF by default. If you enable it, you are solely responsible for " +
        "using it lawfully, informing anyone who may be recorded, and complying with all " +
        "applicable privacy and surveillance laws.\n\n" +
        "The app is provided as-is, without warranty. It is not a substitute for full-device " +
        "encryption, a locked bootloader, or a trustworthy operating system.\n\n" +
        "You must accept these terms to use Keeper.\n\n" +
        "This acknowledgement does not replace legal advice."

/**
 * Blocking first-run screen. It is deliberately full-screen and scrollable; the home UI is not
 * composed until [onAgree] runs.
 */
@Composable
fun TermsScreen(
    onAgree: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var checked by rememberSaveable { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("terms_screen"),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "KEEPER",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Terms & Acknowledgement",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = TERMS_ACKNOWLEDGEMENT_TEXT,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("terms_body"),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { checked = it },
                    modifier = Modifier.testTag("terms_checkbox"),
                )
                Text(
                    text = "I have read and agree to the Terms & Acknowledgement.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(
                onClick = onAgree,
                enabled = checked,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("terms_agree"),
            ) {
                Text("Agree")
            }
            TextButton(
                onClick = onDecline,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("terms_decline"),
            ) {
                Text("Decline and exit")
            }
        }
    }
}
