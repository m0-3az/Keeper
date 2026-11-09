package com.keeper.vault.presence

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.keeper.vault.presence.platform.PresenceNotifications
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PresenceShieldServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun serviceIsDeclaredAsACameraForegroundService() {
        val component = ComponentName(context, PresenceShieldService::class.java)
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
        val notification = PresenceNotifications.build(context)

        assertThat(notification.channelId).isEqualTo(PresenceNotifications.CHANNEL_ID)
        assertThat(notification.flags and Notification.FLAG_ONGOING_EVENT)
            .isEqualTo(Notification.FLAG_ONGOING_EVENT)
        assertThat(notification.flags and Notification.FLAG_AUTO_CANCEL).isEqualTo(0)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertThat(manager.getNotificationChannel(PresenceNotifications.CHANNEL_ID)).isNotNull()
    }
}
