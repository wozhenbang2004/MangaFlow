# MangaFlow

Android manga reader, Kotlin and Jetpack Compose. Package: `dev.mangaflow`.

## Working rules

- CI remains build/package/release only; do not add test or lint jobs. The user subsequently authorized real API verification ("你接 api 你接之前好好测试下啊"). Verify the affected real source path when changing an integration; compilation or mock responses do not prove that reading or login works. Do not add automated test suites. This batch does not run emulator checks, UI automation, or screenshot verification.
- Main automatically builds and publishes a signed release. Keep the existing signing key and monotonically increasing version code.
- Never commit credentials, signing keys, downloaded comics, personal data, or session tokens.
- Keep public reading anonymous. Do not silently attach account credentials to catalog, chapter, image, or review requests.
- Keep Relay-style owner account requests isolated from public reading. Commit a candidate credential only after profile verification, reject stale session responses, and preserve local data on logout.
- Source failures and rate limits must reach the UI. Never replace failures with fabricated manga, ratings, reviews, or download success.
- Treat artwork as the focal point. Use quiet surfaces, deliberate spacing, clear type hierarchy, subtle outlines and restrained motion. Do not default to a stack of large cards.
- Preserve the source URL, attribution and author of external reviews. Do not conflate anime and manga, or attach the first search result without matching titles.
- Keep source metrics distinct: Copy popularity is heat, Bangumi is out of 10, and AniList is out of 100. Comment reply counts are not likes. Chapter discussions must use the exact chapter UUID and never include whole-work reviews as a substitute.
- Compile feature branches before integrating into main. Reader discussion pages must not increase image page counts or corrupt saved image offsets.
- Downloads must be resumable, cancellable and usable without network access after completion.

## Current feedback and scope · 2026-11-12

- The user initially reported "其实我现在用的 0.13 这版已经能稳定看漫画了", then corrected this after another empty-directory screenshot: "不是啊 v13 有时候也这样报错很奇怪". Both old and new versions can fail intermittently; v0.1.3 is not a proven reliable source baseline. Investigate the actual anonymous catalog → chapter → image path; do not repeat blind header variations or infer a version regression from the screenshot.
- Current fixes: "账号网页登录展示被来源限制 210"; "上下滑动特别特别不流畅会卡一下"; "从历史记录点看某个漫画还得再先进那个漫画详情页 loading 再进实际的漫画"; "翻页看漫画过程中也有不少 loading". Provide official website login with verified credential handoff and meaningful source errors, direct history resume, background directory loading, less work on the scrolling thread, and bounded page/chapter prefetch.
- Preserve each chapter's exact page, scroll offset, and long-image position. Continue reading must not require loading the detail screen first. Background directory/prefetch failures must not interrupt the current chapter.
- Double-page/spread support and better page-turn motion remain follow-up work; do not describe them as implemented.
- Android device smoothness and successful login to the user's own account require real device feedback. An API response, source review, or successful APK build does not establish either result.
- Source evidence from this batch: the current official login bundle still calls `/api/kb/web/login` and displays the returned `message`; the existing app discarded that detail for 210. A real production-Kotlin direct chapter request at 05:35 UTC made one anonymous GET and passed the HTML comic-ownership check, but this cloud connection received an empty `contentKey`. No image-read success or successful account login was established by that call; do not turn it into a claim that the user's working v0.1.3 reader is broken.
- Active repair after "不是哥们怎么又这样了": the screenshot again shows one website group with zero chapters and disabled reading. Catalog and body now use the anonymous App protocol together, with a persistent random installation identity and ordered chapter2 images; no account credentials, mirror hopping or identity rotation. Keep a previously usable directory or saved chapter usable when refreshing metadata fails.
- Real source evidence on 2026-11-12: production Kotlin fetched all 236 chapters for `modujingbingdenuli` (215/1/20 across three groups), 61 pages for chapter 01 and 13 for the latest default-group chapter. Sample original images returned HTTP 200 and decoded. `woduzishenji` returned 201 chapters and 66 pages for the prologue after fixing the numeric `comic.ban` misclassification. Its actual App result explicitly reports `is_banned=false`, `is_lock=false` while `comic.ban=2`; do not convert that numeric metadata into a Boolean denial. Catalog/body require explicit Boolean access results and still reject locked/banned content. These service observations do not establish Android device smoothness or personal login success.

## Discovery and discussion batch · 2026-11-12

- Current request: "设计一个热度榜啊或者推荐那样的功能"; "拷贝自己的评论也接入以及其他站的也接入"; "一些系列图标代表站点"; "每一个章节末尾也应该有评论"; "更多更丰富的评论". Implement source recommendations, period/audience rankings, native site metrics, paged work comments/replies and exact-chapter discussions.
- Design baseline: retain the current artwork-led detail page; replace large source rows with compact site marks and native metrics, two inline comment previews, and a source-switching sheet. Reader discussions follow the last image and preserve reading progress. Visual layout and device interaction remain pending user feedback.
- Copy recommendation/rank/comment/reply/chapter-comment endpoints and Bangumi manga matching/short-comment pagination returned real anonymous responses. AniList's expanded review query follows its documented schema but returned HTTP 403 from this environment, so its new body/pagination behavior remains unverified on device.
- Bangumi novel entries must be excluded before comparing manga titles. Keep uncertain matches selectable and remember explicit user associations. Only Copy chapter UUIDs are currently mapped for chapter discussions; never substitute work-level Bangumi/AniList reviews.
