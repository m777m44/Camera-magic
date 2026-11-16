# In-app update: design and current status

**Status: designed, not shipped.** The installed APK has no `INTERNET` permission and no
update code. Nothing in this document describes a feature you can use today.

## Why it is not in the camera APK

The camera is advertised as offline: no account, no analytics, no upload, no network
permission. An in-app update checker that calls the GitHub Releases API needs
`android.permission.INTERNET`, and a client that can fetch any URL can also upload. Adding
that permission to `com.mahdi.camera` would silently invalidate a privacy promise that
`tests/test_app_release.py` enforces, so it was not done.

## The agreed design: a separate, opt-in updater

| Decision | Choice | Reason |
|---|---|---|
| Network access | A separate app (`com.mahdi.camera.updater`) with `INTERNET` | The camera keeps its permission set; a user who never installs the updater transfers nothing. |
| Trigger | Only when the user opens the updater or taps "Check for updates" | No background polling, no scheduled jobs. |
| Source | `https://api.github.com/repos/m777m44/Camera-magic/releases/latest` | Same repository that publishes `Mahdi_Camera.apk`; no third-party host. |
| Download | Only over HTTPS from `github.com` / `objects.githubusercontent.com` | Prevents redirecting a signed APK fetch elsewhere. |
| Integrity | Compare the downloaded file's SHA-256 with `SHA256SUMS.txt` from the same release | Detects truncation or tampering in transit; the APK signature remains the real trust anchor. |
| Signature | Before install, verify the APK is signed by the pinned certificate in `config/mahdi-camera-signing.json` | A modified APK from a different key must never be offered. |
| Install | `PackageInstaller.Session` (API 21+) with an explicit user confirmation | Android requires the user to accept the install; the updater cannot install silently. |
| Permissions | `REQUEST_INSTALL_PACKAGES` on the updater only, granted by the user in system settings | Least privilege; the camera stays unchanged. |
| Progress | Bytes downloaded, percentage, speed and ETA from a foreground-safe `StateFlow` | Requirement from the original brief; shown only while a download is running. |
| Wi-Fi only | Opt-in setting, default on | Avoids surprise mobile-data use. |
| Failure | Any mismatch (size, SHA-256, certificate, package name, lower `versionCode`) aborts and deletes the file | No partial installs, no downgrade loops. |

## Why not a "self-update" inside the camera

* Android does not allow an app to replace its own APK without user interaction.
* Two installers with different signing keys cannot update each other; the pinned
  certificate is what lets the updater hand a new version to the same app identity.
* `versionCode` in this repository is `git rev-list --count HEAD`, so a strictly larger
  value is easy to check and a downgrade is easy to refuse.

## What is already in place

* A fixed download URL that always serves the newest release:
  `https://github.com/m777m44/Camera-magic/releases/latest/download/Mahdi_Camera.apk`
* A published `SHA256SUMS.txt` next to each APK.
* A pinned public certificate in `config/mahdi-camera-signing.json`.
* A release workflow that only publishes builds whose tests passed on the exact current
  `main` commit.

## What is missing before it can be called "in-app update works"

1. The updater module, its manifest, and its own tests (unit + instrumented).
2. A release asset contract test: `SHA256SUMS.txt` must contain an entry for
   `Mahdi_Camera.apk` and the workflow must fail if it does not.
3. A published, signed release whose certificate matches the pin.
4. A device or emulator test that performs a real download and a real
   `PackageInstaller` commit, plus a screenshot of the progress UI as evidence.

Until 1–4 exist, the acceptance criterion "In-App Update works (check, download,
progress bar, percentage, install)" is **not met**, and the README says so.
