package com.keeper.vault.duress.intruder

import com.keeper.vault.contracts.intruder.IntruderEvent
import com.keeper.vault.contracts.intruder.IntruderTrigger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One-shot, process-local confirmation tokens.
 *
 * A token is issued only after the unlock screen's warning is explicitly confirmed. The capture
 * service consumes it before touching the camera. Passing intent extras without a valid token is
 * not sufficient, and a token cannot be replayed.
 */
object CaptureConfirmationGate {
    /** Tokens are short-lived: a stale confirmation must not arm a later capture. */
    const val DEFAULT_TTL_MILLIS: Long = 30_000L

    private val pending = ConcurrentHashMap<String, Long>()

    fun issue(nowMillis: Long, ttlMillis: Long = DEFAULT_TTL_MILLIS): String {
        require(ttlMillis > 0L) { "ttlMillis must be positive" }
        val token = UUID.randomUUID().toString()
        pending[token] = nowMillis + ttlMillis
        return token
    }

    /** Checks without consuming; the service performs the one-shot consume before camera work. */
    fun isValid(token: String?, nowMillis: Long): Boolean {
        if (token.isNullOrBlank()) return false
        val expiresAt = pending[token] ?: return false
        return nowMillis <= expiresAt
    }

    /** Consumes a token exactly once. Returns false for missing, expired, or replayed tokens. */
    fun consume(token: String?, nowMillis: Long): Boolean {
        if (token.isNullOrBlank()) return false
        val expiresAt = pending.remove(token) ?: return false
        return nowMillis <= expiresAt
    }

    fun clearForTests() {
        pending.clear()
    }

    fun revoke(token: String?) {
        if (!token.isNullOrBlank()) pending.remove(token)
    }
}

/**
 * Capture boundary for the two-phase flow. Implementations must only start camera work after the
 * confirmation token has been created by an explicit user action.
 */
fun interface ConfirmedIntruderCapture {
    suspend fun captureConfirmed(trigger: IntruderTrigger, token: String): IntruderEvent?
}
