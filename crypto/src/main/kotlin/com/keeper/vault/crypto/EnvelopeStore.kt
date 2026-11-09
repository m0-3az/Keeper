package com.keeper.vault.crypto

import com.keeper.vault.contracts.crypto.KeyEnvelope

/**
 * Persistence boundary for the key envelope.
 *
 * The envelope contains the PBKDF2 salt, iteration count, and the Keystore-wrapped Layer A. It
 * contains no KEK and no VaultKey. Implementations must not store derived keys.
 */
interface EnvelopeStore {
    /** Returns the stored envelope, or null when none exists. Throws on corruption. */
    fun load(): KeyEnvelope?

    /** Atomically replaces the stored envelope. */
    fun save(envelope: KeyEnvelope)

    /** Removes the envelope. */
    fun clear()
}
