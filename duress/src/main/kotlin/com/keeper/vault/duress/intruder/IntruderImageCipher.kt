package com.keeper.vault.duress.intruder

import com.keeper.vault.contracts.platform.KeeperException
import com.keeper.vault.contracts.platform.SecureRandomSource
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM container for one intruder still.
 *
 * Format: `KPI1 | version[1] | nonce[12] | ciphertext | tag[16]`. A fresh nonce is generated for
 * every capture. This is deliberately **not** [com.keeper.vault.contracts.crypto.VaultCipher]:
 * failed-unlock captures happen before a vault key exists, and the intruder key must never be
 * confused with the vault key. The image is decrypted into RAM only.
 */
internal class IntruderImageCipher(
    private val random: SecureRandomSource,
) {
    fun seal(key: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "Intruder key must be $KEY_BYTES bytes" }
        val nonce = random.nextBytes(NONCE_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, nonce),
        )
        cipher.updateAAD(AAD)
        val ciphertext = cipher.doFinal(plaintext)
        return ByteArray(MAGIC.size + 1 + NONCE_BYTES + ciphertext.size).also { out ->
            MAGIC.copyInto(out)
            out[MAGIC.size] = VERSION.toByte()
            nonce.copyInto(out, MAGIC.size + 1)
            ciphertext.copyInto(out, MAGIC.size + 1 + NONCE_BYTES)
        }
    }

    fun open(key: ByteArray, container: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "Intruder key must be $KEY_BYTES bytes" }
        val header = MAGIC.size + 1
        val minimum = header + NONCE_BYTES + TAG_BITS / 8
        if (container.size < minimum) {
            throw KeeperException.Crypto("Invalid intruder image container")
        }
        if (!container.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw KeeperException.Crypto("Invalid intruder image magic")
        }
        if (container[MAGIC.size].toInt() != VERSION) {
            throw KeeperException.Crypto("Unsupported intruder image version")
        }
        val nonce = container.copyOfRange(header, header + NONCE_BYTES)
        val ciphertext = container.copyOfRange(header + NONCE_BYTES, container.size)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, nonce),
            )
            cipher.updateAAD(AAD)
            cipher.doFinal(ciphertext)
        } catch (e: GeneralSecurityException) {
            throw KeeperException.Crypto("Intruder image authentication failed", e)
        }
    }

    companion object {
        const val KEY_BYTES: Int = 32
        const val NONCE_BYTES: Int = 12
        const val TAG_BITS: Int = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val VERSION = 1
        val MAGIC = "KPI1".toByteArray(Charsets.US_ASCII)
        private val AAD = "KEEPER-INTRUDER-IMAGE".toByteArray(Charsets.US_ASCII)
    }
}
