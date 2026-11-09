package com.keeper.vault.contracts.presence

import kotlinx.coroutines.flow.SharedFlow

/** Integer rectangle in image coordinates. */
data class IntRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(right >= left) { "right must be >= left" }
        require(bottom >= top) { "bottom must be >= top" }
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** Normalized or pixel point, depending on the producer. Documented per use. */
data class FloatPoint(val x: Float, val y: Float)

/** Pixel format of a frame handed to the detector/embedder. */
enum class FaceImageFormat {
    NV21,
    YUV_420_888,
    RGBA_8888,
}

/**
 * One camera frame, copied into a plain byte array so the behavior layer does not leak CameraX
 * types into the contracts.
 *
 * The producer owns [data] and may reuse it after the call returns; consumers that need to retain
 * pixels must copy them.
 */
class FaceImage(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val format: FaceImageFormat,
    val data: ByteArray,
) {
    init {
        require(width > 0) { "width must be positive" }
        require(height > 0) { "height must be positive" }
        require(rotationDegrees in 0..359) { "rotationDegrees must be in 0..359" }
        require(data.isNotEmpty()) { "data must not be empty" }
    }

    override fun toString(): String =
        "FaceImage(width=$width, height=$height, rotation=$rotationDegrees, format=$format, bytes=${data.size})"
}

/**
 * One detected face. Eye probabilities come from ML Kit classification mode; null means the
 * detector did not provide them.
 */
data class DetectedFace(
    val bounds: IntRect,
    val leftEye: FloatPoint?,
    val rightEye: FloatPoint?,
    val leftEyeOpenProbability: Float?,
    val rightEyeOpenProbability: Float?,
    val trackingId: Int?,
)

/**
 * Face embedding produced by MobileFaceNet.
 *
 * The vector is copied on construction and on read. Callers must zero copies when done. The
 * distance is L2; the matcher owns the threshold and must be calibrated on the real device.
 */
class FaceEmbedding(values: FloatArray) {
    private val data: FloatArray = values.copyOf()

    init {
        require(data.isNotEmpty()) { "Embedding must not be empty" }
        require(data.all { it.isFinite() }) { "Embedding must contain only finite values" }
    }

    /** Returns a defensive copy. The caller owns it and should zero it. */
    fun values(): FloatArray = data.copyOf()

    val size: Int get() = data.size

    /** L2 distance. Identical embeddings return 0. */
    fun l2Distance(other: FaceEmbedding): Float {
        require(size == other.size) { "Embedding dimensions differ" }
        var sum = 0.0
        for (i in 0 until size) {
            val delta = data[i] - other.data[i]
            sum += delta * delta
        }
        return kotlin.math.sqrt(sum).toFloat()
    }

    override fun toString(): String = "FaceEmbedding(size=$size, values=[redacted])"
}

/** Detects faces in a frame. Implemented with bundled ML Kit Face Detection. */
interface FaceDetector {
    suspend fun detect(image: FaceImage): List<DetectedFace>

    fun close()
}

/** Produces an embedding for a detected face. Implemented with MobileFaceNet on LiteRT. */
interface FaceEmbedder {
    suspend fun embed(image: FaceImage, face: DetectedFace): FaceEmbedding

    fun close()
}

/**
 * Compares embeddings. The threshold is configuration, not a security constant.
 *
 * A photo can spoof an embedding; this matcher is a lock-behavior input, never a key input.
 */
interface FaceMatcher {
    fun distance(a: FaceEmbedding, b: FaceEmbedding): Float

    fun isOwner(candidate: FaceEmbedding, enrolled: FaceEmbedding, threshold: Float): Boolean
}

/**
 * Stores the averaged enrollment embedding.
 *
 * The production implementation persists the embedding **encrypted in the vault**. It must be
 * loaded only while the vault is open and zeroed when the vault locks.
 */
interface FaceEnrollmentStore {
    suspend fun save(embedding: FaceEmbedding)

    suspend fun load(): FaceEmbedding?

    suspend fun clear()
}

/** Blink/liveness state machine. */
sealed class LivenessState {
    data object Idle : LivenessState()

    data object WaitingForFace : LivenessState()

    data object WaitingForBlink : LivenessState()

    data object BlinkDetected : LivenessState()

    data object TimedOut : LivenessState()

    data object MultipleFaces : LivenessState()
}

/**
 * Blink detector driven by eye-open probabilities.
 *
 * At 1–2 fps a blink can be missed entirely. Phase 4 must use the two-rate design: a short
 * 10–15 fps challenge burst while this detector is active, then return to 1–2 fps.
 */
interface LivenessDetector {
    /** Clears state before a new challenge. */
    fun reset()

    /** Feeds one frame. [timestampMillis] is monotonic. */
    fun onFrame(faces: List<DetectedFace>, timestampMillis: Long): LivenessState
}

/** Events emitted by the continuous presence loop. */
sealed class PresenceEvent {
    data object OwnerPresent : PresenceEvent()

    data object NoFace : PresenceEvent()

    data object MultipleFaces : PresenceEvent()

    data object OwnerMismatch : PresenceEvent()

    data object BlinkTimeout : PresenceEvent()

    data object LivenessPassed : PresenceEvent()

    data class Failure(val message: String) : PresenceEvent()
}

/**
 * Presence Shield tuning. These are starting points to be calibrated on a real device, not
 * security guarantees.
 *
 * @property continuousAnalysisIntervalMillis 700 ms ≈ 1.4 fps for presence/matching.
 * @property challengeBurstFps short high-rate burst used only during the blink challenge.
 * @property challengeBurstMillis duration of that burst.
 * @property noFaceTimeoutMillis lock after this long without a face.
 * @property blinkTimeoutMillis fail liveness if no blink within this window.
 * @property startupGraceMillis avoid a false lock while the camera warms up.
 * @property matchThreshold L2 distance threshold; tune per device/model.
 * @property requiredConsecutiveMatches reduce single-frame false accepts.
 */
data class PresenceConfig(
    val continuousAnalysisIntervalMillis: Long = 700L,
    val challengeBurstFps: Int = 15,
    val challengeBurstMillis: Long = 1_500L,
    val noFaceTimeoutMillis: Long = 2_000L,
    val blinkTimeoutMillis: Long = 10_000L,
    val startupGraceMillis: Long = 1_500L,
    val matchThreshold: Float = 0.75f,
    val requiredConsecutiveMatches: Int = 2,
) {
    init {
        require(continuousAnalysisIntervalMillis > 0) { "interval must be positive" }
        require(challengeBurstFps in 1..30) { "challengeBurstFps must be in 1..30" }
        require(challengeBurstMillis > 0) { "challengeBurstMillis must be positive" }
        require(noFaceTimeoutMillis > 0) { "noFaceTimeoutMillis must be positive" }
        require(blinkTimeoutMillis > 0) { "blinkTimeoutMillis must be positive" }
        require(startupGraceMillis >= 0) { "startupGraceMillis must be non-negative" }
        require(matchThreshold > 0f) { "matchThreshold must be positive" }
        require(requiredConsecutiveMatches >= 1) { "requiredConsecutiveMatches must be >= 1" }
    }
}

/**
 * Continuous owner-presence controller.
 *
 * Behavior only: start it after unlock, stop it when locked. It never receives the VaultKey and
 * cannot decrypt anything. Analysis is suspended while the vault is locked.
 */
interface PresenceShield {
    val events: SharedFlow<PresenceEvent>

    fun start()

    fun stop()

    /** Updates the enrolled embedding after enrollment, or clears it with null. */
    fun updateEnrolled(embedding: FaceEmbedding?)
}
