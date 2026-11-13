# Haze Glass optical adaptation

Upstream: chrisbanes/haze, Haze Glass 2.1.0, fixed commit
`ca21ff2060e52e1882afdb94f9b055f7fc834bd9`, Apache-2.0.

Reference: the Apple Regular/Clear images and Haze author Brush/Stroke frame in
the existing unified glass archive, Space `page_c4faa81b88cc8191b00cbe8fd345bedf`.
The native images were opened during this change; no generated substitute is used.
Those references do not establish acceptance of this Android implementation.

| Upstream file / mechanism | Android adaptation |
| --- | --- |
| `haze-glass/src/commonMain/kotlin/dev/chrisbanes/haze/glass/GlassShaders.kt`: `buildOptical`, SDF/gradient, surface, edge displacement, colour helpers | `ui/glass/HazeGlassShaders.kt`: rounded rectangles, Circle lighting profile, edge refraction and simple chroma. Retains finite-width exponential edge decay, corner normals, bounded displacement, Fresnel, premultiplied alpha and gamut-bounded linear-sRGB chroma. Fixed zero-fold omits unused helpers. |
| Same file: `buildRim` / interaction-light composition | Rim and localized light now compose inside the optical shader, sharing its captured environment and geometry. No independently painted stroke or rim layer. Interaction refraction increases by at most 15%; existing finite app motion controls its duration. |
| Same file: merged field `smoothMin`, `memberSd`, `shapeSd`; `HazeGlassContainer.kt`: shared material surface | A bounded two-member field for the discovery browse/actions pair. Actual child measurement publishes member positions before drawing; one capture drives coverage, smooth-union normals, lens and light. At rest smoothing is zero; touch can smooth nearby members over at most 24dp. The separate navigation button is excluded. Software fallback uses the two rounded paths. This is not the full container registration/ghost/transition runtime. |
| `GlassOptics.kt`, `GlassDefaults.kt`, `GlassStyle.kt`: Regular / Clear | `GlassMaterial.kt`: application-tuned Regular 12–20dp diffusion and .78–1 depth; compact Clear 1.25dp at rest, bounded 20dp displacement and zero dispersion. Existing search/selector expansion controls optical thickness. Frosted roles use native diffusion and zero lens, ambient/specular response. Values are adaptation decisions, not measured Apple screenshot pixels. |
| `GlassRenderEffectFactory.android.kt`: depth input | `HazeGlassOptics.kt`: native blur plus alpha-only sharp/blur mixing with PLUS, followed by optics. Native Android Gaussian blur replaces upstream semantic blur. Shader support is validated once; each surface owns mutable uniforms and cached effect/depth input. Frosted input is already blurred by Haze and is not blurred a second time. |
| `GlassInteraction.kt`: finite light/refraction response and reduced motion | Existing non-consuming pointer ownership drives local light plus a window-local nearby light event. No per-frame coroutine or idle loop; release retains only its point for the finite fade. Zero-strength or distant points are excluded from the effect cache key. |

`LiquidGlassSurface` keeps the existing Haze 1.6.0 transport at zero blur for liquid.
Only its background child expands by the native blur kernel and bounded optical
travel, at a negative placement offset. The shader receives that padding as its
material origin; the parent keeps the original size, rounded clip, hit targets and
foreground. Real backdrop colours enter the optical stage, never text or icons.
Haze still owns source/window coordinates and never captures the glass itself.
The persistent selector and shared navigation button retain the compact 44dp
material reference. Fixed maximum capture padding stays unchanged during morph;
the selector's existing expansion progress supplies the authored thickness delta.
The application environment field samples five spatial positions in the captured
input, weights the centre, and derives luminance/coarse variation on the GPU.
Bounded white-point protection, tint, chroma and rim shading use that same field.
Foreground polarity stays stable; no CPU pixel readback or coordinate-shaped
white inner plate is introduced. These are application coefficients, not measured
accessibility or visual-equivalence results. The complete page's wallpaper and
base are captured together, including account/settings and detail comment filters.

This replaces `BackdropGlassOptics.kt` / `BackdropShaders.kt`; Backdrop's licensed
soft-shadow adaptation remains. No second optical engine, screenshot readback,
new dependency or toolchain upgrade is introduced. The full Haze Glass 2.1.0
runtime, arbitrary-member registration/transitions, semantic blur and separate
sharp edge-detail pass are not included. The app uses fixed role materials without
a skin switch. Readable frosted/software/unsupported/no-source behavior remains;
old-device tint is protected independently from the adaptive path.

Source and diff review only. No tests, local build, device automation or measured
GPU/visual equivalence. Apple quality remains an open user-experience criterion;
CI compilation is separate from device appearance and user acceptance.
