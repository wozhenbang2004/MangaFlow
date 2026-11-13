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

## Verification boundary

The upstream sources and JSON contracts were read on 2026-11-13. No source was
installed or run, no product API was probed, and no test or local build was run.
These are implemented source adapters, not evidence that the sites currently work
on the user's network. They introduce no bundled model or native runtime.
