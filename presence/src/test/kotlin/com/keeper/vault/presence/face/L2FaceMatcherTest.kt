package com.keeper.vault.presence.face

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.presence.FaceEmbedding
import org.junit.Test

class L2FaceMatcherTest {
    private val matcher = L2FaceMatcher()

    @Test
    fun identicalEmbeddingsHaveZeroDistance() {
        val embedding = FaceEmbedding(floatArrayOf(0.25f, -0.5f, 0.75f))

        assertThat(matcher.distance(embedding, embedding)).isEqualTo(0f)
    }

    @Test
    fun distanceIsEuclidean() {
        val a = FaceEmbedding(floatArrayOf(0f, 0f, 0f))
        val b = FaceEmbedding(floatArrayOf(3f, 4f, 0f))

        assertThat(matcher.distance(a, b)).isWithin(1e-5f).of(5f)
    }

    @Test
    fun thresholdIsInclusive() {
        val enrolled = FaceEmbedding(floatArrayOf(0f, 0f))
        val atThreshold = FaceEmbedding(floatArrayOf(3f, 4f))

        assertThat(matcher.isOwner(atThreshold, enrolled, 5f)).isTrue()
        assertThat(matcher.isOwner(atThreshold, enrolled, 4.999f)).isFalse()
    }

    @Test
    fun wrongDimensionThrows() {
        val a = FaceEmbedding(floatArrayOf(1f, 2f))
        val b = FaceEmbedding(floatArrayOf(1f, 2f, 3f))

        runCatching { matcher.distance(a, b) }
            .onSuccess { error("Expected a dimension mismatch") }
            .onFailure { assertThat(it).isInstanceOf(IllegalArgumentException::class.java) }
    }
}
