# DMBus public HHJX parser (2026-11-17)

Read-only selected-episode investigation: DMBus work6290 episode2 page
`https://dmbus.cc/p/6290-1-2.html` declares an HHJX iframe. That page returned
HTTP200 and a literal `window.__HHJX_BOOTSTRAP__` object. Its own visible script
posts the original `url`, `t`, `key` plus `client_fallback=false` to the same
origin's `/api/parse`. The key is issued by that public page for this request,
not an extracted or bundled private key; neither it nor `ts_key` is committed,
persisted in app storage or added to logs.

Sending exactly those page-issued parameters to `https://hhjx.hhplayer.com/api/parse`
returned HTTP200/code200 and a literal HTTPS `.mp4` URL on
`groupvideo.photo.qq.com`. The media was not requested. This proves an address
response for the sampled episode, not device playback, bandwidth or continued
upstream availability. No CAPTCHA, login, TLS or other access check was bypassed.

The adapter is deliberately narrow: only source `anime-native-dmbus`, exact
HHJX HTTPS origin/port, bootstrap URL equal to the current page query, bounded
original timestamp and page key format. It does not synthesize or renew a token.
The existing transport refuses POST redirects; requests and results retain the
same per-source routing snapshot. Unsupported `act` and browser-only `youku`
branches are rejected. Only literal public HTTPS media with a recognized media
extension is accepted. No external script executes. Server errors, expired tokens
or changed protocol fail closed and permit normal exact-episode fallback.

AkiAnime was also inspected: its current YDY parser declaration points at an
external origin, not the previously constrained same-origin endpoint. No new Aki
external-parser permission or POST protocol was guessed in this patch.

Verification is source/protocol review and `git diff --check` only; no local
build/test or on-device acceptance was performed.
