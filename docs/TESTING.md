# Test plan and commands

## Local source tests (no APK or Android SDK required)

```bash
source .venv/bin/activate
python -m pip install -r requirements-dev.txt
python -m unittest discover -v
python -m camera_magic generate-luts --check
ruff check camera_magic tests scripts
ruff format --check camera_magic tests scripts
python scripts/check_java_syntax.py
```

Unit tests use synthetic manifests/ZIPs and mocked external commands. Tiny APK
fixtures are deliberately **not installable applications**. Passing these tests
is not an Apktool integration, actual signing, GPU, emulator or Blackmagic test.
The Java parser checks Java-8 grammar only; javac and Android Lint are separate.

## Original Android library compile / lint

On a host with JDK 17, Gradle 8.11.1 and Android platform/Build-Tools 35:

```bash
gradle --no-daemon :hdr-kit:assembleDebug :hdr-kit:assembleDebugAndroidTest :hdr-kit:lintDebug
```

The CI workflow does this for the **original library**, never for a downloaded
or user-provided Blackmagic binary. Consult the actual check conclusion/logs.
It does not produce a Blackmagic Camera Mahdi application.

## Original-library GPU matrix

Pushes and pull requests run the original-library shader matrix after source
and Android build checks pass. This allows this working branch to exercise the
matrix without changing the default branch. Manual workflow_dispatch runs can
opt in with `shader_matrix=true`; exposing the UI requires the workflow on the
default branch, and API dispatch requires Actions-write permission (which the
current repository connection does not grant). No additional credentials are
needed for the normal source push checks.

The GPU matrix provisions API 23/x86, API 29/x86_64, API 33/x86_64 and
API 35/x86_64 Google API emulator images with software rendering/KVM. Tests:

- Compile/link the OES, detail/LUT and copy programs.
- Decode all ten LUT PNGs and verify dimensions.
- Draw one constant RGB test pixel; compare zero/full/half intensity with the
  CPU grading reference, allowing 8-bit quantization and 16^3 interpolation.

No Blackmagic binary, camera session, physical sensor, saved JPEG, audio or
encoded recording is exercised by these tests. Shader compile success does
not prove the complete HdrRenderer lifecycle, spatial kernels or host integration.

To run the same kit shader tests on an already booted device:

```bash
ANDROID_SERIAL=emulator-5554 gradle --no-daemon :hdr-kit:connectedDebugAndroidTest
```

## Prepare emulators for a real modified APK (not executed here)

Use an independent development host with SDK access and acceleration. Example
image selection (verify current availability with `sdkmanager --list`):

```bash
sdkmanager 'system-images;android-23;google_apis;x86'
sdkmanager 'system-images;android-29;google_apis;x86_64'
sdkmanager 'system-images;android-33;google_apis;x86_64'
sdkmanager 'system-images;android-35;google_apis;x86_64'
avdmanager create avd -n mahdi-api23 -k 'system-images;android-23;google_apis;x86'
# Create equivalent AVDs for 29/33/35 using their image names.
emulator -avd mahdi-api23 -port 5554 -no-snapshot -camera-back emulated -camera-front emulated
```

Wait for the Android launcher/boot to complete. Use another terminal for adb.
Do not assume an ARM64-only original APK can run on these x86/x86_64 images;
most professional camera APKs need a matching physical ARM device or a supported
ARM image/runtime. Inventory and smoke-test compare native ABI declarations.
Never remove native libraries to make an ABI check superficially pass.

After a legitimate standalone APK has been decoded/patched/built/signed and
verified locally, with a fresh report directory for each run:

```bash
python -m camera_magic smoke-test artifacts/Blackmagic_Camera_Mahdi_REBRAND_ONLY.apk \
  --serial emulator-5554 --api 23 --report-dir work/reports/api23-run1
# Repeat on the actual matching serial/API for 29, 33 and 35.
```

What this smoke test does:

1. Verify APK identity, SDK 23/35 baseline, label, v1/v2/v3 and ZIP alignment.
2. Require one explicit adb serial; compare actual API/ABIs with expectations.
3. Install/reinstall the uniquely identified app (never uninstall the original).
4. Grant declared runtime camera/mic permissions, force-stop only this package,
   launch its manifest/aapt2 activity and observe its main process for 10 seconds.
5. Collect timestamp-scoped logcat without clearing other applications' logs;
   attribute Java/native crash markers to this package and attempt a screenshot.
6. Store a structured result even on failure/blockage. Preview/filter/media
   criteria remain `not_tested`, even when launch survival passes.

Reports are local and ignored by Git. Screenshots/logcat may contain personal
information. No pre-generated screenshots are included as purported app evidence.

## Manual end-to-end gate

Follow [ACCEPTANCE.md](ACCEPTANCE.md), including permission denial, lifecycle,
front/back camera orientation, filtered still output and decoded recorded frames,
audio synchronization, unsupported modes and physical Xiaomi/Huawei/Samsung
hardware. Rebrand-only builds cannot pass HDR-in-recording acceptance. APK signing,
SDK declarations, successful installation and a live main process are four
separate facts; none proves that a working camera or filters were delivered.
