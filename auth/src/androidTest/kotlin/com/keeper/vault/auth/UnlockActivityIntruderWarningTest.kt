package com.keeper.vault.auth

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

class UnlockActivityIntruderWarningTest {
    private class FakeHost : UnlockCaptureHost {
        override val warning = MutableStateFlow<UnlockCaptureWarning?>(null)
        override val captureState = MutableStateFlow<UnlockCaptureUiState>(UnlockCaptureUiState.Idle)
        var confirms = 0
        var cancels = 0
        var disables = 0

        override fun confirmCapture() {
            confirms += 1
            warning.value = null
            captureState.value = UnlockCaptureUiState.Capturing
        }

        override fun cancelWarning() {
            cancels += 1
            warning.value = null
        }

        override fun disableCapture() {
            disables += 1
            warning.value = null
        }
    }

    private val host = FakeHost()
    private val installHost = TestRule { base: Statement, _: Description ->
        object : Statement() {
            override fun evaluate() {
                IntruderWarningBridge.host = host
                host.warning.value = UnlockCaptureWarning(failures = 5)
                try {
                    base.evaluate()
                } finally {
                    IntruderWarningBridge.host = null
                    host.warning.value = null
                }
            }
        }
    }

    private val composeRule = createAndroidComposeRule<UnlockActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(installHost).around(composeRule)

    @Test
    fun warningIsShownBeforeAnyConfirmationAndCancelDoesNotCapture() {
        composeRule.onNodeWithTag("intruder_warning_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("intruder_warning_text").assertIsDisplayed()
        assertEquals(0, host.confirms)

        composeRule.onNodeWithTag("intruder_warning_cancel").performClick()
        assertEquals(1, host.cancels)
        assertEquals(0, host.confirms)
    }

    @Test
    fun confirmDismissesWarningAndShowsCapturingIndicator() {
        composeRule.onNodeWithTag("intruder_warning_confirm").performClick()

        assertEquals(1, host.confirms)
        composeRule.onNodeWithTag("intruder_capture_indicator").assertIsDisplayed()
    }
}
