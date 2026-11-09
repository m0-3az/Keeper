package com.keeper.vault.duress.duress

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.platform.KeeperException
import com.keeper.vault.contracts.vault.VaultRepository
import com.keeper.vault.duress.FakeVaultKeyManager
import com.keeper.vault.duress.MutableClock
import com.keeper.vault.duress.RecordingTamperLog
import com.keeper.vault.vault.session.VaultSessionHolder
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DuressControllerTest {

    private val keyManager = FakeVaultKeyManager()
    private val decoyVault = FakeDecoyVault()
    private val tamperLog = RecordingTamperLog()
    private var realVaultUnlocked = true
    private var realSecretDetected = false
    private var lockedRealVault = false

    private fun controller(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ): DefaultDuressController = DefaultDuressController(
        isRealVaultUnlocked = { realVaultUnlocked },
        decoyKeyManager = keyManager,
        realSecretProbe = RealVaultSecretProbe { realSecretDetected },
        decoyVault = decoyVault,
        tamperLog = tamperLog,
        clock = MutableClock(1_234L),
        ioDispatcher = dispatcher,
        onDuressUnlock = { lockedRealVault = true },
    )

    @Test
    fun `configure requires an unlocked real vault`() = runTest {
        realVaultUnlocked = false
        val subject = controller(UnconfinedTestDispatcher(testScheduler))

        val failure = runCatching { subject.configure("duress".toCharArray()) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(KeeperException.Duress::class.java)
        assertThat(keyManager.isConfigured).isFalse()
    }

    @Test
    fun `configure rejects an empty secret`() = runTest {
        val subject = controller(UnconfinedTestDispatcher(testScheduler))

        val failure = runCatching { subject.configure(CharArray(0)) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(KeeperException.Duress::class.java)
        assertThat(keyManager.isConfigured).isFalse()
    }

    @Test
    fun `configure refuses a secret that also opens the real vault`() = runTest {
        realSecretDetected = true
        val subject = controller(UnconfinedTestDispatcher(testScheduler))

        val failure = runCatching { subject.configure("same-as-real".toCharArray()) }
            .exceptionOrNull()

        assertThat(failure).isInstanceOf(KeeperException.Duress::class.java)
        assertThat(failure?.message).contains("differ")
        assertThat(keyManager.isConfigured).isFalse()
    }

    @Test
    fun `configure creates the decoy envelope and records a tamper event`() = runTest {
        val subject = controller(UnconfinedTestDispatcher(testScheduler))

        assertThat(subject.isConfigured()).isFalse()
        subject.configure("duress".toCharArray())

        assertThat(subject.isConfigured()).isTrue()
        assertThat(keyManager.isConfigured).isTrue()
        assertThat(tamperLog.signals).hasSize(1)
        assertThat(tamperLog.signals.single().detail).contains("duress")
    }

    @Test
    fun `configure will not silently overwrite an existing decoy`() = runTest {
        val subject = controller(UnconfinedTestDispatcher(testScheduler))
        subject.configure("duress".toCharArray())

        val failure = runCatching { subject.configure("second".toCharArray()) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(KeeperException.Duress::class.java)
    }

    @Test
    fun `isDuressSecret accepts only the configured secret`() = runTest {
        val subject = controller(UnconfinedTestDispatcher(testScheduler))
        subject.configure("duress".toCharArray())

        assertThat(subject.isDuressSecret("duress".toCharArray())).isTrue()
        assertThat(subject.isDuressSecret("not-duress".toCharArray())).isFalse()
        assertThat(subject.isDuressSecret(CharArray(0))).isFalse()
    }

    @Test
    fun `isDuressSecret is false before configuration`() = runTest {
        val subject = controller(UnconfinedTestDispatcher(testScheduler))

        assertThat(subject.isDuressSecret("anything".toCharArray())).isFalse()
    }

    @Test
    fun `clear requires an unlocked real vault`() = runTest {
        val subject = controller(UnconfinedTestDispatcher(testScheduler))
        subject.configure("duress".toCharArray())
        realVaultUnlocked = false

        val failure = runCatching { subject.clear() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(KeeperException.Duress::class.java)
        assertThat(keyManager.isConfigured).isTrue()
        assertThat(decoyVault.destroyed).isFalse()
    }

    @Test
    fun `clear removes the envelope, the decoy vault and records a tamper event`() = runTest {
        val subject = controller(UnconfinedTestDispatcher(testScheduler))
        subject.configure("duress".toCharArray())

        subject.clear()

        assertThat(subject.isConfigured()).isFalse()
        assertThat(keyManager.isConfigured).isFalse()
        assertThat(decoyVault.destroyed).isTrue()
        assertThat(tamperLog.signals).hasSize(2)
    }

    @Test
    fun `openDecoy installs the decoy key only for the duress secret`() = runTest {
        val subject = controller(UnconfinedTestDispatcher(testScheduler))
        subject.configure("duress".toCharArray())

        assertThat(subject.openDecoy("wrong".toCharArray())).isFalse()
        assertThat(decoyVault.session.currentKey()).isNull()

        assertThat(lockedRealVault).isFalse()

        assertThat(subject.openDecoy("duress".toCharArray())).isTrue()
        assertThat(lockedRealVault).isTrue()
        assertThat(decoyVault.session.currentKey()).isNotNull()

        decoyVault.session.lock()
    }

    @Test
    fun `openDecoy is false when nothing is configured`() = runTest {
        val subject = controller(UnconfinedTestDispatcher(testScheduler))

        assertThat(subject.openDecoy("duress".toCharArray())).isFalse()
        assertThat(decoyVault.session.currentKey()).isNull()
    }
}

private class FakeDecoyVault : DecoyVault {
    override val session = VaultSessionHolder()
    var destroyed: Boolean = false
        private set

    override fun repository(): VaultRepository =
        throw UnsupportedOperationException("not needed in this test")

    override suspend fun destroy() {
        destroyed = true
        session.lock()
    }
}
