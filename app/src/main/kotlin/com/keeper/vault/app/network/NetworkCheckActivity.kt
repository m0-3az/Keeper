package com.keeper.vault.app.network

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keeper.vault.app.KeeperApplication
import com.keeper.vault.app.ui.KeeperTheme
import com.keeper.vault.app.ui.NetworkCheckScreen

/**
 * Read-only network status and public settings shortcuts. No radio is toggled or restored here.
 */
class NetworkCheckActivity : ComponentActivity() {
    private val source by lazy { AndroidNetworkStatusSource(this) }

    private var status by mutableStateOf(
        NetworkStatus(
            wifiConnected = false,
            mobileConnected = false,
            vpnActive = false,
            airplaneMode = false,
        ),
    )
    private var message by mutableStateOf<String?>(null)

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
        status = source.read()

        setContent {
            val theme by container.themeStore.theme.collectAsStateWithLifecycle()
            KeeperTheme(theme = theme) {
                NetworkCheckScreen(
                    status = status,
                    message = message,
                    onRefresh = {
                        status = source.read()
                        message = null
                    },
                    onOpen = ::openSettings,
                    onClose = { finish() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        status = source.read()
    }

    private fun openSettings(target: NetworkSettingTarget) {
        try {
            startActivity(target.intent())
            message = null
        } catch (_: ActivityNotFoundException) {
            message = "This device has no settings screen for ${target.label.lowercase()}."
        } catch (_: SecurityException) {
            message = "This device does not allow opening ${target.label.lowercase()}."
        }
    }
}
