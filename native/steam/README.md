# Steam Workshop native component

MangaFlow media adapter version 0.1.0; component identity 0.1.0-f541366.

The module links the real tapline core pinned to
https://github.com/UnhingedSoftware/tapline/tree/f54136611519bef50cf31336e0c28a78eb175657.
It does not launch Steam, SteamCMD, shell commands or a scene renderer.
The fixed-source patch changes the actual Steam friendly-device label to
MangaFlow so the user recognizes the login approval. No OS/platform or
authentication-protocol fields are altered.

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
nativePoll(Long):String?, nativeCancel(Long) and nativeFree(Long).
nativePoll is nonblocking: null means pending. A complete or error event is
terminal. Completed queues also return complete once drained. An invalid handle
returns error code invalid_job; nativeStart returns0 when unavailable/busy.

All requests are JSON, op is login/account/search/download. Credentials, where
needed, are flat account, steamId (decimal string) and refreshToken.
The adapter never calls TokenStore, automatic login or local Steam discovery.

- login emits qr/url updates, then loggedIn/account/steamId/refreshToken only
  after a refreshed Steam client session succeeds, followed by complete.
- account validates the passed session and emits account/account/steamId,
  followed by complete. Network/session_unavailable does not mean the token
  was revoked; the Kotlin caller must retain the saved credential.
- search accepts query (<=120characters), page (1-based, default1), media
  image/video. Returns items with id/title/previewUrl/media/sizeBytes, page,
  hasMore and unsupportedCount, then complete. Paging belongs to the actual
  upstream rows even where unsupported items are filtered.
- download accepts itemId (decimal string), an existing empty app-owned absolute
  directory, media and maxBytes<=536870912. It requires explicit credentials,
  verifies the returned item app431960/id, uses a fresh flat download and emits
  actual progress/completed/total. complete includes directory/bytes/files;
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
verification, account/search90seconds, and download30minutes. No password form,
publisher key, account probe or token file is introduced. Native credential
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

The pinned Fetch API returns Response.body as a complete Vec<u8>. Its HTTP
adapter supports bounded body reads and Range requests, but exposes no streaming
body reader, sink or progress callback for Legacy blobs. Consequently Legacy
payloads are buffered up to the declared accepted size (maximum512MiB), plus
processing/HTTP-buffer overhead; this is not constant-memory streaming. The
512MiB disk/payload cap is not a claim of a512MiB peak-RAM limit. SteamPipe uses
bounded chunk requests. No separate hand-written network stack is introduced.

Error codes are fixed, sanitized identifiers: invalid_request, auth_required,
session_unavailable, login_failed, steam_unavailable, not_owned,
unsupported_media, size_limit, unsafe_path, download_failed, cancelled, timeout,
native_failed and invalid_job. No remote error string, URL, token or filesystem
path is logged or propagated as an error.
