# JMComic v1.1.12 coverage audit · 2026-11-18

This is the actual MangaFlow adapter-to-SDK audit, not an assertion that all remote JMComic APIs work. The upstream source baseline is [JUKOMU/JMComic-Api-Java v1.1.12](https://github.com/JUKOMU/JMComic-Api-Java/tree/v1.1.12), including `JmClient`, `JmApiClient`, `JmNovelClient` and `JmCreatorClient`. Review status describes linked app code only. Static source review is not live-service, authenticated-account, compilation or installed acceptance.

## Production capabilities implemented before this review

| SDK operation family | MangaFlow entry / owner | Notes |
| --- | --- | --- |
| `getAlbum`, `getPhoto`, search, category query, image bytes | Native `JvmJmBodySource` → existing unified manga/reader | Chapter identity, exact image bytes, existing download/cache/route policy are authoritative |
| `getCategories` and search ordering/time tags | Adult source feed browser and native search filters | Source-native ranking and per-source attribution, not Copy heat |
| Login/logout | Source account sheet; private saved cookies and route-scoped client | No credentials for public discovery |
| `getFavorites`, `manageFavoriteFolder` (read only), `toggleAlbumFavorite` | Account/favourite slots | Folder creation/renaming/moving remain unexposed |
| `getComments` | Native work comment reader | No assumed chapter comments or copied source identity |
| `getHotTags` | Not previously exposed | Added below |

## Newly connected application paths in this batch

| Upstream API | MangaFlow destination | Constraints |
| --- | --- | --- |
| `getRandomRecommend` | JM source menu · 随机推荐 | Real returned entries only; one page; explicit refresh may change the set |
| `getLatest` | JM source menu · 新上架 | Separate from “最近更新”, preserving site semantics |
| `getSerialization` | JM source menu · 连载作品 | Original validated page count and source IDs |
| `getPromote` + `getPromoteList` | Site-authored promotional collections in source feed picker | Only validated IDs and SDK-supported promo types; no synthetic covers/chapters |
| `getWeeklyPicksList` + `getWeeklyPicksDetail` | Each real 每周必看 issue in source feed picker | Current issue ID checked again before opening; one complete result, no fabricated pagination |
| `getHotTags` | JM source-only small search chips | Selecting a tag starts a real source search; no untrusted HTML is executed |
| `getWatchHistory` | Signed-in JM source menu · 站点浏览记录 | Read only; one page because SDK does not supply a reliable next-page count |
| `getAlbumTrackingList` | Signed-in JM source menu · 追更作品 | Read only; paging offered only when returned list/total support it |
| `register` | JM source-account sheet → separately authored request | Reuses the SDK-selected public API host, route, token generator and encrypted response format; **does not invoke deprecated SDK method** |

### Register protocol review

[JUKOMU v1.1.12 register()](https://github.com/JUKOMU/JMComic-Api-Java/blob/v1.1.12/jmcomic-core/src/main/java/io/github/jukomu/jmcomic/core/client/impl/JmApiClient.java) sends username/password/password_confirm/email but hardcodes `gender=""`, `adult=false`, `PrivacyPolicy=false`. Its deprecated annotation alone does not prove the endpoint is unavailable.

Independently, [delta-comic/jmcomic-sdk](https://github.com/delta-comic/jmcomic-sdk/blob/main/packages/sdk/src/modules/auth.ts) uses `POST /register` with username, email, password, password_confirm, and gender; the application submits `Male`/`Female`. No site membership key or user cookie is part of that documented client request. This supports implementing a separate fixed-parameter registration adapter, not bypassing CAPTCHA, age consent or registration limits.

The MangaFlow registration UI takes explicit adult/terms consent and does not persist username/password or sign in automatically. The request gets a **fresh anonymous client** on the user's configured JM route, sends a single bounded POST and parses the SDK's actual encrypted envelope. A malformed/unconfirmed reply is reported as uncertain, not success. No live registration was attempted, so accepted fields, terms and signup availability are **unverified on a real server**. The UI must not auto-retry a signup after an ambiguous outcome.

## Still not connected; explicit decisions

| SDK functions or subsystem | Current gap / action boundary |
| --- | --- |
| `postComment`, `replyToComment`, `postBlogComment`, `replyToBlogComment` | Comment authorship, existing real account and exact item IDs need explicit write UI |
| `voteComment` | Marked discontinued by SDK; do not expose a fake action |
| `toggleAlbumLike` | API is effectively like-only, not reversible; require clear one-shot explicit UI |
| `manageFavoriteFolder` write actions, `getTagsFavorite`, add/remove favourite tags | Needs accurate remote folder/tag mutation and review UX; preserve local shelf |
| `deleteWatchHistory` | Explicit destructive action and source-owned undo/confirmation needed |
| `getUserProfile`, `editUserProfile`, `forgotPassword` | Profile identity needs the *real UID*, not the saved username; deprecated reset API needs verification |
| `getNotifications`, `markNotification`, `getUnreadCount` | Message read/status ownership and read receipt, without claiming push notifications |
| `getAlbumSertracking`, `setAlbumSertracking` | Single toggle style; use exact current state before mutation, no guessed follow state |
| `getTasks`, `claimTask`, `getCoinBuyList`, buy/charge/ad-free | Task list is read-only candidate; rewards/coin/payment methods are deprecated and must not be simulated |
| `getDailyCheckInStatus`, `doDailyCheckin`, history options | Requires true current account UID/daily ID and one explicit mutation; no blind daily action |
| `JmNovelClient`, `JmCreatorClient` | Distinct *novel*/*creator* content types, not manga covers or chapters; needs a separate product adapter before offering |
| SDK downloader/task manager | MangaFlow already owns concurrent download, cache, retry, page/session/route and storage. Do not start a second downloader or write outside its ownership |
| `getComicRead`, `getAlbumDownloadInfo` | Additional upstream metadata, not grounds to replace the working exact-identity image pipeline |

**Principle:** A Java method is not a feature until its actual source-to-UI path, authorization, response semantics and state ownership exist. Do not conflate installed source code with usable JM server behavior, and do not fake SDK methods that are unsupported server-side.

Delivery boundary: source and reference review only; no tests, local builds, authenticated probes or device execution. Existing main CI packages. The owner verifies installed behavior.
