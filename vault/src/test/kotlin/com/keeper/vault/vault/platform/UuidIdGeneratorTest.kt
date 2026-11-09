package com.keeper.vault.vault.platform

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UuidIdGeneratorTest {
    @Test
    fun idsAreUniqueAndFilesystemSafe() {
        val generator = UuidIdGenerator()

        val ids = List(100) { generator.newId() }

        assertThat(ids.toSet()).hasSize(100)
        ids.forEach { id ->
            assertThat(id).matches("[A-Za-z0-9-]+")
            assertThat(id).doesNotContain("..")
            assertThat(id).doesNotContain("/")
        }
    }
}
