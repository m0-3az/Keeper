package com.keeper.vault.presence.shield

import com.keeper.vault.contracts.presence.DetectedFace
import com.keeper.vault.contracts.presence.FaceEmbedding
import com.keeper.vault.contracts.presence.FaceEmbedder
import com.keeper.vault.contracts.presence.FaceImage
import com.keeper.vault.contracts.presence.FaceImageFormat
import com.keeper.vault.contracts.presence.FaceDetector
import com.keeper.vault.contracts.presence.IntRect

internal object PresenceTestFixtures {
    fun image(): FaceImage = FaceImage(
        width = 112,
        height = 112,
        rotationDegrees = 0,
        format = FaceImageFormat.RGBA_8888,
        data = ByteArray(112 * 112 * 4),
    )

    fun face(
        leftOpen: Float? = 0.9f,
        rightOpen: Float? = 0.9f,
        trackingId: Int = 1,
    ): DetectedFace = DetectedFace(
        bounds = IntRect(20, 20, 92, 92),
        leftEye = null,
        rightEye = null,
        leftEyeOpenProbability = leftOpen,
        rightEyeOpenProbability = rightOpen,
        trackingId = trackingId,
    )

    fun embedding(vararg values: Float): FaceEmbedding = FaceEmbedding(values)

    fun ownerEmbedding(): FaceEmbedding = FaceEmbedding(floatArrayOf(0.1f, 0.2f, 0.3f))
}

internal class FakeFaceDetector(
    private val frames: MutableList<List<DetectedFace>> = mutableListOf(),
    private val default: List<DetectedFace> = emptyList(),
) : FaceDetector {
    var calls: Int = 0
        private set

    fun enqueue(faces: List<DetectedFace>): FakeFaceDetector {
        frames += faces
        return this
    }

    override suspend fun detect(image: FaceImage): List<DetectedFace> {
        calls += 1
        return if (frames.isEmpty()) default else frames.removeAt(0)
    }

    override fun close() = Unit
}

internal class FakeFaceEmbedder(
    private val embeddings: MutableList<FaceEmbedding> = mutableListOf(),
    private val default: FaceEmbedding = PresenceTestFixtures.ownerEmbedding(),
) : FaceEmbedder {
    var calls: Int = 0
        private set

    fun enqueue(embedding: FaceEmbedding): FakeFaceEmbedder {
        embeddings += embedding
        return this
    }

    override suspend fun embed(image: FaceImage, face: DetectedFace): FaceEmbedding {
        calls += 1
        return if (embeddings.isEmpty()) default else embeddings.removeAt(0)
    }

    override fun close() = Unit
}

internal class FakeFrameSource(
    private val frames: List<PresenceFrame>,
    private val now: () -> Long,
) : PresenceFrameSource {
    val requests: MutableList<Long> = mutableListOf()
    private var index = 0

    override suspend fun nextFrame(): PresenceFrame? {
        requests += now()
        return if (index < frames.size) frames[index++] else null
    }

    override fun close() = Unit
}
