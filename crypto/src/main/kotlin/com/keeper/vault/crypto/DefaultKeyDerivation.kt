package com.keeper.vault.crypto

import com.keeper.vault.contracts.crypto.CryptoConstants
import com.keeper.vault.contracts.crypto.KeyDerivation
import com.keeper.vault.contracts.platform.KeeperException
import java.security.GeneralSecurityException
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PBKDF2-HMAC-SHA256 with the OWASP floor of 600,000 iterations.
 *
 * The returned bytes are a raw KEK. The caller must use them immediately and zero them. The
 * `PBEKeySpec` password copy is cleared in a finally block; the derived `SecretKey` may hold a
 * JVM copy that cannot be zeroed (documented limitation).
 */
class DefaultKeyDerivation : KeyDerivation {
    override fun derive(secret: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        require(secret.isNotEmpty()) { "Secret must not be empty" }
        require(salt.size == CryptoConstants.SALT_BYTES) {
            "Salt must be ${CryptoConstants.SALT_BYTES} bytes"
        }
        require(iterations >= CryptoConstants.MIN_PBKDF2_ITERATIONS) {
            "PBKDF2 iterations must be at least ${CryptoConstants.MIN_PBKDF2_ITERATIONS}"
        }

        val spec = PBEKeySpec(secret, salt, iterations, 256)
        return try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            factory.generateSecret(spec).encoded
                ?: throw KeeperException.Crypto("PBKDF2 returned no key")
        } catch (e: GeneralSecurityException) {
            throw KeeperException.Crypto("PBKDF2 failed", e)
        } finally {
            spec.clearPassword()
        }
    }
}
