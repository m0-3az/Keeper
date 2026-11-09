package com.keeper.vault.auth

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PatternCanonicalizerTest {
    @Test
    fun `canonicalizes visited nodes in order`() {
        val canonical = PatternCanonicalizer.canonicalize(intArrayOf(0, 1, 2, 5, 8))

        assertThat(canonical).isEqualTo("01258".toCharArray())
    }

    @Test
    fun `rejects too few nodes`() {
        assertThat(PatternCanonicalizer.canonicalize(intArrayOf(0, 1, 2))).isEmpty()
    }

    @Test
    fun `rejects repeated nodes`() {
        assertThat(PatternCanonicalizer.canonicalize(intArrayOf(0, 1, 0, 2))).isEmpty()
    }

    @Test
    fun `rejects out of range nodes`() {
        assertThat(PatternCanonicalizer.canonicalize(intArrayOf(0, 1, 2, 9))).isEmpty()
        assertThat(PatternCanonicalizer.canonicalize(intArrayOf(-1, 1, 2, 3))).isEmpty()
    }

    @Test
    fun `a full pattern is accepted`() {
        val full = IntArray(9) { it }

        assertThat(PatternCanonicalizer.canonicalize(full)).isEqualTo("012345678".toCharArray())
    }
}
