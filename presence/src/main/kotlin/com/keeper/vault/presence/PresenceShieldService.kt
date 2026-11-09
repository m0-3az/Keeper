package com.keeper.vault.presence

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.keeper.vault.presence.platform.CameraFrameSource
import com.keeper.vault.presence.platform.PresenceNotifications
import com.keeper.vault.presence.shield.PresenceFrameSourceAware
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Camera foreground service for the presence shield.
 *
 * The service owns the CameraX lifecycle and the camera frame source. The app supplies the
 * [com.keeper.vault.contracts.presence.PresenceShield] through [PresenceFeature]; the service only
 * attaches frames to it. It never receives or holds a `VaultKey` and never decrypts anything.
 *
 * When the gate is false the service stops the shield, unbinds the camera, removes the
 * notification and stops itself. The app starts it again after the next unlock.
 */
class PresenceShieldService : Service(), LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var gateJob: Job? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var frameSource: CameraFrameSource? = null
    private var sessionActive: Boolean = false
    private var started: Boolean = false
    private var foregroundStarted: Boolean = false

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        PresenceNotifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startForegroundSafely()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            startGateObservation()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        gateJob?.cancel()
        gateJob = null
        stopPresence(stopSelf = false)
        scope.cancel()
        cameraExecutor.shutdown()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private fun startGateObservation() {
        gateJob = scope.launch {
            val gate = PresenceFeature.gateOrNull()
            if (gate == null) {
                stopSelf()
                return@launch
            }
            gate.unlocked.collect { unlocked ->
                if (unlocked) {
                    startPresence()
                } else {
                    stopPresence(stopSelf = true)
                }
            }
        }
    }

    private fun startPresence() {
        if (sessionActive) return
        val shield = PresenceFeature.shieldOrNull()
        if (shield !is PresenceFrameSourceAware) {
            stopSelf()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        val source = CameraFrameSource()
        frameSource = source
        shield.setFrameSource(source)
        sessionActive = true

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                if (!sessionActive) return@addListener
                try {
                    val provider = future.get()
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setTargetResolution(Size(640, 480))
                        .build()
                    analysis.setAnalyzer(cameraExecutor, source)
                    provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        analysis,
                    )
                    cameraProvider = provider
                    imageAnalysis = analysis
                    shield.start()
                } catch (_: Throwable) {
                    stopPresence(stopSelf = true)
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun stopPresence(stopSelf: Boolean) {
        val wasActive = sessionActive
        sessionActive = false
        PresenceFeature.shieldOrNull()?.let { shield ->
            if (shield is PresenceFrameSourceAware) {
                shield.setFrameSource(null)
            }
            if (wasActive) shield.stop()
        }
        val analysis = imageAnalysis
        analysis?.clearAnalyzer()
        imageAnalysis = null
        // Unbind only this service's use case. `unbindAll()` would also tear down the enrollment
        // activity's preview and analyzer, which share the process-wide CameraX provider.
        if (analysis != null) {
            cameraProvider?.unbind(analysis)
        }
        cameraProvider = null
        frameSource?.close()
        frameSource = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        if (stopSelf) stopSelf()
    }

    private fun startForegroundSafely(): Boolean {
        if (foregroundStarted) return true
        return try {
            val notification = PresenceNotifications.build(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceCompat.startForeground(
                    this,
                    PresenceNotifications.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
                )
            } else {
                startForeground(PresenceNotifications.NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
            true
        } catch (_: Exception) {
            // Android 12+ can refuse a camera FGS start from the background. Stop instead of
            // crashing; the app retries while it is foreground-eligible after unlock.
            false
        }
    }
}
