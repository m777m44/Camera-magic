# Acceptance / release gate

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
