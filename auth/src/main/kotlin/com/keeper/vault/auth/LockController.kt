package com.keeper.vault.auth

import com.keeper.vault.contracts.auth.LockController
import com.keeper.vault.contracts.auth.LockReason
import com.keeper.vault.contracts.auth.LockState
import com.keeper.vault.contracts.auth.VaultMode
import com.keeper.vault.contracts.platform.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide lock authority.
 *
 * Locking is idempotent. Every lock closes the live [AuthSession], which zeroes the VaultKey; the
 * vault layer observes [state] and must close its read sessions when the state becomes Locked.
 */
class DefaultLockController(
    private val sessions: AuthSessionStore,
    private val clock: Clock,
) : LockController {
    private val mutableState = MutableStateFlow<LockState>(LockState.Locked)
    override val state: StateFlow<LockState> = mutableState.asStateFlow()

    private val mutableLastReason = MutableStateFlow<LockReason?>(null)

    /** Last lock trigger, for audit UI and tests. Does not carry key material. */
    val lastLockReason: StateFlow<LockReason?> = mutableLastReason.asStateFlow()

    override fun unlock(mode: VaultMode) {
        mutableState.value = LockState.Unlocked(mode = mode, sinceMillis = clock.nowMillis())
    }

    override fun lock(reason: LockReason) {
        sessions.close()
        mutableLastReason.value = reason
        mutableState.value = LockState.Locked
    }

    override fun onAppBackgrounded() {
        if (mutableState.value is LockState.Unlocked) {
            lock(LockReason.APP_BACKGROUNDED)
        }
    }

    override fun onScreenOff() {
        if (mutableState.value is LockState.Unlocked) {
            lock(LockReason.SCREEN_OFF)
        }
    }
}
