package com.keeper.vault.crypto

import com.keeper.vault.contracts.platform.SecureRandomSource
import java.security.SecureRandom

/** CSPRNG-backed random source. Keys, salts, nonces, and identifiers use this, never Random. */
class DefaultSecureRandomSource(
    private val random: SecureRandom = SecureRandom(),
) : SecureRandomSource {
    override fun nextBytes(size: Int): ByteArray {
        require(size >= 0) { "size must be non-negative" }
        return ByteArray(size).also { random.nextBytes(it) }
    }
}
