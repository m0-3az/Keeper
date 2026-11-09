package com.keeper.vault.duress.settings

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IntruderConsentStoreTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun clear() {
        context.getSharedPreferences(
            IntruderConsentStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        ).edit().clear().commit()
    }

    @Test
    fun missingConsentIsNotAccepted() {
        val store = IntruderConsentStore(context)
        assertEquals(0, store.acceptedVersion())
        assertFalse(store.isAccepted())
    }

    @Test
    fun acceptancePersists() {
        IntruderConsentStore(context).accept()
        assertTrue(IntruderConsentStore(context).isAccepted())
    }

    @Test
    fun versionBumpReprompts() {
        IntruderConsentStore(context).accept(1)
        assertTrue(IntruderConsentStore(context).isAccepted(1))
        assertFalse(IntruderConsentStore(context).isAccepted(2))
    }
}
