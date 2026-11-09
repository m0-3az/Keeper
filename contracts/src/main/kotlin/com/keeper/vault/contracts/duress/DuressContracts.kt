package com.keeper.vault.contracts.duress

/**
 * Configures the duress secret and the decoy vault.
 *
 * Invariants:
 * - the duress secret must differ from the real secret;
 * - the decoy has its own PBKDF2 salt and its own key envelope;
 * - the real vault is never deleted or modified by a duress unlock;
 * - duress events are appended to the tamper log;
 * - after a duress unlock, the live real key is zeroed and the decoy key is used.
 *
 * v1 intentionally has **no** "delete real vault on next unlock" action. Adding one requires a
 * typed confirmation, a hold gesture, a loud warning, and a separate security review.
 */
interface DuressController {
    suspend fun isConfigured(): Boolean

    /** Stores a duress secret. Requires the real vault to be unlocked. */
    suspend fun configure(duressSecret: CharArray)

    /** Removes the duress secret and the decoy envelope. Requires the real vault to be unlocked. */
    suspend fun clear()

    /**
     * Returns true when [candidate] opens the decoy. This check is only meaningful while the
     * real vault is unlocked or during an unlock attempt; it never returns the secret.
     */
    suspend fun isDuressSecret(candidate: CharArray): Boolean
}
