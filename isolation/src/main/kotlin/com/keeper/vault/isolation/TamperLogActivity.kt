package com.keeper.vault.isolation

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.keeper.vault.isolation.ui.TamperLogScreen
import kotlinx.coroutines.Dispatchers

/**
 * Tamper log screen entry point. The FQCN is frozen by the app manifest.
 *
 * The screen is advisory: it shows root/debug/accessibility/emulator/hook signals and a clear
 * action. `FLAG_SECURE` is set before the first frame so the log cannot be captured through the
 * normal screenshot or recents pipeline.
 */
class TamperLogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        val tamperLog = AndroidIsolation.createTamperLog(
            context = applicationContext,
            dispatcher = Dispatchers.IO,
        )

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                TamperLogScreen(tamperLog = tamperLog)
            }
        }
    }
}
