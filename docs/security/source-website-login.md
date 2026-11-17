# Source website login: supported boundary and alternatives

Status: 2026-11-17 source review, not a login/device test. The proposed Android WebView bridge was rejected by independent safety review and removed before commit. No browser session, account login, cookie import or network-setting change was performed. Existing reviewed password/Cookie/API-Key paths remain unchanged.

## Why the attempted shell is not enabled

A new Android WebView profile isolates browser data, but does not constitute a network firewall. `shouldInterceptRequest` observes only the initial resource URL, not a redirected resource's later URL; `shouldOverrideUrlLoading` does not cover POST navigation. Exact-host checks on these callbacks therefore cannot prove all requests remain inside an allowed origin. Blocking service-worker networking does not repair all browser-managed redirect paths. The discarded code must not be treated as a ready login implementation.

- [Android WebViewClient](https://developer.android.com/reference/kotlin/android/webkit/WebViewClient)
- [AndroidX Profile](https://developer.android.com/reference/androidx/webkit/Profile)

ProxyController is not a source-scoped solution: its override applies to all WebViews in the app, ignores the system-wide setting, and has asynchronous application. This project must not silently change global browser routing or fall back to direct access when a selected source route cannot be preserved.

- [AndroidX ProxyController](https://developer.android.com/reference/kotlin/androidx/webkit/ProxyController)

## Mature alternatives, with prerequisites

1. **Provider-supported OAuth authorization-code flow with PKCE.** Prefer a platform external authentication session, exact registered redirect, fresh state and verifier per attempt, fixed issuer/authorization/token endpoints, and authenticated account verification before committing state. This does not require copying website cookies. OAuth for native public clients requires PKCE; an arbitrary website login form cannot be converted into OAuth by the client alone. No reviewed source contract in this task established registered OAuth endpoints or a permitted callback for MangaFlow.
   - [RFC 8252](https://www.rfc-editor.org/rfc/rfc8252)
2. **Android Auth Tab plus explicitly supported ephemeral browsing.** Auth Tab returns a callback result; HTTPS redirect ownership is checked using Digital Asset Links. Ephemeral mode isolates session data. Both capabilities must be detected, and a privacy-sensitive flow must not accept the documented automatic ordinary-Custom-Tab fallback. These APIs do not provide an app-level domain firewall or enforce MangaFlow's Relay/custom source proxy. They are candidates only for a real provider authorization contract and an explicitly supported route, not generic website-cookie scraping.
   - [Auth Tab](https://developer.chrome.com/docs/android/custom-tabs/guide-auth-tab)
   - [Ephemeral Custom Tabs](https://developer.chrome.com/docs/android/custom-tabs/guide-ephemeral-tab)
3. **Apple ASWebAuthenticationSession with ephemeral preference.** Provides a callback-based authentication session and an ephemeral-session request. Safari honors the preference; a different macOS browser may not. It still needs a provider callback protocol and does not prove all traffic uses a MangaFlow source route. No iOS/macOS support is claimed by the current app slice.
   - [Apple ephemeral session property](https://developer.apple.com/documentation/authenticationservices/aswebauthenticationsession/prefersephemeralwebbrowsersession)
   - [Apple web-service authentication](https://developer.apple.com/documentation/authenticationservices/authenticating-a-user-through-a-web-service)
4. **Provider-supported device authorization.** The user approves in the provider's browser page; the app uses the documented device/token endpoints and bounded polling, expiry and denial semantics. This avoids importing browser cookies but requires server support. The app cannot invent device authorization endpoints for an ordinary site. Browser routing is still independent and must be disclosed rather than silently substituted.
   - [RFC 8628](https://www.rfc-editor.org/rfc/rfc8628)

## Current product behavior

Source metadata may declare website login without the host having a safe implementation. The source settings show a non-interactive unsupported explanation. No clickable fake website-login action is exposed, no ACCOUNT capability is invented for a website-only declaration, and anonymous use is not gated behind login. Existing actual account methods remain the only actions.

To reopen implementation: establish a provider-owned authorization contract and client registration, supported callback ownership, browser privacy capability and an honest route policy. If strict all-request domain enforcement and per-source custom routing remain simultaneous requirements, the current platform browser APIs are insufficient; keep the feature blocked rather than weakening those requirements silently. No local build, test, automated probe or personal login was run.
