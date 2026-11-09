package com.keeper.vault.duress.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class IntruderConsentDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun acceptRequiresTheCheckbox() {
        var accepted = false
        composeRule.setContent {
            MaterialTheme {
                IntruderConsentDialog(onAccept = { accepted = true }, onDecline = {})
            }
        }

        composeRule.onNodeWithTag("intruder_consent_accept").assertIsNotEnabled()
        composeRule.onNodeWithTag("intruder_consent_checkbox").performClick()
        composeRule.onNodeWithTag("intruder_consent_accept").assertIsEnabled()
        composeRule.onNodeWithTag("intruder_consent_accept").performClick()
        assertTrue(accepted)
    }

    @Test
    fun declineIsAvailableWithoutChecking() {
        var declined = false
        composeRule.setContent {
            MaterialTheme {
                IntruderConsentDialog(onAccept = {}, onDecline = { declined = true })
            }
        }

        composeRule.onNodeWithTag("intruder_consent_decline").performClick()
        assertTrue(declined)
    }
}
