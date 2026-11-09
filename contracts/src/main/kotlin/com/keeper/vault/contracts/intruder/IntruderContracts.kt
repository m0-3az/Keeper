package com.keeper.vault.contracts.intruder

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** What caused a capture. Both triggers use the dedicated IntruderKey. */
enum class IntruderTrigger {
    FAILED_UNLOCK,
    FACE_MISMATCH,
}

/**
 * Opt-in intruder settings.
 *
 * Defaults are deliberately conservative: OFF, no mismatch capture, 30-day retention, capture at
 * 5 failures. The opt-in screen must explain the camera indicator and the legal responsibility.
 *
 * @property retentionDays null means forever; allowed values are 7, 30, 90, null.
 */
data class IntruderSettings(
    val enabled: Boolean = false,
    val captureOnFaceMismatch: Boolean = false,
    val failureThreshold: Int = 5,
    val retentionDays: Int? = 30,
) {
    init {
        require(failureThreshold in setOf(5, 10, 15)) {
            "failureThreshold must be 5, 10, or 15"
        }
        require(retentionDays == null || retentionDays in setOf(7, 30, 90)) {
            "retentionDays must be 7, 30, 90, or null"
        }
        require(enabled || !captureOnFaceMismatch) {
            "Mismatch capture requires the feature to be enabled"
        }
    }
}

/** Persisted settings. The theme is separate (plain preferences); this store holds opt-ins. */
interface IntruderSettingsStore {
    val settings: StateFlow<IntruderSettings>

    suspend fun update(transform: (IntruderSettings) -> IntruderSettings)
}

/**
 * Encrypts intruder images with a dedicated random key wrapped by a non-auth Keystore key.
 *
 * This is deliberately **not** the VaultCipher: failed-unlock capture happens before any
 * secret-derived VaultKey exists. The key is protected at rest by the Keystore only, not by the
 * user's secret. See `LIMITATIONS.md`.
 */
interface IntruderKeyStore {
    suspend fun encrypt(plaintext: ByteArray): ByteArray

    suspend fun decrypt(ciphertext: ByteArray): ByteArray

    /** Deletes the wrapped key and all captures that depend on it. */
    suspend fun destroy()
}

/** Metadata for one capture. The image itself is encrypted on disk. */
data class IntruderEvent(
    val id: String,
    val capturedAtMillis: Long,
    val trigger: IntruderTrigger,
    val encryptedFileName: String,
)

/**
 * Captures a still on a trigger.
 *
 * Must fail closed and quietly: if the camera is unavailable, return null and record a tamper-log
 * event; never crash the unlock screen. The Android camera privacy indicator is expected.
 */
interface IntruderCapture {
    suspend fun capture(trigger: IntruderTrigger): IntruderEvent?
}

/**
 * Encrypted intruder log.
 *
 * Viewing requires reauthentication. Images are decrypted into RAM only. Purging is enforced
 * daily by WorkManager and again on app start.
 */
interface IntruderLog {
    fun observe(): Flow<List<IntruderEvent>>

    /** Decrypts one image into RAM. The caller owns and must zero the array. */
    suspend fun readImage(eventId: String): ByteArray

    /** Deletes events older than [cutoffMillis]. Returns the number deleted. */
    suspend fun purgeBefore(cutoffMillis: Long): Int

    suspend fun deleteAll()
}
