package com.keeper.vault.vault

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.keeper.vault.contracts.vault.VaultRepository
import com.keeper.vault.vault.ui.KeeperVaultTheme
import com.keeper.vault.vault.ui.VaultRoute

/**
 * Phase 2 vault list/content screen.
 *
 * `FLAG_SECURE` is applied before any content is composed. Unlocking is owned by `:auth`; this
 * Activity only consumes a key through [com.keeper.vault.vault.session.VaultSession] and shows a
 * locked state when no key is present.
 *
 * Edge-to-edge is enabled together with `WindowInsets.safeDrawing` in the screen so the header
 * stays below a camera cutout on notched devices.
 */
class VaultActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        enableEdgeToEdge()

        val repository = repositoryOverride
            ?: VaultFeature.repository(applicationContext)

        setContent {
            KeeperVaultTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    VaultRoute(
                        repository = repository,
                        onSettingsClick = ::openSettings,
                    )
                }
            }
        }
    }

    /**
     * Settings lives in `:app`, which depends on this module; navigate by component name to keep
     * the module graph acyclic. A missing activity is not fatal: the vault stays usable.
     */
    private fun openSettings() {
        val intent = Intent()
            .setClassName(packageName, "com.keeper.vault.app.SettingsActivity")
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // A test or stripped build without the settings activity; nothing to open.
        }
    }

    companion object {
        /**
         * Instrumentation-test seam. Production leaves this null and uses [VaultFeature].
         * Tests must reset it after use.
         */
        @Volatile
        var repositoryOverride: VaultRepository? = null
    }
}
