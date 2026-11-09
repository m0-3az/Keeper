package com.keeper.vault.auth.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.keeper.vault.contracts.auth.SecretType
import com.keeper.vault.contracts.platform.SecretZeroizer

/**
 * Password-only vault creation.
 *
 * PIN, pattern, and signature entry were removed from the product: one password plus the optional
 * biometric convenience path keeps the unlock model simple and auditable. [onSetup] must copy the
 * supplied `CharArray` before returning; the screen zeroes its own buffer immediately afterwards.
 */
@Composable
fun SecretSetupScreen(
    configured: Boolean,
    busy: Boolean,
    error: String?,
    biometricAvailable: Boolean,
    biometricOptIn: Boolean,
    onBiometricOptInChange: (Boolean) -> Unit,
    onSetup: (SecretType, CharArray) -> Unit,
    onCancel: () -> Unit,
) {
    val secret = rememberSecretTextState()
    val confirmation = rememberSecretTextState()
    var localError by remember { mutableStateOf<String?>(null) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
        ) {
            AuthHeader(
                title = "Create your vault",
                subtitle = "Choose a password. It is never stored; only a Keystore-wrapped " +
                    "envelope is.",
            )
            Spacer(Modifier.height(24.dp))

            if (configured) {
                Text(
                    text = "A vault already exists on this device.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                SecretTextField(
                    state = secret,
                    label = "Password",
                    testTag = AuthTestTags.SecretField,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                SecretTextField(
                    state = confirmation,
                    label = "Confirm password",
                    testTag = AuthTestTags.ConfirmField,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        val first = secret.copyChars()
                        val second = confirmation.copyChars()
                        try {
                            if (!first.contentEquals(second)) {
                                localError = "The two entries do not match."
                            } else {
                                localError = null
                                onSetup(SecretType.PASSWORD, first)
                                secret.clear()
                                confirmation.clear()
                            }
                        } finally {
                            SecretZeroizer.zero(first)
                            SecretZeroizer.zero(second)
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(AuthTestTags.SubmitButton),
                ) {
                    Text(if (busy) "Creating..." else "Create vault")
                }
            }

            if (biometricAvailable && !configured) {
                Spacer(Modifier.height(16.dp))
                Row {
                    Switch(
                        checked = biometricOptIn,
                        onCheckedChange = onBiometricOptInChange,
                        enabled = !busy,
                        modifier = Modifier.testTag(AuthTestTags.BiometricOptIn),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Enable biometric unlock (optional). Your password still works.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            AuthError(localError ?: error)
            Spacer(Modifier.height(20.dp))
            Row {
                TextButton(
                    onClick = onCancel,
                    enabled = !busy,
                    modifier = Modifier.testTag(AuthTestTags.CancelButton),
                ) {
                    Text("Cancel")
                }
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}
