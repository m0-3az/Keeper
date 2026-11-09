package com.keeper.vault.vault.platform

import com.keeper.vault.contracts.platform.IdGenerator
import java.util.UUID

/**
 * Opaque identifier source.
 *
 * [UUID.randomUUID] is backed by `SecureRandom`; identifiers are filesystem-safe and encode no
 * user data.
 */
class UuidIdGenerator : IdGenerator {
    override fun newId(): String = UUID.randomUUID().toString()
}
