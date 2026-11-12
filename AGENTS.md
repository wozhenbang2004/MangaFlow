# MangaFlow

Android manga reader, Kotlin and Jetpack Compose. Package: `dev.mangaflow`.

## Working rules

- Do not write, run, or configure any tests. Do not run lint, emulator checks, UI automation, screenshot verification, or interface probes. Compilation and APK packaging are allowed for delivery.
- Main automatically builds and publishes a signed release. Keep the existing signing key and monotonically increasing version code.
- Never commit credentials, signing keys, downloaded comics, personal data, or session tokens.
- Keep public reading anonymous. Do not silently attach account credentials to catalog, chapter, image, or review requests.
- Keep Relay-style owner account requests isolated from public reading. Commit a candidate credential only after profile verification, reject stale session responses, and preserve local data on logout.
- Source failures and rate limits must reach the UI. Never replace failures with fabricated manga, ratings, reviews, or download success.
- Treat artwork as the focal point. Use quiet surfaces, deliberate spacing, clear type hierarchy, subtle outlines and restrained motion. Do not default to a stack of large cards.
- Preserve the source URL, attribution and author of external reviews. Do not conflate anime and manga, or attach the first search result without matching titles.
- Downloads must be resumable, cancellable and usable without network access after completion.
