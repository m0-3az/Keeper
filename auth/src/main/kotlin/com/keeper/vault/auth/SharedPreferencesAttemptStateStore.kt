package com.keeper.vault.auth

import android.content.SharedPreferences
import androidx.core.content.edit

/** SharedPreferences attempt counters. Only integers and a timestamp are stored. */
class SharedPreferencesAttemptStateStore(
    private val preferences: SharedPreferences,
) : AttemptStateStore {
    override fun read(): PersistedAttempts = PersistedAttempts(
        failures = preferences.getInt(KEY_FAILURES, 0),
        cooldownUntilMillis = preferences.getLong(KEY_COOLDOWN_UNTIL, 0L)
            .takeIf { it > 0L },
    )

    override fun write(state: PersistedAttempts) {
        preferences.edit {
            putInt(KEY_FAILURES, state.failures)
            putLong(KEY_COOLDOWN_UNTIL, state.cooldownUntilMillis ?: 0L)
        }
    }

    companion object {
        const val PREFERENCES_NAME: String = "keeper_auth_attempts"
        const val KEY_FAILURES: String = "failures"
        const val KEY_COOLDOWN_UNTIL: String = "cooldown_until"
    }
}
