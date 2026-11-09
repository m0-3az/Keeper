package com.keeper.vault.presence.enrollment

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.presence.FaceEmbedding
import org.junit.Test

class FaceEnrollmentCollectorTest {
    @Test
    fun startsEmpty() {
        val collector = FaceEnrollmentCollector(minFrames = 5, maxFrames = 10)

        assertThat(collector.captured).isEqualTo(0)
        assertThat(collector.progress().complete).isFalse()
        assertThat(collector.average()).isNull()
    }

    @Test
    fun requiresTheMinimumBeforeProducingAnAverage() {
        val collector = FaceEnrollmentCollector(minFrames = 5, maxFrames = 10)

        repeat(4) { collector.add(FaceEmbedding(floatArrayOf(1f, 2f))) }

        assertThat(collector.average()).isNull()
    }

    @Test
    fun averagesCapturedEmbeddingsComponentWise() {
        val collector = FaceEnrollmentCollector(minFrames = 3, maxFrames = 10)

        collector.add(FaceEmbedding(floatArrayOf(1f, 2f)))
        collector.add(FaceEmbedding(floatArrayOf(3f, 4f)))
        collector.add(FaceEmbedding(floatArrayOf(5f, 6f)))

        val average = collector.average()
        assertThat(average).isNotNull()
        assertThat(average!!.values().toList()).containsExactly(3f, 4f).inOrder()
    }

    @Test
    fun becomesCompleteAtTheMaximum() {
        val collector = FaceEnrollmentCollector(minFrames = 2, maxFrames = 3)

        assertThat(collector.add(FaceEmbedding(floatArrayOf(1f))).complete).isFalse()
        assertThat(collector.add(FaceEmbedding(floatArrayOf(1f))).complete).isFalse()
        assertThat(collector.add(FaceEmbedding(floatArrayOf(1f))).complete).isTrue()

        // Extra frames are ignored rather than changing the average silently.
        assertThat(collector.add(FaceEmbedding(floatArrayOf(9f))).complete).isTrue()
        assertThat(collector.average()!!.values().toList()).containsExactly(1f)
    }

    @Test
    fun rejectsMixedEmbeddingDimensions() {
        val collector = FaceEnrollmentCollector(minFrames = 2, maxFrames = 4)
        collector.add(FaceEmbedding(floatArrayOf(1f, 2f)))

        val error = runCatching { collector.add(FaceEmbedding(floatArrayOf(1f))) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun resetClearsTheCapture() {
        val collector = FaceEnrollmentCollector(minFrames = 1, maxFrames = 2)
        collector.add(FaceEmbedding(floatArrayOf(1f)))

        collector.reset()

        assertThat(collector.captured).isEqualTo(0)
        assertThat(collector.average()).isNull()
    }
}
