package com.keeper.vault.auth

import androidx.core.content.edit
import com.keeper.vault.contracts.crypto.BiometricEnvelope
import java.util.Base64

/**
 * Persisted biometric convenience envelope.
 *
 * Neither field is secret without the auth-bound Keystore key, so preference storage is
 * acceptable. This store never contains the user's secret or an unwrapped VaultKey.
 */
interface BiometricEnvelopeStore {
    fun load(): BiometricEnvelope?

    fun save(envelope: BiometricEnvelope)

    fun clear()
}

/** Encodes/decodes the envelope as `base64(iv):base64(ciphertext)`. */
object BiometricEnvelopeCodec {
    fun encode(envelope: BiometricEnvelope): String =
        "${Base64.getEncoder().encodeToString(envelope.iv)}:" +
            Base64.getEncoder().encodeToString(envelope.ciphertext)

    fun decode(value: String?): BiometricEnvelope? {
        if (value.isNullOrBlank()) return null
        val parts = value.split(':', limit = 2)
        if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty()) return null
        return try {
            val iv = Base64.getDecoder().decode(parts[0])
            val ciphertext = Base64.getDecoder().decode(parts[1])
            if (iv.isEmpty() || ciphertext.isEmpty()) {
                null
            } else {
                BiometricEnvelope(iv = iv, ciphertext = ciphertext)
            }
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

/** SharedPreferences-backed biometric envelope storage. */
class SharedPreferencesBiometricEnvelopeStore(
    private val preferences: android.content.SharedPreferences,
) : BiometricEnvelopeStore {
    override fun load(): BiometricEnvelope? =
        BiometricEnvelopeCodec.decode(preferences.getString(KEY_ENVELOPE, null))

    override fun save(envelope: BiometricEnvelope) {
        preferences.edit {
            putString(KEY_ENVELOPE, BiometricEnvelopeCodec.encode(envelope))
        }
    }

    override fun clear() {
        preferences.edit { remove(KEY_ENVELOPE) }
    }

    companion object {
        const val PREFERENCES_NAME: String = "keeper_auth_biometric_envelope"
        const val KEY_ENVELOPE: String = "envelope"
    }
}
