package com.keeper.vault.contracts.isolation

import kotlinx.coroutines.flow.Flow

/**
 * Signals that the device may not be trustworthy. None of these checks is conclusive; all can
 * produce false positives and all can be bypassed by a determined attacker.
 */
enum class TamperKind {
    ROOT,
    DEBUGGER,
    ACCESSIBILITY_SERVICE,
    EMULATOR,
    HOOK_FRAMEWORK,
    UNKNOWN,
}

/** One detected signal. [detail] must not contain secrets or user content. */
data class TamperSignal(
    val kind: TamperKind,
    val detectedAtMillis: Long,
    val detail: String? = null,
)

/**
 * Aggregated isolation state for the warning banner.
 *
 * @property backupsDisabled result of the backup policy check (manifest + runtime).
 * @property debuggable true when the running build is debuggable.
 */
data class IsolationStatus(
    val signals: List<TamperSignal>,
    val backupsDisabled: Boolean,
    val debuggable: Boolean,
)

/**
 * Detects root, debugger, accessibility, emulator, and hooking signals.
 *
 * The UI must warn, not block. A hard block would punish legitimate power users and would not
 * stop a real attacker.
 */
interface TamperDetector {
    suspend fun scan(): List<TamperSignal>

    fun observe(): Flow<IsolationStatus>
}

/**
 * Append-only tamper log. Duress events are written here (Phase 6 depends on Phase 5).
 *
 * The log is metadata, not vault content. It must not record secrets or decrypted content.
 */
interface TamperLog {
    suspend fun append(signal: TamperSignal)

    fun observe(): Flow<List<TamperSignal>>

    suspend fun clear()
}
