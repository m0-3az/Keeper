package com.keeper.vault.presence.enrollment

import com.keeper.vault.contracts.crypto.VaultKey

/**
 * Supplies the current live [VaultKey] while the vault is unlocked.
 *
 * The app implements this over its auth session. The enrollment store never caches the key; it
 * asks for it at write/read time and returns immediately. A null result means the vault is locked.
 */
fun interface VaultKeyProvider {
    fun currentKey(): VaultKey?
}
