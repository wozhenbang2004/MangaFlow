# Narrow SD search failure evidence

This observer does not repair the upstream service or establish that requests or
playback succeed. It preserves one precisely evidenced failure hidden by the
pinned script's catch-to-empty behavior; all other ambiguous cases stay unknown.

Scope is `anime-lanerc-sd`, digest
`985c0542acefc3afd13519dde396268fab4bab667ee407e9d4ef688d97138fbf`, method `search`
and a nonempty keyword. State exists only inside one invocation. Both observed
attempts must be actual first-hop POST 200 responses from the exact HTTPS 443
`u.yyxdmn.com/api/new_search/result_v2` endpoint, with no query or fragment.
Each response body hash must match that attempt's AES-decrypt input, and both
corresponding outputs must be syntactically invalid JSON or JSON null. Only if
the final source result is an empty array does the host report that the main
search response parsing did not complete.

A valid JSON value, a successful later response, a third main request, missing
binding evidence, redirects, or over-budget observation does not satisfy this
condition. Legitimate JSON false/zero and objects without `result` are not assigned
an invented business-error meaning. The observer never changes requests, retries,
authentication, script bytes, TLS or routing, and it does not bypass 403 validation.

Additional hashing/parsing is bounded to **262144 characters**, not 262144 encoded
bytes; UTF-16 code units and UTF-8 byte counts differ. A string/escape-aware depth
screen limits diagnostic parsing to 32 nested containers. Deeper outputs stay
unknown. Only attempt flags and response hashes are retained in memory; no keys,
response bodies, decrypted text or hashes are persisted or logged.

Validation is independent static review and diff checking only, not a local
build/test, successful SD request, service recovery or playback acceptance.
