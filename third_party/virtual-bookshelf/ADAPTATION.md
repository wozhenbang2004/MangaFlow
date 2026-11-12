# Virtual bookshelf source adaptation

Upstream: `https://github.com/petargyurov/virtual-bookshelf`

Author: Petar Gyurov. Fixed commit: `3c1073227df161d87eb48e7286bb7748cd22cd4d`.
License: Unlicense; the original full text is in LICENSE.

This directory preserves the six original key source files read for MangaFlow's local shelf. It is not the complete upstream repository: its book data, artwork and deployment files are not bundled. UPSTREAM_README.txt retains the original README bytes as inert reference text. source-manifest.json records original paths, retained paths, addresses and byte identities.

## Mapping

| Original implementation | MangaFlow adaptation |
| --- | --- |
| index.html composes each book from spine, cover and top faces; bookshelf.css positions them and bookshelf.js sets their dimensions | ui/BookshelfBook.kt draws three separate faces with the real manga cover, paper edges and a narrow spine inside a larger touch slot. |
| .spine-title uses vertical-rl, mixed text orientation | BookshelfSpineTitle keeps upright characters, truncates within one/two columns and preserves the full accessible title. |
| .book:hover pivots from the bottom left and rotates/translates the book | The actual press/opening state drives draw-only Compose spring transforms with the same bottom anchor. The fixed combined-clickable region yields ordinary vertical drags to the list. |
| Random colors, patterns, heights and spine widths on initialization | Removed. MangaFlow retains stable slots/order and uses the real cover with theme-aware tint. |
| One-second desktop hover | Replaced with current MangaMotion timing, immediate direct reading, long-press details and real opening/error state. |
| Web page wrapper | LibraryScreen retains the app toolbar/tabs and saved positions, arranges lazy shelf rows and handles the empty shelf. |

BookshelfPlank is the necessary Compose shelf support for these adapted books. The light Shelf Expression reference (`https://sangsara.net/2026/05/25/shelf-expression/`) informed its quiet surface and contact shadow only. No Shelf Expression code or assets are redistributed. KOReader's shelf-style documentation (`https://andyhazz.github.io/bookshelf-docs/use/shelf-styles/`) informed mixing covers and spines; no plugin code is bundled and its preview-first workflow is not adopted.

The Android implementation is a source-backed adaptation, not the original web runtime or an exact reproduction. Source review establishes the mapping only. No tests, local build, emulator, API probe or rendered/device comparison was performed; current device appearance and interaction remain pending user feedback from main CI/CD.

## Cabinet correction · 2026-11-12

The current design keeps full covers rather than the previously rejected narrow-spine arrangement. Actual source faces remain the adaptation base: 4dp binding, visible top/fore-edge paper and bottom-anchored lift. MangaFlow adds theme-drawn cabinet back/side boards and a deeper top/front shelf profile around those faces; these cabinet drawings are our adaptation, not copied Shelf Expression assets. Captions/progress/plain metrics are grouped per book. Side boards remain continuous across lazy bays; an empty bay contains no fabricated books. Dragging raises the bay, cover row and slot, with swipe clipping disabled during extraction. Direct reading, exact original positions and group management remain. No device/visual acceptance is claimed from source review.
