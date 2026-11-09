package com.keeper.vault.isolation

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.isolation.IsolationStatus
import com.keeper.vault.contracts.isolation.TamperKind
import com.keeper.vault.contracts.isolation.TamperSignal
import com.keeper.vault.contracts.platform.BuildInfo
import com.keeper.vault.contracts.platform.Clock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultTamperDetectorTest {

    @Test
    fun `scan returns a signal for every tamper kind`() = runTest {
        val probe = FakeProbe(
            id = "all",
            findings = {
                TamperKind.entries.map { kind ->
                    ProbeFinding(kind = kind, detail = "detail-${kind.name}")
                }
            },
        )
        val detector = detector(probes = listOf(probe))

        val signals = detector.scan()

        assertThat(signals.map { it.kind })
            .containsExactlyElementsIn(TamperKind.entries)
            .inOrder()
        assertThat(signals.map { it.detail })
            .containsExactlyElementsIn(TamperKind.entries.map { "detail-${it.name}" })
            .inOrder()
        assertThat(signals.map { it.detectedAtMillis }).containsExactly(1_000L, 1_000L, 1_000L, 1_000L, 1_000L, 1_000L)
    }

    @Test
    fun `scan deduplicates identical findings but keeps distinct details`() = runTest {
        val probe = FakeProbe(
            id = "root",
            findings = {
                listOf(
                    ProbeFinding(TamperKind.ROOT, "su"),
                    ProbeFinding(TamperKind.ROOT, "su"),
                    ProbeFinding(TamperKind.ROOT, "magisk"),
                    ProbeFinding(TamperKind.ROOT, "su"),
                )
            },
        )

        val signals = detector(probes = listOf(probe)).scan()

        assertThat(signals).hasSize(2)
        assertThat(signals.map { it.detail }).containsExactly("su", "magisk").inOrder()
    }

    @Test
    fun `scan normalizes blank details to null`() = runTest {
        val probe = FakeProbe(
            id = "blank",
            findings = { listOf(ProbeFinding(TamperKind.UNKNOWN, "   ")) },
        )

        val signals = detector(probes = listOf(probe)).scan()

        assertThat(signals).hasSize(1)
        assertThat(signals.single().detail).isNull()
    }

    @Test
    fun `a failing probe produces an unknown warning instead of silence`() = runTest {
        val probe = FakeProbe(
            id = "root",
            failure = IllegalStateException("sensitive internal message"),
        )

        val signals = detector(probes = listOf(probe)).scan()

        assertThat(signals).hasSize(1)
        assertThat(signals.single().kind).isEqualTo(TamperKind.UNKNOWN)
        assertThat(signals.single().detail).isEqualTo("probe failed: root")
        assertThat(signals.single().detail).doesNotContain("sensitive")
    }

    @Test
    fun `scan aggregates findings from multiple probes`() = runTest {
        val root = FakeProbe("root") { listOf(ProbeFinding(TamperKind.ROOT, "su")) }
        val debugger = FakeProbe("debugger") { listOf(ProbeFinding(TamperKind.DEBUGGER, "attached")) }

        val signals = detector(probes = listOf(root, debugger)).scan()

        assertThat(signals.map { it.kind }).containsExactly(TamperKind.ROOT, TamperKind.DEBUGGER).inOrder()
    }

    @Test
    fun `status carries signals backup posture and debuggable flag`() = runTest {
        val probe = FakeProbe("root") { listOf(ProbeFinding(TamperKind.ROOT, "su")) }
        val detector = detector(
            probes = listOf(probe),
            backupsDisabled = false,
            debuggable = true,
        )

        val status = detector.observe().first()

        assertThat(status.signals.map { it.kind }).containsExactly(TamperKind.ROOT)
        assertThat(status.backupsDisabled).isFalse()
        assertThat(status.debuggable).isTrue()
    }

    @Test
    fun `status reports backups disabled when the policy probe says so`() = runTest {
        val status = detector(backupsDisabled = true).observe().first()

        assertThat(status.backupsDisabled).isTrue()
        assertThat(status.debuggable).isFalse()
        assertThat(status.signals).isEmpty()
    }

    @Test
    fun `observe rescans on its polling interval`() = runTest {
        var findings = emptyList<ProbeFinding>()
        val probe = FakeProbe("root") { findings }
        val detector = detector(probes = listOf(probe), pollIntervalMillis = 1_000)
        val emissions = mutableListOf<IsolationStatus>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            detector.observe().take(2).toList(emissions)
        }
        runCurrent()
        assertThat(emissions).hasSize(1)
        assertThat(emissions.first().signals).isEmpty()

        findings = listOf(ProbeFinding(TamperKind.ROOT, "su"))
        advanceTimeBy(1_000)
        runCurrent()

        assertThat(emissions).hasSize(2)
        assertThat(emissions.last().signals.map { it.kind }).containsExactly(TamperKind.ROOT)
        job.cancel()
    }

    @Test
    fun `scan does not report a signal for a clean device`() = runTest {
        assertThat(detector().scan()).isEmpty()
    }

    private fun kotlinx.coroutines.test.TestScope.detector(
        probes: List<TamperProbe> = emptyList(),
        backupsDisabled: Boolean = true,
        debuggable: Boolean = false,
        pollIntervalMillis: Long = 30_000,
    ): DefaultTamperDetector = DefaultTamperDetector(
        probes = probes,
        backupPolicy = BackupPolicyProbe { backupsDisabled },
        buildInfo = object : BuildInfo {
            override val debuggable: Boolean = debuggable
            override val versionName: String = "1.0-test"
        },
        clock = Clock { 1_000L },
        pollIntervalMillis = pollIntervalMillis,
    )

    private class FakeProbe(
        override val id: String,
        private val failure: Exception? = null,
        private val findings: () -> List<ProbeFinding> = { emptyList() },
    ) : TamperProbe {
        override suspend fun probe(): List<ProbeFinding> {
            failure?.let { throw it }
            return findings()
        }
    }
}
