package com.keeper.vault.duress

/**
 * User-facing copy for the intruder feature.
 *
 * Kept in code (not `strings.xml`) so unit tests can assert the mandatory legal disclosure
 * verbatim without Android resources. Localization can move this to resources later without
 * changing the behaviour layer.
 */
object IntruderCopy {
    const val TITLE: String = "Intruder log"
    const val SETTINGS_TITLE: String = "Intruder log settings"

    /**
     * Required plain-language disclosure. The last sentence is mandatory and must not be
     * removed or softened; legal review is still pending (plan item O3).
     */
    const val DISCLOSURE: String =
        "Keeper can take one still with the front camera after repeated failed unlocks. " +
            "Every capture requires a visible warning and an explicit on-screen confirmation; " +
            "cancelling means no camera starts. Face-mismatch capture is disabled in this " +
            "release. Captures are encrypted on this device and are never uploaded. " +
            "Android shows a camera privacy indicator while the camera runs. " +
            "A covered lens, a mask, or a device without a front camera defeats capture. " +
            "You are responsible for complying with local privacy laws."

    const val DEFAULT_OFF: String = "Intruder capture is OFF until you explicitly opt in."

    /** Exact activation-consent text; legal review is still pending. */
    const val ACTIVATION_CONSENT: String =
        "Intruder capture records images of people who fail to unlock this device. " +
            "It is off by default. If you enable it, you confirm that you will use it only " +
            "where lawful, that you will provide any required notice or consent, and that you " +
            "are solely responsible for capturing, storing, retaining, and using those images. " +
            "Images stay on this device. To the extent permitted by law, the developer is not " +
            "responsible for your use. This is not legal advice."

    const val ACTIVATION_CHECKBOX: String =
        "I have read and agree to this intruder-capture consent."
    const val ACTIVATION_TITLE: String = "Intruder capture consent"
    const val ENABLE: String = "Enable intruder capture"
    const val MISMATCH: String = "Also capture on owner face mismatch"
    const val MISMATCH_DISABLED: String =
        "Face-mismatch capture is disabled in this release. It has no visible confirmation " +
            "flow, so it cannot start the camera. Only a failed unlock with an on-screen " +
            "confirmation can capture."
    const val THRESHOLD_TITLE: String = "Capture after failed unlocks"
    const val RETENTION_TITLE: String = "Delete captures after"
    const val FOREVER: String = "Forever"
    const val DAYS: String = "days"
    const val OPEN_LOG: String = "Open intruder log"
    const val DELETE_ALL: String = "Delete all captures"
    const val CAMERA_MISSING: String =
        "Camera permission is not granted. Captures will fail closed until you grant it."
    const val GRANT_CAMERA: String = "Grant camera permission"
    const val NEVER_UPLOADED: String = "Captures stay in this app's private storage."
    const val LOCKED_MESSAGE: String = "Unlock the vault before viewing the intruder log."
    const val REAUTH_MESSAGE: String =
        "Re-enter your vault secret to view the intruder log. " +
            "The frozen auth API verifies by unlocking again."
    const val REAUTH_BUTTON: String = "Verify and view"
    const val EMPTY: String = "No intruder captures have been recorded."
    const val VIEW: String = "View"
    const val DELETE: String = "Delete"
    const val CLOSE_IMAGE: String = "Close image"
    const val SECRET_LABEL: String = "Vault secret"
    const val CAPTURE_FAILED: String = "The camera was unavailable; no capture was stored."
}
