package com.keeper.vault.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.keeper.vault.app.ui.KeeperTheme
import com.keeper.vault.app.ui.ThemePicker
import com.keeper.vault.app.network.NetworkCheckActivity
import com.keeper.vault.contracts.settings.ThemeMode
import kotlinx.coroutines.launch

/**
 * Theme and security settings.
 *
 * The activity is a plain Compose shell over the app-owned [com.keeper.vault.contracts.settings.ThemeStore];
 * it never touches key material. It is reachable from the gear on the home and vault screens.
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        enableEdgeToEdge()

        val container = (application as? KeeperApplication)?.container
        if (container == null) {
            finish()
            return
        }
        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "0.1.0"

        setContent {
            val theme by container.themeStore.theme.collectAsStateWithLifecycle()
            KeeperTheme(theme = theme) {
                SettingsScreen(
                    theme = theme,
                    versionName = versionName,
                    onThemeChange = { mode ->
                        lifecycleScope.launch { container.themeStore.setTheme(mode) }
                    },
                    onNetworkCheck = {
                        startActivity(Intent(this, NetworkCheckActivity::class.java))
                    },
                    onClose = { finish() },
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    theme: ThemeMode,
    versionName: String,
    onThemeChange: (ThemeMode) -> Unit,
    onNetworkCheck: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp)
                .testTag("settings_screen"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onClose,
                    modifier = Modifier.testTag("settings_close"),
                ) {
                    Text(stringResource(R.string.settings_close))
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_appearance),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            ThemePicker(theme = theme, onThemeChange = onThemeChange)

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.settings_security),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            SecurityLine(stringResource(R.string.settings_unlock_method))
            SecurityLine(stringResource(R.string.settings_auto_lock))
            SecurityLine(stringResource(R.string.settings_screen_off))
            SecurityLine(stringResource(R.string.settings_encryption))
            SecurityLine(stringResource(R.string.settings_no_internet))

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onNetworkCheck,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_network_check"),
            ) {
                Text("Network check")
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_face_enrollment),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.settings_version, versionName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SecurityLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
