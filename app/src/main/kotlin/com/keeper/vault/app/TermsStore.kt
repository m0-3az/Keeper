package com.keeper.vault.app

import android.content.Context

/**
 * First-run terms acceptance.
 *
 * The version is stored as an integer so a future terms revision can re-prompt by increasing
 * [TERMS_VERSION]. A missing or older value is never accepted.
 */
class TermsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun acceptedVersion(): Int = preferences.getInt(KEY_ACCEPTED_VERSION, 0)

    fun isAccepted(currentVersion: Int = TERMS_VERSION): Boolean =
        acceptedVersion() >= currentVersion

    fun accept(version: Int = TERMS_VERSION) {
        preferences.edit().putInt(KEY_ACCEPTED_VERSION, version).apply()
    }

    companion object {
        const val PREFERENCES_NAME: String = "keeper_terms"
        const val KEY_ACCEPTED_VERSION: String = "accepted_version"
        const val TERMS_VERSION: Int = 1
    }
}
