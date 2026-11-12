# Haze Glass optical adaptation

Upstream: chrisbanes/haze, Haze Glass 2.1.0, fixed commit
`ca21ff2060e52e1882afdb94f9b055f7fc834bd9`, Apache-2.0.

Reference: the Apple Regular/Clear images and Haze author Brush/Stroke frame in
the existing unified glass archive, Space `page_c4faa81b88cc8191b00cbe8fd345bedf`.
The native images were opened during this change; no generated substitute is used.
Those references do not establish acceptance of this Android implementation.

| Upstream file / mechanism | Android adaptation |
| --- | --- |
| `haze-glass/src/commonMain/kotlin/dev/chrisbanes/haze/glass/GlassShaders.kt`: `buildOptical`, SDF/gradient, surface, edge displacement, colour helpers | `ui/glass/HazeGlassShaders.kt`: one rounded rectangle, Circle lighting profile, Edge refraction and simple chroma. Retains finite-width exponential edge decay, corner normals, bounded displacement, Fresnel, premultiplied alpha and gamut-bounded linear-sRGB chroma. Fixed zero-fold omits its unused uniforms/helpers. |
| Same file: `buildRim` / interaction-light composition | A normal-lit direct-draw rim replaces the former painted stroke. The optical stage includes the localized white-light blend; the rim's light moves from an authored upper-left position towards the pointer. Interaction refraction increases by at most 15%; existing application motion controls its duration. |
| `GlassOptics.kt`, `GlassDefaults.kt`, `GlassStyle.kt`: Regular / Clear | `GlassMaterial.kt`: responsive Regular 20–25dp blur and .65–1 depth; Clear 1.25dp blur, 28dp edge and 56dp maximum displacement. Reading panels reduce refraction and add a small theme tint. Values are from actual source, not measured screenshot pixels. |
| `GlassRenderEffectFactory.android.kt`: depth input | `HazeGlassOptics.kt`: blur plus alpha-only sharp/blur mixing with PLUS, followed by the optical effect. Native Android Gaussian blur replaces the upstream semantic blur; this is a material difference. Shader programs compile once, per-surface effect/depth results are cached, and uniform updates occur immediately before effect creation or the rim draw. |

`LiquidGlassSurface` keeps the existing Haze 1.6.0 transport at zero blur for liquid.
Only its background child expands by the native blur kernel and bounded optical
travel, at a negative placement offset. The shader receives that padding as its
material origin; the parent keeps the original size, rounded clip, hit targets and
foreground. Real backdrop colours enter the optical stage, never text or icons.
Haze still owns source/window coordinates and never captures the glass itself.
The app's prior contrast-bounded readability response is retained through optical
white-point adaptation: Clear controls lift dark artwork for dark ink, and dark
appearance lowers bright artwork for white ink. The response follows sampled
luminance, never a coordinate-shaped white inner plate. These are application
coefficients, not measured accessibility or visual-equivalence results.

This replaces `BackdropGlassOptics.kt` / `BackdropShaders.kt`; Backdrop's licensed
soft-shadow adaptation remains. No second optical engine, screenshot readback,
new dependency or toolchain upgrade is introduced. The full Haze Glass 2.1.0
runtime, merged material bridges, semantic blur and separate
sharp edge-detail pass are not included. The existing skin switch and readable
frosted/software/unsupported/no-source behavior remain.

Source and diff review only. No tests, local build, device automation or measured
GPU/visual equivalence. Apple quality remains an open user-experience criterion;
CI compilation is separate from device appearance and user acceptance.
