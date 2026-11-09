package com.keeper.vault.presence.enrollment

import com.keeper.vault.contracts.presence.FaceEmbedding

/** Progress shown while the owner enrollment screen captures frames. */
data class EnrollmentProgress(
    val captured: Int,
    val requiredMinimum: Int,
    val maximum: Int,
    val complete: Boolean,
)

/**
 * Captures 5-10 embeddings and averages them component-wise.
 *
 * Only the averaged template is persisted. The individual frames stay in memory and are dropped
 * when the collector resets or goes out of scope.
 */
class FaceEnrollmentCollector(
    private val minFrames: Int = 5,
    private val maxFrames: Int = 10,
) {
    init {
        require(minFrames >= 1) { "minFrames must be positive" }
        require(maxFrames >= minFrames) { "maxFrames must be >= minFrames" }
        require(maxFrames <= 100) { "maxFrames is unreasonably large" }
    }

    private val embeddings = mutableListOf<FaceEmbedding>()

    val captured: Int get() = embeddings.size

    fun progress(): EnrollmentProgress = EnrollmentProgress(
        captured = embeddings.size,
        requiredMinimum = minFrames,
        maximum = maxFrames,
        complete = embeddings.size >= maxFrames,
    )

    /** Adds one embedding. Frames beyond [maxFrames] are ignored. */
    fun add(embedding: FaceEmbedding): EnrollmentProgress {
        if (embeddings.size >= maxFrames) return progress()
        val first = embeddings.firstOrNull()
        require(first == null || first.size == embedding.size) {
            "Embedding dimensions must match"
        }
        embeddings += embedding
        return progress()
    }

    /**
     * Average template once [minFrames] frames exist, else null.
     *
     * Temporary arrays are zeroed; [FaceEmbedding] holds its own defensive copy.
     */
    fun average(): FaceEmbedding? {
        if (embeddings.size < minFrames) return null
        val size = embeddings.first().size
        val sums = DoubleArray(size)
        try {
            for (embedding in embeddings) {
                val values = embedding.values()
                try {
                    for (index in 0 until size) {
                        sums[index] += values[index]
                    }
                } finally {
                    values.fill(0f)
                }
            }
            val average = FloatArray(size) { index ->
                (sums[index] / embeddings.size).toFloat()
            }
            return try {
                FaceEmbedding(average)
            } finally {
                average.fill(0f)
            }
        } finally {
            sums.fill(0.0)
        }
    }

    fun reset() {
        embeddings.clear()
    }
}
