package com.keeper.vault.contracts.settings

import kotlinx.coroutines.flow.StateFlow

/**
 * Five themes: dark, white (light), AMOLED (pure black), midnight blue, and forest green.
 *
 * The theme is not a secret. It is stored in plain preferences; using an encrypted store for it
 * would add cost and failure modes without a benefit.
 */
enum class ThemeMode {
    DARK,
    LIGHT,
    AMOLED,
    MIDNIGHT,
    FOREST,
}

/** Non-secret app settings. */
data class AppSettings(
    val theme: ThemeMode = ThemeMode.DARK,
    val onboardingComplete: Boolean = false,
)

/** Plain preferences for theme/onboarding. Never store secrets here. */
interface ThemeStore {
    val theme: StateFlow<ThemeMode>

    suspend fun setTheme(theme: ThemeMode)
}

/**
 * Encrypted store for secrets only.
 *
 * The implementation must use a Keystore-backed AES-GCM key and must not use the deprecated
 * `EncryptedSharedPreferences` API. It must never store the derived KEK or the VaultKey: those
 * live only in the key envelope and in RAM.
 *
 * Keys are logical names (`"duress_enabled"`, `"biometric_envelope"`), not user data.
 */
interface SecureSettingsStore {
    suspend fun put(key: String, value: CharArray)

    /** Returns a copy the caller must zero, or null when absent/corrupt. */
    suspend fun get(key: String): CharArray?

    suspend fun remove(key: String)

    suspend fun clear()
}
