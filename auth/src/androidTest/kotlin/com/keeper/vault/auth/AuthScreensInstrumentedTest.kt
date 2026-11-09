package com.keeper.vault.auth

import android.view.WindowManager
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import com.keeper.vault.auth.ui.AuthTestTags
import com.keeper.vault.contracts.auth.AttemptState
import com.keeper.vault.contracts.auth.SecretType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Compose-level auth tests. They compile without a device; a device is required to execute them
 * (BiometricPrompt and real Keystore behavior are still device-only).
 */
class AuthScreensInstrumentedTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private lateinit var component: TestAuthComponent

    @Before
    fun installTestComponent() {
        component = TestAuthComponent()
        AuthFeature.install(component)
    }

    @After
    fun clearTestComponent() {
        AuthFeature.resetForTests()
        component.fakeAuthManager.setupSecret?.fill('\u0000')
        component.fakeAuthManager.expectedSecret.fill('\u0000')
    }

    @Test
    fun passwordSetupFlowCreatesTheVault() {
        ActivityScenario.launch(SecretSetupActivity::class.java).use {
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(AuthTestTags.SecretField)
                .performTextInput("correct horse")
            composeRule.onNodeWithTag(AuthTestTags.ConfirmField)
                .performTextInput("correct horse")
            composeRule.onNodeWithTag(AuthTestTags.SubmitButton).performClick()

            composeRule.waitUntil(5_000) { component.fakeAuthManager.setupType != null }
            assertEquals(SecretType.PASSWORD, component.fakeAuthManager.setupType)
            assertEquals(
                "correct horse",
                component.fakeAuthManager.setupSecret?.concatToString(),
            )
        }
    }

    @Test
    fun setupOffersOnlyPassword() {
        ActivityScenario.launch(SecretSetupActivity::class.java).use {
            composeRule.waitForIdle()
            composeRule.onNodeWithText("PIN").assertDoesNotExist()
            composeRule.onNodeWithText("Pattern").assertDoesNotExist()
            composeRule.onNodeWithText("Signature").assertDoesNotExist()
        }
    }

    @Test
    fun passwordUnlockFlowSucceeds() {
        component.fakeAuthManager.configured = true
        component.fakeAuthManager.expectedType = SecretType.PASSWORD
        component.fakeAuthManager.expectedSecret = "correct horse".toCharArray()

        ActivityScenario.launch(UnlockActivity::class.java).use {
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(AuthTestTags.SecretField)
                .performTextInput("correct horse")
            composeRule.onNodeWithTag(AuthTestTags.UnlockButton).performClick()

            composeRule.waitUntil(5_000) { component.fakeAuthManager.unlockCalls > 0 }
            assertEquals(1, component.fakeAuthManager.unlockCalls)
        }
    }

    @Test
    fun unlockOffersOnlyPassword() {
        component.fakeAuthManager.configured = true

        ActivityScenario.launch(UnlockActivity::class.java).use {
            composeRule.waitForIdle()
            composeRule.onNodeWithText("PIN").assertDoesNotExist()
            composeRule.onNodeWithText("Pattern").assertDoesNotExist()
            composeRule.onNodeWithText("Signature").assertDoesNotExist()
        }
    }

    @Test
    fun bothScreensSetFlagSecure() {
        ActivityScenario.launch(SecretSetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(
                    activity.window.attributes.flags and
                        WindowManager.LayoutParams.FLAG_SECURE != 0,
                )
            }
        }
        ActivityScenario.launch(UnlockActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(
                    activity.window.attributes.flags and
                        WindowManager.LayoutParams.FLAG_SECURE != 0,
                )
            }
        }
    }

    @Test
    fun cooldownIsShownAndUnlockIsDisabled() {
        component.fakeAuthManager.configured = true
        component.fakeLimiter.state = AttemptState(
            failures = 5,
            cooldownUntilMillis = System.currentTimeMillis() + 30_000L,
            lockedOut = true,
        )

        ActivityScenario.launch(UnlockActivity::class.java).use {
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithTag(AuthTestTags.Cooldown)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag(AuthTestTags.UnlockButton).assertIsNotEnabled()
        }
    }

    @Test
    fun biometricButtonIsHiddenWhenBiometricsAreUnavailable() {
        component.fakeAuthManager.configured = true
        component.fakeBiometric.enabled = true
        component.fakeBiometric.available = false

        ActivityScenario.launch(UnlockActivity::class.java).use {
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(AuthTestTags.BiometricButton).assertDoesNotExist()
        }
    }

    @Test
    fun biometricButtonIsHiddenWhenNotEnrolledByTheUser() {
        component.fakeAuthManager.configured = true
        component.fakeBiometric.enabled = false
        component.fakeBiometric.available = true

        ActivityScenario.launch(UnlockActivity::class.java).use {
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(AuthTestTags.BiometricButton).assertDoesNotExist()
        }
    }

    @Test
    fun biometricButtonAppearsOnlyForAnEnabledAvailablePath() {
        component.fakeAuthManager.configured = true
        component.fakeBiometric.enabled = true
        component.fakeBiometric.available = true

        ActivityScenario.launch(UnlockActivity::class.java).use {
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(AuthTestTags.BiometricButton).assertExists()
        }
    }
}
