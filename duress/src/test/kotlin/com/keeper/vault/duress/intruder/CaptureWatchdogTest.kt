package com.keeper.vault.duress.intruder

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureWatchdogTest {

    @Test
    fun `fires exactly once when the timeout elapses`() = runTest {
        var timeouts = 0
        val watchdog = CaptureWatchdog(backgroundScope, timeoutMillis = 12_000L) {
            timeouts++
        }

        watchdog.start()
        advanceTimeBy(11_999L)
        runCurrent()
        assertThat(timeouts).isEqualTo(0)

        advanceTimeBy(1L)
        runCurrent()
        assertThat(timeouts).isEqualTo(1)

        advanceTimeBy(60_000L)
        runCurrent()
        assertThat(timeouts).isEqualTo(1)
    }

    @Test
    fun `cancelling before the deadline prevents the timeout`() = runTest {
        var timeouts = 0
        val watchdog = CaptureWatchdog(backgroundScope, timeoutMillis = 12_000L) {
            timeouts++
        }

        watchdog.start()
        advanceTimeBy(6_000L)
        runCurrent()
        watchdog.cancel()

        advanceTimeBy(60_000L)
        runCurrent()
        assertThat(timeouts).isEqualTo(0)
    }

    @Test
    fun `restarting resets the deadline without leaving a second timer`() = runTest {
        var timeouts = 0
        val watchdog = CaptureWatchdog(backgroundScope, timeoutMillis = 12_000L) {
            timeouts++
        }

        watchdog.start()
        advanceTimeBy(6_000L)
        runCurrent()
        watchdog.start()

        advanceTimeBy(6_000L)
        runCurrent()
        assertThat(timeouts).isEqualTo(0)

        advanceTimeBy(6_000L)
        runCurrent()
        assertThat(timeouts).isEqualTo(1)

        advanceTimeBy(60_000L)
        runCurrent()
        assertThat(timeouts).isEqualTo(1)
    }
}
