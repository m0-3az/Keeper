package com.keeper.vault.presence.platform

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.keeper.vault.contracts.platform.KeeperException
import com.keeper.vault.contracts.presence.DetectedFace
import com.keeper.vault.contracts.presence.FaceDetector
import com.keeper.vault.contracts.presence.FaceImage
import com.keeper.vault.contracts.presence.FloatPoint
import com.keeper.vault.contracts.presence.IntRect
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bundled ML Kit face detector.
 *
 * Classification is enabled for eye-open probabilities, tracking is enabled for stable ids, and
 * the fast performance mode is used because the blink challenge samples at 10-15 fps. The frame is
 * made upright before [InputImage] creation so bounding boxes are in the same coordinate system as
 * the pixels the embedder crops.
 *
 * Construction is explicit (not a default argument evaluated before the constructor body) so a
 * missing/unloadable ML Kit component is logged and wrapped in [KeeperException.Presence] rather
 * than surfacing as a bare NPE. Detection has a timeout so a wedged native call cannot block the
 * enrollment pipeline forever.
 */
class MLKitFaceDetector(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    detector: com.google.mlkit.vision.face.FaceDetector? = null,
) : FaceDetector {
    private val detector: com.google.mlkit.vision.face.FaceDetector =
        detector ?: createDetector()

    @Volatile
    private var closed: Boolean = false

    override suspend fun detect(image: FaceImage): List<DetectedFace> {
        if (closed) throw KeeperException.Presence("Face detector is closed")
        return withContext(dispatcher) {
            val bitmap = FaceImageBitmap.toUprightBitmap(image)
            try {
                val input = InputImage.fromBitmap(bitmap, 0)
                val faces = Tasks.await(
                    detector.process(input),
                    DETECTION_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                )
                faces.mapNotNull { face ->
                    toDetectedFace(face, image.width, image.height, rotated = image.rotationDegrees)
                }
            } catch (error: OutOfMemoryError) {
                Log.e(TAG, "ML Kit: out of memory while detecting", error)
                throw KeeperException.Presence("ML Kit ran out of memory", error)
            } catch (error: KeeperException) {
                throw error
            } catch (error: Throwable) {
                if (error is VirtualMachineError) throw error
                Log.e(TAG, "ML Kit: detection failed", error)
                throw KeeperException.Presence("ML Kit face detection failed", error)
            } finally {
                bitmap.recycle()
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        Log.i(TAG, "ML Kit: closing detector")
        runCatching { detector.close() }
            .onFailure { Log.e(TAG, "ML Kit: close failed", it) }
    }

    private fun toDetectedFace(
        face: Face,
        width: Int,
        height: Int,
        rotated: Int,
    ): DetectedFace? {
        val box = face.boundingBox
        // When the image was rotated, width/height describe the source; the upright bitmap has
        // swapped dimensions for 90/270 degrees. Clamp against the rotated size.
        val uprightWidth = if (rotated == 90 || rotated == 270) height else width
        val uprightHeight = if (rotated == 90 || rotated == 270) width else height
        val left = box.left.coerceIn(0, uprightWidth)
        val top = box.top.coerceIn(0, uprightHeight)
        val right = box.right.coerceIn(left, uprightWidth)
        val bottom = box.bottom.coerceIn(top, uprightHeight)
        if (right <= left || bottom <= top) return null

        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position?.let {
            FloatPoint(it.x, it.y)
        }
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position?.let {
            FloatPoint(it.x, it.y)
        }
        return DetectedFace(
            bounds = IntRect(left, top, right, bottom),
            leftEye = leftEye,
            rightEye = rightEye,
            leftEyeOpenProbability = face.leftEyeOpenProbability,
            rightEyeOpenProbability = face.rightEyeOpenProbability,
            trackingId = face.trackingId,
        )
    }

    private companion object {
        const val TAG: String = "FaceEnroll"
        const val DETECTION_TIMEOUT_SECONDS: Long = 8L

        fun createDetector(): com.google.mlkit.vision.face.FaceDetector {
            Log.i(TAG, "ML Kit: creating detector with bundled model")
            return try {
                FaceDetection.getClient(
                    FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                        .enableTracking()
                        .setMinFaceSize(0.15f)
                        .build(),
                ).also { Log.i(TAG, "ML Kit: detector created") }
            } catch (error: OutOfMemoryError) {
                Log.e(TAG, "ML Kit: out of memory during initialization", error)
                throw KeeperException.Presence("ML Kit ran out of memory", error)
            } catch (error: Throwable) {
                if (error is VirtualMachineError) throw error
                Log.e(TAG, "ML Kit: initialization failed", error)
                throw KeeperException.Presence("ML Kit face detector could not be created", error)
            }
        }
    }
}
