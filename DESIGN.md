# MangaFlow detail · 2026-11-12

## Active user requirement

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

## Revised detail contract · 2026-11-12 (supersedes DETAIL-02 through DETAIL-05)

The user rejected the three-tab/paginated screenshot and explicitly requested the following:

- Synopsis is two lines beside the artwork. Tapping it opens the full copy in a sheet. Do not restore a dedicated Introduction tab.
- Chapters and Comments use quiet text tabs with a narrow underline, not a large segmented capsule.
- The reading action names the actual chapter; continuation keeps its saved page.
- The chapter grid uses small fixed-height tiles with an adaptive four-column tendency on portrait phones. The whole fetched directory is presented by a lazy grid and scrolls continuously, with no range picker or bottom pagination.
- Group, order, search and the last-read locator live in an overlay side drawer. A fixed-size toolbar owns the drawer and download icons.
- Download-mode selection must never insert a status row or resize a tile. The icon changes from download to progress, check or failure in a reserved slot without moving surrounding chapters.
- Real status/genre/update tags and provider metrics use multiple quiet, low-saturation accents. No fabricated tags, counts, ratings or providers.
- Keep exact chapter progress, anonymous source use, existing downloads, comments, and cache intact. The grid and comment list remain in a fixed content area beneath the header. No outer detail scroll, test suite or emulator automation for this change.

This section overrides older paging and section-transition advice in the same document. A successful CI build confirms compilation only; device interaction remains unverified.
