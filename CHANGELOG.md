# Changelog

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
