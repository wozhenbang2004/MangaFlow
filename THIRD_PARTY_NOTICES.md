# Third-party notices

## Local PDF import and LAN reading synchronization (2026-11-22)

- Windows PDF import uses Apache PDFBox 3.0.8 and Apache JBIG2 ImageIO 3.0.5, licensed under Apache-2.0 with their bundled notices. The exact upstream license and notice texts are retained in `third_party/pdf-import/`. Sources: https://github.com/apache/pdfbox/tree/3.0.8 and https://github.com/apache/pdfbox-jbig2/tree/3.0.5. No JAI JPEG2000 codec is added; an unsupported image decoder fails the import explicitly. Android uses the system `android.graphics.pdf.PdfRenderer` API.
- Windows service discovery uses JmDNS 3.6.5 (tag `v3.6.5`), Apache-2.0. Its exact license and notice are retained in `third_party/lan-sync/`. Source: https://github.com/jmdns/jmdns/tree/v3.6.5. Android uses system NSD. TLS and certificate generation use the existing OkHttp 5.5.1 family with `okhttp-tls` (Apache-2.0) and the platform TLS implementation; MangaFlow's reading-data protocol and paired-device UI are project code.
- Bangumi manga collection and AniList manga-list integration implement the official API contracts at https://github.com/bangumi/api and https://docs.anilist.co/. Service access, users' authorization and catalogue identity confirmation remain separate from this software's licensing. No third-party application's client ID or secret is bundled.

## Optional image and video enhancement engines (2026-11-19)

The Enhancement Center invokes optional external engine executables; their source
code and weights are not embedded in this application by this integration.
Explicit component installation downloads the complete fixed upstream archive,
checks its SHA-256, and preserves its bundled notices. The upstream setup command
then prepares the platform-specific runtime and models under their respective
terms. A model being installed is not proof of device compatibility or visual
qualification.

- **MangaJaNaiCLI 0.9.3** — GPL-3.0; the-database. Source:
  https://github.com/the-database/MangaJaNaiCLI/tree/901322ae449c6fea82427293ab702aaa7a762dd1 .
  Fixed `mangajanai-windows-x64.zip` SHA-256:
  `c1c5396945557fdea0263510a693ed3e976602a7c93ea66a33491463dc747ae5`.
  MangaFlow uses its documented image, model, scale, tile and progress interface.
- **VideoJaNai 2.14.1 CLI** — GPL-3.0; the-database. Source:
  https://github.com/the-database/VideoJaNai/tree/d57f2575a7e53e51a1b0778f307c89f0c4ebcd43 .
  Fixed `videojanai-windows-x64.zip` SHA-256:
  `a362daaaaf380cd70b65cfcbd86449f03fde8e606bc251513eaae699747389ec`.
  MangaFlow invokes actual super-resolution, RIFE and encoding operations in an
  owned process. No JaNai native implementation is copied into the Kotlin adapter.
- **FFmpeg / ffprobe, Gyan Windows shared full build 9.0.2** — optional external
  distribution with its complete upstream component notices; do not describe the
  full build as exclusively LGPL. Source/build release:
  https://github.com/GyanD/codexffmpeg/releases/tag/9.0.2 ; legal/source information:
  https://ffmpeg.org/legal.html . Archive SHA-256:
  `8d31e162f1616e37aab3fa2db991b97e1b8dbeb1c8465fd81a81be16ff91b328`.
- **MangaJaNai / IllustrationJaNai model family** — separate model terms,
  including the MangaJaNai project's CC BY-NC 4.0 license:
  https://github.com/the-database/MangaJaNai/blob/main/LICENSE . The GPL CLI license
  does not replace a weight's license. Imported models remain subject to their
  original terms; MangaFlow records their actual bytes and SHA-256 without
  assigning unrestricted commercial rights.
- **RIFE** — upstream project MIT, Copyright Megvii Inc.:
  https://github.com/hzwer/ECCV2022-RIFE/blob/main/LICENSE . Converted model packages
  retain the notices supplied with those packages. The upstream ncnn/Vulkan
  wrapper is MIT: https://github.com/nihui/rife-ncnn-vulkan/blob/master/LICENSE .
  It was researched but is not bundled or claimed implemented as an Android video
  pipeline by this change.
- **ONNX Runtime Android 1.31.1** — the existing optional signed per-ABI component
  and its MIT/dependency notices below are reused. The image adapter does not add
  another native runtime download or claim a Vulkan execution provider.

Native libaji/TensorRT/DirectML components and model archives downloaded by the
upstream setup retain their own license obligations. This notice does not grant
rights to redistribute an unreviewed native package. Exact interface research,
fixed archive sizes, model identities and unimplemented backends are documented
in `docs/research/enhancement-engines-20261119.md`.

## Anime4K video enhancement

MangaFlow embeds the complete Anime4K Restore CNN S/M and Upscale CNN x2 S/M
GLSL sources from bloc97/Anime4K commit
`7684e9586f8dcc738af08a1cdceb024cc184f426` (MIT):
https://github.com/bloc97/Anime4K/tree/7684e9586f8dcc738af08a1cdceb024cc184f426 .
The four source paths are `glsl/Restore/Anime4K_Restore_CNN_S.glsl`,
`glsl/Restore/Anime4K_Restore_CNN_M.glsl`,
`glsl/Upscale/Anime4K_Upscale_CNN_x2_S.glsl` and
`glsl/Upscale/Anime4K_Upscale_CNN_x2_M.glsl`. Their original copyright,
license and coefficient text remain in `Anime4KShaderLibrary.kt`;
the license is also retained at `third_party/anime4k/LICENSE.txt`.
The source collection includes Restore v4.0 and Upscale v3.2 descriptions;
the pinned commit, not a blanket algorithm version label, identifies it.

Desktop passes the original sources to the existing mpv GPU hooks.
Android adapts the fixed multi-pass source dialect to Media3/OpenGL ES;
iOS adapts it to Metal compute. Signed floating-point intermediate values
and the original CNN coefficients are retained. The two application profiles
combine Restore S + Upscale S and Restore M + Upscale M; these are not the
complete upstream Mode A/B/C presets. Viewport conditions and actual native
color/format support are explicit platform behaviors, not a guarantee of
identical output, native 4K detail or measured real-time performance.

The iOS GLSL-to-Metal and compute-pipeline adaptation is derived from
imxieyi/Anime4KMetal commit `c46b8652a203d259ffbc3d39f21aa54263e11584`
(Copyright 2021 Yi Xie, Apache-2.0), specifically `Shared/MPVShader.swift`
and `Shared/Anime4K.swift`:
https://github.com/imxieyi/Anime4KMetal/tree/c46b8652a203d259ffbc3d39f21aa54263e11584 .
The modified files are `iosApp/Sources/Anime4KMetalShader.swift` and
`iosApp/Sources/Anime4KMetalPipeline.swift`. MangaFlow uses its own existing
routed AVPlayerItem and surface lifecycle, asynchronous preparation and
explicit fallback; it does not incorporate the upstream demonstration
player or its video assets. The complete Apache-2.0 license is retained at
`third_party/anime4k-metal/LICENSE.txt` and packaged in the iOS application.

## Optional built-in adult manga sources

MangaFlow's two opt-in adult catalogue adapters are compiled into the reader,
but do not participate in ordinary discovery or automatic cross-source matching.
The PicACG HTTP/response contract was independently adapted from publicly
documented protocol shapes in l2studio/picacomic-api (Apache-2.0;
https://github.com/l2studio/picacomic-api) and niuhuan/pica-rust (MIT;
https://github.com/niuhuan/pica-rust). No image files, catalogue snapshots,
website accounts or proprietary artwork are distributed with the application.

JMComic on Android and Windows uses the Java library
io.github.jukomu:jmcomic-core:v1.1.12 (MIT;
https://github.com/JUKOMU/JMComic-Api-Java) and, on Android,
io.github.jukomu:jmcomic-android-support:v1.1.12 (MIT). Its image processor
restores provider-scrambled segments before files are handed to MangaFlow's
existing reader and download store. The external libraries are used without
bundling another client UI. No adult catalogue is represented as authorized
or legally cleared merely by being an open-source API wrapper. Actual server
access, authentication, image support and jurisdiction-specific legality
remain subject to verification and applicable service rights.

Android uses a platform adaptation of JmImageTool from v1.1.12 /
5a7a4bb4870edb512274dc2adeb4f7c157445321 (Copyright (c) 2025 JUKOMU, MIT).
Its public segment-count and format logic is unchanged; processor selection
binds AndroidImageProcessor directly. The Android build excludes the original
desktop AWT processor, reflective factory and desktop ImageIO WebP dependency.
Windows retains the original upstream core. The adapted source includes the
full original MIT notice.

## Optional Steam Workshop connection and QR rendering

The Steam connection component adapts the MPL-2.0 tapline library at
f54136611519bef50cf31336e0c28a78eb175657:
https://github.com/UnhingedSoftware/tapline/tree/f54136611519bef50cf31336e0c28a78eb175657 .
The component source and the bounded-download/network-route/session-progress
source patch are in native/steam;
Android downloads a same-signer verified native component only after an explicit
Steam action, while Windows includes its platform library. No SteamCMD, .NET,
Wallpaper Engine renderer or shared engine assets are incorporated.
Resolved Rust dependency attribution and license texts travel with the native
component assets and are retained with its exact CI Cargo.lock. Steam content
remains subject to the account's access and the wallpaper author's rights.

Optional Steam profile XML parsing uses unmodified roxmltree 0.20.0
(MIT OR Apache-2.0; https://github.com/RazrFalcon/roxmltree/tree/v0.20.0),
with DTD disabled and bounded body/node counts. Its resolved attribution and
license files are packaged with the native component by the existing Cargo
metadata collector. The public legacy profile endpoint is optional and is not
a bundled profile database or proof of access to private account information.

The QR painter uses QRose 1.3.1 (MIT), tag source
https://github.com/alexzhirkevich/qrose/tree/1.3.1 . It encodes the real Steam
login URL locally; no remote QR-image service or embedded account credentials
are used. Native library existence or build completion is not evidence of a
successful Steam account login or Workshop download.

## Multiplatform dependencies

Author-script classification retains the original per-UTF16-character contract
using a bounded BMP range table derived from Unicode 15.0.0 Scripts.txt:
https://www.unicode.org/Public/15.0.0/ucd/Scripts.txt . The Unicode data license
is included at `third_party/unicode/LICENSE.txt`; Common, Inherited and Unknown
are excluded from script-conflict evidence, as in the original implementation.

The production shared module uses JetBrains Compose Multiplatform 1.12.2, Kotlin
2.4.21, kotlinx.coroutines 1.11.0, atomicfu 0.29.0, kotlinx.datetime 0.7.1,
kotlinx.serialization JSON 1.13.0, Ktor 3.6.1 and Okio 3.16.2 (Apache-2.0).
Ksoup 0.2.6 is Fleeksoft's MIT-licensed multiplatform HTML parser:
https://github.com/fleeksoft/ksoup . Provider business/parsing rules remain
MangaFlow code; the JSON/request facades are application adapters, not modified
copies presented as org.json or OkHttp.

Windows embeds OpenJFX 21.0.9 WebView/Media/Swing (GPLv2 with Classpath Exception;
https://github.com/openjdk/jfx/blob/jfx21/LICENSE and bundled upstream legal notices),
ICU4J 77.1 (Unicode License; https://github.com/unicode-org/icu), and
`io.github.darkxanter:webp-imageio:0.3.3` (Apache-2.0;
https://github.com/darkxanter/webp-imageio). The WebP reader uses the provider's
real crop/scale parameters, not an ignored generic source-region hint. Its
libwebp submodule is the 1.3.2 security-fix line. iOS uses system ImageIO,
UIKit, WebKit, Keychain and AVFoundation; no Android native executable is
downloaded or represented as an iOS engine. Existing upstream motion, Haze,
Telephoto and panel-algorithm notices below remain in force.

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
The sidebar's MotionNavigationColumn also adapts these independent edges vertically over measured
native row bounds, keeping labels and hit regions fixed and bounding long-jump stretch.

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

## OSHI device resource information

Desktop resource information uses OSHI 6.12.0 (`com.github.oshi:oshi-core:6.12.0`),
https://github.com/oshi/oshi/tree/oshi-parent-6.12.0 . OSHI is used for local CPU,
memory and process observations; no device serial number or hardware identifier is
stored or transmitted by this resource feature. Android and iOS use platform APIs.

OSHI core/common are MIT licensed. Their normal JNA/JNA-platform 5.18.1 and SLF4J
2.0.17 runtime dependencies retain the licenses/notices included in their artifacts;
OSHI's optional jLibreHardwareMonitor dependency is not enabled. OSHI's original
license at the pinned tag is reproduced below.

MIT License

Copyright (c) 2010-2023 The OSHI Project Contributors: https://github.com/oshi/oshi/graphs/contributors

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


## Guided panel reading and optional models

The traditional gutter detector in `reader/panels/ClassicPanelDetector.kt` adapts
`jsneij/paneldet` at `f0ef27986969fce6ca2dd75e2be2962b56db376c`, `paneldet.py`.
Copyright (c) 2026 Jorge Sneij, MIT. MangaFlow adapts its page-statistic, recursive
X/Y gutter and coverage mechanisms to bounded Android bitmap analysis; it does not
include the upstream connected-component, deskew or command-line implementation.
The exact MIT text is bundled as `licenses/Paneldet-MIT.txt`.
Source: https://github.com/jsneij/paneldet/tree/f0ef27986969fce6ca2dd75e2be2962b56db376c

`reader/panels/PanelGeometry.kt` adapts the recursive reading-order and spread-seam
mechanisms in Komai's `PanelOrdering.kt`. Nano input preparation, output-layout
decoding, letterbox reversal and overlap suppression in `PanelModelRuntime.kt`
adapt Komai's `MlPanelBoundaryDetector.kt` and `YoloPanelDecoder.kt` at
`f567e2be8f30b59f04d14dd7fc589e186fb72ff8` (Apache-2.0).
Source: https://github.com/erik-sandoval/komai/tree/f567e2be8f30b59f04d14dd7fc589e186fb72ff8/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/panel
Komai is derived from Mihon/Tachiyomi (Copyright 2015 Javier Tomas and 2024 Mihon
Open Source Project, as credited by its README). The upstream Apache-2.0 text is
bundled as `licenses/Komai-APACHE-2.0.txt`. MangaFlow substitutes normalized local
types, a limited CPU runtime, correctly quantized signed/unsigned input, finite
geometry checks, conservative duplicate suppression and independently downloadable
models. It does not import Komai's upscaler, source system or application.

Model weights are not included in the APK and are never downloaded merely by
opening a manga or selecting a mode. An explicit Download action fetches the exact
upstream revision into app-private storage, verifies its complete SHA-256 and
length, then atomically installs it. Partial downloads are removed on cancellation
or failure. Unavailable models retain a visible notice and use traditional/geometry
fallbacks. Inference does not upload manga pages. Source images remain unchanged;
analysis uses a separate reduced bitmap and saves only normalized coordinates.

Optional weight identities and terms:

- **YOLO26-nano manga panel detector** by Leandro Narosky (`leoxs22`),
  revision `40a2854663d537563cfb95c370288a84c6505b9a`,
  `manga_panel_detector_int8.tflite`, 2,837,823 bytes,
  SHA-256 `b1a7d8d4492e04a777ae0d3efd9dc1fbd6e8f361971eadb813279ce3dfd1b464`.
  Source: https://huggingface.co/leoxs22/manga-panel-detector-yolo26n
  These weights are **AGPL-3.0**, derived from Ultralytics YOLO26n (Copyright
  Ultralytics). The author explicitly corrected the earlier Apache-2.0 label.
  They were trained on **Manga109-s** (Aizawa et al., University of Tokyo); no
  training images or annotations are distributed by MangaFlow. Preserve the
  source, dataset acknowledgement and applicable model terms. The complete
  upstream model card and AGPL text are bundled as `licenses/YOLO26-MODEL-CARD.md`
  and `licenses/YOLO26-AGPL-3.0.txt`. A format-converted third-party copy's old
  Apache label does not override the original author's current model license.

- **Koharu Layout RF-DETR Seg 2XL 1152**, source weights by Mayo Takanashi
  (`mayocream`) at `aed55fdb8ca953c6bec33cf6ed6dd52a9b72bfa2`,
  exported to ONNX by `ShiniShiho` at
  `bfbbd4e5ab34a50459865074fa044da496cebb57`, `rfdetr-seg-2xlarge.onnx`,
  148,442,003 bytes,
  SHA-256 `7cc10d4316371946b8441da3512261a8e148b129abcdb0ea6235ed1d1d06d351`.
  Sources: https://huggingface.co/mayocream/koharu-layout-rfdetr-seg-2xl-1152
  and https://huggingface.co/ShiniShiho/koharu-layout-rfdetr-seg-2xl-1152-onnx
  Both cards designate these weights **custom / other** and retain the Manga109
  training-data statements. RF-DETR software's Apache-2.0 license is not an
  independent license grant for these weights or comic images. The exact original
  terms are preserved in `licenses/Koharu1152-SOURCE-MODEL-CARD.md` and
  `licenses/Koharu1152-ONNX-MODEL-CARD.md`. No claim of unrestricted commercial
  permission or of extra rights to Manga109/third-party artwork is made.
  MangaFlow consumes boxes and class logits with the documented 1152 RGB/ImageNet
  normalization contract and does not materialize instance masks in Java.

- **Inkwell panel reading-order model** by `cedarrapidsboy`, revision
  `fa7afba52ba7dc7b0840c7702b767972b5e17a9b`, `panel-order-model.onnx`,
  103,294 bytes,
  SHA-256 `84eb78fc5ec31a3e986a7a16ce10f7baa657ef5c5b4a98877b40aa9354ff3464`.
  Source: https://huggingface.co/cedarrapidsboy/inkwell-panel-models
  The upstream card declares the **reading-order model MIT**; its distinct
  AGPL-3.0 YOLOv8 detector is not used. `PanelModelRuntime.kt` follows the fourteen
  geometric features and probability-sum ranking in the author's
  `detect_and_order.py`, with bounded input, Kotlin/ORT resources and horizontal
  coordinate mirroring for right-to-left reading. The mirroring is MangaFlow's
  adaptation, not an upstream accuracy claim. The original card, including the
  exact separate model-license declarations, is bundled as
  `licenses/Inkwell-MODEL-CARD.md`; the standard MIT text is bundled as
  `licenses/Inkwell-MIT.txt`. The card supplies no separate copyright line;
  MangaFlow does not invent a legal name or copyright year for this author.

The newer **Koharu Layout HighRes 1920** is a different 46.6M-parameter,
186,458,372-byte SafeTensors checkpoint at
`mayocream/koharu-layout-highres@68077eb09401a46e548190ff2786bd405113ea8b`,
SHA-256 `03de2f77281974f5084cdd8652ace2b55dc15ac0378aed66654e62537cdda255`.
Its current card declares Apache-2.0. It was researched but is **not integrated**:
no compatible pre-exported ONNX/TFLite graph was established for that checkpoint.
The selectable 1152 model is labelled precisely and does not impersonate HighRes
1920 or inherit the newer checkpoint's license. CPU performance, model accuracy
on the user's manga and Android device behavior remain unverified.
Source: https://huggingface.co/mayocream/koharu-layout-highres

The Android runtimes are Google AI Edge **LiteRT 1.4.2** (Apache-2.0;
`licenses/LiteRT-APACHE-2.0.txt`) and Microsoft **ONNX Runtime Android 1.31.1**
(MIT; `licenses/ONNX-Runtime-MIT.txt`). Upstream ONNX Runtime's complete dependency
notice is preserved as `licenses/ONNX-Runtime-ThirdPartyNotices.txt`; its inclusion
does not imply every optional upstream component is used. CPU execution is limited
to two inference threads; GPU, NNAPI and NPU delegates are not enabled.
Sources: https://github.com/google-ai-edge/LiteRT and https://github.com/microsoft/onnxruntime/tree/v1.31.1


## Daily reading activity calendar

The date padding/week-column/block geometry in `ui/ReadingStatsSheet.kt` adapts
`src/lib/calendar.ts` (`groupByWeeks`, continuous missing-date handling) and
`src/components/ActivityCalendar.tsx` from react-activity-calendar,
`https://github.com/grubersjoe/react-activity-calendar/tree/4931c8f7d85c98de10358f6f5a841c50bd4ff228`.
The Android adaptation uses the existing Canvas, Monday-first dates, foreground-reading
milliseconds, fixed time levels and local date selection. It includes no React runtime,
source test generator or synthetic activity data.

MIT License

Copyright (c) 2021 Jonathan Gruber

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

The beUI measured-trigger morph already credited above also informs
`ui/SegmentedGlassHeader.kt`'s `RankingsFromTab`: one retained surface grows from the
measured ranking tab, final-size labels are revealed separately, and the same progress
retraces the path. This is an Android adaptation within the existing header, not the
upstream web popover or an assertion of visual equivalence.


## Optional native inference loading

MangaFlow now distributes the pinned native LiteRT and ONNX Runtime libraries as optional,
architecture-specific signed release components rather than embedding their .so files in the APK.
The existing Apache-2.0 / MIT runtime notices above are also preserved in each component ZIP.
The small Java interfaces remain compiled into the app. ONNX Runtime 1.31.1's Android loader
is adapted from the actual upstream source at Git blob ccbf89b592567ce1cbbae0b984192a55876387b8:
https://github.com/microsoft/onnxruntime/blob/v1.31.1/java/src/main/java/ai/onnxruntime/OnnxRuntime.java
The MIT copyright header remains in app/src/main/java/ai/onnxruntime/OnnxRuntime.java.
Its Android branch uses only the application-verified, read-only private directory and explicit
core-before-JNI absolute loading. The original loader class is excluded from the generated API JAR;
all other upstream API classes/native signatures remain. See third_party/onnxruntime/ADAPTATION.md.
There is no downloaded Java/Dex code or new runtime license claim. This source adaptation and
packaging inspection do not establish Android runtime execution or on-device verification.

## Optional QuickJS manga-source script engine

The compiled Android host uses dokar3's **quickjs-kt 1.0.18** Kotlin/JNI API from
`io.github.dokar3:quickjs-kt:1.0.18`. The unmodified Android native library comes from
`io.github.dokar3:quickjs-kt-android:1.0.18` on Maven Central. Its consumer ProGuard
rules remain part of the dependency; `libquickjs.so` is excluded from the base APK
and is packaged separately for arm64-v8a, armeabi-v7a, x86 and x86_64. The existing
permanent APK certificate signs the runtime catalog; installation verifies the
archive, ABI and each native member, then seals a private directory before loading.
No Java/Dex implementation is downloaded. Ordinary reading does not download or
load the optional engine, and source import does not imply runtime consent.

The official v1.0.18 Android loader supports
`com.dokar.quickjs.library.path` / `com.dokar.quickjs.library.name`; MangaFlow sets
them only to its verified private installation and loads that absolute file before
creating a QuickJS instance. The loader is not replaced. This packaging/source
inspection does not establish a successful Android execution or support for every
third-party source dialect.

Upstream sources and license materials (also included in every component ZIP):

- Wrapper: https://github.com/dokar3/quickjs-kt/tree/v1.0.18;
  its complete upstream Apache-2.0 text is bundled as
  `licenses/QuickJS-kt-APACHE-2.0.txt`.
- Engine: https://github.com/bellard/quickjs/tree/04be246001599f5995fa2f2d8c91a0f198d3f34c,
  the v1.0.18 wrapper's pinned submodule. Copyright 2017–2021 Fabrice Bellard and
  Charlie Gordon; the complete MIT text is `licenses/QuickJS-MIT.txt`.
- Native vector utility:
  https://github.com/eteran/c-vector/tree/774773d4cb1e66dd736e90e7f482d4f22af464c6,
  the wrapper's pinned submodule. Its complete upstream MIT copyright and license
  are bundled as `licenses/c-vector-MIT.txt`.

## Unified Chapters / additional body sources

The logical-parent/source-variant and priority-view mechanism is adapted from the
Apache-2.0 Komikku/TachiyomiSY implementation, reviewed at Komikku
`383ffaa81667f45fc6c2975edb1760a7606531d3` (SmartSearchMerge,
GetMergedChaptersByMangaId, merged.sq and ChapterLoader). MangaFlow's storage,
semantic safeguards, explicit identities and retained source selector are modified
integrations of that mechanism with its existing reader and motion components.

Komiic's public GraphQL and image protocol is adapted from Keiyoushi at
`c3bc55d548b0a5f1b316a323bde9e6e2516e4bea`. Its Apache-2.0 license and precise
source/modification record are in `third_party/multi-source/`. MangaDex uses its
public manga/feed/chapter/At-home API; no Venera script or runtime is bundled.
Body-source marks are local MangaFlow vector artwork rather than downloaded
source favicons; provider names and original source attribution remain visible. Refer to ADAPTATION.md for the unverified
source-access and implementation boundaries.

Baozi / Manhuagui public reader adapters, Packer/LZString decoder adaptations and
Komiic origin configuration use Keiyoushi `fc7a827769f612dfa6dccb51e819f5b4eecaeab5`;
MangaBZ uses `535ffdbdd84eadab593d515283cb82aa9bf177f5`, all Apache-2.0.
The exact upstream paths, modified local files and omitted runtime/account/image
rewriting features are recorded in `third_party/multi-source/ADAPTATION.md`.

Guazi, Manhuaren, Rumanhua and Zaimanhua anonymous adapters additionally adapt
Keiyoushi/extensions-source `9080fbfd1d02ce2cad0f7466996fa19816f39401`
(Apache-2.0): Guazimanhua, the shared Dm5 public web contract, MMLook/PageDecrypt,
and Zaimanhua/DTOs. These are modified common-Kotlin integrations using source-owned
transport, exact native identities, bounded parsing and normal TLS validation.
The complete Apache-2.0 license is included at
`third_party/multi-source/KEIYOUSHI-APACHE-2.0.txt`; the exact file map and omitted
upstream account, certificate-bypass and HTTP-workaround features are in ADAPTATION.md.
Unlicensed Venera script text is not bundled or translated verbatim.


## Anime source adapters and playback (2026-11-16)

MangaFlow's anime adapters use public source/API contracts. A source entry does not establish that the remote service is currently reachable, that all media formats are supported, or that the application grants distribution rights to remote content.

- **KazumiRules** — https://github.com/Predidit/KazumiRules/tree/64ea35976c383a3a760eb23d744fcada076317ee
  TvTFun 1.3, giriGiriLove 2.7 and LMM 2.4 rule endpoint/selector contracts are adapted into Kotlin. MIT, Copyright (c) 2024 Predidit.
- **AGE provider contract** — https://github.com/miru-2-0/repo/blob/aea94f7e8a16689aeb9e510a86cd4a6cadab286f/repo/agedm.io.js
  The provider file declares MIT and attributes appdevelpo. The /v2 catalogue and Vurl response contract is adapted; the application uses its existing routed network and HTML implementation.
- **TvTFun protocol cross-references** — https://github.com/AniBakaBaka/AniBaka/blob/4041aa6200c76bb829422dabba213fa9e8819db5/assets/rules/tvtfun.json and https://github.com/uerax/Animaku/blob/2040ce6175f3b2eec72a4b829ad7a30129f1c191/apps/server/src/lib/tvtfun.ts
  Public request/response field references only. Animaku implementation code is not copied or linked.
- **Bangumi API** — https://github.com/bangumi/api/blob/master/open-api/v0.yaml
  Metadata and collection protocol integration. The implementation preserves canonical episode identities and uses explicit episode collections for animation progress.
- **AniList API** — https://github.com/AniList/docs/tree/03281c0a4bbf0c7f2097e0c935cddaed1096aa65
  GraphQL protocol integration. User credentials are supplied separately and never distributed with the application.
- **AndroidX Media3** — https://developer.android.com/media/media3 and https://github.com/androidx/media
  Android playback, media sessions, data sources and compressed media caching. Apache License 2.0; licenses and attribution supplied by its published artifacts remain applicable.
- **Tencent ncnn 20261020 and Real-CUGAN** — https://github.com/Tencent/ncnn/releases/tag/20261020 and https://github.com/nihui/realcugan-ncnn-vulkan/tree/395302c5c70f1bff604c974e92e0a87e45c9f9ee
  The Android Vulkan bridge links the pinned official ncnn static runtime under BSD-3-Clause and adapts the MIT preprocessor and model I/O contract. The app downloads the fixed Real-CUGAN NOSE model from the upstream repository. The original Real-CUGAN MIT notice (Copyright 2022 bilibili), converter MIT notice (Copyright 2019 nihui), and ncnn notices are included in Android assets.
- **RIFE 4.6 ncnn Warp** — https://github.com/nihui/rife-ncnn-vulkan/tree/a7532fc3f9f8f008cd6eecd6f2ffe2a9698e0cf7
  The optional Android Vulkan component uses the unmodified MIT Warp shader, adapted ncnn layer and official flownet 4.6 parameter and weight pair. The weights are downloaded explicitly and checked against the pinned contents. RIFE and the ncnn converter MIT notices are included in Android assets and the optional native archive.
- **ArtCNN v1.6.2** — https://github.com/Artoriuz/ArtCNN/releases/tag/v1.6.2
  Bundled unmodified C4F16 and C4F32 GLSL release files for Windows libmpv. MIT, Copyright (c) 2024 João Chrisóstomo. The full upstream notice is packaged alongside the shaders in `shared/src/desktopMain/resources/artcnn/LICENSE`.
- **RIFE FP32 v2 ONNX exports** — https://github.com/AmusementClub/vs-mlrt/releases/tag/external-models
  Android accepts exact v2 weights from upstream 4.25 Lite, 4.22 and 4.26 model archives. Weights are imported from user-selected files and retain the applicable upstream model notices. RIFE algorithm source: https://github.com/hzwer/ECCV2022-RIFE.
- **MediaMP 0.5.1** — https://github.com/open-ani/mediamp/tree/v0.5.1
  Desktop mpv and iOS AVKit adapters. MediaMP glue and the bundled native runtime have distinct license obligations. The chosen Windows/macOS native runtime includes dynamically linked libmpv/FFmpeg and related components under their applicable LGPL and other licenses; it is not described as an exclusively Apache-licensed bundle. Corresponding native dependency versions and build/source references are maintained in the upstream tagged project, submodules and runtime distribution. The application does not use the all-platform runtime aggregator or introduce Linux GPL runtime artifacts.
- **Apple AVKit / AVFoundation** — system frameworks used on iOS. Native platform format and subtitle limitations remain applicable.
- Existing Ksoup, kotlinx.coroutines, Ktor, OkHttp, QuickJS/SourcePackedScript, Compose and shared glass/motion notices above continue to apply.

### MIT permission notice for adapted MIT source contracts

Copyright (c) 2024 Predidit
AGE adapter attribution: appdevelpo (as declared in the referenced provider file).

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
