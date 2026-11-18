package dev.mangaflow.anime.sources.lanerc

import dev.mangaflow.anime.sources.animeHash
import dev.mangaflow.json.JSONObject

/**
 * Preserves only the anonymous state of the exact Guazi protocol below when each invocation
 * uses a new JS context. The original source expects these globals to survive between calls.
 *
 * Reference: https://js.z1i.cn/js/guazi.js, SHA-256
 * b1de6ca1f34b47d11d351ee540445da321d547902b08502edfe062fa501e2be9.
 * This bridge is independently implemented; it contains no source cryptographic material.
 *
 * Invoke after loading the source. Save only for writable, still-current source/revision/route
 * calls; LanercPreferences supplies the existing revision fence and private storage.
 */
internal fun lanercSessionRestore(id: String, digest: String, routeKey: String): String? =
    guaziSessionBridge(id, digest, routeKey, restore = true)

internal fun lanercSessionSave(id: String, digest: String, routeKey: String): String? =
    guaziSessionBridge(id, digest, routeKey, restore = false)

private fun guaziSessionBridge(id: String, digest: String, routeKey: String, restore: Boolean): String? {
    if (id != "anime-lanerc-guazi" ||
        digest != "b1de6ca1f34b47d11d351ee540445da321d547902b08502edfe062fa501e2be9" ||
        !routeKey.matches(Regex("[0-9a-f]{64}"))) return null
    val key = JSONObject.quote("mangaflow:guazi-session:v1:" + animeHash(id + "\n" + digest + "\n" + routeKey))
    return """
        (function (g) {
          'use strict';
          try {
            if (typeof g.getItem !== 'function' || typeof g.setItem !== 'function' ||
                !Array.isArray(g.HOSTS) || g.HOSTS.length < 1 || g.HOSTS.length > 64) return;
            function explicit(key) {
              return g.ext && g.ext[key] != null && String(g.ext[key]) !== '';
            }
            // An explicit token without tokenId must not inherit an anonymous user's tokenId.
            // deviceOldKey is the pinned script's actual device override. Other explicit device
            // fields are also left to the source, without inventing their protocol behavior.
            if (g.TOKEN_OVERRIDE || g.TOKEN_ID_OVERRIDE || explicit('token') || explicit('tokenId') ||
                explicit('deviceOldKey') || explicit('deviceId') || explicit('deviceKey')) return;
            function integer(value, maximum) {
              return typeof value === 'number' && isFinite(value) &&
                Math.floor(value) === value && value >= 0 && value <= maximum;
            }
            function text(value, maximum) {
              return typeof value === 'string' && value.length <= maximum && /^[\x21-\x7e]*$/.test(value);
            }
            function valid(value) {
              if (!value || typeof value !== 'object' || Array.isArray(value) ||
                  Object.keys(value).length !== 7 || value.v !== 1) return false;
              if (typeof value.deviceId !== 'string' || typeof value.deviceKey !== 'string' ||
                  !text(value.token, 8192) || !text(value.tokenId, 256) ||
                  !integer(value.hostIndex, g.HOSTS.length - 1) ||
                  !integer(value.failedAt, 9007199254740991)) return false;
              var deviceEmpty = value.deviceId === '' && value.deviceKey === '';
              var deviceValid = /^[0-9]{15}$/.test(value.deviceId) && /^[0-9A-F]{40}$/.test(value.deviceKey);
              if (!deviceEmpty && !deviceValid) return false;
              if (value.token !== '' && !deviceValid) return false;
              if (value.token === '' && value.tokenId !== '') return false;
              return true;
            }
            var state;
            if ($restore) {
              var raw = g.getItem($key, '');
              if (typeof raw !== 'string' || raw.length === 0 || raw.length > 16384) return;
              state = JSON.parse(raw);
            } else {
              state = {v:1, deviceId:g._deviceId, deviceKey:g._deviceKey, token:g._token,
                tokenId:g._tokenId, hostIndex:g._hostIdx, failedAt:g._lastRegFailAt};
            }
            if (!valid(state)) return;
            // Keep a real, recent failed registration even with an empty token. A stale or future
            // wall-clock value must not turn the source's 60-second cooldown into an indefinite one.
            var now = Date.now();
            if (!integer(now, 9007199254740991)) return;
            if (state.token !== '' || state.failedAt > now || now - state.failedAt >= 60000) state.failedAt = 0;
            if ($restore) {
              g._deviceId = state.deviceId;
              g._deviceKey = state.deviceKey;
              g._token = state.token;
              g._tokenId = state.tokenId;
              g._hostIdx = state.hostIndex;
              g._lastRegFailAt = state.failedAt;
            } else {
              var encoded = JSON.stringify(state);
              if (encoded.length <= 16384) g.setItem($key, encoded);
            }
          } catch (_) {
            // Best-effort private state only. Never log payloads, tokens or exception bodies.
          }
        })(globalThis);
        void 0;
    """.trimIndent()
}
