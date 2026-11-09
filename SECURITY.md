# Security policy

Keeper is a local-only Android vault. Please do not open a public issue for a
security vulnerability.

## Reporting

Use GitHub's private vulnerability reporting: open the repository's **Security**
tab and choose **Report a vulnerability**. Include:

- the version or commit under test;
- device model and Android API level;
- reproduction steps and observed impact;
- whether a rooted device, debug build, or physical access is required.

Do not include real vault secrets, decrypted content, or intruder images.

## Scope

- key hierarchy, Keystore wrapping, and AES-256-GCM file storage;
- password and biometric unlock, attempt limiting, and lock behavior;
- intruder-capture consent, confirmation, storage, and retention;
- isolation and tamper warnings;
- manifest and network posture, including the absence of `INTERNET`.

## Supported versions

The latest `0.1.x` preview is supported. Older previews may not receive fixes.

## Disclosure

Reports are handled on a best-effort basis. Please allow time for a fix and
coordinate disclosure before publishing details. No bug bounty is offered.
