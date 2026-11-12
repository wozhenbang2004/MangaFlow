# Third-party notices

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
MangaFlow reverses the drag direction for the bottom edge, retains the half-distance resistance,
fills a fixed footer ring, and requests the next page when full rather than waiting for release.
A per-gesture guard prevents repeat requests; Foundation `1.8.2` owns drag/fling/cancel dispatch.
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
