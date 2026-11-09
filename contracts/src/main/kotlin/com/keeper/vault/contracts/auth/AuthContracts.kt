package com.keeper.vault.contracts.auth

import kotlinx.coroutines.flow.StateFlow

/**
 * The four supported secret types.
 *
 * Passwords, PINs, patterns, and signatures are all entered as `CharArray` and converted to a
 * `CharArray` for PBKDF2. They are never stored as `String`.
 */
enum class SecretType {
    PASSWORD,
    PIN,
    PATTERN,
    SIGNATURE,
}

/** Setup-time policy. The UI and the validator must agree on these numbers. */
data class SecretPolicy(
    val minLength: Int,
    val maxLength: Int,
    val allowBiometricConvenience: Boolean,
) {
    init {
        require(minLength >= 1) { "minLength must be positive" }
        require(maxLength >= minLength) { "maxLength must be >= minLength" }
    }
}

/** Which vault the current unlock opens. */
enum class VaultMode {
    REAL,
    DECOY,
}

/**
 * Result of an unlock attempt.
 *
 * [WrongSecret] is deliberately uniform: it does not distinguish "no such vault", "wrong secret",
 * or "tag failure". It may carry attempt/cooldown state, which is not a cryptographic oracle.
 */
sealed class UnlockResult {
    data class Success(val mode: VaultMode) : UnlockResult()

    data class WrongSecret(
        val failures: Int,
        val attemptsRemaining: Int,
        val cooldownUntilMillis: Long?,
    ) : UnlockResult()

    data class LockedOut(val untilMillis: Long) : UnlockResult()

    data class Rejected(val reason: String) : UnlockResult()
}

/** Lock state consumed by every vault-content screen. */
sealed class LockState {
    data object Locked : LockState()

    data class Unlocked(
        val mode: VaultMode,
        val sinceMillis: Long,
    ) : LockState()
}

/**
 * Why the vault locked. Every reason must zero the live VaultKey and close read sessions.
 *
 * Presence reasons are emitted by the behavior layer; they never carry key material.
 */
enum class LockReason {
    MANUAL,
    APP_BACKGROUNDED,
    SCREEN_OFF,
    FACE_ABSENT,
    FACE_MISMATCH,
    MULTIPLE_FACES,
    BLINK_TIMEOUT,
    FAILED_ATTEMPTS,
    DURESS,
    TAMPER_WARNING,
    PROCESS_DEATH,
}

/**
 * Authentication boundary. Password/PIN/pattern/signature is authoritative.
 *
 * Biometric unlock is an additional convenience path backed by a separate auth-bound Keystore
 * key. It must never become the only way into the real vault.
 */
interface AuthManager {
    suspend fun isConfigured(): Boolean

    /** Creates the vault and the chosen secret. Rejects secrets that violate [SecretPolicy]. */
    suspend fun setup(type: SecretType, secret: CharArray)

    /** Attempts an unlock. Never logs the secret. */
    suspend fun unlock(type: SecretType, secret: CharArray): UnlockResult

    /** Re-wraps the vault key under a new secret. Requires the old secret. */
    suspend fun changeSecret(type: SecretType, oldSecret: CharArray, newSecret: CharArray): Boolean

    /**
     * Enables/disables the biometric convenience path. Enabling requires the secret to unwrap
     * the VaultKey once; disabling deletes the auth-bound Keystore key.
     */
    suspend fun setBiometricEnabled(enabled: Boolean, secret: CharArray): Boolean

    suspend fun isBiometricEnabled(): Boolean
}

/**
 * Process-wide lock authority.
 *
 * Locking must be idempotent and must happen on: background, screen off, face absence, face
 * mismatch, multiple faces, blink timeout, failed-attempt lockout, duress, and process death.
 */
interface LockController {
    val state: StateFlow<LockState>

    /** Called only after a successful secret or biometric unlock. */
    fun unlock(mode: VaultMode)

    /** Locks and zeroes live key material. */
    fun lock(reason: LockReason)

    /** Lifecycle hook: app moved to background. */
    fun onAppBackgrounded()

    /** Lifecycle hook: screen turned off. */
    fun onScreenOff()
}

/** Attempt state exposed to the unlock UI. */
data class AttemptState(
    val failures: Int,
    val cooldownUntilMillis: Long?,
    val lockedOut: Boolean,
)

/**
 * Failure counter and cooldown.
 *
 * Required behavior: after 5 failures, impose a cooldown; escalate on further failures; reset
 * only after a successful unlock. Cooldowns must survive process restart.
 */
interface AttemptLimiter {
    suspend fun current(): AttemptState

    suspend fun recordFailure(): AttemptState

    suspend fun reset()
}

/** Setup-time validation result. */
sealed class SecretValidation {
    data object Valid : SecretValidation()

    data class Invalid(val reason: String) : SecretValidation()
}

/** Validates a secret against [SecretPolicy] without storing it. */
fun interface SecretValidator {
    fun validate(type: SecretType, secret: CharArray): SecretValidation
}
