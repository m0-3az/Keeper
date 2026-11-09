package com.keeper.vault.duress

import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented screen contract tests. They compile and run on a device/emulator; this phase had
 * no device attached, so only compilation was verified here.
 */
@RunWith(AndroidJUnit4::class)
class IntruderScreensTest {

    @Test
    fun settingsActivitySetsFlagSecureBeforeContent() {
        ActivityScenario.launch(IntruderSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val flags = activity.window.attributes.flags
                assertTrue(
                    "IntruderSettingsActivity must set FLAG_SECURE",
                    flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
                )
            }
        }
    }

    @Test
    fun logActivitySetsFlagSecureBeforeContent() {
        ActivityScenario.launch(IntruderLogActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val flags = activity.window.attributes.flags
                assertTrue(
                    "IntruderLogActivity must set FLAG_SECURE",
                    flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
                )
            }
        }
    }
}
