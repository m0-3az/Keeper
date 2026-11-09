package com.keeper.vault.app

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.auth.UnlockCaptureUiState
import com.keeper.vault.contracts.intruder.IntruderEvent
import com.keeper.vault.contracts.intruder.IntruderSettings
import com.keeper.vault.contracts.intruder.IntruderSettingsStore
import com.keeper.vault.contracts.intruder.IntruderTrigger
import com.keeper.vault.contracts.platform.Clock
import com.keeper.vault.duress.intruder.CaptureConfirmationGate
import com.keeper.vault.duress.intruder.ConfirmedIntruderCapture
import com.keeper.vault.duress.intruder.IntruderCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

class UnlockIntruderHostTest {
    @After
    fun tearDown() {
        CaptureConfirmationGate.clearForTests()
    }

    private class FakeSettings(initial: IntruderSettings) : IntruderSettingsStore {
        private val state = MutableStateFlow(initial)
        override val settings: StateFlow<IntruderSettings> = state

        override suspend fun update(transform: (IntruderSettings) -> IntruderSettings) {
            state.value = transform(state.value)
        }

        fun set(value: IntruderSettings) {
            state.value = value
        }
    }

    private class RecordingCapture : ConfirmedIntruderCapture {
        var calls = 0
        val tokens = mutableListOf<String>()

        override suspend fun captureConfirmed(trigger: IntruderTrigger, token: String): IntruderEvent? {
            calls += 1
            tokens += token
            return IntruderEvent(
                id = "event-$calls",
                capturedAtMillis = 1_000L,
                trigger = trigger,
                encryptedFileName = "event-$calls.enc",
            )
        }
    }

    private fun fixture(
        consent: Boolean = true,
        settings: IntruderSettings = IntruderSettings(enabled = true, failureThreshold = 5),
        capture: RecordingCapture = RecordingCapture(),
        scope: CoroutineScope,
        issueToken: () -> String = { CaptureConfirmationGate.issue(1_000L) },
        revokeToken: (String) -> Unit = { CaptureConfirmationGate.revoke(it) },
    ): Triple<UnlockIntruderHost, FakeSettings, RecordingCapture> {
        val store = FakeSettings(settings)
        val coordinator = IntruderCoordinator(
            settings = store,
            capture = capture,
            tamperLog = null,
            clock = Clock { 1_000L },
            repeatIntervalMillis = 0L,
        )
        return Triple(
            UnlockIntruderHost(
                coordinator = coordinator,
                settings = store,
                consentAccepted = { consent },
                scope = scope,
                issueToken = issueToken,
                revokeToken = revokeToken,
            ),
            store,
            capture,
        )
    }

    @Test
    fun `no warning or capture before the threshold`() = runTest {
        val (host, _, capture) = fixture(scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        host.onFailureCount(4)
        runCurrent()

        assertThat(host.warning.value).isNull()
        assertThat(capture.calls).isEqualTo(0)
    }

    @Test
    fun `warning appears from the counter and capture waits for confirmation`() = runTest {
        val (host, _, capture) = fixture(scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        host.onFailureCount(5)
        runCurrent()

        assertThat(host.warning.value).isNotNull()
        assertThat(capture.calls).isEqualTo(0)

        host.confirmCapture()
        runCurrent()

        assertThat(capture.calls).isEqualTo(1)
        val state = host.captureState.value
        assertThat(state).isInstanceOf(UnlockCaptureUiState.Started::class.java)
        assertThat((state as UnlockCaptureUiState.Started).message)
            .isEqualTo("Capture started. Check the intruder log for the result.")
    }

    @Test
    fun `the token is issued only after the user confirms`() = runTest {
        var issued = 0
        val capture = RecordingCapture()
        val (host, _, _) = fixture(
            capture = capture,
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            issueToken = { "token-${++issued}" },
        )

        host.onFailureCount(5)
        runCurrent()
        assertThat(host.warning.value).isNotNull()
        assertThat(issued).isEqualTo(0)
        assertThat(capture.tokens).isEmpty()

        host.confirmCapture()
        runCurrent()
        assertThat(issued).isEqualTo(1)
        assertThat(capture.tokens).containsExactly("token-1")
    }

    @Test
    fun `a rejected confirmation revokes the issued token`() = runTest {
        val revoked = mutableListOf<String>()
        val capture = RecordingCapture()
        val (host, store, _) = fixture(
            capture = capture,
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            issueToken = { "token-rejected" },
            revokeToken = { revoked += it },
        )

        host.onFailureCount(5)
        runCurrent()
        // The setting changes between the warning and the tap.
        store.set(store.settings.value.copy(enabled = false))

        host.confirmCapture()
        runCurrent()

        assertThat(capture.calls).isEqualTo(0)
        assertThat(revoked).containsExactly("token-rejected")
        assertThat(host.captureState.value)
            .isInstanceOf(UnlockCaptureUiState.Failure::class.java)
    }

    @Test
    fun `cancel consumes nothing and the next attempt can warn`() = runTest {
        val (host, _, capture) = fixture(scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        host.onFailureCount(5)
        runCurrent()
        host.cancelWarning()
        assertThat(host.warning.value).isNull()
        assertThat(capture.calls).isEqualTo(0)

        host.onFailureCount(6)
        runCurrent()
        assertThat(host.warning.value).isNotNull()
        assertThat(capture.calls).isEqualTo(0)
    }

    @Test
    fun `capture result clears after the visible window`() = runTest {
        val (host, _, _) = fixture(scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        host.onFailureCount(5)
        runCurrent()
        host.confirmCapture()
        runCurrent()
        assertThat(host.captureState.value).isInstanceOf(UnlockCaptureUiState.Started::class.java)

        advanceTimeBy(UnlockIntruderHost.RESULT_VISIBLE_MILLIS + 1L)
        runCurrent()
        assertThat(host.captureState.value).isEqualTo(UnlockCaptureUiState.Idle)
    }

    @Test
    fun `no consent means no warning`() = runTest {
        val (host, _, capture) = fixture(
            consent = false,
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )

        host.onFailureCount(5)
        runCurrent()

        assertThat(host.warning.value).isNull()
        assertThat(capture.calls).isEqualTo(0)
    }

    @Test
    fun `turn off disables the setting and does not capture`() = runTest {
        val (host, store, capture) = fixture(scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        host.onFailureCount(5)
        runCurrent()
        host.disableCapture()
        runCurrent()

        assertThat(store.settings.value.enabled).isFalse()
        assertThat(capture.calls).isEqualTo(0)
        assertThat(host.captureState.value).isInstanceOf(UnlockCaptureUiState.Disabled::class.java)
    }
}
