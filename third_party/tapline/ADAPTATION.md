# tapline adaptation for MangaFlow

- Upstream: https://github.com/UnhingedSoftware/tapline
- Exact source pin: f54136611519bef50cf31336e0c28a78eb175657
- License: Mozilla Public License2.0; complete upstream text is
  LICENSE-MPL-2.0.txt in this directory.
- Used API: Session anonymous_shared/with_token_shared/qr_login,
  browse_workshop/workshop_details and observed Workshop downloads.
- The core checkout is a build input in native/steam/tapline. Its source is
  fixed by scripts/build_steam.py; it is not copied into the app source tree
  or silently taken from a floating branch.

native/steam/src/lib.rs is the JNI/platform boundary. It handles opaque job IDs,
bounded queues and cancellation, passes only explicit in-memory credentials,
and leaves encrypted storage to the existing Android/Windows implementations.
It never calls TokenStore::default_file, Session::automatic, local Steam account
discovery, SteamCMD or a rendering/scene runtime. The original FFI implementation
was read as an API reference; this wrapper uses the core directly.

native/steam/tapline-bounds.patch makes two narrow fixed-source changes. It
changes tapline-auth's actual DEVICE_NAME constant from tapline to MangaFlow
for recognizable Steam approval; it does not alter OS/platform fields, login
protocol or ownership checks. It also modifies the pinned session.rs download
entry. download_workshop_item_observed delegates to a new bounded variant with
unlimited defaults, preserving original callers. MangaFlow calls the bounded
variant with512MiB/512entries; the real manifest is checked before entry writes,
including checked file-byte sum, declared total, all entries, link/executable
rejection and chunk offsets. No Steam ownership refusal is bypassed.

The media scope is original video and genuine standalone image payloads exposed
by the source. Scene previews are not originals. SteamPipe projects require
project.json validation in the Kotlin importer; no interactive renderer,
unverified Scene extraction or invented Image tag is included.

The patch and wrapper are available source distributed alongside the build
instructions. GPL/.NET/SteamKit2 helpers from other researched clients are not
incorporated. Native dependency notices are collected from actual Cargo
metadata/license files during packaging; source reading is not a platform
operation, account-login, Workshop-download or device-decoder success claim.

Actual transport limitation: tapline-io::Fetch::get returns a complete
Response.body Vec<u8>; the pinned Tokio HTTP client has bounded/ranged reads but
no exposed streaming-to-sink Legacy API. Legacy media remains whole-body
buffered up to the accepted declared512MiB plus HTTP/processing overhead. This
payload cap is not a verified peak-RAM bound. The SteamPipe path uses chunks;
no replacement or hand-written HTTP stack is added in this source batch.
