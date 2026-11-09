package com.keeper.vault.vault.data

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.vault.VaultEntry
import org.junit.Test

class VaultEntryEntityTest {
    @Test
    fun mapsContractEntryToRoomEntityAndBack() {
        val entry = VaultEntry(
            id = "entry",
            displayName = "Note.txt",
            mimeType = "text/plain",
            sizeBytes = 42,
            createdAtMillis = 100,
            modifiedAtMillis = 200,
        )

        assertThat(entry.toEntity().toVaultEntry()).isEqualTo(entry)
    }

    @Test
    fun entityKeepsAllMetadataColumns() {
        val entity = VaultEntryEntity("entry", "Note.txt", "text/plain", 42, 100, 200)

        assertThat(entity.id).isEqualTo("entry")
        assertThat(entity.displayName).isEqualTo("Note.txt")
        assertThat(entity.mimeType).isEqualTo("text/plain")
        assertThat(entity.sizeBytes).isEqualTo(42)
        assertThat(entity.createdAtMillis).isEqualTo(100)
        assertThat(entity.modifiedAtMillis).isEqualTo(200)
    }
}
