package com.keeper.vault.auth

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.auth.SecretType
import com.keeper.vault.contracts.auth.UnlockResult
import com.keeper.vault.contracts.auth.VaultMode
import com.keeper.vault.contracts.platform.KeeperException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultAuthManagerTest {
    private class Fixture(
        useTypeStore: Boolean = true,
    ) {
        val clock = FakeClock(now = 50_000L)
        val limiterStore = InMemoryAttemptStateStore()
        val limiter = PersistentAttemptLimiter(limiterStore, clock)
        val keyManager = FakeVaultKeyManager()
        val sessions = AuthSessionStore()
        val biometricStore = FakeBiometricVaultKeyStore()
        val envelopes = FakeBiometricEnvelopeStore()
        val secretTypeStore = FakeSecretTypeStore()
        var biometricAvailable = true

        val manager = DefaultAuthManager(
            keyManager = keyManager,
            validator = DefaultSecretValidator(),
            attemptLimiter = limiter,
            sessions = sessions,
            clock = clock,
            biometricStore = biometricStore,
            biometricEnvelopes = envelopes,
            biometricAvailability = BiometricAvailability { biometricAvailable },
            secretTypeStore = secretTypeStore.takeIf { useTypeStore },
            dispatcher = UnconfinedTestDispatcher(),
        )

        val lockController = DefaultLockController(sessions, clock)
    }

    @Test
    fun `setup works for all four secret types and opens a session`() = runTest {
        SecretType.entries.forEach { type ->
            val fixture = Fixture()

            fixture.manager.setup(type, secretFor(type))

            assertThat(fixture.manager.isConfigured()).isTrue()
            assertThat(fixture.keyManager.configured).isTrue()
            assertThat(fixture.secretTypeStore.type).isEqualTo(type)
            val session = fixture.sessions.current()
            assertThat(session).isNotNull()
            assertThat(session!!.mode).isEqualTo(VaultMode.REAL)
            assertThat(session.copyKeyBytes()).isEqualTo(fixture.keyManager.keyBytes)
            assertThat(fixture.limiter.current().failures).isEqualTo(0)
        }
    }

    @Test
    fun `unlock works for all four secret types`() = runTest {
        SecretType.entries.forEach { type ->
            val fixture = Fixture()
            fixture.manager.setup(type, secretFor(type))

            val result = fixture.manager.unlock(type, secretFor(type))

            assertThat(result).isEqualTo(UnlockResult.Success(VaultMode.REAL))
            assertThat(fixture.sessions.current()).isNotNull()
            assertThat(fixture.sessions.current()!!.copyKeyBytes())
                .isEqualTo(fixture.keyManager.keyBytes)
        }
    }

    @Test
    fun `wrong secret returns a uniform failure with attempt state`() = runTest {
        val fixture = Fixture()
        fixture.manager.setup(SecretType.PIN, "1234".toCharArray())
        fixture.sessions.close()

        val result = fixture.manager.unlock(SecretType.PIN, "9999".toCharArray())

        assertThat(result).isEqualTo(
            UnlockResult.WrongSecret(
                failures = 1,
                attemptsRemaining = 4,
                cooldownUntilMillis = null,
            ),
        )
        assertThat(fixture.sessions.current()).isNull()
    }

    @Test
    fun `the same characters in a different secret type do not unlock`() = runTest {
        val fixture = Fixture()
        fixture.manager.setup(SecretType.PIN, "1234".toCharArray())
        fixture.sessions.close()

        val result = fixture.manager.unlock(SecretType.PASSWORD, "1234".toCharArray())

        assertThat(result).isInstanceOf(UnlockResult.WrongSecret::class.java)
        assertThat(fixture.sessions.current()).isNull()
    }

    @Test
    fun `invalid setup secret is rejected before a vault is created`() = runTest {
        val fixture = Fixture()

        val error = runCatching {
            fixture.manager.setup(SecretType.PIN, "12".toCharArray())
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(KeeperException.Auth::class.java)
        assertThat(fixture.keyManager.createCount).isEqualTo(0)
        assertThat(fixture.manager.isConfigured()).isFalse()
    }

    @Test
    fun `a second setup is refused`() = runTest {
        val fixture = Fixture()
        fixture.manager.setup(SecretType.PASSWORD, "correct horse".toCharArray())

        val error = runCatching {
            fixture.manager.setup(SecretType.PASSWORD, "other horse".toCharArray())
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(KeeperException.Auth::class.java)
        assertThat(fixture.keyManager.createCount).isEqualTo(1)
    }

    @Test
    fun `empty unlock is rejected without recording a failure`() = runTest {
        val fixture = Fixture()
        fixture.manager.setup(SecretType.PASSWORD, "correct horse".toCharArray())

        val result = fixture.manager.unlock(SecretType.PASSWORD, CharArray(0))

        assertThat(result).isInstanceOf(UnlockResult.Rejected::class.java)
        assertThat(fixture.limiter.current().failures).isEqualTo(0)
    }

    @Test
    fun `the fifth failure starts a cooldown and the next attempt is locked out`() = runTest {
        val fixture = Fixture()
        fixture.manager.setup(SecretType.PIN, "1234".toCharArray())
        val before = fixture.keyManager.unlockCount

        repeat(4) {
            assertThat(fixture.manager.unlock(SecretType.PIN, "0000".toCharArray()))
                .isInstanceOf(UnlockResult.WrongSecret::class.java)
        }
        val fifth = fixture.manager.unlock(SecretType.PIN, "0000".toCharArray()) as UnlockResult.WrongSecret
        assertThat(fifth.failures).isEqualTo(5)
        assertThat(fifth.attemptsRemaining).isEqualTo(0)
        assertThat(fifth.cooldownUntilMillis).isEqualTo(fixture.clock.now + 30_000L)

        val sixth = fixture.manager.unlock(SecretType.PIN, "0000".toCharArray())

        assertThat(sixth).isEqualTo(UnlockResult.LockedOut(fixture.clock.now + 30_000L))
        assertThat(fixture.keyManager.unlockCount).isEqualTo(before + 5)
    }

    @Test
    fun `a successful unlock resets the attempt counter`() = runTest {
        val fixture = Fixture()
        fixture.manager.setup(SecretType.PIN, "1234".toCharArray())
        repeat(4) { fixture.manager.unlock(SecretType.PIN, "0000".toCharArray()) }

        fixture.manager.unlock(SecretType.PIN, "1234".toCharArray())

        assertThat(fixture.limiter.current().failures).isEqualTo(0)
    }

    @Test
    fun `changeSecret re-wraps and invalidates the old secret`() = runTest {
        val fixture = Fixture()
        fixture.manager.setup(SecretType.PASSWORD, "old password".toCharArray())

        val changed = fixture.manager.changeSecret(
            SecretType.PASSWORD,
            "old password".toCharArray(),
            "new password".toCharArray(),
        )

        assertThat(changed).isTrue()
        assertThat(fixture.manager.unlock(SecretType.PASSWORD, "old password".toCharArray()))
            .isInstanceOf(UnlockResult.WrongSecret::class.java)
        assertThat(fixture.manager.unlock(SecretType.PASSWORD, "new password".toCharArray()))
            .isEqualTo(UnlockResult.Success(VaultMode.REAL))
    }

    @Test
    fun `changeSecret with the wrong old secret fails and keeps the old secret valid`() = runTest {
        val fixture = Fixture()
        fixture.manager.setup(SecretType.PASSWORD, "old password".toCharArray())

        val changed = fixture.manager.changeSecret(
            SecretType.PASSWORD,
            "wrong password".toCharArray(),
            "new password".toCharArray(),
        )

        assertThat(changed).isFalse()
        assertThat(fixture.limiter.current().failures).isEqualTo(1)
        assertThat(fixture.manager.unlock(SecretType.PASSWORD, "old password".toCharArray()))
            .isEqualTo(UnlockResult.Success(VaultMode.REAL))
    }

    @Test
    fun `changeSecret rejects a new secret that violates policy`() = runTest {
        val fixture = Fixture()
        fixture.manager.setup(SecretType.PIN, "1234".toCharArray())

        val changed = fixture.manager.changeSecret(
            SecretType.PIN,
            "1234".toCharArray(),
            "12".toCharArray(),
        )

        assertThat(changed).isFalse()
        assertThat(fixture.limiter.current().failures).isEqualTo(0)
        assertThat(fixture.manager.unlock(SecretType.PIN, "1234".toCharArray()))
            .isEqualTo(UnlockResult.Success(VaultMode.REAL))
    }

    @Test
    fun `enabling biometrics wraps the vault key and disabling deletes it`() = runTest {
        val fixture = Fixture()
        fixture.manager.setup(SecretType.PIN, "1234".toCharArray())

        assertThat(fixture.manager.setBiometricEnabled(true, "1234".toCharArray())).isTrue()
        assertThat(fixture.manager.isBiometricEnabled()).isTrue()
        assertThat(fixture.envelopes.envelope).isNotNull()
        assertThat(fixture.biometricStore.wrapCount).isEqualTo(1)

        assertThat(fixture.manager.setBiometricEnabled(false, "1234".toCharArray())).isTrue()
        assertThat(fixture.manager.isBiometricEnabled()).isFalse()
        assertThat(fixture.envelopes.envelope).isNull()
        assertThat(fixture.biometricStore.deleted).isTrue()
    }

    @Test
    fun `enabling biometrics with the wrong secret fails`() = runTest {
        val fixture = Fixture()
        fixture.manager.setup(SecretType.PIN, "1234".toCharArray())

        val enabled = fixture.manager.setBiometricEnabled(true, "0000".toCharArray())

        assertThat(enabled).isFalse()
        assertThat(fixture.envelopes.envelope).isNull()
        assertThat(fixture.limiter.current().failures).isEqualTo(1)
    }

    @Test
    fun `disabling biometrics with the wrong secret does not delete the key`() = runTest {
        val fixture = Fixture()
        fixture.manager.setup(SecretType.PIN, "1234".toCharArray())
        fixture.manager.setBiometricEnabled(true, "1234".toCharArray())

        val disabled = fixture.manager.setBiometricEnabled(false, "0000".toCharArray())

        assertThat(disabled).isFalse()
        assertThat(fixture.biometricStore.deleted).isFalse()
        assertThat(fixture.envelopes.envelope).isNotNull()
    }

    @Test
    fun `biometrics cannot be enabled when no class 3 biometrics exist`() = runTest {
        val fixture = Fixture()
        fixture.manager.setup(SecretType.PIN, "1234".toCharArray())
        fixture.biometricAvailable = false

        val enabled = fixture.manager.setBiometricEnabled(true, "1234".toCharArray())

        assertThat(enabled).isFalse()
        assertThat(fixture.envelopes.envelope).isNull()
    }

    @Test
    fun `biometric setup can verify the secret without stored type metadata`() = runTest {
        val fixture = Fixture(useTypeStore = false)
        fixture.manager.setup(SecretType.PIN, "1234".toCharArray())

        assertThat(fixture.manager.setBiometricEnabled(true, "1234".toCharArray())).isTrue()
        assertThat(fixture.manager.isBiometricEnabled()).isTrue()
    }

    @Test
    fun `biometric completion opens a session and resets attempts`() = runTest {
        val fixture = Fixture()
        fixture.manager.setup(SecretType.PIN, "1234".toCharArray())
        fixture.manager.setBiometricEnabled(true, "1234".toCharArray())
        repeat(3) { fixture.limiter.recordFailure() }
        fixture.sessions.close()

        val cipher = fixture.manager.createDecryptCipher()
        assertThat(cipher).isNotNull()
        val result = fixture.manager.completeUnlock(cipher!!)

        assertThat(result).isEqualTo(UnlockResult.Success(VaultMode.REAL))
        assertThat(fixture.sessions.current()).isNotNull()
        assertThat(fixture.sessions.current()!!.copyKeyBytes())
            .isEqualTo(fixture.keyManager.keyBytes)
        assertThat(fixture.limiter.current().failures).isEqualTo(0)
    }

    @Test
    fun `biometric completion fails safely without an envelope`() = runTest {
        val fixture = Fixture()
        fixture.manager.setup(SecretType.PIN, "1234".toCharArray())
        fixture.sessions.close()
        val cipher = fixture.biometricStore.createDecryptCipher(
            com.keeper.vault.contracts.crypto.BiometricEnvelope(
                ByteArray(12) { 1 },
                ByteArray(32) { 2 },
            ),
        )

        val result = fixture.manager.completeUnlock(cipher)

        assertThat(result).isInstanceOf(UnlockResult.Rejected::class.java)
        assertThat(fixture.sessions.current()).isNull()
    }

    @Test
    fun `biometric unwrap failure returns a rejected result`() = runTest {
        val fixture = Fixture()
        fixture.manager.setup(SecretType.PIN, "1234".toCharArray())
        fixture.manager.setBiometricEnabled(true, "1234".toCharArray())
        fixture.sessions.close()
        fixture.biometricStore.unwrapFailure = true

        val result = fixture.manager.completeUnlock(fixture.manager.createDecryptCipher()!!)

        assertThat(result).isInstanceOf(UnlockResult.Rejected::class.java)
        assertThat(fixture.sessions.current()).isNull()
    }

    @Test
    fun `biometric unlock does not replace the authoritative secret path`() = runTest {
        val fixture = Fixture()
        fixture.manager.setup(SecretType.PIN, "1234".toCharArray())
        fixture.manager.setBiometricEnabled(true, "1234".toCharArray())
        fixture.manager.completeUnlock(fixture.manager.createDecryptCipher()!!)
        fixture.lockController.unlock(VaultMode.REAL)
        fixture.lockController.lock(com.keeper.vault.contracts.auth.LockReason.MANUAL)

        val result = fixture.manager.unlock(SecretType.PIN, "1234".toCharArray())

        assertThat(result).isEqualTo(UnlockResult.Success(VaultMode.REAL))
    }

    @Test
    fun `enabling biometrics with an authenticated cipher uses the live session`() = runTest {
        val fixture = Fixture()
        fixture.manager.setup(SecretType.PIN, "1234".toCharArray())
        val cipher = fixture.manager.createEncryptCipher()

        assertThat(cipher).isNotNull()
        assertThat(fixture.manager.enableWithCurrentSession(cipher!!)).isTrue()
        assertThat(fixture.manager.isBiometricEnabled()).isTrue()
    }

    @Test
    fun `enabling biometrics with a cipher requires a live session`() = runTest {
        val fixture = Fixture()
        fixture.manager.setup(SecretType.PIN, "1234".toCharArray())
        val cipher = fixture.manager.createEncryptCipher()!!
        fixture.sessions.close()

        assertThat(fixture.manager.enableWithCurrentSession(cipher)).isFalse()
        assertThat(fixture.manager.isBiometricEnabled()).isFalse()
    }

    @Test
    fun `locking zeroes the auth session opened by unlock`() = runTest {
        val fixture = Fixture()
        fixture.manager.setup(SecretType.PIN, "1234".toCharArray())
        fixture.manager.unlock(SecretType.PIN, "1234".toCharArray())
        val session = fixture.sessions.current()!!

        fixture.lockController.lock(com.keeper.vault.contracts.auth.LockReason.APP_BACKGROUNDED)

        assertThat(session.isClosed).isTrue()
        assertThat(session.vaultKey.copyBytes()).isEqualTo(ByteArray(32))
    }

    private fun secretFor(type: SecretType): CharArray = when (type) {
        SecretType.PASSWORD -> "correct horse".toCharArray()
        SecretType.PIN -> "1234".toCharArray()
        SecretType.PATTERN -> "0123".toCharArray()
        SecretType.SIGNATURE -> "S10123456789abcdef".toCharArray()
    }
}
