package com.keeper.vault.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.keeper.vault.app.network.NetworkSettingTarget
import com.keeper.vault.app.network.NetworkStatus
import com.keeper.vault.app.ui.NetworkCheckScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NetworkCheckScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun statusRowsAndDisclaimerAreVisible() {
        composeRule.setContent {
            MaterialTheme {
                NetworkCheckScreen(
                    status = NetworkStatus(
                        wifiConnected = true,
                        mobileConnected = false,
                        vpnActive = false,
                        airplaneMode = true,
                    ),
                    message = null,
                    onRefresh = {},
                    onOpen = {},
                    onClose = {},
                )
            }
        }

        composeRule.onNodeWithTag("network_wifi_status").assertIsDisplayed()
        composeRule.onNodeWithTag("network_airplane_status").assertIsDisplayed()
        composeRule.onNodeWithTag("network_bluetooth_status").assertIsDisplayed()
        composeRule.onNodeWithTag("network_disclaimer", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun settingsButtonsInvokeTheMatchingTargets() {
        val opened = mutableListOf<NetworkSettingTarget>()
        composeRule.setContent {
            MaterialTheme {
                NetworkCheckScreen(
                    status = NetworkStatus(false, false, false, false),
                    message = null,
                    onRefresh = {},
                    onOpen = { opened += it },
                    onClose = {},
                )
            }
        }

        for (target in NetworkSettingTarget.entries) {
            composeRule.onNodeWithTag("network_open_${target.name.lowercase()}")
                .performScrollTo()
                .performClick()
        }
        assertEquals(NetworkSettingTarget.entries, opened)
    }
}
