package com.keeper.vault.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keeper.vault.app.network.NetworkSettingTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkSettingIntentTest {
    @Test
    fun targetsBuildPublicSettingsIntents() {
        for (target in NetworkSettingTarget.entries) {
            val intent = target.intent()
            assertEquals(target.action, intent.action)
            assertTrue(
                intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0,
            )
        }
    }
}
