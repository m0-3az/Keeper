package com.keeper.vault.isolation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keeper.vault.contracts.isolation.IsolationStatus
import com.keeper.vault.contracts.isolation.TamperKind
import com.keeper.vault.contracts.isolation.TamperSignal
import com.keeper.vault.isolation.ui.TamperWarningBanner
import com.keeper.vault.isolation.ui.TAMPER_BANNER_TAG
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TamperWarningBannerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun bannerIsVisibleWhenASignalIsPresent() {
        composeRule.setContent {
            MaterialTheme {
                TamperWarningBanner(
                    status = IsolationStatus(
                        signals = listOf(TamperSignal(TamperKind.ROOT, 1, "su")),
                        backupsDisabled = true,
                        debuggable = false,
                    ),
                )
            }
        }

        composeRule.onNodeWithTag(TAMPER_BANNER_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.tamper_banner_title)).assertIsDisplayed()
    }

    @Test
    fun bannerIsVisibleWhenBackupsAreEnabled() {
        composeRule.setContent {
            MaterialTheme {
                TamperWarningBanner(
                    status = IsolationStatus(
                        signals = emptyList(),
                        backupsDisabled = false,
                        debuggable = false,
                    ),
                )
            }
        }

        composeRule.onNodeWithTag(TAMPER_BANNER_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.tamper_banner_backups_enabled),
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun bannerIsAbsentOnACleanHardenedDevice() {
        composeRule.setContent {
            MaterialTheme {
                TamperWarningBanner(
                    status = IsolationStatus(
                        signals = emptyList(),
                        backupsDisabled = true,
                        debuggable = false,
                    ),
                )
            }
        }

        composeRule.onNodeWithTag(TAMPER_BANNER_TAG).assertDoesNotExist()
    }
}
