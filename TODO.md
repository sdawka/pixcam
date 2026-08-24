# TODO

What's left, in suggested order. Status of finished stages lives in the README.

## 1. Pixel validation (blocking — next device session)

Several shipped features have only ever run on the emulator (LIMITED, placeholder
calibration) or the UMIDIGI (no RAW, no manual sensor, no tone curves). On a
Pixel (LEVEL_3), verify:

- [ ] Manual ISO/shutter sliders drive real exposure
- [ ] HAL tone-curve chips (Device / Linear / sRGB / Filmic)
- [ ] View HAL/WYSIWYG toggle — does the graded viewfinder actually match our
      developed JPEG?
- [ ] 12MP Bayer develop: image quality (demosaic artifacts, noise) and GPU
      timing on full-size raws
- [ ] Our color science against real calibration matrices — check the logged
      CCT estimate is sane and skin/foliage/sky don't drift
- [ ] LUTs on the viewfinder at full preview rate

Expect a tuning list to fall out of this — our filmic curve currently renders
brighter/flatter than the HAL render and is hardcoded.

## 2. Develop-curve controls (small)

- [ ] Exposure (pre-tone gain) and contrast parameters on the develop pipeline,
      surfaced in the PRO panel; keep Hable as the base operator
- [ ] Consider re-develop of existing DNGs from the gallery (pipeline already
      takes params; needs DNG reading + a UI entry point)

## 3. Own AWB (small, high leverage)

Our color science still trusts the HAL's per-shot SENSOR_NEUTRAL_COLOR_POINT
for white balance — the last piece of HAL color influence.

- [ ] Estimate the illuminant ourselves from the raw mosaic (gray-world /
      gray-edge on the black-levelled, shading-corrected data), feed it to
      ColorScience as the neutral point
- [ ] Keep the HAL neutral as fallback + a debug toggle to A/B

## 4. Binned-RAW preview develop (decide after Pixel testing)

The viewfinder currently grades the HAL's YUV (LINEAR curve + our filmic in
GL). The maximal version develops a low-res RAW stream through our own
pipeline so preview *color* — not just tone — is ours. Real architectural
work (persistent GL develop pass per frame, RAW repeating stream support
varies by HAL). Only worth it if Pixel testing shows the YUV path isn't
close enough.

## 5. Stage 3 — burst merge / Bayer-domain denoise (largest chunk)

HDR+-style low light. Nothing started.

- [ ] Burst capture (N raw frames, fixed exposure)
- [ ] Align (coarse-to-fine, tile-based) in the raw domain
- [ ] Merge (robust average / Wiener-style per tile), then feed the existing
      develop pipeline
- [ ] Start CPU-correct, move hot loops to GPU

## Small polish (fold in opportunistically)

- [ ] "Grant" button on the permission-denied screen
- [ ] Gallery: label which files are DNG vs our developed JPEG
- [ ] Info dialog: show which color path (own / HAL fallback) and estimated CCT
      for the last shot
