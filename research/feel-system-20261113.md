# MangaFlow Feel System · 2026-11-13

## Scope and baseline

User: “快而不硬，软而不拖，活而不乱。” This is a global input → action → result upgrade, not a request for more decorative animation. The current task explicitly authorizes an isolated branch, PR, complete Lint/build/Release checks and actual device interaction if available. Preserve navigation, black/white/neutral surfaces, source accents, existing fixed liquid/frosted roles, original images, history and downloads. No bookshelf sorting, new animation runtime or large dependency.

Audit started from main `3d2ec9b3c0d04c6f36f06996c1eb4227ffbc9173`. Its release 0.1.126 / run `43368251904` failed at Lint: NonObservableLocale, three StateFlowValueCalledInComposition occurrences and unused Scaffold padding (5 errors, 218 warnings, 2 hints). The preceding 0.1.125 / run `43367315040` succeeded. Build success does not establish Android startup or hand feel.

## Research and adoption

- AndroidX animation: `Animatable.animateTo` and springs continue current geometry/velocity and replace in-flight motion. Keep existing Ovio two-edge springs rather than add a timer/queue or second animation engine. https://developer.android.com/reference/kotlin/androidx/compose/animation/core/Animatable ; https://developer.android.com/develop/ui/compose/animation/customize
- AndroidX interaction/pointer passes: observe down for immediate feedback without consuming it; Foundation still decides click, long press and scroll. Cancel the pressed drawing on slop, consumed movement, multi-touch, bounds or disposal. https://developer.android.com/develop/ui/compose/touch-input/user-interactions/handling-interactions ; https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures
- Material3 pull-to-refresh: reuse native nested scrolling, resistance and release dispatch, with the existing circular visual language. No custom scroll recognizer, fling-to-refresh, forced content translation or second overscroll spring. https://developer.android.com/reference/kotlin/androidx/compose/material3/pulltorefresh/pullToRefresh.modifier
- Android semantic haptics: ViewCompat/HapticFeedbackConstantsCompat, honoring view/system preferences and API/hardware fallback. No VIBRATE permission, amplitude patterns or ignore-settings flags. https://developer.android.com/develop/ui/views/haptics/haptic-feedback
- Accessibility: preserve Foundation selectable/clickable/toggleable, native dialog/sheet focus, progress semantics and stable targets. Observe TalkBack changes rather than take a one-time snapshot. https://developer.android.com/develop/ui/compose/accessibility/api-defaults
- Existing real adopted sources remain: Ovio `81a1bcb`, beUI `a866cb2`, Vaul and AndroidX adaptations recorded in THIRD_PARTY_NOTICES. Saved approved fluid/Jelly tabs guide material-only deformation and stationary labels. Haze 2.1 official capture/member/input-sharing policies and six modules are retained; no optical fidelity claim comes from these source reads. https://github.com/chrisbanes/haze/blob/main/CHANGELOG.md

## Audit: actual code before this change

| Area | Baseline status | Correction / retained boundary |
| --- | --- | --- |
| MangaMotion and common components | Implemented: role tokens, press transform, retained dialogs/sheets, icon swaps, pane input gates, delayed busy, animator scale observer. Device feel unverified. | Press relied on scroll-delayed InteractionSource; motion-off press lacked a static response. Common text/outlined/filled buttons still used default feedback. |
| Main/sub-tabs | Implemented: Ovio independent edges, measured labels, spring retargeting, saved positions and keyed content. No evidence of an animation queue. | Consolidate the existing edge constants. Stop outgoing list flings; retain immediate selection and change-only semantic haptics. |
| Drawer | Implemented: official Material DrawerState/anchored gestures, actual-offset background transform, one request identity, canceled-navigation guards and persistent Haze source. Device reverse drag unverified. | Keep the native single drag owner and current navigation contract; don't add another drag recognizer or animate an already finger-driven offset. |
| Search | Implemented: retained reversible beUI shell/foreground, early focus, keyboard dismissal, query positions and hidden-input gates. | Search result covers lacked selected-placement sharing. Add the same route-level cover transition used by the browse list. |
| Header selector / menus | Implemented: permanent glass, retained presence and fixed label geometry. | Remove 70%/40% animation-completion click gates and the outgoing overlay's input-consuming gate, while keeping disabled outgoing controls inaccessible. Retain native modal focus/close behavior. |
| Covers/grid/list | Implemented: placement-specific shared covers and stable keyed lists. | Reuse common immediate pressure, preserve exact image/progress identity; stop inactive flings. |
| Shelf | Flat cover grid with long-press drag-to-trash and undo. | **No free placement or drag sorting exists.** Keep removal only; adopt immediate press without multiplying transforms. |
| Swipe/pull | Implemented: 55% left-swipe release deletion; 96dp bottom pull, threshold hysteresis, cancellation, bounded velocity, accessible load action. | Centralize bespoke haptics; retain axis/slop arbitration, original release thresholds and undo-before-file-deletion. Add 80dp native top refresh. |
| Reader | Implemented: stable manifest/image identity, per-chapter offset, settled-page saves, zoom/pull arbitration, predictive back and release-only scrub commit. | Remove repetitive per-page scrub vibration. Preserve ordinary page/scroll silence and do not introduce image scaling. |
| Loading/error/retry | Single HTTP call bounded at 45s; retained browse/catalog data and explicit retry exist. | Multiple sequential calls had no operation budget; add one total budget. Convert only that timeout into a recoverable error, retaining true parent cancellation. |
| Favorite/download/delete/result | Local favorite already persisted; download queue and delayed physical deletion/undo already exist. | Favorite clicks were dropped during cloud sync. Keep latest intent with a single worker; prequery/write/readback, never blind replay after uncertainty. Replace queued transient snackbars with latest feedback; undo keeps its independent owner. |
| Accessibility/performance | Animator observer, native semantics, font-aware headers and deferred glass draws existed. Runtime/frame-rate evidence absent. | Observe touch exploration centrally, stop autoplay immediately, retain static press/loader when motion is off, avoid per-reader scroll recomposition and new captures. |

## Common contract and parameters

| Role | Adopted values / ownership |
| --- | --- |
| Press | Immediate non-consuming Initial observation. Attack k=1600, damping=1; release k=833, damping=.866. Icons .94, broad surfaces .985; bounded artwork .965 and existing .99 rows retain their role. No added glow/ripple. Draw-time transform only; motion-off static alpha .72. |
| Tabs | Existing leading k=520/damping=1.009, trailing k=230/damping=.989, direction swaps roles. Labels/hit regions remain stationary; latest target replaces predecessor. Selection haptic only when the index changes. |
| Navigation | k=520/damping=1; opacity 160ms enter / 120ms leave; normal pane travel 10dp, chapter/comments soft travel 24dp. Existing cover identity/spring and predictive-back cancellation remain. |
| Search / panels | Existing search k=230/.78 and foreground 320ms; panels k=840/1.38, 180/120ms opacity. No business action waits for decorative completion. Persistent glass retains its source/role. |
| Drag / pull | Existing k=420/.86 return with bounded actual release velocity; direct deltas are not spring-smoothed. Delete .55 width, rearm below .47. Bottom pull 96dp, rearm .88; top refresh native 80dp, one threshold feedback per deliberate crossing. Ring remains circular. |
| Haptic events | Selection, Threshold, Confirm, Reject, Undo. One successful pulse per window per >=70ms; view/system settings and compatibility fallback remain authoritative. No pulses on ordinary scrolling, paging, auto-refresh results or download progress. Default platform long-press accessibility feedback remains platform-owned. |
| State | Busy visual delayed 160ms; results/errors never delayed. Browse/search/author budget 25s, full directory 35s, chapter manifest 25s, explicit cloud reconciliation 45s. Timeout shows error/retry and retains existing data; no automatic credential/mutation retry. |

## Page integration and requirements

| User requirement | Integration / evidence |
| --- | --- |
| Immediate press, reverse actions | Common icon/text/outlined/filled wrappers; shared surface press; all original callbacks remain immediate. Cloud favorites coalesce latest desired state without dropping local toggles. |
| Scroll/side swipe/long press | Native Foundation arbitration; raw press never consumes input. Swipe-to-trash and drag-to-trash remain separate deliberate gestures. Inactive library/download/browse panes stop their existing fling; retained positions are not cleared. |
| Fluid primary/child tabs | Existing MotionTabs and role tokens throughout ranking, library, download filters, settings, reader modes and detail groups; chapters/comments use soft directional transition. |
| Search / drawer / overlays | Existing single-owner interruptible search/drawer retained. Selector no longer waits for transition percentages. Popover/dialog use nondeprecated retained rememberTransition; shared glass press emits Cancel on cancellation. |
| Refresh/loading/failure | Native top refresh and existing bottom release-load; delayed semantic busy indicator, total foreground deadlines, content-retaining errors and explicit retry. |
| Favorite/download/delete/undo | Immediate local favorite and icon swap; explicit downloads retain queue state; deletion hides item then commits after undo, with semantic undo feedback. Transient global notices replace one another; they cannot delay separate undo state. |
| Reader restraint | Silent continuous scrub/page/scroll, no new image deformation, unchanged page/offset/history/zoom and end-of-chapter gesture arbitration. |
| Cover → detail | Browse/carousel/rank transitions preserved; search uses a query-specific placement key and shared route scope. |
| Accessibility | Live animator preference and touch-exploration listener; static feedback in motion-off mode; no forced haptics; Foundation semantics and large-font measurement retained. Error messages announce politely. |
| Release/startup | Non-main workflow performs full lint, assembleDebug and minified assembleRelease and preserves both APKs + R8 mapping. Keep exact RoomDatabase constructor rules and independent diagnostics. Device startup must be observed separately. |

## Verification status

- Independent read-only audit found and guided corrections for delayed press, dropped reverse favorite, queued notices, inactive flings, nonobservable TalkBack state, dense scrub haptics and unbounded whole-directory duration.
- No unit/mock/snapshot/benchmark test assets added or executed. Static source review and `git diff --check` are not device evidence.
- Build/CI evidence will be updated below after the branch workflows complete. Existing signed main release workflow is not changed or triggered by this branch.
- Environment inventory has no `adb`, Android SDK/emulator or `/dev/kvm`. No connected Android device has been observed. Installation/startup, real rapid tap/reverse drag, TalkBack, large-font layout, frame pacing, 60/90/120Hz and phone/tablet appearance remain **unverified**. Do not claim an emulator, screenshots, FPS or physical haptics were observed.
- Actual acceptance path: rapid A→B→A tabs and search toggles; drawer drag/reverse; scroll then short/long/side swipe; favorite on/off during synchronization; cancel/undo deletion; fresh/refreshed/failed request and explicit retry; chapter navigation/scrub/progress restoration; system animator scale 0 and haptics off; TalkBack and large type; install and cold start the minified APK.

No feature is marked device-accepted solely because it exists in source or compiles.

## Concurrent main integration

Before PR creation, main advanced to `9fa02c0` through `7c9cc9a` (UI-CONTINUITY-13A–G). Integrated its centered/compact reading dock, grouped score typography, centered manual gallery, bounded volume retry, flowing child controls, search route-completion cleanup and download chapter grid/menu. Conflicts preserve these newer decisions. Search uses a query-specific key and keeps its source until the actual entering transition finishes, then follows the existing clear-search behavior. The gallery is now manual, so it has no autoplay timer to cancel. Full CI must run again on the integrated head.

## Delivery authorization update

User follow-up in this task: “为什么不直接和到 main？” This supersedes holding the work on a branch-only PR. Finish integration and the required build/Lint gates, merge to latest main, and observe its normal signed Release result without another confirmation. Device operation remains separately unverified.

## Verification log

- Source integration after latest main: independent read-only review of local `9ecc324` found no remaining blocker within the modified feedback/search/download integration. This is source review, not real-device evidence.
- Functional remote head `a0d9d503cd17ffab685b511175019efa51ac3843`: branch run `43373980339` passed full Android Lint and uploaded both required HTML/XML reports (`mangaflow-lint-build-30-1`, artifact `14013642305`). Parsed XML: **0 errors, 218 warnings, 2 hints**. Leading warning families: 124 SyntheticAccessor, 37 UseKtx, 14 RequiresFeature; none are globally suppressed. The same run then passed :app:assembleDebug and :app:assembleRelease (R8 enabled), uploaded APKs and the release mapping, and completed successfully. Logs show Lint/build success at 05:47:50 UTC and packaging success at 05:51:19 UTC.
- First feature run `43373568520` exposed a missing DownloadsScreen LaunchedEffect import and missing active route input. These were corrected by adding the import and passing root activity to the download pane; no check was disabled.
- The final housekeeping removes four unused imports only. Normal signed main Release CI verifies the delivered tree after merging. The exact final CI/release URLs and outcomes are recorded on PR #2.

Latest-main preservation: before final merge, main advanced to `bc2289f` with optional guided-panel reading. The merge retains its models/runtimes, R8 rules, source/license records and reader cursor integration. This feature is separate from Feel System and is not a new dependency introduced for feedback. Its settings buttons use the same shared feedback wrappers. Only documentation conflicted; the existing reader changes merged without removing either behavior. Final signed main CI covers the combined source.

## Latest-code recovery · 2026-11-13 (FEEL-CONTINUITY-21)

User's current report: “我们最新代码好像把你的动效系统弄丢了” (this conversation; exact message ID unavailable). The installed version is not identified. Source comparison from merged Feel `8336409` through main `6015acb` finds the core MangaMotion provider, immediate press/cancellation, shared buttons, semantic haptics, refresh, tabs, foreground deadlines and favorite reconciliation retained. The shared persistent root-shell introduced by `246a8ae` avoids scene recreation; `d419d17` restores a coordinated sidebar fade. Ordinary `changeSection` entries still bypass that fade by assigning sectionName directly. This is a concrete routing regression, not deletion of the whole system.

- Repair: reuse the existing DrawerAction actor for every root entry; remove the two notification close-only assignments that would overwrite the destination. Keep 70ms out / 180ms in / 6dp settle, one mounted body, fixed real wallpaper, saved positions, native drawer and its input/motion-off guards. Ordinary Reader/Detail transitions stay on their existing route path.
- New multi-source candidate rows lacked shared press feedback. Reuse motionPress with the shared .985 surface scale, stable outer clickable, button action semantics and no ripple; selection emits the central Selection event only on an actual change, respecting system settings and the existing 70ms rate limit. No new dependency or animation framework.
- Independent read-only review confirmed the bypass and notification overwrite and checked same-page, reversal, motion-off and Reader/Detail paths. Current evidence is source/diff review only; no tests/local build or device operation. Latest project delivery rules supersede the earlier full-Lint-per-push instructions: retain manual-only Lint, submit to latest main and confirm the CI trigger. No Android device/emulator is available here; installation/startup, actual continuity, haptics, TalkBack, font scaling and frame pacing remain pending the built artifact and real user experience.

Further user feedback “侧边栏切换没做好” reopens sidebar acceptance. DRAWER-RESPONSE-22 in DESIGN.md records the confirmed exposure/click mismatch, missing shared row press and deliberately blank foreground, and supersedes the old 70ms-to-zero/whole-reveal gate. The new click-first destination commit makes native drag reversal independent of navigation, rather than claiming a cancelled child close cancels its parent route. The same batch addresses the two actual statistics/source-mark screenshots under STATS-SOURCES-23. All three requests remain pending device appearance/operation; source-only review is not a successful phone experience. Exact message IDs and installed screenshot version are unavailable.

Concurrent main `b680687` was integrated before delivery: preserve automatic source discovery, Baozi/Manhuagui/MangaBZ adapters and the latest explicit low-saturation-blue “拷” character. The latter remains the shared CopySourceWordmark for chapter/source rows and rating markers; the deleted paired-page resource is not restored. Only the other letter-based rating marks become pictograms.

The subsequent intermittent double-tap report is tracked under DRAWER-TAP-24 in DESIGN.md. Reading the actual BOM-pinned Material3 1.4.0 / Foundation 1.12.1 source identifies native animation capture consuming DOWN before the selectable row. A bounded outer drawer tap observer preserves the first valid release during that capture, with native swipes, synthetic cancellation, stable-state Surface semantics and vertical-fling stopping retained. Independent read-only review found no source blocker; the user's real-device report remains open pending the replacement artifact. No unit/local/device test was performed.
