# Relay Link v1

MangaFlow controls only its own network exceptions. Relay owns VPN execution, subscriptions, nodes, user rules and core reloads. Android is the supported integration platform; Windows and iOS retain their existing networking.

## Identity and consent

Discover only `dev.relay.android.network` and `dev.relay.android`, in that order. Both editions retain Java component names `dev.relay.android.RelayLinkService` and `dev.relay.android.RelayLinkConsentActivity`. Installation alone is not protocol support, authorization or an active VPN.

Bind explicitly. Messenger messages use `what=1`, with UTF-8 JSON in Bundle key `payload`. Every request includes `protocol:1`, a bounded unique `requestId` and `op`. Every response repeats protocol/requestId and has `ok`. The service derives the caller from `Message.sendingUid`, resolves its unique installed package and current signing certificate, and rejects ambiguous/shared identities. Only `dev.mangaflow` is eligible. Package names supplied in JSON confer no authority.

`hello` is the only unauthenticated operation and discloses compatibility/consent requirements only. `status`, `replace` and `revoke` require the stored package/certificate grant on every call. First consent uses an explicit result-based Activity launch so Relay can verify `getCallingPackage()`. Permission is stored by Relay and can be revoked in either app. MangaFlow pins the provider's certificate after explicit successful consent; a replaced signer cannot silently inherit it.

## Rules and receipts

`replace` contains `grantId`, `expectedRevision` and `rules:[{id,mode,domains}]`. Modes are `DIRECT` and `PROXY`; hostnames are canonical suffixes, never arbitrary sing-box JSON. Empty domains represent precise-entry-only policies. Replacement is bounded, validated, namespace-local, compare-and-swap and idempotent. Different exits for overlapping host suffixes must use precise routing. A conflict never permits blind overwrite. The initial MangaFlow client sends precise-entry-only source/category/default intents: a VPN domain rule cannot preserve a later explicit task choice of SYSTEM, or distinguish chapter downloads from browsing the same source. Relay retains bounded domain compilation, but MangaFlow does not infer safe app-wide exceptions from a source's homepage.

The status receipt separates `savedRevision`, `appliedRevision`, `vpnConnected`, `coreSession`, `state` and `conflicts`. `coreSession` combines a per-process random UUID and a changing core epoch, so process restart cannot reuse an observation session. Runtime states are `DISCONNECTED`, `PENDING`, `APPLIED`, `CONFLICT` and `ERROR`. Applied revision belongs to the exact current core snapshot; reload failure/rollback cannot acknowledge the candidate. VPN stop clears runtime application evidence. Existing app bypass/block/forced policies and global mode are surfaced; exceptions to global mode require explicit consent. External writes never change a subscription, node or another app's policy.

`endpoints.direct` and `endpoints.proxy`, when usable, contain loopback `host`, `port`, `username`, `password`. Relay compiles authenticated inbounds with matching route and DNS decisions. Credentials travel only through authorized IPC, remain out of logs/diagnostics/transfer journals, and are invalidated when the grant changes. Revocation must retire the old listeners/connections even when a later core reload fails.

## Frozen requests and precise routing

MangaFlow resolves explicit task > source > category > global preference once. A managed snapshot stores only provider package, provider certificate and grant identity, alongside the requested mode. Actual proxy credentials are resolved from the current acknowledged session immediately before transport use. Existing explicit manual proxy endpoints remain independent. Old nonmanaged tasks retain their old snapshot.

Two authenticated local entries implement the already resolved direct/proxy request intent; shared CDN names and dynamic image hosts therefore do not require guessing a source from traffic. `SYSTEM` keeps Relay's normal routing. A missing/stale grant, core conflict, service death, unavailable endpoint or unsupported platform fails the managed request explicitly; it cannot silently fall back to a different route. Explicit retry using current settings is the existing way to change a frozen task. Pausing persists a namespace-clear intent and reports failure until the remote clear is acknowledged; it does not pretend that local disable alone removed VPN rules.

“Saved”, “core applied”, and “actual response through the managed entry” are distinct facts. Only real product traffic can create the last observation, after a fresh status receipt confirms the socket's original coreSession. It is not a public-IP, geographical-exit, all-CDN or installed-device acceptance claim. Protocol replies, diagnostic probes and native task completion alone never verify a request.

## Compatibility and release evidence

Initial MangaFlow baseline: `c8f3016552e723981a51e018107883510eef14f6`. Available Relay baseline: recovered public-ready 0.3.1/versionCode25, not a claimed GitHub main. Fixed core: `1.14.4-relay-fallback.4`, AAR SHA-256 `a30d78defda5baabce1acbb9554000779e4fbcba5481454ef8754329008e5417`.

Build, authorization, active reload, restart/revocation, shared-CDN routing, global/app-policy conflicts and actual device behavior require separate evidence. No test suite or synthetic probe is added. Existing MangaFlow CI owns its packages; Relay builds retain the original network-edition signer and private configuration stays out of source/APKs.

## One active application proxy provider · 2026-11-17

Relay and custom proxy are mutually exclusive application selections. The routing store preserves default/category/source mode choices and all saved endpoint addresses when switching provider. Relay selection ignores dormant custom endpoints. Custom selection uses the source's saved endpoint when present, otherwise the global custom endpoint; missing proxy configuration fails explicitly. Custom DIRECT uses the ordinary direct app transport, not Relay, without changing the device VPN or system network settings.

Existing installations with any saved custom endpoint initially select custom; otherwise they select Relay. This deliberately resolves the former mixed behaviour (custom PROXY plus Relay DIRECT) rather than claiming unchanged transport. Source/category overrides and automatic-check locks are not deleted. Relay's app-specific inventory is cleared while custom is active; user-wide VPN state is untouched.

A frozen task is never rewritten to another provider. Shared HTTP request entry, JVM OkHttp request entry/URL transport and Relay endpoint resolution reject a frozen route incompatible with the active provider, asking for an explicit restart under current settings. Already in-flight responses may finish; this is not an instant system-wide network cutover. Manual network checks now apply verified recommendations when autoApply is enabled, just like scheduled checks. Source locks are serialized with final batch publication; configuration/source/network/Relay freshness fences and two real successful samples remain required. Unsupported/partial targets stay visible and cannot fabricate an automatic recommendation.

Source review only; no local builds, automated tests, simulated network requests or observed device cutover claims.

### Network configuration surface

The connection page presents one Relay/custom-address radio selection, with provider-specific controls below it. Saved inactive addresses remain stored. Default routing and source overrides are separate from provider selection. Relay's compact success mark requires applied state, connected VPN and an actual observed response; details retain the distinction between applied rules and verified traffic.

The usage page progressively discloses manga, anime, ratings/metadata/danmaku, download/sync categories and other saved sources. Listing a source never probes or enables it. Locks use the exact automatic-check target IDs; category defaults without aggregate probes have no misleading lock. Source DIRECT saves preserve dormant addresses; the explicit clear-source-settings action removes that source override. Opening the dialog does not jump to historical checks; running a new check and viewing history are separate actions.

UI review used the two supplied screenshots and existing Motion/Material controls. Narrow-screen and enlarged-font rendering remains unverified; no local build or automated tests were run.
