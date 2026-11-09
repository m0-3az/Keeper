package com.keeper.vault.presence

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.keeper.vault.contracts.presence.FaceEnrollmentStore
import com.keeper.vault.presence.enrollment.FaceEnrollmentCollector
import com.keeper.vault.presence.platform.MLKitFaceDetector
import com.keeper.vault.presence.platform.MobileFaceNetEmbedder
import com.keeper.vault.presence.platform.RgbaImageProxyConverter
import com.keeper.vault.presence.ui.EnrollmentScreen
import com.keeper.vault.presence.ui.EnrollmentUiState
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Compose owner-enrollment screen.
 *
 * Captures 10 frames (the contract allows 5-10), averages the embeddings and asks the injected
 * [FaceEnrollmentStore] to encrypt the result. The activity never sees a VaultKey and never
 * writes a file itself. The window is FLAG_SECURE before any content is shown.
 *
 * Every hardware hop is defensive: ML Kit and LiteRT are initialized off the main thread behind
 * try/catch (including [OutOfMemoryError]), CameraX binding is retried after the presence service
 * releases the camera, and a preview+analysis bind can fall back to analysis-only. Any permanent
 * failure is shown in the UI with a "Skip Face Enrollment" action; it never crashes the process or
 * traps the user.
 */
class PresenceEnrollmentActivity : ComponentActivity() {
    private val stateFlow = MutableStateFlow<EnrollmentUiState>(EnrollmentUiState.NeedsPermission)
    private val collector = FaceEnrollmentCollector(minFrames = MIN_FRAMES, maxFrames = MAX_FRAMES)
    private val analysisScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "keeper-face-camera").apply { isDaemon = true }
    }
    private val processing = AtomicBoolean(false)
    private val lastAnalyzedAt = AtomicLong(0L)
    private val frameCount = AtomicInteger(0)
    private val analysisErrors = AtomicInteger(0)
    private val lastFrameAt = AtomicLong(0L)

    private var store: FaceEnrollmentStore? = null
    private var detector: MLKitFaceDetector? = null
    private var embedder: MobileFaceNetEmbedder? = null
    private var previewView: PreviewView? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var previewUseCase: Preview? = null
    private var stallJob: Job? = null
    private var frameWatchJob: Job? = null

    @Volatile
    private var cameraStarted: Boolean = false

    @Volatile
    private var binding: Boolean = false

    @Volatile
    private var preparing: Boolean = false

    @Volatile
    private var enrolling: Boolean = false

    @Volatile
    private var saving: Boolean = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Log.i(TAG, "Camera permission result: granted=$granted")
        if (granted) {
            beginScanning()
        } else {
            stateFlow.value = EnrollmentUiState.Failed(
                "Camera permission was denied. Face enrollment cannot continue. " +
                    "You can skip it and keep using the vault.",
                retryable = true,
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(
            TAG,
            "onCreate: device=${Build.MANUFACTURER}/${Build.MODEL} sdk=${Build.VERSION.SDK_INT}",
        )
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        enableEdgeToEdge()

        // The shield service owns the front camera while the vault is unlocked. Stopping it is
        // asynchronous, so binding below also retries if CameraX still reports the camera busy.
        try {
            stopService(Intent(this, PresenceShieldService::class.java))
        } catch (error: Exception) {
            Log.e(TAG, "Could not stop the presence service", error)
        }

        store = PresenceFeature.enrollmentStoreOrNull()
        Log.i(TAG, "Enrollment store present=${store != null}")

        setContent {
            val state = stateFlow.collectAsStateWithLifecycle()
            EnrollmentScreen(
                state = state.value,
                onRequestPermission = {
                    Log.i(TAG, "Requesting camera permission")
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                },
                onRetry = ::retry,
                onSkip = ::skipEnrollment,
            ) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                    factory = { context ->
                        Log.i(TAG, "Camera: creating PreviewView (COMPATIBLE)")
                        PreviewView(context).also { view ->
                            view.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            view.scaleType = PreviewView.ScaleType.FILL_CENTER
                            previewView = view
                            maybeStartCamera()
                        }
                    },
                    update = { view ->
                        previewView = view
                        maybeStartCamera()
                    },
                )
            }
        }

        when {
            store == null -> {
                Log.e(TAG, "Enrollment store is null; presence was not installed")
                stateFlow.value = EnrollmentUiState.Failed(
                    "Enrollment is not configured. Unlock the vault and try again.",
                    retryable = false,
                )
            }

            !hasCameraHardware() -> {
                Log.e(TAG, "Device reports no camera hardware")
                stateFlow.value = EnrollmentUiState.Failed(
                    "This device has no camera, so the owner face cannot be enrolled. " +
                        "The vault remains usable.",
                    retryable = false,
                )
            }

            hasCameraPermission() -> {
                Log.i(TAG, "Camera permission already granted; preparing engines")
                beginScanning()
            }

            else -> {
                Log.i(TAG, "Waiting for camera permission")
                stateFlow.value = EnrollmentUiState.NeedsPermission
            }
        }
    }

    override fun onDestroy() {
        enrolling = false
        preparing = false
        saving = false
        stallJob?.cancel()
        stallJob = null
        frameWatchJob?.cancel()
        frameWatchJob = null
        runCatching { analysisUseCase?.clearAnalyzer() }
            .onFailure { Log.e(TAG, "Could not clear analyzer", it) }
        analysisUseCase = null
        previewUseCase = null
        runCatching { cameraProvider?.unbindAll() }
            .onFailure { Log.e(TAG, "Could not unbind camera", it) }
        cameraProvider = null
        cameraStarted = false
        binding = false
        runCatching { detector?.close() }
            .onFailure { Log.e(TAG, "Could not close ML Kit", it) }
        detector = null
        runCatching { embedder?.close() }
            .onFailure { Log.e(TAG, "Could not close LiteRT", it) }
        embedder = null
        cameraExecutor.shutdown()
        analysisScope.cancel()
        super.onDestroy()
    }

    /**
     * Loads ML Kit and LiteRT on a background dispatcher, then starts the preview. A failure is
     * shown as a message instead of taking the process down.
     */
    private fun beginScanning() {
        Log.i(
            TAG,
            "beginScanning: store=${store != null} permission=${hasCameraPermission()} " +
                "preparing=$preparing enrolling=$enrolling saving=$saving",
        )
        if (store == null) {
            stateFlow.value = EnrollmentUiState.Failed(
                "Enrollment is not configured. Unlock the vault and try again.",
                retryable = false,
            )
            return
        }
        if (!hasCameraPermission()) {
            stateFlow.value = EnrollmentUiState.NeedsPermission
            return
        }
        if (preparing || enrolling || saving) return

        preparing = true
        stateFlow.value = EnrollmentUiState.Preparing
        lifecycleScope.launch {
            val failure = withContext(Dispatchers.Default) { prepareEngines() }
            preparing = false
            if (isFinishing || isDestroyed) return@launch
            if (failure != null) {
                Log.e(TAG, "Engine preparation failed: ${failure.message}")
                stateFlow.value = failure
                return@launch
            }

            Log.i(TAG, "Engines ready; starting camera")
            collector.reset()
            analysisErrors.set(0)
            saving = false
            enrolling = true
            cameraStarted = false
            binding = false
            lastFrameAt.set(0L)
            stateFlow.value = EnrollmentUiState.Scanning(
                captured = 0,
                maximum = MAX_FRAMES,
                hint = "Center your face and hold still.",
            )
            maybeStartCamera()
            startStallWatch()
        }
    }

    /** Returns a user-facing failure state, or null when both engines are ready. */
    private fun prepareEngines(): EnrollmentUiState.Failed? {
        if (detector == null) {
            detector = try {
                MLKitFaceDetector()
            } catch (error: OutOfMemoryError) {
                Log.e(TAG, "ML Kit: out of memory during initialization", error)
                return EnrollmentUiState.Failed(
                    "ML Kit ran out of memory on this device. Face enrollment unavailable.",
                    retryable = false,
                )
            } catch (fatal: Throwable) {
                if (fatal is VirtualMachineError || fatal is ThreadDeath) throw fatal
                Log.e(TAG, "ML Kit: initialization failed", fatal)
                return EnrollmentUiState.Failed(
                    "Face detection is unavailable on this device. Face enrollment unavailable.",
                    retryable = false,
                )
            }
            Log.i(TAG, "ML Kit: detector created")
        }

        if (embedder == null) {
            Log.i(TAG, "LiteRT: loading MobileFaceNet asset and interpreter")
            embedder = try {
                MobileFaceNetEmbedder(applicationContext).also { it.warmUp() }
            } catch (error: OutOfMemoryError) {
                Log.e(TAG, "LiteRT: out of memory while loading the model", error)
                return EnrollmentUiState.Failed(
                    "LiteRT Model failed to load on this device. Face enrollment unavailable.",
                    retryable = false,
                )
            } catch (fatal: Throwable) {
                if (fatal is VirtualMachineError || fatal is ThreadDeath) throw fatal
                Log.e(TAG, "LiteRT: model/interpreter load failed", fatal)
                return EnrollmentUiState.Failed(
                    "LiteRT Model failed to load on this device. Face enrollment unavailable.",
                    retryable = false,
                )
            }
            Log.i(TAG, "LiteRT: model ready")
        }
        return null
    }

    private fun maybeStartCamera() {
        val preview = previewView ?: run {
            Log.d(TAG, "Camera: PreviewView not attached yet")
            return
        }
        if (cameraStarted || binding) {
            Log.d(TAG, "Camera: already started=$cameraStarted binding=$binding")
            return
        }
        if (!enrolling || !hasCameraPermission() || store == null) {
            Log.d(TAG, "Camera: not ready (enrolling=$enrolling permission=${hasCameraPermission()})")
            return
        }

        binding = true
        Log.i(TAG, "Camera: requesting ProcessCameraProvider")
        val future = try {
            ProcessCameraProvider.getInstance(this)
        } catch (fatal: Throwable) {
            binding = false
            Log.e(TAG, "Camera: ProcessCameraProvider.getInstance threw", fatal)
            fail(
                "The camera service could not start: ${fatal.message ?: fatal.javaClass.simpleName}. " +
                    "The vault remains usable.",
                retryable = true,
            )
            return
        }
        future.addListener(
            {
                binding = false
                if (isFinishing || isDestroyed) {
                    Log.w(TAG, "Camera: provider ready after activity was destroyed")
                    return@addListener
                }
                val provider = try {
                    future.get()
                } catch (fatal: Throwable) {
                    Log.e(TAG, "Camera: provider future failed", fatal)
                    fail(
                        "The camera service could not start: " +
                            "${fatal.message ?: fatal.javaClass.simpleName}. " +
                            "The vault remains usable.",
                        retryable = true,
                    )
                    return@addListener
                }
                Log.i(TAG, "Camera: provider ready")
                bindCamera(provider, preview)
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun bindCamera(provider: ProcessCameraProvider, preview: PreviewView) {
        val hasFront = try {
            provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
        } catch (fatal: Throwable) {
            Log.e(TAG, "Camera: hasCamera(front) threw", fatal)
            false
        }
        Log.i(TAG, "Camera: front camera available=$hasFront")
        if (!hasFront) {
            fail(
                "This device does not expose a front camera. The vault remains usable.",
                retryable = false,
            )
            return
        }

        runCatching { provider.unbindAll() }
            .onFailure { Log.w(TAG, "Camera: unbindAll before bind failed", it) }

        val analysis = try {
            buildAnalysis()
        } catch (fatal: Throwable) {
            Log.e(TAG, "Camera: could not build ImageAnalysis", fatal)
            fail(
                "The camera analyzer could not be configured: " +
                    "${fatal.message ?: fatal.javaClass.simpleName}. The vault remains usable.",
                retryable = true,
            )
            return
        }
        Log.i(
            TAG,
            "Camera: ImageAnalysis built (RGBA_8888, ${ANALYSIS_WIDTH}x$ANALYSIS_HEIGHT, " +
                "keep-only-latest)",
        )

        val previewUseCase = try {
            Preview.Builder().build().also {
                it.surfaceProvider = preview.surfaceProvider
            }
        } catch (fatal: Throwable) {
            Log.e(TAG, "Camera: could not build Preview; trying analysis-only", fatal)
            null
        }

        bindUseCases(provider, analysis, previewUseCase, attempt = 0)
    }

    private fun buildAnalysis(): ImageAnalysis {
        val builder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        try {
            builder.setTargetResolution(Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT))
        } catch (error: Exception) {
            // Some devices reject explicit resolutions; CameraX's default is still usable.
            Log.w(TAG, "Camera: setTargetResolution rejected; using default", error)
        }
        return builder.build().also { analysis ->
            analysis.setAnalyzer(cameraExecutor) { imageProxy -> analyze(imageProxy) }
        }
    }

    /**
     * Binds Preview+Analysis, retrying while the foreground service releases the camera. If the
     * device rejects the combination, falls back to analysis-only: the preview is cosmetic and
     * enrollment can still proceed.
     */
    private fun bindUseCases(
        provider: ProcessCameraProvider,
        analysis: ImageAnalysis,
        preview: Preview?,
        attempt: Int,
    ) {
        if (!enrolling || isFinishing || isDestroyed) {
            Log.d(TAG, "Camera: bind abandoned (enrolling=$enrolling)")
            return
        }
        Log.i(
            TAG,
            "Camera: bind attempt ${attempt + 1}/${BIND_ATTEMPTS + 1} " +
                "preview=${preview != null}",
        )
        try {
            val useCases = if (preview != null) {
                arrayOf<androidx.camera.core.UseCase>(preview, analysis)
            } else {
                arrayOf<androidx.camera.core.UseCase>(analysis)
            }
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                *useCases,
            )
            cameraProvider = provider
            analysisUseCase = analysis
            previewUseCase = preview
            cameraStarted = true
            Log.i(TAG, "Camera: bound successfully (preview=${preview != null})")
            if (preview == null) {
                val current = stateFlow.value
                if (current is EnrollmentUiState.Scanning) {
                    stateFlow.value = current.copy(
                        previewAvailable = false,
                        hint = "Camera preview unavailable; hold still in front of the camera.",
                    )
                }
            }
            startFrameWatch()
        } catch (fatal: Throwable) {
            if (fatal is VirtualMachineError && fatal !is OutOfMemoryError) throw fatal
            Log.e(
                TAG,
                "Camera: bind attempt ${attempt + 1} failed: " +
                    "${fatal.javaClass.simpleName}: ${fatal.message}",
                fatal,
            )
            runCatching { provider.unbindAll() }

            if (attempt < BIND_ATTEMPTS) {
                lifecycleScope.launch {
                    delay(BIND_RETRY_DELAY_MILLIS)
                    bindUseCases(provider, analysis, preview, attempt + 1)
                }
            } else if (preview != null) {
                Log.w(TAG, "Camera: preview+analysis rejected; falling back to analysis-only")
                bindUseCases(provider, analysis, preview = null, attempt = 0)
            } else {
                fail(
                    "The front camera could not be started: " +
                        "${fatal.message ?: fatal.javaClass.simpleName}. " +
                        "The vault remains usable.",
                    retryable = true,
                )
            }
        }
    }

    private fun analyze(imageProxy: ImageProxy) {
        val total = frameCount.incrementAndGet()
        lastFrameAt.set(SystemClock.elapsedRealtime())
        if (total == 1 || total % 60 == 0) {
            Log.i(
                TAG,
                "Frame analysis #$total: ${imageProxy.width}x${imageProxy.height} " +
                    "rotation=${imageProxy.imageInfo.rotationDegrees} " +
                    "format=${imageProxy.format}",
            )
        }
        if (!enrolling) {
            imageProxy.close()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastAnalyzedAt.get() < ANALYSIS_INTERVAL_MILLIS) {
            imageProxy.close()
            return
        }
        if (!processing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        lastAnalyzedAt.set(now)
        val image = try {
            RgbaImageProxyConverter.convert(imageProxy)
        } catch (fatal: Throwable) {
            Log.e(TAG, "Frame conversion failed", fatal)
            imageProxy.close()
            processing.set(false)
            return
        }
        imageProxy.close()

        analysisScope.launch {
            try {
                val faceDetector = detector ?: return@launch
                val faceEmbedder = embedder ?: return@launch
                val faces = faceDetector.detect(image)
                if (!enrolling) return@launch
                if (faces.size != 1) {
                    hint("Look at the camera alone - no other faces in view.")
                    return@launch
                }
                val embedding = faceEmbedder.embed(image, faces.first())
                if (!enrolling) return@launch
                analysisErrors.set(0)
                val progress = collector.add(embedding)
                if (progress.captured > 0) {
                    stallJob?.cancel()
                    stallJob = null
                }
                stateFlow.value = EnrollmentUiState.Scanning(
                    captured = progress.captured,
                    maximum = progress.maximum,
                    hint = if (progress.complete) null else "Hold still.",
                )
                Log.i(TAG, "Frame analysis: captured ${progress.captured}/${progress.maximum}")
                if (progress.complete) finishEnrollment()
            } catch (error: OutOfMemoryError) {
                Log.e(TAG, "Frame analysis ran out of memory", error)
                fail(
                    "Face processing ran out of memory on this device. Face enrollment unavailable.",
                    retryable = false,
                )
            } catch (fatal: Throwable) {
                if (fatal is VirtualMachineError || fatal is ThreadDeath) throw fatal
                val count = analysisErrors.incrementAndGet()
                Log.e(TAG, "Frame analysis failed (#$count)", fatal)
                if (count >= MAX_CONSECUTIVE_ANALYSIS_ERRORS) {
                    fail(
                        "Face processing failed repeatedly: " +
                            "${fatal.message ?: fatal.javaClass.simpleName}. Try again.",
                        retryable = true,
                    )
                } else {
                    hint("Face processing hiccup; keep your face in view.")
                }
            } finally {
                processing.set(false)
            }
        }
    }

    private fun finishEnrollment() {
        if (saving) return
        saving = true
        enrolling = false
        stallJob?.cancel()
        stallJob = null
        frameWatchJob?.cancel()
        frameWatchJob = null
        val average = collector.average()
        val target = store
        if (average == null || target == null) {
            saving = false
            Log.e(TAG, "Not enough usable frames to enroll")
            stateFlow.value = EnrollmentUiState.Failed(
                "Not enough usable frames. Try again.",
                retryable = true,
            )
            return
        }
        Log.i(TAG, "Saving encrypted owner template")
        stateFlow.value = EnrollmentUiState.Saving
        lifecycleScope.launch {
            try {
                target.save(average)
                Log.i(TAG, "Owner template saved; finishing with RESULT_OK")
                stateFlow.value = EnrollmentUiState.Complete
                stopCamera()
                setResult(RESULT_OK)
                finish()
            } catch (error: OutOfMemoryError) {
                Log.e(TAG, "Out of memory while saving the template", error)
                collector.reset()
                stateFlow.value = EnrollmentUiState.Failed(
                    "Not enough memory to save the enrollment. Try again.",
                    retryable = true,
                )
            } catch (fatal: Throwable) {
                if (fatal is VirtualMachineError || fatal is ThreadDeath) throw fatal
                Log.e(TAG, "Could not save the encrypted enrollment", fatal)
                collector.reset()
                stateFlow.value = EnrollmentUiState.Failed(
                    "Could not save the encrypted enrollment: " +
                        "${fatal.message ?: "vault unavailable"}. Try again.",
                    retryable = true,
                )
            } finally {
                saving = false
            }
        }
    }

    private fun startStallWatch() {
        stallJob?.cancel()
        stallJob = lifecycleScope.launch {
            delay(STALL_HINT_MILLIS)
            val current = stateFlow.value
            if (current is EnrollmentUiState.Scanning && current.captured == 0) {
                hint("No face detected yet. Improve the light and center your face.")
            }
        }
    }

    /**
     * If CameraX never delivers a frame (camera busy, AOSP HAL quirk), say so instead of leaving
     * the user staring at a frozen preview forever.
     */
    private fun startFrameWatch() {
        frameWatchJob?.cancel()
        frameWatchJob = lifecycleScope.launch {
            delay(FRAME_WATCHDOG_MILLIS)
            if (!enrolling || isFinishing || isDestroyed) return@launch
            val last = lastFrameAt.get()
            if (last == 0L || SystemClock.elapsedRealtime() - last > FRAME_WATCHDOG_MILLIS) {
                Log.e(TAG, "No camera frames delivered within $FRAME_WATCHDOG_MILLIS ms")
                hint(
                    "The camera is not delivering frames. Tap Try again, or Skip Face " +
                        "Enrollment and keep using the vault.",
                )
            }
        }
    }

    private fun retry() {
        Log.i(TAG, "Retry requested")
        if (!hasCameraPermission()) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            beginScanning()
        }
    }

    private fun skipEnrollment() {
        enrolling = false
        preparing = false
        saving = false
        stallJob?.cancel()
        stallJob = null
        frameWatchJob?.cancel()
        frameWatchJob = null
        runOnUiThread { stopCamera() }
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun fail(message: String, retryable: Boolean) {
        Log.e(TAG, "Enrollment failed: $message")
        enrolling = false
        preparing = false
        binding = false
        cameraStarted = false
        stallJob?.cancel()
        stallJob = null
        frameWatchJob?.cancel()
        frameWatchJob = null
        stateFlow.value = EnrollmentUiState.Failed(message, retryable)
        // The analyzer runs on a background coroutine; CameraX unbinding belongs to the main
        // thread. runOnUiThread executes immediately when called from the main thread.
        runOnUiThread { stopCamera() }
    }

    private fun stopCamera() {
        runCatching { analysisUseCase?.clearAnalyzer() }
            .onFailure { Log.e(TAG, "Camera: clearAnalyzer failed", it) }
        analysisUseCase = null
        previewUseCase = null
        runCatching { cameraProvider?.unbindAll() }
            .onFailure { Log.e(TAG, "Camera: unbindAll failed", it) }
        cameraProvider = null
        cameraStarted = false
        binding = false
    }

    private fun hint(message: String) {
        val current = stateFlow.value
        if (current is EnrollmentUiState.Scanning) {
            stateFlow.value = current.copy(hint = message)
        }
    }

    private fun hasCameraHardware(): Boolean = try {
        packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    } catch (error: Exception) {
        Log.e(TAG, "Could not query camera feature", error)
        false
    }

    private fun hasCameraPermission(): Boolean = try {
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    } catch (error: Exception) {
        Log.e(TAG, "Could not query camera permission", error)
        false
    }

    private companion object {
        const val TAG: String = "FaceEnroll"
        const val MIN_FRAMES: Int = 5
        const val MAX_FRAMES: Int = 10
        const val ANALYSIS_INTERVAL_MILLIS: Long = 500L
        const val STALL_HINT_MILLIS: Long = 15_000L
        const val FRAME_WATCHDOG_MILLIS: Long = 10_000L
        const val MAX_CONSECUTIVE_ANALYSIS_ERRORS: Int = 15
        const val BIND_ATTEMPTS: Int = 3
        const val BIND_RETRY_DELAY_MILLIS: Long = 600L
        const val ANALYSIS_WIDTH: Int = 640
        const val ANALYSIS_HEIGHT: Int = 480
    }
}
