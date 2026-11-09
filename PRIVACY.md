# Keeper — Privacy

Keeper is a local-first Android vault. It does not collect, transmit, or sell
data. This document describes what stays on the device and what the user is
responsible for.

## Network

- Keeper declares **no `INTERNET` permission**. The merged manifest is checked
  with `aapt` in the commit gate.
- There is no telemetry, analytics, crash reporting, account system, or cloud
  sync.
- Bundled ML Kit transitively contains Google datatransport code. Keeper removes
  the `INTERNET` permission and the three telemetry entry points at manifest
  merge, so those classes have no entry point and no network capability. This
  must be re-verified after dependency updates.
- No APK or source is published publicly; the repository is private pending
  legal review.

## Local storage

| Data | Where | Protection |
|---|---|---|
| Vault file content | App-private files, AES-256-GCM `KPR1` containers | Encrypted with the vault key |
| Vault metadata (names, MIME, sizes, timestamps) | Room | **Not encrypted**; visible to anyone who can read app-private storage |
| Face templates | App-private presence store | Encrypted with the vault key |
| App settings and theme | `SharedPreferences` | Plain preferences; no secrets |
| Terms acceptance | `keeper_terms` preferences | Version integer only |
| Intruder activation consent | `keeper_intruder_consent` preferences | Version integer only |
| Intruder images | App-private storage, `KPI1` AES-256-GCM | Dedicated Keystore-wrapped `IntruderKey` |
| Intruder metadata | Room | **Not encrypted** with the vault key |

There is no backup to a cloud service. `allowBackup=false` and the data
extraction rules are set in the manifest. Clearing app data or uninstalling the
app removes the local data, subject to the operating system's storage behavior.

## Face data

- Face embeddings are biometric data. They are stored locally and are not
  revocable: a leaked embedding cannot be reissued like a password.
- Face matching and blink liveness are deterrents, not authentication. Photos,
  screens, and video replay may fool them.
- The camera is active only while a feature that needs it is running; the
  Android camera indicator applies. Keeper does not record continuously.

## Intruder capture

- Intruder capture is **off by default**. Enabling it requires a separate,
  versioned activation consent and the camera permission.
- **Every capture requires a visible warning and an explicit on-screen confirmation.** There is no
  first-time bypass and no automatic capture. Cancelling starts no camera and consumes no rate
  limit. Face-mismatch capture is disabled in this release because it has no visible confirmation
  flow.
- Images stay in the app's private storage and are never uploaded.
- Retention is 7, 30, 90 days, or forever; the default is 30 days. A daily
  WorkManager job purges expired captures.
- The user is solely responsible for lawful use, notice, consent, retention, and
  deletion. Legal review is pending; public APK distribution is blocked until a
  named reviewer signs off. See `LIMITATIONS.md` §8.

## Network check

- The Network check screen is read-only. It reads Wi-Fi, mobile data, VPN (best effort), and
  airplane-mode state using the existing `ACCESS_NETWORK_STATE` permission.
- It opens public system settings screens for Wi-Fi, Bluetooth, NFC, mobile/wireless, hotspot,
  VPN, and airplane mode. It cannot toggle or restore any radio, and it makes no such claim.
- Bluetooth, NFC, and hotspot states are shown as “Open settings to check” rather than requesting
  sensitive permissions just for display.
- Keeper still has no `INTERNET` permission and uploads nothing.

## Terms and consent

- First run is blocked by the Terms & Acknowledgement screen. Acceptance is
  stored as `accepted_version` in `keeper_terms`; a version bump re-prompts.
- Intruder activation consent is stored as `accepted_version` in
  `keeper_intruder_consent`; a version bump re-prompts.
- Declining either consent leaves the respective feature unusable; declining
  the terms exits the app.

## Status

This is a **preview**. The source is available for review, but the app is not
cleared for public APK distribution. It is not a substitute for full-device
encryption, a locked bootloader, or a trustworthy operating system. The legal
review of intruder capture is pending.
