package com.keeper.vault.isolation

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.isolation.IsolationStatus
import com.keeper.vault.contracts.isolation.TamperKind
import com.keeper.vault.contracts.isolation.TamperSignal
import org.junit.Test

class IsolationStatusTest {

    @Test
    fun `a clean hardened status has no warnings`() {
        val status = IsolationStatus(
            signals = emptyList(),
            backupsDisabled = true,
            debuggable = false,
        )

        assertThat(status.hasWarnings()).isFalse()
    }

    @Test
    fun `any signal raises a warning`() {
        val status = IsolationStatus(
            signals = listOf(TamperSignal(TamperKind.ROOT, 1, "su")),
            backupsDisabled = true,
            debuggable = false,
        )

        assertThat(status.hasWarnings()).isTrue()
    }

    @Test
    fun `enabled backups raise a warning`() {
        val status = IsolationStatus(
            signals = emptyList(),
            backupsDisabled = false,
            debuggable = false,
        )

        assertThat(status.hasWarnings()).isTrue()
    }

    @Test
    fun `a debuggable build raises a warning`() {
        val status = IsolationStatus(
            signals = emptyList(),
            backupsDisabled = true,
            debuggable = true,
        )

        assertThat(status.hasWarnings()).isTrue()
    }
}
