# Unified Chapters body source adapters

The source adapters are small Kotlin consumers of public source protocols. They
do not load third-party scripts, APKs, remote code, account cookies or private tokens.
Mihon APK compatibility is not implemented by these adapters.

## Komiic

- Upstream reference: Keiyoushi/extensions-source
- Fixed commit: `c3bc55d548b0a5f1b316a323bde9e6e2516e4bea`
- License: Apache-2.0, as declared by the upstream LICENSE at that commit.
- Read files: `src/zh/komiic/src/eu/kanade/tachiyomi/extension/zh/komiic/Komiic.kt`,
  `Entity.kt`, `Queries.kt`, and `Payload.kt`.
- Original source:
  https://github.com/keiyoushi/extensions-source/tree/c3bc55d548b0a5f1b316a323bde9e6e2516e4bea/src/zh/komiic
- Local file: `data/sources/KomiicBodySource.kt`.

The GraphQL listing/search, comicById, chaptersByComicId and imagesByChapterId
request shapes, book/chapter distinction, image kid construction and chapter
Referer behavior are adapted from the upstream implementation. This is a modified
MangaFlow implementation using its existing OkHttp/coroutine transport, JSON parser,
stable source-qualified IDs and Unified Chapters models. Source-auth refresh,
mirrors, Cloudflare handling and source preference/extension UI are not copied.
Anonymous access and image quotas remain source-controlled; refusal is reported
without credential replay or identity changes.

## MangaDex

- API documentation reference: https://api.mangadex.org/docs/
- Companion implementation read: Venera's `manga_dex.js` at
  `venera-app/venera-configs@a04e05591f7832f7166d6660546613c114d6738a`.
- https://github.com/venera-app/venera-configs/blob/a04e05591f7832f7166d6660546613c114d6738a/manga_dex.js
- Local file: `data/sources/MangaDexBodySource.kt`.

The Venera script was read as an API-contract cross-check. No script text or
runtime is incorporated: that source repository did not expose a project LICENSE
in this read. The Kotlin adapter uses standard MangaDex manga/feed/chapter/At-home
JSON requests. It keeps translated languages, scanlator and volume information;
walks complete feed pages within an explicit bound; excludes external-only,
unavailable and zero-page records; checks chapter ownership; and requests original
At-home images. There is no automatic compression fallback or silent language
switch. Anonymous login/quotas and API access policies remain source-controlled.

## Additional Chinese sources · 2026-11-13

Baozi and Manhuagui protocol adaptations use Keiyoushi/extensions-source commit
`fc7a827769f612dfa6dccb51e819f5b4eecaeab5` (Apache-2.0):

- `src/zh/baozimanhua/src/eu/kanade/tachiyomi/extension/zh/baozimanhua/Baozi.kt`:
  public app-reader HTML, card/onclick links, chapter slots and CDN routing.
  Local file `data/sources/BaoziBodySource.kt`. Gatekeeper solving, cookies,
  banner cropping/recompression and image-response interceptors are not copied.
- `src/zh/manhuagui/src/eu/kanade/tachiyomi/extension/zh/manhuagui/Manhuagui.kt`
  and `Dto.kt`, plus `lib/unpacker` (`Unpacker.kt`, `SubstringExtractor.kt`)
  and `lib/lzstring` (`LZString.kt`): public search/catalogue/reader payloads.
  Local files `ManhuaguiBodySource.kt`, `ManhuaguiPayload.kt` and
  `SourcePackedScript.kt` in `data/sources`. Text decoding is bounded; scripts
  are never executed. Hidden/adult unlock cookies and account requests are omitted.
- `src/zh/komiic/build.gradle.kts` / `Komiic.kt`: declared .com/.cc origin choice
  and matching subdomain routing, adapted in `KomiicBodySource.kt`.

MangaBZ uses Keiyoushi commit `535ffdbdd84eadab593d515283cb82aa9bf177f5`,
`src/zh/mangabz` (`Mangabz.kt`, build metadata) and its shared MangaBZ theme
(`MangabzTheme.kt`, `Preferences.kt`).
Local `data/sources/MangaBzBodySource.kt` reuses `SourcePackedScript`, validates
chapter membership, collects the complete original-image manifest, retains signed
parameters and has bounded caches/rate/concurrency. HTTP mirrors are omitted.

These are modified MangaFlow integrations with its existing cancellable transport,
Jsoup/JSON, stable identities and Unified Chapters, not the upstream extension runtime.
See the complete Apache-2.0 text in `KEIYOUSHI-APACHE-2.0.txt`.

## Four anonymous Chinese source integrations · 2026-11-16

The user explicitly requested all four researched sources, with anonymous access
first. The following public protocol/parser implementations are adapted from
Keiyoushi/extensions-source at `9080fbfd1d02ce2cad0f7466996fa19816f39401`,
under its Apache-2.0 LICENSE (the complete text is already included in this folder):

- `src/zh/guazimanhua/.../Guazimanhua.kt` and build metadata become
  `data/sources/GuaziBodySource.kt`: desktop-UA public HTML listing/search,
  complete chapter selector and original reader images.
- `lib-multisrc/dm5/.../Dm5.kt` supplies the public web parsing/image-request
  contract adapted in `data/sources/ManhuarenBodySource.kt`. Venera's
  `manhuaren.js` at `a04e05591f7832f7166d6660546613c114d6738a` was read only
  as an independently implemented public-protocol cross-check; its repository
  had no project LICENSE, and no script text/runtime is incorporated.
- `lib-multisrc/mmlook/.../MMLook.kt` and `PageDecrypt.kt`, plus the
  `src/zh/rumanhua` build metadata, become `RumanhuaBodySource.kt` and
  `RumanhuaPayload.kt`: public form search, morechapter completion and bounded
  Packer/Base64/XOR/Base64/JSON manifest decoding. Upstream hostname-verifier
  bypass and HTTP URL workarounds are deliberately omitted.
- `src/zh/zaimanhua/.../Zaimanhua.kt`, `ZaimanhuaDto.kt` and build metadata
  become `ZaimanhuaBodySource.kt`: public updates/search, pc grouped catalogue,
  h5 chapter permission/HD-image fields. Account login, token refresh, favorites,
  hidden-catalogue unlocking and comment-image generation are omitted.

These modifications integrate the current shared SourceHttp/route authority,
SourceKeys, Unified Chapters, reader/download original-image flow, application
source registry and explicit per-source diagnostics. Parsing, page/group counts,
request starts and concurrent metadata requests remain bounded and cancellable.
Normal HTTPS verification is required, and absent/blocked/limited content never
becomes an empty successful catalogue or saved chapter. Source access and free
chapter scope remain controlled by the actual providers.

No new runtime, APK extension, original comic assets or source accounts are bundled.
The adapters compile as commonMain Kotlin for Android, Windows and iOS. Source
review/ordinary CI activation is distinct from real source availability, certificate
compatibility, complete image transfer, installed UI or mainland speed verification.

## Historical verification boundary · 2026-11-13

The upstream sources and JSON contracts were read on 2026-11-13. No source was
installed or run, no product API was probed, and no test or local build was run.
These are implemented source adapters, not evidence that the sites currently work
on the user's network. They introduce no bundled model or native runtime.
