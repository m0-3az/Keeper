package com.keeper.vault.vault

import android.content.Context
import com.keeper.vault.contracts.vault.VaultRepository
import com.keeper.vault.vault.platform.VaultDependencies

/**
 * Process-wide vault repository seam installed by the app's manual-DI container.
 *
 * `:app` constructs the repository with the auth-owned [com.keeper.vault.vault.session.VaultSessionProvider]
 * and installs it here. If the Activity is launched directly (for example by an instrumentation
 * test), it falls back to [VaultDependencies.repository].
 */
object VaultFeature {
    @Volatile
    private var installed: VaultRepository? = null

    fun install(repository: VaultRepository) {
        installed = repository
    }

    fun repository(context: Context): VaultRepository {
        installed?.let { return it }
        return synchronized(this) {
            installed ?: VaultDependencies.repository(context.applicationContext).also {
                installed = it
            }
        }
    }

    fun resetForTests() {
        installed = null
    }
}
