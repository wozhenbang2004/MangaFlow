# MangaFlow detail · 2026-11-12

Current detail contract: [Large artwork and scrolling](#large-artwork-and-scrolling--2026-11-12-current-contract). Older decisions below are retained with their superseding requirements.

## Original density requirement

“我不希望漫画详情页是这样的 uiux 请你把信息密度做高点我不希望我是滑下去的请你设计好 uiux”

The supplied 午夜心旋律 screenshot shows two work comments before the catalog, repeated group labels under chapters, four very tall chapter rows and an overlaid reading bar. Current source still uses a 510dp hero. The prior 236dp note was not implemented and is not a valid current baseline.

## Structure and component decisions

| ID | Decision and concrete implementation | Observable requirement |
| --- | --- | --- |
| DETAIL-01 | A bounded Column contains navigation, a compact horizontal cover/info header, native metric badges, section tabs, the active content region and the reading action. No outer LazyColumn or overlay bar. | Chapters are the default visible content. No need to pass a cover, synopsis or comments to select a chapter. |
| DETAIL-02 | Adapt Mihon's fixed MangaAndSourceTitlesSmall composition: an 84×112dp cover, 16dp gutter and adjacent title/author/status/tags. Full title/metadata remain available in Introduction. Small-height/landscape/large-font layouts use a compact title header. | Artwork remains identifiable; the shared header leaves most available space for the task and can yield space to the keyboard. |
| DETAIL-03 | Adapt Ovio Liquid Tabs minimal into Compose: one indicator with separately animated leading/trailing edges, stationary labels and immediate content selection. Three sections: Chapters, Introduction, Comments. | Selected section is clear; rapid switching does not reset each section's saved position. No decorative counters or duplicate headings. |
| DETAIL-04 | Reflow existing chapter content into an adaptive grid. Compute rows from the bounded area and font scale; paginate the grid instead of extending the whole page. Adapt BoardUI's controlled pager and disabled boundaries, with 48dp actions and a range picker for many pages. | Ordinary portrait widths show roughly three columns; larger fonts reduce columns. Every chapter remains reachable; resizing preserves the anchor chapter where possible. |
| DETAIL-05 | A compact toolbar holds group selection, order, search, download mode and, when available, a last-read locator. Default to the first source group; All remains available. A visible download mode changes the tile action and shows its label. Long press additionally opens full chapter details/actions. | No repeated default-group subtitle in every tile. Read/download states remain distinguishable; downloading/queued/completed items cannot be enqueued again. |
| DETAIL-06 | Render the existing full comments panel directly in the Comments section. Keep provider selection above its bounded list, real avatars, replies, native metrics, matching, pagination and failures. Introduction has its own scroll area. | Switching sections preserves list positions. Comments need no second “all comments” sheet. Long content scrolls only within its own section. |
| DETAIL-07 | One compact reading action participates in normal layout and uses the existing onContinue path for saved progress. Catalog failures are an inline summary with detailed cause on demand. Wrap the detail route in the existing SaveableStateHolder. | A failed refresh cannot disable saved continuation. Returning from the reader restores the catalog section/group/search/page. The bottom action never covers chapters. |

## Sources and adaptation boundaries

- Mihon `524fa53043f2f5b2321ea13027e44f093ab4245c`, `app/src/main/java/eu/kanade/presentation/manga/components/MangaInfoHeader.kt`, `MangaAndSourceTitlesSmall`, Apache-2.0. Source read; no screenshot of that exact fixed version obtained. Only its compact header composition is adapted.
- Ovio `81a1bcb6737356038163728b3b2a657c9dcffa32`, `components/ovio/liquid-tabs/{liquid-tabs.tsx,use-liquid.ts,worlds/minimal.tsx}`, MIT. Source and saved Minimal screenshot read. Leading/trailing springs 520/230, critical damping and slight stretch are adapted to Compose; no WebView or extra runtime dependency.
- BoardUI saved source `components/base/pagination/pagination.tsx`, retrieved 2026-11-01, SHA256 `47fbc308182f5ad32415f6c51d917241db02a9b340c6f2fb536554ce784b33ac`, MIT. Original source and the saved local rendering read. Controlled state, boundary disabling and compact arrows are adapted; the mobile range picker replaces the desktop numeric window.
- Existing MangaFlow metric badges, comment/reply components, chapter actions and artwork requests are reused. Preserve native site scales and the user's removal of outward navigation links.

## Verification boundary

This is an implementation and packaging slice. Read current code, inspect the diff and compile the feature branch before main. Do not run tests, UI automation, emulator checks or fabricated screenshot fixtures. API contracts are unchanged. Real device layout, touch feel and large-font behavior remain pending user experience; source arithmetic and a successful build are not visual acceptance.

The source review found no new blocker in catalog paging, selection, download guards or history continuation. Provider selection and comment-list positions are retained; the existing reply sublist still releases expanded replies when leaving its composition and loads again when reopened. This slice does not claim persistent reply-thread caching.

## Motion system · 2026-11-12

Current request: “去深度优化我们现在漫画软件的动画系统可以深度学习下我们审美库先”. This slice changes motion and the state updates that disrupt it. Existing source/account contracts, artwork, dense layouts, reader modes and saved progress remain the product baseline. Other ongoing detail/home redesigns must be preserved when integrating.

| ID | Adopted mechanism and implementation | Observable requirement |
| --- | --- | --- |
| MOTION-01 | Shared press, swap, panel and navigation roles. Adapt beUI `a866cb2` physics/lifecycle and Ovio `81a1bcb` indicator edges; retain the approved Jelly reference's stationary labels and Josh reference's immediate press/release. Compose supplies the animation engine. | Repeated input retargets the current motion. No timers delay real actions or simulate completion. |
| MOTION-02 | Ovio leading/trailing springs animate only a tab's indicator. Press transforms and drawer depth read animated values in drawing layers. | Layout positions, touch targets and chapter dimensions stay stable; tab labels do not deform; no whole-list entrance on refresh. |
| MOTION-03 | Forward/back route transitions use opposite short translations, fixed viewport bounds and outgoing interaction gates. Vaul's actual drawer-offset coupling provides a small background recession only for root navigation. | Returning restores the existing saveable scroll/section state. Drawer dragging stays attached to the finger. Reader images never shrink with small tool dialogs. |
| MOTION-04 | Adapt beUI PresenceGate/MorphingModal: keep the outgoing visual until it settles, release its interaction/focus immediately, and animate only the panel. Bottom sheets hide before disposal. | Close/reopen can interrupt an exit; no invisible control receives actions, no stacked content makes a panel grow and shrink. |
| MOTION-05 | Reader chrome is an overlay with visibility-independent system insets. Scrubbing previews a page and commits once on release; horizontal history uses settled pages. Brightness previews locally before one persistence update. | Toolbars do not push artwork; dragging a slider does not decode or save every intermediate page. Preserve exact page/long-image position. |
| MOTION-06 | Short cache work gets delayed busy feedback, with fixed reserved slots. Known image aspect ratios survive thumbnail decoding. Icon changes use a fixed slot. | No flash of a spinner for a brief hit, no fake progress, no layout movement when download state changes. |
| MOTION-07 | Observe Android's animator-duration setting for reduced motion; disable additional spatial effects and carousel autoplay when disabled. Native gesture/animation timing still uses the platform clock. | All controls and status information remain usable with animations disabled. No completion depends on a decorative animation. |

### Reference evidence and adaptation

- beUI `a866cb2f37ce68c3fc0c66425e86e3f31566e4be`: `lib/ease.ts`, `lib/presence-gate.tsx`, `components/motion/morphing-modal.tsx`, MIT. Original code read; saved official modal/tab screenshots inspected. The screenshot deployment was not proven to match the archived commit. Adapt physics and presence ownership, without copying its full-screen blur or changing the reader viewport.
- Ovio `81a1bcb6737356038163728b3b2a657c9dcffa32`: existing Liquid Tabs reference above. Keep independent edges, stationary text and continuous retargeting. The existing catalog grouping/layout is outside this motion slice.
- Vaul `3e97aac6a38e4481bade71d7233ed6002e80f9b0` (1.1.2): `src/use-scale-background.ts`, `src/index.tsx`, MIT. Original video frame inspected. Adapt progress-coupled background depth to the existing side drawer; preserve Material's gesture arbitration instead of copying Web velocity units.
- Aesthetic-library records `uiux-motion-transition-system-20261110`, `uiux-motion-easing-kubai-20261106`, `uiux-doc-component-tabs-fluid`, `uiux-doc-component-button-tactile-review`: original motion-system text, saved approved tabs and Josh source/review read. These establish motion roles and prior preferences, not approval of this new Android implementation.
- Android official animation, graphicsLayer, DrawerState and bottom-sheet documentation was checked against the APIs used. Web stiffness/damping/mass are converted as `k/m` and `c/(2*sqrt(k*m))`; equal parameters do not guarantee identical frames across engines.

Implementation, main CI compilation, release packaging and phone experience are separate evidence. Follow AGENTS.md's current direct-main delivery rules: review source, submit to main and confirm CI triggered, without a separate branch build or waiting for completion. No tests, lint, emulator, UI automation or screenshot fixtures are permitted in this slice. Phone smoothness, visual quality and accessibility interaction remain pending user experience after delivery.

## Ranking and shelf density · 2026-11-12

User feedback: “图一顶上这块占用空间太大了图二信息密度太低了”. The supplied ranking screenshot repeats the selected period/audience below two navigation rows; the shelf screenshot repeats the page title below the app bar, adds 24dp gaps between large history cards, and repeats “继续阅读” in every item.

| ID | Adopted change | Required behavior |
| --- | --- | --- |
| DENSITY-01 | Put Recommend / Ranking / Latest directly in the 52dp discovery toolbar, with the existing drawer, history and search actions. Reuse the fixed Ovio Liquid Tabs adaptation through `BrowseTabs`, with stationary labels, a quiet 32dp indicator and 48dp tab targets. | No separate brand/header row above discovery tabs. Selecting a tab closes and clears search in one browse-state change. Existing per-query/per-period caches and list positions remain in use. |
| DENSITY-02 | Ranking has one filter row: day/week/month/total, the current audience menu and refresh. Remove the duplicate period/audience heading and caption. Keep narrow-width period scrolling; the audience control stays visible. | Current period and audience remain identifiable. Male/female selection, refresh and native per-period heat stay available. The reserved 1dp refresh track cannot shift list content. |
| DENSITY-03 | Ranking rows use 54×76dp covers, a minimum 30dp rank column, 12dp information gutter, 3dp text gaps and 8dp row gaps. Remove decorative forward arrows. | Preserve two-line titles, authors and real source badges. Longer ranks grow in one line. Rows remain wholly clickable. Do not invent metrics or reduce title/body text to create density. |
| DENSITY-04 | The shelf title and cloud action share the 52dp app bar. Below it, keep only the 48dp collection/history tabs. Adapt the existing recent-reading row to remove the card surface and repeated continuation label: 46×64dp cover, title, exact chapter/page and source badges, 6dp vertical padding and 4dp row gaps. | Clicking a history row still calls direct continuation with the original `ReadingProgress`. No detail-screen detour, changed page index or changed storage. Collection/history keep separate saved scroll states. |
| DENSITY-05 | Collections use the existing dense cover tile, 100dp adaptive minimum width, 16dp side padding and 10dp column gaps; increase the minimum with system font scale. | Ordinary portrait widths fit three covers where space permits; large text may reduce columns. Keep real badges and readable title roles. |

Scope uses the existing MangaFlow components and Ovio source already attributed above. The aesthetic-library proximity/minimum-expression practice supplies the grouping rationale; it does not certify a rendered result. This supersedes the earlier “shelves and ranking rows retain their existing layout” note for these two views only. Account, downloads, settings, reader, source APIs and signing remain outside this slice. Build/package and source review only; no tests, lint, emulator, UI automation or generated screenshot fixtures. Device density, large-font layout and touch feel await user experience.

## Revised detail contract · 2026-11-12 (supersedes DETAIL-02 through DETAIL-05)

The user rejected the three-tab/paginated screenshot and explicitly requested the following:

- Synopsis is two lines beside the artwork. Tapping it opens the full copy in a sheet. Do not restore a dedicated Introduction tab.
- Chapters and Comments use quiet text tabs with a narrow underline, not a large segmented capsule.
- The reading action names the actual chapter; continuation keeps its saved page.
- The chapter grid uses small fixed-height tiles with an adaptive four-column tendency on portrait phones. The whole fetched directory is presented by a lazy grid and scrolls continuously, with no range picker or bottom pagination.
- The latest search-overlay follow-up removes the former chapter-tool drawer. Keep simple horizontal group switching and the download action in one fixed-height toolbar; remove chapter search, order and last-read locator controls.
- Download-mode selection must never insert a status row or resize a tile. The icon changes from download to progress, check or failure in a reserved slot without moving surrounding chapters.
- Real status/genre/update tags and provider metrics use multiple quiet, low-saturation accents. No fabricated tags, counts, ratings or providers.
- Keep exact chapter progress, anonymous source use, existing downloads, comments, and cache intact. The grid and comment list remain in a fixed content area beneath the header. No outer detail scroll, test suite or emulator automation for this change.

This section overrides older paging and section-transition advice in the same document. A successful CI build confirms compilation only; device interaction remains unverified.

Motion integration preserves this revised layout: the shared Ovio edge animation uses the actual compact text widths, 38dp tab height and 32dp underline; it does not restore the former capsule. The later motion feedback below adds a short content transition only when the user changes sections. Catalog icons keep their existing 14dp slots, and the fixed toolbar retains the latest mode label and icons. The user's “要啊直接合到 main 啊” explicitly authorizes this integration and the existing signed-release workflow; the current direct-main rules in AGENTS.md supersede the former branch-compilation gate.

## Large artwork and scrolling · 2026-11-12 (current contract)

User: "没事 ci 触发了就行别管了你去改新的这个问题漫画详情我希望做成 kayokoi.site 这个网站那样的大图而且这个网站滑动那个大图相关的逻辑也要学过来以及为什么要弄黑色背景额". Recorded 2026-11-12 07:31 UTC. This replaces DETAIL-01/02's fixed small header and the revised contract's prohibition on outer header motion. The dense catalog and two-tab requirements remain active.

| ID | Implementation decision | Observable requirement and evidence boundary |
| --- | --- | --- |
| DETAIL-ART-01 | Real cover artwork fills the upper background, at a stable size while the foreground moves. Portrait uses a roughly 60% viewport hero, capped at 560dp and reduced on short screens to leave the reading controls reachable. Landscape uses Fit for the portrait source. | Artwork is the focal point. No tiny cover beside a large empty black area; no fabricated landscape asset. The phone composition adapts the site's full-viewport blog cover to leave the catalog visible. |
| DETAIL-ART-02 | Reuse Material3 1.3.2 exitUntilCollapsed nested scroll with Foundation 1.8.2 gestures. Upward input moves the foreground information out and brings the content surface over the fixed image; the list then scrolls. Downward input returns the list to its beginning before expanding the artwork. Disable Material endpoint snapping; retain Foundation fling. | Artwork does not resize, zoom or gain blur according to scroll. A visible down-arrow smoothly exposes the catalog; new vertical user input cancels that shortcut animation. Header and each list retain their own scroll state. |
| DETAIL-ART-03 | Place title, authors, a two-line inline synopsis, real tags and metrics on a readable theme-colored surface over the art. Tapping synopsis or the toolbar title opens the full information sheet. Short-height layouts keep the toolbar title/sheet and more space for the catalog. | No Introduction tab. Preserve quiet Chapters / Comments tabs, compact lazy chapter tiles, inline group switching, reserved download-status icons, exact return position and chapter-named reading action. |
| DETAIL-ART-04 | Replace hardcoded detail dark constants with Material color roles. Adapt accent text for light/dark contrast, including comments, drawer, search, errors and download states. Let non-reader system bars follow the same app theme. | Light system theme produces the app's warm light detail background with readable dark text. Dark system theme stays coherent. Reader-specific background preferences stay independent. |

### Reference and reuse evidence

- The requested https://kayokoi.site/ returned public HTML declaring Sakurairo 3.0.10, repository mirai-mamori/Sakurairo, tag commit bd4a4a33f27da4a3b52baf9dccf0507cd5393f01. Live HTML has centerbg height 100vh and site_bg_as_cover=true. Same-version source `style.css` body uses background-attachment:fixed; `src/app/coverBackground.ts` assigns the body background; `src/app/global-func.js` headertop_down smoothly scrolls to content. Live body uses background-size:auto, so our portrait Crop is an explicit mobile adaptation, not a claim about the site.
- Same-version mobile navigation hides on downward scroll and reappears upward; this app keeps a small reachable navigation row for back/favorite and changes the content title with the header state. The fixed-artwork/foreground relationship and smooth content shortcut are adopted; blog waves, social controls, random artwork and desktop wheel interception are not part of this manga task.
- The site's live style/scripts failed to load in this environment (upstream 503 / smoothscroll 404), and the browser displayed its loader. Actual rendered site motion is unverified. Source evidence does not establish an unmodified live deployment. No scroll-driven zoom or blur was found; the theme's 0.8-second blur is an entry animation only.
- Sakurairo is GPL v2 or later. Its behavior is used as a reference; no theme code or assets are copied. The implementation reuses existing MangaFlow artwork, metadata and catalog components plus Apache-2.0 AndroidX APIs: Google Maven BOM 2025.05.01 maps to Material3 1.3.2 and Foundation/UI 1.8.2. Their published source JARs were read, including AppBar.kt ExitUntilCollapsed and Scrollable.kt pre/self/post dispatch.
- Source review is required; tests and UI automation remain prohibited. Per the latest explicit instruction, verify CI triggered and stop waiting. Compilation, actual device layout, image crop and gesture feel remain unverified until build/user feedback; do not call the new design visually accepted.

The shared-motion integration retains this large-artwork contract, its nested-scroll and shortcut cancellation behavior, and all Material theme roles. It only replaces the existing tab indicator, button feedback, icon swaps and sheet presence with the shared motion components. The original landscape Fit/Crop condition is cached at the outer layout scope to fix its Kotlin receiver error; artwork size and scrolling are unchanged by that repair.


## Mixed-source comments · 2026-11-12

The latest review screenshot rejects a source-by-source Comments tab that spends its first screen on three repeated rating cards and the matched manga title. This supersedes the previous standalone review-source carousel:

- The default Comments tab is one mixed LazyColumn of confirmed Copy, Bangumi and AniList reviews, in newest-first order using each provider's reported date. Never include unconfirmed matches, unrelated anime entries or fabricated content.
- Keep stable native provider/comment/reply IDs, author/avatar/date, original score scales, spoiler handling, and exact-source reply requests. Every row shows a compact, low-saturation source badge that adapts to light/dark backgrounds.
- Remove the repeated score row, subject title and Source / Pending management sheet. The detail header already shows metrics. Only actual failures and uncertain matches with available candidates expose compact, expandable actions after the comments; binding still requires an explicit user choice.
- Use a compact 28dp avatar, 8dp row padding and readable 13sp comment text. Auto-load the next eligible provider page when the user nears the merged feed's end; never request multiple providers at once or imply that loaded count is the total site count.
- Keep authentication, API protocols, caching and chapter discussions unchanged. Direct main submission followed by the existing main CI/CD build is the delivery process; the user alone verifies on-device appearance and behavior. No tests are added or run.

## Motion coverage and artwork toolbar · 2026-11-12

User: “好几个按钮 tab 的切换点击都没有舒服优雅的动画以及漫画详情那上面一大条白色不好吧。。” The three supplied phone screenshots show the settings choices, download tabs and a white toolbar separating the artwork from the status area. Source review confirmed that those settings/download controls still bypassed the shared motion system. This feedback supplements MOTION-01–07 and replaces the earlier always-opaque detail toolbar and instant section-content replacement.

| ID | Concrete change | Observable requirement |
| --- | --- | --- |
| MOTION-08 | Extend the existing Ovio `81a1bcb` two-edge indicator to settings/reader modes and theme swatches, download filters and ranking periods. Fade label colors, reserve both label weights for compact tabs, and provide a soft pressed background. Preserve RadioButton semantics for preferences. | Indicators travel between choices; text and hit targets do not jump. Settings includes the already-supported WEBTOON mode, so its current value is never reported as vertical reading. Choices update immediately. |
| MOTION-09 | Use the existing beUI-derived timing/presence roles for a 10dp directional content transition in detail sections, library tabs and download filters. Only the selected index triggers it; leaving content is non-interactive. Retain separate list states and fixed headers. | Repeated selection can interrupt the transition. Download progress, refreshed data and updated counts do not animate the whole list. Returning to each tab restores its previous position. |
| MOTION-10 | Continue the actual cover under the status area. Keep back/favorite/retry in small translucent plates. Use Material3 header collapse progress for the toolbar surface, icon contrast and actual manga title; no delayed spring on a finger-driven scroll. Keep a subtle top scrim for contrast. | No white strip above expanded artwork. The normal theme surface appears as the header collapses; title and controls stay readable. Reader/root/detail system-bar ownership is exclusive to the active route. Existing fixed-artwork motion, dense catalog, mixed comments and bottom reading action remain intact. |

Settings, download actions and the recommendation cover reuse the existing shared press behavior with stable outer click bounds. Reader mode-specific options adjust their height over the shared content duration. No new UI dependency or source protocol is introduced; the existing fixed Ovio/beUI notices remain bundled. The screenshots establish the before state only. This batch uses source review and a direct-main CI trigger, with no local/branch build, tests, lint, simulator or UI automation; device appearance, gesture feel and build outcome remain unverified at submission.

## Floating search and liquid surfaces · 2026-11-12

Search uses a separate overlay over the retained browse snapshot. Query changes do not replace the underlying carousel, and only the overlay receives keyboard insets. The ViewModel retains the background across configuration changes; per-query lists retain their own positions. The panel keeps its content until its exit finishes, intercepts blank-area taps and dismisses through Back or the surrounding scrim. Preserve the full-width carousel from `35217b8` and the shared motion from `112a2fe`.

`LiquidGlassSurface` shares the existing Haze 1.6.0 source/effect pair across search, artwork information and floating artwork controls. It samples the actual background, applies 12dp blur and theme tint, and adds a quiet highlight and rounded edge. Android 13+ refracts only the rim through an original AGSL shader. The shader is compiled once per surface, with dimensions passed as uniforms. Text and icons remain outside that optical layer. Android 12 retains blur; older devices retain a readable tinted surface. Glass on toolbar controls fades with header collapse, while the ordinary toolbar surface appears.

API references checked against the pinned dependency: [Haze 1.6.0 usage](https://github.com/chrisbanes/haze/blob/1.6.0/docs/usage.md), [effect scope](https://github.com/chrisbanes/haze/blob/1.6.0/haze/src/commonMain/kotlin/dev/chrisbanes/haze/HazeChild.kt) and [Android AGSL](https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl). No dependency upgrade, API request or storage migration is part of this change. Source review and the main CI trigger do not establish visual acceptance or device smoothness.

## Reader controls and continuous downloads · 2026-11-12

The latest reader screenshots reject the large black gradients, dark oversized dialogs, thick slider handles and sparse chapter rows. Downloads must open directly and retain the ordinary directory and discussion experience. This supersedes the former downloaded-only entry and chapter-only presentation rules.

- Floating reader plates follow the app theme, with a subtle outline/shadow; paper color remains independent. The page and dim controls use a 3dp track, round thumb and at least a 48dp interaction area. Dragging previews locally and commits at release.
- Settings stay compact and scroll within short screens. The chapter dialog uses an adaptive grid, one group selector, a current-chapter selection and reserved saved/progress icons; it does not repeat the default group in each tile.
- A download row's cover/title opens its chapter and restores the saved image location. A small fixed action slot handles pause/resume/delete; there is no extra offline-reading CTA.
- The reader keeps original page identities and uses available local images page by page. Saving the opened chapter runs alongside reading, sharing transfers with the worker. Only explicit user actions restart paused or removed work. Full saved directories and online updates let local chapters continue into other chapters.
- The reader's mixed comments label each source and distinguish “本话” from “作品”. Only Copy currently provides exact chapter comments; confirmed Bangumi/AniList work reviews are additional discussion. Provider spoilers and long reviews stay folded, ordinary work comments show compact previews. The direct comment sheet leaves image progress intact, and refresh retrieves current content when the source is reachable.
- Source and diff review only. Build/release belongs to main CI/CD; compilation, device appearance, touch feel and live service behavior are not claimed as verified by this change.
