# WS-10: ClojureScript Platform Parity

| | |
|---|---|
| **Status** | Planning |
| **Priority** | P2 |
| **Estimated size** | L |
| **Branch** | ws/10-cljs-platform-parity |
| **Depends on** | WS-02 (`data.json` base64 contract), WS-03 (`runtime.crypto` / `runtime.jwt` file ownership — *not* their cljs implementations), WS-05 (`atproto.runtime.ws` namespace + jetstream rework land first; milestone 5 here builds on them), WS-07 (frozen `datetime` `normalize`/`normalize-or-epoch`/`current-datetime` signatures + http cljs `:timeout`/`:signal` semantics), WS-09 (eval-free lexicon spec registration). Only WS-05 is a hard build-order dependency (milestone 5); see "File ownership" for the land-order handshake. |
| **Blocks** | none directly (terminal integration workstream); unblocks an honest README platform matrix and any future browser/Node example apps |

## Goal

When this workstream is done, the `:cljs` branches of the runtime namespaces (`datetime` — including WS-07's `normalize`/`normalize-or-epoch`/`current-datetime` — `string`, `dns`, `http`, and WS-05's `ws`) are real implementations instead of nil-returning stubs, the cljs byte representation is standardized on `js/Uint8Array`, Jetstream works from ClojureScript (browser and Node), the existing `.cljc` test suite runs under ClojureScript in CI, and the README platform matrix reflects measured reality instead of aspiration. Crypto/JWT on cljs remains explicitly out of scope (WS-03 follow-up) and is documented as such.

## Current state

ClojureScript support is largely nominal. Several `.cljc` namespaces have reader conditionals with only `:clj` branches, so the cljs versions of those functions compile but return `nil` at runtime:

- **datetime** — `src/atproto/runtime/datetime.cljc:22-26` (`parse`) and `:28-30` (`current-time-millis`) are `#?(:clj ...)` only. On cljs, `parse` returns nil for every input, so the `::lexicon/datetime` spec (`src/atproto/lexicon.cljc:62-68`, which ends with `datetime/parse` as a predicate) rejects *all* datetimes. `current-time-millis` returning nil also breaks `atproto.tid/next-tid` (`src/atproto/tid.cljc:28-45`).
- **string** — `src/atproto/runtime/string.cljc:8-10` (`utf8-length`) and `:12-19` (`grapheme-length`) are `:clj`-only. On cljs they return nil, so the lexicon string-length constraints generated at `src/atproto/lexicon.cljc:558-561` evaluate e.g. `(<= nil 300)` — which under JS semantics (`null <= 300` is `true`) means **maxLength/maxGraphemes constraints silently always pass and minLength/minGraphemes always fail**. This is a correctness bug, not just a missing feature.
- **dns** — `src/atproto/runtime/dns.cljc:14-37`: the entire `interceptor` var is `#?(:clj {...})`, i.e. `nil` on cljs. `atproto.identity/resolve-handle` (`src/atproto/identity.cljc:157-168`) unconditionally executes a queue containing this interceptor (`src/atproto/identity.cljc:121-142`), so handle resolution crashes on cljs. `atproto.lexicon.resolver/nsid->did` (`src/atproto/lexicon/resolver.cljc:21-47`) has the same problem.
- **http** — `src/atproto/runtime/http.cljc` has a cljs branch but it is incomplete and buggy:
  - `url-encode` / `url-decode` (`:39-47`) are `:clj`-only → return nil on cljs. They are used by `query-params->query-string` (`:65-72`) and `serialize-url` (`:108-122`), which the cljs `handle-request` itself calls (`:140`), so any request with query params breaks.
  - `parse-url` (`:74-97`) is `:clj`-only → returns nil; used by `atproto.identity/url->web-did` (`src/atproto/identity.cljc:91-96`) and the OAuth client.
  - The `::url` / `::uri` specs (`:99-106`) degrade to bare `string?` on cljs because the validating predicate is inside `#?(:clj ...)`.
  - The cljs `handle-request` (`:137-167`) uses `goog.net.XhrIo`: it hardcodes `(.setTimeoutInterval 0)` (line 142), ignoring the `:timeout` option that callers pass (e.g. `src/atproto/identity.cljc:147`); it binds the XHR error at line 147 but never includes it in the response map (network failures surface as `{:status 0 ...}` instead of `{:error "HTTPClientError" ...}` like the clj branch at `:131-135`); it has no binary body support (`.getResponse` is text-mode); and XhrIo does not work on Node/edge runtimes at all.
- **jetstream** — `src/atproto/jetstream.clj` is JVM-only (`java.net.http.WebSocket`, `charred.api`, `a/>!!` at line 28, `Thread/sleep` at line 50, `java.time.Instant` at lines 87-89).
- **cljs test infrastructure** — none. `deps.edn` has no ClojureScript dependency or alias, there is no shadow-cljs/figwheel config, and `.github/workflows/` does not exist (no CI at all). Worse, every test namespace uses `#?(:cljs [cljs.test :refer :all])` (`test/atproto/lexicon_test.cljc:4`, `test/atproto/identity_test.cljc:2-3`, `test/atproto/runtime/string_test.cljc:2-5`, `test/atproto/xrpc/server_test.cljc:2-3`, `test/atproto/data_test.cljc:2-3`) — `:refer :all` is not supported by the ClojureScript compiler, so none of them currently compile on cljs. The test fixture-inlining macro `interop-test-cases` (`test/atproto/lexicon_test.cljc:15-25`) is already designed for cljs (clj macro inlines file contents at compile time), which is the right pattern.
- **README** — `README.md:12-33` claims 🟢 ClojureScript for "API Client", "Lex Validation", and "Identity Resolution". Given the above, none of these are true today. The matrix must be audited and corrected as part of this workstream's acceptance.

Adjacent known issues owned elsewhere (do not fix here): base64 `$bytes` encode/decode on cljs via `crypto/base64-*` clj-only stubs (`src/atproto/data/json.cljc:39,69,84` — WS-02); `register-specs!` uses `eval` (`src/atproto/lexicon.cljc:709-714`), which does not exist in compiled ClojureScript and blocks lexicon spec registration on cljs (WS-09); `runtime/crypto.cljc` and `runtime/jwt.cljc` are entirely `:clj`-only (WS-03).

## Reference implementation guide

All paths are into `/Users/luke/github/bluesky-social/atproto`.

**Datetime** — `packages/syntax/src/datetime.ts`:
- `DATETIME_REGEX` (lines 317-318): the strict RFC3339-intersection regex (4-digit year, month 01-12, day 01-31, hour 00-23, min/sec 00-59 plus leap-second `60`, optional unbounded fraction, `Z` or `±hh:mm` offset, rejecting offset hours > 23).
- `parseString` (lines 324-346): cheap checks first (string, ≤64 chars, no `-00:00` suffix), then regex, then `new Date(input)` for semantic validation (days-in-month, etc. — V8/JSC/SpiderMonkey return `Invalid Date` for e.g. `2021-02-30T...`).
- `parseDate` (lines 353-369): `NaN` check plus `0 <= getUTCFullYear() <= 9999`.
- `toDatetimeString` / `currentDatetimeString` (lines 174-189): canonical output is `Date.toISOString()` (millisecond precision, `Z` suffix).
- `normalizeDatetime` (lines 209-256) / `normalizeDatetimeAlways` (lines 266-272): timezone-designator detection (offset / `Z` / TZ abbreviation), else try `"<s>Z"` then `"<s> UTC"` then as-is; epoch-string fallback. This is the behavior contract for the `:cljs` bodies of WS-07's `normalize`/`normalize-or-epoch` (see Deliverables).

**UTF-8 / grapheme length** — `packages/lex/lex-data/src/`:
- `utf8.ts:86-87` — `utf8Len` = Node `Buffer.byteLength` when available, else computed loop.
- `utf8-len.ts:13-51` — `utf8LenCompute`: allocation-free UTF-16 → UTF-8 byte-count loop with surrogate-pair handling. Port this (or use `TextEncoder`; see Risks).
- `utf8.ts:53-62` — `graphemeLen` prefers native `Intl.Segmenter`, falls back to the `unicode-segmenter` npm ponyfill with a one-time `console.warn`.
- `utf8-grapheme-len.ts:11-21` — `graphemeLenNative` (iterate `segmenter.segment(str)`) and `graphemeLenPonyfill`.

**Handle resolution / DNS-over-HTTPS** — `packages/internal/handle-resolver/src/`:
- `atproto-handle-resolver.ts:29-80` — the official strategy: race DNS TXT and HTTPS well-known, prefer DNS result, fall back to a secondary TXT resolver. (The Clojure SDK's sequential DNS-then-HTTPS in `identity.cljc:157-168` is a simplification of this; keep it.)
- `internal-resolvers/dns-handle-resolver.ts:12-38` — TXT parsing rules: only records starting `did=`; **more than one `did=` record ⇒ null** (already matched by `identity.cljc:133-142`).
- `atproto-doh-handle-resolver.ts:37-75` — `dohResolveTxtFactory`: `GET {endpoint}?name={hostname}&type=TXT` with `accept: application/dns-json` ("Google flavored" DoH JSON, supported by `https://cloudflare-dns.com/dns-query` and `https://dns.google/resolve`); response shape `{Status, Answer: [{name, type, data, TTL}]}`; TXT answers have `type` 16; `extractTxtData` (lines 122-124) strips surrounding quotes and unescapes `\"`.
- `internal-resolvers/well-known-handler-resolver.ts:20-52` — well-known fetch: first line of body, trimmed, must look like a DID.
- `xrpc-handle-resolver.ts:25-80` — third strategy used by browser apps: `com.atproto.identity.resolveHandle` against a configured service (document as the browser fallback; optional implementation here).
- Node-native reference: `packages/identity/src/handle/index.ts:17-84` (`resolve`, `resolveDns`, `parseDnsResult`).
- Browser guidance (CORS reality, privacy caveats for shared resolvers): `packages/oauth/oauth-client-browser/README.md` lines 95-137.

**HTTP/fetch** — the modern TS stack is fetch-based everywhere; for binary response handling see `packages/lex/lex-client/src/response.ts:325-336` (`arrayBuffer()` → `Uint8Array`, empty body → undefined, content-type switch between `application/json` and binary).

**WebSocket** — `packages/ws-client/src/index.ts`:
- `WebSocketKeepAlive` (line 5), reconnect loop in `[Symbol.asyncIterator]` (lines 25-88), exponential backoff with jitter `backoffMs` (lines 183-188: `min(1000 * (2^n + rand(-0.5,0.5)), maxMs)`), heartbeat ping/terminate (lines 110-138), reconnectable-error classification (lines 161-180).
- Note: there is no official Jetstream client in the TS monorepo (Jetstream itself is a Go service); the existing `src/atproto/jetstream.clj` consume/control-channel API is the design to preserve. WS-05 ports ws-client's backoff/heartbeat into `atproto.runtime.ws` (`05-sync-streaming.md:98-140`); this WS reads ws-client only as background for the `:cljs` branch it adds to that namespace.

**Interop test fixtures** (exact paths):
- `interop-test-files/syntax/datetime_syntax_valid.txt` (40 lines, includes 12-digit fractions, `+01:45` offsets, year 0123)
- `interop-test-files/syntax/datetime_syntax_invalid.txt`
- `interop-test-files/syntax/datetime_parse_invalid.txt` (7 lines: syntax-valid but semantically invalid — month 00/13, day 00, hour 25, minute 99, second 61)
- Richer supersets in `packages/syntax/tests/interop-files/datetime_valid.txt` and `datetime_invalid.txt` (40/68 lines).
- All of `interop-test-files/syntax/*.txt` are **already vendored** at `test/interop-test-files/syntax/` and verified byte-identical to the reference repo as of this writing (`diff -q` clean).

## Scope

### In scope

- `:cljs` branch for `atproto.runtime.datetime`: strict `parse` (regex + `js/Date` semantic validation + year-range check, ported from `packages/syntax/src/datetime.ts`), `current-time-millis` (`js/Date.now`), a new cross-platform canonical formatting helper (`->string`), and the `:cljs` bodies of WS-07's frozen `current-datetime`, `normalize`, and `normalize-or-epoch` (WS-07 lands signatures + `:clj` bodies with cljs stubs that may return `{:error "NotImplemented"}` — `07-xrpc-client-ergonomics.md:436,458`; this WS replaces the stubs). Per `00-overview.md` §4.9 item 6 there is no separate `now-string`: WS-10 adopts WS-07's `current-datetime` name.
- `:cljs` branch for `atproto.runtime.string`: `utf8-length` (ported byte-count loop, validated against `TextEncoder`) and `grapheme-length` (`Intl.Segmenter` with documented fallback policy).
- `:cljs` (and reusable cljc) DNS-over-HTTPS TXT resolver in `atproto.runtime.dns` with a pluggable endpoint (default `https://cloudflare-dns.com/dns-query`), same response contract as today's clj interceptor (`{:values [...]}` / `{:error ...}`); graceful `{:error "DNSUnavailable"}` when disabled so `identity/resolve-handle` falls through to the `.well-known` HTTPS method.
- Documented browser handle-resolution story: DoH works in browsers (CORS-enabled public endpoints); `.well-known` is usually CORS-blocked cross-origin; `com.atproto.identity.resolveHandle` via a trusted service is the third option (documented; implementation optional).
- Complete the cljs HTTP runtime: replace `goog.net.XhrIo` with `js/fetch` (browser + Node ≥18 + edge); honor `:timeout` via `AbortController` (timeout expiry ⇒ `{:error "Timeout"}`) and an optional `:signal` (abort ⇒ `{:error "Aborted"}`) — preserving the semantics WS-07 freezes for its interim XhrIo change (`07-xrpc-client-ergonomics.md:440-441`; `00-overview.md:446`); map other network failures to `{:error "HTTPClientError" :message ...}`; string bodies for `application/json`/`text/*` responses, `js/Uint8Array` for binary; request bodies accepting strings and byte arrays; cljs `url-encode`/`url-decode`/`parse-url`/`serialize-url` and real `::url`/`::uri` spec predicates.
- Standardize the cljs byte representation on `js/Uint8Array`: change `atproto.runtime.bytes/bytes?` (`src/atproto/runtime/bytes.cljc:10-13`, currently tests `js/Int8Array`) to test `js/Uint8Array`. WS-10 owns this decision (`00-overview.md:439`), and `Uint8Array` is WS-02's recommendation (`02-dag-cbor-data-model.md:360` — it's what goog.crypt, multiformats/alphabase, and fetch/WebSocket boundaries produce, and matches the TS SDK's raw `Uint8Array`). Without it, the binary HTTP/WS payloads this WS returns would fail the SDK's own `bytes?` predicate. See Deliverables and milestone 3.
- `:cljs` branch of WS-05's `atproto.runtime.ws` (`src/atproto/runtime/ws.cljc`): WS-05 creates the namespace with the `:clj` implementation and the reconnect/backoff/heartbeat policy, cljs stubbed `{:error "NotImplemented"}` (`05-sync-streaming.md:68,98-140,603`); this WS replaces the stub with a `js/WebSocket` implementation (browser; Node ≥22 global) of the same API. Per `00-overview.md` §4.9 item 7, **no `runtime/websocket.cljc` is created**.
- Port `atproto.jetstream` from `.clj` to `.cljc` **after WS-05's jetstream rework lands** (WS-05 owns `src/atproto/jetstream.clj` first — `05-sync-streaming.md:526`; its milestone 8 already rebases connection management onto `atproto.runtime.ws`, fixing the `Thread/sleep`/backoff bugs). This WS then does the `git mv`, preserves the public API (`consume`, `current-time-us`, `us-str`, plus whatever additive options WS-05 shipped), and replaces remaining JVM-only internals with cross-platform ones (`a/put!` for any remaining `a/>!!`, `runtime.json` for direct charred calls, cast logging).
- cljs test infrastructure: shadow-cljs `:node-test` build + `package.json`, fix the `:refer :all` cljs-incompatibilities in existing test namespaces, an explicit allowlist of cljs-clean test namespaces that grows as WS-02/09 land, and a GitHub Actions workflow running both the JVM suite (`clojure -X:test`) and the cljs suite.
- New cross-platform unit tests: `runtime/datetime_test.cljc` (driven by the vendored interop fixtures, including the currently-unused `datetime_parse_invalid.txt`), expanded `runtime/string_test.cljc`, `runtime/http_test.cljc`, `runtime/dns_test.cljc`.
- README platform-matrix audit and correction (ClojureScript column, Jetstream row, and the "Jetstream is JVM-only" prose at `README.md:104`).

### Out of scope

- **`src/atproto/runtime/crypto.cljc` and `src/atproto/runtime/jwt.cljc`** — owned by WS-03. Do not add cljs branches even though they are the next obvious gap. The coordinated follow-up plan (documented, not implemented): WebCrypto (`crypto.subtle`) for SHA-256/random/ES256 where possible, `@noble/curves`+`@noble/hashes` via npm for secp256k1, JWS assembly in cljc code. The crypto contract's key operations are async (overview §4.2) precisely so WebCrypto's Promise-based API fits without an API change. Consequence to document: **OAuth and DPoP do not work on cljs after WS-10**; credentials-based auth (`atproto.credentials`, which uses no crypto — verified: requires only interceptor/identity/xrpc-client) does.
- **Base64 / `$bytes` JSON round-trip on cljs** (`src/atproto/data/json.cljc:39,69,84`) — owned by WS-02. The cljs byte-*representation* decision is **not** out of scope: WS-10 owns the `Int8Array`→`Uint8Array` standardization (`00-overview.md:439`; WS-02's doc explicitly defers to WS-10 and writes its `:cljs` branches against whatever `bytes/bytes?` says at merge time — `02-dag-cbor-data-model.md:360`). See "In scope", Deliverables, and milestone 3.
- **`register-specs!`/`eval` replacement** (`src/atproto/lexicon.cljc:709-714`) — owned by WS-09. Until it lands, `lexicon_test` and any schema-registration path cannot run on cljs; the cljs test allowlist simply excludes them.
- **`src/atproto/lexicon.cljc` itself** — no edits here. The string/datetime fixes land in the runtime namespaces its generated specs already call.
- **`atproto.lexicon.resolver`** — it is currently broken on *both* platforms (uses `nsid/parse`, `did/resolve`, `str/split` without requiring them — see `src/atproto/lexicon/resolver.cljc:21-28,53-69`); repairing it is not a cljs-parity concern. Our DoH interceptor must merely be drop-in compatible with its `dns/interceptor` usage at `:32-34`.
- **ClojureDart** — separate future effort. Do not add `:cljd` branches.
- **Browser-bundle test runner (karma/playwright)** — Node-based cljs tests only in CI; a manual browser smoke check is documented but not automated.
- **CBOR firehose client** — only Jetstream (JSON) is in scope; the `runtime.ws` `:cljs` branch must support binary frames (delivering `js/Uint8Array` to `:on-message`) so WS-05's firehose client can later work on cljs.

## Deliverables

### `atproto.runtime.datetime` (modified)

```clojure
(ns atproto.runtime.datetime
  "Cross-platform parsing/formatting of atproto datetime strings.")

(defn parse
  "Strictly parse an atproto datetime string (RFC 3339 / ISO 8601 / WHATWG
  intersection, per https://atproto.com/specs/lexicon#datetime).

  Returns a platform-native datetime (java.time.OffsetDateTime on the JVM,
  js/Date on JS) or nil if the string is not a valid, semantically-parseable
  datetime. Enforces: regex shape, calendar validity (days in month), year
  0000-9999. Callers must not rely on sub-millisecond precision on JS.

  Note: the existing clj branch is unchanged. The cljs branch applies a strict
  RFC3339 regex (ported from the TS reference DATETIME_REGEX; do NOT reuse the
  looser atproto.lexicon.regex/rfc3339), trims the fractional part to 3 digits
  for engine portability, constructs (js/Date. s), and rejects when
  (js/isNaN (.getTime d)) or (.getUTCFullYear d) is outside 0..9999."
  [s])

(defn ->string
  "Canonical atproto datetime string for a value returned by `parse`:
  YYYY-MM-DDTHH:mm:ss.sssZ (UTC, millisecond precision).
  clj: format via DateTimeFormatter on the Instant truncated to millis.
  cljs: (.toISOString d)."
  [dt])

(defn current-datetime
  "The current time as a canonical atproto datetime string.
  Signature + :clj body land in WS-07 (07-xrpc-client-ergonomics.md:419-422,
  frozen contract item 6 at :453); this WS fills the :cljs body:
  (.toISOString (js/Date.)). (This replaces the now-string helper from an
  earlier draft — per 00-overview.md §4.9 item 6, WS-07's name wins.)"
  [])

(defn normalize
  "Flexible datetime string → canonical UTC ISO-8601 millisecond-precision
  string (\"...sssZ\"), or {:error \"InvalidDatetime\" :message ...}.
  Signature + :clj body are WS-07's (07-xrpc-client-ergonomics.md:424-429);
  on cljs WS-07 stubs it (may return {:error \"NotImplemented\"}, 07:458) and
  this WS replaces the stub, porting normalizeDatetime
  (packages/syntax/src/datetime.ts:209-256): if the string carries a timezone
  designator (offset, Z, or TZ abbreviation) parse as-is via (js/Date. s);
  otherwise try (js/Date. (str s \"Z\")), then (js/Date. (str s \" UTC\")),
  then as-is; accept only dates passing the NaN + year 0000-9999 checks;
  output (.toISOString d)."
  [s])

(defn normalize-or-epoch
  "Like normalize but returns \"1970-01-01T00:00:00.000Z\" instead of an
  error map (ports normalizeDatetimeAlways,
  packages/syntax/src/datetime.ts:266-272). :clj body is WS-07's; this WS
  fills the :cljs body."
  [s])

(defn current-time-millis
  "Milliseconds since the UNIX epoch. cljs: (js/Date.now)."
  [])
```

### `atproto.runtime.string` (modified)

```clojure
(defn utf8-length
  "Number of bytes in the UTF-8 encoding of s.
  cljs: allocation-free counting loop ported from lex-data utf8LenCompute
  (UTF-16 code units, +1 for 0x80-0x7FF, +2 above, surrogate-pair aware)."
  [s])

(defn grapheme-length
  "Number of extended grapheme clusters in s.
  cljs: uses a cached (js/Intl.Segmenter.) instance when available. When
  Intl.Segmenter is missing (only legacy engines; Node>=16, all evergreen
  browsers incl. Firefox>=125 have it) falls back to counting Unicode code
  points and emits a single atproto.runtime.cast/alert documenting the
  deviation (ZWJ sequences over-counted)."
  [s])
```

### `atproto.runtime.dns` (modified)

```clojure
(ns atproto.runtime.dns
  "Cross-platform DNS client.
  Request map: {:hostname string, :type \"txt\", :doh-endpoint url?}
  Response map: {:values [string]} | {:error string, ...} — unchanged contract.")

(def default-doh-endpoint
  "Atom holding the default DNS-over-HTTPS endpoint URL (Google-flavored
  application/dns-json protocol), or nil to disable DoH.
  Defaults: clj nil (JNDI is used), cljs \"https://cloudflare-dns.com/dns-query\"."
  )

(defn set-default-doh-endpoint!
  "Set (or, with nil, disable) the process-wide default DoH endpoint."
  [url])

(def doh-interceptor
  "DoH TXT resolution. Pure cljc: issues a GET via the http/json interceptors
  with accept: application/dns-json, filters Answer entries with type 16,
  strips surrounding quotes / unescapes \\\" in :data (per the TS reference
  extractTxtData), and returns {:values [...]} on Status 0 with answers,
  {:error \"DNS name not found\"} otherwise. Endpoint resolution order:
  (:doh-endpoint request), then @default-doh-endpoint; with neither, responds
  {:error \"DNSUnavailable\" :message ...}.")

(def interceptor
  "Platform-default DNS interceptor (drop-in for existing callers):
  clj  — JNDI system resolver, unchanged.
  cljs — doh-interceptor."
  )
```

No changes are required in `atproto.identity`: `resolve-handle` (`src/atproto/identity.cljc:157-168`) already treats any DNS `{:error ...}` as a fall-through to the `.well-known` HTTPS method, which is exactly the desired browser behavior when DoH is disabled.

### `atproto.runtime.http` (modified)

```clojure
(defn url-encode [s]
  ;; cljs: (js/encodeURIComponent s)
  ;; clj: unchanged (note "+" vs "%20" divergence; see Risks)
  )
(defn url-decode [s])  ;; cljs: (js/decodeURIComponent s)

(defn parse-url [s]
  ;; cljs: via (js/URL. s); same output map {:protocol :host :port :path
  ;; :query-params :fragment}. Port parsed with js/parseInt when non-empty;
  ;; :path omitted when pathname is "/" and the input has no path component
  ;; (parity with the clj branch's blank-path behavior); fragment without "#".
  ;; Returns nil on construction error.
  )

(s/def ::url ...)  ;; cljs predicate: #(try (js/URL. %) true (catch :default _ false))
(s/def ::uri ...)  ;; cljs: same constructor check (js has no distinct URI type)

(defn handle-request
  "cljs branch rewritten on js/fetch (browser, Node >=18, Deno, workers):
   - URL built via serialize-url/query-params->query-string (now functional on cljs)
   - headers via stringify-keys/clj->js, method name upper-cased
   - :timeout (ms) honored via AbortController + js/setTimeout (clearTimeout on
     settle); timeout expiry => (cb {:error \"Timeout\" ...})
   - optional :signal in the request map; abort => (cb {:error \"Aborted\" ...})
     (both per the semantics WS-07 froze for its interim XhrIo change,
     07-xrpc-client-ergonomics.md:440-441 — this fetch rewrite subsumes that
     change and must preserve them, 00-overview.md:446)
   - :follow-redirects false -> {:redirect \"manual\"}
   - request :body passed through (string | js/Uint8Array | js/ArrayBuffer)
   - response :status int; :headers keyword/lower-case map (iterate (.-headers resp))
   - response :body — content-type application/json* or text/* => string
     (via (.text resp)); anything else => js/Uint8Array (via (.arrayBuffer resp));
     empty body => nil
   - other rejection/network error => (cb {:error \"HTTPClientError\" :message ... :ex e})"
  [http-request cb])
```

### `atproto.runtime.bytes` (modified — one-line `bytes?` change, coordinated with WS-02)

Decision recorded here (WS-10 owns it per `00-overview.md:439`; WS-02 recommends the same, `02-dag-cbor-data-model.md:360`): **the canonical cljs byte type is `js/Uint8Array`**. Concretely, the `:cljs` branch of `bytes?` (`src/atproto/runtime/bytes.cljc:10-13`) changes from `(= js/Int8Array (type v))` to testing `js/Uint8Array` (instance check, so subclassing/cross-realm caveats are documented if relevant). Rationale: every byte-producing boundary this WS and WS-02 touch — fetch `arrayBuffer()`, WebSocket binary frames, `goog.crypt`, multiformats/alphabase — produces `Uint8Array`, and the TS SDK's data model is raw `Uint8Array`; keeping `Int8Array` would make the SDK's own predicate reject its own payloads. Landing: by milestone 3 at the latest (before binary fetch bodies ship). WS-02 owns the file (`eq?` `:cljs` branch), so if WS-02 is in flight the one-liner lands inside WS-02's PR (WS-10 confirms the decision to WS-02 at kickoff — WS-02's doc marks it "Open until WS-10 confirms"); otherwise WS-10 lands it directly. The `->utf8` cljs branch (`bytes.cljc:19-22`) already converts via `js/Uint8Array.` and keeps working.

### `atproto.runtime.ws` (`:cljs` branch added; namespace created and owned by WS-05)

Per `00-overview.md` §4.9 item 7 and the conflict matrix (`00-overview.md:441-442`), there is exactly one websocket namespace: WS-05's `atproto.runtime.ws` (`src/atproto/runtime/ws.cljc`, API specced at `05-sync-streaming.md:98-140` — `connect`/`send!`/`close!`/`connected?` with `:url-fn`/`:headers`/`:on-message`/`:on-error`/`:on-reconnect`/`:on-close`/`:max-reconnect-ms`/`:heartbeat-interval-ms` config and the ws-client reconnect/backoff policy). WS-05 ships it `:clj`-only with cljs stubbed `{:error "NotImplemented"}` (`05-sync-streaming.md:603`). **This WS does not create `runtime/websocket.cljc`** (an earlier draft did); it replaces the cljs stub, implementing WS-05's API verbatim — no redesign:

- `js/WebSocket` (browser; Node ≥22 global — older Node users polyfill via the `ws` package, documented), `binaryType` set to `"arraybuffer"`; `:on-message` receives a string for text frames and `js/Uint8Array` for binary frames (clj delivers `^bytes`, per WS-05's contract).
- Reconnect/backoff: reuse WS-05's backoff logic (jittered exponential, capped at `:max-reconnect-ms`) — shared cljc code where WS-05 structured it that way, else a faithful cljs twin; `:url-fn` re-invoked before every (re)connect.
- Platform caveats (documented in the namespace, coordinated with WS-05's own cljs note at `05-sync-streaming.md:603`): browser `WebSocket` cannot set custom headers — non-empty `:headers` on cljs surfaces `{:error "WebSocketError" :message "custom headers unsupported on this platform"}` via `:on-error`; there is no client ping API, so `:heartbeat-interval-ms` degrades to an inactivity watchdog (terminate + reconnect when no message arrives within the interval) rather than ping/pong.

### `atproto.jetstream` (renamed `.clj` → `.cljc`, after WS-05's rework)

Land order (agreed in both docs and the overview matrix, `00-overview.md:441`, `05-sync-streaming.md:526`): WS-05 owns `src/atproto/jetstream.clj` first — its milestone 8 rebases connection management onto `atproto.runtime.ws`, adds `:cursor-store` and typed events, and fixes the `Thread/sleep`/`a/>!!`-on-listener-thread/backoff bugs. Only then does this WS `git mv` the file to `.cljc` and make the remaining internals cross-platform, rebasing over WS-05's final state.

Public API preserved exactly as WS-05 ships it (additive over today's `(consume ch & {:keys [host cursor control-ch max-retries wanted-collections close?]})` returning the control channel; `current-time-us`; `us-str`). Changes in this WS:

- `:cljs` reader conditionals so the WS-05 implementation (already on `atproto.runtime.ws`) compiles and runs on cljs.
- JSON parsing via `atproto.runtime.json/read-str` (charred on clj — same library as today via `src/atproto/runtime/json.cljc:20-21` — `js/JSON.parse` on cljs) applied in the channel transducer, replacing any remaining direct charred calls.
- Sweep for any JVM-only core.async ops WS-05's rework left behind (`a/put!` instead of `a/>!!`, `(a/<! (a/timeout ms))` in go-loops instead of blocking sleeps); expected to be mostly done by WS-05.
- `us-str` cljs branch via `(js/Date. (/ us 1000))` + `.toISOString` (document microsecond truncation; clj branch keeps `Instant` precision).
- Logging via `atproto.runtime.cast` instead of `clojure.tools.logging`, if WS-05 hasn't already converted it (cast is already cross-platform, `src/atproto/runtime/cast.cljc:10-17`).

### Test/CI infrastructure (new files)

- `shadow-cljs.edn` — `{:deps {:aliases [:test]} :builds {:test {:target :node-test :output-to "target/node-tests.js" :ns-regexp "..."}}}` with the regexp/allowlist of cljs-clean namespaces.
- `package.json` — `shadow-cljs` devDependency only; no runtime npm deps.
- `.github/workflows/test.yml` — two jobs: `clj` (temurin 21 + clojure CLI, `clojure -X:test`) and `cljs` (Node 22 + `npm ci` + `npx shadow-cljs compile test` + `node target/node-tests.js`).
- `deps.edn` — no changes required for cljs (shadow-cljs reads the `:test` alias); if a `:cljs` convenience alias is added, coordinate (see File ownership).

## Interface contract

**Provided (frozen for other workstreams):**

- `atproto.runtime.datetime/parse` — string → platform datetime | nil; `->string`, `current-time-millis` as above. Consumers: `atproto.lexicon` (predicate position only), `atproto.tid`. Plus the `:cljs` bodies fulfilling WS-07's frozen `normalize`/`normalize-or-epoch`/`current-datetime` contract (`07-xrpc-client-ergonomics.md:453`; consumers: WS-07's `atproto.repo` conveniences and anyone normalizing user-supplied datetimes) — identical fixture-verified behavior on both platforms.
- `atproto.runtime.string/utf8-length`, `grapheme-length` — string → int on both platforms (never nil). Consumer: `atproto.lexicon` generated specs.
- `atproto.runtime.dns/interceptor` — request `{:hostname :type}`, response `{:values [...]}`/`{:error ...}` — unchanged shape, now total on cljs. Consumers: `atproto.identity`, `atproto.lexicon.resolver`.
- `atproto.runtime.http/handle-request` response contract on cljs: `:body` is string for JSON/text content types and `js/Uint8Array` otherwise; network errors are `{:error "HTTPClientError" :message ...}` (matching the clj branch), `:timeout` expiry is `{:error "Timeout"}` and `:signal` abort is `{:error "Aborted"}` (matching WS-07's frozen cljs semantics, `07-xrpc-client-ergonomics.md:440-441`).
- `atproto.runtime.bytes/bytes?` on cljs tests `js/Uint8Array` — the standardized representation (decision owned here, `00-overview.md:439`); all binary payloads this WS produces satisfy it.
- `atproto.runtime.ws` becomes functional on cljs with WS-05's exact API (`connect|send!|close!|connected?`) — future firehose/sync workstreams may code against it on both platforms.

**Consumed (assumptions about other workstreams):**

- From WS-02: a working `data.json` base64 round-trip. (The canonical cljs byte type is *provided by* this WS, not consumed: WS-10 owns the `Int8Array`→`Uint8Array` decision and WS-02 writes its `:cljs` branches against whatever `bytes/bytes?` says at merge time, `02-dag-cbor-data-model.md:360`.) Until merged: WS-10 code never constructs lexicon `bytes` values; HTTP/WebSocket binary payloads are `js/Uint8Array` at the boundary (consistent with the standardization); tests touching `data`/`data.json` stay off the cljs allowlist. Develop against vendored datetime fixtures only — no WS-02 stubs needed.
- From WS-05: the `atproto.runtime.ws` namespace (`:clj` implementation + frozen API) and the reworked jetstream (on `runtime.ws`, with `:cursor-store`/typed events). Until merged: milestone 5 here is blocked — do **not** pre-create a `runtime/websocket.cljc` stopgap (§4.9 item 7); milestones 1-4 have no WS-05 dependency.
- From WS-07: the frozen `normalize`/`normalize-or-epoch`/`current-datetime` signatures (`:clj` bodies + cljs NotImplemented stubs, `07-xrpc-client-ergonomics.md:436,453,458`) and the http cljs `:timeout`/`:signal` error semantics (`{:error "Timeout"}`/`{:error "Aborted"}`, `07:440-441`). Until merged: implement `parse`/`->string`/`current-time-millis` first; the normalize-family `:cljs` bodies land in milestone 2 only after the signatures are merged or explicitly agreed with WS-07.
- From WS-09: an eval-free `register-specs!`. Until merged: `lexicon_test` (which itself calls `eval`, `test/atproto/lexicon_test.cljc:372-376`) stays off the cljs allowlist; the `::lexicon/datetime`-via-`datetime/parse` integration is instead covered by the new `runtime/datetime_test.cljc` against the same fixtures.
- From WS-03: nothing at build time (crypto fns are only invoked at runtime by oauth/dpop paths, which WS-10 does not exercise on cljs).

## File ownership

Created: `src/atproto/jetstream.cljc` (git mv from `src/atproto/jetstream.clj`, **after WS-05's jetstream milestone lands**), `test/atproto/runtime/datetime_test.cljc`, `test/atproto/runtime/http_test.cljc`, `test/atproto/runtime/dns_test.cljc`, `test/atproto/jetstream_test.cljc`, `shadow-cljs.edn`, `package.json`, `.github/workflows/test.yml`. (No `src/atproto/runtime/websocket.cljc` — see §4.9 item 7 and the table below.)

Modified: `src/atproto/runtime/datetime.cljc` (`:cljs` branches incl. WS-07's normalize family), `src/atproto/runtime/string.cljc`, `src/atproto/runtime/dns.cljc`, `src/atproto/runtime/http.cljc`, `src/atproto/runtime/ws.cljc` (`:cljs` branch; WS-05's namespace), `src/atproto/runtime/bytes.cljc` (one-line `bytes?` change; WS-02 coordination per table), `test/atproto/runtime/string_test.cljc`, `README.md` (matrix + Jetstream prose), and **mechanical `:refer :all` fixes only** in `test/atproto/identity_test.cljc`, `test/atproto/xrpc/server_test.cljc`, `test/atproto/data_test.cljc`, `test/atproto/data/json_test.cljc`, `test/atproto/lexicon_test.cljc` (change `#?(:clj [clojure.test :refer :all] :cljs [cljs.test :refer :all])` to unconditional `[clojure.test :refer [deftest is are testing]]`, which cljs aliases to `cljs.test`).

Handshake with other workstreams (agreed resolution: **WS-10 lands last and rebases**):

| File | Also touched by | Resolution |
|---|---|---|
| `src/atproto/runtime/crypto.cljc`, `src/atproto/runtime/jwt.cljc` | WS-03 (owner) | WS-10 never touches them. |
| `src/atproto/data/json.cljc`, `test/atproto/data_test.cljc`, `test/atproto/data/json_test.cljc` | WS-02 (owner) | WS-10 only does the `:refer :all` test fix and only if WS-02 hasn't already; rebase and drop on conflict. |
| `src/atproto/runtime/bytes.cljc` | WS-02 (file owner: `eq?` `:cljs`), WS-04 (additive `concat-bytes`/`slice`) | **WS-10 owns the `Int8Array`→`Uint8Array` decision** (`00-overview.md:439`): standardize `bytes?` on `js/Uint8Array`. The one-line change lands inside WS-02's PR if WS-02 is in flight (WS-10 confirms the decision at kickoff), else WS-10 lands it directly. |
| `src/atproto/runtime/ws.cljc` | WS-05 (owner, creates it) | **Single websocket namespace** (`00-overview.md` §4.9 item 7, conflict matrix `:442`): WS-10 creates no `runtime/websocket.cljc`; it adds the `:cljs` branch to WS-05's namespace after WS-05 lands it, implementing WS-05's API verbatim. |
| `src/atproto/jetstream.clj` (→ `.cljc`) | WS-05 (owner first — `05-sync-streaming.md:526`) | WS-05's rework (runtime.ws rebase, cursor-store, typed events) lands first; WS-10 then does the `git mv` + `:cljs` branch, rebasing over WS-05's final state. If both are in flight, WS-05 merges first. |
| `src/atproto/runtime/http.cljc` | WS-07 (interim cljs `:timeout`/`:signal` on XhrIo — `07-xrpc-client-ergonomics.md:440-441,467`) | WS-10's fetch rewrite subsumes WS-07's change but **preserves the frozen semantics**: `:timeout` → `{:error "Timeout"}`, `:signal` abort → `{:error "Aborted"}` (`00-overview.md:446`). WS-10 rebases. |
| `src/atproto/runtime/datetime.cljc` | WS-07 (`normalize`/`normalize-or-epoch`/`current-datetime` signatures + `:clj` bodies), WS-09 (`parse-lenient`) | Disjoint fns (`00-overview.md:445`): WS-10 adds `:cljs` branches last, replacing WS-07's NotImplemented cljs stubs; no `now-string` (§4.9 item 6). |
| `src/atproto/lexicon.cljc`, `test/atproto/lexicon_test.cljc` | WS-09 (owner) | WS-10 makes no `lexicon.cljc` edits; the test-ns refer fix follows the same drop-on-conflict rule. |
| `test/atproto/identity_test.cljc` | WS-06 (modifies — `06-identity-plc.md:380`) | WS-10's change is the mechanical `:refer :all` fix only, same drop-on-conflict rule as the WS-02/09 test namespaces. |
| `deps.edn`, `README.md`, `.github/workflows/test.yml` | potentially all workstreams | WS-10 rebases; README matrix edit happens in the final milestone after WS-02/03/05/07/09 are in. |

## Test plan

**Unit tests (cross-platform, run on JVM and Node):**

- `test/atproto/runtime/datetime_test.cljc` (new): every line of `test/interop-test-files/syntax/datetime_syntax_valid.txt` ⇒ `(some? (parse s))`; every line of `datetime_parse_invalid.txt` (vendored but currently unused by any test) ⇒ nil; every line of `datetime_syntax_invalid.txt` ⇒ nil; `->string`/`current-datetime` produce strings that re-`parse` and match `#"\.\d{3}Z$"`. Normalize family, asserted on **both** platforms (extending WS-07's clj-only coverage, `07-xrpc-client-ergonomics.md:489`): every `datetime_syntax_valid.txt` line normalizes without error; every `datetime_parse_invalid.txt` line (month 00/13, day 00, hour 25, minute 99, second 61) yields `{:error "InvalidDatetime" ...}` from `normalize`; `normalize-or-epoch` returns `"1970-01-01T00:00:00.000Z"` for those and for garbage; `current-datetime` output is `normalize`-stable. Reuse the `interop-test-cases` inlining-macro pattern from `test/atproto/lexicon_test.cljc:15-25` (move it to a shared `test/atproto/test_support.cljc` helper).
- `test/atproto/runtime/string_test.cljc` (extend existing): existing cases (already include the 25-byte/1-grapheme family emoji) plus `"café"` → utf8 6 / graphemes 4 (parity with `lex-data/src/utf8.ts` doc examples) and a `TextEncoder` cross-check on cljs.
- `test/atproto/runtime/http_test.cljc` (new): pure functions only — `parse-url`/`serialize-url` round-trips for the URL shapes the SDK constructs (`https://plc.directory/did:plc:...`, `https://host/.well-known/atproto-did`, xrpc URLs with repeated query params), `url-encode`/`url-decode`, `query-params->query-string` parity assertions between platforms.
- `test/atproto/runtime/dns_test.cljc` (new): DoH answer parsing as a pure function (factor `answers->values` out of the interceptor): quoted/escaped TXT data, multiple `did=` records ⇒ error, `Status` ≠ 0 ⇒ error, no endpoint ⇒ `DNSUnavailable`.

**Fixtures to vendor** (into `test/interop-test-files/syntax/`, which is on the `:test` classpath):

- Already present and byte-identical — re-sync check only: `interop-test-files/syntax/datetime_{syntax_valid,syntax_invalid,parse_invalid}.txt` from `/Users/luke/github/bluesky-social/atproto/interop-test-files/syntax/`.
- New, from the syntax package's own test dir: `/Users/luke/github/bluesky-social/atproto/packages/syntax/tests/interop-files/datetime_valid.txt` and `datetime_invalid.txt` → vendor as `test/interop-test-files/syntax/datetime_valid.txt` / `datetime_invalid.txt` (supersets exercising lenient-vs-strict edges).

**cljs test runner:** shadow-cljs `:node-test` build on Node ≥22. Initial allowlist: `atproto.runtime.string-test`, `atproto.runtime.datetime-test`, `atproto.runtime.http-test`, `atproto.runtime.dns-test`, `atproto.identity-test` (spec-only tests; verify `identity.cljc` compiles on cljs — it requires `lexicon.cljc`, whose `register-specs!`/`eval` is a compile *warning* not error under shadow-cljs; if it hard-fails, gate on WS-09 and document). Grow to `data`/`json`/`lexicon`/`xrpc.server` tests as WS-02/09 land.

**Integration tests** (JVM + Node, `^:integration`-tagged, excluded from default CI runs; run manually and in a scheduled workflow):

- `identity/resolve-handle` for a stable handle (e.g. `atproto.com` → its DID) on clj (JNDI) and cljs (DoH via Cloudflare and via `https://dns.google/resolve`).
- `identity/resolve-identity` end-to-end against `plc.directory` over the fetch-based http runtime.
- `test/atproto/jetstream_test.cljc`: connect to `jetstream1.us-east.bsky.network`, receive ≥1 event within 30s, close via control channel; assert reconnect logic with a deliberately bad host (expects error after `max-retries`).

**Live-service verification:** documented manual checklist — Node REPL: resolve handle, run an unauthenticated `app.bsky.actor.getProfile` query against `https://public.api.bsky.app`, consume 100 Jetstream events; browser (vite or shadow `:browser` scratch target): same query + DoH handle resolution, confirming the `.well-known` fallback fails gracefully (CORS) rather than crashing.

## Acceptance criteria

- [ ] `(parse s)` on cljs returns non-nil for all 40 entries of `datetime_syntax_valid.txt` and nil for all entries of `datetime_syntax_invalid.txt` and `datetime_parse_invalid.txt`; same results on clj (no regression).
- [ ] `normalize`/`normalize-or-epoch`/`current-datetime` (WS-07's frozen signatures) behave identically on clj and cljs across the vendored datetime fixtures: every `datetime_syntax_valid.txt` entry normalizes; every `datetime_parse_invalid.txt` entry errors from `normalize` and yields the epoch string from `normalize-or-epoch`; `current-datetime` is `normalize`-stable. No `now-string` exists in the codebase (§4.9 item 6).
- [ ] `atproto.runtime.bytes/bytes?` on cljs returns true for `js/Uint8Array` (and the docstring/decision note records the standardization); binary HTTP and WebSocket payloads produced by this WS satisfy `bytes?`.
- [ ] `utf8-length` and `grapheme-length` return correct ints on cljs for the full existing test table (`test/atproto/runtime/string_test.cljc:7-30`), including 25/1 for the family emoji.
- [ ] Lexicon `maxLength`/`maxGraphemes` constraints actually reject over-long strings on cljs (verified via a directly-`s/def`'d spec mirroring `lexicon.cljc:556-564`, or via `lexicon_test` if WS-09 has landed).
- [ ] `identity/resolve-handle` works on Node cljs via DoH against a live handle, and returns `{:error ...}` (not a crash) in an environment with DoH disabled.
- [ ] cljs `handle-request` via fetch: JSON GET against `plc.directory` succeeds; `:timeout` honored (request against a black-hole host fails with `{:error "Timeout"}` within the timeout, per WS-07's frozen semantics); `:signal` abort yields `{:error "Aborted"}`; binary response yields `js/Uint8Array` (satisfying `bytes?`).
- [ ] `jetstream/consume` receives and decodes live events on JVM (no regression) and on Node cljs; control-channel close cleanly terminates; public API signature unchanged.
- [ ] `npx shadow-cljs compile test && node target/node-tests.js` passes locally and in CI; `clojure -X:test` still passes; both run in `.github/workflows/test.yml` on push/PR.
- [ ] No remaining `:refer :all` under a `:cljs` reader conditional in `test/`.
- [ ] README platform matrix re-audited: every ClojureScript 🟢/🟡/⭕ claim matched to a passing test or demoted, including Stream client → 🟢 (Jetstream) for cljs and explicit ⭕/note for OAuth-on-cljs (WS-03); `README.md:104` "JVM only" Jetstream sentence updated.
- [ ] `interop-test-files/README.md` provenance note updated if new fixtures vendored; vendored fixtures byte-identical to reference paths cited above.

## Milestones

1. **cljs test scaffolding** — `shadow-cljs.edn`, `package.json`, CI workflow (clj job + cljs job), `:refer :all` fixes across test namespaces, shared `interop-test-cases` helper, allowlist containing only `atproto.runtime.string-test` (expected to fail ⇒ ship with the allowlist empty and the string test added in M2, so the build is green). JVM suite must stay green.
2. **datetime + string cljs branches** — implement `parse`/`->string`/`current-time-millis` plus the `:cljs` bodies of WS-07's `normalize`/`normalize-or-epoch`/`current-datetime` (replacing its NotImplemented stubs; if WS-07 hasn't merged yet, land the parse family first and the normalize family in a follow-up commit once the signatures are frozen), vendor the two extra datetime fixture files, add `datetime_test.cljc` (incl. the both-platform normalize assertions), extend `string_test.cljc`, enable both in the cljs allowlist.
3. **http runtime on fetch + byte-type standardization** — `url-encode/decode`, `parse-url`, specs, fetch-based `handle-request` with timeout/abort (`{:error "Timeout"}`/`{:error "Aborted"}` per WS-07's frozen semantics)/error/binary handling; land the `runtime.bytes/bytes?` `js/Uint8Array` change (via WS-02's PR if in flight, else directly — see File ownership); `http_test.cljc`; enable in allowlist. Verify `identity`-namespace compile on cljs and enable `identity_test` if clean.
4. **DNS-over-HTTPS** — `doh-interceptor` + platform `interceptor` + endpoint config; `dns_test.cljc`; integration-tagged live resolution test; browser handle-resolution story documented in the namespace docstring.
5. **runtime.ws `:cljs` branch + jetstream port** — *blocks on WS-05*: add the `js/WebSocket` implementation to WS-05's `src/atproto/runtime/ws.cljc` (replacing its NotImplemented cljs stub); after WS-05's jetstream rework lands, `git mv` `jetstream.clj` → `jetstream.cljc` (API-preserving) and make the remaining internals cross-platform; integration-tagged jetstream test on both platforms; JVM example in `README` comment block re-verified.
6. **Audit & README** — rebase over landed WS-02/03/05/07/09, expand cljs allowlist to every now-clean namespace, run the live-service checklist, correct the README matrix, final acceptance sweep.

Each milestone is an independently mergeable PR that leaves both CI jobs green.

## Risks & open questions

- **Datetime approach (decision needed, recommendation: hand-rolled regex + `js/Date`)** — js-joda would give OffsetDateTime-equivalent semantics but adds a large dependency for what is, in this SDK, a validity predicate plus a canonical formatter. The TS reference itself uses regex + `new Date` (`packages/syntax/src/datetime.ts:317-346`). Recommended: port that, including fraction-trimming to 3 digits before `js/Date` construction so we do not depend on engines' lenient long-fraction parsing (the 12-digit-fraction fixtures must pass). Residual risk: `js/Date` semantic checks (e.g. rejecting Feb 30) are engine behavior, not spec-guaranteed for all inputs — the interop fixture suite is the guard.
- **Grapheme fallback policy (decision needed, recommendation: code-point fallback + one-time `cast/alert`)** — `Intl.Segmenter` is available in Node ≥16, all evergreen browsers (Firefox since 125, 2024-04). Hard-failing when absent would break all lexicon validation on legacy engines; silently wrong counts are worse than loudly degraded ones. The TS reference ships an npm ponyfill (`unicode-segmenter`), which we cannot cleanly depend on for non-shadow cljs consumers — porting it is out of budget. Revisit if a user reports a real environment without Segmenter.
- **`utf8-length`: ported loop vs `TextEncoder`** — recommendation: port the `utf8LenCompute` loop (`lex-data/src/utf8-len.ts:13-51`, allocation-free, no globals) and cross-check against `TextEncoder` in tests. `TextEncoder`-only would also be acceptable.
- **DoH default endpoint & privacy** — defaulting cljs to Cloudflare leaks queried handles to a third party (the TS browser client makes this an explicit, mandatory choice — see `oauth-client-browser/README.md:95-104` caution). Recommendation: keep the Cloudflare default for out-of-the-box utility, document the leak prominently in the `dns` namespace docstring and README, and support `set-default-doh-endpoint!`/`:doh-endpoint` overrides including `nil` to disable. Open question for the orchestrator: default-on vs default-nil (require explicit opt-in, exactly like TS).
- **Browser `.well-known` fallback is CORS-bound** — most PDS hosts do not send CORS headers on `/.well-known/atproto-did`, so in browsers the HTTPS method usually fails even for valid handles; DoH is the reliable browser path. An `XrpcHandleResolver` equivalent (`com.atproto.identity.resolveHandle` against a configured service, per `internal/handle-resolver/src/xrpc-handle-resolver.ts`) is the other TS-blessed option — documented here, implementation deferred (open question: include in M4 if cheap).
- **`url-encode` platform divergence** — clj `URLEncoder/encode` emits `+` for space (form encoding, and is also deprecated-without-charset as called at `http.cljc:42`); `js/encodeURIComponent` emits `%20`. Recommendation: normalize both platforms to RFC 3986 (`URLEncoder` + `"+"`→`"%20"` post-fix on clj) inside this WS since it lands the final `http.cljc` rewrite (WS-07's interim cljs change doesn't touch `url-encode`); flag in the PR description since it changes clj-emitted URLs (OAuth PAR bodies built elsewhere are unaffected — verified only query strings use it).
- **`parse-url` normalization differences** — `js/URL` normalizes empty path to `/` and lower-cases hosts; tests pin behavior for the URL shapes the SDK actually builds, and the doc accepts cosmetic divergence elsewhere.
- **Node version floor** — global `WebSocket` requires Node ≥22 (LTS since 2024-10); `fetch` requires ≥18. Recommendation: document Node ≥22 for cljs usage and run CI on 22; older Node users can set `js/globalThis.WebSocket` from the `ws` package themselves.
- **Does `lexicon.cljc` compile under shadow-cljs today?** — `register-specs!`'s `eval` (`lexicon.cljc:713`) is an undeclared-var *warning* in standard cljs compilation, not necessarily an error; if shadow's default `:warnings-as-errors` config or the `io/resource` conditionals still block compilation, milestone 3's `identity_test` enablement slips to milestone 6 (post-WS-09). Determine empirically in M1; the milestone plan is valid either way.
- **`mvxcvi/multiformats` cljs support** — `data.cljc` requires `multiformats.cid` unconditionally; the library advertises cross-platform support but this is unverified in this repo. If it breaks cljs compilation of `data.cljc` (and transitively `lexicon`/`identity`), that finding belongs to WS-02 — report, don't fix, but it gates the same allowlist entries as WS-09.
- **Jetstream rename churn** — `jetstream.clj` → `jetstream.cljc` preserves the namespace name, so downstream `:require`s are unaffected (in-repo consumer: `examples/statusphere/src/xyz/statusphere/ingester.clj:6` requires `atproto.jetstream` by namespace and keeps working); only tooling referencing the file path could notice. Run the statusphere example's ingester manually in M5 to confirm no JVM behavior change.
