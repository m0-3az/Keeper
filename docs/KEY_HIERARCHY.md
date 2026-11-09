# Key hierarchy

Keeper keeps the vault key out of persistent storage. The hierarchy has four layers.

## Setup

1. `VaultKey` = 32 random bytes from `SecureRandom`.
2. `KEK` = PBKDF2-HMAC-SHA256(secret, 16-byte salt, at least 600,000 iterations).
3. `LayerA` = AES-256-GCM(KEK, VaultKey).
4. `Stored` = Keystore.encrypt(`LayerA`), with StrongBox preferred and TEE fallback.
5. Persist the salt, iteration count, and `Stored`. **Never persist KEK or VaultKey.** Zero every
   buffer.

## Unlock

1. `Keystore.decrypt(Stored)` -> `LayerA`.
2. Recompute `KEK` from the entered secret and stored salt.
3. AES-GCM decrypt(`LayerA`) -> `VaultKey`.
4. A wrong secret fails the GCM tag; return a uniform failure with no oracle.

## What each layer buys

- Keystore layer: offline extraction of app files does not yield the key.
- Secret layer: app-level malware or root cannot decrypt without the PIN/password.
- A short PIN remains brute-forceable if an attacker can query the Keystore online; attempt limits
  and cooldowns are still required.

## Biometric convenience path

A separate Keystore key with `setUserAuthenticationRequired(true)` wraps `VaultKey` for
`BiometricPrompt`. It is an additional path only; the password path remains authoritative and must
still work if biometrics are removed, re-enrolled, or unavailable. `BiometricPrompt` cannot run
continuously.

## Intruder key

Failed-unlock capture happens before any secret-derived `VaultKey` exists. Intruder images use a
dedicated random `IntruderKey`, wrapped by a non-auth Keystore key. Only the `FAILED_UNLOCK` trigger
is active; every capture requires a visible warning and explicit on-screen confirmation.
`FACE_MISMATCH` capture is disabled because there is no visible confirmation flow for a background
mismatch. This key is protected at rest by the Keystore only, not by the user's secret; see
`LIMITATIONS.md` section 8.

## Duress

A second envelope (separate salt, separate DB/directory) is unlocked by the duress secret and opens
the decoy vault. The real vault is untouched. "Delete the real vault on next unlock" is
intentionally not part of v1.
