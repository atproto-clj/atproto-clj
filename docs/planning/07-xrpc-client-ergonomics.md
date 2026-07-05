# WS-07: XRPC Client Ergonomics & API Conveniences

| | |
|---|---|
| **Status** | Implemented (milestones 1–7; CLJS datetime/http bodies deferred to WS-10 as planned) |
| **Priority** | P1 |
| **Estimated size** | L |
| **Branch** | ws/07-xrpc-client-ergonomics |
| **Depends on** | WS-01 (xrpc client refactor — lands first on `src/atproto/xrpc/client.cljc`; this WS rebases and consumes whatever Session/auth contract WS-01 freezes). WS-10 (cljs runtime parity — owns the `:cljs` branch of `src/atproto/runtime/datetime.cljc`; this WS defines the datetime helper API and ships the `:clj` implementations). No dependency on WS-02 (CBOR) — all bodies here are JSON or opaque binary. |
| **Blocks** | Any workstream or example app that wants ergonomic repo CRUD, typed XRPC errors, retries, AT-URI manipulation, or TID parse/compare (e.g. firehose/repo workstreams that need TID ordering, and the top-level `atproto.client` docs). |

## Goal

When this workstream is done, the XRPC client returns a typed, predictable error taxonomy (`{:error :message :status :headers :retryable?}`) instead of ad-hoc `HTTP_<status>` strings; supports per-request/per-client headers, service proxying (`atproto-proxy`, `atproto-accept-labelers`), timeouts, opt-in retry with exponential backoff, and cooperative cancellation. A new `atproto.client.repo` namespace provides createRecord/getRecord/putRecord/deleteRecord/listRecords/applyWrites/uploadBlob conveniences with optimistic-concurrency params and cursor pagination. New/extended utility namespaces give AT-URI construct/parse/accessors, TID parse/compare with the 13-char zero-padding fix, and datetime normalize/current-datetime helpers matching the TypeScript reference behavior.

## Current state

All paths relative to repo root `/Users/luke/github/atproto-clj/atproto-clj`.

### XRPC client (`src/atproto/xrpc/client.cljc`)

- TODO list at `src/atproto/xrpc/client.cljc:11-16` explicitly names this workstream's gaps: error handling, timeout, retry, cursor pagination, bubbling server errors.
- `init` (`:18-25`) accepts only `:service`, `:session`, `:validate-requests?`. No `:headers`, no proxy/labeler config, no timeout/retry defaults.
- `request-validator` (`:27-36`) validates the request map against `lexicon/request-spec-key` (`src/atproto/lexicon.cljc:768-772`). The generated request specs are open `s/keys` (`src/atproto/lexicon.cljc:492-505`), so adding new request keys (`:headers`, `:timeout`, `:signal`) does NOT break validation. Note it **throws** `ex-info` instead of returning an error map — inconsistent with the rest of the SDK; this WS normalizes it to an error map.
- `Session` protocol + `delegate-auth-interceptor` (`:38-67`) handle auth/refresh. `expired-token-error?` (`:43-46`) does an exact `=` match on `"application/json"` content-type, which fails for `application/json; charset=utf-8` — flag to WS-01 (which owns auth) but do not fix here unless still broken after rebase.
- `procedure-interceptor` (`:93-109`) builds the HTTP request and **constructs `:headers` itself** at `:107` (`:headers {:content-type encoding}`), discarding any caller-provided headers. `query-interceptor` (`:122-133`) sets no headers at all. Verified by grep: zero occurrences of `atproto-proxy` or `atproto-accept-labelers` anywhere in `src/`.
- `handle-xrpc-response` (`:85-91`): success returns bare `:body` (response headers are dropped — rate-limit headers are unobservable); a non-2xx with `:error` in body returns the body as-is (no `:status`, no `:headers`, no `:retryable?`); otherwise falls through to `http/error-map` (`src/atproto/runtime/http.cljc:33-37`) which produces `{:error "HTTP_<status>" :http-response resp}`.
- No pagination, no retries, no cancellation, no timeouts (see runtime below).

### Runtime

- `src/atproto/runtime/interceptor.cljc`: callback-based chain; `execute` (`:157-169`) `select-keys`s only `:channel :callback :promise` from opts. Error convention is documented at `:42-43`: response maps with `:error` + `:message`. No abort/termination mechanism.
- `src/atproto/runtime/http.cljc`: `handle-request` (`:124-167`). CLJ passes the request map straight to http-kit (`:128`), so a `:timeout` key already flows through (http-kit supports `:timeout` in ms); identity ns already exploits this (`src/atproto/identity.cljc:147`). CLJS hardcodes `(.setTimeoutInterval 0)` (`:142`) — per-request timeout is silently ignored. Network failures on CLJ become `{:error "HTTPClientError" :message ... :ex ...}` (`:131-135`). Binary request bodies: CLJ http-kit accepts `byte[]`/`InputStream`/`File` as `:body`; CLJS passes `body` to `XhrIo.send` untouched (`:163-167`), which accepts `ArrayBuffer`/`ArrayBufferView`/`Blob`/string. The JSON interceptors only touch bodies when content-type is JSON (`src/atproto/runtime/json.cljc:30-43`, `src/atproto/data/json.cljc:96-112`), so binary bodies pass through cleanly — **uploadBlob needs no http-runtime change for requests**. (CLJS response body via `XhrIo.getResponse` without `responseType` set returns text; binary *response* bodies (getBlob) are out of scope.)
- No cancellation anywhere: nothing aborts an XhrIo, and the chain has no "stop" signal.

### TID (`src/atproto/tid.cljc`, 45 lines)

- `s32-encode` (`:17-24`) produces a minimal-length string; `next-tid` (`:41-45`) concatenates `(s32-encode (* 1000 (monotime-ms)))` with a clock-id padded to 2 chars. **The timestamp segment is not zero-padded to 11 chars**, so any timestamp < 32^10 microseconds produces a TID shorter than 13 chars (invalid per spec and per `src/atproto/lexicon/regex.cljc:21-22`). Current wall-clock times happen to encode to exactly 11 chars, which is why this latent bug hasn't bitten.
- No `parse`, no timestamp accessor, no comparison helpers, no `from-time`, no validity predicate, no `s32-decode`.
- `clock-id` (`:39`) is `(floor (* 1024 (random)))` — a `double`, 10 bits (spec-compliant; TS uses 5 bits, see reference). Coerce to `long`.
- `next-tid` does not accept a `prev` TID to guarantee strict ordering relative to a caller-supplied value (TS `TID.next(prev)` does).

### AT-URI

- Validation-only: `s/def ::at-uri` conformer at `src/atproto/lexicon.cljc:85-95` (conforms to `{:authority :collection :rkey}` but is a spec, not an API), backed by the regex at `src/atproto/lexicon/regex.cljc:15-16` (named groups `authority`, `collection`, `rkey`, `fragment`). There is no public construct/parse/accessor namespace.

### Datetime (`src/atproto/runtime/datetime.cljc`, 30 lines)

- CLJ-only (`parse` at `:22-26` returns `nil` on CLJS; `current-time-millis` at `:28-30` has no `:cljs` branch — WS-10 owns adding it). No normalize, no current-datetime string helper. `trim-fraction` (`:15-20`) exists for >9-digit fractional seconds.

### Top-level client (`src/atproto/client.cljc`)

- `procedure`/`query` (`:70-88`) are thin delegations; docstrings enumerate request keys and must be updated when `:headers`/`:timeout`/`:signal` are added.

### Tests

- Interop syntax fixtures are **already vendored** at `test/interop-test-files/syntax/` (tid, aturi, datetime, etc.) and consumed via the `interop-test-cases` macro in `test/atproto/lexicon_test.cljc:15-25`. There are no tests today for `atproto.tid`, no AT-URI API tests, no xrpc client tests.

## Reference implementation guide

All paths relative to `/Users/luke/github/bluesky-social/atproto`.

### Error taxonomy & retryability

- `packages/xrpc/src/types.ts` — legacy client model: `ResponseType` enum (`:26-49`), `httpResponseCodeToEnum` status bucketing (`:51-65`: 1xx/3xx→XRPCNotSupported(404), unknown 4xx→400, unknown 5xx→500), `XRPCError` with `status`/`error`/`message`/`headers` (`:103-163`).
- `packages/lex/lex-client/src/errors.ts` — the modern model to mirror: `StatusErrorCodes` map status→error-name (`:29-43`); **`RETRYABLE_HTTP_STATUS_CODES = #{408 425 429 500 502 503 504 522 524}`** (`:68-70`); `XrpcResponseError` derives `error`/`message` from a valid JSON error body, else from status (fallback: `>=500 → "UpstreamFailure"`, else `"InvalidRequest"`) (`:193-216`), `shouldRetry` = status in retryable set (`:214-216`); `XrpcAuthenticationError` (401) never retries (`:284-291`); `XrpcInvalidResponseError` for schema-invalid/garbage responses (`:322-348`); `XrpcInternalError` never retries (`:391-411`); `XrpcFetchError` (network/timeout) **always retries optimistically** (`:433-449`). Tests: `packages/lex/lex-client/src/errors.test.ts`.
- `packages/lex/lex-client/src/www-authenticate.ts` — WWW-Authenticate parsing; port only if cheap (optional).

### Headers, service proxy, labelers

- `packages/api/src/agent.ts:117-138` — the fetch-handler wrapper that sets `atproto-proxy` (only if not already set per-request) and merges `atproto-accept-labelers` from `appLabelers.map(l => l + ";redact")`, instance labelers, and any per-request value, joined with `", "`. `withProxy` (`:157-161`) clones with `"did#service_id"`; `configureLabelers`/`configureProxy` (`:174-201`).
- `packages/lex/lex-client/src/util.ts:97-116` — `buildXrpcRequestHeaders`: same logic, standalone function (the cleanest thing to port).
- `packages/lex/lex-client/src/types.ts:11-23` — `Service` format: `"<did>#<service-identifier>"`, e.g. `"did:web:api.bsky.app#bsky_appview"`, labeler fragment `atproto_labeler`.
- Precedence: per-request headers override client defaults (`packages/lex/lex-client/src/agent.ts:169-186`, `packages/lex/lex-client/src/client.ts:501-523`). Caller-supplied `content-type` is rejected with an error (`packages/lex/lex-client/src/xrpc.ts:263-267`); the client also sets `accept` from the method's output encoding (`:258-261`).

### Retry & timeouts

- `packages/common-web/src/retry.ts` — `retry` loop (`:8-35`): default `maxRetries = 3`, retry while `retries < maxRetries && waitMs !== null && retryable(err)`; `backoffMs` (`:43-47`): `min(2^n * 100, 1000)` with ±15% jitter (`:50-53`). Tests: `packages/common-web/tests/retry.test.ts`.
- Timeouts in TS land are `AbortSignal`-based; we map to http-kit `:timeout` (CLJ) and `XhrIo.setTimeoutInterval` (CLJS).

### Cancellation

- TS uses `AbortSignal` threaded into fetch (`packages/lex/lex-client/src/xrpc.ts:216`, `:285`, `:298`). No interceptor analog exists in our SDK; design below is cooperative.

### Repo CRUD conveniences

- `packages/lex/lex-client/src/client.ts` — untyped convenience layer (what we port, minus codegen typing): `createRecord` (`:629-645`, collection defaults to record `$type`, repo defaults to authenticated DID, `rkey` optional → **server generates the TID when omitted**, passes `validate`/`swapCommit`); `deleteRecord` (`:656-671`, `swapCommit`/`swapRecord`); `getRecord` (`:682-695`); `putRecord` (`:706-723`, `swapCommit`/`swapRecord`/`validate`); `listRecords` (`:733-744`, `limit`/`cursor`/`reverse`); `applyWrites` (`:769-782`); `uploadBlob` (`:800-802`); `getBlob` (`:811-816`). Typed `create` resolves default rkey via `getDefaultRecordKey` (`packages/lex/lex-client/src/util.ts:162-170`): `tid`/`any` key types → `undefined` (server-side generation); `literal:*` → the literal.
- `packages/lex/lex-client/src/write-operation-builder.ts` — applyWrites op builder (`com.atproto.repo.applyWrites#create/#update/#delete` union with `$type` tags). We accept plain maps instead of a builder.
- Binary body handling: `packages/lex/lex-client/src/xrpc.ts:309-360` (`xrpcProcedureInput`) and `BinaryBodyInit` (`packages/lex/lex-client/src/types.ts:50-56`).

### TID

- `packages/common-web/src/tid.ts` — `next(prev?)` (`:24-44`): monotonic via `max(Date.now(), lastTimestamp)` + same-ms counter, and if `prev` is newer, returns `fromTime(prev.timestamp() + 1, clockid)`; `fromTime` (`:50-54`) — note TS pads only the clockid, not the timestamp (same latent bug; our fix pads timestamp to 11); `timestamp()` = `s32decode(str.slice(0,11))` (`:72-74`); `clockid()` (`:76-78`); lexicographic `compareTo`/`newerThan`/`olderThan` (`:93-109`). `s32encode`/`s32decode` at `packages/common-web/src/util.ts:115-131`. Tests to port: `packages/common-web/tests/tid.test.ts` (round-trip parse, next > prev with future prev, sort order) and `packages/syntax/tests/tid.test.ts` (interop fixture driven).

### AT-URI

- `packages/syntax/src/aturi.ts` — `ATP_URI_REGEX` (`:22-24`), `AtUri` class (`:28-156`): `make(handleOrDid, collection?, rkey?)` (`:50-55`), accessors `host`/`collection`/`rkey`/`hash`, `toString` normalization (strip trailing `/`, prefix `at://`) (`:135-155`). Note this class is *permissive* (accepts URIs the strict `aturi_validation.ts` rejects); our version should validate segments with the existing lexicon specs instead. Tests: `packages/syntax/tests/aturi.test.ts`.

### Datetime

- `packages/syntax/src/datetime.ts` — `currentDatetimeString` (`:174-176`), `toDatetimeString` (`:187-189`), `normalizeDatetime` (`:209-255`: if tz designator present parse as-is, else try `"<s>Z"`, then `"<s> UTC"`, then as-is; output is always UTC millisecond-precision ISO `...sssZ`), `normalizeDatetimeAlways` → epoch fallback (`:266-272`), strict `DATETIME_REGEX` (`:317-318`) and year-range checks (`:353-369`). Tests: `packages/syntax/tests/datetime.test.ts` (fixture-driven, including `datetime_parse_invalid.txt` cases that are *syntactically* valid but must fail/normalize specially).

### Interop fixtures

- Source of truth: `/Users/luke/github/bluesky-social/atproto/interop-test-files/syntax/` — `tid_syntax_valid.txt`, `tid_syntax_invalid.txt`, `aturi_syntax_valid.txt`, `aturi_syntax_invalid.txt`, `datetime_syntax_valid.txt`, `datetime_syntax_invalid.txt`, `datetime_parse_invalid.txt`. All seven are already vendored at `test/interop-test-files/syntax/`; re-diff against upstream before relying on them (cheap: `diff -r`).

## Scope

### In scope

- **Error taxonomy** (new `atproto.xrpc.error` ns + wiring in `atproto.xrpc.client`):
  - status→error-name table mirroring `StatusErrorCodes` (400 InvalidRequest, 401 AuthenticationRequired, 403 Forbidden, 404 XRPCNotSupported, 406 NotAcceptable, 413 PayloadTooLarge, 415 UnsupportedMediaType, 429 RateLimitExceeded, 500 InternalServerError, 501 MethodNotImplemented, 502 UpstreamFailure, 503 NotEnoughResources, 504 UpstreamTimeout; unknown ≥500 → UpstreamFailure, other 4xx → InvalidRequest, 1xx/3xx → XRPCNotSupported).
  - error maps gain `:status`, `:headers`, `:retryable?` while preserving `:error`/`:message`; server-provided `:error` from a JSON body always wins over the derived name; raw response kept under `:http-response`.
  - retryable set `#{408 425 429 500 502 503 504 522 524}`; 401 never retryable; network errors (`HTTPClientError`, timeout, aborted XHR) retryable.
  - lexicon request-validation failure returns `{:error "InvalidRequest" :message ... :explain-data ...}` instead of throwing (normalize `request-validator`).
  - success responses keep returning the body, with response headers attached as metadata (`{::xrpc/headers ...}`) so rate-limit info is observable without breaking callers.
- **Header passthrough**: `:headers` map in `init` config (client-wide defaults) and in each `query`/`procedure` request map; merge order: computed (content-type/auth) > per-request > client defaults; reject caller-supplied `:content-type` on procedures with a body (error map, mirroring TS).
- **Service proxying**: `:service-proxy` and `:labelers` in `init`; helpers `with-service-proxy` / `with-labelers`; emit `atproto-proxy` (per-request header wins) and `atproto-accept-labelers` (merged, `;redact` suffix supported, joined `", "`); spec for the `"did#service_id"` format.
- **Timeouts**: `:timeout` (ms) per client and per request; CLJ flows through to http-kit (already works); CLJS: change `src/atproto/runtime/http.cljc:142` to use the request's `:timeout` and map XhrIo timeout to a retryable `{:error "Timeout"}`.
- **Retry**: new `atproto.runtime.retry` cljc ns with callback-based `with-retry` + `backoff-ms` (2^n×100 capped at 1000, ±15% jitter); `:max-retries` option on client init and per request; **default 0 (off)** — opt-in; only retries error maps where `:retryable?` is true.
- **Cancellation**: `abort-signal`/`abort!` API; signal checked before each retry attempt and at interceptor-chain entry/leave via a signal-checking interceptor; CLJS best-effort `XhrIo.abort()`; CLJ documents cooperative semantics (http-kit cannot abort an in-flight request; the result is discarded and the caller receives `{:error "Aborted"}` exactly once).
- **Pagination**: generic `fetch-pages`/`fetch-all` cursor helper on the xrpc client (async, all platforms, with `:max-pages` guard); CLJ-only lazy `page-seq`/`record-seq` (blocking deref per page, documented).
- **Repo conveniences** (new `atproto.client.repo` ns — *not* `atproto.repo`, which WS-04 owns for the MST/commit repository layer): `create-record`, `get-record`, `put-record`, `delete-record`, `list-records`, `list-all-records`, `record-seq` (clj), `apply-writes`, `upload-blob` — with `:repo` defaulting to the session DID, `:collection` defaulting to record `:$type`, `swap-commit`/`swap-record` optimistic-concurrency params, server-side `:validate?` passthrough, and optional client-side lexicon validation of records.
- **AT-URI** (new `atproto.at-uri` ns): `parse`, `make`/`format`, `valid?`, accessors (`authority`, `collection`, `rkey`, `fragment`), reusing `atproto.lexicon.regex/at-uri` and lexicon segment specs.
- **TID** (`atproto.tid`): zero-pad timestamp segment to 11 chars (13-char invariant), `s32-decode`, `parse`, `timestamp`/`clock-id` accessors, `from-time`, `compare-tids`/`newer?`/`older?`, `valid?`, `next-tid` arity accepting `prev`, coerce clock-id to long.
- **Datetime helpers** (`atproto.runtime.datetime`): `normalize`, `normalize-or-epoch`, `current-datetime` (CLJ implementations; API shape fixed cross-platform, CLJS bodies left for WS-10).
- Docstring updates in `src/atproto/client.cljc` for new request keys, plus thin re-exports there if WS-01's final shape encourages it.

### Out of scope

- **Typed app.bsky helpers, RichText, moderation, post/social-graph/label/preferences helpers** — explicitly not planned for this SDK (`README.md:43`).
- **Lexicon codegen / typed method wrappers** (the `lex-cli`-generated typed client surface) — not planned (N/A row in `README.md:26`).
- **Auth/session/refresh changes** including the `expired-token-error?` content-type bug — WS-01 owns `Session`/auth in `src/atproto/xrpc/client.cljc`; we only rebase around it.
- **Response lexicon validation** (TS `XRPCInvalidResponseError`) — leave a seam in the error taxonomy (`"InvalidResponse"` name reserved) but implementing response validation belongs with the lexicon workstream that owns `response-spec-key` consumers.
- **CLJS branch of `atproto.runtime.datetime`** (parse/current-time-millis/normalize on JS) — WS-10. This WS must not write `:cljs` reader conditionals in that file beyond stubs agreed with WS-10.
- **Binary response bodies / `com.atproto.sync.getBlob`** — requires `:as :byte-array`-style support in the http runtime response path on both platforms; defer (note as open question).
- **OAuth DPoP-aware retry of `use_dpop_nonce`** — `src/atproto/oauth/client/dpop.cljc` already exists; touching it is WS-01/OAuth territory.
- **Jetstream/streaming cancellation** — only the request/response interceptor chain is in scope.

## Deliverables

### 1. `atproto.xrpc.error` (new, cljc)

```clojure
(ns atproto.xrpc.error
  "Typed error taxonomy for XRPC responses, mirroring @atproto/lex-client errors.")

(def status->error-name
  "HTTP status → canonical XRPC error name (used when the response body
  does not carry a valid {\"error\": ...} payload)."
  {400 "InvalidRequest" 401 "AuthenticationRequired" 403 "Forbidden"
   404 "XRPCNotSupported" 406 "NotAcceptable" 413 "PayloadTooLarge"
   415 "UnsupportedMediaType" 429 "RateLimitExceeded" 500 "InternalServerError"
   501 "MethodNotImplemented" 502 "UpstreamFailure" 503 "NotEnoughResources"
   504 "UpstreamTimeout"})

(def retryable-statuses #{408 425 429 500 502 503 504 522 524})

(defn http-error
  "Build an error map from a non-2xx XRPC HTTP response.

  Returns {:error <name> :message <str> :status <int> :headers <map>
           :retryable? <bool> :http-response <raw>}.
  - :error  is the body's :error if the body is a JSON error payload,
            else (status->error-name status), else \"UpstreamFailure\"/\"InvalidRequest\".
  - :retryable? is (and (not= 401 status) (contains? retryable-statuses status))."
  [{:keys [status headers body] :as http-response}])

(defn network-error
  "Wrap a runtime transport failure ({:error \"HTTPClientError\"/\"Timeout\"/...})
  as a retryable XRPC error map (no :status)."
  [err])

(defn invalid-request
  "Error map for client-side request validation failure (never retryable).
  {:error \"InvalidRequest\" :message ... :explain-data ...}"
  [explain-data])

(defn retryable? [error-map] "True if :retryable? is set." ...)
```

### 2. `atproto.runtime.retry` (new, cljc)

```clojure
(ns atproto.runtime.retry
  "Callback-based retry with exponential backoff (ports common-web/retry.ts).")

(defn backoff-ms
  "Exponential backoff with jitter: min(2^n * multiplier, max) ± 15%.
  Defaults multiplier=100, max=1000."
  [n & {:keys [multiplier max]}])

(defn schedule
  "Platform-appropriate delayed invocation of zero-arg `f` after `ms`.
  CLJ: future + Thread/sleep (or a shared ScheduledExecutorService).
  CLJS: js/setTimeout."
  [ms f])

(defn with-retry
  "Run async op `f`, a fn of one arg (a callback receiving a result map).
  If the result has (pred result) truthy and attempts remain, re-run after
  backoff; otherwise deliver the result to `cb`.

  opts: :max-retries (default 3 when invoked; callers gate on >0)
        :retryable?  (default atproto.xrpc.error/retryable?)
        :backoff-ms  (fn [attempt] ms; default backoff-ms)
        :signal      (optional abort signal; checked before each attempt,
                      delivering {:error \"Aborted\"} if set)"
  [f opts cb])
```

### 3. `atproto.xrpc.client` (modified — REBASE ON WS-01 FIRST)

```clojure
(defn init
  "New config keys (all optional, in addition to WS-01's):
  :headers        map of default headers for every request (lowercase keyword keys)
  :service-proxy  \"<did>#<service-id>\" → atproto-proxy header
  :labelers       coll of labeler DIDs, or {:did ... :redact? true} maps
                  → atproto-accept-labelers header
  :timeout        default per-request timeout in ms
  :max-retries    default retry count for retryable errors (default 0 = off)"
  [config])

(defn with-service-proxy
  "Return a client that routes via the given service: \"did:web:api.bsky.app#bsky_appview\"."
  [client service])

(defn with-labelers [client labelers])

(defn abort-signal
  "Create a cancellation signal: {::aborted? (atom false) ::listeners (atom [])}."
  [])

(defn abort!
  "Trip the signal. Pending xrpc calls deliver {:error \"Aborted\"} and,
  where the platform supports it (CLJS XhrIo), abort the in-flight request.
  On CLJ the underlying http-kit request cannot be interrupted; its eventual
  result is discarded."
  [signal])

;; query/procedure request maps accept new keys:
;;   :headers  per-request headers (override client defaults; computed
;;             content-type/authorization win; supplying :content-type with a
;;             body is an error)
;;   :timeout  ms
;;   :signal   abort signal
;;   :max-retries  override client default
(defn query [client request & {:as opts}])
(defn procedure [client request & {:as opts}])

(defn fetch-pages
  "Cursor pagination driver. Repeatedly calls `query` with :cursor threaded
  from each response, invoking (step-fn acc page) per page (reduced short-
  circuits). Stops when no cursor, items empty, :max-pages reached, or error.
  Async like everything else; the deferred value is the final acc or an
  error map.

  opts: :items-fn (default :records), :cursor-fn (default :cursor),
        :max-pages, plus :channel/:callback/:promise."
  [client request step-fn init-acc & {:as opts}])

(defn fetch-all
  "fetch-pages collecting (items-fn page) into a single vector."
  [client request & {:as opts}])

#?(:clj
   (defn page-seq
     "Lazy seq of page bodies; each step blocks on the underlying promise.
     CLJ-only convenience; do not use on event-loop threads."
     [client request & {:as opts}]))
```

Behavioral changes inside the chain:

- `handle-xrpc-response` uses `atproto.xrpc.error/http-error` for non-2xx, `network-error` for transport `:error`s, and attaches `{:atproto.xrpc.client/headers headers}` metadata to successful map bodies.
- header-merging happens in `procedure-interceptor`/`query-interceptor` `::i/enter` (client defaults → proxy/labeler headers → per-request `:headers` → computed `content-type`), then auth interceptors add `:authorization` via `assoc-in` as today (`src/atproto/credentials.cljc:63-66`).
- retry wraps the *whole* `i/execute` invocation (`with-retry (fn [cb] (i/execute ctx :callback cb)) ...`) rather than living inside the chain, so auth refresh still works per attempt.
- a `signal-interceptor` is prepended when `:signal` is present; it checks the signal in `::i/enter`/`::i/leave` and replaces the response with `{:error "Aborted" :message "Request aborted"}` (the http callback result is then ignored by the `final` interceptor delivering only once — guard with an atom).

### 4. `atproto.client.repo` (new, cljc)

All functions follow the SDK async convention (`& {:as opts}` with `:channel`/`:callback`/`:promise`, returning a platform deferred) and return error maps on failure. `client` is an `atproto.client`/xrpc client; `:repo` defaults to `(atproto.client/did client)` and it is an error (`{:error "NotAuthenticated"}`) if neither is available. All also accept `:headers`/`:timeout`/`:signal`/`:max-retries` passthrough.

```clojure
(ns atproto.client.repo
  "Convenience wrappers over com.atproto.repo.* XRPC methods.
  (Named atproto.client.repo, not atproto.repo: WS-04 owns atproto.repo
  for the MST/commit repository layer, mirroring @atproto/repo.)")

(defn create-record
  "com.atproto.repo.createRecord.
  m: :record (required, atproto data map; :collection defaults to its :$type)
     :repo :collection :rkey (optional; omitted → server generates a TID)
     :validate? (tri-state: true/false/nil → server default)
     :swap-commit (CID string)
  Client-side: if the client has :validate-requests? and the record's lexicon
  is loaded, validate the record before sending.
  Result body: {:uri ... :cid ...} (uri usable with atproto.at-uri/parse)."
  [client m & {:as opts}])

(defn get-record
  "com.atproto.repo.getRecord. m: :collection :rkey required; :repo, :cid optional.
  Result: {:uri ... :cid ... :value {...}}."
  [client m & {:as opts}])

(defn put-record
  "com.atproto.repo.putRecord. m: :record :rkey required; :repo :collection
  :validate? :swap-commit :swap-record optional."
  [client m & {:as opts}])

(defn delete-record
  "com.atproto.repo.deleteRecord. m: :collection :rkey required;
  :repo :swap-commit :swap-record optional."
  [client m & {:as opts}])

(defn list-records
  "com.atproto.repo.listRecords — single page.
  m: :collection required; :repo :limit :cursor :reverse optional.
  Result: {:records [{:uri :cid :value} ...] :cursor ...}."
  [client m & {:as opts}])

(defn list-all-records
  "All pages via atproto.xrpc.client/fetch-all (guard with :max-pages)."
  [client m & {:as opts}])

#?(:clj
   (defn record-seq
     "Lazy, blocking seq of records across pages (CLJ only)."
     [client m & {:as opts}]))

(defn apply-writes
  "com.atproto.repo.applyWrites.
  m: :writes — vector of plain maps:
       {:type :create :collection nsid :rkey (optional) :value record}
       {:type :update :collection nsid :rkey rkey :value record}
       {:type :delete :collection nsid :rkey rkey}
     (translated to $type-tagged com.atproto.repo.applyWrites#create/update/delete)
     :repo :validate? :swap-commit optional."
  [client m & {:as opts}])

(defn upload-blob
  "com.atproto.repo.uploadBlob.
  `blob` is platform bytes (CLJ: byte[]/InputStream; CLJS: js/Blob,
  js/ArrayBuffer, js/Uint8Array). `encoding` is the MIME type (required on
  CLJ; on CLJS defaults to a js/Blob's .-type, else required).
  Result body: {:blob {:$type \"blob\" :ref ... :mimeType ... :size ...}}
  (already decoded to atproto data by the data.json interceptor)."
  [client blob encoding & {:as opts}])
```

### 5. `atproto.at-uri` (new, cljc)

```clojure
(ns atproto.at-uri
  "Construct, parse, and access at:// URIs. Validation reuses atproto.lexicon specs.")

(defn parse
  "Parse an at:// URI string into
  {:authority <did-or-handle> :collection <nsid>? :rkey <str>? :fragment <str>?}.
  Returns nil if invalid (strict: authority/collection/rkey are each validated)."
  [s])

(defn valid? [s])

(defn make
  "Build a canonical at:// URI string from parts. Validates each part;
  returns {:error \"InvalidAtUri\" :message ...} on bad input.
  (make authority) (make authority collection) (make authority collection rkey)
  or (make {:authority a :collection c :rkey r})."
  [authority & [collection rkey]])

(defn authority  [s-or-parsed])
(defn collection [s-or-parsed])
(defn rkey       [s-or-parsed])
```

### 6. `atproto.tid` (modified)

```clojure
(def ^:const tid-length 13)

(defn s32-encode
  "Now public; optional pad-to length (left-pad with \\2)."
  ([n]) ([n pad-to]))

(defn s32-decode "Sort32 string → long." [s])

(defn valid? "13 chars matching atproto.lexicon.regex/tid." [s])

(defn parse
  "TID string → {:timestamp <epoch micros, long> :clock-id <long>}; nil if invalid."
  [s])

(defn timestamp "Epoch microseconds of the TID (long)." [tid])

(defn from-time
  "Build a TID string from epoch-microseconds + clock-id.
  Timestamp segment zero-padded to 11 chars (fixes the <13-char bug);
  clock-id padded to 2."
  [micros clock-id])

(defn compare-tids
  "Lexicographic comparator (= chronological once padded). Negative if a older."
  [a b])

(defn newer? [a b])
(defn older? [a b])

(defn next-tid
  "Monotonically increasing TID. With `prev`, guaranteed > prev
  (falls back to prev's timestamp + 1, as in common-web TID.next)."
  ([]) ([prev]))
```

`next-tid` must route through `from-time` so existing callers get the padding fix; coerce `clock-id` (`src/atproto/tid.cljc:39`) to `long`.

### 7. `atproto.runtime.datetime` (modified — coordinate with WS-10)

```clojure
(defn current-datetime
  "Now, as a canonical atproto datetime string (UTC, millisecond precision,
  trailing Z). Ports syntax/datetime.ts currentDatetimeString."
  [])

(defn normalize
  "Flexible datetime string → canonical UTC ISO-8601 with millisecond
  precision (\"...sssZ\"). Accepts missing-timezone inputs (interpreted as
  UTC). Returns {:error \"InvalidDatetime\" :message ...} when unparseable.
  Ports syntax/datetime.ts normalizeDatetime, incl. the year 0000-9999 bounds."
  [s])

(defn normalize-or-epoch
  "Like normalize but returns \"1970-01-01T00:00:00.000Z\" instead of an error."
  [s])
```

CLJ implementations in this WS; `:cljs` bodies stubbed to delegate to whatever WS-10 lands (agree on these exact signatures with WS-10 before merging — they are the contract).

### 8. `atproto.runtime.http` (modified)

- CLJS: `handle-request` uses `(.setTimeoutInterval xhr (or (:timeout http-request) 0))` and maps `goog.net.ErrorCode.TIMEOUT`/`ABORT` to `{:error "Timeout"}` / `{:error "Aborted"}`.
- Optional `:signal` in the request map: CLJS registers `#(.abort xhr)` as a signal listener; CLJ documents non-support (cooperative only).

## Interface contract

### Provided (frozen once Milestone 1-2 merge; other workstreams may code against):

1. **Error map shape** for all XRPC failures:
   `{:error <string name> :message <string>? :status <int>? :headers <map>? :retryable? <boolean> :http-response <map>?}` — `:error`/`:message` keys unchanged from today's convention (`src/atproto/runtime/interceptor.cljc:42-43`), additions are purely additive.
2. **`atproto.tid`**: `next-tid` (0/1-arity), `parse`, `timestamp`, `from-time`, `compare-tids`, `valid?` — TIDs produced are always exactly 13 chars.
3. **`atproto.at-uri`**: `parse`/`make`/`valid?`/accessors with the parsed-map shape above.
4. **`atproto.client.repo`** function names/arg maps as sketched (body shapes are whatever the server returns, decoded). The namespace name itself is part of the contract — `atproto.repo` is reserved for WS-04.
5. **`atproto.runtime.retry/with-retry`** callback contract (usable by identity resolution "productionize" work later, see `src/atproto/identity.cljc:19`).
6. **`atproto.runtime.datetime`**: `current-datetime`, `normalize`, `normalize-or-epoch` signatures (WS-10 implements `:cljs`).

### Consumed:

- **WS-01's revised `atproto.xrpc.client`** internals (Session protocol, interceptor ordering). Assumption: query/procedure remain "request-map in, body-or-error-map out" with the `i/execute` chain and `:callback/:promise/:channel` opts. **Development before WS-01 merges**: build `atproto.xrpc.error`, `atproto.runtime.retry`, `atproto.tid`, `atproto.at-uri`, `atproto.runtime.datetime` first (no overlap); develop the client wiring on a branch with integration via a stub client — `atproto.client.repo` should only call `atproto.client/procedure|query` + public xrpc fns, never chain internals, so a fake client (`{:keys [...]}` + functions returning canned promises) plus the vendored fixtures suffices for tests.
- **WS-10 datetime cljs branch**: we define signatures; on CLJS, `normalize`/`current-datetime` may return `{:error "NotImplemented"}` until WS-10 lands (tests gated with reader conditionals).

## File ownership

| File | Action | Conflicts |
|---|---|---|
| `src/atproto/xrpc/client.cljc` | modify | **WS-01 also modifies; WS-01 lands first, WS-07 rebases.** Land Milestone 4+ only after WS-01 merges. |
| `src/atproto/xrpc/error.cljc` | create | none |
| `src/atproto/runtime/retry.cljc` | create | none |
| `src/atproto/runtime/http.cljc` | modify (cljs timeout/abort only) | check WS-01/WS-10 plans before merging; changes are 10-15 lines in `handle-request` — rebase as needed |
| `src/atproto/runtime/interceptor.cljc` | modify (only if signal check needs `execute` support; prefer a plain interceptor and zero changes) | shared infra — keep diff minimal |
| `src/atproto/client/repo.cljc` | create | **naming agreement with WS-04**: WS-04 owns `atproto.repo` (`src/atproto/repo.cljc`, MST/commit layer, see 04-mst-repo-car.md File ownership); this WS's XRPC CRUD ns is therefore `atproto.client.repo` (00-overview §4.9 item 5). Confirm the decision with WS-04 before either workstream's affected milestone (WS-07 M7) lands. |
| `src/atproto/at_uri.cljc` | create | none |
| `src/atproto/tid.cljc` | modify | none known |
| `src/atproto/runtime/datetime.cljc` | modify (`:clj` additions + cross-platform fn shells) | **WS-10 owns the `:cljs` branch** — agree signatures, land `:clj` first |
| `src/atproto/client.cljc` | modify (docstrings, opts passthrough) | WS-01 may touch; trivial rebase |
| `test/atproto/tid_test.cljc` | create | none |
| `test/atproto/at_uri_test.cljc` | create | none |
| `test/atproto/client/repo_test.cljc` | create | none (`test/atproto/repo_test.cljc` belongs to WS-04) |
| `test/atproto/xrpc/client_test.cljc` | create | coordinate with WS-01 if it also adds one (merge files) |
| `test/atproto/xrpc/error_test.cljc` | create | none |
| `test/atproto/runtime/retry_test.cljc` | create | none |
| `test/atproto/runtime/datetime_test.cljc` | create | WS-10 will extend |
| `test/interop-test-files/syntax/*` | refresh if upstream changed | shared fixture dir — additive only |

## Test plan

### Unit tests

- **`atproto.tid`**: round-trip `parse`/`from-time` (timestamp + clock-id preserved); `(count (next-tid)) = 13`; `from-time` with tiny timestamps (e.g. `(from-time 1 0)`) still yields 13 chars and satisfies `valid?` (regression for padding bug); `next-tid` with a future `prev` returns something strictly newer (port `packages/common-web/tests/tid.test.ts:29-34`); sort via `compare-tids` matches chronological order; `valid?` against the interop fixtures.
- **`atproto.at-uri`**: every line of `aturi_syntax_valid.txt` parses and `(make (parse x))`-style round-trips to a canonical form; every line of `aturi_syntax_invalid.txt` returns nil; accessor behavior; `make` rejects bad NSIDs/rkeys (reuse cases from `packages/syntax/tests/aturi.test.ts`).
- **`atproto.runtime.datetime`** (clj): all `datetime_syntax_valid.txt` normalize without error; `datetime_parse_invalid.txt` cases error from `normalize` (matching `packages/syntax/tests/datetime.test.ts` semantics); `normalize-or-epoch` returns the epoch string for garbage; `current-datetime` matches the strict regex and is `normalize`-stable.
- **`atproto.xrpc.error`**: table-driven status→name mapping incl. fallback buckets (1xx/3xx→XRPCNotSupported, unknown 4xx→InvalidRequest, unknown 5xx→UpstreamFailure); body `:error` wins; `:retryable?` true exactly for `#{408 425 429 500 502 503 504 522 524}` minus 401; network errors retryable.
- **`atproto.runtime.retry`**: succeeds first try → one invocation; retryable error then success → 2 invocations; respects `:max-retries`; non-retryable error → no retry; backoff bounds (`100±15%`, capped `1000±15%`); signal aborts between attempts.
- **`atproto.xrpc.client`**: with a stub `http/handle-request` (redef or injected interceptor): header merge precedence (client defaults < proxy/labelers < per-request < computed content-type); `atproto-proxy` only set when absent per-request; `atproto-accept-labelers` joining incl. `;redact`; content-type rejection; success metadata headers; pagination `fetch-all` over a 3-page stub incl. `:max-pages` and error-mid-stream; abort delivers exactly one `{:error "Aborted"}`.
- **`atproto.client.repo`**: against a stub client capturing requests: nsid + body/params construction for all seven ops, `:repo` defaulting from session DID, `NotAuthenticated` error, `:swap-commit`/`:swap-record`/`:validate?` passthrough, applyWrites `$type` tagging, upload-blob sets `:encoding` and passes bytes untouched.

### Interop fixtures

Already vendored under `test/interop-test-files/syntax/` (consumed via the `interop-test-cases` macro pattern from `test/atproto/lexicon_test.cljc:15-25`). Before Milestone 1, `diff -r` against `/Users/luke/github/bluesky-social/atproto/interop-test-files/syntax/` and refresh `tid_*`, `aturi_*`, `datetime_*` if upstream changed. No new fixture files are required; no JSON fixtures exist upstream for xrpc errors or repo CRUD (use hand-written stubs mirroring `packages/lex/lex-client/src/errors.test.ts` cases).

### Integration / live verification

- CLJ + CLJS: run existing suite (`clojure -X:test`; cljs runner if/when configured) — all milestones must keep it green.
- Manual live check (documented in PR, not CI): unauthenticated `query` against `https://public.api.bsky.app` (`app.bsky.actor.getProfile`) exercising headers metadata and a deliberate 400 to inspect the error map; against a throwaway account on `bsky.social`: `upload-blob` a small PNG, `create-record`/`get-record`/`put-record` with `:swap-record` mismatch (expect `{:error "InvalidSwap" :status 400 :retryable? false}`), `list-all-records` over >100 records, `delete-record`; service-proxy check: query `app.bsky.actor.getProfile` on the PDS with `:service-proxy "did:web:api.bsky.app#bsky_appview"`.

## Acceptance criteria

- [x] Non-2xx XRPC responses produce error maps with `:error`, `:status`, `:headers`, `:retryable?` (and `:message` when available); no `"HTTP_<status>"` strings escape the XRPC client path.
- [x] Server JSON error names (e.g. `"InvalidSwap"`, `"ExpiredToken"`) are preserved verbatim in `:error`.
- [x] `:retryable?` is true exactly for statuses `#{408 425 429 500 502 503 504 522 524}` (excluding 401) and for transport-level failures.
- [x] Per-request and per-client `:headers` reach the wire; precedence = computed > request > client; supplying `:content-type` alongside a body returns an error map.
- [x] `:service-proxy` emits `atproto-proxy`; `:labelers` emits merged `atproto-accept-labelers` with `;redact` support; per-request values win.
- [x] `:timeout` is honored on CLJ (http-kit) and CLJS (XhrIo), surfacing a retryable `{:error "Timeout"}` (CLJS XhrIo path implemented but unexecuted — cljs test runs await WS-10).
- [x] `:max-retries` > 0 retries only retryable errors with jittered exponential backoff; default behavior (0) is byte-for-byte today's single attempt.
- [x] `abort!` on a signal causes pending calls to deliver `{:error "Aborted"}` exactly once; CLJS aborts the underlying XHR.
- [x] `atproto.client.repo` covers create/get/put/delete/list/list-all/apply-writes/upload-blob with `:repo` defaulting, swap params, and `:validate?` passthrough; `upload-blob` round-trips a binary body on CLJ (CLJS execution awaits WS-10's test runner).
- [x] `fetch-all`/`page-seq` paginate via `:cursor` and stop on missing cursor, empty page, `:max-pages`, or error.
- [x] `atproto.tid/from-time` and `next-tid` always emit exactly 13 chars (incl. timestamps < 32^10 µs); `parse`/`timestamp`/`compare-tids` round-trip; all TID interop fixtures pass through `valid?`.
- [x] `atproto.at-uri/parse` accepts every `aturi_syntax_valid.txt` line and rejects every `aturi_syntax_invalid.txt` line; `make` produces canonical `at://` strings.
- [x] `atproto.runtime.datetime/normalize` (CLJ) matches the reference semantics on all vendored datetime fixtures; `current-datetime` emits canonical millisecond-precision UTC.
- [x] `clojure -X:test` green after every milestone; no changes outside the files listed in File ownership.

## Milestones

Each is an independently mergeable, green PR.

1. ✅ **TID fixes & API** — `src/atproto/tid.cljc` (padding fix, parse/compare/from-time/valid?), `test/atproto/tid_test.cljc`, fixture refresh if needed. No dependencies. *(Done; fixtures diffed against upstream, no refresh needed.)*
2. ✅ **AT-URI namespace** — `src/atproto/at_uri.cljc` + tests. No dependencies.
3. ✅ **Datetime helpers (CLJ)** — `src/atproto/runtime/datetime.cljc` additions + tests; signatures pre-agreed with WS-10. No dependencies. *(CLJS bodies stubbed as `{:error "NotImplemented"}` per the WS-10 contract.)*
4. ✅ **Error taxonomy + retry runtime** — new `atproto.xrpc.error` + `atproto.runtime.retry` namespaces with full unit tests; *not yet wired into the client* (pure additions, mergeable even before WS-01). *(The abort-signal primitives live in `atproto.runtime.retry`; `atproto.xrpc.client/abort-signal`/`abort!` delegate to them.)*
5. ✅ **Client wiring: errors, headers, proxy/labelers** *(after WS-01 merges; rebase)* — modify `src/atproto/xrpc/client.cljc` (`handle-xrpc-response` → error ns, success-headers metadata, header merge, `with-service-proxy`/`with-labelers`, non-throwing `request-validator`), docstring updates in `src/atproto/client.cljc`, `test/atproto/xrpc/client_test.cljc`. *(Done on top of WS-01's merged client; success headers readable via `atproto.xrpc.client/response-headers`.)*
6. ✅ **Timeout, retry, cancellation** — `:timeout`/`:max-retries`/`:signal` request opts, `abort-signal`/`abort!`, CLJS `http.cljc` timeout/abort support, tests.
7. ✅ **Pagination + repo conveniences** — `fetch-pages`/`fetch-all`/`page-seq` in the xrpc client; new `src/atproto/client/repo.cljc` + `test/atproto/client/repo_test.cljc` (ns `atproto.client.repo`; naming agreement with WS-04 confirmed before this milestone lands — see File ownership); live-verification notes in the PR description. *(Live verification against bsky.social not run from CI; see Test plan.)*

## Risks & open questions

1. **WS-01 rebase risk (high)**: Milestones 5-7 touch the same file WS-01 rewrites. Mitigation: land 1-4 immediately (zero overlap); keep client changes in small, well-separated interceptors; if WS-01 slips, develop 5-7 against its branch.
2. **Default retry policy**: TS `retry` defaults to 3 but the lex-client does not auto-retry XRPC calls. **Recommendation: default `:max-retries 0` (off), opt-in per client/request** — avoids surprising duplicate procedures (createRecord is not idempotent; only retry procedures when the caller opts in, and consider documenting that retries are safest for queries).
3. **Client-side vs server-side rkey generation in `create-record`**: charter says "auto-TID rkey"; the reference deliberately omits `rkey` and lets the PDS generate it (`packages/lex/lex-client/src/util.ts:162-170`). **Recommendation: follow the reference (omit → server generates); callers wanting client-side determinism pass `{:rkey (atproto.tid/next-tid)}`.** Document this in the `create-record` docstring.
4. **JVM cancellation is cooperative only**: http-kit has no public in-flight abort. Accepting "discard the result, deliver `:error Aborted` once" keeps the API honest cross-platform. Alternative (rejected for now): switch CLJ runtime to `java.net.http.HttpClient` for real cancellation — too invasive for this WS.
5. **Pagination shape**: lazy seq is JVM-only (blocking) and reducibles don't fit CLJS async. **Recommendation: `fetch-pages` (async reduce) as the primitive on all platforms, `fetch-all` as sugar, `page-seq` CLJ-only.** Revisit a core.async transducer adapter if demand appears.
6. **Success-headers exposure**: metadata only works on collections (`with-meta` fails on strings/nil bodies). Acceptable because XRPC JSON outputs are maps; document that non-map bodies carry no header metadata. Alternative `{:body ... :headers ...}` envelope rejected (breaks every existing caller).
7. **`http/error-map` (`src/atproto/runtime/http.cljc:33-37`)** remains for non-XRPC consumers (identity, oauth). Do not change it here; a future cleanup can migrate those callers to richer errors.
8. **Datetime normalize on CLJ vs JS `Date` quirks**: the reference leans on JS `Date` parsing for "lenient" inputs (`packages/syntax/src/datetime.ts:209-255`); `java.time` is stricter. Port behavior, not implementation: use explicit formatter fallbacks (ISO local date-time → assume UTC; RFC-1123-ish with zone names) and accept that some exotic browser-parseable strings will error on CLJ — fixture-driven tests define the contract, and `datetime_parse_invalid.txt` cases must fail on both platforms.
9. **`:signal` + auth-refresh interaction**: a refresh triggered from `delegate-auth-interceptor`'s `::i/leave` re-enters the chain; the signal interceptor must sit so that an abort during refresh still delivers exactly once. Cover with a dedicated test.
10. **Library choices**: none required — everything builds on existing deps (`deps.edn`: http-kit, charred, core.async). Explicitly avoid adding a retry/scheduling library; `schedule` is ~6 lines per platform.
