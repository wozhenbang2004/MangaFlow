# Optional scene fonts

## Font selection UI · 2026-11-14

FONT-PICKER-20261114 adapts the existing FontChoiceRow/SettingsDisclosure/PANEL, the app's Ovio MotionTabs (source reference81a1bcb in MotionTabs.kt), keyed MotionTabContent and compact real download feedback. One role is shown at a time; real font samples lead, names/status follow and one trailing slot owns download/cancel/More. Management and original licensing disclose on demand. Row selection/long-press semantics, outgoing input gates, large-text control sizing and reduced motion remain. No new font assets, catalog choice, font-store operation, platform loader or dependency is introduced. Original before-state screenshot1000121663.jpg is observed; the new artifact's visual/motion/device result remains pending user feedback.

The selector retains MangaFlow's existing SettingsDisclosure/PANEL, motionPress and MotionIconButton controls. Four roles remain independently system-default: interface, work titles, prose, and numbers. Follow-interface remains an explicit choice outside the interface role. Optional fonts never rewrite other role selections or original manga image pixels.

## Current direction · 2026-11-14

The user rejected the earlier classic Chinese choices: “我不想弄这些很经典的中文字体啊。。什么思源文楷”. Noto Sans SC, Noto Serif SC and LXGW WenKai are retired rather than silently renamed as a new face. Existing selections normalize to the role's system default; root's store migration owns preference/cache handling.

HarmonyOS Sans is the modern, restrained interface/prose option. The download includes the actual Regular400, Medium500 and Bold700 files, preserving meaningful interface hierarchy without claiming a nonexistent variable file. Standalone SC files in the official OpenHarmony tree are separate faces; the package is24,647,200 bytes. These files remain unmodified and are loaded privately as optional application assets; they are not offered for font export or as standalone fonts. The font's full original license and copyright remain readable offline.

The approved collection's 站酷庆科黄油体 provides the expressive alternative only for actual book titles. Its geometric, rounded design should not replace dense controls or long comments; the complete file is8,328,684 bytes and has a single genuine400 face. Inter and Geist continue to serve only numeric runs. This is project selection, not a claim of new user aesthetic approval or device rendering acceptance.

Reference collection and coverage boundary: https://chatgpt.com/space/page_e4d707779d6081919403cd3031586676 . The old250-character preview files are never deployed as full fonts. MiSans and Smiley Sans remain potential comparisons; no unverified mirror, ZIP extraction capability or incomplete specimen is introduced.

## Fixed original files and integrity

OpenHarmony distribution commit:0d79cad76b37ba0f3ccb1323c83833c78e0860de; Google Fonts distribution commit:29b4ec6df2522a8aee279414b78a07910b065874.

| ID / face | Complete upstream file | Bytes | Git blob SHA1 |
| --- | --- | ---: | --- |
| harmony_sans_sc /400 | fonts/HarmonyOS_Sans_SC_Regular.ttf |8261060|25069dad0a3ec40ba9378cd201a34a687ce52047|
| harmony_sans_sc /500 | fonts/HarmonyOS_Sans_SC_Medium.ttf |8227216|6814ccec713b87f623785e58c2b47b609114df8a|
| harmony_sans_sc /700 | fonts/HarmonyOS_Sans_SC_Bold.ttf |8158924|534ddf9a7e8e89af55c161e3f8c3b081c3d91a5b|
| zcool_huangyou /400 | ofl/zcoolqingkehuangyou/ZCOOLQingKeHuangYou-Regular.ttf |8328684|982a3b80143268592f573feb196791fbc2330e17|
| inter / variable | ofl/inter/Inter[opsz,wght].ttf |876576|047c92f6e2212473dc436020afed689527076d44|
| geist / variable | ofl/geist/Geist[wght].ttf |169056|f63f0afc6390323715972b6645115485f37cc9f4|

Git blob hashes include the ASCII prefix `blob <exact bytes>\0` followed by raw bytes, not raw-file SHA1. The app verifies the complete original file's length, header, Git digest and platform parsing before publication. Catalog download URLs are fixed to the above commits; every file has its supported-size raw/jsDelivr alternative. No font binary is included in the application packages or repository change.

License sources, retained complete in MangaFontLicenses.kt:
- https://github.com/openharmony/resources/blob/0d79cad76b37ba0f3ccb1323c83833c78e0860de/LICENSE_Fonts (blob64ab8ad15b8088166fdd420716ca9f8238bb16fc): HarmonyOS Sans Fonts License Agreement; original copyright/notice and use attribution are shown with the font selector and its offline license.
- https://github.com/google/fonts/blob/29b4ec6df2522a8aee279414b78a07910b065874/ofl/zcoolqingkehuangyou/OFL.txt (blobbd3df5cf6f237a4afaea70106f8243da07cd8cd8): SIL OFL1.1, copyright2018 ZCOOL QingKe HuangYou Project Authors.
- Inter and Geist retain their unchanged original SIL OFL1.1 notices.

All downloads remain explicit user choices. Missing/failed fonts retain readable system text and a visible retry status. Glyph coverage beyond the actual SC font and the original title font may require platform fallback; the numeric-only Latin choices do not claim Chinese coverage.

## Platform implementation

Uses the existing public APIs of Compose1.12.2 without a dependency/runtime change:
- https://github.com/JetBrains/compose-multiplatform-core/blob/v1.12.2/compose/ui/ui-text/src/androidMain/kotlin/androidx/compose/ui/text/font/AndroidFont.android.kt
- https://github.com/JetBrains/compose-multiplatform-core/blob/v1.12.2/compose/ui/ui-text/src/skikoMain/kotlin/androidx/compose/ui/text/platform/PlatformFont.skiko.kt
- https://github.com/JetBrains/compose-multiplatform-core/blob/v1.12.2/compose/ui/ui-text/src/desktopMain/kotlin/androidx/compose/ui/text/platform/DesktopFont.desktop.kt
- https://github.com/JetBrains/compose-multiplatform-core/blob/v1.12.2/compose/ui/ui-text/src/nativeMain/kotlin/androidx/compose/ui/text/platform/NativeFont.native.kt

Android preloads Typefaces in the background and wraps AndroidFont. NonAndroid loads verified bytes. Fixed HarmonyOS Sans faces declare their actual400/500/700 weights; the title face is400 only, and Inter/Geist keep genuine variable-axis requests. Cache reads avoid disk/network work on the UI thread. System fallback remains available at launch, after failure or when files are lost.

Existing bounded NetworkClient/StorageFile transport, one IO command owner and stale-attempt guards continue to own download/cancel/delete/publication. No tests, local builds or product/device probes were performed. Exact upstream metadata and source changes do not establish real downloads, glyph rendering, phone memory use or Android/Windows/iOS appearance; these await current-build user feedback.
