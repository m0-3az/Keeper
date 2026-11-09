package com.keeper.vault.auth

import com.keeper.vault.contracts.auth.VaultMode
import com.keeper.vault.contracts.crypto.VaultKey
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Live authentication result handed to the app after a successful unlock.
 *
 * The session owns the only live [VaultKey] reference. Closing it zeroes the key. The vault layer
 * must close its read sessions when the lock controller leaves [com.keeper.vault.contracts.auth.LockState.Unlocked].
 */
class AuthSession(
    val vaultKey: VaultKey,
    val mode: VaultMode,
    val openedAtMillis: Long,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val isClosed: Boolean
        get() = closed.get()

    /** Returns a defensive copy. The caller owns and must zero it. */
    fun copyKeyBytes(): ByteArray {
        check(!closed.get()) { "Auth session is closed" }
        return vaultKey.copyBytes()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            vaultKey.close()
        }
    }

    override fun toString(): String =
        "AuthSession(mode=$mode, openedAtMillis=$openedAtMillis, key=[redacted])"
}

/** Process-wide holder for the live auth session. */
class AuthSessionStore {
    private val current = AtomicReference<AuthSession?>(null)

    fun open(vaultKey: VaultKey, mode: VaultMode, openedAtMillis: Long): AuthSession {
        val session = AuthSession(vaultKey, mode, openedAtMillis)
        current.getAndSet(session)?.close()
        return session
    }

    fun current(): AuthSession? = current.get()

    /** Closes and zeroes the live session. Safe to call repeatedly. */
    fun close() {
        current.getAndSet(null)?.close()
    }
}
