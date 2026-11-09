package com.keeper.vault.isolation

import android.content.pm.ApplicationInfo

/**
 * Interprets the effective `ApplicationInfo` flags.
 *
 * `FLAG_ALLOW_BACKUP` is set by the manifest merger when `android:allowBackup` is true. Keeper's
 * manifest sets it to false; this check exists so a future merge regression becomes a visible
 * warning rather than silent misconfiguration.
 */
object BackupPolicy {
    fun isDisabled(applicationFlags: Int): Boolean =
        (applicationFlags and ApplicationInfo.FLAG_ALLOW_BACKUP) == 0
}
