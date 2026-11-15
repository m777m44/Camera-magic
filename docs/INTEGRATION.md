# HDR integration guide — work still required

**No Blackmagic APK was supplied. This library is not integrated into that app.**
The CLI copies original LUT/shader assets into the patched tree but does not
insert a DEX bridge, call the renderer or add a working host UI. Its outputs are
explicitly rebrand-only. This distinction must survive any release notes.

## Intended SDR path

```text
Camera2 capture output
    -> renderer-owned Surface / external-OES SurfaceTexture
    -> oriented RGBA source FBO
    -> spatial-detail + 3D LUT + strength -> processed RGBA FBO
          -> fit/letterbox -> preview EGL window
          -> timestamped EGL window -> MediaCodec -> host muxer/audio
          -> optional glReadPixels -> filtered preview-resolution bitmap
```

The processed texture is shared by preview and encoder. Sending the camera
straight to a second codec Surface bypasses filtering and fails acceptance.
This implementation uses ES 2.0 for wider API-23 hardware compatibility; ES 3.0
is not assumed merely because the OS is Android 6+. It checks OES, recordable
EGL configuration, texture size and framebuffer support at runtime.

## What the filters do

Ten original presets: Balanced, Crisp Detail, Natural Soft, Cinema Contrast,
Vivid Landscape, Shadow Recovery Look, Soft Highlights, Cool Clarity, Warm
Detail and Monochrome Detail. `presets.json` is authoritative.

- Display-referred shadow lift, highlight softening, contrast, saturation and
  warmth are baked into 16×16×16 LUTs.
- PNG atlas: 4×4 blue-slice tiles, 16×16 texels each, total 64×64 RGB8. Red is
  horizontal and green vertical within each tile; blue interpolation is explicit.
- `.cube` uses red-fastest ordering. `.cube` and PNG are generated from the same
  function. PNG carries no ICC/sRGB chunks to avoid LUT color conversion.
- The shader uses half-texel offsets, bilinear RG interpolation and linear blue
  interpolation (trilinear 3D sampling). Borders use clamp-to-edge.
- Fine four-neighbor unsharp masking plus wider four-neighbor local contrast
  are separate spatial operations. A 3D LUT alone cannot add spatial detail.
- Strength blends the *entire processed effect*, including clarity, with the
  unfiltered source. Zero is identity, not a sharpened image with a neutral LUT.

These are **pseudo-HDR SDR looks**, not exposure fusion, extra sensor detail,
10-bit output, HLG/PQ or Log normalization. Highlight recovery cannot recover
clipped information. Sharpening can increase noise and halos. The pipeline
assumes display-referred SDR/Rec.709-like input. Never feed it unconverted Log,
HLG or PQ and preserve HDR metadata as if the result were unchanged HDR.
Select a supported SDR camera/codec mode or add a separately validated color
management path first. RenderScript, Frida and GPUImage are not dependencies.

## Host analysis before any hook

Use jadx, decoded Smali and native-library inspection to locate the actual
capture/session/preview/encoder surfaces, render thread, UI, codec and muxer
ownership. Modern camera apps can put most processing in native code. A generic
`isDeviceSupported -> true` patch neither creates a working pipeline nor safely
substitutes hardware functionality. No such patch is provided.

First check whether a legitimate built-in LUT processing/burn-in path can be
extended without adding a second pipeline. Verify its color space, LUT layout,
intensity behavior and recorded output. This guide does not assume that a
particular Blackmagic version exposes such a hook.

## Library lifecycle contract

- Compile this source as the `hdr-kit` AAR. It has no production dependencies
  other than Android APIs. Test-only AndroidX dependencies are separate.
- Create a dedicated `HandlerThread`; construct `HdrRenderer` on its Looper.
  All mutations and `close()` must run on that thread. Use `getHandler().post`.
- Choose a Camera2-supported SDR output size and configure the renderer at that
  size. The camera must be able to output to its SurfaceTexture at that size.
  Start with 640×360 or a supported low resolution and measure device throughput.
- Pass `getCameraInputSurface()` to the reviewed Camera2 capture session. This
  Surface belongs to the renderer, not the host; do not release it independently.
- Pass the preview Surface and its real dimensions to `setPreviewSurface`.
  It is borrowed. Detach with null before destroying the host Surface.
- Configure a surface-input SDR MediaCodec at exactly the renderer's width and
  height. Call `setEncoderSurface(codec.createInputSurface())`. The host must
  prepare/start/drain the codec concurrently and mux video/audio itself.
- The same filtered FBO is copied to both destinations, with camera nanosecond
  timestamps submitted by EGL for the encoder. Validate monotonic timestamps,
  frame pacing and audio synchronization on each device.
- Supply `setInputTransform(float[16])` for sensor rotation/front-camera mirroring
  and the intended crop. The renderer composes it with SurfaceTexture's matrix.
  Sensor orientation and codec rotation metadata are host responsibilities.
- `captureFilteredFrame()` returns the **last processed frame at stream resolution**,
  not a full-resolution sensor JPEG. It is an expensive, vertically corrected
  readback; caller owns/recycles the Bitmap and handles saving/MediaStore. A
  separate high-resolution still path needs its own reviewed filtering route.
- Catch construction/preset failures; the render error listener runs on the GL
  thread. Stop the capture and encoder safely before restoring the original
  unfiltered path or showing an unsupported-mode error. Do not silently continue
  a recording without filters or continue using a failed renderer.
- Stop repeating, close/await the Camera2 session, detach encoder/preview,
  close the renderer on its thread, release host-owned codec/preview Surfaces
  and then quit the HandlerThread. Recreate after context/surface loss.

### UI sketch (host adaptation required, not a completed app patch)

The host creates the renderer on its GL thread, keeps lifecycle-safe ownership,
and forwards UI events there. Conceptual callbacks:

```java
// renderer and presets must already have been created on glHandler's thread.
// Add the actual SeekBar/preset picker to the host's reviewed layout/HUD.
seekBar.setMax(100);
seekBar.setProgress(50);
seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
    @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
        glHandler.post(() -> {
            if (renderer != null) renderer.setStrength(value / 100f);
        });
    }
    @Override public void onStartTrackingTouch(SeekBar bar) { }
    @Override public void onStopTrackingTouch(SeekBar bar) { }
});
// On preset selection, post renderer.setPreset(presets.get(index)); catch IOException.
```

The surrounding host variables, resources, error UI, lifecycle guards and hook
points cannot be supplied honestly before analyzing the actual application.
A callback to a released renderer must not be left queued by a destroyed UI.

## If integrating into an APK instead of an Android source project

Do this locally only with the necessary rights. There is no universal resource
or Smali merge that works for every version:

1. Assemble the AAR from this source and extract its `classes.jar` locally.
2. Use the verified SDK D8 with `--min-api 23` and the platform `android.jar` to
   produce DEX. Java 8 lambdas must be desugared by D8, not hand-copied as class files.
3. Use a verified compatible Baksmali/Smali toolchain; resolve the requested
   standalone 3.0.9+ requirement rather than assuming it is installed.
4. Merge into a **new, unused** multidex Smali directory, retaining all existing
   classes. Reject duplicate class descriptors. API 23 supports platform multidex.
5. Merge assets under `assets/mahdi_hdr` and create the actual host bridge/UI
   resources. The library itself defines no R-dependent widgets/layouts.
6. Patch only the inspected host hook points with correct method descriptors,
   registers, lifetimes, exception behavior and camera/codec surface ownership.
   Native/JNI paths may require native-source changes that are unavailable.
7. Rebuild/align/sign locally, then execute every acceptance layer and retain
   evidence. Do not flip a report's HDR flag as a substitute for a tested bridge.

The automatic `build`/`sign` CLI deliberately keeps its `REBRAND_ONLY` naming
contract. A genuinely integrated release needs a separately reviewed build plan
and evidence, not a filename change to this baseline.

## Testing coverage limits

The Android instrumented tests compile/link all shaders, decode all ten atlases,
and draw a constant test pixel at intensity 0/0.5/1 against a CPU color reference.
They do not test this entire Camera2/SurfaceTexture/EGL-window/MediaCodec lifecycle,
spatial-kernel behavior on real scenes, still encoding, audio or Blackmagic hooks.
The Python tests check generation/layout/manifest policy/command ordering using
synthetic fixtures. Follow [ACCEPTANCE.md](ACCEPTANCE.md) for real release tests.
