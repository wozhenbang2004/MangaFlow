# Lanerc feature transfer into MangaFlow

Source implementation checkpoint: 2026-11-16. This document distinguishes implemented application paths from the complete requested inventory. It does not certify installed behavior, source availability, rendering performance or successful packaging.

## Reference and decisions

The user requested that Lanerc's sources, functions and interaction designs be implemented in MangaFlow, then improved. The reference is the uploaded Lanerc 1.1.6 / `com.qsreod` APK, SHA-256 `ee44900cb1217d00f2fe4a7c53f39cbac071f8dddb564e45f99edb1c71f837d2`. [The original analysis](https://chatgpt.com/space/page_e184ced85e8c8191985ad8153f1c6375) contains APK identities, public-source findings and the unsuccessful emulator attempt. Full application source licensing was not verified. MangaFlow implements compatible behavior independently and retains its own existing glass/motion components. It does not embed the APK, its native libraries, private credentials or the remote script bodies in this repository.

The integration base is `7e71ae5e95291eb8c771ffa8a74d25722950d16d`. Existing canonical Bangumi identities, the four native providers, tracking review, native cache owners and manga behavior remain the foundation. The new compatibility protocol is separate from manga script semantics.

## Implemented user journeys

| Journey | Application behavior | Principal source |
| --- | --- | --- |
| Find a show | Popular/newest/calendar, weekday selection, canonical search, year/tag filtering, paging, related subjects and local search history. Tag filtering uses the official search/rank contract rather than claiming date order. | `AnimeMetadataRepository`, `AnimeDiscoveryPane` |
| Open a show | Display retained metadata first; read bound source catalogues independently of the metadata request. A failed refresh preserves usable data and reports its own error. | `AnimeController`, `AnimeMetadataCache` |
| Browse a provider | Installed video/browse providers expose their real home sections, categories, filters, paged cards and related cards. Browse-only cards search for a canonical subject; they cannot become video assets. | `LanercBrowse`, `AnimeSourceBrowsePanel` |
| Associate multiple sources | Search providers independently; retry or append one bucket. The user chooses the work/season/line, with explicit native episode mappings where ambiguous. Source version changes invalidate stale preparation and selections. | `AnimeSourcesPanel`, `AnimeController` |
| Continue watching | One dismissible resume dock opens the saved canonical episode after its exact sources are ready. A later playback checkpoint can make the dock reappear. | `AnimeLibraryPane`, `AnimeLibraryStore` |
| Organize the library | Followed/history views, local folders, pinning and disclosed status/actions. Clearing positions preserves followed/completed state; removal is confirmed in the owning panel. A persisted per-subject clearing marker suppresses legacy unowned position records. | `AnimeLibraryPane`, `AnimeLibraryStore` |
| Change episode/source | Resolve while retaining the current native session. Cancellation leaves the old playback intact. Save its current checkpoint before committing replacement; cross-asset positions require an explicit continue/from-start choice. Failed opening exposes one guarded previous-session restore action. | `AnimeController` |
| Use the player | Retained native surface across fullscreen/layout changes, current-episode positioning, reverse order, episode-number search, keyboard actions, double-tap seek, hold speed, input lock and one retained settings panel. | `AnimePlayerPane`, `AnimePlayerSettings` |
| Adjust playback | Source resolutions, real native video/audio/subtitle tracks, scale, speed, volume, background intent, actual PiP where available, buffer targets and bounded recovery. Unsupported native capabilities do not appear as working controls. | `AnimePlayer` platform owners |
| Continue automatically | Persistent autoplay, next-episode resolution preparation, opening/ending skip and optional exact-source recovery. A sleep deadline or episode-end pause cancels pending continuation. Preparation only resolves a future asset; it is not a downloaded episode. | `AnimeController`, `AnimePlaybackSettings` |
| Save a video | Explicit app-owned queue, pause/resume/retry/delete, byte/segment progress, strong-validator resume and atomic completion. Play completed files through a store-issued local capability. Active local readers retain a lease while deletion waits. | `anime/downloads`, `AnimeLocalMedia` |
| Show danmaku | Official search and explicit episode selection, plus the observed exact AniCh station protocol. Settings include opacity, size, density, color, duplicates, blocked words and signed time offset. Timeline seeks use binary search; the visible overlay interpolates the native playback clock and shares physical rows. | `anime/danmaku`, `AnimeDanmakuOverlay`, `AnimeDanmakuPane` |
| Back up and restore | Existing local/WebDAV backup includes anime entries, mappings, positions, settings, folders, search history and privately installed source packages. All documents validate before mutation; older backups leave anime data intact. Credentials, cookies, private Ext parameters, expiring stream addresses and video bytes are excluded. Partial multi-store failures identify the stores already changed. | `storage/LibraryStore`, `AnimeLibraryStore`, `LanercSourceStore` |
| Compare tracking accounts | Existing Bangumi/AniList per-field review, exact main-episode numbers, owner/revision guards and persistent local undo remain in place. No remote destructive action is inferred from missing/failed reads. | `AnimeTrackingRepository`, `AnimeTrackingPanels` |
| Enhance anime during playback | The existing video-settings panel offers original/light/fine profiles, persists the choice and reports configured/processed/unavailable/failed separately. All three native backends use the original pinned Anime4K CNN sources; enhancement does not create a new media asset, route or progress record. | `Anime4KShaderLibrary`, `AnimeVideoEnhancement`, platform player adapters |

## Sources and compatibility

The reference directory contains **23 distinct script references**, originating from 24 remote registration rows. It classifies **16 video, 1 browse, 4 comic, 1 danmaku and 1 unknown** script; these are capability findings, not 23 working video services. The four native TvTFun/AGE/giriGiriLove/LMM adapters remain independently available.

| Type | Referenced scripts | Current treatment |
| --- | --- | --- |
| Video | Lanerc, 金牌, 瓜子, 师兄, yzx, AkiAnime, LMM85, 柚子, DMBus, 追剧, 23, AniCh, Cain, 柠檬, 闪电, 零七 | Explicit installation, enable/disable, update, previous-version rollback, video/browse adaptation and full opaque episode flags. Individual sources may require capabilities or accounts described below. |
| Browse | 豆瓣发现 | Real provider discovery only; canonical search before association. |
| Comic | 酷漫星, 咸番鸭, yulu, 漫时光 | Listed with their actual image/comic capability. Their reader/plugin conversion remains open; they are not registered as video providers. |
| Danmaku | 弹弹play script reference | Listed/installed by capability. The full public script contract was not retained in the prior research, so its payload is not guessed. The separate official authenticated integration and exact AniCh path are implemented. |
| Unknown | 囧次元 | Listed with an explicit unverified-protocol reason; not promoted to playable status. |

`LanercJavascriptHost` supplies the observed synchronous host protocol on the existing optional QuickJS engine. The runtime isolates each invocation, serializes mutable per-source sessions, freezes route and installation revision, bounds memory/network work, propagates cancellation and rejects late publications. HTTP binary responses, cookies, encoding/crypto helpers, private KV and Ext are handled through the existing platform transport/storage. Parameters and cookies are private per source; backup imports never execute scripts during validation. Installation itself needs the application's JavaScript component to be available.

The inventory is intentionally pinned to the researched references, including provenance digests and alias information. Updates replace only explicitly installed packages and retain the previous version. Script content in a private user backup is validated against its recorded hash and a known descriptor. The public catalogue is not evidence of reachable sites, accepted credentials or playable streams.

## Actual platform boundaries

| Capability | Android | Desktop | iOS |
| --- | --- | --- | --- |
| Native backend | Media3/ExoPlayer | Existing Mediamp/mpv | Existing Mediamp/AVKit |
| First video frame acknowledgement | Native rendered-frame callback | Unknown when not exposed by backend | Unknown when not exposed by backend |
| Audio/subtitle/video tracks | Native track groups and selections | Exposed mpv feature tracks | Hidden where Mediamp does not expose track metadata/selection |
| Picture in picture | Actual Activity capability with Manifest support | Not advertised | Not advertised by this bridge |
| Scale, speed, volume | Native | Native | Supported exposed features only |
| Background audio | Existing app/session owner and foreground policy | Native audio with reduced video work | Existing audio background configuration and exposed player behavior |
| Offline playback | Whitelisted file DataSource and lease | Whitelisted local range server and lease | Whitelisted local range server and lease |
| Anime4K | Media3 effect, OpenGL ES 3 and signed FP16 intermediates; explicit x2 Restore/Upscale profiles | Existing libmpv GPU shader hooks; accepted/read-back configuration only, actual processed frames unconfirmed | Same AVPlayerItem video output and Metal on iOS 15 / Apple GPU family 3 or later; original AVPlayerLayer fallback |

### Anime4K: required local enhancement

The latest user correction makes Anime4K a required part of the player; see DESIGN ANIME4K-01–06. The shared library contains the complete original Restore CNN S/M and Upscale CNN x2 S/M files from [bloc97/Anime4K `7684e958`](https://github.com/bloc97/Anime4K/tree/7684e9586f8dcc738af08a1cdceb024cc184f426). Light combines S/S; fine combines M/M. These are application profiles, not the complete upstream Mode A/B/C presets. Native 4K source decoding is a separate capability. Upscaling 1920×1080 by two in each dimension yields 3840×2160; it does not recover the original master or make a 720p source into native 4K.

The UI retains the existing PANEL/MotionTabs controls and starts with original video. Settings and portable backups preserve the selected profile. Processing is local GPU work; no enhancement account, cloud API or uploaded video is required. The small/medium original learned coefficients and all dependent passes remain intact. GPU intermediates use signed floating-point textures; this is not an unrelated sharpen filter carrying the Anime4K name.

Android adapts the fixed GLSL dialect to Media3's actual SDR effect pipeline. Its selected profiles explicitly perform x2 upscaling, even if the final display scales the result down. The current decoder format supplies the source dimensions; the effect reports its own input/output dimensions. Normal mode changes replace effects on the existing player. A failed video-processing graph gets one guarded replacement native renderer on the same media source, route, media-session token, position, speed, volume and track settings, with Anime4K disabled for that attempt. Both synchronous and asynchronous failure before the recovered native first frame are local renderer failures, not permission to consume source-recovery attempts or select a different provider.

Desktop writes the embedded shaders into a private versioned resource directory, verifies complete contents and atomically replaces files before appending both literal paths to the current mpv shader list. It preserves the existing VO, hardware-decoding and route/header owners. A complete list read-back yields `CONFIGURED`, never a fabricated `ACTIVE`, output resolution or GPU timing. While enabled, an approximately 500 ms check observes dimensions, color transfer and VO, removing the list when PQ/HLG or unsupported/unknown color is observed. This is not atomic with every decoded frame: a changing track may retain the previous configuration until the next scheduled observation, and that interval is not a strict response bound. If clearing cannot be confirmed, the interface explicitly asks to reopen the video. The original upstream viewport-dependent upscale condition remains in force.

iOS adapts [Anime4KMetal `c46b8652`](https://github.com/imxieyi/Anime4KMetal/tree/c46b8652a203d259ffbc3d39f21aa54263e11584), specifically `Shared/MPVShader.swift` and `Shared/Anime4K.swift`, while retaining the current routed AVPlayerItem. Asynchronous shader/plan preparation, CVMetalTexture-backed decoded frames, an arena of FP16 intermediate textures and an MTKView perform the actual passes. One GPU frame is in flight; stale item/configuration/surface callbacks cannot promote it. `ACTIVE` requires both GPU completion and drawable presentation acknowledgement. Reported processing dimensions describe the final CNN texture, not the screen or its letterbox. The upstream viewport condition determines whether the x2 pass is needed. Background/surface removal and OFF stop new enhancement work and restore original rendering. HDR/wide-gamut, protected video, embedded soft subtitles and unsupported geometry/format paths keep the original system presentation. The application deployment target remains iOS 14; unsupported devices retain original playback.

Licenses are retained in the embedded sources, repository notices and actual platform resources. The original shader bytes were compared with their pinned upstream blobs; source/API review and diff checks do not establish shader compilation, visual equivalence, sustained FPS, energy use or installed acceptance. Those remain unverified under the current no-local-build/no-probe boundary.

Offline admission currently covers progressive video files and finite, unencrypted HLS media playlists with whole-file TS/fMP4 resources. It rejects live/updating playlists, master playlists with alternate media, encryption keys, byte-range HLS, unsupported extensions, DASH and externally attached subtitles. This is an implemented bounded format contract, not a promise that every remote stream can be downloaded. Jobs run while the app is foreground, yield during current playback opening/buffering and restore as paused after interruption. A system-managed background downloader is not implemented.

Recognized standalone offline containers are MP4/MKV/WebM/TS on Android and desktop, and MP4 on iOS. HLS uses its separately validated TS/fMP4 resources. Container recognition is not a guarantee that every device can decode every contained codec.

Official danmaku requires this application's own AppId/AppSecret, stored in platform secret storage. With no credentials the interface explains configuration and does not pretend that a search succeeded. Reference documentation: [official access guide](https://doc.dandanplay.com/open/), [official Swagger](https://api.dandanplay.net/swagger/v2/swagger.json). No Lanerc client identity is borrowed. The official endpoint is independently registered for source routing.

## Complete inventory: remaining work

The user asked for the entire product inventory. The following are **open**, not completed by the presence of APK classes, menu labels or a source-directory row. There is no claim that all Lanerc functionality has been ported.

| Open capability | Concrete missing implementation or dependency |
| --- | --- |
| Dynamic browser media sniffing | A platform host that executes the target site's page while preserving route, cookies, cancellation and media identity. The compatibility runtime currently returns an explicit capability error. Static/native resolvers remain available. |
| Some advanced iOS script crypto | The current platform codec does not provide all RSA/GCM modes required by individual scripts. Such sources fail with a concrete capability reason; there is no TLS downgrade or dummy decrypt result. |
| Protected source login/captcha/binding | Source-specific authentication/UI contracts and a legitimate user session are needed. `needLogin`/`needBind` are surfaced; no embedded token or fabricated authorization is used. |
| Four additional comic scripts | Convert their verified image contracts into the manga plugin/reader pipeline with its exact chapter/image ownership. They are not videos and are not silently installed as manga adapters. |
| Unknown 囧次元 protocol | A readable verified provider contract is required before an adapter can be implemented. |
| Cast/DLNA/AirPlay | Device discovery, native renderer connection, URL/header/route compatibility and session transfer. The player does not display a nonfunctional cast button. |
| Shared watching | A real room/signaling service, ownership, reconnect, clock synchronization and invitation/authentication design. The APK's room strings do not supply a reusable service contract. |
| Community, comments, posts and rewards | Service contracts, the application's own account model and moderation/business requirements. The uploaded client backend is not an authorized MangaFlow community service. |
| Update notifications/calendar subscription | Durable scheduling, update comparison and platform notification behavior. The visible calendar does not imply a subscription/background notifier. |
| Whole-account import, automatic cross-device synchronization and remote deletion | Explicit account-wide pagination, field ownership, deletion/conflict semantics and review UX beyond the existing subject-level Bangumi/AniList comparison. |
| Extra native engines and external subtitle file import | Additional supported engine/decode and native file-selection contracts. Anime4K now has the real integrated source paths and explicit boundaries documented above. |
| Background/offline format expansion | Native job scheduling plus encrypted/master/byte-range HLS, DASH and external-subtitle packaging, with exact local integrity and cancellation handling. |

## Review and delivery evidence

This batch uses source/API review and `git diff --check` only. It does not run local builds, tests, API/source probes or device/UI automation, following the repository's established boundary. Installed animation quality, first-frame latency, provider reachability, file playback, accessibility and platform packaging remain unmeasured until an actual artifact is installed or CI reports a result.

The current base had real shared Kotlin compiler failures. Android [run 43853588475](https://github.com/wozhenbang2004/MangaFlow/actions/runs/43853588475), and Windows/iOS [run 43853588249](https://github.com/wozhenbang2004/MangaFlow/actions/runs/43853588249) logs identify the missing `onDismiss`, undefined button name and five missing `runtime` arguments. The reviewed source correction from [commit a8ea310](https://github.com/wozhenbang2004/MangaFlow/commit/a8ea31075a9a249f29486d8c9a41547df1dcafb3) is incorporated with attribution. This does not claim that the newly expanded batch has passed CI.

Root publishes the integrated latest-main batch with an expected-head guard, confirms ordinary Android and multiplatform CI activation, and stops waiting for artifacts. The resulting commit and run links are delivered in chat. No status-only follow-up push or invented runtime result is part of this checkpoint.

Additional public protocol references: [Bangumi v0 specification](https://github.com/bangumi/api/blob/master/open-api/v0.yaml), [Bangumi calendar specification](https://github.com/bangumi/api/blob/master/open-api/api.yml), [Mediamp v0.5.1](https://github.com/open-ani/mediamp/tree/v0.5.1). Existing dependency versions and licenses are retained; no new native engine is bundled by this work.
