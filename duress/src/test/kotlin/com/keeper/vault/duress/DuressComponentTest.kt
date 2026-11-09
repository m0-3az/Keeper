package com.keeper.vault.duress

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.duress.DuressController
import com.keeper.vault.contracts.intruder.IntruderCapture
import com.keeper.vault.contracts.intruder.IntruderEvent
import com.keeper.vault.contracts.intruder.IntruderLog
import com.keeper.vault.contracts.intruder.IntruderTrigger
import com.keeper.vault.contracts.platform.KeeperException
import com.keeper.vault.duress.duress.DecoyUnlocker
import com.keeper.vault.duress.duress.DecoyVault
import com.keeper.vault.duress.intruder.ConfirmedIntruderCapture
import com.keeper.vault.duress.intruder.IntruderCoordinator
import com.keeper.vault.duress.intruder.IntruderRecorder
import com.keeper.vault.duress.settings.IntruderSettingsCodec
import com.keeper.vault.duress.settings.PersistentIntruderSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

class DuressComponentTest {

    private class RecordingLog : IntruderLog, IntruderRecorder {
        val purges = mutableListOf<Long>()
        var deletedAll: Boolean = false
        val recorded = mutableListOf<IntruderEvent>()

        override fun observe(): Flow<List<IntruderEvent>> = flowOf(emptyList())

        override suspend fun readImage(eventId: String): ByteArray = ByteArray(0)

        override suspend fun purgeBefore(cutoffMillis: Long): Int {
            purges += cutoffMillis
            return 7
        }

        override suspend fun deleteAll() {
            deletedAll = true
        }

        override suspend fun record(
            trigger: IntruderTrigger,
            plaintextImage: ByteArray,
        ): IntruderEvent = throw UnsupportedOperationException()

        override suspend fun recordReserved(
            event: IntruderEvent,
            plaintextImage: ByteArray,
        ): IntruderEvent = throw UnsupportedOperationException()
    }

    private class FakeController(
        private val opens: Boolean = true,
    ) : DuressController {
        override suspend fun isConfigured(): Boolean = true
        override suspend fun configure(duressSecret: CharArray) = Unit
        override suspend fun isDuressSecret(candidate: CharArray): Boolean = opens
        override suspend fun clear() = Unit
    }

    private class OpenableController(
        private val opens: Boolean,
    ) : DuressController, DecoyUnlocker {
        override suspend fun isConfigured(): Boolean = true
        override suspend fun configure(duressSecret: CharArray) = Unit
        override suspend fun isDuressSecret(candidate: CharArray): Boolean = opens
        override suspend fun clear() = Unit
        override suspend fun openDecoy(candidate: CharArray): Boolean = opens
    }

    private class NoopCapture : IntruderCapture, ConfirmedIntruderCapture {
        override suspend fun capture(trigger: IntruderTrigger): IntruderEvent? = null

        override suspend fun captureConfirmed(trigger: IntruderTrigger, token: String): IntruderEvent? = null
    }

    private val settings = PersistentIntruderSettingsStore(
        persistence = InMemoryIntruderSettingsPersistence(
            IntruderSettingsCodec.encode(enabledSettings(retentionDays = 7)),
        ),
        dispatcher = UnconfinedTestDispatcher(),
    )
    private val log = RecordingLog()
    private val clock = MutableClock(10 * IntruderRetentionDayMillis)

    @After
    fun tearDown() {
        DuressFeature.resetForTests()
    }

    private fun component(
        controller: DuressController = FakeController(),
        scheduler: () -> Unit = {},
        scope: CoroutineScope = CoroutineScope(UnconfinedTestDispatcher()),
    ) = DefaultDuressComponent(
        intruderSettings = settings,
        intruderLog = log,
        intruderRecorder = log,
        intruderCapture = NoopCapture(),
        intruderCoordinator = IntruderCoordinator(
            settings = settings,
            capture = NoopCapture(),
            tamperLog = null,
            clock = clock,
        ),
        duressController = controller,
        decoyVault = FakeDecoyVault(),
        tamperLog = null,
        clock = clock,
        purgeScheduler = scheduler,
        scope = scope,
    )

    @Test
    fun `purgeExpired applies the retention cutoff`() = runTest {
        val subject = component()

        val removed = subject.purgeExpired()

        assertThat(removed).isEqualTo(7)
        assertThat(log.purges)
            .containsExactly(clock.nowMillis - 7 * IntruderRetentionDayMillis)
    }

    @Test
    fun `deleteAllCaptures clears the log`() = runTest {
        component().deleteAllCaptures()

        assertThat(log.deletedAll).isTrue()
    }

    @Test
    fun `openDecoy delegates only for a decoy-capable controller`() = runTest {
        assertThat(component(controller = OpenableController(true)).openDecoy("x".toCharArray()))
            .isTrue()
        assertThat(component(controller = OpenableController(false)).openDecoy("x".toCharArray()))
            .isFalse()
        assertThat(component(controller = FakeController()).openDecoy("x".toCharArray())).isFalse()
    }

    @Test
    fun `start schedules the worker and purges once`() = runTest {
        var scheduled = 0
        val subject = component(
            scheduler = { scheduled++ },
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )

        subject.start()

        assertThat(scheduled).isEqualTo(1)
        assertThat(log.purges).hasSize(1)
    }

    @Test
    fun `start survives an unavailable scheduler`() = runTest {
        val subject = component(
            scheduler = { throw IllegalStateException("WorkManager unavailable") },
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )

        subject.start()

        assertThat(log.purges).hasSize(1)
    }

    @Test
    fun `DuressFeature installs, starts and resets the component`() {
        val subject = component()

        assertThat(DuressFeature.componentOrNull()).isNull()
        DuressFeature.install(subject)
        assertThat(DuressFeature.componentOrNull()).isSameInstanceAs(subject)
        DuressFeature.resetForTests()
        assertThat(DuressFeature.componentOrNull()).isNull()
    }

    @Test
    fun `the disclosure contains the mandatory legal sentence`() {
        assertThat(IntruderCopy.DISCLOSURE)
            .contains("You are responsible for complying with local privacy laws.")
        assertThat(IntruderCopy.DISCLOSURE).contains("never uploaded")
        assertThat(IntruderCopy.DISCLOSURE).contains("camera privacy indicator")
    }
}

private const val IntruderRetentionDayMillis: Long = 24L * 60L * 60L * 1000L

private class FakeDecoyVault : DecoyVault {
    override val session = com.keeper.vault.vault.session.VaultSessionHolder()

    override fun repository(): com.keeper.vault.contracts.vault.VaultRepository =
        throw UnsupportedOperationException()

    override suspend fun destroy() = Unit
}
