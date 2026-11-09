package com.keeper.vault.app

import android.app.Application
import android.util.Log

/**
 * Manual DI root. The container is created once and owns the auth/vault/isolation wiring.
 *
 * No Hilt, no reflection-based injection, no global mutable service locator beyond the explicit
 * feature bridges (`AuthFeature`, `VaultFeature`, `VaultSession`) required by frozen FQCN screens.
 *
 * Startup is deliberately fail-soft: a broken Keystore provider, an unreadable Room database, or
 * a missing native library must show an explanation instead of terminating the process. The shell
 * reads [startupError] and renders a recovery screen when [container] is null.
 */
class KeeperApplication : Application() {

    @Volatile
    var container: AppContainer? = null
        private set

    @Volatile
    var startupError: StartupError? = null
        private set

    override fun onCreate() {
        super.onCreate()
        startupError = try {
            container = AppContainer(this)
            null
        } catch (fatal: Throwable) {
            if (fatal is VirtualMachineError || fatal is ThreadDeath) throw fatal
            Log.e(TAG, "Keeper failed to initialize", fatal)
            StartupError.from(fatal)
        }
    }

    private companion object {
        const val TAG = "KeeperApplication"
    }
}

/**
 * A startup failure rendered by the shell. [component] is a short, non-technical label such as
 * "vault database"; [detail] is safe to display; [technical] is kept for the tamper log/support.
 */
data class StartupError(
    val component: String,
    val detail: String,
    val technical: String,
) {
    companion object {
        fun from(error: Throwable): StartupError {
            val staged = error as? StartupException
            val cause = staged?.cause ?: error
            return StartupError(
                component = staged?.component ?: "application",
                detail = cause.message?.takeIf { it.isNotBlank() }
                    ?: cause.javaClass.simpleName,
                technical = buildString {
                    append(cause.javaClass.name)
                    cause.message?.takeIf { it.isNotBlank() }?.let {
                        append(": ")
                        append(it)
                    }
                },
            )
        }
    }
}

/** Wraps a failure with the initialization stage that produced it. */
class StartupException(
    val component: String,
    cause: Throwable,
) : Exception("Keeper could not initialize $component", cause)
