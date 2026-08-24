# pixcam

A native Android camera app that doesn't fake it. Stock camera apps bake in
multi-frame computational processing, watercolor noise reduction, halo
sharpening, and aggressive tone curves. pixcam goes as close to the sensor as
Android allows (Camera2, `RAW_SENSOR`) and owns everything after it.

## Status: Stage 0 done, Stages 1–2 started

Capture core:

- Back camera via Camera2, preferring one with the RAW capability.
- ISP processing switched off wherever the HAL allows: noise reduction, edge
  enhancement, hot pixel correction.
- Manual controls (on devices with `MANUAL_SENSOR`): ISO, shutter, manual focus.
- RAW_SENSOR stills written as DNG via `DngCreator` (with lens shading map
  requested for later pipeline stages); JPEG fallback on non-RAW devices
  (with `JPEG_ORIENTATION` set from the sensor mounting).
- Files land in `Pictures/Pixcam/` via MediaStore.

Camera UI (Compose):

- Letterboxed preview between a status strip and a control bar; round shutter
  with saving spinner, shutter-blink capture flash, self-vanishing "Saved …"
  notices.
- PRO panel: ISO/shutter log sliders on manual-sensor devices; EV compensation
  and AE lock as the best-effort fallback on LIMITED hardware; manual focus.
- Hardware info dialog showing everything probed from `CameraCharacteristics`.
- In-app gallery: last-shot thumbnail plus a full-screen swipeable viewer
  (subsampled decode, EXIF rotation, embedded-preview fallback for DNGs).

Stage 1 (in progress):

- User-selectable tone curves pushed as `TONEMAP_CURVE` to preview and stills:
  Device / Linear / sRGB / Filmic (Hable), gated on the HAL advertising
  `TONEMAP_MODE_CONTRAST_CURVE`.
- Full 3D LUT support, independent of HAL capabilities: the viewfinder renders
  through GLES3 (camera → `SurfaceTexture` → OES texture → `sampler3D` with
  hardware trilinear filtering), with `.cube` import via the system file picker
  and three built-in looks (Punch / Teal Orange / Mono). JPEG stills get the
  LUT baked in offscreen (EGL pbuffer, CPU trilinear fallback) with EXIF
  carried over; DNGs stay raw by design — the LUT is a preview/develop look,
  applied for real in Stage 2.

Stage 2 (in progress) — our own RAW develop:

- Every RAW capture saves the DNG plus a JPEG developed by our own GPU
  pipeline (single GLES3 pass): black level (dynamic per-frame when the HAL
  reports it) → lens shading map → white balance → Malvar-He-Cutler demosaic
  → per-shot color matrix → Hable filmic curve → sRGB, with the active 3D LUT
  applied on top. CFA-order-agnostic (RGGB/GRBG/GBRG/BGGR), validated by
  instrumented tests against a CPU reference (`RawDevelopTest`).
- Still to come: the GLES-graded WYSIWYG viewfinder on binned RAW, and our own
  tone/color decisions replacing the HAL's per-shot CCM.

## Roadmap

1. **Stage 1 — ISP override + graded viewfinder**: custom `TONEMAP_CURVE` +
   `COLOR_CORRECTION_TRANSFORM` on the YUV path; GLES shader grading the preview.
2. **Stage 2 — our own RAW develop**: GPU pipeline — black level, shading map,
   neutral-point white balance, Malvar demosaic, interpolated DNG matrices →
   sRGB/P3, our own filmic curve. Simplified version on binned RAW for a
   WYSIWYG viewfinder.
3. **Stage 3 — burst merge / Bayer-domain denoise** for low light (HDR+-style).

Research brief with sources: see the plan at
`~/.claude/plans/use-a-subagent-team-effervescent-alpaca.md`.
Reference codebases: [android/camera-samples](https://github.com/android/camera-samples),
[PhotonCamera](https://github.com/eszdman/PhotonCamera) (GPL — study, don't copy),
[MotionCam archive](https://github.com/Willow0349/motioncam-archive).

## Building

Needs JDK 17 and the Android SDK (`local.properties` points at
`~/Library/Android/sdk`).

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew assembleDebug
```

Run on the emulator (created with the `android` CLI):

```sh
android emulator start medium_phone
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.pixcam/.MainActivity
```

The emulator's virtual camera advertises RAW (1280x960) and produces valid
Bayer DNGs, but reports LIMITED hardware with no manual sensor — real testing
needs a device. Best targets: Pixels (LEVEL_3, honest Bayer DNGs, all
processing-off modes). Samsung throttles third-party RAW.

## Device quirks log

- **Emulator (sdk_gphone64)**: HAL omits `CONTROL_POST_RAW_SENSITIVITY_BOOST`
  from capture results unless it's set in the request; without it `DngCreator`
  throws `Missing metadata fields for tag BaselineExposure`. We set it to 100
  on every still request.
