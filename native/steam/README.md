# Steam Workshop native component

MangaFlow media adapter version 0.1.3; component identity 0.1.3-f541366.

The module links the real tapline core pinned to
https://github.com/UnhingedSoftware/tapline/tree/f54136611519bef50cf31336e0c28a78eb175657.
It does not launch Steam, SteamCMD, shell commands or a scene renderer.
The fixed-source patch changes the actual Steam friendly-device label to
MangaFlow so the user recognizes the login approval. No OS/platform or
authentication-protocol fields are altered. The same pinned patch also threads
immutable job network routes through the existing maintained transport.

## Build inputs

Use Rust 1.88 or newer. The pinned core uses stabilized Rust 1.88 let-chains.
Android uses NDK 27.2.12479018, API26 clang for each actual target and a 16KiB ELF
max-page-size linker flag. JNI bindings use jni-sys0.3.0; no JVM or JNI headers
are required. Windows target is x86_64-pc-windows-msvc.

1. Check out tapline at the exact pin into native/steam/tapline.
2. Verify HEAD and a pristine source tree, then from that checkout apply
   ../tapline-bounds.patch with git apply.
3. Seed native/steam/Cargo.lock from the pinned upstream Cargo.lock if absent.
   The first cargo build resolves the new wrapper/jni-sys root while preserving
   the upstream dependency versions; retain that generated lock for subsequent
   ABI builds with --locked. Do not run generate-lockfile, tests or all-targets.
4. Build the native/steam/Cargo.toml manifest in release mode for each real
   target. Crate/package is mangaflow-steam, library is mangaflow_steam.
   Android output is libmangaflow_steam.so; Windows is mangaflow_steam.dll.

The release profile uses size optimization, thin LTO, one codegen unit, stripped
symbols and panic=unwind. Tokio task panic and every JNI boundary are contained;
panic=abort would turn an unexpected dependency panic into an app crash.
Binary size and platform operation remain unmeasured until actual CI artifacts.

The native source checkout/target/lock are build inputs and are ignored by the
repository. The upstream MPL2 license is third_party/tapline/LICENSE-MPL-2.0.txt.
The packager collects actual dependency metadata/license files from this build.
No assumed size, checksum or dependency license is substituted for build output.

## JNI contract

Class dev.mangaflow.storage.SteamNative has static nativeStart(String):Long,
nativePoll(Long):String?, nativeCancel(Long), nativeFree(Long) and
nativeSubmitGuard(Long,String,Int):Boolean.
nativePoll is nonblocking: null means pending. A complete or error event is
terminal. Completed queues also return complete once drained. An invalid handle
returns error code invalid_job; nativeStart returns0 when unavailable/busy.

All requests are JSON, op is login/passwordLogin/account/profile/library/search/download.
Every operation also requires an immutable network object. kind is SYSTEM or
DIRECT, or HTTP/SOCKS5 with an explicit host and port. PROXY mode is resolved by
the shared Kotlin routing store before nativeStart; a missing/invalid endpoint is
rejected. Login, guard polling, account restore, search and original-media download
use the STEAM_WALLPAPERS category. Optional component acquisition uses its own
STEAM_COMPONENT route outside this native bridge.

The fixed-source patch passes one job route through CM directory lookup, CM
WebSocket/TLS and all Shared HTTP/CDN calls. HTTP CONNECT uses the maintained
async-http-proxy1.2.5 (BSD-3-Clause); SOCKS5 uses tokio-socks0.5.3 (MIT).
Target domains are sent to the proxy, including CM-directory and CDN requests;
only the proxy endpoint itself needs ordinary OS resolution. Direct/SYSTEM
connections retain OS DNS/routing and cannot override another app's VPN/TUN.
SYSTEM preserves the native client's existing default network behavior; it
does not claim to discover Windows HTTP/PAC settings. No environment variables,
global proxy settings or OS routes are changed. The proxy mode never falls back
to a direct socket. Existing rustls certificate/hostname checks remain active.

Each native job owns a separate immutable-route HTTP pool while its derived Shared
retains the original application-wide four-chunk semaphore. TCP connection and proxy negotiation
are bounded by30seconds; TLS/WS and request work retain the existing job deadline. The app's package
identity and per-job cancel/free lifetime stay unchanged; changing a setting
applies to subsequently started jobs. This slice supports unauthenticated local
HTTP/SOCKS ports only, with no credential/profile/authentication extension. Credentials, where
needed, are flat account, steamId (decimal string) and refreshToken.
The adapter never calls TokenStore, automatic login or local Steam discovery.

- login emits qr/url updates, then loggedIn/account/steamId/refreshToken only
  after a refreshed Steam client session succeeds, followed by complete.
- passwordLogin accepts an explicit account/password for this attempt only. It uses the
  pinned core's RSA key / BeginAuthSessionViaCredentials flow and retains its pending
  login through Steam Guard. It emits guard/methods (actual Steam confirmation types:
  2 email code, 3 authenticator code, 4 mobile approval, 5 email-link approval), then
  guardAccepted or guardRejected as applicable. nativeSubmitGuard enqueues one explicit
  code/type on this same live login job; it never starts another login or resends the
  password. Invalid/expired codes remain recoverable on that pending session.
  The same PendingLogin is retained across at most3 bounded CM reconnects with a
  10second heartbeat; passwords and submitted codes are never replayed. A Guard
  submission with an ambiguous network outcome is queried on the same challenge
  before the UI permits an explicit retry. guardReconnecting, guardResumed and
  authVerifying describe that existing attempt, not a newly authenticated account.
  Mobile/email approvals continue polling without a code; successful login uses the
  same verified-client-session and platform-vault handoff as QR. Passwords and codes
  are never persisted, logged, included in state or sent to MangaFlow servers.
- account validates the passed session and emits account/account/steamId,
  followed by complete. Network/session_unavailable does not mean the token
  was revoked; the Kotlin caller must retain the saved credential.
- profile is an optional, separate metadata request for the already verified SteamID64.
  It uses the same fixed route and the public numeric Steam Community profile XML,
  with a15second/256KiB/4096node bound and DTD disabled. roxmltree0.20.0
  (MIT OR Apache-2.0) parses actual steamID/avatarFull only after steamID64 matches.
  HTTPS avatars are restricted to the existing Steam/CDN allowlist. This XML API is
  deprecated and privacy-limited; failure must not revoke the independent session,
  block browsing or invent a nickname/avatar. The profile request carries no token
  to the public endpoint. Valve documents this legacy interface at
  https://partner.steamgames.com/documentation/community_data .
- library accepts collection favorites/subscriptions, media image/video and page.
  It uses the real pinned PublishedFile.GetUserFiles RPC with myfavorites or
  mysubscriptions (string enum), current verified SteamID64 and app431960.
  items/page/hasMore retain the server's real collection paging even when unsupported
  media is filtered out; this is not a search-query approximation of a collection.
- search accepts query (<=120characters), page (1-based, default1), media
  image/video. Returns items with id/title/previewUrl/media/sizeBytes, page,
  hasMore and unsupportedCount, then complete. Paging belongs to the actual
  upstream rows even where unsupported items are filtered.
- download accepts itemId (decimal string), an existing empty app-owned absolute
  directory, media and maxBytes<=536870912. It requires explicit credentials,
  verifies the returned item app431960/id, uses a fresh flat download and emits
  phase waiting/connecting/resolving/downloading/verifying events followed by
  actual progress/completed/total/known. known=false remains indeterminate before
  real byte progress; callers must not treat preview size as downloaded bytes.
  complete includes directory/bytes/files;
  actual Legacy primary metadata additionally supplies primaryFile and media.

Video browsing uses the real Video tag. Image browsing does not invent an Image
tag or relabel a Scene. It admits only actual Legacy primary filename/URL media
metadata. A SteamPipe scene/unknown manifest cannot be called a static image.
The caller requires project.json's real main media for SteamPipe projects;
Legacy primaryFile points at the actual downloaded blob, including contents.bin
only where the real primary URL establishes its media type. Every import must
validate the owned path, real signature and platform decoder. Preview images
are display-only and are never used as download payloads or main-media fallback.

## Bounds and lifetime

At most8 live jobs,4 shared chunk permits and2 runtime workers. Progress events
coalesce in a32-entry queue. Login is capped360seconds including connection and
verification, account/search/library90seconds, profile15seconds, and download30minutes. Password login and guard submission are explicit user actions; no publisher key,
account probe or token file is introduced. Native credential
buffers and queued response strings are zeroized; the JVM still owns its strings
and platform secret storage.

The pinned core patch adds download_workshop_item_bounded while preserving the
original unbounded API. Before item files/directories are created it enforces
actual manifest byte/entry limits, rejects executable/link entries and invalid
chunk ranges. Native downloads cap512MiB and512 manifest entries, reject unknown
declared size, and do not enable DLC or extensions. No downloaded program runs.

Cancel aborts the async worker. Free removes the handle and joins its supervisor
on the Kotlin IO dispatcher; callers must not poll/free concurrently. Upstream
spawn_blocking chunk decode/finalization may briefly finish after async abort,
so each transfer owns a unique disposable directory that is never reused.
The Kotlin importer controls cancellation, deletion/retry and final persistence;
an aborted transfer must never be marked imported.

Legacy media now uses sequential1MiB identity-encoded Range requests through
that same maintained Fetch and the owned FileSink. Each bounded response is
validated for exact206 Content-Range/total/length and strong ETag consistency
with If-Range, then written before emitting real byte progress. A full-file Vec
fallback is deliberately absent. A missing/changed range validator or unsupported
range is a surfaced range_unsupported/content_changed failure, not a silent return
to the old memory path. Each request has a60second deadline;90seconds without
material byte progress aborts the transfer. SteamPipe retains bounded chunks and
the same no-progress watchdog. The512MiB content/disk cap is still not a claim of
an exact peak-RAM bound for the full import/decoder pipeline.

Authenticated CM operations share a bounded20second gate for their entire
session-dependent lifetime, avoiding concurrent logons with the same credential.
Public search always uses an anonymous session and may run while a download is
active. Profile metadata has a separate lane and no authenticated CM login.
The JVM client separately owns authentication, browse, transfer and profile jobs,
with generation/credential-epoch checks; canceling or failing one does not replace
another lane's handle. Component preparation is shared but canceled only by its
actual owner. A locally saved session is a candidate until the account job verifies
it; network/routing failure preserves that candidate rather than deleting it.

Error codes are fixed, sanitized identifiers: invalid_request, auth_required,
session_unavailable, login_failed, steam_unavailable, not_owned,
unsupported_media, size_limit, unsafe_path, download_failed, cancelled, timeout,
native_failed, invalid_job, invalid_credentials, invalid_guard_code,
expired_guard_code, login_rate_limited, login_refused, unsupported_guard,
network_configuration, busy, connection_timeout, metadata_timeout,
auth_connection_lost, auth_session_expired, guard_submission_unknown,
profile_timeout, profile_unavailable, range_unsupported, content_changed and
download_stalled. No remote error string, URL, token or filesystem
path is logged or propagated as an error.
