package com.keeper.vault.app

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoInternetPermissionTest {
    @Test
    fun mergedManifestDoesNotRequestInternet() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val requested = info.requestedPermissions.orEmpty().toSet()
        assertFalse(
            "INTERNET must never be requested",
            requested.contains("android.permission.INTERNET"),
        )
    }
}
