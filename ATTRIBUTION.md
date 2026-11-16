# Attribution

Mahdi Camera contains **no third-party source code**. Every file under `camera-app/`,
`camera_magic/` and `assets/` is original work for this repository, released under the MIT
licence in [LICENSE](../LICENSE).

The projects below were read as **feature and UX references** while designing the camera.
Their algorithms and ideas informed requirements; their code, shaders, artwork, strings and
asset files were not copied, translated, decompiled or vendored. No repository is added as a
Git submodule or Gradle dependency.

| Project | Licence | What it was consulted for | Code included? |
|---|---|---|---|
| [bjzhou/PhotonCamera](https://github.com/bjzhou/PhotonCamera) | Apache-2.0 | The product idea of a real-time LUT engine with an adjustable strength, `.cube`/atlas importing, and a preset row in the camera UI. | No |
| [Gr4nt-cell/iCam](https://github.com/Gr4nt-cell/iCam) | GPL-3.0 | Multi-frame HDR+ and high-frame-rate video as future goals; not implemented in this stage. | No |
| [HF-CYGG/LumaSR](https://github.com/HF-CYGG/LumaSR) | No licence file detected | AI super-resolution (ncnn/Vulkan) as a future goal; not implemented. | No |
| [tribixbite/CustomCamera](https://github.com/tribixbite/CustomCamera) | MIT | Pro-camera control layout, focus peaking, histogram and zoom chips as reference for later stages. | No |
| [matthew777777/RawLens](https://github.com/matthew777777/RawLens) | GPL-3.0 | The RAW/HDR+/MFSR pipeline as a long-term design reference only. | No |

## Why nothing was vendored

* RawLens and iCam are GPL-3.0. Copying any of their code would force this whole repository
  to GPL-3.0 and change the distribution terms for every file, including the MIT toolkit.
* This project targets **display-referred SDR** output. Code written for 10-bit HLG, RAW
  demosaicing or Vulkan inference would not compile into the current app without the
  dependencies those projects use (ncnn, Vulkan compute, CameraX).
* A feature that cannot be tested on a real device in this repository would be an
  unverified claim. Everything shipped here is exercised by unit or instrumented tests.

If a future stage does copy GPL-3.0 code, this file must be updated **before** that code is
merged, the repository licence must change to GPL-3.0, and the CI licence check must be
updated to match.

## Other references

* The `.cube` 3D LUT text format is a de-facto interchange format documented by several
  grading tools; `camera-app/src/main/java/com/mahdi/camera/lut/CubeLut.java` is an
  independent implementation of that public format.
* Android platform APIs (Camera2, `MediaRecorder`, `ImageReader`, EGL/GLES20) are used under
  the Android SDK terms and are not redistributed here.
* No Blackmagic Design code, artwork, fonts, trademarks, services or APKs are included. The
  app is not affiliated with Blackmagic Design.
