package com.keeper.vault.auth

import com.keeper.vault.contracts.auth.AttemptLimiter
import com.keeper.vault.contracts.auth.AuthManager
import com.keeper.vault.contracts.auth.SecretType
import com.keeper.vault.contracts.auth.SecretValidation
import com.keeper.vault.contracts.auth.SecretValidator
import com.keeper.vault.contracts.auth.UnlockResult
import com.keeper.vault.contracts.auth.VaultMode
import com.keeper.vault.contracts.crypto.BiometricVaultKeyStore
import com.keeper.vault.contracts.crypto.VaultKey
import com.keeper.vault.contracts.crypto.VaultKeyManager
import com.keeper.vault.contracts.platform.Clock
import com.keeper.vault.contracts.platform.KeeperException
import com.keeper.vault.contracts.platform.SecretZeroizer
import java.util.concurrent.CancellationException
import javax.crypto.Cipher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Authentication manager for all four secret types.
 *
 * The secret is authoritative and is only ever handled as `CharArray`. A type-specific domain
 * prefix is prepended before PBKDF2, so the same characters entered as a different type cannot
 * unlock the vault. Pattern and signature callers pass the canonical form produced by
 * [PatternCanonicalizer] and [SignatureCanonicalizer].
 *
 * Biometrics are an additional path through a separate auth-bound Keystore key. If they are
 * removed, re-enrolled, or fail, [unlock] still works.
 */
class DefaultAuthManager(
    private val keyManager: VaultKeyManager,
    private val validator: SecretValidator,
    private val attemptLimiter: AttemptLimiter,
    private val sessions: AuthSessionStore,
    private val clock: Clock,
    private val biometricStore: BiometricVaultKeyStore? = null,
    private val biometricEnvelopes: BiometricEnvelopeStore? = null,
    private val biometricAvailability: BiometricAvailability = BiometricAvailability { false },
    private val secretTypeStore: SecretTypeStore? = null,
    private val attemptPolicy: AttemptPolicy = AttemptPolicy(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AuthManager, BiometricAuth {

    override suspend fun isConfigured(): Boolean = withContext(dispatcher) {
        keyManager.exists()
    }

    override suspend fun setup(type: SecretType, secret: CharArray) {
        if (secret.isEmpty()) {
            throw KeeperException.Auth("Secret must not be empty")
        }
        when (val validation = validator.validate(type, secret)) {
            is SecretValidation.Invalid -> throw KeeperException.Auth(validation.reason)
            SecretValidation.Valid -> Unit
        }
        if (withContext(dispatcher) { keyManager.exists() }) {
            throw KeeperException.Auth("A vault already exists")
        }

        val derived = domainSeparated(type, secret)
        try {
            secretTypeStore?.save(type)
            val vaultKey = withContext(dispatcher) { keyManager.create(derived) }
            openSession(vaultKey)
            resetAttemptsQuietly()
        } catch (e: CancellationException) {
            throw e
        } catch (e: KeeperException.Auth) {
            throw e
        } catch (e: Exception) {
            throw KeeperException.Auth("Could not create the vault", e)
        } finally {
            SecretZeroizer.zero(derived)
        }
    }

    override suspend fun unlock(type: SecretType, secret: CharArray): UnlockResult {
        if (secret.isEmpty()) {
            return UnlockResult.Rejected("Enter your secret")
        }
        val attempts = attemptLimiter.current()
        val cooldownUntil = attempts.cooldownUntilMillis
        if (attempts.lockedOut && cooldownUntil != null) {
            return UnlockResult.LockedOut(cooldownUntil)
        }

        val derived = domainSeparated(type, secret)
        return try {
            val vaultKey = withContext(dispatcher) { keyManager.unlock(derived) }
            resetAttemptsQuietly()
            openSession(vaultKey)
            UnlockResult.Success(VaultMode.REAL)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordFailure()
        } finally {
            SecretZeroizer.zero(derived)
        }
    }

    override suspend fun changeSecret(
        type: SecretType,
        oldSecret: CharArray,
        newSecret: CharArray,
    ): Boolean {
        if (oldSecret.isEmpty() || newSecret.isEmpty()) return false
        if (validator.validate(type, newSecret) !is SecretValidation.Valid) return false

        val oldDerived = domainSeparated(type, oldSecret)
        val newDerived = domainSeparated(type, newSecret)
        return try {
            withContext(dispatcher) { keyManager.changeSecret(oldDerived, newDerived) }
            resetAttemptsQuietly()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordFailure()
            false
        } finally {
            SecretZeroizer.zero(oldDerived)
            SecretZeroizer.zero(newDerived)
        }
    }

    override suspend fun setBiometricEnabled(enabled: Boolean, secret: CharArray): Boolean {
        if (secret.isEmpty()) return false
        if (attemptLimiter.current().lockedOut) return false
        if (biometricStore == null || biometricEnvelopes == null) return false

        val verified = verifySecret(secret)
            ?: run {
                recordFailure()
                return false
            }

        return try {
            if (!enabled) {
                biometricStore.deleteKey()
                biometricEnvelopes.clear()
                resetAttemptsQuietly()
                true
            } else {
                enableBiometric(verified)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        } finally {
            verified.close()
        }
    }

    override suspend fun isBiometricEnabled(): Boolean =
        biometricStore != null && biometricEnvelopes?.load() != null

    // BiometricAuth ----------------------------------------------------------------------------

    override fun canAuthenticate(): Boolean = biometricAvailability.isAvailable()

    override fun isEnabled(): Boolean = biometricEnvelopes?.load() != null && biometricStore != null

    override fun createDecryptCipher(): Cipher? {
        val store = biometricStore ?: return null
        val envelope = biometricEnvelopes?.load() ?: return null
        return try {
            store.createDecryptCipher(envelope)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun completeUnlock(cipher: Cipher): UnlockResult {
        val store = biometricStore ?: return UnlockResult.Rejected("Biometrics are not available")
        val envelope = biometricEnvelopes?.load()
            ?: return UnlockResult.Rejected("Biometrics are not enabled")
        return try {
            val bytes = withContext(dispatcher) { store.unwrap(cipher, envelope) }
            val vaultKey = try {
                VaultKey.from(bytes)
            } finally {
                SecretZeroizer.zero(bytes)
            }
            resetAttemptsQuietly()
            openSession(vaultKey)
            UnlockResult.Success(VaultMode.REAL)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UnlockResult.Rejected("Biometric unlock failed")
        }
    }

    override fun createEncryptCipher(): Cipher? {
        val wrapper = biometricStore as? BiometricCipherWrapper ?: return null
        return try {
            wrapper.createEncryptCipher()
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun enableWithCurrentSession(cipher: Cipher): Boolean {
        val session = sessions.current() ?: return false
        val wrapper = biometricStore as? BiometricCipherWrapper ?: return false
        val envelopes = biometricEnvelopes ?: return false
        val keyBytes = session.vaultKey.copyBytes()
        return try {
            val envelope = withContext(dispatcher) { wrapper.wrapWithCipher(cipher, keyBytes) }
            envelopes.save(envelope)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        } finally {
            SecretZeroizer.zero(keyBytes)
        }
    }

    // Internals --------------------------------------------------------------------------------

    private suspend fun verifySecret(secret: CharArray): VaultKey? {
        val knownType = secretTypeStore?.load()
        val types = if (knownType != null) listOf(knownType) else SecretType.entries
        for (type in types) {
            val derived = domainSeparated(type, secret)
            val key = try {
                withContext(dispatcher) { keyManager.unlock(derived) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } finally {
                SecretZeroizer.zero(derived)
            }
            if (key != null) return key
        }
        return null
    }

    private suspend fun enableBiometric(vaultKey: VaultKey): Boolean {
        val store = biometricStore ?: return false
        val envelopes = biometricEnvelopes ?: return false
        if (!biometricAvailability.isAvailable()) return false
        val keyBytes = vaultKey.copyBytes()
        return try {
            val envelope = withContext(dispatcher) { store.wrap(keyBytes) }
            envelopes.save(envelope)
            resetAttemptsQuietly()
            true
        } finally {
            SecretZeroizer.zero(keyBytes)
        }
    }

    /**
     * A storage failure while clearing the counter must not turn a successful unlock into a
     * failure. Keeping the old count is the conservative outcome.
     */
    private suspend fun resetAttemptsQuietly() {
        try {
            attemptLimiter.reset()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep the previous counter; the key has already been obtained.
        }
    }

    private fun openSession(vaultKey: VaultKey) {
        sessions.open(vaultKey, VaultMode.REAL, clock.nowMillis())
    }

    private suspend fun recordFailure(): UnlockResult.WrongSecret {
        val state = attemptLimiter.recordFailure()
        val remaining = (attemptPolicy.maxFailures - state.failures).coerceAtLeast(0)
        return UnlockResult.WrongSecret(
            failures = state.failures,
            attemptsRemaining = remaining,
            cooldownUntilMillis = state.cooldownUntilMillis,
        )
    }

    private fun domainSeparated(type: SecretType, secret: CharArray): CharArray {
        val prefix = when (type) {
            SecretType.PASSWORD -> PASSWORD_PREFIX
            SecretType.PIN -> PIN_PREFIX
            SecretType.PATTERN -> PATTERN_PREFIX
            SecretType.SIGNATURE -> SIGNATURE_PREFIX
        }
        val out = CharArray(prefix.size + secret.size)
        prefix.copyInto(out)
        secret.copyInto(out, prefix.size)
        return out
    }

    private companion object {
        val PASSWORD_PREFIX = "keeper.auth.v1.password|".toCharArray()
        val PIN_PREFIX = "keeper.auth.v1.pin|".toCharArray()
        val PATTERN_PREFIX = "keeper.auth.v1.pattern|".toCharArray()
        val SIGNATURE_PREFIX = "keeper.auth.v1.signature|".toCharArray()
    }
}
