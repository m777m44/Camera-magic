# Changelog

## 0.3.0 — Independent Mahdi Camera application

- With the owner's explicit approval, change the deliverable from a name-only vendor
  APK to an independent original Camera2 Android app (`com.mahdi.camera`, Mahdi Camera).
  No Blackmagic code, artwork, APK, cloud service or affiliation is included.
- Add real preview, lens/resolution selection up to 1080p, H.264 MP4/AAC recording,
  optional microphone, MediaStore/legacy gallery saving, and last-clip playback.
- Add hardware-gated manual ISO/shutter/focus, white-balance presets, exposure
  compensation, digital zoom and screen-only composition guides.
- Close the camera and finalize recording on pause; discard failed/too-short output.
  No Internet permission, analytics, background capture or broad media-read permission.
- Add Java calculation tests, build/Lint, and real emulator recording/decoding and
  lifecycle tests on API 23 and 35. These are not physical-phone quality tests.
- Add persistent-certificate-pinned release automation and a secure optional local
  signing handoff containing only the public APK, never a private key/password.
  Rebuild validated main and compare all non-signature content before publication.
- Use the new independent asset name `Mahdi_Camera.apk`; keep legacy name-only tools
  and the separate HDR reference library without silently integrating their filters.

Full unattended releases require owner-configured MAHDI_APP_* signing secrets.
If permissions prevent setup, local signing can publish a genuine release with
that same private key; automation does not silently rotate certificates or use debug keys.

## 0.2.0 — Name-only APK and opt-in releases

- Added `rename-apk`: preserves the original application ID, icons, SDK and app
  version, changes application/launcher display labels only, and keeps raw DEX
  rather than reassembling Smali. No HDR library/assets are injected.
- Added post-build identity/permission checks and byte-for-byte checks of all
  DEX, native-library and asset files before a newly signed APK is accepted.
- Added authorized-source HTTPS/SHA-256 input handling, persistent-key recovery,
  checksums, verification reports and an opt-in trusted-main release workflow.
- Stage immutable release assets as a draft, then mark the complete release Latest;
  a failed or stale build does not replace the last successful public APK.
- Reserved a stable Latest asset name: `Blackmagic_Camera_Mahdi.apk`.
- Added source-policy unit tests and a real toolchain rename/sign round trip using
  only the repository's original test APK, never a substitute Blackmagic app.
- Support modern `minSdkVersion` and legacy `sdkVersion` badging output, stage an
  owned aapt2 copy so Apktool does not chmod a system-owned SDK, and explicitly
  select the same Build-Tools 36.0.0 in validation and release builds.

No original Blackmagic APK or production signing material has been provided.
Publication remains disabled until an authorized source and persistent signing
settings are configured. The future download URL is not an existing APK release.

## 0.1.0 — Toolkit baseline

- Added a local APK inventory, full Apktool decode, manifest audit, conservative
  application-ID/label/icon patching, build, zipalign and v1/v2/v3 signing workflow.
- Added explicit SDK-downgrade acknowledgement, split-package blocking, component
  class preservation, custom-permission/provider-authority remapping and reports.
- Added ten original SDR pseudo-HDR LUT presets, deterministic `.cube`/PNG atlas
  generation, spatial-detail shaders and a dependency-light Android GL library.
- Added unit tests, a source-only Android build/shader-test CI workflow and
  device smoke-test tooling for API 23, 29, 33 and 35.
- Excluded third-party APKs, decompiled application code and signing keys from Git.
- Fixed API-23 EGL14 initialization rejecting null version arrays, discovered by
  actual emulator shader tests; added a renderer construction/cleanup regression test.

No Blackmagic APK has been supplied, modified, built, signed, integrated with the
HDR library, or run on an emulator/device for this baseline. An SDK declaration
is not a compatibility result. LUT asset injection is not filter integration.
