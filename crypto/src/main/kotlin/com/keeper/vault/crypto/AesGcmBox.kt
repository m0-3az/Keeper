package com.keeper.vault.crypto

import com.keeper.vault.contracts.crypto.CryptoConstants
import com.keeper.vault.contracts.platform.KeeperException
import com.keeper.vault.contracts.platform.SecureRandomSource
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Small AES-256-GCM box used for Layer A (KEK encrypts VaultKey) inside the key envelope.
 *
 * Format: `nonce[12] | ciphertext | tag[16]`. The AAD binds the box to the Layer A context. This
 * is not the file cipher; file containers add magic/version and stream via [AesGcmVaultCipher].
 */
internal class AesGcmBox(
    private val random: SecureRandomSource,
) {
    fun seal(key: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == CryptoConstants.VAULT_KEY_BYTES) { "Key must be 32 bytes" }
        val nonce = random.nextBytes(CryptoConstants.GCM_NONCE_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(CryptoConstants.GCM_TAG_BITS, nonce),
        )
        cipher.updateAAD(AAD)
        val sealed = cipher.doFinal(plaintext)
        return nonce + sealed
    }

    fun open(key: ByteArray, sealed: ByteArray): ByteArray {
        require(key.size == CryptoConstants.VAULT_KEY_BYTES) { "Key must be 32 bytes" }
        val minimum = CryptoConstants.GCM_NONCE_BYTES + (CryptoConstants.GCM_TAG_BITS / 8)
        if (sealed.size < minimum) {
            throw KeeperException.Crypto("Invalid sealed payload")
        }
        val nonce = sealed.copyOfRange(0, CryptoConstants.GCM_NONCE_BYTES)
        val ciphertext = sealed.copyOfRange(CryptoConstants.GCM_NONCE_BYTES, sealed.size)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(CryptoConstants.GCM_TAG_BITS, nonce),
            )
            cipher.updateAAD(AAD)
            cipher.doFinal(ciphertext)
        } catch (e: GeneralSecurityException) {
            throw KeeperException.Crypto("Authentication failed")
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val AAD = "KEEPER-LAYER-A".toByteArray(Charsets.US_ASCII)
    }
}
