# AGE player protocol evidence

Read-only public response inspection on 2026-11-17. No remote script or WASM was
executed, no private key extracted, no media downloaded and no device acceptance
or regional network benchmark performed.

## Exact work and episode

- `https://api.agedm.io/v2/search?query=脑洞学生会&page=1` returned work `20260293`.
- `https://api.agedm.io/v2/detail/20260293` returned `video.id=20260293`,
  `video.name=脑洞学生会`, `video.year=2026` and five playlists under this one video.
- Episode two in `xigua` contains an opaque `age_...` value, not a public media URL.
- `https://www.agedm.io/play/20260293/1/2` returned HTTP 200 and the actual
  `iframeForVideo` at `https://jx.wuzhoupai.com:8443/vip/?url=...`.
- That declared iframe returned HTTP 200, with hexadecimal `Vurl`, `Time`, `Ref`,
  `Version`, and `PlayerPath.wasm` pointing to `jx.wuzhoupai.com.wasm` on the
  page-declared CDN. The page loads a minified player script.
- Static inspection of that script shows Go `importObject`, WebAssembly loading,
  `hxm_encrypt` over URL/client/time fields, signed `Video-Parse-*` request headers,
  and `hxm_decrypt` of a returned URL. These are not plain video/source tags.
  No decryption or signing secrets were extracted or copied.

Thus this sample's failure is not explained by an absent HTTP response. The
existing data-only resolver lacks this dynamic signed player protocol. This does
not establish that the upstream service is unavailable or that every AGE line
needs the same protocol.

## Working data-only alternative in the same response

For the same work's second episode in `ffm3u8`, the response-declared
`player_jx.zj` plus that playlist's actual value returned HTTP 200. Its page
contains a literal `Vurl` pointing to an HTTPS `.m3u8` URL. This matches the
existing `AnimeStreamResolver.MEDIA_ASSIGNMENT` contract without running scripts.
It establishes that a direct-media candidate is extractable, not that playback,
media authorization, bandwidth or device decoding has been accepted.

Automatic recovery can try this precisely matched episode in another verified
line rather than retrying the unsupported `xigua` parser indefinitely. Do not
infer equivalent episodes from array index alone or map other works by title.

## Dynamic support boundary

The app's QuickJS host has no WebAssembly/Go browser runtime. The existing isolated
Android login WebView is not a routed media sniffer. Profile isolation can separate
cookies but does not enforce the per-source network route. Android WebView request
interception is not a complete security boundary; process-wide ProxyController
would affect unrelated WebViews. Therefore simply executing this player in that
container would not satisfy the existing networking contract.

A future implementation needs a bounded, isolated browser/WASM execution facility
with all network paths routed through the exact anime source snapshot, cancellation
and session cleanup, and no credential borrowing or TLS exceptions. Until then,
keep dynamic parsing explicitly unsupported and prefer verified data-only lines.

## Default playback integration

The earlier all-source change already offered equivalent AGE routes for new
automatic bindings, but legacy saved groups remained locked to one line. Default
playback now derives a transient fallback list from the existing confirmed
AGE episode without rewriting that saved group. It requires the verified returned
AGE work ID and the existing strict complete group/episode-label contract. Explicit
option playback remains locked; manual mappings that change the canonical episode
number or kind do not expand. Every resolve, delayed start and player-error recovery
rechecks this same live candidate set. This permits a default xigua attempt to
fall through to ffm3u8 for the proven same episode, while preserving user mappings.

Source integration is statically reviewed; no local build or runtime playback test
was performed. The public page observation above is not a player first-frame result.
