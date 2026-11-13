# Optional scene fonts

The font selector adapts MangaFlow's existing SettingsDisclosure/PANEL, motionPress and MotionIconButton controls. The role/candidate model follows the approved18-family collection and the actual typography system at https://chatgpt.com/space/page_e4d707779d6081919403cd3031586676 . Preview subsets are never shipped as full fonts.

## Fixed files and integrity

Google Fonts distribution commit:29b4ec6df2522a8aee279414b78a07910b065874.

| ID | Full file | Bytes | Git blob SHA1 |
| --- | --- | ---: | --- |
| noto_sans_sc | ofl/notosanssc/NotoSansSC[wght].ttf |17772300|fb0637bafbcd804fe32152370a1225990745b4bc|
| noto_serif_sc | ofl/notoserifsc/NotoSerifSC[wght].ttf |25125512|eab063faf229160a52d3760f5555150e4eb9e5bf|
| inter | ofl/inter/Inter[opsz,wght].ttf |876576|047c92f6e2212473dc436020afed689527076d44|
| geist | ofl/geist/Geist[wght].ttf |169056|f63f0afc6390323715972b6645115485f37cc9f4|
| wenkai | lxgw/LxgwWenKai@d20c88d9ed1570a54ef09b46f37724712bbe0203 / fonts/TTF/LXGWWenKai-Regular.ttf |25928940|7f8f031dc5c6c5ba1913d93c36d4667c1e311cea|

Git blob hashes include the UTF-8/ASCII prefix `blob <exact bytes>\0`, followed by raw bytes. They are not raw-file SHA1. Catalog URLs, source links and complete per-family original license/copyright text are in MangaFontCatalog.kt/MangaFontLicenses.kt. The app's inline permission disclosure exposes the entire notice offline. No font source/shape is modified; local filenames are safe aliases only. All five fonts retain SIL OFL1.1; WenKai's original reserved-name/additional permission header is preserved. No binary font is included in this repository change.

All choices are explicit user downloads. Missing fonts retain system text and visible retry status. The first four files are variable; WenKai's downloaded file is real Regular400 only. Numeric-only Inter/Geist choices do not imply CJK glyph coverage. MiSans was investigated as a comparison but is not offered without a verified pinned complete file.

## Platform implementation

Uses the actual public APIs of the project's Compose1.12.2, with no dependency change:
- https://github.com/JetBrains/compose-multiplatform-core/blob/v1.12.2/compose/ui/ui-text/src/androidMain/kotlin/androidx/compose/ui/text/font/AndroidFont.android.kt
- https://github.com/JetBrains/compose-multiplatform-core/blob/v1.12.2/compose/ui/ui-text/src/skikoMain/kotlin/androidx/compose/ui/text/platform/PlatformFont.skiko.kt
- https://github.com/JetBrains/compose-multiplatform-core/blob/v1.12.2/compose/ui/ui-text/src/desktopMain/kotlin/androidx/compose/ui/text/platform/DesktopFont.desktop.kt
- https://github.com/JetBrains/compose-multiplatform-core/blob/v1.12.2/compose/ui/ui-text/src/nativeMain/kotlin/androidx/compose/ui/text/platform/NativeFont.native.kt

Android preloads Typefaces in the background and wraps AndroidFont; nonAndroid loads verified bytes and declares Font using explicit variation settings. Variable400/500/600/700 are real weight-axis requests, not four declarations with unchanged underlying glyphs. The common cache supplies UI reads without disk/network work. System fallback remains available at first launch, after a failed download or when local files are lost. Interactive safety, icons and original manga image pixels remain under existing owners.

The transport uses existing bounded NetworkClient downloads and StorageFile atomic moves/private directories. One IO command owner publishes preferences/files/attempt completion, with stale-attempt guards. Normal source/CI evidence does not establish actual glyph rendering, downloaded assets or phone/Windows/iOS behavior; these await current-build device feedback. No tests/local builds/probes were run.
