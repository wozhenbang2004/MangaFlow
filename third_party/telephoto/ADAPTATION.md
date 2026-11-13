# Telephoto vertical-reader integration

The app already uses Telephoto for horizontal reading. Long vertical images now also use
the public sub-sampling module, pinned to **0.16.0**, upstream commit
`2a5e5f502605367c0f497bbbdc3804a15f5e2f74`, by Saket Narayan, Apache License 2.0.

Source reference: `https://github.com/saket/telephoto/tree/2a5e5f502605367c0f497bbbdc3804a15f5e2f74`.

| Upstream source actually read | MangaFlow integration |
| --- | --- |
| `zoomable-image/sub-sampling-image/src/androidMain/kotlin/me/saket/telephoto/subsamplingimage/SubSamplingImage.kt` | Render decoded bands through the actual library composable. |
| `SubSamplingImageState.kt`, `SubSamplingImageSource.kt`, `internal/ImageRegionDecoder.kt` in the same module | `reader/VerticalReaderTiles.kt` implements a public custom source whose decoder delegates to the upstream file decoder and translates crop coordinates. |
| `internal/AndroidImageRegionDecoder.kt`, `internal/PooledAndroidImageRegionDecoder.kt`, `internal/ImageCache.kt` | Reuse the installed engine's EXIF orientation, sample size, prepared painters, pooling and tile lifetime; these algorithms are not copied or replaced. |
| `zoomable/src/commonMain/kotlin/me/saket/telephoto/zoomable/Zoomable.kt` | Disable pinch/quick/double-click handling for the bands. The existing outer vertical list and page tap remain the input owners. |

The full original page stays one LazyColumn item with the existing pixel resume cursor.
MangaFlow selects only the visible fixed bands and their immediate neighbors. Each band
presents its crop as a separate source, allowing the actual Telephoto engine to tile that
bounded region instead of treating an entire tall list item as a visible viewport.

Coil continues to own fetching and its bounded preview. The overlay pins the existing disk
snapshot or uses the downloaded file; it does not download another original or copy it to a
second cache. Unsupported region decoding leaves the preview visible. The adapter is app
glue over the public SDK, not an upstream source fork or an independently implemented decoder.

Reviewed by source and diff only. No local build, tests, device execution, frame-rate,
memory benchmark or visual-equivalence result is claimed. License URI:
`https://www.apache.org/licenses/LICENSE-2.0`.

## Guided panel camera · 2026-11-13

`reader/panels/PanelCamera.kt` keeps the same **0.16.0 / 2a5e5f5** image loader and
original-file region-decoding pipeline. Normalized detections select a camera pose;
the app does not crop, resample, save or replace the manga image.

Additional upstream sources read for this integration:

- `zoomable/ZoomableState.kt`, `RealZoomableState.kt`, `ZoomableContentTransformation.kt`,
  `ZoomableCoordinateSystem.kt`, `ZoomSpec.kt`, `spatial/CoordinateSystem.kt`,
  `spatial/SpatialOffset.kt`, `internal/RealZoomableCoordinateSystem.kt` and
  `internal/contentTransformation.kt` at `zoomable/src/commonMain/kotlin/me/saket/telephoto/`.
- `ZoomableImage.kt`, `ZoomableImageState.kt` in `zoomable-image/core/src/main/kotlin/`;
  the Coil 3 `ZoomableAsyncImage.kt`; and `SubSamplingImageState.kt` in the Android
  sub-sampling module.

The public 0.16 API uses **absolute content scale** for `zoomTo`, supports `panBy`,
and exposes coordinate conversion under `ExperimentalTelephotoApi`. This adapter
does not use the newer `ZoomFocalPoint` API or upgrade the existing reader dependency.
Painter previews and the full-file decoder have different unscaled coordinate frames.
After each source/viewport geometry change the app records complete source bounds at
Fit, then converts normalized detections into that frame. A finite 320 ms camera job
interpolates scale and centre together through the public snap zoom/pan methods.
Retargeting cancels the old job; direct gestures cancel it without moving the next target.
Reduced-motion mode applies the final pose immediately. Telephoto still owns actual
tile selection, decoding, bounds constraints, pinch zoom and double-tap zoom.

Initial-pass single-finger horizontal swipe ownership avoids simultaneously panning
the image and turning a panel. Two-finger gestures remain with Telephoto. Overview
outlines and hit tests use the same current source-to-viewport mapping as the camera.
The finite camera interpolation and panel input routing are MangaFlow integration glue,
not copied upstream algorithms or claims of upstream visual equivalence. Only source
and diff review was performed; no tests, local build or device verification was run.
