package com.keeper.vault.isolation

import com.keeper.vault.contracts.isolation.TamperKind

/**
 * One observation from a [TamperProbe]. A probe can report several findings because a single
 * category often has more than one indicator (for example several root paths).
 *
 * [detail] is metadata only: a path, package name, build field, or service id. It must never
 * contain user content or secret material.
 */
data class ProbeFinding(
    val kind: TamperKind,
    val detail: String? = null,
)

/**
 * An injectable check behind the tamper detector.
 *
 * Probes are deliberately best-effort. Every publicly documented root/hook check can be bypassed
 * by a determined attacker, and several checks can produce false positives on legitimate devices.
 * The UI warns; it never blocks.
 */
interface TamperProbe {
    /** Stable identifier used in error warnings. It must not contain user data. */
    val id: String

    suspend fun probe(): List<ProbeFinding>
}

/**
 * Runtime backup-posture check.
 *
 * `allowBackup` is a manifest declaration, but the merged manifest is what runs. Reading the
 * effective application flag catches a manifest-merger mistake instead of trusting the source.
 */
fun interface BackupPolicyProbe {
    fun backupsDisabled(): Boolean
}
