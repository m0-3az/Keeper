# Keeper — Known Limitations

This file is part of the product, not an appendix. If a limitation is not here, it is not claimed.

## 1. Face matching and blink liveness are deterrents, not guarantees

- MobileFaceNet embeddings can be spoofed by a photograph, a screen, or a video replay.
- Blink detection is weak against video replay and can miss real blinks at low sampling rates. It raises the cost of a casual attack; it is not cryptographic proof of presence.
- ML-based anti-spoofing is a possible later upgrade, not part of v1.
- Face matching never decrypts anything. It only controls lock behavior. The vault key always comes from the user's secret (or the OS biometric path).

## 2. Memory zeroing is best effort

- JVM `CharArray`/`ByteArray` buffers can be copied by the GC, the JIT, decoders, and Compose.
- `Bitmap` pixel buffers and decoder buffers cannot be reliably wiped.
- Decrypted content is kept in RAM only, but RAM can be captured on a rooted or compromised device while the vault is open.
- `FLAG_SECURE` and tamper warnings reduce screen/recents exposure; they do not remove it.

## 3. Rooted or compromised devices

- A rooted device can capture the screen buffer, keystrokes, and decrypted RAM while the vault is open.
- A root-capable attacker can also invoke the app's Keystore operations while the app is running.
- The secret-derived KEK means stolen app files alone are not enough, but an online attacker who can query the app still faces only the password and the attempt limiter.
- Root/package probes check known `su` paths and a fixed list of root-manager package names
  declared in the manifest `<queries>`; a renamed or unknown root manager can evade them.
- No app-level check can make a compromised OS trustworthy.

## 4. Short PINs are weak

- A 4–6 digit PIN has a small search space.
- If an attacker can query the Keystore online, the 600,000-iteration PBKDF2 only slows each guess; it does not make a short PIN strong.
- Use a long password for high-risk use. The UI must say this plainly.

## 5. PBKDF2 cost varies by device

- 600,000 iterations is the floor, not a universal constant.
- Low-end devices may take noticeably long to unlock. Benchmark on the target device; raise the count if the device is fast, never go below 600,000.
- A future migration must store the iteration count and support re-wrapping.

## 6. Work Profile isolation is user-dependent

- The app can recommend a Work Profile (Island/Shelter), but it cannot configure or guarantee it.
- If the user does not set it up correctly, the isolation benefit does not exist.
- Island/Shelter behavior is best effort and must be tested on the actual device.

## 7. Import is a move; deletion of the original is best effort

- Importing through the Storage Access Framework copies the file into the encrypted store and then
  asks the provider to delete the source document. On success, the file has moved into the vault.
- The source is deleted only after the encrypted copy has been committed. If the copy fails, the
  original document is deliberately left untouched.
- A provider may refuse deletion (cloud drives, read-only media, a revoked write grant). Keeper then
  shows a dialog naming the file and asks the user to delete the original manually. Keeper cannot
  erase a file it does not control, and it does not report success when deletion failed.
- Deletion is not a secure erase: the provider or filesystem may retain blocks, backups, or
  thumbnails. Only the vault copy is covered by AES-256-GCM.
- If the device has no DocumentsUI/SAF picker, import is unavailable and Keeper says so; the rest of
  the vault still works.

## 8. Intruder log limitations

- Intruder capture is never automatic. Every capture requires a visible warning and an explicit
  on-screen “Take photo” confirmation; cancelling starts no camera and consumes no rate limit.
- Face-mismatch capture is disabled in this release because a background mismatch has no visible
  confirmation flow. Stored `captureOnFaceMismatch` values migrate to false.
- Capture depends on the camera being uncovered, available, and permitted.
- A covered lens, a mask, a device without a front camera, or a camera held by the OS defeats it.
- Android 12+ shows a camera privacy indicator. Android 14+ camera foreground-service rules apply.
- Capture can fail when the device is locked, when the app is background-restricted, or when the camera is in use by another app. Failure must be logged, not hidden.
- Intruder images use a dedicated Keystore-wrapped `IntruderKey`, not the user's secret. A compromised app process can decrypt them.
- Intruder metadata (timestamp, trigger, opaque filename) is stored in Room. It is not encrypted with the vault key.
- Legal exposure varies by region. The disclosure must include: *"You are responsible for complying with local privacy laws."* Legal review is pending. Public APK distribution is blocked until a named reviewer signs off on the disclosure, retention, and jurisdiction analysis.
- This is not covert surveillance. The privacy indicator is expected, and the feature is opt-in and disclosed.

## 9. Vault metadata is not encrypted

- Room stores display name, MIME type, size, timestamps, and an opaque file identifier.
- The file content is AES-256-GCM ciphertext, but the existence and names of entries are visible to anyone who can read the app's private storage.
- If filenames are sensitive, use neutral display names. Full metadata encryption is out of scope for v1.

## 10. No INTERNET permission is necessary but not sufficient

- The app declares no `INTERNET` permission, and the merged manifest is checked with `aapt` on every commit.
- That stops the app's own network stack. It does not stop a compromised OS, a malicious accessibility service, or another app from exfiltrating data.
- Bundled ML Kit and on-device TFLite/LiteRT are used specifically to avoid a network model dependency.

### 10a. Bundled ML Kit pulls telemetry code

- `com.google.mlkit:face-detection` transitively brings Google datatransport and Firebase
  encoders. Datatransport injects `android.permission.INTERNET` and telemetry services.
- Keeper refuses `INTERNET` at manifest merge and removes `TransportBackendDiscovery`,
  `JobInfoSchedulerService`, and `AlarmManagerSchedulerBroadcastReceiver`.
- The classes remain in the APK but have no entry point and no network permission. The app
  process cannot upload. This must be re-verified after every dependency update; the aapt and
  dependency scans do that automatically.
- The bundled face model itself is inside the ML Kit AAR; it does not download at runtime.

## 11. Other honest limits

- `FLAG_SECURE` does not prevent photography of the screen with another camera.
- Screen blur on face mismatch is a UI response, not a memory wipe.
- The face enrollment is only as good as the frames captured; poor lighting produces a poor template.
- The duress/decoy vault looks different only if the user populates it; an empty decoy is suspicious.
- Deleting the real vault on a duress event is intentionally **not** in v1. If added later, it needs a typed confirmation, a hold gesture, a loud warning, and a separate review.
- The app is not a substitute for full-device encryption, a locked bootloader, or a trustworthy OS.
- Play Store target requirements change; `targetSdk 36` is correct as of 2026-10-28 and must be rechecked before release.

## 12. Decryption is capped at 256 MiB of RAM

- The AES-GCM file cipher withholds plaintext until the tag verifies. It accumulates the
  decrypted bytes in a zeroable in-memory buffer, so a single entry larger than 256 MiB fails
  with a crypto error rather than risking an OOM or releasing unauthenticated plaintext.
- The Phase 1 requirement is 10 MB. Larger-file streaming would need a different format (chunked
  authenticated encryption) and a separate review.
- The Android Keystore adapter is tested on a real device via instrumented tests; JVM unit tests
  cover the surrounding key-manager and cipher logic. StrongBox is preferred, TEE is the fallback,
  and the fallback is not visible to the user in v1.

## 13. Network check is read-only

- The Network check screen reports Wi-Fi, mobile data, VPN (best effort), and airplane-mode state.
- It opens public system settings screens for Wi-Fi, Bluetooth, NFC, mobile/wireless, hotspot,
  VPN, and airplane mode.
- It cannot toggle, disable, or restore any radio. It does not request `BLUETOOTH_CONNECT`, `NFC`,
  `WRITE_SETTINGS`, `MODIFY_PHONE_STATE`, or similar permissions just to display status.
- Keeper has no `INTERNET` permission and does not upload network state.
