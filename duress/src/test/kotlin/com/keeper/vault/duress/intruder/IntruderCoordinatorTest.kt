package com.keeper.vault.duress.intruder

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.intruder.IntruderEvent
import com.keeper.vault.contracts.intruder.IntruderSettings
import com.keeper.vault.contracts.intruder.IntruderTrigger
import com.keeper.vault.duress.InMemoryIntruderSettingsPersistence
import com.keeper.vault.duress.MutableClock
import com.keeper.vault.duress.RecordingTamperLog
import com.keeper.vault.duress.enabledSettings
import com.keeper.vault.duress.settings.IntruderSettingsCodec
import com.keeper.vault.duress.settings.PersistentIntruderSettingsStore
import com.keeper.vault.duress.testEvent
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

class IntruderCoordinatorTest {

    private class RecordingCapture : ConfirmedIntruderCapture {
        val triggers = mutableListOf<IntruderTrigger>()
        val tokens = mutableListOf<String>()
        var result: IntruderEvent? = null
        var failure: Exception? = null

        override suspend fun captureConfirmed(trigger: IntruderTrigger, token: String): IntruderEvent? {
            triggers += trigger
            tokens += token
            failure?.let { throw it }
            return result
        }
    }

    private fun token(nowMillis: Long = 1_000L): String =
        CaptureConfirmationGate.issue(nowMillis)

    private fun store(
        settings: IntruderSettings,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ) = PersistentIntruderSettingsStore(
        persistence = InMemoryIntruderSettingsPersistence(
            IntruderSettingsCodec.encode(settings),
        ),
        dispatcher = dispatcher,
    )

    private fun coordinator(
        settings: IntruderSettings,
        capture: ConfirmedIntruderCapture,
        clock: MutableClock,
        tamperLog: RecordingTamperLog? = RecordingTamperLog(),
        repeatIntervalMillis: Long = 5 * 60_000L,
        maxCapturesPerDay: Int = 20,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ) = IntruderCoordinator(
        settings = store(settings, dispatcher),
        capture = capture,
        tamperLog = tamperLog,
        clock = clock,
        repeatIntervalMillis = repeatIntervalMillis,
        maxCapturesPerDay = maxCapturesPerDay,
    )

    @Test
    fun `disabled settings never offer a warning or capture`() = runTest {
        val capture = RecordingCapture()
        val subject = coordinator(
            settings = IntruderSettings(),
            capture = capture,
            clock = MutableClock(1_000L),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        assertThat(subject.requestWarning(failures = 99)).isNull()
        assertThat(subject.onFaceMismatch()).isNull()
        assertThat(capture.triggers).isEmpty()
    }

    @Test
    fun `a warning is offered from the counter but does not capture`() = runTest {
        val capture = RecordingCapture().apply {
            result = testEvent(IntruderTrigger.FAILED_UNLOCK, 1L)
        }
        val subject = coordinator(
            settings = enabledSettings(threshold = 5),
            capture = capture,
            clock = MutableClock(1_000L),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        assertThat(subject.requestWarning(failures = 4)).isNull()
        val warning = subject.requestWarning(failures = 5)
        assertThat(warning).isNotNull()
        assertThat(capture.triggers).isEmpty()
    }

    @Test
    fun `confirmation captures once`() = runTest {
        val event = testEvent(IntruderTrigger.FAILED_UNLOCK, 1_000L)
        val capture = RecordingCapture().apply { result = event }
        val tamper = RecordingTamperLog()
        val subject = coordinator(
            settings = enabledSettings(threshold = 5),
            capture = capture,
            clock = MutableClock(1_000L),
            tamperLog = tamper,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        val warning = subject.requestWarning(failures = 5)!!
        assertThat(subject.confirmCapture(warning, token())).isEqualTo(event)
        assertThat(subject.confirmCapture(warning, token())).isNull()

        assertThat(capture.triggers).containsExactly(IntruderTrigger.FAILED_UNLOCK)
        assertThat(tamper.signals).hasSize(1)
        assertThat(tamper.signals.single().detail).contains("FAILED_UNLOCK")
    }

    @Test
    fun `confirmation passes the UI-issued token to the capture gateway`() = runTest {
        val capture = RecordingCapture().apply {
            result = testEvent(IntruderTrigger.FAILED_UNLOCK, 1_000L)
        }
        val subject = coordinator(
            settings = enabledSettings(threshold = 5),
            capture = capture,
            clock = MutableClock(1_000L),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val issued = token()

        val warning = subject.requestWarning(failures = 5)!!
        assertThat(subject.confirmCapture(warning, issued)).isNotNull()

        assertThat(capture.tokens).containsExactly(issued)
    }

    @Test
    fun `a rejected confirmation revokes its token`() = runTest {
        val capture = RecordingCapture().apply {
            result = testEvent(IntruderTrigger.FAILED_UNLOCK, 1_000L)
        }
        val clock = MutableClock(1_000L)
        val subject = coordinator(
            settings = enabledSettings(threshold = 5),
            capture = capture,
            clock = clock,
            repeatIntervalMillis = 60_000L,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        subject.confirmCapture(subject.requestWarning(failures = 5)!!, token())

        clock.nowMillis += 1_000L
        val rejected = token(clock.nowMillis)
        assertThat(subject.confirmCapture(IntruderWarningRequest(5), rejected)).isNull()

        assertThat(CaptureConfirmationGate.isValid(rejected, clock.nowMillis)).isFalse()
        assertThat(capture.tokens).hasSize(1)
    }

    @Test
    fun `cancelling a warning does not capture or consume the rate limit`() = runTest {
        val capture = RecordingCapture().apply {
            result = testEvent(IntruderTrigger.FAILED_UNLOCK, 1L)
        }
        val subject = coordinator(
            settings = enabledSettings(threshold = 5),
            capture = capture,
            clock = MutableClock(1_000L),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        assertThat(subject.requestWarning(failures = 5)).isNotNull()
        // The caller cancels; nothing is confirmed.
        assertThat(capture.triggers).isEmpty()
        assertThat(subject.requestWarning(failures = 6)).isNotNull()
        assertThat(capture.triggers).isEmpty()
    }

    @Test
    fun `repeats are rate limited until the window elapses`() = runTest {
        val capture = RecordingCapture().apply {
            result = testEvent(IntruderTrigger.FAILED_UNLOCK, 1L)
        }
        val clock = MutableClock(1_000L)
        val subject = coordinator(
            settings = enabledSettings(threshold = 5),
            capture = capture,
            clock = clock,
            repeatIntervalMillis = 60_000L,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        subject.confirmCapture(subject.requestWarning(failures = 5)!!, token())
        clock.nowMillis += 59_999L
        assertThat(subject.requestWarning(failures = 6)).isNull()
        clock.nowMillis += 1L
        val next = subject.requestWarning(failures = 7)
        assertThat(next).isNotNull()
        subject.confirmCapture(next!!, token())
        assertThat(capture.triggers).hasSize(2)
    }

    @Test
    fun `a backwards clock cannot bypass the rate limit`() = runTest {
        val capture = RecordingCapture().apply {
            result = testEvent(IntruderTrigger.FAILED_UNLOCK, 1L)
        }
        val clock = MutableClock(10_000L)
        val subject = coordinator(
            settings = enabledSettings(threshold = 5),
            capture = capture,
            clock = clock,
            repeatIntervalMillis = 60_000L,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        subject.confirmCapture(subject.requestWarning(failures = 5)!!, token())
        clock.nowMillis = 1L

        assertThat(subject.requestWarning(failures = 6)).isNull()
        assertThat(capture.triggers).hasSize(1)
    }

    @Test
    fun `face mismatch never captures even when settings claim it is on`() = runTest {
        val event = testEvent(IntruderTrigger.FACE_MISMATCH, 1_000L)
        val capture = RecordingCapture().apply { result = event }
        val subject = coordinator(
            settings = enabledSettings(threshold = 5, mismatch = true),
            capture = capture,
            clock = MutableClock(1_000L),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        assertThat(subject.onFaceMismatch()).isNull()
        assertThat(capture.triggers).isEmpty()
    }

    @Test
    fun `a failed confirmed capture does not arm the rate limit`() = runTest {
        val capture = RecordingCapture().apply { failure = IllegalStateException("camera busy") }
        val tamper = RecordingTamperLog()
        val subject = coordinator(
            settings = enabledSettings(threshold = 5),
            capture = capture,
            clock = MutableClock(1_000L),
            tamperLog = tamper,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        val first = subject.requestWarning(failures = 5)!!
        assertThat(subject.confirmCapture(first, token())).isNull()
        val second = subject.requestWarning(failures = 6)
        assertThat(second).isNotNull()
        assertThat(subject.confirmCapture(second!!, token())).isNull()

        assertThat(capture.triggers).hasSize(2)
        assertThat(tamper.signals).isEmpty()
    }

    @Test
    fun `the daily capture cap bounds storage abuse`() = runTest {
        val capture = RecordingCapture().apply {
            result = testEvent(IntruderTrigger.FAILED_UNLOCK, 1L)
        }
        val clock = MutableClock(1_000L)
        val subject = coordinator(
            settings = enabledSettings(threshold = 5),
            capture = capture,
            clock = clock,
            repeatIntervalMillis = 0L,
            maxCapturesPerDay = 2,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        subject.confirmCapture(subject.requestWarning(failures = 5)!!, token())
        clock.nowMillis += 1_000L
        subject.confirmCapture(subject.requestWarning(failures = 6)!!, token())
        clock.nowMillis += 1_000L
        assertThat(subject.requestWarning(failures = 7)).isNull()

        clock.nowMillis += IntruderRetention.MILLIS_PER_DAY
        assertThat(subject.requestWarning(failures = 8)).isNotNull()
        assertThat(capture.triggers).hasSize(2)
    }

    @Test
    fun `tamper detail never contains capture content`() = runTest {
        val tamper = RecordingTamperLog()
        val capture = RecordingCapture().apply {
            result = testEvent(IntruderTrigger.FAILED_UNLOCK, 1_000L)
        }
        val subject = coordinator(
            settings = enabledSettings(threshold = 5),
            capture = capture,
            clock = MutableClock(1_000L),
            tamperLog = tamper,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        subject.confirmCapture(subject.requestWarning(failures = 5)!!, token())

        val detail = tamper.signals.single().detail.orEmpty()
        assertThat(detail).doesNotContain("image")
        assertThat(detail).doesNotContain("base64")
    }
}
