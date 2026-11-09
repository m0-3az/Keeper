package com.keeper.vault.auth

import android.content.SharedPreferences
import androidx.core.content.edit
import com.keeper.vault.contracts.auth.SecretType

/**
 * Non-secret metadata: which secret type was chosen at setup.
 *
 * The type is not key material. Persisting it lets the unlock screen preselect the right entry
 * mode and lets biometric enable/disable verify the secret with one PBKDF2 derivation instead of
 * trying all four types. The secret itself is never persisted.
 */
interface SecretTypeStore {
    fun load(): SecretType?

    fun save(type: SecretType)

    fun clear()
}

class SharedPreferencesSecretTypeStore(
    private val preferences: SharedPreferences,
) : SecretTypeStore {
    override fun load(): SecretType? {
        val stored = preferences.getString(KEY_TYPE, null) ?: return null
        return SecretType.entries.firstOrNull { it.name == stored }
    }

    override fun save(type: SecretType) {
        preferences.edit { putString(KEY_TYPE, type.name) }
    }

    override fun clear() {
        preferences.edit { remove(KEY_TYPE) }
    }

    companion object {
        const val PREFERENCES_NAME: String = "keeper_auth_secret_type"
        const val KEY_TYPE: String = "secret_type"
    }
}
