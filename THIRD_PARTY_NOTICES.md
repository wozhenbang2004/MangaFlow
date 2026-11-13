# Third-party notices

## Author and tag search contracts

Public search option names and actual category identifiers were checked against
Venera's source configuration, fixed commit `a04e05591f7832f7166d6660546613c114d6738a`,
`https://github.com/venera-app/venera-configs/blob/a04e05591f7832f7166d6660546613c114d6738a/copy_manga.js`
(`category_param_dict`, `categoryComics`, `search.optionList`).
`data/SearchTags.kt` retains known source names/identifiers; current comic metadata
can supply additional paths. These are protocol metadata, not a bundled copy of
the upstream client implementation. The source adapter and bounded multi-tag
intersection are application code; source inspection is not a live-service result.

## Telephoto

Telephoto 0.16.0, copyright Saket Narayan, Apache License 2.0:
`https://github.com/saket/telephoto/tree/2a5e5f502605367c0f497bbbdc3804a15f5e2f74`
and `https://www.apache.org/licenses/LICENSE-2.0`.
The app uses the actual zoomable/sub-sampling libraries. Its vertical-band custom-source
adapter delegates region decoding, sampling, EXIF and pooling to that upstream engine.
Integration and source-reading boundaries are recorded in `third_party/telephoto/ADAPTATION.md`.

## Virtual bookshelf

The local collection's `ui/BookshelfBook.kt` adapts Petar Gyurov's
`virtual-bookshelf`, fixed commit `3c1073227df161d87eb48e7286bb7748cd22cd4d`:
`https://github.com/petargyurov/virtual-bookshelf/tree/3c1073227df161d87eb48e7286bb7748cd22cd4d`.

The upstream `.spine`, `.cover`, `.top`, vertical title and bottom-anchored
rotation/translation are adapted to Compose drawing and the app's existing motion.
The app uses real manga covers, stable book slots, direct reading and accessible
press/long-press actions; upstream random appearance and desktop-only hover are
removed. No upstream book data or cover artwork is bundled. This is a source
adaptation, not the original website running inside the app.

The original key source files, byte manifest, full **Unlicense** text and precise
adaptation map are retained in `third_party/virtual-bookshelf/`. No additional
runtime library is required. Source retention and review do not establish Android
compilation, appearance or user acceptance.

## Public rating and comment contracts · 2026-11-12

MangaFlow's additional adapters are independently implemented against public documentation
and readable protocol/model definitions. No third-party client implementation or user review
text is bundled. Retrieved content keeps its source, author and original identifiers; these
references do not grant a new license over upstream users' content.

- MangaUpdates: official documentation `https://mangaupdates.apidocumentation.com/`.
  The v1 series/comments/reviews field shapes were also checked in the non-official SDK's
  saved OpenAPI specification, `https://github.com/FiliArrochada/MangaUpdates.NET/blob/fd4d68c0b0d01bdcbf1dcc26f838b051c1db6f96/api/openapi-1.0.0.yaml`.
  That snapshot is supporting protocol evidence, not an official SDK or a live-service result.
- Kitsu: official API documentation `https://kitsu.docs.apiary.io/` and server definitions
  `https://github.com/hummingbird-me/kitsu-server/tree/the-future` (`media_reaction_resource.rb`,
  `media_staff_resource.rb`, `mapping_resource.rb`, `jsonapi-resources.rb`). Current public
  host configuration: `https://github.com/hummingbird-me/kitsu-mobile/blob/2f2a5524c960cb6dcf6a0c7bbb1e12e0ff0e1213/src/config/kitsu.ts`.
- MyAnimeList through Tenrai: `https://api.tenrai.org/llms.txt`. Tenrai is a third-party beta
  transport, not an additional original rating site. The public interface needs no user key.
  MangaFlow does not use the retired public Jikan service or treat forum topic metadata as replies.
- MangaDex: official API documentation `https://api.mangadex.org/docs/` and OpenAPI
  `https://api.mangadex.org/docs/swagger.yaml`. This adapter uses manga identity and statistics;
  forum thread IDs and reply counts are not presented as fetched discussion bodies.
- Shikimori: official project definitions `https://github.com/shikimori/shikimori`, including
  `config/initializers/apipie.rb`, `app/graphql/queries/mangas_query.rb`,
  `app/graphql/types/concerns/db_entry_fields.rb`, `app/query_objects/comments/api_query.rb`
  and `app/serializers/comment_serializer.rb`. MangaFlow uses its own truthful User-Agent;
  it does not impersonate a registered application. Local comments are distinct from its
  MAL-imported score, which is not duplicated in the app's metrics.
- Dantotsu: public contributor integration documentation
  `https://github.com/itsmechinmoy/dantotsu-comment-db/blob/main/docs.html`
  (read document blob `653bcfc82315a9835150a58e656cac3336d8f9c3`) and the public client protocol
  at commit `c647f715dbfba08d0752cce33ed0b7b473067ba9`. This is contributor documentation,
  not a service-operator availability guarantee. The documented fixed public `appauth`
  handshake carries no MangaFlow user's account credential. Rejections and rate limits are
  returned normally; there is no key/identity rotation or private-service fallback.
- Tencent Comics: saved public search HTML and original adapter evidence at
  `https://github.com/Kinremtus/mediatracker/tree/a338f11d6e333d9bb2e0a65354a1a5d999ba6af4`
  (`tests/fixtures/acqq_search.html`, `src/services/external/official_acqq.rs`), and saved
  detail HTML `https://github.com/lidavidm/rssfly/blob/3266e4375ba931f4b8899826d324cd56a868b929/test_data/acqq.646277.html`.
  Search capture: October 2026; detail capture: January 2021. The independently written
  adapter uses complete titles, verified comic IDs, authors and the displayed vote count.
  It preserves the raw score without asserting an unverified maximum. No comment script,
  vote submission, account or alternate access route is used.
- Bahamut ACG: original metadata parser `https://github.com/XuHaoJun/gamer-crawler/blob/master/index.js`;
  native rating selectors documented by the original author at
  `https://gist.github.com/david082321/b5f998e609b3b4fb72c771a71763528c`;
  numeric identity/search address reference `https://www.wikidata.org/wiki/Property:P6367`.
  The manga platform and author labels are checked before retaining a 5-point average.
  Historical metadata selectors and public link formats are source evidence, not a current
  connectivity claim. No forum text or the reference script's alternative scoring formula is imported.
- Manhuagui: public reader configuration
  `https://github.com/venera-app/venera-configs/blob/a04e05591f7832f7166d6660546613c114d6738a/manhuagui.js`
  and original reader API implementation
  `https://github.com/Aoi-hosizora/manhuagui_api/tree/f6fe73d1f37d5d5b8b9881b92614a09b1319ab77`
  (`internal/service/search.go`, `author.go`, `manga.go`, `http.go` and `internal/pkg/static/url.go`).
  Only the documented read operation and actual five vote buckets are used. The adapter's
  calculated 10-point mean is explicitly labelled 投票均分; the displayed HTML placeholder
  is not a count. Voting, user cookies and private endpoints are excluded.
- MangaBaka: official static OpenAPI `https://mangabaka.org/api.json`, mapping guide
  `https://mangabaka.org/developers`, and attribution guidance
  `https://mangabaka.org/developers/concepts/license-and-attribution`.
  Only active comic titles, authors and supported source IDs assist identity matching;
  the adapter does not import rating copies, cover images or review bodies.

These additions were checked by reading contracts and implementation only. No current
service response, compilation, device appearance or runtime reliability is claimed from
that review. Existing license notices below remain unchanged.

## MangaBaka data attribution

Identity metadata is provided by MangaBaka and its contributors, `https://mangabaka.org`.
MangaBaka-original data is licensed under Creative Commons Attribution-NonCommercial-ShareAlike
4.0 International (CC BY-NC-SA 4.0), `https://creativecommons.org/licenses/by-nc-sa/4.0/`.
Data terms and the disclaimer of warranties: `https://mangabaka.org/about/data-license`.
MangaFlow selects identity and matching fields and converts identifier/display formats.
Linked original provider names remain attributed. It does not bundle or redistribute a database;
aggregate MangaBaka scores are not presented as an additional source score. Source content is supplied without warranties and no endorsement
of MangaFlow is implied. This notice also appears visibly in the project README.

Upstream AniList, MyAnimeList, Kitsu, MangaUpdates and Shikimori identifiers retain their
respective provider attribution and terms. MangaBaka's license does not sublicense their
content. Original scores and comments are read through those providers' own implemented
channels. The integration is for personal, non-commercial use; commercial use needs the
applicable separate rights. All attribution URIs remain plain text in the app and notices.

## Ovio avatar and Liquid Tabs patterns

The comment avatar's initials-under-photo fallback is adapted for Compose and Coil from
JanaSundar/ovio（`https://github.com/JanaSundar/ovio/blob/81a1bcb6737356038163728b3b2a657c9dcffa32/components/shared/avatar.tsx`）,
commit `81a1bcb6737356038163728b3b2a657c9dcffa32`.
The image is decorative beside its author's name, keeps the fallback on failure, and is loaded only with the visible comment.
The remaining comment layout belongs to MangaFlow.

The shared detail, discovery, local/cloud shelf, download, settings and reader selectors in `ui/motion/MotionTabs.kt`
and `ui/BrowseTabs.kt` are adapted from the same fixed Ovio version:
`components/ovio/liquid-tabs/use-liquid.ts` and
`components/ovio/liquid-tabs/worlds/minimal.tsx`.
The Compose port preserves independently animated indicator edges and stationary labels,
uses Android's animation clock and system duration scale, and omits the web deployment demo.

MIT License

Copyright (c) 2026 Jana Sundar

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## beUI motion roles and panel presence

Shared press, icon-swap and panel motion in `ui/motion/MangaMotion.kt` is adapted from
beUI / starc007/ui-components（`https://github.com/starc007/ui-components/tree/a866cb2f37ce68c3fc0c66425e86e3f31566e4be`）,
commit `a866cb2f37ce68c3fc0c66425e86e3f31566e4be`:
`lib/ease.ts`, `lib/presence-gate.tsx` and `components/motion/morphing-modal.tsx`.
The Compose adaptation converts the source spring stiffness, damping and mass to
unit-mass stiffness and damping ratio, keeps outgoing visuals separate from active
input and focus, and retains panels until their exit finishes. Android owns the
animation clock, dialog window, semantics and system motion preference. The web
runtime and full-screen blur are not included.

The root-level discovery search in `ui/ExpandingSearchBar.kt` also adapts that version's
`components/motion/morphing-search.tsx` and `components/motion/popover-morph.tsx`.
It retains the shared shell, fixed-size foreground, independent reveal, end-aligned anchor
and exit presence. The Android adaptation opens leftward from the existing 48dp icon slot,
adds a short leading icon response, uses an approximate unit-mass counterpart of the
source's 580ms/bounce-0.22 shell spring and its 320ms foreground easing, and joins real
query results inside the same shell. Haze and the existing optical layer replace the web
backdrop filter; the upstream input divider is omitted. This is not a pixel-identical
port or evidence of device appearance, timing or frame rate.

MIT License

Copyright (c) 2026 Saurabh Chauhan

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## Vaul drawer background relationship

The root navigation drawer's progress-coupled background transformation in
`ui/MangaFlowApp.kt` is adapted from
emilkowalski/vaul（`https://github.com/emilkowalski/vaul/tree/3e97aac6a38e4481bade71d7233ed6002e80f9b0`）,
commit `3e97aac6a38e4481bade71d7233ed6002e80f9b0` (version 1.1.2):
`src/use-scale-background.ts` and the drag/background mapping in `src/index.tsx`.
The Compose adaptation follows the actual side-drawer offset in a drawing layer,
with smaller scale, translation and corner changes suited to MangaFlow. Material's
existing drawer gestures and dismissal remain in control; the web scroll locking,
velocity thresholds and React runtime are not included.

MIT License

Copyright (c) 2023 Emil Kowalski

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## AndroidX bottom pull continuation

The bottom loading gesture in `ui/motion/BottomPullLoad.kt` is adapted from
AndroidX Material3 `1.3.2`, `commonMain/androidx/compose/material3/pulltorefresh/PullToRefresh.kt`,
in the published source archive（`https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3/1.3.2/material3-1.3.2-sources.jar`）.
The source file SHA-256 is `a46039a6805195abfc47accc103f1cf254ae451524f2e5eeb4dc4ee57479335d`.
Copyright 2023 The Android Open Source Project, licensed under Apache-2.0.
MangaFlow reverses the drag direction for the bottom edge and fills a fixed footer ring directly
from unused drag distance. A full ring arms the next-page action; a real pointer release commits it.
A non-consuming Initial-pass observer distinguishes release from Compose's consumed synthetic
cancellation event; fling alone never requests content. Foundation `1.8.2` owns scrolling and
gesture arbitration. Pointer lifecycle references: AndroidX `SuspendingPointerInputFilter.kt`
（`https://android.googlesource.com/platform/frameworks/support/+/5ef07889c9bc26965e52670d92c3560b3bf07c7c/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/input/pointer/SuspendingPointerInputFilter.kt`）
and the Foundation gesture API（`https://developer.android.com/reference/kotlin/androidx/compose/foundation/gestures/package-summary`）.
The source header is retained. The complete Apache-2.0 text is already bundled in
`app/src/main/assets/licenses/Mihon-APACHE-2.0.txt`.

## Mihon compact manga header

The compact horizontal cover/information composition in `CompactBookHeader` is adapted from
MangaAndSourceTitlesSmall（`https://github.com/mihonapp/mihon/blob/524fa53043f2f5b2321ea13027e44f093ab4245c/app/src/main/java/eu/kanade/presentation/manga/components/MangaInfoHeader.kt`）,
Mihon commit `524fa53043f2f5b2321ea13027e44f093ab4245c`, licensed under Apache-2.0.
MangaFlow changes the cover dimensions, text limits and spacing, reuses its own image requests,
and places metadata inside its bounded detail layout. It does not include Mihon's navigation,
source APIs, search callbacks, long-scroll description or application-specific types.
The complete license is included in `app/src/main/assets/licenses/Mihon-APACHE-2.0.txt`.

## BoardUI pagination

The controlled chapter pager is adapted from BoardUI Pagination（`https://www.boardui.com/zh-hans/components/pagination`）,
`components/base/pagination/pagination.tsx`, the source saved on 2026-11-01
(SHA256 `47fbc308182f5ad32415f6c51d917241db02a9b340c6f2fb536554ce784b33ac`).
MangaFlow keeps controlled page selection, disabled first/last boundaries and compact arrows;
48dp touch targets and a chapter-range sheet replace the desktop numeric page window.

MIT License

Copyright (c) 2026 Mertcan Dundar Esmergul (BoardUI)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## Haze 2.1 runtime

MangaFlow uses the complete official Haze 2.1.0 runtime from Maven Central:
core, Blur, Glass, both Material3 adapters and the material-preset module.
Upstream: `https://github.com/chrisbanes/haze/tree/2.1.0`, source commit
`ca21ff2060e52e1882afdb94f9b055f7fc834bd9`.

Christopher Banes and the Haze project contributors, Apache-2.0.
The complete license remains bundled as
`app/src/main/assets/licenses/Haze-APACHE-2.0.txt`.

The official library owns source transport, typed inputs/retention/sharing,
semantic blur and progressive effects, full Glass optics, detail/rim stages,
container fields and member transitions, interaction, accessibility, fidelity,
native-window eligibility and supported-platform fallback. Application wrappers
provide role/style/layout and interaction-source policies. The copied 1.6-backed
optical renderer and limited two-member field have been removed; the former
adaptation record is historical at `third_party/haze-glass/ADAPTATION.md`.
The separately attributed soft-shadow adaptation below remains.

This is Haze's renderer, not Apple's renderer. Native window-backdrop eligibility
requires a supported Android 37.2+ hardware window; other supported devices use
Haze's official source renderer. Source integration and CI are separate from
measured device appearance/performance and user acceptance.

## Backdrop soft shadows

`ui/glass/BackdropSoftShadow.kt` adapts Kyant0/AndroidLiquidGlass (Backdrop) 2.0.1,
fixed commit `65ab177e90e5c1d8c62e70cf7755841982da65f6`:
`backdrop/src/commonMain/kotlin/com/kyant/backdrop/shadow/ShadowModifier.kt`,
`shadow/Shadow.kt` and `backdrop/src/androidMain/kotlin/com/kyant/backdrop/internal/Paint.kt`.
Copyright 2025 Kyant, Apache-2.0. The complete license remains bundled as
`app/src/main/assets/licenses/Backdrop-APACHE-2.0.txt`.

The adaptation retains the blurred outline and clear-centre mask, substitutes a
bounded cached software A8 image for the upstream graphics layer and uses the
supplied Compose Shape and two quiet shadow presets. Only the shadow mask uses
this software path; backdrop capture and glass remain accelerated.


## Source marks

AniList, MyAnimeList and Kitsu vector path data comes from Simple Icons,
commit `cd796c98565fc466923fc1128b962e133fa3c612`, `icons/anilist.svg`,
`icons/myanimelist.svg` and `icons/kitsu.svg`. Original paths are retained in
`drawable/ic_source_*.xml`; colors are applied by the UI. The complete CC0 1.0
text is bundled as `licenses/SimpleIcons-CC0.txt`. Other site labels use plain
short wordmarks and are not claimed to be original brand logos.
Source: `https://github.com/simple-icons/simple-icons/tree/cd796c98565fc466923fc1128b962e133fa3c612`.
