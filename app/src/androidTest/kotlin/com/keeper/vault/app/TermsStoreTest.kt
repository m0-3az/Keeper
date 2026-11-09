package com.keeper.vault.app

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
class TermsStoreTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun clear() {
        context.getSharedPreferences(TermsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun missingAcceptanceIsNotAccepted() {
        val store = TermsStore(context)
        assertEquals(0, store.acceptedVersion())
        assertFalse(store.isAccepted())
    }

    @Test
    fun acceptancePersists() {
        val store = TermsStore(context)
        store.accept()
        assertTrue(store.isAccepted())
        assertTrue(TermsStore(context).isAccepted())
    }

    @Test
    fun versionBumpReprompts() {
        TermsStore(context).accept(1)
        assertTrue(TermsStore(context).isAccepted(1))
        assertFalse(TermsStore(context).isAccepted(2))
    }
}
