package com.keeper.vault.presence

import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PresenceEnrollmentActivityTest {
    @Test
    fun enrollmentScreenSetsFlagSecureBeforeShowingContent() {
        ActivityScenario.launch(PresenceEnrollmentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val flags = activity.window.attributes.flags
                assertThat(flags and WindowManager.LayoutParams.FLAG_SECURE)
                    .isEqualTo(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}
