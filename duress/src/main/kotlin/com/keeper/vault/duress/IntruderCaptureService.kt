package com.keeper.vault.duress

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.os.Build
import android.os.IBinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.keeper.vault.contracts.intruder.IntruderEvent
import com.keeper.vault.contracts.intruder.IntruderTrigger
import com.keeper.vault.contracts.isolation.TamperKind
import com.keeper.vault.contracts.isolation.TamperSignal
import com.keeper.vault.duress.intruder.CaptureConfirmationGate
import com.keeper.vault.duress.intruder.CaptureWatchdog
import com.keeper.vault.duress.settings.IntruderConsentStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Camera foreground service for a single intruder still.
 *
 * Flow: start foreground with the camera type, verify the permission and the opt-in, bind the
 * front camera to this service's lifecycle, capture one JPEG, seal it with the dedicated
 * IntruderKey (via the component's recorder), insert one metadata row, and stop.
 *
 * It fails closed and quietly: no camera, no permission, a refused foreground start, or a
 * concurrent camera user produces a tamper-log entry and no capture. It never touches the vault
 * key, never uploads, and the Android camera privacy indicator is expected.
 */
class IntruderCaptureService : Service(), LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var watchdog: CaptureWatchdog? = null
    private var foregroundStarted: Boolean = false
    private val finished = AtomicBoolean(false)
    private var startedOnce: Boolean = false

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        IntruderNotifications.ensureChannel(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val event = intent?.let(::eventFrom)
        if (event == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (startedOnce) {
            // Only one still per service instance; repeated starts are ignored.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startedOnce = true
        if (!IntruderConsentStore(this).isAccepted()) {
            failAndStop(startId, "intruder consent missing")
            return START_NOT_STICKY
        }
        val component = try {
            DuressFeature.component(applicationContext)
        } catch (_: Exception) {
            null
        }
        if (component == null || !component.intruderSettings.settings.value.enabled) {
            failAndStop(startId, "intrusion capture is disabled")
            return START_NOT_STICKY
        }
        val token = intent?.getStringExtra(EXTRA_CONFIRMATION_TOKEN)
        if (!CaptureConfirmationGate.consume(token, System.currentTimeMillis())) {
            failAndStop(startId, "capture not confirmed")
            return START_NOT_STICKY
        }
        if (!startForegroundSafely()) {
            failAndStop(startId, "foreground start refused")
            return START_NOT_STICKY
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            failAndStop(startId, "camera permission missing")
            return START_NOT_STICKY
        }
        captureOnce(event, startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watchdog?.cancel()
        watchdog = null
        scope.cancel()
        cameraExecutor.shutdown()
        unbindCamera()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private fun captureOnce(event: IntruderEvent, startId: Int) {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        val captureWatchdog = CaptureWatchdog(scope, CAPTURE_TIMEOUT_MILLIS) {
            failAndStop(startId, "camera timeout")
        }
        watchdog = captureWatchdog
        captureWatchdog.start()
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                if (finished.get()) return@addListener
                try {
                    val provider = future.get()
                    if (!provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                        failAndStop(startId, "front camera unavailable")
                        return@addListener
                    }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                    provider.unbindAll()
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, capture)
                    cameraProvider = provider
                    imageCapture = capture
                    capture.takePicture(
                        cameraExecutor,
                        object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                if (finished.get()) {
                                    image.close()
                                    return
                                }
                                val bytes = try {
                                    image.jpegBytes()
                                } finally {
                                    image.close()
                                }
                                if (bytes == null) {
                                    scope.launch { failAndStop(startId, "unsupported capture format") }
                                } else {
                                    // Camera delivery is the timeout boundary; storage has its own
                                    // terminal path and must not be reported as a camera timeout.
                                    captureWatchdog.cancel()
                                    persist(event, bytes, startId)
                                }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                scope.launch { failAndStop(startId, "camera capture failed") }
                            }
                        },
                    )
                } catch (_: Throwable) {
                    failAndStop(startId, "camera binding failed")
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun persist(
        event: IntruderEvent,
        jpeg: ByteArray,
        startId: Int,
    ) {
        scope.launch {
            if (finished.get()) {
                jpeg.fill(0)
                return@launch
            }
            try {
                val component = DuressFeature.component(applicationContext)
                component.intruderRecorder.recordReserved(event, jpeg)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                reportFailure("encrypted capture could not be stored")
            } finally {
                jpeg.fill(0)
                finish(startId)
            }
        }
    }

    private fun failAndStop(startId: Int, reason: String) {
        if (!finished.compareAndSet(false, true)) return
        watchdog?.cancel()
        watchdog = null
        reportFailure(reason)
        stopCaptureService(startId)
    }

    private fun reportFailure(reason: String) {
        scope.launch {
            // The service is stopped immediately after this call. Keep the advisory log write
            // alive long enough to record why the capture failed.
            withContext(NonCancellable) {
                try {
                    val component = DuressFeature.componentOrNull() ?: return@withContext
                    component.tamperLog?.append(
                        TamperSignal(
                            kind = TamperKind.UNKNOWN,
                            detectedAtMillis = System.currentTimeMillis(),
                            detail = "intruder capture failed: $reason",
                        ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The service must never crash the app because the advisory log is unavailable.
                }
            }
        }
    }

    private fun finish(startId: Int) {
        if (!finished.compareAndSet(false, true)) return
        watchdog?.cancel()
        watchdog = null
        stopCaptureService(startId)
    }

    private fun stopCaptureService(startId: Int) {
        unbindCamera()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf(startId)
    }

    private fun unbindCamera() {
        imageCapture = null
        cameraProvider?.unbindAll()
        cameraProvider = null
    }

    private fun startForegroundSafely(): Boolean {
        if (foregroundStarted) return true
        return try {
            val notification = IntruderNotifications.build(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceCompat.startForeground(
                    this,
                    IntruderNotifications.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
                )
            } else {
                startForeground(IntruderNotifications.NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
            true
        } catch (_: Exception) {
            // Android 12+ can refuse a camera FGS start from the background. Fail closed.
            false
        }
    }

    private fun eventFrom(intent: Intent): IntruderEvent? {
        val id = intent.getStringExtra(EXTRA_EVENT_ID) ?: return null
        val capturedAt = intent.getLongExtra(EXTRA_CAPTURED_AT, -1L)
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: return null
        if (capturedAt < 0L || fileName != "$id.enc") return null
        val trigger = intent.getStringExtra(EXTRA_TRIGGER)?.let { raw ->
            IntruderTrigger.entries.firstOrNull { it.name == raw }
        } ?: return null
        return IntruderEvent(
            id = id,
            capturedAtMillis = capturedAt,
            trigger = trigger,
            encryptedFileName = fileName,
        )
    }

    private fun ImageProxy.jpegBytes(): ByteArray? {
        if (format != ImageFormat.JPEG || planes.isEmpty()) return null
        val buffer = planes[0].buffer
        if (!buffer.hasRemaining()) return null
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    companion object {
        /** Bound for provider acquisition, binding, and the single still. */
        const val CAPTURE_TIMEOUT_MILLIS: Long = 12_000L

        const val EXTRA_TRIGGER: String = "com.keeper.vault.duress.extra.TRIGGER"
        const val EXTRA_EVENT_ID: String = "com.keeper.vault.duress.extra.EVENT_ID"
        const val EXTRA_CAPTURED_AT: String = "com.keeper.vault.duress.extra.CAPTURED_AT"
        const val EXTRA_FILE_NAME: String = "com.keeper.vault.duress.extra.FILE_NAME"
        const val EXTRA_CONFIRMATION_TOKEN: String = "com.keeper.vault.duress.extra.CONFIRMATION_TOKEN"
    }
}
