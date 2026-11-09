package com.keeper.vault.duress.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import com.keeper.vault.contracts.intruder.IntruderSettings
import com.keeper.vault.contracts.intruder.IntruderSettingsStore
import com.keeper.vault.contracts.platform.KeeperException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Persistence boundary for the intruder opt-ins.
 *
 * The settings contain no secrets: a boolean, a threshold, a retention window, and one opt-in.
 * Plain preferences are therefore acceptable, matching the theme and secret-type stores.
 */
interface IntruderSettingsPersistence {
    fun loadRaw(): String?

    fun saveRaw(value: String)
}

/** SharedPreferences adapter. Deliberately thin; the format lives in [IntruderSettingsCodec]. */
class SharedPreferencesIntruderSettingsPersistence(
    private val preferences: SharedPreferences,
) : IntruderSettingsPersistence {
    override fun loadRaw(): String? = preferences.getString(KEY, null)

    override fun saveRaw(value: String) {
        preferences.edit { putString(KEY, value) }
    }

    companion object {
        const val PREFERENCES_NAME: String = "keeper_intruder_settings"
        const val KEY: String = "intruder_settings_v1"
    }
}

/**
 * Versioned text codec for [IntruderSettings].
 *
 * Any malformed, unknown-version, or out-of-policy payload decodes to the safe default (OFF), so
 * a corrupt preference file can never turn capture on. Unknown fields are ignored to leave room
 * for a future version.
 */
object IntruderSettingsCodec {
    private const val HEADER = "KIS1"
    private const val SEPARATOR = '|'
    private const val FIELD_SEPARATOR = '='
    private const val FOREVER = "forever"

    fun encode(settings: IntruderSettings): String = buildString {
        append(HEADER)
        append(SEPARATOR).append("enabled").append(FIELD_SEPARATOR).append(if (settings.enabled) 1 else 0)
        append(SEPARATOR).append("mismatch").append(FIELD_SEPARATOR)
            .append(if (settings.captureOnFaceMismatch) 1 else 0)
        append(SEPARATOR).append("threshold").append(FIELD_SEPARATOR)
            .append(settings.failureThreshold)
        append(SEPARATOR).append("retention").append(FIELD_SEPARATOR)
            .append(settings.retentionDays?.toString() ?: FOREVER)
    }

    fun decode(raw: String?): IntruderSettings {
        if (raw.isNullOrBlank()) return IntruderSettings()
        val fields = raw.split(SEPARATOR)
        if (fields.firstOrNull() != HEADER) return IntruderSettings()

        val values = mutableMapOf<String, String>()
        for (field in fields.drop(1)) {
            val split = field.split(FIELD_SEPARATOR, limit = 2)
            if (split.size == 2) values[split[0]] = split[1]
        }

        val enabled = values.boolean("enabled") ?: return IntruderSettings()
        val mismatch = values.boolean("mismatch") ?: return IntruderSettings()
        val threshold = values["threshold"]?.toIntOrNull() ?: return IntruderSettings()
        val retention = when (val rawRetention = values["retention"]) {
            null -> return IntruderSettings()
            FOREVER -> null
            else -> rawRetention.toIntOrNull() ?: return IntruderSettings()
        }

        return try {
            IntruderSettings(
                enabled = enabled,
                captureOnFaceMismatch = mismatch,
                failureThreshold = threshold,
                retentionDays = retention,
            )
        } catch (_: IllegalArgumentException) {
            IntruderSettings()
        }
    }

    private fun Map<String, String>.boolean(key: String): Boolean? = when (get(key)) {
        "1", "true" -> true
        "0", "false" -> false
        else -> null
    }
}

/**
 * Validates, persists, and publishes the intruder opt-ins.
 *
 * A rejected transform (for example threshold 7 or mismatch capture while the feature is OFF)
 * throws [KeeperException.Duress] and leaves the previous state intact. A storage failure does the
 * same, so the process never believes a setting is on when it was not written.
 */
class PersistentIntruderSettingsStore(
    private val persistence: IntruderSettingsPersistence,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : IntruderSettingsStore {
    private val mutex = Mutex()
    private val loaded = readSafely()
    private val initialState = loaded.copy(captureOnFaceMismatch = false)
    private val state = MutableStateFlow(initialState)

    init {
        // Face-mismatch capture has no visible confirmation flow in this release. Migrate any
        // restored true value to false and persist the safe state.
        if (loaded.captureOnFaceMismatch) {
            try {
                persistence.saveRaw(IntruderSettingsCodec.encode(initialState))
            } catch (_: Exception) {
                // The in-memory state is already safe; the next successful update rewrites it.
            }
        }
    }

    override val settings: StateFlow<IntruderSettings> = state.asStateFlow()

    override suspend fun update(transform: (IntruderSettings) -> IntruderSettings) {
        withContext(dispatcher) {
            mutex.withLock {
                val transformed = try {
                    transform(state.value)
                } catch (e: IllegalArgumentException) {
                    throw KeeperException.Duress(
                        e.message ?: "Invalid intruder settings",
                        e,
                    )
                }
                // Face-mismatch capture has no visible confirmation flow in this release.
                val next = transformed.copy(captureOnFaceMismatch = false)
                if (next == state.value) return@withLock
                try {
                    persistence.saveRaw(IntruderSettingsCodec.encode(next))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw KeeperException.Duress("Could not persist intruder settings", e)
                }
                state.value = next
            }
        }
    }

    private fun readSafely(): IntruderSettings = try {
        IntruderSettingsCodec.decode(persistence.loadRaw())
    } catch (_: Exception) {
        IntruderSettings()
    }
}
