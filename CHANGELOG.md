# Changelog

## 0.4.0 — GPU LOOK pipeline, stills and LUT import (stage 1)

All code is original to this repository; see [ATTRIBUTION](ATTRIBUTION.md).

- **LOOK engine.** A single GPU pass applies a 3D colour cube to the live preview *and* the
  recorded frames through the same processed texture, so a preview-only filter is
  impossible by construction. Strength, clarity, grain and vignette are adjustable;
  strength 0 is an exact passthrough.
- **Bundled looks.** Six original display-referred looks (Neutral, Natural, Punch, Cinema,
  Soft light, Noir) generated deterministically by `python -m camera_magic app-luts`.
  `tests/test_app_lut.py` proves the Java parser and the Python generator produce identical
  atlases, so a look cannot grade differently in the preview than in the saved file.
- **User LUTs.** Import `.cube` and `.png` atlases from the system picker. The parser
  accepts the public 3D-cube format (comments, CRLF, comma separators, `DOMAIN_MIN/MAX`,
  `LUT_3D_INPUT_RANGE`), rejects 1D cubes, oversized cubes, inverted or 8-bit/log-scaled
  domains and non-finite entries with a user-readable message, and stores imports only in
  app-private storage. Imported LUTs can be removed again.
- **Still photos.** A new PHOTO action saves JPEGs to `DCIM/MahdiCamera`. With LOOK active
  the graded GPU frame is read back so the photo matches the preview; otherwise the sensor
  JPEG is used with the correct orientation hint.
- **Honest fallback.** If the GPU path or an extra camera stream is rejected (common on
  restricted HALs), the camera returns to the previous unfiltered path and the status line
  says the preview is unfiltered. Nothing is ever recorded with a look label but no look.
- **Engine and UI.** Optional preview target alongside the renderer target, JPEG reader for
  the plain path, multi-surface session layouts tried in order, encoder timestamps kept
  monotonic and repeated before detach, `OrientationHint` set to 0 only when the GPU already
  drew upright frames.
- **Stills keep the look without a GPU.** The renderer's cube is also available to the CPU
  (`CubeLut.sample`, `LookGrade`), so a saved JPEG is graded with exactly the table the
  preview uploads even on devices whose GLES cannot present the preview (the emulator
  cannot create an EGL window surface at all). The status line reports which path is live.
- **Tests.** 40 JVM unit tests (`.cube` parser contract, trilinear sampler and domain
  handling, pure column-major orientation matrix, encoder rotation, viewport, tuning
  immutability) and six instrumented tests, **all green on emulated API 23 and API 35**:
  preview lifecycle, pause/resume, Activity recreation, bundled looks and on-device `.cube`
  parsing, a still saved with the Noir look and measurably desaturated, and a recorded
  MP4 whose H.264 and AAC tracks are decoded back.
- **No new permissions.** The APK still has no `INTERNET` permission. An in-app updater is
  designed but not shipped; see [docs/UPDATER.md](docs/UPDATER.md).
- Fixed a pre-existing release self-test that accepted a 166 KB APK body in a
  repository-dispatch handoff instead of asserting the size guard.
- Fixed real bugs found by the emulator: every bundled look sampled the wrong cube geometry
  (the tile grid was ignored), every `.cube` larger than 2³ failed import ("Corrupt atlas
  buffer"), a rejected look session was retried in an endless loop so no preview appeared,
  and a static camera lease could be lost forever ("Camera is still closing") after an
  Activity was recreated or a recording stopped.

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
