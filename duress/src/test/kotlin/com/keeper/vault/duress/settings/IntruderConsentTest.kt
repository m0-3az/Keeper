package com.keeper.vault.duress.settings

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.intruder.IntruderSettings
import org.junit.Test

class IntruderConsentTest {
    @Test
    fun defaultSettingsAreOff() {
        val settings = IntruderSettings()
        assertThat(settings.enabled).isFalse()
        assertThat(settings.captureOnFaceMismatch).isFalse()
        assertThat(settings.failureThreshold).isEqualTo(5)
        assertThat(settings.retentionDays).isEqualTo(30)
    }

    @Test
    fun enablingWithoutConsentShowsConsentBeforeCamera() {
        assertThat(
            decideIntruderEnable(
                enabled = true,
                consentAccepted = false,
                cameraGranted = false,
            ),
        ).isEqualTo(IntruderEnableDecision.SHOW_CONSENT)
        assertThat(
            decideIntruderEnable(
                enabled = true,
                consentAccepted = false,
                cameraGranted = true,
            ),
        ).isEqualTo(IntruderEnableDecision.SHOW_CONSENT)
    }

    @Test
    fun consentThenCameraThenEnable() {
        assertThat(
            decideIntruderEnable(
                enabled = true,
                consentAccepted = true,
                cameraGranted = false,
            ),
        ).isEqualTo(IntruderEnableDecision.REQUEST_CAMERA)
        assertThat(
            decideIntruderEnable(
                enabled = true,
                consentAccepted = true,
                cameraGranted = true,
            ),
        ).isEqualTo(IntruderEnableDecision.ENABLE)
    }

    @Test
    fun disablingNeverNeedsConsentOrCamera() {
        assertThat(
            decideIntruderEnable(
                enabled = false,
                consentAccepted = false,
                cameraGranted = false,
            ),
        ).isEqualTo(IntruderEnableDecision.DISABLE)
    }
}
