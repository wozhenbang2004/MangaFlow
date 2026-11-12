# MangaFlow

Android manga reader, Kotlin and Jetpack Compose. Package: `dev.mangaflow`.

## Working rules

- CI remains build/package/release only; do not add test or lint jobs. The user subsequently authorized real API verification ("你接 api 你接之前好好测试下啊"). Verify the affected real source path when changing an integration; compilation or mock responses do not prove that reading or login works. Do not add automated test suites.
- Main automatically builds and publishes a signed release. Keep the existing signing key and monotonically increasing version code.
- Never commit credentials, signing keys, downloaded comics, personal data, or session tokens.
- Keep public reading anonymous. Do not silently attach account credentials to catalog, chapter, image, or review requests.
- Keep Relay-style owner account requests isolated from public reading. Commit a candidate credential only after profile verification, reject stale session responses, and preserve local data on logout.
- Source failures and rate limits must reach the UI. Never replace failures with fabricated manga, ratings, reviews, or download success.
- Treat artwork as the focal point. Use quiet surfaces, deliberate spacing, clear type hierarchy, subtle outlines and restrained motion. Do not default to a stack of large cards.
- Preserve the source URL, attribution and author of external reviews. Do not conflate anime and manga, or attach the first search result without matching titles.
- Downloads must be resumable, cancellable and usable without network access after completion.

## Current feedback and scope · 2026-11-12

- The user confirmed "其实我现在用的 0.13 这版已经能稳定看漫画了". Keep v0.1.3's working anonymous source protocol as the baseline; do not resume the abandoned header experiment.
- Current fixes: "账号网页登录展示被来源限制 210"; "上下滑动特别特别不流畅会卡一下"; "从历史记录点看某个漫画还得再先进那个漫画详情页 loading 再进实际的漫画"; "翻页看漫画过程中也有不少 loading". Provide official website login with verified credential handoff and meaningful source errors, direct history resume, background directory loading, less work on the scrolling thread, and bounded page/chapter prefetch.
- Preserve each chapter's exact page, scroll offset, and long-image position. Continue reading must not require loading the detail screen first. Background directory/prefetch failures must not interrupt the current chapter.
- Earlier requested double-page/spread support, better page-turn motion, Copy chapter comments and additional review sources remain follow-up work after this reading/login batch; do not describe them as implemented.
- Android device smoothness and successful login to the user's own account require real device feedback. An API response, source review, or successful APK build does not establish either result.
- Source evidence from this batch: the current official login bundle still calls `/api/kb/web/login` and displays the returned `message`; the existing app discarded that detail for 210. A real production-Kotlin direct chapter request at 05:35 UTC made one anonymous GET and passed the HTML comic-ownership check, but this cloud connection received an empty `contentKey`. No image-read success or successful account login was established by that call; do not turn it into a claim that the user's working v0.1.3 reader is broken.
