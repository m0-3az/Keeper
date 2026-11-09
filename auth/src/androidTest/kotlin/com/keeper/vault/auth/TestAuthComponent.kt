package com.keeper.vault.auth

import com.keeper.vault.contracts.auth.AttemptLimiter
import com.keeper.vault.contracts.auth.AttemptState
import com.keeper.vault.contracts.auth.AuthManager
import com.keeper.vault.contracts.auth.LockController
import com.keeper.vault.contracts.auth.SecretType
import com.keeper.vault.contracts.auth.UnlockResult
import com.keeper.vault.contracts.auth.VaultMode
import com.keeper.vault.contracts.platform.Clock
import javax.crypto.Cipher

internal class FakeAuthManager : AuthManager {
    var configured: Boolean = false
    var expectedType: SecretType = SecretType.PASSWORD
    var expectedSecret: CharArray = "correct horse".toCharArray()
    var acceptAnyNonEmpty: Boolean = false
    var setupType: SecretType? = null
        private set
    var setupSecret: CharArray? = null
        private set
    var unlockCalls: Int = 0
        private set

    override suspend fun isConfigured(): Boolean = configured

    override suspend fun setup(type: SecretType, secret: CharArray) {
        setupType = type
        setupSecret?.fill('\u0000')
        setupSecret = secret.copyOf()
        expectedType = type
        expectedSecret.fill('\u0000')
        expectedSecret = secret.copyOf()
        configured = true
    }

    override suspend fun unlock(type: SecretType, secret: CharArray): UnlockResult {
        unlockCalls++
        val matches = acceptAnyNonEmpty && secret.isNotEmpty() ||
            (type == expectedType && secret.contentEquals(expectedSecret))
        return if (matches) {
            UnlockResult.Success(VaultMode.REAL)
        } else {
            UnlockResult.WrongSecret(failures = 1, attemptsRemaining = 4, cooldownUntilMillis = null)
        }
    }

    override suspend fun changeSecret(
        type: SecretType,
        oldSecret: CharArray,
        newSecret: CharArray,
    ): Boolean = true

    override suspend fun setBiometricEnabled(enabled: Boolean, secret: CharArray): Boolean = true

    override suspend fun isBiometricEnabled(): Boolean = false
}

internal class FakeBiometricAuth : BiometricAuth {
    var enabled: Boolean = false
    var available: Boolean = false
    var completeResult: UnlockResult = UnlockResult.Rejected("not configured")

    override fun canAuthenticate(): Boolean = available

    override fun isEnabled(): Boolean = enabled

    override fun createDecryptCipher(): Cipher? = null

    override suspend fun completeUnlock(cipher: Cipher): UnlockResult = completeResult

    override fun createEncryptCipher(): Cipher? = null

    override suspend fun enableWithCurrentSession(cipher: Cipher): Boolean = false
}

internal class FakeAttemptLimiter : AttemptLimiter {
    var state: AttemptState = AttemptState(failures = 0, cooldownUntilMillis = null, lockedOut = false)
    var resetCount: Int = 0
        private set

    override suspend fun current(): AttemptState = state

    override suspend fun recordFailure(): AttemptState {
        state = state.copy(failures = state.failures + 1)
        return state
    }

    override suspend fun reset() {
        state = AttemptState(failures = 0, cooldownUntilMillis = null, lockedOut = false)
        resetCount++
    }
}

internal class FakeSecretTypeStoreForTest(
    var type: SecretType? = SecretType.PASSWORD,
) : SecretTypeStore {
    override fun load(): SecretType? = type

    override fun save(type: SecretType) {
        this.type = type
    }

    override fun clear() {
        type = null
    }
}

internal class TestAuthComponent : AuthComponent {
    val fakeAuthManager = FakeAuthManager()
    val fakeBiometric = FakeBiometricAuth()
    val fakeLimiter = FakeAttemptLimiter()
    val fakeTypeStore = FakeSecretTypeStoreForTest()
    val testSessions = AuthSessionStore()
    override val lockController: LockController = DefaultLockController(
        testSessions,
        Clock { System.currentTimeMillis() },
    )

    override val authManager: AuthManager = fakeAuthManager
    override val biometricAuth: BiometricAuth = fakeBiometric
    override val attemptLimiter: AttemptLimiter = fakeLimiter
    override val sessions: AuthSessionStore = testSessions
    override val secretTypeStore: SecretTypeStore = fakeTypeStore

    override fun installLifecycle() = Unit

}
