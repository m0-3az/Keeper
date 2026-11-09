package com.keeper.vault.duress.duress

import com.keeper.vault.contracts.crypto.VaultKeyManager
import com.keeper.vault.contracts.platform.SecretZeroizer
import kotlinx.coroutines.CancellationException

/**
 * Returns true only when [candidate] opens the real vault's key envelope.
 *
 * Used by [DefaultDuressController.configure] to refuse a duress secret that is the user's real
 * secret. The frozen [com.keeper.vault.contracts.auth.AuthManager] has no verify-without-side-
 * effects call, and calling `unlock` would record failures and open a session, so the check reads
 * the real envelope directly instead.
 */
fun interface RealVaultSecretProbe {
    suspend fun unlocksRealVault(candidate: CharArray): Boolean
}

/**
 * Envelope-backed probe.
 *
 * `DefaultAuthManager` domain-separates the secret with a type prefix before PBKDF2. The prefixes
 * below mirror the v1 auth protocol so a candidate equal to the real secret under any of the four
 * secret types is recognised. If auth ever changes its domain separation, this guard degrades to
 * "not detected" but never blocks configuration; it is a best-effort safety check, not a
 * cryptographic boundary. The probe has no side effects on the attempt limiter.
 */
class EnvelopeRealVaultSecretProbe(
    private val keyManager: VaultKeyManager,
) : RealVaultSecretProbe {
    override suspend fun unlocksRealVault(candidate: CharArray): Boolean {
        if (candidate.isEmpty()) return false
        for (prefix in AuthDomainPrefixes.ALL) {
            val derived = prefix + candidate
            try {
                val key = keyManager.unlock(derived)
                key.close()
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Wrong secret for this type; try the next one.
            } finally {
                SecretZeroizer.zero(derived)
            }
        }
        return false
    }
}

/**
 * v1 auth domain prefixes, mirrored from `DefaultAuthManager`.
 *
 * Kept here rather than duplicated in a shared module because contracts are frozen and the auth
 * constants are private. This is intentionally documented as a best-effort equality guard.
 */
internal object AuthDomainPrefixes {
    internal val PASSWORD = "keeper.auth.v1.password|".toCharArray()
    private val PIN = "keeper.auth.v1.pin|".toCharArray()
    private val PATTERN = "keeper.auth.v1.pattern|".toCharArray()
    private val SIGNATURE = "keeper.auth.v1.signature|".toCharArray()

    val ALL: List<CharArray>
        get() = listOf(PASSWORD, PIN, PATTERN, SIGNATURE)
}
