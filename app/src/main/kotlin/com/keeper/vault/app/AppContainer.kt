package com.keeper.vault.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.keeper.vault.auth.AuthComponent
import com.keeper.vault.auth.AuthFeature
import com.keeper.vault.auth.AuthUnlockRouter
import com.keeper.vault.auth.DefaultAuthComponent
import com.keeper.vault.auth.IntruderWarningBridge
import com.keeper.vault.auth.ui.AuthThemeState
import com.keeper.vault.contracts.auth.LockReason
import com.keeper.vault.contracts.auth.LockState
import com.keeper.vault.contracts.auth.UnlockResult
import com.keeper.vault.contracts.auth.VaultMode
import com.keeper.vault.contracts.crypto.VaultKey
import com.keeper.vault.contracts.isolation.TamperDetector
import com.keeper.vault.contracts.isolation.TamperLog
import com.keeper.vault.contracts.platform.BuildInfo
import com.keeper.vault.contracts.platform.Clock
import com.keeper.vault.contracts.platform.KeeperException
import com.keeper.vault.contracts.platform.SecretZeroizer
import com.keeper.vault.contracts.presence.DetectedFace
import com.keeper.vault.contracts.presence.FaceDetector
import com.keeper.vault.contracts.presence.FaceEmbedder
import com.keeper.vault.contracts.presence.FaceEmbedding
import com.keeper.vault.contracts.presence.FaceImage
import com.keeper.vault.contracts.presence.PresenceEvent
import com.keeper.vault.contracts.settings.ThemeMode
import com.keeper.vault.contracts.settings.ThemeStore
import com.keeper.vault.contracts.vault.VaultRepository
import com.keeper.vault.crypto.AesGcmVaultCipher
import com.keeper.vault.duress.AndroidDuressWiring
import com.keeper.vault.duress.DuressComponent
import com.keeper.vault.duress.DuressFeature
import com.keeper.vault.duress.settings.IntruderConsentStore
import com.keeper.vault.isolation.AndroidIsolation
import com.keeper.vault.presence.PresenceFeature
import com.keeper.vault.presence.PresenceShieldService
import com.keeper.vault.presence.enrollment.EncryptedFaceEnrollmentStore
import com.keeper.vault.presence.enrollment.VaultKeyProvider
import com.keeper.vault.presence.platform.MLKitFaceDetector
import com.keeper.vault.presence.platform.MobileFaceNetEmbedder
import com.keeper.vault.presence.shield.DefaultPresenceShield
import com.keeper.vault.presence.shield.MutablePresenceGate
import com.keeper.vault.vault.VaultFeature
import com.keeper.vault.vault.platform.VaultDependencies
import com.keeper.vault.vault.session.VaultSession
import com.keeper.vault.vault.ui.VaultThemeState
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manual DI root. No Hilt, no service locator with mutable construction state.
 *
 * The container owns the auth component, the vault repository, the isolation layer, and the
 * plain-preferences theme store. The auth session is copied into [VaultSession] after unlock and
 * both copies are zeroed on lock.
 */
class AppContainer(
    private val application: Application,
) {
    private val warnings = mutableListOf<String>()

    /** Non-fatal initialization problems, surfaced in diagnostics but never blocking the UI. */
    val startupWarnings: List<String> get() = warnings.toList()

    val auth: AuthComponent = critical("authentication") {
        DefaultAuthComponent.create(application)
    }
    val themeStore: ThemeStore = critical("settings") {
        SharedPreferencesThemeStore(application)
    }
    val tamperLog: TamperLog? = optional("tamper log") {
        AndroidIsolation.createTamperLog(
            context = application,
            dispatcher = Dispatchers.IO,
        )
    }
    val tamperDetector: TamperDetector? = optional("tamper detector") {
        AndroidIsolation.createDetector(
            context = application,
            buildInfo = AppBuildInfo(application),
        )
    }
    val vaultRepository: VaultRepository = critical("vault database") {
        VaultDependencies.repository(
            context = application,
            sessionProvider = VaultSession.provider,
        )
    }

    private val presenceGate = MutablePresenceGate(false)
    private val presenceStore = EncryptedFaceEnrollmentStore(
        directory = File(application.filesDir, "presence"),
        cipher = AesGcmVaultCipher(),
        keyProvider = VaultKeyProvider { VaultSession.provider.currentKey() },
        dispatcher = Dispatchers.IO,
    )

    // ML Kit and LiteRT load native libraries and, depending on the resolved artifact, may query
    // Google Play Services. Construct them on first use only: the old eager
    // FaceDetection.getClient() call ran on every launch and crashed in Application.onCreate on
    // devices where the detector could not initialize.
    private val faceDetector: FaceDetector = LazyFaceDetector { MLKitFaceDetector() }

    private val faceEmbedder: FaceEmbedder = LazyFaceEmbedder { MobileFaceNetEmbedder(application) }

    private val presenceShield = DefaultPresenceShield(
        gate = presenceGate,
        detector = faceDetector,
        embedder = faceEmbedder,
        clock = Clock { System.currentTimeMillis() },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    val duress: DuressComponent?
    val unlockWarningHost: UnlockIntruderHost?

    @Volatile
    private var decoyMode: Boolean = false

    @Volatile
    private var decoyTransition: Boolean = false

    private var lastFailureCount: Int = 0

    /**
     * Presence events are only lock signals while the app is actually visible.
     *
     * The shield keeps the camera alive in a foreground service. When the user opens the SAF
     * picker the owner's face naturally leaves the frame, and a two-second "no face" timeout used
     * to wipe the key before the file could be chosen. The lifecycle guard owns the background
     * lock; presence locks are suppressed while backgrounded.
     */
    @Volatile
    private var appForeground: Boolean = true

    private val processObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            appForeground = true
        }

        override fun onStop(owner: LifecycleOwner) {
            appForeground = false
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)
        critical("authentication hooks") { AuthFeature.install(auth) }

        // Duress depends on a second Keystore alias, its own Room database, and WorkManager. If
        // any of those is unavailable the shell stays usable and the duress controls are hidden.
        duress = optional("duress") { AndroidDuressWiring.create(application) }
        duress?.let { component ->
            optional("duress hooks") { DuressFeature.install(component) }
        }
        unlockWarningHost = duress?.let { component ->
            UnlockIntruderHost(
                coordinator = component.intruderCoordinator,
                settings = component.intruderSettings,
                consentAccepted = {
                    IntruderConsentStore(application).isAccepted()
                },
                scope = scope,
            )
        }
        IntruderWarningBridge.host = unlockWarningHost
        AuthUnlockRouter.interceptor = { _, secret ->
            if (duress == null) null else tryDuressUnlock(secret)
        }
        VaultFeature.install(vaultRepository)
        PresenceFeature.install(presenceStore, presenceGate, presenceShield)
        applyTheme(themeStore.theme.value)
        scope.launch {
            themeStore.theme.collect { applyTheme(it) }
        }
        scope.launch {
            presenceShield.events.collect { event ->
                when (event) {
                    PresenceEvent.NoFace -> lockFromPresence(LockReason.FACE_ABSENT)
                    PresenceEvent.MultipleFaces -> lockFromPresence(LockReason.MULTIPLE_FACES)
                    PresenceEvent.OwnerMismatch -> {
                        if (!appForeground) return@collect
                        // Face-mismatch capture is intentionally disabled in this release:
                        // there is no visible confirmation flow for a background mismatch.
                        auth.lockController.lock(LockReason.FACE_MISMATCH)
                    }

                    PresenceEvent.BlinkTimeout -> lockFromPresence(LockReason.BLINK_TIMEOUT)
                    else -> Unit
                }
            }
        }
        scope.launch {
            auth.lockController.state.collect { state ->
                when (state) {
                    is LockState.Unlocked -> {
                        unlockWarningHost?.clear()
                        if (decoyMode) {
                            enterDecoyMode()
                        } else {
                            syncVaultSession()
                        }
                        presenceGate.set(true)
                        reloadPresence()
                    }

                    LockState.Locked -> {
                        presenceGate.set(false)
                        presenceShield.updateEnrolled(null)
                        stopPresenceService()
                        VaultSession.lock()
                        if (decoyMode && !decoyTransition) {
                            exitDecoyMode()
                        }
                    }
                }
            }
        }
        scope.launch { pollUnlockFailures() }
    }

    /**
     * Loads the encrypted owner template after unlock and starts the camera service only when a
     * template exists. Enrollment invokes this again when it returns.
     */
    fun reloadPresence() {
        if (!presenceGate.unlocked.value) return
        scope.launch {
            val embedding = runCatching { presenceStore.load() }.getOrNull()
            presenceShield.updateEnrolled(embedding)
            if (embedding == null) {
                stopPresenceService()
            } else {
                startPresenceService()
            }
        }
    }

    fun isDecoyMode(): Boolean = decoyMode

    /**
     * Releases the front camera before owner enrollment. The shield service and the enrollment
     * activity share one process-wide CameraX provider; leaving the service running makes the
     * preview black and the analyzer receive no frames. [reloadPresence] restarts the service
     * when enrollment returns, whether it succeeded or was cancelled.
     */
    fun suspendPresenceForEnrollment() {
        stopPresenceService()
    }

    private suspend fun tryDuressUnlock(secret: CharArray): UnlockResult? {
        val component = duress ?: return null
        if (decoyMode) return null
        decoyTransition = true
        val opened = try {
            component.openDecoy(secret)
        } catch (e: CancellationException) {
            decoyTransition = false
            throw e
        } catch (_: Exception) {
            false
        }
        if (!opened) {
            decoyTransition = false
            return null
        }
        val installed = enterDecoyMode()
        decoyTransition = false
        return if (installed) UnlockResult.Success(VaultMode.DECOY) else null
    }

    private fun enterDecoyMode(): Boolean {
        val component = duress ?: return false
        val key = component.decoyVault.session.currentKey() ?: return false
        val bytes = key.copyBytes()
        try {
            VaultSession.setKey(VaultKey.from(bytes))
        } finally {
            SecretZeroizer.zero(bytes)
        }
        VaultFeature.install(component.decoyVault.repository())
        decoyMode = true
        return true
    }

    private fun exitDecoyMode() {
        duress?.decoyVault?.session?.lock()
        VaultFeature.install(vaultRepository)
        decoyMode = false
    }

    /**
     * Polls the persisted failure counter. When it increases past the configured threshold the
     * host is offered a warning; this loop never starts the camera and never captures by itself.
     */
    private suspend fun pollUnlockFailures() {
        lastFailureCount = try {
            auth.attemptLimiter.current().failures
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            0
        }
        while (true) {
            delay(1_000L)
            val state = try {
                auth.attemptLimiter.current()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            when {
                state.failures > lastFailureCount -> {
                    lastFailureCount = state.failures
                    if (!decoyMode) {
                        unlockWarningHost?.onFailureCount(state.failures)
                    }
                }

                state.failures < lastFailureCount -> {
                    lastFailureCount = state.failures
                    try {
                        duress?.intruderCoordinator?.resetWindow()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // The intruder log is optional; the counter reset is already recorded.
                    }
                    unlockWarningHost?.clear()
                }
            }
        }
    }

    /**
     * Copies the auth-owned key into the vault session holder. The copy is owned by
     * [VaultSession] and is zeroed on lock; the auth session keeps its own key.
     */
    fun syncVaultSession() {
        val session = auth.sessions.current() ?: return
        val bytes = runCatching { session.copyKeyBytes() }.getOrNull() ?: return
        try {
            VaultSession.setKey(VaultKey.from(bytes))
        } finally {
            SecretZeroizer.zero(bytes)
        }
    }

    /** Manual lock from the shell. Auth clears the session; the collector clears the vault copy. */
    fun lockNow() {
        auth.lockController.lock(LockReason.MANUAL)
    }

    private fun lockFromPresence(reason: LockReason) {
        if (appForeground) auth.lockController.lock(reason)
    }

    private fun startPresenceService() {
        try {
            ContextCompat.startForegroundService(
                application,
                Intent(application, PresenceShieldService::class.java),
            )
        } catch (_: Exception) {
            // Android 12+ may refuse a camera FGS start from a non-foreground state. The next
            // unlock/resume retries; the shield fails closed without a camera.
        }
    }

    private fun stopPresenceService() {
        application.stopService(Intent(application, PresenceShieldService::class.java))
    }

    private fun applyTheme(mode: ThemeMode) {
        VaultThemeState.mode = mode
        AuthThemeState.mode = mode
    }

    /**
     * Runs a required initialization step. A failure is rethrown as [StartupException] so the
     * application shell can identify the broken component and render a recovery screen.
     */
    private fun <T> critical(component: String, block: () -> T): T = try {
        block()
    } catch (fatal: Throwable) {
        if (fatal is VirtualMachineError || fatal is ThreadDeath) throw fatal
        throw StartupException(component, fatal)
    }

    /**
     * Runs an optional subsystem. Keystore aliases, WorkManager, ML Kit native libraries, and
     * Room files are allowed to fail independently; the rest of the vault remains usable.
     */
    private fun <T> optional(component: String, block: () -> T): T? = try {
        block()
    } catch (fatal: Throwable) {
        if (fatal is VirtualMachineError || fatal is ThreadDeath) throw fatal
        Log.w(TAG, "$component failed to initialize; continuing without it", fatal)
        warnings += "$component unavailable (${fatal.javaClass.simpleName})"
        null
    }

    private companion object {
        const val TAG = "AppContainer"
    }
}

/**
 * Defers ML Kit construction until the shield has a frame to analyze. Startup therefore performs
 * no Play Services lookup and cannot be killed by a broken ML Kit installation.
 */
private class LazyFaceDetector(
    private val factory: () -> FaceDetector,
) : FaceDetector {
    private val delegate = lazy {
        runCatching(factory).getOrElse { error ->
            Log.w("AppContainer", "Face detector unavailable", error)
            UnavailableFaceDetector(error)
        }
    }

    override suspend fun detect(image: FaceImage): List<DetectedFace> = delegate.value.detect(image)

    override fun close() {
        if (delegate.isInitialized()) delegate.value.close()
    }
}

private class LazyFaceEmbedder(
    private val factory: () -> FaceEmbedder,
) : FaceEmbedder {
    private val delegate = lazy {
        runCatching(factory).getOrElse { error ->
            Log.w("AppContainer", "Face embedder unavailable", error)
            UnavailableFaceEmbedder(error)
        }
    }

    override suspend fun embed(image: FaceImage, face: DetectedFace): FaceEmbedding =
        delegate.value.embed(image, face)

    override fun close() {
        if (delegate.isInitialized()) delegate.value.close()
    }
}

/**
 * Presence stand-ins used when ML Kit or LiteRT cannot load on this device. The shield catches
 * these failures and reports "Face detection failed" instead of terminating the process.
 */
private class UnavailableFaceDetector(
    private val cause: Throwable? = null,
) : FaceDetector {
    override suspend fun detect(image: FaceImage): List<DetectedFace> =
        throw KeeperException.Presence("Face detection is unavailable on this device", cause)

    override fun close() = Unit
}

private class UnavailableFaceEmbedder(
    private val cause: Throwable? = null,
) : FaceEmbedder {
    override suspend fun embed(image: FaceImage, face: DetectedFace): FaceEmbedding =
        throw KeeperException.Presence("Face embedding is unavailable on this device", cause)

    override fun close() = Unit
}

/** Plain-preferences theme store. Theme is not a secret. */
class SharedPreferencesThemeStore(
    context: Context,
) : ThemeStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val state = MutableStateFlow(read())

    override val theme: StateFlow<ThemeMode> = state.asStateFlow()

    override suspend fun setTheme(theme: ThemeMode) {
        preferences.edit().putString(THEME_KEY, theme.name).apply()
        state.value = theme
    }

    private fun read(): ThemeMode = runCatching {
        ThemeMode.valueOf(preferences.getString(THEME_KEY, ThemeMode.DARK.name)!!)
    }.getOrDefault(ThemeMode.DARK)

    companion object {
        const val PREFERENCES_NAME: String = "keeper_settings"
        const val THEME_KEY: String = "theme"
    }
}

private class AppBuildInfo(
    application: Application,
) : BuildInfo {
    override val debuggable: Boolean =
        (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    override val versionName: String = runCatching {
        application.packageManager.getPackageInfo(application.packageName, 0).versionName
    }.getOrNull() ?: "unknown"
}
