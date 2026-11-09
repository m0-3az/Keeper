package com.keeper.vault.isolation

import com.keeper.vault.contracts.isolation.IsolationStatus

/**
 * True when the banner should be shown.
 *
 * A clean signal list is not enough: backups that are not provably disabled and a debuggable build
 * are both posture problems. The absence of a signal is never treated as proof of safety.
 */
fun IsolationStatus.hasWarnings(): Boolean =
    signals.isNotEmpty() || !backupsDisabled || debuggable
