package com.keeper.vault.auth

import com.keeper.vault.contracts.auth.AttemptLimiter
import com.keeper.vault.contracts.auth.AttemptState
import com.keeper.vault.contracts.platform.Clock

/**
 * Cooldown policy.
 *
 * The first four failures are free; the fifth starts a 30-second cooldown and each further
 * failure doubles (capped at 15 minutes). The failure count is never reset by a cooldown, only by
 * a successful authentication.
 */
data class AttemptPolicy(
    val maxFailures: Int = DEFAULT_MAX_FAILURES,
    val cooldownScheduleMillis: LongArray = longArrayOf(
        30_000L,
        60_000L,
        120_000L,
        300_000L,
        900_000L,
    ),
) {
    init {
        require(maxFailures >= 1) { "maxFailures must be positive" }
        require(cooldownScheduleMillis.isNotEmpty()) { "A cooldown schedule is required" }
        require(cooldownScheduleMillis.all { it > 0 }) { "Cooldowns must be positive" }
    }

    fun cooldownFor(failures: Int): Long {
        val index = (failures - maxFailures).coerceAtLeast(0)
            .coerceAtMost(cooldownScheduleMillis.lastIndex)
        return cooldownScheduleMillis[index]
    }

    fun maxCooldownMillis(): Long = cooldownScheduleMillis.max()

    companion object {
        const val DEFAULT_MAX_FAILURES: Int = 5
    }
}

/** Persisted attempt counters. Deliberately contains no secret material. */
data class PersistedAttempts(
    val failures: Int,
    val cooldownUntilMillis: Long? = null,
)

/** Storage seam for attempt counters. Implementations must not store secrets. */
interface AttemptStateStore {
    fun read(): PersistedAttempts

    fun write(state: PersistedAttempts)
}

/**
 * Durable attempt limiter.
 *
 * State is written on every failure and survives process restarts through [store]. A clock that
 * moves backwards cannot extend a cooldown past [AttemptPolicy.maxCooldownMillis].
 */
class PersistentAttemptLimiter(
    private val store: AttemptStateStore,
    private val clock: Clock,
    private val policy: AttemptPolicy = AttemptPolicy(),
) : AttemptLimiter {
    private val lock = Any()

    override suspend fun current(): AttemptState = synchronized(lock) {
        normalize(store.read())
    }

    override suspend fun recordFailure(): AttemptState = synchronized(lock) {
        val previous = store.read()
        val failures = previous.failures + 1
        val cooldownUntil = if (failures >= policy.maxFailures) {
            clock.nowMillis() + policy.cooldownFor(failures)
        } else {
            null
        }
        val next = PersistedAttempts(failures = failures, cooldownUntilMillis = cooldownUntil)
        store.write(next)
        normalize(next)
    }

    override suspend fun reset() {
        synchronized(lock) {
            store.write(PersistedAttempts(failures = 0, cooldownUntilMillis = null))
        }
    }

    private fun normalize(raw: PersistedAttempts): AttemptState {
        val now = clock.nowMillis()
        val farthest = now + policy.maxCooldownMillis()
        var cooldown = raw.cooldownUntilMillis
        if (cooldown != null) {
            cooldown = when {
                cooldown <= now -> null
                cooldown > farthest -> farthest
                else -> cooldown
            }
        }
        return AttemptState(
            failures = raw.failures,
            cooldownUntilMillis = cooldown,
            lockedOut = raw.failures >= policy.maxFailures && cooldown != null,
        )
    }
}
