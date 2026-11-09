package com.keeper.vault.auth

import com.keeper.vault.contracts.auth.SecretType
import com.keeper.vault.contracts.auth.UnlockResult

/**
 * Optional app-owned unlock interceptor.
 *
 * [UnlockActivity] asks this before the real auth manager. The app uses it to recognize a duress
 * secret and route to the decoy vault. Returning null falls through to the normal authoritative
 * unlock. This is a manual-DI seam; tests leave it null.
 */
object AuthUnlockRouter {
    @Volatile
    var interceptor: (suspend (SecretType, CharArray) -> UnlockResult?)? = null
}
