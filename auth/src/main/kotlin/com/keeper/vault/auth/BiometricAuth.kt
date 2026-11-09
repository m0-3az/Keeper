package com.keeper.vault.auth

import com.keeper.vault.contracts.auth.UnlockResult
import com.keeper.vault.contracts.crypto.BiometricEnvelope
import com.keeper.vault.contracts.crypto.BiometricVaultKeyStore
import javax.crypto.Cipher
/** Class 3 biometric availability, injectable so the UI and tests agree. */
fun interface BiometricAvailability {
    fun isAvailable(): Boolean
}

/**
 * Extra operations needed to enroll the biometric path with a real auth-bound Keystore key.
 *
 * The frozen [BiometricVaultKeyStore] interface can wrap with an internally initialized cipher,
 * which is enough for fakes and for keys without per-use authentication. Android auth-per-use keys
 * require the encryption cipher to be authorized by `BiometricPrompt` first, so the concrete
 * adapter also exposes those two operations.
 */
interface BiometricCipherWrapper {
    fun createEncryptCipher(): Cipher

    fun wrapWithCipher(cipher: Cipher, vaultKey: ByteArray): BiometricEnvelope
}

/**
 * Biometric convenience surface used by the unlock screen.
 *
 * It is additive: every method fails closed, and the secret path remains authoritative.
 */
interface BiometricAuth {
    fun canAuthenticate(): Boolean

    fun isEnabled(): Boolean

    /** Initialized decrypt cipher for `BiometricPrompt.CryptoObject`, or null if unusable. */
    fun createDecryptCipher(): Cipher?

    /** Completes an unlock after the OS reports biometric success with [cipher]. */
    suspend fun completeUnlock(cipher: Cipher): UnlockResult

    /** Initialized encrypt cipher for the enrollment prompt, when supported. */
    fun createEncryptCipher(): Cipher?

    /** Wraps the live session key after an enrollment prompt succeeded. */
    suspend fun enableWithCurrentSession(cipher: Cipher): Boolean
}
