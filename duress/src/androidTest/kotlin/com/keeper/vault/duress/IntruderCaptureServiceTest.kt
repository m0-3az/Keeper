package com.keeper.vault.duress

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.intruder.IntruderTrigger
import com.keeper.vault.contracts.platform.Clock
import com.keeper.vault.duress.intruder.ServiceIntruderCapture
import com.keeper.vault.duress.settings.IntruderConsentStore
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IntruderCaptureServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun serviceIsDeclaredAsACameraForegroundService() {
        val component = ComponentName(context, IntruderCaptureService::class.java)
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getServiceInfo(
                component,
                PackageManager.ComponentInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getServiceInfo(component, 0)
        }

        assertThat(info.exported).isFalse()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertThat(info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
                .isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        }
    }

    @Test
    fun notificationIsOngoingAndUsesTheDedicatedChannel() {
        val notification = IntruderNotifications.build(context)

        assertThat(notification.channelId).isEqualTo(IntruderNotifications.CHANNEL_ID)
        assertThat(notification.flags and Notification.FLAG_ONGOING_EVENT)
            .isEqualTo(Notification.FLAG_ONGOING_EVENT)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertThat(manager.getNotificationChannel(IntruderNotifications.CHANNEL_ID)).isNotNull()
    }

    @Test
    fun manifestRequestsCameraForegroundPermissions() {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val requested = info.requestedPermissions.orEmpty().toSet()

        assertThat(requested).contains("android.permission.CAMERA")
        assertThat(requested).contains("android.permission.FOREGROUND_SERVICE_CAMERA")
    }

    @Test
    fun unconfirmedCaptureIsRejectedWithoutStartingTheCamera() = runBlocking {
        val capture = ServiceIntruderCapture(
            context = context,
            tamperLog = null,
            clock = Clock { 1_000L },
        )

        assertThat(capture.capture(IntruderTrigger.FAILED_UNLOCK)).isNull()
    }

    @Test
    fun directConfirmedCaptureWithoutAValidTokenIsRejected() = runBlocking {
        IntruderConsentStore(context).accept()
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val capture = ServiceIntruderCapture(
            context = context,
            tamperLog = null,
            clock = Clock { 1_000L },
        )

        assertThat(
            capture.captureConfirmed(IntruderTrigger.FAILED_UNLOCK, "forged-token"),
        ).isNull()
        assertThat(
            capture.captureConfirmed(IntruderTrigger.FAILED_UNLOCK, ""),
        ).isNull()
    }

    @Test
    fun intruderScreensAreNotExported() {
        val settings = context.packageManager.getActivityInfo(
            ComponentName(context, IntruderSettingsActivity::class.java),
            0,
        )
        val log = context.packageManager.getActivityInfo(
            ComponentName(context, IntruderLogActivity::class.java),
            0,
        )

        assertThat(settings.exported).isFalse()
        assertThat(log.exported).isFalse()
    }
}
