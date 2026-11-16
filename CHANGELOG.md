# Changelog

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
