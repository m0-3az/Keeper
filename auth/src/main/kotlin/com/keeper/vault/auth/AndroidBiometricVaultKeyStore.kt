package com.keeper.vault.auth

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.RequiresApi
import androidx.biometric.BiometricManager
import com.keeper.vault.contracts.crypto.BiometricEnvelope
import com.keeper.vault.contracts.crypto.CryptoConstants
import com.keeper.vault.contracts.crypto.BiometricVaultKeyStore
import com.keeper.vault.contracts.platform.KeeperException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Auth-bound Android Keystore adapter for the biometric convenience path.
 *
 * The key requires Class 3 biometric authentication (`setUserAuthenticationRequired(true)`) and is
 * invalidated when biometrics are re-enrolled. It is separate from the non-auth Layer A key, so
 * removing biometrics never destroys the authoritative secret path.
 *
 * `wrap` is usable with fakes and with keys that do not require authentication per use. With a
 * real per-use key, the setup UI must create the encrypt cipher, pass it to `BiometricPrompt`, and
 * call [wrapWithCipher] after success. The unlock path follows the same pattern through
 * [createDecryptCipher] and [unwrap].
 */
class AndroidBiometricVaultKeyStore(
    private val context: Context,
) : BiometricVaultKeyStore, BiometricCipherWrapper {
    override fun isAvailable(): Boolean {
        if (!canUseBiometrics()) return false
        return try {
            existingKey() != null
        } catch (e: KeeperException.Crypto) {
            false
        }
    }

    fun canUseBiometrics(): Boolean =
        BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    override fun wrap(vaultKey: ByteArray): BiometricEnvelope =
        wrapWithCipher(createEncryptCipher(), vaultKey)

    override fun createEncryptCipher(): Cipher = try {
        Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
    } catch (e: GeneralSecurityException) {
        throw KeeperException.Crypto("Could not initialize biometric cipher", e)
    } catch (e: IllegalStateException) {
        throw KeeperException.Crypto("Biometric authentication is required", e)
    }

    override fun wrapWithCipher(cipher: Cipher, vaultKey: ByteArray): BiometricEnvelope {
        if (vaultKey.isEmpty()) throw KeeperException.Crypto("Vault key must not be empty")
        val iv = cipher.iv ?: throw KeeperException.Crypto("Biometric cipher has no IV")
        val ciphertext = try {
            cipher.doFinal(vaultKey)
        } catch (e: GeneralSecurityException) {
            throw KeeperException.Crypto("Could not wrap the vault key", e)
        } catch (e: IllegalStateException) {
            throw KeeperException.Crypto("Biometric authentication is required", e)
        }
        return BiometricEnvelope(iv = iv, ciphertext = ciphertext)
    }

    override fun createDecryptCipher(envelope: BiometricEnvelope): Cipher {
        val key = existingKey() ?: throw KeeperException.Crypto("Biometric key is missing")
        return try {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    key,
                    GCMParameterSpec(CryptoConstants.GCM_TAG_BITS, envelope.iv),
                )
            }
        } catch (e: GeneralSecurityException) {
            throw KeeperException.Crypto("Could not initialize biometric decryption", e)
        } catch (e: IllegalStateException) {
            throw KeeperException.Crypto("Biometric authentication is required", e)
        }
    }

    override fun unwrap(cipher: Cipher, envelope: BiometricEnvelope): ByteArray {
        val plaintext = try {
            cipher.doFinal(envelope.ciphertext)
        } catch (e: GeneralSecurityException) {
            throw KeeperException.Crypto("Biometric unwrap failed")
        } catch (e: IllegalStateException) {
            throw KeeperException.Crypto("Biometric authentication is required")
        }
        if (plaintext.size != CryptoConstants.VAULT_KEY_BYTES) {
            plaintext.fill(0)
            throw KeeperException.Crypto("Biometric unwrap produced an invalid key")
        }
        return plaintext
    }

    override fun deleteKey() {
        try {
            keyStore().deleteEntry(ALIAS)
        } catch (e: GeneralSecurityException) {
            throw KeeperException.Crypto("Could not delete biometric key", e)
        }
    }

    private fun getOrCreateKey(): SecretKey = existingKey() ?: createKey()

    private fun existingKey(): SecretKey? = try {
        val entry = keyStore().getEntry(ALIAS, null)
        (entry as? KeyStore.SecretKeyEntry)?.secretKey
    } catch (e: GeneralSecurityException) {
        throw KeeperException.Crypto("Could not read biometric key", e)
    }

    private fun createKey(): SecretKey {
        val generator = try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        } catch (e: GeneralSecurityException) {
            throw KeeperException.Crypto("Android Keystore unavailable", e)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                generator.init(baseSpec().setIsStrongBoxBacked(true).build())
                return generator.generateKey()
            } catch (e: ProviderException) {
                // StrongBox may not support auth-bound keys; the TEE fallback is next.
            } catch (e: GeneralSecurityException) {
                // Same fallback.
            }
        }

        return try {
            generator.init(baseSpec().build())
            generator.generateKey()
        } catch (e: GeneralSecurityException) {
            throw KeeperException.Crypto("Could not generate biometric key", e)
        } catch (e: ProviderException) {
            throw KeeperException.Crypto("Could not generate biometric key", e)
        }
    }

    private fun baseSpec(): KeyGenParameterSpec.Builder {
        val builder = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setBiometricPerUse(builder)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        return builder
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun setBiometricPerUse(builder: KeyGenParameterSpec.Builder) {
        builder.setUserAuthenticationParameters(
            0,
            KeyProperties.AUTH_BIOMETRIC_STRONG,
        )
    }

    private fun keyStore(): KeyStore = try {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    } catch (e: GeneralSecurityException) {
        throw KeeperException.Crypto("Android Keystore unavailable", e)
    } catch (e: java.io.IOException) {
        throw KeeperException.Crypto("Android Keystore unavailable", e)
    }

    private companion object {
        const val ALIAS = "keeper.biometric.vault.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
