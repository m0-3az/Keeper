package com.keeper.vault.vault.session

import com.keeper.vault.contracts.crypto.VaultKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Supplies the live [VaultKey] to the vault UI and repository.
 *
 * Phase 2 deliberately does not unlock anything. `:app`/`:auth` own the real key lifecycle and
 * call [VaultSession.setKey] after a successful unlock; [VaultSession.lock] on every lock event.
 */
interface VaultSessionProvider {
    /** The current key, or null when the vault is locked. The caller must not close it. */
    fun currentKey(): VaultKey?

    val isUnlocked: Boolean
        get() = currentKey() != null
}

/**
 * Thread-safe holder for one live [VaultKey].
 *
 * Replacing or clearing the key zeroes the previous key. The holder owns the key; callers must
 * not close it themselves.
 */
class VaultSessionHolder : VaultSessionProvider {
    private val state = MutableStateFlow<VaultKey?>(null)
    private val unlockedState = MutableStateFlow(false)

    /** Observable lock state for Compose; avoids polling from the UI. */
    val unlocked: StateFlow<Boolean> = unlockedState.asStateFlow()

    override fun currentKey(): VaultKey? = state.value

    /** Installs a new key and zeroes the previous one. Passing null locks the holder. */
    fun setKey(key: VaultKey?) {
        synchronized(this) {
            val previous = state.value
            state.value = key
            unlockedState.value = key != null
            if (previous != null && previous !== key) {
                previous.close()
            }
        }
    }

    /** Clears and zeroes the current key. */
    fun lock() {
        setKey(null)
    }
}

/**
 * Process-wide session used by [com.keeper.vault.vault.VaultActivity] in Phase 2.
 *
 * `:app` and `:auth` wire the same holder after merge. Instrumented tests set it directly.
 */
object VaultSession {
    private val holder = VaultSessionHolder()

    val provider: VaultSessionProvider
        get() = holder

    val unlocked: StateFlow<Boolean>
        get() = holder.unlocked

    fun setKey(key: VaultKey?) {
        holder.setKey(key)
    }

    fun lock() {
        holder.lock()
    }

    fun isUnlocked(): Boolean = holder.isUnlocked
}
