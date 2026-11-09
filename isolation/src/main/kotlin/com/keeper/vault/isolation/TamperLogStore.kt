package com.keeper.vault.isolation

import android.content.SharedPreferences

/**
 * Durable string storage for the tamper log.
 *
 * The interface is deliberately tiny so the log logic can be unit-tested with an in-memory fake
 * and the Android adapter can use SharedPreferences without leaking a Context into the logic.
 */
interface TamperLogStore {
    suspend fun read(): String?

    suspend fun write(value: String)

    suspend fun clear()
}

/**
 * SharedPreferences-backed metadata store.
 *
 * The tamper log holds kind, timestamp, and a short non-secret detail. It never holds vault
 * content, credentials, or decrypted data. `commit()` is used instead of `apply()` because the
 * caller is already on an injected IO dispatcher and a durable append must be synchronous.
 */
class SharedPreferencesTamperLogStore(
    private val preferences: SharedPreferences,
) : TamperLogStore {

    override suspend fun read(): String? = preferences.getString(KEY_ENTRIES, null)

    override suspend fun write(value: String) {
        if (!preferences.edit().putString(KEY_ENTRIES, value).commit()) {
            error("SharedPreferences rejected the tamper log write")
        }
    }

    override suspend fun clear() {
        if (!preferences.edit().remove(KEY_ENTRIES).commit()) {
            error("SharedPreferences rejected the tamper log clear")
        }
    }

    companion object {
        const val KEY_ENTRIES: String = "entries_v1"
    }
}
