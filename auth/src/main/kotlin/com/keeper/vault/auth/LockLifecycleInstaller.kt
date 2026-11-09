package com.keeper.vault.auth

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.keeper.vault.contracts.auth.LockController
import com.keeper.vault.contracts.platform.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Installs process-lifecycle and screen-off hooks for a [LockController].
 *
 * `ProcessLifecycleOwner` delays `ON_STOP` briefly, which avoids locking on a configuration
 * change. A further grace period is required because opening the Storage Access Framework picker
 * or a permission dialog also stops the process lifecycle; locking there wiped the key before
 * the user could pick a file. [BackgroundLockGuard] locks only after the grace period expires and
 * cancels the pending lock on `ON_START`.
 *
 * `ACTION_SCREEN_OFF` is a protected system broadcast; a runtime receiver is required because
 * manifest receivers cannot subscribe to it. Screen-off still locks immediately, as does a manual
 * lock or a failed-attempt lockout.
 */
class LockLifecycleInstaller(
    private val application: Application,
    private val lockController: LockController,
    graceMillis: Long = BackgroundLockGuard.DEFAULT_GRACE_MILLIS,
) {
    private var installed: Boolean = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val backgroundGuard = BackgroundLockGuard(
        scope = scope,
        clock = Clock { System.currentTimeMillis() },
        graceMillis = graceMillis,
        onGraceExpired = { lockController.onAppBackgrounded() },
    )

    private val processObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            backgroundGuard.onForegrounded()
        }

        override fun onStop(owner: LifecycleOwner) {
            backgroundGuard.onBackgrounded()
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                lockController.onScreenOff()
            }
        }
    }

    @Synchronized
    fun install() {
        if (installed) return
        ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)
        ContextCompat.registerReceiver(
            application,
            screenReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_EXPORTED,
        )
        installed = true
    }

    @Synchronized
    fun uninstall() {
        if (!installed) return
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processObserver)
        backgroundGuard.cancel()
        scope.cancel()
        try {
            application.unregisterReceiver(screenReceiver)
        } catch (e: IllegalArgumentException) {
            // Already unregistered; the receiver is process-lifetime by design.
        }
        installed = false
    }
}
