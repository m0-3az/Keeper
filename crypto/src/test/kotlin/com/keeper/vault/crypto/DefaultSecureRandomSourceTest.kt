package com.keeper.vault.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class DefaultSecureRandomSourceTest {
    private val subject = DefaultSecureRandomSource()

    @Test
    fun `returns the requested number of bytes`() {
        assertThat(subject.nextBytes(32)).hasLength(32)
    }

    @Test
    fun `does not return the same bytes twice`() {
        val first = subject.nextBytes(32)
        val second = subject.nextBytes(32)

        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `rejects a negative size`() {
        assertThrows(IllegalArgumentException::class.java) {
            subject.nextBytes(-1)
        }
    }
}
