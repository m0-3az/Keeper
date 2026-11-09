package com.keeper.vault.duress.duress

import com.keeper.vault.contracts.crypto.VaultKey
import com.keeper.vault.contracts.crypto.VaultKeyManager
import com.keeper.vault.contracts.duress.DuressController
import com.keeper.vault.contracts.isolation.TamperKind
import com.keeper.vault.contracts.isolation.TamperLog
import com.keeper.vault.contracts.isolation.TamperSignal
import com.keeper.vault.contracts.platform.Clock
import com.keeper.vault.contracts.platform.KeeperException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Opens the decoy for the app after its duress detour. */
interface DecoyUnlocker {
    /**
     * Verifies [candidate] against the decoy envelope and, on success, installs the decoy key in
     * [DecoyVault.session]. Returns false without side effects for a wrong secret.
     *
     * The app is responsible for locking the real vault (`LockReason.DURESS`) first and routing
     * the UI to [DecoyVault.repository]. This seam exists because the frozen `AuthManager` has no
     * "unlock decoy" method and `:app` is outside this module's scope.
     */
    suspend fun openDecoy(candidate: CharArray): Boolean
}

/**
 * Default duress/decoy controller.
 *
 * Invariants from the frozen contract:
 * - the decoy has its own PBKDF2 salt, its own Keystore key (`keeper.layer_a.decoy.v1`) and its
 *   own envelope directory;
 * - configuring/clearing requires the real vault to be unlocked;
 * - a secret that opens the real envelope is rejected where practical;
 * - the real vault is never deleted or modified;
 * - `isDuressSecret` zeroes the key it recovers;
 * - v1 has no "delete the real vault" action.
 */
class DefaultDuressController(
    private val isRealVaultUnlocked: () -> Boolean,
    private val decoyKeyManager: VaultKeyManager,
    private val realSecretProbe: RealVaultSecretProbe,
    private val decoyVault: DecoyVault,
    private val tamperLog: TamperLog?,
    private val clock: Clock,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Called once a duress secret is verified, before the decoy key is installed.
     *
     * Production wiring locks the real vault (`LockReason.DURESS`) and zeroes the app's real
     * `VaultSession` copy here, so the decoy key replaces a zeroed real key instead of coexisting
     * with it. Kept as a callback because the frozen `DuressController` contract has no app
     * routing surface.
     */
    private val onDuressUnlock: () -> Unit = {},
) : DuressController, DecoyUnlocker {

    override suspend fun isConfigured(): Boolean = withContext(ioDispatcher) {
        decoyKeyManager.exists()
    }

    override suspend fun configure(duressSecret: CharArray) {
        requireRealVaultUnlocked()
        if (duressSecret.isEmpty()) {
            throw KeeperException.Duress("Duress secret must not be empty")
        }
        if (withContext(ioDispatcher) { decoyKeyManager.exists() }) {
            throw KeeperException.Duress(
                "A duress secret is already configured; clear it before choosing a new one",
            )
        }
        val opensRealVault = try {
            realSecretProbe.unlocksRealVault(duressSecret)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw KeeperException.Duress("Could not verify the duress secret", e)
        }
        if (opensRealVault) {
            throw KeeperException.Duress("The duress secret must differ from the real secret")
        }

        val key = try {
            withContext(ioDispatcher) { decoyKeyManager.create(duressSecret) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: KeeperException.Duress) {
            throw e
        } catch (e: Exception) {
            throw KeeperException.Duress("Could not create the decoy envelope", e)
        }
        key.close()
        appendTamper("duress configured")
    }

    override suspend fun isDuressSecret(candidate: CharArray): Boolean {
        if (candidate.isEmpty()) return false
        val key = unlockDecoy(candidate) ?: return false
        key.close()
        return true
    }

    override suspend fun clear() {
        requireRealVaultUnlocked()
        var failure: Throwable? = null
        try {
            withContext(ioDispatcher) { decoyKeyManager.destroy() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e
        }
        try {
            withContext(ioDispatcher) { decoyVault.destroy() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (failure == null) failure = e
        }
        if (failure != null) {
            throw KeeperException.Duress("Could not clear the decoy vault", failure)
        }
        appendTamper("duress cleared")
    }

    override suspend fun openDecoy(candidate: CharArray): Boolean {
        if (candidate.isEmpty()) return false
        val key = unlockDecoy(candidate) ?: return false
        try {
            onDuressUnlock()
        } catch (e: CancellationException) {
            key.close()
            throw e
        } catch (e: Exception) {
            key.close()
            throw KeeperException.Duress("Could not lock the real vault", e)
        }
        decoyVault.session.setKey(key)
        return true
    }

    /** Returns the decoy key for [candidate], or null. Caller owns and must close the key. */
    private suspend fun unlockDecoy(candidate: CharArray): VaultKey? {
        val configured = withContext(ioDispatcher) {
            runCatching { decoyKeyManager.exists() }.getOrDefault(false)
        }
        if (!configured) return null
        return try {
            withContext(ioDispatcher) { decoyKeyManager.unlock(candidate) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Uniform failure: no oracle for a wrong duress secret.
            null
        }
    }

    private fun requireRealVaultUnlocked() {
        val unlocked = try {
            isRealVaultUnlocked()
        } catch (_: Exception) {
            false
        }
        if (!unlocked) {
            throw KeeperException.Duress("Unlock the real vault before changing duress settings")
        }
    }

    private suspend fun appendTamper(detail: String) {
        val log = tamperLog ?: return
        try {
            log.append(
                TamperSignal(
                    kind = TamperKind.UNKNOWN,
                    detectedAtMillis = clock.nowMillis(),
                    detail = detail,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Configuration already succeeded; the tamper log is advisory metadata.
        }
    }
}
