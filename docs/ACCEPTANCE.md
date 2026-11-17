# Acceptance / release gate

> **The independent `camera-app` is a separate deliverable from the patcher described
> below.** Its measured results are in the section "Measured: independent `camera-app`"
> at the end of this file. The rows below still describe the (unfinished) patcher. Never
> promote a patcher row to `passed` from a `camera-app` emulator run: they are different
> artefacts.

This file is a checklist, not a test report. No Blackmagic application APK was
provided to create this baseline. Do not replace `not_tested` with `passed`
without an APK SHA-256, tool versions, device/API/ABI and retained evidence.

| Requested criterion | Baseline status | Evidence required to pass |
|---|---|---|
| Name and new icon | Patcher implemented; no real APK patched | Installed launcher screenshot + aapt2 badging |
| Unique `com.mahdi.blackmagic.camera` alongside original | Manifest patch tested on synthetic fixtures only | Both installed; provider/custom permission/hardcoded ID audit |
| Android 6 / API 23 launch without immediate crash | **Not tested** | Signed v1 APK, ABI-matched device, launch report + logcat |
| Android 15 / API 35 compatibility | **Not tested** | Install, permission/foreground service tests, codec/camera/lifecycle checks |
| At least five filters in preview AND saved images/video | Ten original assets + reference GL library; **not integrated** | Host bridge source, intensity UI, filtered readback and encoded frame comparisons |
| Correct v2/v3 signing and successful install | Signing/verification automation unit-tested; **no APK signed** | Real apksigner output, ZIP alignment, API 23 v1 check, adb install |
| Source/final files in repository | Toolkit source/assets/docs only | No final APK exists; distribution of third-party code requires permission |

## Separate test layers

1. Python unit tests: synthetic XML, ZIP and command mocks. They test the toolkit,
   not APKTool, the original APK, actual signing, Android execution or GPU behavior.
2. Java syntax parser: syntax only, not compilation or Android API verification.
3. CI `android-library`: assembles/lints only the original `hdr-kit` source. A
   workflow definition is not evidence that a run passed; inspect the actual run.
4. CI `shader-matrix`: the kit's shader compile/atlas/intensity tests on
   API 23/29/33/35. It does not install or test Blackmagic Camera, Camera2 capture,
   encoder output, still output, audio or the full renderer lifecycle.
5. `smoke-test`: installs the supplied, separately verified APK on an explicit
   device, starts its original activity class under the new application ID,
   checks main-process survival and retains a screenshot/logcat. It does not
   automatically verify preview quality, filter operation or recorded media.
6. Manual end-to-end acceptance: mandatory before any compatibility claim.

## Runtime matrix (the modified host application)

| API | OS | Launch | Preview | Strength 0/50/100 | Saved still | Encoded video + audio | Lifecycle |
|---|---|---|---|---|---|---|---|
| 23 | Android 6 | not_tested | not_tested | not_tested | not_tested | not_tested | not_tested |
| 29 | Android 10 | not_tested | not_tested | not_tested | not_tested | not_tested | not_tested |
| 33 | Android 13 | not_tested | not_tested | not_tested | not_tested | not_tested | not_tested |
| 35 | Android 15 | not_tested | not_tested | not_tested | not_tested | not_tested | not_tested |

Xiaomi, Huawei and Samsung physical-device rows must be recorded separately,
including model, firmware, Camera2 capabilities, ABI, resolutions, codecs,
thermal throttling and permission behavior. Brand name alone is not a hardware
capability. Emulator launch success is not physical-camera compatibility.

## Manual image/video checks

- Use a fixed SDR test chart, locked exposure/white balance/focus and identical
  framing. Do not compare auto-exposure changes as if they were filter changes.
- Verify every preset at intensity 0, 0.5 and 1. At 0 the *filter* should be an
  identity operation within 8-bit/codec tolerances, including spatial detail.
- Decode saved video and compare frames, not a screenshot of the preview. A
  preview-only filter with an unfiltered MediaCodec path fails acceptance.
- Inspect JPEG/bitmap output, orientation, mirroring, cropped field of view,
  highlight clipping, halos, shadow noise, color charts and banding.
- Check monotonic presentation timestamps, output size, FPS, dropped frames,
  audio sync, rotation metadata and media duration. Drain MediaCodec concurrently.
- Exercise camera/permission denied, mic denied, missing hardware, orientation
  change, foreground/background, screen lock, low storage, camera in use and GL
  context/surface loss. A fallback must be explicit; no silent unfiltered recording.
- For API 35 review exported components, camera/microphone foreground-service
  declarations/start restrictions, storage/MediaStore behavior and 16 KiB pages.
  `zipalign -P 16` cannot fix 4 KiB-aligned ELF load segments or unavailable APIs.

## Release evidence (local / restricted storage)

Keep `doctor.json`, input/output SHA-256, source version/provenance, review notes,
`patch.json`, build log, certificate fingerprint, verification JSON and one fresh
smoke report per device. Remove personal paths, serial numbers, captured media
and logcat secrets before sharing. Never publish the keystore or its passwords.

## Measured: independent `camera-app`

Evidence: workflow `Mahdi Camera app validation` run
[43907626417](https://github.com/m777m44/Camera-magic/actions/runs/43907626417) on commit
`f0dfeec`, both emulator jobs green (API 35 x86_64 and API 23 x86, `google_apis`,
`-gpu swiftshader_indirect`, emulated back/front cameras).

| Criterion | Emulator result | What is still unproven |
|---|---|---|
| Installs and launches on API 23 | `passed` (instrumented suite runs in-process) | ABI-matched physical Android 6 device |
| Installs and launches on API 35 | `passed` | Physical Android 15 device |
| Camera preview opens, closes and reopens | `passed`: pause/resume ×3, Activity recreation ×3, recording then restore | Vendor HAL quirks (Xiaomi/Huawei/Samsung/Pixel) |
| ≥5 looks bundled and selectable | `passed`: 6 looks decode on device, each also yields a gradable CPU cube | — |
| Look reaches the saved still | `passed`: the saved JPEG is measurably desaturated versus an unfiltered capture of the same scene | GPU-graded preview, because emulated GLES cannot create an EGL window surface here |
| `.cube` import | `parsed on device` (`CubeLut.parse` + atlas allocation for a 9³ cube) | Large 33³/65³ cubes on a real device |
| H.264 + AAC recorded and decodable | `passed`: MediaExtractor finds an `avc` video track and an `mp4a-latm` audio track, samples decode, duration > 1 s | Real sensor quality, 4K, 10-bit |
| Manual ISO/shutter/WB/focus, peaking | **not tested** | Requires a device with manual Camera2 support |
| Luminance histogram + exposure advice | `passed` on device (`GuidesAndHdrTest`): the overlay reports its grid/level/histogram state and the histogram produces a live exposure reading from the graded frame | Colour accuracy of the advice on real scenes |
| Composition grids (thirds, golden, cross), title-safe frame, horizon level | `passed`: grid/level state is asserted on device; the level indicator is sensor-driven and preview-only | How the level feels in the hand on a real device |
| HDR bracket (3/5/7 frames) fused on device | `passed` where the emulator sensor exposes AE compensation: a bracketed capture produces a decodable JPEG; the test skips (JUnit assume) on sensors without compensation | Real-scene ghosting between bracketed frames |
| RAW/DNG, sensor HDR, super-resolution, night mode, dual video | **not implemented** | Multi-frame fusion is *display-referred* HDR, not sensor HDR |
| Signed release APK v1–v4, permanent download link | **not done**: no release published yet | Needs the keystore and a published release |

The emulator proves lifecycle, permissions, capture, encoding and the colour pipeline's
plumbing. It cannot prove image quality, colour accuracy, thermal behaviour or vendor
compatibility; those stay `not_tested` until a physical device run is recorded.
