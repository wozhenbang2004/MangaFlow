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

- The user confirmed "其实我现在用的 0.13 这版已经能稳定看漫画了". Keep v0.1.3's working anonymous source protocol as the baseline; do not resume the abandoned header experiment.
- Current fixes: "账号网页登录展示被来源限制 210"; "上下滑动特别特别不流畅会卡一下"; "从历史记录点看某个漫画还得再先进那个漫画详情页 loading 再进实际的漫画"; "翻页看漫画过程中也有不少 loading". The later login feedback below supersedes the website-login UI. Preserve direct history resume, background directory loading, less work on the scrolling thread, and bounded page/chapter prefetch.
- Preserve each chapter's exact page, scroll offset, and long-image position. Continue reading must not require loading the detail screen first. Background directory/prefetch failures must not interrupt the current chapter.
- Double-page/spread support and better page-turn motion remain follow-up work; do not describe them as implemented.
- Android device smoothness and successful login to the user's own account require real device feedback. An API response, source review, or successful APK build does not establish either result.
- Source evidence from this batch: the current official login bundle still calls `/api/kb/web/login` and displays the returned `message`; the existing app discarded that detail for 210. A real production-Kotlin direct chapter request at 05:35 UTC made one anonymous GET and passed the HTML comic-ownership check, but this cloud connection received an empty `contentKey`. No image-read success or successful account login was established by that call; do not turn it into a claim that the user's working v0.1.3 reader is broken.

## Login repair · 2026-11-12

- User request: "我们的漫画软件登录界面不允许截图我怎么拍给你报错啊服了而且还是登录不了实在不行你看看人家 relay 桌面端的漫画部分怎么实现的呢我吐了也别弄个官网登录的按钮纯多余".
- The login screen must allow screenshots. Remove the website-login entry and activity; default to the existing username/password form and disclose manual Cookie / Token import as a secondary action. Keep secrets masked by default, clear them on submit, and make sanitized errors selectable/copyable.
- Relay-Desktop `0079af7` already uses the same `/api/kb/web/login` form. Its code is a reference, not evidence of a successful account login. Preserve the matching form protocol; verify its candidate token through the same website's `/api/v2/web/user/info`, as used by the current official bundle and EasyCopy `72bd3c9`, instead of requiring the old App profile endpoint to succeed.
- Keep authentication submission and profile-verification failures distinct. HTTP 210 can represent a form error; preserve the source's sanitized message/detail and any Retry-After. Never automatically replay credentials through alternate login endpoints or promote an unverified token.
- Evidence at 05:58 UTC: one request with empty login fields returned HTTP 210 and `用戶名不能為空`; an unauthenticated read of `/api/v2/web/user/info` returned HTTP 401. This establishes reachable endpoints and error semantics, not a successful account login. Actual account success and device screenshot behavior remain pending user feedback.

## Discovery and discussion batch · 2026-11-12

- Current request: "设计一个热度榜啊或者推荐那样的功能"; "拷贝自己的评论也接入以及其他站的也接入"; "一些系列图标代表站点"; "每一个章节末尾也应该有评论"; "更多更丰富的评论". Implement source recommendations, period/audience rankings, native site metrics, paged work comments/replies and exact-chapter discussions.
- Design baseline: retain the current artwork-led detail page; replace large source rows with compact site marks and native metrics, two inline comment previews, and a source-switching sheet. Reader discussions follow the last image and preserve reading progress. Visual layout and device interaction remain pending user feedback.
- Copy recommendation/rank/comment/reply/chapter-comment endpoints and Bangumi manga matching/short-comment pagination returned real anonymous responses. AniList's expanded review query follows its documented schema but returned HTTP 403 from this environment, so its new body/pagination behavior remains unverified on device.
- Bangumi novel entries must be excluded before comparing manga titles. Keep uncertain matches selectable and remember explicit user associations. Only Copy chapter UUIDs are currently mapped for chapter discussions; never substitute work-level Bangumi/AniList reviews.
