package com.keeper.vault.crypto

import android.annotation.TargetApi
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.keeper.vault.contracts.crypto.CryptoConstants
import com.keeper.vault.contracts.crypto.KeystoreEnvelope
import com.keeper.vault.contracts.platform.KeeperException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore adapter for wrapping Layer A.
 *
 * StrongBox is attempted first on API 28+; any StrongBox failure falls back to the TEE-backed
 * provider. The key is non-auth: the password/GCM layer is what makes the user's secret mandatory.
 * A separate auth-bound key is used only for the biometric convenience path (Phase 3).
 *
 * Wrapped format: `version[1] | ivLen[1] | iv | ciphertext+tag`.
 */
class AndroidKeystoreEnvelope(
    private val alias: String = DEFAULT_ALIAS,
) : KeystoreEnvelope {
    override fun wrap(layerA: ByteArray): ByteArray {
        if (layerA.isEmpty()) throw KeeperException.Crypto("Layer A must not be empty")
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            if (iv.size !in 12..16) {
                throw KeeperException.Crypto("Unexpected Keystore IV length")
            }
            val ciphertext = cipher.doFinal(layerA)
            ByteArray(2 + iv.size + ciphertext.size).also { out ->
                out[0] = VERSION.toByte()
                out[1] = iv.size.toByte()
                iv.copyInto(out, 2)
                ciphertext.copyInto(out, 2 + iv.size)
            }
        } catch (e: KeeperException.Crypto) {
            throw e
        } catch (e: GeneralSecurityException) {
            throw KeeperException.Crypto("Keystore wrap failed", e)
        } catch (e: ProviderException) {
            // Some vendor Keystore providers throw a RuntimeException from Cipher.init/generate.
            throw KeeperException.Crypto("Keystore wrap failed", e)
        } catch (e: SecurityException) {
            throw KeeperException.Crypto("Keystore access denied", e)
        }
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        if (wrapped.size < 2 + 12 + (CryptoConstants.GCM_TAG_BITS / 8)) {
            throw KeeperException.Crypto("Invalid wrapped layer")
        }
        if (wrapped[0].toInt() != VERSION) {
            throw KeeperException.Crypto("Unsupported wrapped layer version")
        }
        val ivLength = wrapped[1].toInt() and 0xFF
        if (ivLength !in 12..16 || wrapped.size < 2 + ivLength + (CryptoConstants.GCM_TAG_BITS / 8)) {
            throw KeeperException.Crypto("Invalid wrapped layer IV")
        }
        val iv = wrapped.copyOfRange(2, 2 + ivLength)
        val ciphertext = wrapped.copyOfRange(2 + ivLength, wrapped.size)
        val key = getExistingKey() ?: throw KeeperException.Crypto("Keystore key missing")
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(CryptoConstants.GCM_TAG_BITS, iv),
            )
            cipher.doFinal(ciphertext)
        } catch (e: GeneralSecurityException) {
            throw KeeperException.Crypto("Keystore unwrap failed", e)
        } catch (e: ProviderException) {
            throw KeeperException.Crypto("Keystore unwrap failed", e)
        } catch (e: SecurityException) {
            throw KeeperException.Crypto("Keystore access denied", e)
        }
    }

    override fun deleteKey() {
        try {
            keyStore().deleteEntry(alias)
        } catch (e: GeneralSecurityException) {
            throw KeeperException.Crypto("Could not delete Keystore key", e)
        } catch (e: ProviderException) {
            throw KeeperException.Crypto("Could not delete Keystore key", e)
        } catch (e: SecurityException) {
            throw KeeperException.Crypto("Keystore access denied", e)
        }
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        getExistingKey()?.let { return it }
        return createKey()
    }

    private fun getExistingKey(): SecretKey? {
        return try {
            val entry = keyStore().getEntry(alias, null)
            (entry as? KeyStore.SecretKeyEntry)?.secretKey
        } catch (e: GeneralSecurityException) {
            throw KeeperException.Crypto("Could not read Keystore key", e)
        } catch (e: ProviderException) {
            throw KeeperException.Crypto("Could not read Keystore key", e)
        } catch (e: SecurityException) {
            throw KeeperException.Crypto("Keystore access denied", e)
        }
    }

    private fun createKey(): SecretKey {
        val generator = try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        } catch (e: GeneralSecurityException) {
            throw KeeperException.Crypto("Android Keystore unavailable", e)
        } catch (e: ProviderException) {
            throw KeeperException.Crypto("Android Keystore unavailable", e)
        } catch (e: SecurityException) {
            throw KeeperException.Crypto("Keystore access denied", e)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                generator.init(strongBoxSpec())
                return generator.generateKey()
            } catch (e: ProviderException) {
                // StrongBox unavailable or the key could not be generated; fall back to TEE.
            } catch (e: GeneralSecurityException) {
                // Same fallback: the TEE path is the documented second choice.
            }
        }
        return try {
            generator.init(teeSpec())
            generator.generateKey()
        } catch (e: GeneralSecurityException) {
            throw KeeperException.Crypto("Could not generate Keystore key", e)
        } catch (e: ProviderException) {
            throw KeeperException.Crypto("Could not generate Keystore key", e)
        } catch (e: SecurityException) {
            throw KeeperException.Crypto("Keystore access denied", e)
        }
    }

    private fun baseSpec(): KeyGenParameterSpec.Builder =
        KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)

    @TargetApi(Build.VERSION_CODES.P)
    private fun strongBoxSpec(): KeyGenParameterSpec =
        baseSpec().setIsStrongBoxBacked(true).build()

    private fun teeSpec(): KeyGenParameterSpec = baseSpec().build()

    private fun keyStore(): KeyStore {
        return try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        } catch (e: GeneralSecurityException) {
            throw KeeperException.Crypto("Android Keystore unavailable", e)
        } catch (e: java.io.IOException) {
            throw KeeperException.Crypto("Android Keystore unavailable", e)
        } catch (e: ProviderException) {
            throw KeeperException.Crypto("Android Keystore unavailable", e)
        } catch (e: SecurityException) {
            throw KeeperException.Crypto("Keystore access denied", e)
        }
    }

    private companion object {
        const val DEFAULT_ALIAS = "keeper.layer_a.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION = 1
    }
}
