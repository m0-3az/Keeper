package com.keeper.vault.duress

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.keeper.vault.duress.settings.IntruderConsentStore
import com.keeper.vault.duress.settings.SharedPreferencesIntruderSettingsPersistence
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

class IntruderSettingsActivityTest {
    private val clearPreferences = TestRule { base: Statement, _: Description ->
        object : Statement() {
            override fun evaluate() {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                context.getSharedPreferences(
                    IntruderConsentStore.PREFERENCES_NAME,
                    Context.MODE_PRIVATE,
                ).edit().clear().commit()
                context.getSharedPreferences(
                    SharedPreferencesIntruderSettingsPersistence.PREFERENCES_NAME,
                    Context.MODE_PRIVATE,
                ).edit().clear().commit()
                base.evaluate()
            }
        }
    }

    private val composeRule = createAndroidComposeRule<IntruderSettingsActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(clearPreferences).around(composeRule)

    @Test
    fun enablingRequiresConsentAndDeclineKeepsItOff() {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("intruder_enable_switch")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("intruder_enable_switch").assertIsOff()
        composeRule.onNodeWithTag("intruder_enable_switch").performClick()

        composeRule.onNodeWithTag("intruder_consent_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("intruder_consent_decline").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("intruder_enable_switch").assertIsOff()
    }
}
