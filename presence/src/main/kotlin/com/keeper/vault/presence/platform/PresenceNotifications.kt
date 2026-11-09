package com.keeper.vault.presence.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat

/**
 * Foreground-service notification for the presence shield.
 *
 * The text is deliberately generic: it is visible on the lock screen and must not reveal vault
 * contents or an enrolled identity. The channel is low importance and silent.
 */
object PresenceNotifications {
    const val CHANNEL_ID: String = "keeper_presence_shield"
    const val NOTIFICATION_ID: Int = 4_104

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Presence shield",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the owner-presence check running while the vault is open"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context): Notification {
        ensureChannel(context)
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Keeper presence shield")
            .setContentText("Watching for the vault owner")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .apply { contentIntent?.let(::setContentIntent) }
            .build()
    }
}
