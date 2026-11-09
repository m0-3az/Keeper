package com.keeper.vault.duress.settings

import android.content.Context

/**
 * Separate activation consent for intruder capture.
 *
 * This is intentionally independent of the general terms acceptance: enabling camera capture of
 * other people needs its own, explicit acknowledgement. A missing or older value is not accepted.
 */
class IntruderConsentStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun acceptedVersion(): Int = preferences.getInt(KEY_ACCEPTED_VERSION, 0)

    fun isAccepted(currentVersion: Int = CONSENT_VERSION): Boolean =
        acceptedVersion() >= currentVersion

    fun accept(version: Int = CONSENT_VERSION) {
        preferences.edit().putInt(KEY_ACCEPTED_VERSION, version).apply()
    }

    companion object {
        const val PREFERENCES_NAME: String = "keeper_intruder_consent"
        const val KEY_ACCEPTED_VERSION: String = "accepted_version"
        const val CONSENT_VERSION: Int = 1
    }
}

/** Ordered decision for the enable switch. Consent is checked before the camera permission. */
enum class IntruderEnableDecision {
    DISABLE,
    SHOW_CONSENT,
    REQUEST_CAMERA,
    ENABLE,
}

fun decideIntruderEnable(
    enabled: Boolean,
    consentAccepted: Boolean,
    cameraGranted: Boolean,
): IntruderEnableDecision = when {
    !enabled -> IntruderEnableDecision.DISABLE
    !consentAccepted -> IntruderEnableDecision.SHOW_CONSENT
    !cameraGranted -> IntruderEnableDecision.REQUEST_CAMERA
    else -> IntruderEnableDecision.ENABLE
}
