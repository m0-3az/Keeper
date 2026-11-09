package com.keeper.vault.app.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NetworkCheckTest {
    @Test
    fun everyTargetOpensAPublicSettingsScreen() {
        assertThat(NetworkSettingTarget.entries).hasSize(7)
        for (target in NetworkSettingTarget.entries) {
            assertThat(target.action).startsWith("android.settings.")
            assertThat(target.action).doesNotContain("STATE")
        }
    }

    @Test
    fun statusLabelsAreReadOnlyText() {
        assertThat(onOff(true)).isEqualTo("Connected")
        assertThat(onOff(false)).isEqualTo("Not connected")
        assertThat(onOff(true, "On", "Off")).isEqualTo("On")
    }

    @Test
    fun targetsCoverTheChecklistWithoutToggleActions() {
        val actions = NetworkSettingTarget.entries.map { it.action }.toSet()
        assertThat(actions).contains("android.settings.WIFI_SETTINGS")
        assertThat(actions).contains("android.settings.BLUETOOTH_SETTINGS")
        assertThat(actions).contains("android.settings.NFC_SETTINGS")
        assertThat(actions).contains("android.settings.WIRELESS_SETTINGS")
        assertThat(actions).contains("android.settings.VPN_SETTINGS")
        assertThat(actions).contains("android.settings.AIRPLANE_MODE_SETTINGS")
        assertThat(actions.none { it.contains("WIFI_STATE", ignoreCase = true) }).isTrue()
        assertThat(actions.none { it.contains("BLUETOOTH_STATE", ignoreCase = true) }).isTrue()
    }
}
