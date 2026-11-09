package com.keeper.vault.auth

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SignatureCanonicalizerTest {
    private val drawing = listOf(
        SignaturePoint(0f, 0f),
        SignaturePoint(1f, 2f),
        SignaturePoint(2f, 4f),
        SignaturePoint(4f, 5f),
        SignaturePoint(6f, 4f),
        SignaturePoint(8f, 2f),
        SignaturePoint(9f, 0f),
        SignaturePoint(7f, -2f),
        SignaturePoint(5f, -3f),
        SignaturePoint(3f, -2f),
        SignaturePoint(1f, -1f),
        SignaturePoint(0f, 0f),
    )

    @Test
    fun `produces a versioned character sequence`() {
        val canonical = SignatureCanonicalizer.canonicalize(drawing)

        assertThat(canonical.size).isAtLeast(SignatureCanonicalizer.MIN_CANONICAL_LENGTH)
        assertThat(canonical[0]).isEqualTo('S')
        assertThat(canonical[1]).isEqualTo('1')
    }

    @Test
    fun `is deterministic`() {
        assertThat(SignatureCanonicalizer.canonicalize(drawing))
            .isEqualTo(SignatureCanonicalizer.canonicalize(drawing))
    }

    @Test
    fun `normalizes translation and scale`() {
        val moved = drawing.map { SignaturePoint(it.x * 3f + 120f, it.y * 3f - 45f) }

        assertThat(SignatureCanonicalizer.canonicalize(moved))
            .isEqualTo(SignatureCanonicalizer.canonicalize(drawing))
    }

    @Test
    fun `different strokes produce different secrets`() {
        val reversed = drawing.reversed()

        assertThat(SignatureCanonicalizer.canonicalize(reversed))
            .isNotEqualTo(SignatureCanonicalizer.canonicalize(drawing))
    }

    @Test
    fun `too few points are rejected`() {
        assertThat(SignatureCanonicalizer.canonicalize(drawing.take(4))).isEmpty()
    }

    @Test
    fun `non finite points are ignored`() {
        val noisy = drawing.mapIndexed { index, point ->
            if (index % 3 == 0) SignaturePoint(Float.NaN, point.y) else point
        }

        assertThat(SignatureCanonicalizer.canonicalize(noisy).size)
            .isAtLeast(SignatureCanonicalizer.MIN_CANONICAL_LENGTH)
    }

    @Test
    fun `a stroke break is encoded`() {
        val withBreak = drawing.mapIndexed { index, point ->
            if (index == 6) point.copy(strokeStart = true) else point
        }

        assertThat(SignatureCanonicalizer.canonicalize(withBreak).any { it == '|' }).isTrue()
    }

    @Test
    fun `duplicate consecutive points are collapsed`() {
        val duplicated = drawing.flatMap { listOf(it, it, it) }

        assertThat(SignatureCanonicalizer.canonicalize(duplicated))
            .isEqualTo(SignatureCanonicalizer.canonicalize(drawing))
    }

    @Test
    fun `empty input is rejected`() {
        assertThat(SignatureCanonicalizer.canonicalize(emptyList())).isEmpty()
    }

    @Test
    fun `a motionless drawing is rejected`() {
        val still = List(20) { SignaturePoint(4f, 4f) }

        assertThat(SignatureCanonicalizer.canonicalize(still)).isEmpty()
    }

    @Test
    fun `long drawings are bounded to the maximum point count`() {
        val many = List(400) { index ->
            SignaturePoint(
                x = kotlin.math.cos(index / 20f) * 100f,
                y = kotlin.math.sin(index / 20f) * 100f,
            )
        }

        val canonical = SignatureCanonicalizer.canonicalize(many)

        assertThat(canonical.size)
            .isAtMost(2 + SignatureCanonicalizer.MAX_POINTS * 2)
    }
}
