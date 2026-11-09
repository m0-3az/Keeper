package com.keeper.vault.auth.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class IntruderWarningDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun warningOffersConfirmCancelAndDisable() {
        var confirmed = false
        var cancelled = false
        var disabled = false
        composeRule.setContent {
            MaterialTheme {
                IntruderWarningDialog(
                    onConfirm = { confirmed = true },
                    onCancel = { cancelled = true },
                    onDisable = { disabled = true },
                )
            }
        }

        composeRule.onNodeWithTag("intruder_warning_text").assertIsDisplayed()
        composeRule.onNodeWithTag("intruder_warning_confirm").performClick()
        assertTrue(confirmed)

        composeRule.onNodeWithTag("intruder_warning_cancel").performClick()
        assertTrue(cancelled)

        composeRule.onNodeWithTag("intruder_warning_disable").performClick()
        assertTrue(disabled)
    }

    @Test
    fun capturingIndicatorIsVisibleOnlyWhileCapturing() {
        composeRule.setContent {
            MaterialTheme {
                IntruderCaptureStatus(com.keeper.vault.auth.UnlockCaptureUiState.Capturing)
            }
        }

        composeRule.onNodeWithTag("intruder_capture_indicator").assertIsDisplayed()
    }
}
