package com.keeper.vault.auth

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.auth.AttemptState
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PersistentAttemptLimiterTest {
    private val clock = FakeClock(now = 10_000L)
    private val store = InMemoryAttemptStateStore()
    private val limiter = PersistentAttemptLimiter(store, clock)

    @Test
    fun `starts clean`() = runTest {
        assertThat(limiter.current())
            .isEqualTo(AttemptState(failures = 0, cooldownUntilMillis = null, lockedOut = false))
    }

    @Test
    fun `first four failures do not impose a cooldown`() = runTest {
        repeat(4) { index ->
            val state = limiter.recordFailure()
            assertThat(state.failures).isEqualTo(index + 1)
            assertThat(state.cooldownUntilMillis).isNull()
            assertThat(state.lockedOut).isFalse()
        }
    }

    @Test
    fun `the fifth failure imposes a cooldown`() = runTest {
        repeat(4) { limiter.recordFailure() }

        val state = limiter.recordFailure()

        assertThat(state.failures).isEqualTo(5)
        assertThat(state.cooldownUntilMillis).isEqualTo(clock.now + 30_000L)
        assertThat(state.lockedOut).isTrue()
    }

    @Test
    fun `the cooldown expires but the failure count remains`() = runTest {
        repeat(5) { limiter.recordFailure() }
        clock.now += 30_000L

        val state = limiter.current()

        assertThat(state.failures).isEqualTo(5)
        assertThat(state.cooldownUntilMillis).isNull()
        assertThat(state.lockedOut).isFalse()
    }

    @Test
    fun `further failures escalate the cooldown`() = runTest {
        repeat(5) { limiter.recordFailure() }
        clock.now += 30_000L
        val sixth = limiter.recordFailure()
        assertThat(sixth.cooldownUntilMillis).isEqualTo(clock.now + 60_000L)

        clock.now += 60_000L
        val seventh = limiter.recordFailure()
        assertThat(seventh.cooldownUntilMillis).isEqualTo(clock.now + 120_000L)
    }

    @Test
    fun `escalation is capped at the longest cooldown`() = runTest {
        repeat(20) { limiter.recordFailure() }

        val state = limiter.current()

        assertThat(state.cooldownUntilMillis).isEqualTo(clock.now + 900_000L)
    }

    @Test
    fun `reset clears failures and cooldown`() = runTest {
        repeat(5) { limiter.recordFailure() }

        limiter.reset()

        assertThat(limiter.current())
            .isEqualTo(AttemptState(failures = 0, cooldownUntilMillis = null, lockedOut = false))
    }

    @Test
    fun `state survives a new limiter instance`() = runTest {
        repeat(5) { limiter.recordFailure() }

        val recreated = PersistentAttemptLimiter(store, clock)

        assertThat(recreated.current()).isEqualTo(limiter.current())
    }

    @Test
    fun `a clock moved backwards cannot extend the cooldown beyond the cap`() = runTest {
        store.state = PersistedAttempts(failures = 9, cooldownUntilMillis = 10_000_000L)
        clock.now = 0L

        val state = limiter.current()

        assertThat(state.cooldownUntilMillis).isEqualTo(900_000L)
    }

    @Test
    fun `only numeric attempt state is written to the store`() = runTest {
        repeat(5) { limiter.recordFailure() }

        assertThat(store.state.failures).isEqualTo(5)
        assertThat(store.state.cooldownUntilMillis).isNotNull()
        assertThat(store.writes).isAtLeast(5)
    }

    @Test
    fun `policy schedules and caps cooldowns`() {
        val policy = AttemptPolicy()

        assertThat(policy.cooldownFor(5)).isEqualTo(30_000L)
        assertThat(policy.cooldownFor(9)).isEqualTo(900_000L)
        assertThat(policy.maxCooldownMillis()).isEqualTo(900_000L)
    }

    @Test
    fun `policy rejects impossible configuration`() {
        assertThat(runCatching { AttemptPolicy(maxFailures = 0) }.isFailure).isTrue()
        assertThat(
            runCatching { AttemptPolicy(cooldownScheduleMillis = longArrayOf()) }.isFailure,
        ).isTrue()
        assertThat(
            runCatching { AttemptPolicy(cooldownScheduleMillis = longArrayOf(0L)) }.isFailure,
        ).isTrue()
    }
}
