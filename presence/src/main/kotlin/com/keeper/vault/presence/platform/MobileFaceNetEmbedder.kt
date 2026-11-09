package com.keeper.vault.presence.platform

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.core.graphics.scale
import com.keeper.vault.contracts.platform.KeeperException
import com.keeper.vault.contracts.presence.DetectedFace
import com.keeper.vault.contracts.presence.FaceEmbedder
import com.keeper.vault.contracts.presence.FaceEmbedding
import com.keeper.vault.contracts.presence.FaceImage
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

/**
 * MobileFaceNet embedder backed by LiteRT 2.3.2's [Interpreter].
 *
 * The bundled model expects `1 x 112 x 112 x 3` float input and returns a `1 x 128` embedding.
 * Faces are cropped with a small margin, rotated upright, scaled to 112x112 and normalized as
 * `(channel - 127.5) / 128`, the MobileFaceNet convention.
 *
 * Inference is serialized because [Interpreter] is not thread-safe. [close] is idempotent and
 * releases the native interpreter deterministically. Every native failure (missing/compressed
 * asset, unsupported ABI, corrupt model, low memory) is wrapped in a
 * [KeeperException.Presence] so the enrollment UI can report it instead of crashing.
 */
class MobileFaceNetEmbedder(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val assetName: String = DEFAULT_ASSET,
    private val marginRatio: Float = 0.15f,
) : FaceEmbedder {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val loggedInference = AtomicBoolean(false)
    private var interpreter: Interpreter? = null
    private var closed: Boolean = false

    override suspend fun embed(image: FaceImage, face: DetectedFace): FaceEmbedding =
        withContext(dispatcher) {
            synchronized(lock) {
                if (closed) throw KeeperException.Presence("Face embedder is closed")
                val active = interpreter ?: run {
                    Log.i(TAG, "LiteRT: lazy interpreter load on first frame")
                    createInterpreter().also { interpreter = it }
                }
                runInference(active, image, face)
            }
        }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            Log.i(TAG, "LiteRT: closing interpreter")
            interpreter?.let { active ->
                runCatching { active.close() }
                    .onFailure { Log.e(TAG, "LiteRT: interpreter close failed", it) }
            }
            interpreter = null
        }
    }

    /**
     * Loads and validates the model before the first frame.
     *
     * Enrollment calls this on a background dispatcher so a missing or incompatible asset is
     * reported as a clear message before the camera starts. A failed warm-up releases the native
     * interpreter instead of leaking it.
     */
    fun warmUp() {
        Log.i(TAG, "LiteRT: warm-up start (asset=$assetName)")
        synchronized(lock) {
            if (closed) throw KeeperException.Presence("Face embedder is closed")
            val existing = interpreter
            if (existing != null) {
                validate(existing)
                Log.i(TAG, "LiteRT: warm-up skipped; interpreter already loaded")
                return
            }
            val created = try {
                createInterpreter()
            } catch (error: Throwable) {
                if (error is VirtualMachineError && error !is OutOfMemoryError) throw error
                Log.e(TAG, "LiteRT: interpreter creation failed during warm-up", error)
                throw asPresence(error, "LiteRT model failed to load")
            }
            try {
                validate(created)
                interpreter = created
                Log.i(TAG, "LiteRT: warm-up complete")
            } catch (error: Throwable) {
                Log.e(TAG, "LiteRT: model validation failed during warm-up", error)
                runCatching { created.close() }
                throw asPresence(error, "LiteRT model failed to load")
            }
        }
    }

    private fun validate(active: Interpreter) {
        val input = active.getInputTensor(0)
        val output = active.getOutputTensor(0)
        Log.i(
            TAG,
            "LiteRT: input=${input.shape().joinToString("x")} type=${input.dataType()} " +
                "output=${output.shape().joinToString("x")} type=${output.dataType()}",
        )
        if (!input.shape().contentEquals(INPUT_SHAPE)) {
            throw KeeperException.Presence(
                "Unexpected MobileFaceNet input shape: ${input.shape().joinToString("x")}",
            )
        }
        if (input.dataType() != DataType.FLOAT32) {
            throw KeeperException.Presence("MobileFaceNet input is not FLOAT32")
        }
        if (output.numElements() !in 1..MAX_OUTPUT_VALUES) {
            throw KeeperException.Presence(
                "Unexpected MobileFaceNet output shape: ${output.shape().joinToString("x")}",
            )
        }
    }

    private fun createInterpreter(): Interpreter {
        Log.i(TAG, "LiteRT: loading model asset $assetName")
        val model = try {
            loadModelBuffer()
        } catch (error: OutOfMemoryError) {
            Log.e(TAG, "LiteRT: out of memory while loading the model asset", error)
            throw KeeperException.Presence("LiteRT model asset is too large for this device", error)
        } catch (error: Throwable) {
            if (error is VirtualMachineError) throw error
            Log.e(TAG, "LiteRT: cannot read the model asset", error)
            throw KeeperException.Presence("Cannot load the MobileFaceNet model", error)
        }
        Log.i(TAG, "LiteRT: model buffer ready (${model.capacity()} bytes)")
        return try {
            Interpreter(model, Interpreter.Options().setNumThreads(2)).also {
                Log.i(TAG, "LiteRT: interpreter created")
            }
        } catch (error: OutOfMemoryError) {
            Log.e(TAG, "LiteRT: out of memory while creating the interpreter", error)
            throw KeeperException.Presence("LiteRT interpreter ran out of memory", error)
        } catch (error: Throwable) {
            if (error is VirtualMachineError) throw error
            Log.e(TAG, "LiteRT: interpreter creation failed", error)
            throw KeeperException.Presence("Cannot create the LiteRT interpreter", error)
        }
    }

    private fun loadModelBuffer(): ByteBuffer {
        try {
            appContext.assets.openFd(assetName).use { descriptor ->
                Log.i(
                    TAG,
                    "LiteRT: mapping uncompressed asset (offset=${descriptor.startOffset} " +
                        "length=${descriptor.declaredLength})",
                )
                // The mapping stays valid after the descriptor/stream is closed. Closing the
                // stream also closes its duplicated descriptor instead of leaking an FD.
                return FileInputStream(descriptor.fileDescriptor).use { input ->
                    input.channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        descriptor.startOffset,
                        descriptor.declaredLength,
                    )
                }
            }
        } catch (error: OutOfMemoryError) {
            throw error
        } catch (error: Throwable) {
            Log.w(
                TAG,
                "LiteRT: asset is not directly mappable; reading it into a direct buffer",
                error,
            )
            // Library assets are not covered by the app's noCompress rule on every packaging
            // path; fall back to an in-memory direct buffer when the asset is compressed.
            val bytes = appContext.assets.open(assetName).use { it.readBytes() }
            Log.i(TAG, "LiteRT: asset read into memory (${bytes.size} bytes)")
            if (bytes.size < 8 || bytes[4] != 'T'.code.toByte() || bytes[5] != 'F'.code.toByte() ||
                bytes[6] != 'L'.code.toByte() || bytes[7] != '3'.code.toByte()
            ) {
                throw KeeperException.Presence("MobileFaceNet asset is not a TFLite model")
            }
            val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            buffer.put(bytes)
            buffer.rewind()
            return buffer
        }
    }

    private fun runInference(
        interpreter: Interpreter,
        image: FaceImage,
        face: DetectedFace,
    ): FaceEmbedding {
        val inputShape = interpreter.getInputTensor(0).shape()
        if (!inputShape.contentEquals(INPUT_SHAPE)) {
            throw KeeperException.Presence("Unexpected MobileFaceNet input shape")
        }
        val outputTensor = interpreter.getOutputTensor(0)
        val outputElements = outputTensor.numElements()
        if (outputElements <= 0) {
            throw KeeperException.Presence("Unexpected MobileFaceNet output shape")
        }

        val upright = FaceImageBitmap.toUprightBitmap(image)
        var crop: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            crop = cropWithMargin(upright, face.bounds)
            scaled = if (crop.width == INPUT_SIZE && crop.height == INPUT_SIZE) {
                crop
            } else {
                crop.scale(INPUT_SIZE, INPUT_SIZE)
            }

            val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
            scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            val input = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
            val inputFloats = input.asFloatBuffer()
            for (pixel in pixels) {
                inputFloats.put(((pixel shr 16 and 0xFF) - 127.5f) / 128f)
                inputFloats.put(((pixel shr 8 and 0xFF) - 127.5f) / 128f)
                inputFloats.put(((pixel and 0xFF) - 127.5f) / 128f)
            }
            input.rewind()

            val output = ByteBuffer.allocateDirect(outputElements * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
            interpreter.run(input, output)
            output.rewind()
            val values = FloatArray(outputElements)
            output.asFloatBuffer().get(values)
            if (loggedInference.compareAndSet(false, true)) {
                Log.i(TAG, "LiteRT: first inference completed (${values.size} values)")
            }
            val embedding = FaceEmbedding(values)
            values.fill(0f)
            output.rewind()
            output.asFloatBuffer().put(FloatArray(outputElements))
            return embedding
        } catch (error: OutOfMemoryError) {
            Log.e(TAG, "LiteRT: out of memory during inference", error)
            throw KeeperException.Presence("MobileFaceNet inference ran out of memory", error)
        } catch (error: KeeperException) {
            throw error
        } catch (error: Throwable) {
            if (error is VirtualMachineError) throw error
            Log.e(TAG, "LiteRT: inference failed", error)
            throw KeeperException.Presence("MobileFaceNet inference failed", error)
        } finally {
            if (scaled != null && scaled !== crop) scaled.recycle()
            if (crop != null && crop !== upright) crop.recycle()
            upright.recycle()
        }
    }

    private fun cropWithMargin(
        bitmap: Bitmap,
        bounds: com.keeper.vault.contracts.presence.IntRect,
    ): Bitmap {
        val marginX = (bounds.width * marginRatio).toInt()
        val marginY = (bounds.height * marginRatio).toInt()
        val left = (bounds.left - marginX).coerceIn(0, bitmap.width - 1)
        val top = (bounds.top - marginY).coerceIn(0, bitmap.height - 1)
        val right = (bounds.right + marginX).coerceIn(left + 1, bitmap.width)
        val bottom = (bounds.bottom + marginY).coerceIn(top + 1, bitmap.height)
        if (right - left < 8 || bottom - top < 8) {
            throw KeeperException.Presence("Detected face is too small to embed")
        }
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    private fun asPresence(error: Throwable, message: String): KeeperException.Presence =
        error as? KeeperException.Presence
            ?: KeeperException.Presence("$message (${error.javaClass.simpleName})", error)

    companion object {
        private const val TAG: String = "FaceEnroll"
        const val DEFAULT_ASSET: String = "mobilefacenet.tflite"
        const val INPUT_SIZE: Int = 112
        private const val MAX_OUTPUT_VALUES: Int = 4_096
        private val INPUT_SHAPE = intArrayOf(1, INPUT_SIZE, INPUT_SIZE, 3)
    }
}
