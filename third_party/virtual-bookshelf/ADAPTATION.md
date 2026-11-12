# Virtual bookshelf source adaptation

Upstream: `https://github.com/petargyurov/virtual-bookshelf`

Author: Petar Gyurov. Fixed commit: `3c1073227df161d87eb48e7286bb7748cd22cd4d`.
License: Unlicense; the original full text is in LICENSE.

This directory preserves the six original key source files read for MangaFlow's local shelf. It is not the complete upstream repository: its book data, artwork and deployment files are not bundled. UPSTREAM_README.txt retains the original README bytes as inert reference text. source-manifest.json records original paths, retained paths, addresses and byte identities.

## Flat collection correction · 2026-11-12 (current)

User: “这个书架别弄 3d 了就弄正常的，以及书架别弄追更等功能”. This supersedes the physical cabinet and spine implementations below. The current collection reuses the actual `CoverTile`/`MangaCover` source in `ui/DiscoverScreen.kt`: ordinary complete cover images using Fit, horizontal two-line titles and one quiet line of real chapter/page progress. A normal phone shows three columns, with fewer columns for narrow space/large text. There are no rotated faces, simulated paper/bindings, vertical spine titles, shelf planks, cabinet frames or invented volumes.

The collection no longer accepts shelf-status UI parameters or shows follow/hoard/pause filters, labels or menu actions. Existing user fields and backup data remain in storage. Direct reading, collection order, separate collection/history positions, image collections and statistics remain. `BookshelfBook` only keeps the existing flat press/drag transforms and opening feedback around `MangaCover`; the outer row, cover row and book slot retain their drag elevation. A lifted cover releases swipe recognition/clipping. Left swipe still deletes only after its 55% release threshold, and long-press drag still requires release inside the measured circular trash target. Cancellation or release elsewhere restores the cover. Horizontal title/progress long press retains the details/catalog entry.

The original files, license and the historical mapping below remain archived; the current flat collection does not claim to render or reproduce virtual-bookshelf. No tests, local build or UI automation was performed. Source/diff review is not device appearance or gesture acceptance.

## Historical mapping (superseded)

| Original implementation | MangaFlow adaptation |
| --- | --- |
| index.html composes each book from spine, cover and top faces; bookshelf.css positions them and bookshelf.js sets their dimensions | ui/BookshelfBook.kt now presents the opaque spine as the main face, with a shallow paper top/closed side edge and a small complete real-cover endcap. Fixed spine slots start at 56dp, with 4dp gaps and approximately 224dp height. |
| .spine-title uses vertical-rl, mixed text orientation | BookshelfSpineTitle lays out upright grapheme columns from right to left, truncates within two columns and preserves the full accessible title. This Compose text adaptation is not CSS writing-mode or identical Latin-run shaping. |
| .book:hover pivots from the bottom left and rotates/translates the book | The actual press/opening state drives draw-only Compose spring transforms with the same bottom anchor. The fixed combined-clickable region yields ordinary vertical drags to the list. |
| Random colors, patterns, heights and spine widths on initialization | Removed. MangaFlow retains stored order and stable geometry. Opaque neutral bindings use stable theme-relative shades; the actual cover identifies each book without covering the title. |
| One-second desktop hover | Replaced with current MangaMotion timing, immediate direct reading and real opening/error state. Long-press-drag lifts the book; the short progress area below the shelf retains long-press details/group management. |
| Web page wrapper | LibraryScreen retains the app toolbar/tabs and saved positions, arranges lazy shelf rows and handles the empty shelf. |

BookshelfPlank is the necessary Compose shelf support for these adapted books. It is now a shallow ledge beneath each real row, with no cabinet back/side boards, top frame or fabricated empty bays. Books pack left rather than expanding to cover-card columns. The light Shelf Expression reference (`https://sangsara.net/2026/05/25/shelf-expression/`) informed its quiet surface and contact shadow only. No Shelf Expression code or assets are redistributed. KOReader's shelf-style documentation (`https://andyhazz.github.io/bookshelf-docs/use/shelf-styles/`) informed earlier cover/spine exploration; no plugin code is bundled and its preview-first workflow is not adopted. Native ratings remain in the existing manga detail rather than repeating below each narrow spine.

## Ordinary shelving correction · 2026-11-12 (superseded)

The latest request rejects the cover-facing cabinet below: “这个书架还是太丑了做成那种正常放书的那种方式吧”. The actual pinned `.book`, `.spine`, `.spine-title` and `.top` source was reread for this correction. The current spine-facing layout keeps opaque readable bindings, real cover identity and compact saved progress/group state. Width/font scale determines wrapping while order and the independent favorite/history scroll states remain. Three drawing levels raise the dragged shelf row, book row and book slot; the lifted book disables swipe recognition/clipping. Physical left swipe still commits at 55% on release. Long-press drag only removes on release inside the measured circular trash target; cancellation or release elsewhere restores the book. No tests, local build or device rendering was performed.

The Android implementation is a source-backed adaptation, not the original web runtime or an exact reproduction. Source review establishes the mapping only. No tests, local build, emulator, API probe or rendered/device comparison was performed; current device appearance and interaction remain pending user feedback from main CI/CD.

## Cabinet correction · 2026-11-12 (superseded)

The current design keeps full covers rather than the previously rejected narrow-spine arrangement. Actual source faces remain the adaptation base: 4dp binding, visible top/fore-edge paper and bottom-anchored lift. MangaFlow adds theme-drawn cabinet back/side boards and a deeper top/front shelf profile around those faces; these cabinet drawings are our adaptation, not copied Shelf Expression assets. Captions/progress/plain metrics are grouped per book. Side boards remain continuous across lazy bays; an empty bay contains no fabricated books. Dragging raises the bay, cover row and slot, with swipe clipping disabled during extraction. Direct reading, exact original positions and group management remain. No device/visual acceptance is claimed from source review.
