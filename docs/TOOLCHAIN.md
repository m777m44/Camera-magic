# Toolchain and provenance

## What this environment can and cannot do

The checkout initially contained only `README.md`. No original APK, Java,
Android SDK, adb, emulator or KVM device was present. Network access in this
workspace is restricted to GitHub and package registries, not Google Play,
APKMirror, Google's SDK download host or Google's Maven repository. GitHub
repository access works through `git`/`gh`; the repository is public.

Consequently this baseline does not claim acquisition of any Blackmagic APK,
latest-app-version verification, Android compilation, signing, or host-app
emulator/device tests in the workspace. Python tooling/tests and deterministic
asset generation can run here. Remote CI has its own environment: consult the
actual run, not this document, for its result.

## Install on your own development host

Use an Android Studio release supporting API 35 (Ladybug or newer), or the
current official command-line SDK. Review/download current stable tools from
their publishers and verify published hashes/signatures. The toolkit never
installs unverified JARs or binaries automatically.

Minimum supported external-tool versions:

- Java 17+, with `java` and `keytool` from the same maintained JDK installation.
- Apktool 2.11.1+. Avoid `-r/-s`; this task needs text resources and Smali.
- jadx 1.5.1+ if using optional analysis.
- Stable Build-Tools 35.0.0+; `source.properties` is used to verify its version.
- Platform 35 / platform-tools, plus emulator/system images for device tests.

GitHub release metadata queried while preparing this baseline returned Apktool
3.0.3 and jadx 1.5.6. These tools were not installed/tested here. Google's Smali
repository's latest-release endpoint returned 3.0.6, not evidence of the requested
standalone 3.0.9+. Do not invent a 3.0.9 download or claim that requirement passed.
A manual bridge requiring a standalone assembler must first resolve and verify
that tool requirement. The rebrand workflow uses Apktool's bundled DEX tools;
it does not assert their standalone version or translate newer Android APIs.

Example SDK installation on a host with access to official Google downloads:

```bash
sdkmanager 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0' 'emulator'
# Only after reviewing the licenses interactively:
sdkmanager --licenses
```

For the original Android library, install Gradle 8.11.1 and JDK 17, then:

```bash
gradle --no-daemon :hdr-kit:assembleDebug :hdr-kit:assembleDebugAndroidTest :hdr-kit:lintDebug
```

The pinned AGP/Gradle baseline is reproducible, not a dynamically resolved
latest-stable claim. Upgrade AGP, Gradle and JDK together after checking their
compatibility. A Gradle binary/wrapper and Android SDK are not vendored here.

## Configure discovery

Copy `config/toolchain.example.json` to `toolchain.json` and set real paths.
Do not commit this local file. `--tools PATH` selects another configuration.

Configuration priority: explicit JSON paths, then environment, then PATH/SDK.
`APKTOOL_JAR` selects `java -jar …`; `JADX` selects the jadx executable;
`JAVA_HOME` or `JAVA` selects Java; `ANDROID_SDK_ROOT`/`ANDROID_HOME` selects SDK.
`KEYTOOL` may explicitly select the matching JDK keytool. SDK tool overrides are
not accepted independently: zipalign/apksigner/aapt2 come from the validated SDK
Build-Tools directory. `build_tools` in JSON pins that directory; without it the
highest stable numeric revision >=35.0.0 is selected. Preview/rc packages are
not silently selected.

```bash
python -m camera_magic doctor --report work/reports/doctor.json
```

`doctor` returns 0 only for a usable rebranding/build/signing toolchain, 1 when
required tools are missing, and 2 for an invalid configuration. `jadx`, adb and
the emulator are optional to a rebrand build but required for their own tasks.
A green doctor is not a compatibility/integration/real-camera result.

## Original package provenance

1. Obtain a complete legitimate APK from the publisher or a source you trust.
   Do not enter Google credentials into scripts or send them in chat.
2. Verify its original signing certificate with the publisher's known certificate
   fingerprint, where available. A SHA-256 digest alone does not authenticate it.
3. Retain source URL, version name/code, acquisition date, original certificate
   fingerprint and permission/redistribution terms in local review notes.
4. Inventory computes a SHA-256 and records native ABI, ELF load-alignment hints
   and DEX magic. An archive containing base/split APKs is listed, not merged.
5. Decode records the input digest and Apktool version under `.camera-magic/`.
   That marker is a local audit aid, not a cryptographic attestation that an
   operator has not subsequently changed the decoded tree.

The latest stable Blackmagic version and the requested 3.5.0.0064 were not
verified. Do not label an unknown APK as that version based on its filename.

## Important failure modes

- `isSplitRequired`, required split types, uses-split and Play split-required
  metadata block patching. Supply a standalone input. Removing flags can create
  an installable-looking APK missing resources, bytecode or native libraries.
- `sharedUserId` is not automatically removed: changing signatures/IDs alters
  shared data and permission semantics.
- Exported components with intent filters must have an explicit policy before
  targeting 35. The toolkit refuses to auto-export them as a blanket fix.
- SDK values are changed in both Manifest and Apktool metadata; Apktool 2.x's
  `packageInfo.renameManifestPackage` is updated; 3.x's `resourcesInfo` is preserved.
- Native ABIs, JNI names, resource package IDs and vendor native code are not
  globally renamed or faked. Review hardcoded authorities in Smali/native code.
- Minimum SDK downgrade can cause assembly/verification errors, missing Java
  APIs, missing ELF symbols, unsupported camera/codec paths and immediate crashes.
- ZIP alignment is distinct from ELF segment alignment and 16 KiB page testing.
- Runtime integrity/licensing issues are reported for review; no automatic
  always-true device checks, signature checks or anti-tamper bypasses are shipped.

Store logs in ignored directories. Device serials, local paths, logcat and media
can contain personal information even when no keystore is included.
