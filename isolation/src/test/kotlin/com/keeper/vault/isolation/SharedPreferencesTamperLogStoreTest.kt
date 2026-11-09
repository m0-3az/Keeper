package com.keeper.vault.isolation

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SharedPreferencesTamperLogStoreTest {

    @Test
    fun `read returns null when nothing is stored`() = runTest {
        val store = SharedPreferencesTamperLogStore(FakePreferences())

        assertThat(store.read()).isNull()
    }

    @Test
    fun `write persists a value that read returns`() = runTest {
        val preferences = FakePreferences()
        val store = SharedPreferencesTamperLogStore(preferences)

        store.write("payload")

        assertThat(preferences.values[SharedPreferencesTamperLogStore.KEY_ENTRIES]).isEqualTo("payload")
        assertThat(store.read()).isEqualTo("payload")
    }

    @Test
    fun `write overwrites the previous value`() = runTest {
        val store = SharedPreferencesTamperLogStore(FakePreferences())

        store.write("first")
        store.write("second")

        assertThat(store.read()).isEqualTo("second")
    }

    @Test
    fun `write failure is reported instead of being swallowed`() = runTest {
        val preferences = FakePreferences().apply { commitResult = false }
        val store = SharedPreferencesTamperLogStore(preferences)

        val error = runCatching { store.write("payload") }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `clear removes the stored value`() = runTest {
        val store = SharedPreferencesTamperLogStore(FakePreferences())
        store.write("payload")

        store.clear()

        assertThat(store.read()).isNull()
    }

    @Test
    fun `clear failure is reported instead of being swallowed`() = runTest {
        val preferences = FakePreferences().apply { commitResult = false }
        val store = SharedPreferencesTamperLogStore(preferences)

        val error = runCatching { store.clear() }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalStateException::class.java)
    }

    private class FakePreferences : SharedPreferences {
        val values: MutableMap<String, Any?> = mutableMapOf()
        var commitResult: Boolean = true

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()

        override fun getString(key: String?, defValue: String?): String? =
            values[key] as? String ?: defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST")
            (values[key] as? MutableSet<String>) ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class FakeEditor : SharedPreferences.Editor {
            private val updates: MutableMap<String, Any?> = mutableMapOf()
            private val removals: MutableSet<String> = mutableSetOf()
            private var clearAll: Boolean = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                updates[key ?: ""] = value
                return this
            }

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?,
            ): SharedPreferences.Editor {
                updates[key ?: ""] = values
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                updates[key ?: ""] = value
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                updates[key ?: ""] = value
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                updates[key ?: ""] = value
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                updates[key ?: ""] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                removals += key ?: ""
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearAll = true
                return this
            }

            override fun commit(): Boolean {
                if (!commitResult) return false
                if (clearAll) values.clear()
                removals.forEach(values::remove)
                values.putAll(updates)
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}
