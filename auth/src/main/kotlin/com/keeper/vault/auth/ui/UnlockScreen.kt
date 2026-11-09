package com.keeper.vault.auth.ui

import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.keeper.vault.contracts.auth.SecretType
import com.keeper.vault.contracts.platform.SecretZeroizer
import kotlinx.coroutines.delay

/**
 * Password unlock, plus the additive biometric convenience button.
 *
 * PIN, pattern, and signature entry were removed from the product. [onUnlock] must copy the
 * supplied `CharArray` before returning; the screen zeroes its own buffer immediately afterwards.
 */
@Composable
fun UnlockScreen(
    configured: Boolean,
    busy: Boolean,
    error: String?,
    cooldownUntilMillis: Long?,
    biometricEnabled: Boolean,
    biometricAvailable: Boolean,
    resetToken: Int,
    onUnlock: (SecretType, CharArray) -> Unit,
    onBiometricClick: () -> Unit,
    onConfigure: () -> Unit,
) {
    val secret = rememberSecretTextState()
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(resetToken) {
        secret.clear()
    }

    LaunchedEffect(cooldownUntilMillis) {
        val until = cooldownUntilMillis ?: return@LaunchedEffect
        while (true) {
            nowMillis = System.currentTimeMillis()
            if (nowMillis >= until) break
            delay(500)
        }
        nowMillis = System.currentTimeMillis()
    }

    val cooldownSeconds = cooldownUntilMillis?.let { until ->
        ((until - nowMillis + 999L) / 1000L).coerceAtLeast(0L)
    }
    val blocked = busy || (cooldownSeconds != null && cooldownSeconds > 0L)

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
                title = "Unlock Keeper",
                subtitle = "Your password is the only way in. Biometrics are an optional " +
                    "shortcut.",
            )
            Spacer(Modifier.height(24.dp))

            if (!configured) {
                Text(
                    text = "No vault is configured on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onConfigure,
                    modifier = Modifier.testTag(AuthTestTags.SubmitButton),
                ) {
                    Text("Set up vault")
                }
            } else {
                if (cooldownSeconds != null && cooldownSeconds > 0L) {
                    Text(
                        text = "Too many attempts. Try again in ${cooldownSeconds}s.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag(AuthTestTags.Cooldown),
                    )
                    Spacer(Modifier.height(16.dp))
                }

                SecretTextField(
                    state = secret,
                    label = "Password",
                    testTag = AuthTestTags.SecretField,
                    enabled = !blocked,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        val entered = secret.copyChars()
                        try {
                            onUnlock(SecretType.PASSWORD, entered)
                        } finally {
                            SecretZeroizer.zero(entered)
                        }
                        secret.clear()
                    },
                    enabled = !blocked,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(AuthTestTags.UnlockButton),
                ) {
                    Text(if (busy) "Unlocking..." else "Unlock")
                }

                AuthError(error)

                if (biometricEnabled && biometricAvailable) {
                    Spacer(Modifier.height(20.dp))
                    OutlinedButton(
                        onClick = onBiometricClick,
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(AuthTestTags.BiometricButton),
                    ) {
                        Text("Unlock with biometrics")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Biometrics are a shortcut. Your password always works.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
