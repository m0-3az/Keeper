package com.keeper.vault.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TermsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun agreeRequiresTheCheckbox() {
        var agreed = false
        composeRule.setContent {
            MaterialTheme {
                TermsScreen(onAgree = { agreed = true }, onDecline = {})
            }
        }

        composeRule.onNodeWithTag("terms_agree").assertIsNotEnabled()
        composeRule.onNodeWithTag("terms_checkbox").performScrollTo().performClick()
        composeRule.onNodeWithTag("terms_agree").assertIsEnabled()
        composeRule.onNodeWithTag("terms_agree").performScrollTo().performClick()
        assertTrue(agreed)
    }

    @Test
    fun declineIsAvailableWithoutChecking() {
        var declined = false
        composeRule.setContent {
            MaterialTheme {
                TermsScreen(onAgree = {}, onDecline = { declined = true })
            }
        }

        composeRule.onNodeWithTag("terms_decline").performScrollTo().performClick()
        assertTrue(declined)
    }
}
