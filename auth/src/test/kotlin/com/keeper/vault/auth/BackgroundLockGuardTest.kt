package com.keeper.vault.auth

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.platform.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The SAF picker stops the process lifecycle. These tests pin the grace window that keeps the
 * vault unlocked long enough to choose and import a file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundLockGuardTest {

    private fun TestScope.guard(
        graceMillis: Long = 30_000L,
        onExpired: () -> Unit,
    ): BackgroundLockGuard = BackgroundLockGuard(
        scope = this,
        clock = Clock { testScheduler.currentTime },
        graceMillis = graceMillis,
        onGraceExpired = onExpired,
    )

    @Test
    fun `nothing is locked while the app is foregrounded`() = runTest {
        var locks = 0
        val guard = guard { locks++ }

        advanceTimeBy(60_000L)
        runCurrent()

        assertThat(locks).isEqualTo(0)
    }

    @Test
    fun `backgrounding does not lock before the grace period`() = runTest {
        var locks = 0
        val guard = guard { locks++ }

        guard.onBackgrounded()
        advanceTimeBy(29_999L)
        runCurrent()

        assertThat(locks).isEqualTo(0)
    }

    @Test
    fun `backgrounding locks when the grace period expires`() = runTest {
        var locks = 0
        val guard = guard { locks++ }

        guard.onBackgrounded()
        advanceTimeBy(30_000L)
        runCurrent()

        assertThat(locks).isEqualTo(1)
    }

    @Test
    fun `returning to the foreground cancels the pending lock`() = runTest {
        var locks = 0
        val guard = guard { locks++ }

        guard.onBackgrounded()
        advanceTimeBy(20_000L)
        guard.onForegrounded()
        advanceTimeBy(60_000L)
        runCurrent()

        assertThat(locks).isEqualTo(0)
    }

    @Test
    fun `a second background event restarts the grace window`() = runTest {
        var locks = 0
        val guard = guard { locks++ }

        guard.onBackgrounded()
        advanceTimeBy(20_000L)
        guard.onForegrounded()
        guard.onBackgrounded()
        advanceTimeBy(25_000L)
        runCurrent()

        assertThat(locks).isEqualTo(0)

        advanceTimeBy(5_000L)
        runCurrent()

        assertThat(locks).isEqualTo(1)
    }

    @Test
    fun `cancel prevents a lock after uninstall`() = runTest {
        var locks = 0
        val guard = guard { locks++ }

        guard.onBackgrounded()
        guard.cancel()
        advanceTimeBy(60_000L)
        runCurrent()

        assertThat(locks).isEqualTo(0)
    }

    @Test
    fun `grace must be positive`() = runTest {
        val error = runCatching {
            BackgroundLockGuard(
                scope = this,
                clock = Clock { 0L },
                graceMillis = 0L,
                onGraceExpired = {},
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }
}
