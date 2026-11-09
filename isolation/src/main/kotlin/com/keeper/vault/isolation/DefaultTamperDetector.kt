package com.keeper.vault.isolation

import com.keeper.vault.contracts.isolation.IsolationStatus
import com.keeper.vault.contracts.isolation.TamperDetector
import com.keeper.vault.contracts.isolation.TamperKind
import com.keeper.vault.contracts.isolation.TamperSignal
import com.keeper.vault.contracts.platform.BuildInfo
import com.keeper.vault.contracts.platform.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/**
 * Aggregates injectable [TamperProbe]s into warnings.
 *
 * Design choices:
 * - A probe failure becomes an [TamperKind.UNKNOWN] warning. Failing open silently would be the
 *   one outcome the isolation layer must not produce.
 * - Exact duplicates (same kind and detail) are collapsed; distinct details remain so the log and
 *   banner can show what was found.
 * - Blank details are normalized to null.
 * - [observe] re-scans on a fixed interval so a debugger attached after launch is noticed.
 *
 * None of this blocks the user. A determined attacker on a compromised OS can defeat every check.
 */
class DefaultTamperDetector(
    private val probes: List<TamperProbe>,
    private val backupPolicy: BackupPolicyProbe,
    private val buildInfo: BuildInfo,
    private val clock: Clock,
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
) : TamperDetector {

    init {
        require(pollIntervalMillis > 0) { "pollIntervalMillis must be positive" }
    }

    override suspend fun scan(): List<TamperSignal> {
        val detectedAtMillis = clock.nowMillis()
        val unique = LinkedHashMap<SignalKey, TamperSignal>()

        for (probe in probes) {
            val findings = try {
                probe.probe()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                listOf(ProbeFinding(TamperKind.UNKNOWN, "probe failed: ${probe.id}"))
            }

            for (finding in findings) {
                val detail = finding.detail?.trim()?.takeIf { it.isNotEmpty() }
                val key = SignalKey(finding.kind, detail)
                unique.putIfAbsent(
                    key,
                    TamperSignal(
                        kind = finding.kind,
                        detectedAtMillis = detectedAtMillis,
                        detail = detail,
                    ),
                )
            }
        }

        return unique.values.toList()
    }

    override fun observe(): Flow<IsolationStatus> = flow {
        while (currentCoroutineContext().isActive) {
            emit(status())
            delay(pollIntervalMillis)
        }
    }

    private suspend fun status(): IsolationStatus = IsolationStatus(
        signals = scan(),
        backupsDisabled = try {
            backupPolicy.backupsDisabled()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Treat an unreadable backup posture as "not confirmed disabled" so the banner warns.
            false
        },
        debuggable = buildInfo.debuggable,
    )

    private data class SignalKey(
        val kind: TamperKind,
        val detail: String?,
    )

    companion object {
        const val DEFAULT_POLL_INTERVAL_MILLIS: Long = 30_000L
    }
}
