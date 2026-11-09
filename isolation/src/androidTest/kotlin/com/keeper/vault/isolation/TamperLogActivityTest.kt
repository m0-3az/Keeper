package com.keeper.vault.isolation

import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TamperLogActivityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<TamperLogActivity>()

    @Test
    fun activitySetsFlagSecure() {
        val flags = composeRule.activity.window.attributes.flags

        assertTrue(
            "TamperLogActivity must set FLAG_SECURE",
            flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
        )
    }

    @Test
    fun screenShowsLogTitleAndClearAction() {
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.tamper_log_title),
            substring = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.tamper_log_clear),
        ).assertIsDisplayed()
    }
}
