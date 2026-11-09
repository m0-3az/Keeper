package com.keeper.vault.app

import android.Manifest
import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

class MainActivityTermsGateTest {
    private val clearTerms = TestRule { base: Statement, _: Description ->
        object : Statement() {
            override fun evaluate() {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                context.getSharedPreferences(TermsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit()
                base.evaluate()
            }
        }
    }

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(clearTerms)
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(composeRule)

    @Test
    fun gateBlocksHomeUntilTermsAreAccepted() {
        composeRule.onNodeWithTag("terms_screen").assertIsDisplayed()
        composeRule.onAllNodesWithTag("home_screen").assertCountEquals(0)
        composeRule.onNodeWithTag("terms_agree").assertIsNotEnabled()

        composeRule.onNodeWithTag("terms_checkbox").performScrollTo().performClick()
        composeRule.onNodeWithTag("terms_agree").performScrollTo().performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("home_screen").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()
    }

    @Test
    fun declineFinishesTheActivity() {
        composeRule.onNodeWithTag("terms_decline").performScrollTo().performClick()
        composeRule.waitUntil(5_000) {
            composeRule.activityRule.scenario.state == Lifecycle.State.DESTROYED
        }
        assertEquals(
            Lifecycle.State.DESTROYED,
            composeRule.activityRule.scenario.state,
        )
    }

    @Test
    fun acceptanceSurvivesRecreation() {
        composeRule.onNodeWithTag("terms_checkbox").performScrollTo().performClick()
        composeRule.onNodeWithTag("terms_agree").performScrollTo().performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("home_screen").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("home_screen").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithTag("terms_screen").assertCountEquals(0)
    }

    @Test
    fun olderAcceptedVersionReprompts() {
        composeRule.onNodeWithTag("terms_checkbox").performScrollTo().performClick()
        composeRule.onNodeWithTag("terms_agree").performScrollTo().performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("home_screen").fetchSemanticsNodes().isNotEmpty()
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences(TermsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(TermsStore.KEY_ACCEPTED_VERSION, TermsStore.TERMS_VERSION - 1)
            .commit()

        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("terms_screen").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("terms_screen").assertIsDisplayed()
    }
}
