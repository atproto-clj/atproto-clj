# WS-05: Firehose, Tap & Streaming Sync

| | |
|---|---|
| **Status** | Implemented (M1–M10 in one pass, 2026-07-04; WS-02/03/04/08 had already landed, so no stubs were needed and the WS-04-gated finale shipped with the rest — see "Implementation notes") |
| **Priority** | P1 |
| **Estimated size** | L |
| **Branch** | ws/05-sync-streaming |
| **Depends on** | WS-02 (`atproto.data.cbor` contract: DAG-CBOR `decode` / `decode-multi` / `encode`), WS-04 (optional, final milestones only: commit proof verification + MST walk for CAR backfill) |
| **Blocks** | Nothing hard. Example apps (statusphere ingester) and any future indexer/labeler workstream consume the event-shape + `CursorStore` contracts defined here. `atproto.runtime.ws` is reusable by any future streaming workstream. |

## Goal

When this workstream is done, the SDK has a production-grade streaming story: a `com.atproto.sync.subscribeRepos` firehose client that decodes binary CBOR frames into typed Clojure events with cursor persistence, gap detection, and automatic reconnection; a Tap client (WebSocket channel with acks, admin HTTP endpoints, and a webhook Ring handler) matching `@atproto/tap`; an upgraded Jetstream consumer that shares the same reconnect/cursor machinery and no longer drops identity/account events; and backfill helpers (listRecords walk now, getRepo CAR walk once WS-04 lands). Commit verification (signatures/proofs) is an optional mode added last, consuming WS-04.

## Implementation notes (2026-07-04)

Implemented after WS-02/03/04/07/08/09 had merged, so the provisional pieces
this plan hedged on were never created; the conflict-callout resolutions all
took their "other workstream landed first" branch:

- **No `atproto.sync.frame`** — the firehose consumes WS-08's canonical
  `atproto.xrpc.frames` (per `00-overview.md` §4.9 item 8); the
  `$type`-reconstruction helper lives in `atproto.sync.firehose/body-with-type`.
- **No `atproto.sync.car`** — WS-04's `atproto.repo.car/read-car` is used
  directly, so firehose/backfill block maps are **CID-keyed** (not
  string-keyed as sketched here), matching the repo namespaces.
- **No cbor stub** — `atproto.data.cbor` is consumed directly.
- Verification errors use WS-04's name **`RepoVerification`** (this doc's
  sketches said `RepoVerificationError`).
- **Frame/message fixtures are generated with the SDK's own codecs** (which
  are themselves pinned byte-exact against vendored reference fixtures:
  `test/atproto/xrpc/frame_fixtures.json`, `test/interop-test-files/`)
  instead of a Node script run inside the reference checkout — the checkout
  isn't available in this environment. The verified-mode tests build real
  ES256K-signed commits via `atproto.repo`.
- **Jetstream zstd is dropped** per risk 3 (the dictionary lives only in the
  external `bluesky-social/jetstream` repo); `:compress?` returns
  `{:error "UnsupportedOption"}`. `:max-retries` is accepted but deprecated —
  reconnection now uses the shared capped-backoff runtime and retries until
  the control channel closes.
- `subscribeRepos.json` needed no test vendoring: WS-09 already bundles the
  canonical lexicons under `resources/lexicons/`, so `:validate?` tests
  register those.
- `atproto.runtime.ws` heartbeats treat **any inbound traffic** as liveness,
  not just pongs: JDK listener callbacks are serialized, so a slow consumer
  would otherwise delay pong delivery and false-trigger the dead-peer check.
- Identity enrichment failures leave the raw event flowing (with `on-error`
  notified); an unverifiable handle is omitted from the event, per the
  reference.

## Current state

- `src/atproto/jetstream.clj` is the **only** streaming code in the SDK. Verified by grep: `subscribeRepos`, `firehose`, and `com.atproto.sync` appear nowhere in `src/` or `examples/`. It is a JVM-only JSON Jetstream consumer:
  - `java.net.http` WebSocket + core.async; `consume` (`src/atproto/jetstream.clj:91-138`) puts raw parsed JSON maps on a caller channel and returns a control channel.
  - Cursor is tracked in a volatile from `:time_us` (`src/atproto/jetstream.clj:133-134`) and rewound by 1µs on reconnect (`src/atproto/jetstream.clj:131`). There is **no persistence hook** — a restart loses the cursor.
  - Known defects this workstream fixes by rebasing Jetstream onto the new shared WebSocket utility:
    - Reconnect/backoff happens inside `connect` with `Thread/sleep` and recursion (`src/atproto/jetstream.clj:46-55`), called from inside a `go-loop` (`src/atproto/jetstream.clj:127-131`) — blocks a go thread; exceptions after `max-retries` are swallowed by the `go` block.
    - Backoff is `3^retries` seconds with no jitter and no max cap (`src/atproto/jetstream.clj:48`).
    - `onText` uses blocking `a/>!!` on the WebSocket listener thread (`src/atproto/jetstream.clj:28`).
    - No client-side liveness check (no ping/heartbeat); a silently dead TCP connection hangs forever.
    - No typed events: consumers get raw JSON. The statusphere example ingester destructures only `{:keys [did commit]}` (`examples/statusphere/src/xyz/statusphere/ingester.clj:17`) so Jetstream `identity`/`account` events are silently dropped.
    - No zstd support (Jetstream's `compress=true` mode).
- Building blocks that already exist and must be reused:
  - CID create/parse/encode: `atproto.data/decode-cid` (`src/atproto/data.cljc:61`), `parse-cid` (`src/atproto/data.cljc:71`), `cid-link?` (`src/atproto/data.cljc:33`).
  - Lexicon subscription specs: `translate-primary-type-def "subscription"` (`src/atproto/lexicon.cljc:523`) and `message-spec-key` (`src/atproto/lexicon.cljc:780`) already produce a spec for subscription messages when a lexicon is loaded via `load-resources!` (`src/atproto/lexicon.cljc:699`) + `register-specs!` (`src/atproto/lexicon.cljc:709`).
  - Async/error conventions: `atproto.runtime.interceptor/platform-async` (`src/atproto/runtime/interceptor.cljc:129`) and `execute` (`:157`); errors are `{:error "Name" :message "..."}` maps. Observability via `atproto.runtime.cast` (`src/atproto/runtime/cast.cljc`).
  - XRPC client for backfill + Tap-adjacent HTTP: `atproto.xrpc.client/query` (`src/atproto/xrpc/client.cljc:135`) and `procedure` (`:111`); JSON⇄atproto-data conversion `atproto.data.json/encode`/`decode` (`src/atproto/data/json.cljc:61,74`).
  - Ring conventions for the webhook handler: `atproto.xrpc.server.ring/handler` (`src/atproto/xrpc/server/ring.clj:37-43`), `ring-request->http-request` (`:19`).
  - `deps.edn` already includes http-kit 2.8.0 (provides a WebSocket *server* for tests; its client is not used) and core.async.

## Reference implementation guide

All paths under `/Users/luke/github/bluesky-social/atproto` (checked out at commit `b9ef557`, 2026-06-10).

| Concern | Reference | Notes |
|---|---|---|
| Reconnecting WS client, heartbeat, backoff | `packages/ws-client/src/index.ts` | `WebSocketKeepAlive`: re-resolves URL on every (re)connect (`:34`), exponential backoff with ±0.5s jitter capped at `maxReconnectSeconds` (default 64s) (`:183-188`), ping-based heartbeat every 10s, terminate if no pong (`:110-137`), reconnectable error classification (`:162-181`), clean-close vs abnormal-close (1006) distinction (`:50-57`). |
| Binary frame codec | `packages/xrpc-server/src/stream/frames.ts` | `Frame.fromBytes` (`:30-59`): `decodeAll(bytes)` must yield exactly `[header, body]`; >2 items or missing body is an error (`:31-36`). Header `{op: 1, t?: "#commit"}` (message) or `{op: -1}` (error, body `{error, message?}`). `frames.ts:21-23` shows encoding (concat of two CBOR items) for tests/server. Header schema in `packages/xrpc-server/src/stream/types.ts:3-27`. |
| Subscription wrapper, `$type` reconstruction | `packages/xrpc-server/src/stream/subscription.ts` | Builds URL `{service}/xrpc/{method}?{params}` (`:33`), turns header `t` `"#commit"` into `$type` `"com.atproto.sync.subscribeRepos#commit"` (`:40-47`), validates each message, skips invalid ones. Error frames are thrown as errors by `ensureChunkIsMessage` (`packages/xrpc-server/src/stream/stream.ts:28-39`). |
| Firehose event parsing | `packages/sync/src/firehose/index.ts` | Options incl. `getCursor`/`runner` mutual exclusion (`:64-66`), collection filtering with `.*` prefix patterns (`:67-85`), reconnect delay 3s on subscription error (`:139`). `parseCommitAuthenticated` (`:203-251`, incl. force-key-refresh retry on `RepoVerificationError` `:230-235`), `parseCommitUnauthenticated` (`:253-259`), `formatCommitOps` (`:272-318`): reads the commit's CAR (`evt.blocks` bytes), looks up each op's record block by CID, decodes record from DAG-CBOR; delete ops have no record. `parseSync` (`:320-334`), `parseIdentity` (`:336-355`, with bidirectional handle verification `:357-368`), `parseAccount` (`:370-382`, drops events with unknown status strings `:384-386`). |
| Parsed event shapes | `packages/sync/src/events.ts` | `CommitEvt` = create/update/delete with `{seq time commit blocks rev uri did collection rkey [record cid]}` (`:8-36`); `SyncEvt` (`:38-46`); `IdentityEvt` (`:48-55`); `AccountEvt` (`:57-70`). |
| Cursor + ordered processing | `packages/sync/src/runner/memory-runner.ts`, `consecutive-list.ts`, `types.ts` | `EventRunner` interface (`types.ts:1-8`). `MemoryRunner.trackEvent` (`memory-runner.ts:47-60`): per-DID serial partitions, global concurrency limit, cursor advances to the highest *consecutive* completed seq, then `setCursor` hook fires. `ConsecutiveList` (`consecutive-list.ts:15-44`) is the core data structure. |
| `did`/`seq` extraction | `packages/sync/src/util.ts:3-16` | commit uses `.repo`, others use `.did`. |
| Firehose wire schema | `lexicons/com/atproto/sync/subscribeRepos.json` | `#commit` required fields & 2MB `blocks` limit (`:31-108`), `#sync` (`:109-138`), `#identity` (`:139-153`), `#account` with `knownValues` incl. `desynchronized`/`throttled` (`:154-179`), `#info` (`:180-192`), `#repoOp` with nullable `cid` and `prev` (`:193-213`), stream errors `FutureCursor`/`ConsumerTooSlow` (`:23-29`). Note `com.atproto.sync.getRepo`, `listRepos`, etc. live alongside it; `com.atproto.repo/listRecords.json` is in `lexicons/com/atproto/repo/`. |
| Tap client + channel | `packages/tap/src/client.ts`, `channel.ts` | `Tap`: HTTP endpoints `/repos/add`, `/repos/remove` (POST `{dids}`), `/resolve/{did}`, `/info/{did}`, Basic auth header (`client.ts:18-50`). `TapChannel`: WS to `/channel`, JSON text messages, ack protocol `{"type":"ack","id":n}` (`channel.ts:74-76`), acks buffered while disconnected and flushed in order on reconnect (`channel.ts:62-107`), **no ack on handler error** so Tap redelivers (`channel.ts:145-150`). |
| Tap event schema + `parseTapEvent` | `packages/tap/src/types.ts` | Wire shape is nested: `{id, type: "record", record: {...}}` / `{id, type: "identity", identity: {...}}`; note snake_case `is_active` (`types.ts:19`). `parseTapEvent` (`types.ts:76-101`) flattens to a single-level event. `RepoInfo` schema (`types.ts:103-113`). |
| Tap admin auth | `packages/tap/src/util.ts` | `formatAdminAuthHeader` = `Basic base64("admin:" + password)` (`:1-3`), `parseAdminAuthHeader` (`:5-14`), `assureAdminAuth` with timing-safe compare (`:16-33`). |
| Tap indexer ergonomics | `packages/tap/src/simple-indexer.ts` | Type-dispatched handlers; auto-acks after handler (`:36-43`); error handler optional. (`lex-indexer.ts` is schema-typed dispatch — port only its collection-dispatch idea, not the codegen typing.) |
| Tap README (semantics) | `packages/tap/README.md` | At-least-once delivery, per-repo ordering, backfill-before-live, `live` flag on record events, webhook mode example (`:51-56`, `:175-234`). Tap server itself is `bluesky-social/indigo` `cmd/tap` (external). |

**Interop/test fixtures available:**

- `/Users/luke/github/bluesky-social/atproto/interop-test-files/` contains **only** `crypto/` and `syntax/` — there are no firehose-frame or CAR interop fixtures upstream. (The repo's existing `test/interop-test-files/README.md` mirrors this.)
- `/Users/luke/github/bluesky-social/atproto/packages/repo/tests/car-file-fixtures.json` — vendorable: `[{root: cid-string, blocks: [{cid, bytes(base64)}], car(base64)}]`; exercised by `packages/repo/tests/car.test.ts:23-60`. Use for the minimal CAR reader.
- `/Users/luke/github/bluesky-social/atproto/packages/repo/tests/commit-proof-fixtures.json` — belongs to WS-04 (verification); do not vendor here.
- Tap JSON event examples to copy into JSON fixtures: `packages/tap/tests/_util.ts:18-41` and `packages/tap/tests/channel.test.ts:7-31` (note `channel.test.ts` has the *wire* nested shape; `_util.ts` has the *parsed* flat shape).
- Firehose frames: no fixtures exist; we generate them (see Test plan).

## Scope

### In scope

- **`atproto.runtime.ws`** — new shared WebSocket consumer runtime (port of `@atproto/ws-client` `WebSocketKeepAlive`): URL re-resolution per connect, exponential backoff with jitter and cap, ping/pong heartbeat with dead-connection termination, clean-close vs abnormal-close semantics, binary and text message support. `.cljc` file, `:clj` implementation via `java.net.http.WebSocket` behind a small protocol; ClojureScript left as a stub returning `{:error "NotImplemented"}`.
- **`atproto.sync.frame`** — binary event-stream frame codec (`decode-frame`, `encode-frame`) on top of the WS-02 `atproto.data.cbor` contract, including error-frame and malformed-frame handling. **Provisional:** WS-08 is building the same codec *now* as `atproto.xrpc.frames` for its subscription transport (`08-service-auth-xrpc-server.md`, "atproto.xrpc.frames" section); the two consolidate per `00-overview.md` §4.9 item 8 — see Interface contract and File ownership.
- **`atproto.sync.cursor`** — `CursorStore` protocol (get/set, async via callback) + in-memory implementation. Consumed by firehose, Jetstream, and the runner.
- **`atproto.sync.firehose`** — `com.atproto.sync.subscribeRepos` client: cursor query param from store/fn, `$type` reconstruction from frame header, optional lexicon validation, dispatch of `#commit` / `#sync` / `#identity` / `#account` / `#info`, error frames surfaced as `{:error "ConsumerTooSlow"|"FutureCursor" ...}`, seq-gap and seq-regression detection (cast metric + optional callback), collection filtering with `prefix.*` patterns, **unverified** commit parsing (record extraction from the commit CAR) first; identity events optionally enriched/verified via `atproto.identity/resolve-identity`; account status passed through openly (accept `desynchronized`/`throttled`).
- **`atproto.sync.car`** — minimal *read-only* CAR v1 block scanner (varint-framed sections, DAG-CBOR header `{version 1, roots [...]}`, CID-prefixed blocks) sufficient for firehose commit/sync events. Provisional: replaced by/merged into the WS-04 repo CAR namespace if that lands first (see Risks).
- **`atproto.sync.runner`** — port of `ConsecutiveList` + `MemoryRunner`: per-DID serial partitions, bounded global concurrency, consecutive-seq cursor commit with `CursorStore` hook.
- **`atproto.tap.auth`** — `format-admin-auth-header`, `parse-admin-auth-header`, `assure-admin-auth` (timing-safe).
- **`atproto.tap.events`** — `parse-tap-event` (wire JSON → flattened typed event) shared by channel and webhook; spec for tap events.
- **`atproto.tap.client`** — Tap admin client: `add-repos!`, `remove-repos!`, `resolve-did`, `repo-info` (async, interceptor-based) and `channel` (WS with ack protocol, buffered acks across reconnects, at-least-once handler semantics).
- **`atproto.tap.webhook`** — Ring handler factory for Tap webhook mode with shared-secret admin auth (the equivalent of the README's express example).
- **Jetstream upgrades** (`atproto.jetstream`): rebase connection management onto `atproto.runtime.ws` (fixes the bugs listed in Current state); `:cursor-store` option (same `CursorStore` protocol, cursor in µs); typed event parsing (`:commit`/`:identity`/`:account` kinds so consumers like the statusphere ingester stop silently dropping events); optional zstd decompression (`compress=true`) behind an optional dependency; preserve the existing `consume` channel API (additive changes only).
- **`atproto.sync.backfill`** — `com.atproto.repo.listRecords` pagination walk emitting synthetic create events (`:live false`), with per-collection iteration from `com.atproto.repo.describeRepo`; `com.atproto.sync.getRepo` CAR-based backfill **as a final milestone gated on WS-04** (MST walk).
- **Verified firehose mode** — `parse-commit-authenticated` consuming the WS-04 proof-verification contract, with force-key-refresh retry. Final milestone; unverified mode ships first.
- Vendoring fixtures + generation script for binary frame fixtures (see Test plan).

### Out of scope

- **DAG-CBOR encode/decode itself** — WS-02 owns `atproto.data.cbor`. This workstream only consumes it (stub until merged).
- **MST, repo data structures, commit signature & proof verification, CAR *writing*** — WS-04. This workstream consumes `verify-proofs`-style and MST-walk contracts in its final milestones only.
- **The firehose server side** (`com.atproto.sync.subscribeRepos` in `atproto.xrpc.server`) — a future xrpc-server workstream. We only write enough server scaffolding inside *tests* (http-kit WS server emitting canned frames).
- **Bundling `com.atproto.*` lexicon JSON into SDK `resources/`** — whichever workstream owns lexicon vendoring. We vendor `subscribeRepos.json` under `test/` only, for validation tests.
- **Updating `examples/statusphere`** to use Tap or typed Jetstream events — follow-up example work after this merges; not part of this branch.
- **Jetstream server-side features** (Jetstream is an external Go service); we only implement the client.
- **`LexIndexer`-style codegen-typed handlers** — requires the lexicon-codegen story; we ship the untyped `simple-indexer` equivalent (plain dispatch on event kind/collection).
- **ClojureScript WebSocket implementation** — namespaces are `.cljc` and platform-gated, but only the JVM implementation is required to pass tests in this workstream.

## Deliverables

Public API sketches. All async functions follow the SDK convention: trailing `& {:as opts}` with `:callback` / `:channel` / `:promise` handled by `atproto.runtime.interceptor/platform-async`; errors are `{:error "Name" :message "..."}` maps; observability via `atproto.runtime.cast`.

### `src/atproto/runtime/ws.cljc`

```clojure
(ns atproto.runtime.ws
  "Cross-platform reconnecting WebSocket consumer.

  Port of @atproto/ws-client WebSocketKeepAlive. JVM implementation uses
  java.net.http.WebSocket; cljs is not yet implemented.")

(defn connect
  "Open a self-healing WebSocket subscription.

  Config map:
    :url-fn          (fn [cb]) -> calls cb with the URL string (or {:error ...}).
                     Re-invoked before every (re)connect so cursor params stay fresh.
    :headers         map of extra headers (e.g. Tap admin auth).
    :on-message      (fn [msg]) msg is ^bytes for binary frames, String for text.
                     Called on the socket listener thread; must not block long.
    :on-error        (fn [{:keys [error message exception]}]) non-fatal + fatal errors.
    :on-reconnect    (fn []) called after a successful reconnect (not first connect).
    :on-close        (fn [{:keys [code reason]}]) terminal: no further reconnects.
    :max-reconnect-ms        cap for backoff (default 64000).
    :heartbeat-interval-ms   ping interval; terminate if no pong (default 10000).

  Reconnect policy (port of ws-client/src/index.ts:25-88,183-188):
  exponential backoff 2^n seconds with +-0.5s jitter, capped; abnormal close
  (1006) and IO errors reconnect; clean server close ends the subscription
  (on-close); protocol-level failures surface through on-error and end it.

  Returns a handle for use with `send!` and `close!`."
  [config])

(defn send!
  "Send a text or binary message. Calls cb with {} or {:error ...}.
  Used by the Tap channel for acks."
  [handle msg cb])

(defn close!
  "Cleanly close the socket and stop reconnecting. Idempotent."
  [handle])

(defn connected? [handle])
```

### `src/atproto/sync/frame.cljc` (provisional — consolidates with WS-08's `atproto.xrpc.frames`, see Interface contract)

```clojure
(ns atproto.sync.frame
  "Binary event-stream frames: a frame is exactly two concatenated DAG-CBOR
  items, header then body. Port of xrpc-server/src/stream/frames.ts."
  (:require [atproto.data.cbor :as cbor])) ;; WS-02 contract

(defn decode-frame
  "bytes -> frame map.

  Message frame: {:op 1, :t \"#commit\", :body {...}}   (:t optional)
  Error frame:   {:op -1, :error \"ConsumerTooSlow\", :message \"...\"}
  Malformed:     {:error \"InvalidFrame\" :message \"...\"}
  (too many CBOR items, missing body, bad header, unknown op)"
  [bytes])

(defn encode-frame
  "frame map -> bytes. Inverse of decode-frame; used by tests and a future
  server implementation."
  [frame])

(defn message-body
  "Attach :$type to a message frame body given the subscription nsid:
  header :t \"#commit\" + nsid \"com.atproto.sync.subscribeRepos\"
  -> body with :$type \"com.atproto.sync.subscribeRepos#commit\".
  Port of xrpc-server/src/stream/subscription.ts:40-47."
  [nsid frame])
```

### `src/atproto/sync/cursor.cljc`

```clojure
(ns atproto.sync.cursor)

(defprotocol CursorStore
  :extend-via-metadata true
  (get-cursor [store cb]
    "Calls cb with {:cursor n} ({:cursor nil} when none) or {:error ...}.")
  (set-cursor [store cursor cb]
    "Persist cursor. Calls cb with {} or {:error ...}."))

(defn memory-store
  "In-memory CursorStore, optionally seeded: (memory-store) or
  (memory-store {:cursor 123})."
  [& {:keys [cursor]}])
```

### `src/atproto/sync/firehose.cljc`

```clojure
(ns atproto.sync.firehose
  "com.atproto.sync.subscribeRepos (firehose) client.
  Port of @atproto/sync Firehose (unverified mode; verified mode is gated
  on WS-04).")

;; Parsed event shapes (mirror packages/sync/src/events.ts).
;; Wire-level lexicon keys keep their camelCase names; envelope keys are
;; kebab-case Clojure keywords.
;;
;; commit-derived (one event per repo op):
;;   {:kind :create | :update | :delete
;;    :seq 1234 :time "..." :did "did:plc:..." :rev "..." :since "..."
;;    :commit <cid> :uri "at://did/coll/rkey" :collection "..." :rkey "..."
;;    :blocks {"<cid-string>" bytes}      ;; block map from the commit CAR
;;    :record {...} :cid <cid>}           ;; :create/:update only
;; {:kind :sync     :seq :time :did :cid :rev :blocks}
;; {:kind :identity :seq :time :did :handle :did-doc}  ;; handle/did-doc optional
;; {:kind :account  :seq :time :did :active :status}   ;; :status open string
;; {:kind :info     :name "OutdatedCursor" :message "..."}
;; {:kind :gap      :seq :prev-seq}        ;; emitted on non-consecutive seq

(s/def ::event ...) ;; spec over the above

(defn consume
  "Subscribe to the firehose and process events.

  Config:
    :service           e.g. \"wss://bsky.network\" (default)
    :handler           (fn [event]) called serially; cursor only advances
                       after handler returns. One of :handler or :runner.
    :runner            an atproto.sync.runner runner for partitioned
                       concurrency (handler then passed to the runner).
    :on-error          (fn [{:keys [error message event exception]}])
                       non-fatal: FirehoseParseError, FirehoseHandlerError,
                       InvalidFrame, validation failures. Stream-level error
                       frames (FutureCursor, ConsumerTooSlow) also surface
                       here before reconnect/shutdown.
    :cursor            initial cursor (int), or
    :cursor-store      atproto.sync.cursor/CursorStore; consulted via url-fn
                       on every (re)connect, updated as events complete.
    :filter-collections [\"app.bsky.feed.post\" \"xyz.statusphere.*\"]
                       client-side; exact NSIDs or `.*` prefix patterns
                       (port of firehose/index.ts:67-85).
    :exclude           #{:identity :account :commit :sync}
    :validate?         validate messages against the loaded lexicon's
                       subscription message spec (lexicon/message-spec-key);
                       invalid messages -> on-error, skipped. Default false.
    :resolve-identity? enrich :identity events with did-doc + bidirectionally
                       verified handle via atproto.identity (default false;
                       port of firehose/index.ts:336-368).
    :ws-opts           passed through to atproto.runtime.ws/connect.

  Seq handling: seqs must be strictly increasing. A jump emits a :gap event
  and a cast/metric; a regression emits on-error {:error \"SeqRegression\"}.

  Returns a handle; (stop! handle) shuts down (close WS, drain in-flight
  handler calls, final set-cursor)."
  [config])

(defn stop! [handle])

;; Lower-level parsing fns, public for reuse and testing:

(defn parse-message
  "Decoded+typed subscribeRepos message map -> seq of parsed events
  (a #commit fans out to one event per op; unverified). Pure except for
  CAR/CBOR decoding. Invalid/unknown -> {:error \"FirehoseParseError\" ...}."
  [message & {:keys [filter-collections]}])

(defn parse-commit-unverified [commit-message match-collection-fn])
(defn parse-sync-event [sync-message])
(defn parse-account-event [account-message])
(defn parse-identity-event
  "Without resolution. Use atproto.identity/resolve-identity separately for
  verified handles." [identity-message])

;; Milestone 10 (WS-04-gated):
(defn parse-commit-verified
  "Like parse-commit-unverified but verifies commit signature + inclusion
  proofs via the WS-04 contract; retries once with a forced key refresh on
  verification failure (port of firehose/index.ts:203-251).
  Async: calls cb with events or {:error \"RepoVerificationError\" ...}."
  [id-resolver commit-message match-collection-fn cb])
```

### `src/atproto/sync/car.cljc` (provisional — see Risks)

```clojure
(ns atproto.sync.car
  "Minimal read-only CAR v1 reader: enough to extract the block map and root
  from firehose #commit/#sync `blocks` bytes. Superseded by the WS-04 repo
  CAR namespace when it lands.")

(defn read-car
  "bytes -> {:roots [<cid>], :blocks {\"<cid-string>\" bytes}}
  or {:error \"InvalidCar\" :message ...}.
  CAR v1: varint-length-prefixed sections; first section is the DAG-CBOR
  header {:version 1 :roots [...]}; each block section is CID bytes followed
  by block bytes."
  [bytes])
```

### `src/atproto/sync/runner.cljc`

```clojure
(ns atproto.sync.runner
  "Partitioned in-order event processing with consecutive-seq cursor commit.
  Port of @atproto/sync MemoryRunner + ConsecutiveList.")

(defn memory-runner
  "Options:
    :concurrency   max partitions processed at once (default unbounded)
    :cursor-store  CursorStore updated with the latest *consecutive*
                   completed seq (memory-runner.ts:47-60 semantics)
    :start-cursor  initial cursor"
  [& {:as opts}])

(defn track-event
  "Schedule handler-fn (fn [cb]) on the partition for did, recording seq.
  Events for the same did run serially; cursor advances per ConsecutiveList."
  [runner did seq handler-fn])

(defn get-cursor [runner cb])
(defn drain!
  "Wait for all queued work to complete. cb with {}."
  [runner cb])
(defn destroy! [runner])

;; pure data structure, exposed for testing:
(defn consecutive-list [])
(defn push [clist v])        ;; -> [clist' item-id]
(defn complete [clist item-id]) ;; -> [clist' completed-values]
```

### `src/atproto/tap/auth.cljc`

```clojure
(ns atproto.tap.auth)

(defn format-admin-auth-header
  "password -> \"Basic \" + base64(\"admin:\" + password)" [password])

(defn parse-admin-auth-header
  "header value -> password, or {:error \"InvalidAuthHeader\" :message ...}
  (must be Basic with username admin; port of tap/src/util.ts:5-14)."
  [header])

(defn admin-auth-valid?
  "Timing-safe comparison (java.security.MessageDigest/isEqual on the JVM).
  -> boolean" [expected-password header])
```

### `src/atproto/tap/events.cljc`

```clojure
(ns atproto.tap.events
  (:require [clojure.spec.alpha :as s]))

;; Flattened event shapes (port of tap/src/types.ts:45-74):
;; {:type :record  :id 1 :action :create|:update|:delete
;;  :did "..." :rev "..." :collection "..." :rkey "..."
;;  :record {...} :cid "..."          ;; create/update only
;;  :live true}
;; {:type :identity :id 2 :did "..." :handle "..."
;;  :active true :status "active"}    ;; wire key is_active -> :active

(s/def ::event ...)

(defn parse-tap-event
  "Parsed wire JSON (nested {:id n :type \"record\" :record {...}} shape)
  -> flattened event map, or {:error \"InvalidTapEvent\" :message ...
  :explain-data ...}. Record bodies pass through atproto.data.json/decode
  so $link/$bytes become CIDs/bytes. Port of tap/src/types.ts:76-101."
  [data])
```

### `src/atproto/tap/client.cljc`

```clojure
(ns atproto.tap.client
  "Client for a Tap instance (bluesky-social/indigo cmd/tap).
  Port of @atproto/tap Tap + TapChannel.")

(defn create
  "{:url \"http://localhost:2480\" :admin-password \"secret\"} -> client"
  [config])

;; Admin HTTP endpoints; async per SDK convention, e.g.
;; (tap/add-repos! client [\"did:plc:...\"] :callback cb)
(defn add-repos! [client dids & {:as opts}])    ;; POST /repos/add
(defn remove-repos! [client dids & {:as opts}]) ;; POST /repos/remove
(defn resolve-did [client did & {:as opts}])    ;; GET /resolve/{did} -> {:did-doc ...} | {:error \"DidNotFound\"}
(defn repo-info [client did & {:as opts}])      ;; GET /info/{did}

(defn channel
  "Open the /channel WebSocket (ws[s] derived from :url) with admin auth.

  Config:
    :handler  (fn [event ack!]) — event from atproto.tap.events; call (ack!)
              after durable processing. ack! is async-safe; acks are sent as
              {\"type\":\"ack\",\"id\":id}, buffered while disconnected and
              flushed in order on reconnect (port of tap/src/channel.ts:62-107).
              If the handler throws, NO ack is sent (Tap redelivers:
              at-least-once).
    :on-error (fn [{:keys [error message exception]}]) parse/handler errors.
    :ws-opts  passed to atproto.runtime.ws/connect.

  Returns a handle; (stop! handle) closes it."
  [client config])

(defn stop! [handle])
```

### `src/atproto/tap/webhook.clj`

```clojure
(ns atproto.tap.webhook
  "Ring handler for Tap webhook delivery mode.")

(defn handler
  "Build a Ring handler for Tap webhook POSTs.

  Config:
    :admin-password  shared secret; requests failing
                     atproto.tap.auth/admin-auth-valid? -> 401.
    :handler         (fn [event]) — parsed via atproto.tap.events/parse-tap-event;
                     return value ignored; throw -> 500 (Tap retries).
    :path            route to match (default \"/tap\"); non-matching -> nil
                     (composable like atproto.xrpc.server.ring/handler,
                     src/atproto/xrpc/server/ring.clj:37-43).

  200 {} on success; 400 on unparseable events; 401/500 as above."
  [config])
```

### `src/atproto/jetstream.clj` (modified, additive)

```clojure
;; consume gains options (existing options & channel contract unchanged):
;;   :cursor-store  atproto.sync.cursor/CursorStore (µs cursor); read at
;;                  (re)connect, written as events are taken.
;;   :typed?        when true, events are parsed into
;;                  {:kind :commit|:identity|:account ...} maps (commit ops
;;                  use the same :create/:update/:delete keys as
;;                  atproto.sync.firehose where applicable); unknown kinds
;;                  pass through raw with :kind :unknown.
;;   :compress?     zstd mode (`compress=true` query param); requires the
;;                  optional zstd dep + vendored dictionary; without it,
;;                  returns {:error \"UnsupportedOption\"}.
;; internals rebased onto atproto.runtime.ws (jitter/backoff/heartbeat).
```

### `src/atproto/sync/backfill.cljc`

```clojure
(ns atproto.sync.backfill
  "Backfill a repo's records ahead of / alongside live streaming.")

(defn list-records-walk
  "Walk com.atproto.repo.listRecords for one repo+collection via the supplied
  atproto.xrpc.client, calling handler with synthetic events
  {:kind :create :live false :did ... :collection ... :rkey ... :record ...
   :cid ... :uri ...} in rkey order. Paginates with :cursor/:limit (100).
  Async; final cb gets {:count n} or {:error ...}."
  [xrpc-client {:keys [repo collection limit handler]} & {:as opts}])

(defn backfill-repo
  "describeRepo -> collections (optionally filtered) -> list-records-walk
  each. Async; cb gets {:count n :collections [...]} or {:error ...}."
  [xrpc-client {:keys [repo filter-collections handler]} & {:as opts}])

;; Milestone 10 (WS-04-gated):
(defn get-repo-walk
  "Fetch com.atproto.sync.getRepo (CAR bytes), verify + walk the MST via the
  WS-04 contract, emit the same synthetic events. Single round-trip,
  authenticated alternative to list-records-walk."
  [xrpc-client {:keys [repo handler]} & {:as opts}])
```

## Interface contract

### Provided (frozen once Milestone 3 merges)

1. **Event shapes** — the `:kind`-keyed maps documented in `atproto.sync.firehose` above, and `:type`-keyed Tap events in `atproto.tap.events`. Wire-level lexicon field names stay camelCase (matching the SDK's existing JSON convention, e.g. `:alsoKnownAs` in `atproto.identity`); envelope keys are kebab-case.
2. **`atproto.sync.cursor/CursorStore`** — the persistence protocol (above) for firehose, Jetstream, and runner cursors. `:extend-via-metadata true` like `atproto.xrpc.client/Session` (`src/atproto/xrpc/client.cljc:38`).
3. **`atproto.runtime.ws/connect|send!|close!|connected?`** — reusable by any workstream needing a WebSocket consumer.
4. **`atproto.sync.frame/decode-frame|encode-frame`** — **provisional, exempt from the freeze.** WS-08 is building the equivalent codec *concurrently* as `atproto.xrpc.frames` for its subscription transport (`08-service-auth-xrpc-server.md`, "atproto.xrpc.frames" section). Per `00-overview.md` §4.9 item 8 only one codec should exist, and WS-08's `atproto.xrpc.frames` is canonical: whichever workstream lands second deletes/ports its own codec and consumes the first, keeping the `message-body`/`$type`-reconstruction helper wherever the firehose client needs it. No other workstream should take a dependency on `atproto.sync.frame`.

### Consumed

1. **WS-02 — `atproto.data.cbor`** (hard). Assumed contract:
   - `(cbor/decode bytes)` → one atproto-data value; `(cbor/decode-multi bytes)` → seq of consecutive values (frames are exactly two); `(cbor/encode data)` → bytes.
   - CBOR tag 42 decodes to the same CID objects `atproto.data` uses (`multiformats.cid`, satisfying `atproto.data/cid?`); CBOR byte strings decode to platform bytes (`atproto.runtime.bytes/bytes?`).
   - **Before WS-02 merges:** develop against `test/atproto/support/cbor_stub.clj`, a throwaway DAG-CBOR decoder (JVM-only, no canonical-encoding checks) used only by tests/fixtures, kept out of `src/`. All `src/` code requires `atproto.data.cbor` by name so the swap is a no-op; CI for this branch stays green by gating the cbor-dependent test namespaces behind the stub. If WS-02's actual fn names differ, rename at rebase (single require site per namespace).
2. **WS-04 — repo verification & MST** (optional, milestones 9–10 only). Assumed contract: a `verify-proofs`-style fn (CAR bytes + claims `[{:collection :rkey :cid}]` + did + signing key → verified ops or `{:error "RepoVerificationError"}`), and an MST walk for `get-repo-walk`. Until merged: `parse-commit-verified` and `get-repo-walk` are not written; nothing else here touches WS-04.
3. **Existing SDK contracts** (already merged on `redesign`): `atproto.runtime.interceptor`, `atproto.runtime.http`, `atproto.runtime.json`, `atproto.runtime.cast`, `atproto.data`, `atproto.data.json`, `atproto.identity/resolve-identity`, `atproto.xrpc.client`, `atproto.lexicon/message-spec-key`.

## File ownership

Created by this workstream (repo-relative):

- `src/atproto/runtime/ws.cljc`
- `src/atproto/sync/frame.cljc` *(provisional — duplicate of WS-08's `atproto.xrpc.frames`; see conflict callout)*
- `src/atproto/sync/cursor.cljc`
- `src/atproto/sync/firehose.cljc`
- `src/atproto/sync/car.cljc` *(provisional — see conflict note)*
- `src/atproto/sync/runner.cljc`
- `src/atproto/sync/backfill.cljc`
- `src/atproto/tap/auth.cljc`
- `src/atproto/tap/events.cljc`
- `src/atproto/tap/client.cljc`
- `src/atproto/tap/webhook.clj`
- `test/atproto/runtime/ws_test.clj`
- `test/atproto/sync/frame_test.cljc`
- `test/atproto/sync/car_test.cljc`
- `test/atproto/sync/cursor_test.cljc`
- `test/atproto/sync/firehose_test.clj`
- `test/atproto/sync/runner_test.cljc`
- `test/atproto/sync/backfill_test.clj`
- `test/atproto/tap/auth_test.cljc`
- `test/atproto/tap/events_test.cljc`
- `test/atproto/tap/client_test.clj`
- `test/atproto/tap/webhook_test.clj`
- `test/atproto/support/cbor_stub.clj` *(deleted when WS-02 merges)*
- `test/atproto/support/ws_server.clj` *(http-kit WS test server helpers)*
- `test/atproto/sync/fixtures/car-file-fixtures.json` *(vendored)*
- `test/atproto/sync/fixtures/frames/*.bin` + `frames-manifest.json` *(generated; see Test plan)*
- `test/atproto/sync/fixtures/subscribe-repos-messages.json` *(generated)*
- `test/atproto/tap/fixtures/tap-events.json`
- `test/resources/lexicons/com/atproto/sync/subscribeRepos.json` *(vendored for validation tests; note `:test` alias must add `test/resources` to paths, or store under `test/` and load via file path — implementer's choice, keep it out of shipped `resources/`)*

Modified:

- `src/atproto/jetstream.clj` — **shared with WS-10 by land order** (agreement recorded in `00-overview.md` §4.9 item 7 and the conflict matrix, `00-overview.md:441-442`): WS-05 owns the file *first* — rebase onto `atproto.runtime.ws`, typed events, `:cursor-store` (Milestone 8); WS-10 then git-mv's it to `jetstream.cljc` and adds the `:cljs` branch (on `atproto.runtime.ws`, not a separate `runtime/websocket.cljc`), rebasing over WS-05's changes. If WS-05 Milestone 8 and WS-10's jetstream milestone are in flight simultaneously, WS-05 merges first and WS-10 rebases — coordinate in the PR descriptions.
- `deps.edn` — **shared file, conflict-prone.** Changes here are minimal: add an optional alias (e.g. `:zstd`) with `com.github.luben/zstd-jni`; no top-level dep changes expected. Whoever lands second rebases; coordinate in the PR description.

Conflict callout: `src/atproto/sync/car.cljc` overlaps conceptually with WS-04 (repo/CAR). Agreement to encode in both branch plans: **WS-05 lands the minimal read-only reader first** (it is ~80 lines and unblocks unverified commits); WS-04, when it builds full CAR/MST support, either consumes `atproto.sync.car/read-car` as-is or supersedes it and updates `atproto.sync.firehose`'s single call site — WS-04 rebases over WS-05 for this file. If WS-04 merges first instead, WS-05 deletes its provisional reader at rebase and consumes WS-04's.

Conflict callout 2: `src/atproto/sync/frame.cljc` duplicates WS-08's `atproto.xrpc.frames` (`08-service-auth-xrpc-server.md`, "atproto.xrpc.frames" section — same two-CBOR-item codec). Resolution per `00-overview.md` §4.9 item 8: **`atproto.xrpc.frames` is canonical**; whichever workstream lands second deletes/ports its codec and requires the survivor, keeping the `message-body` `$type`-reconstruction helper where the firehose client needs it (the firehose has a single require site, so the swap is mechanical). Owners confirm at kickoff.

## Test plan

**Unit tests** (`clj -X:test`, all green at every milestone):

- `frame_test`: round-trip `encode-frame`/`decode-frame` for message frames (with/without `:t`), error frames; malformed cases (3 CBOR items, 1 item, bad header op, garbage bytes) → `{:error "InvalidFrame"}`; `message-body` `$type` reconstruction for `#commit` and fully-qualified `t` values (port the cases implied by `xrpc-server/src/stream/frames.ts:30-59` and `subscription.ts:40-47`).
- `car_test`: vendored `car-file-fixtures.json` — decode base64 `car`, assert root CID string and every block's CID/bytes match (mirror `packages/repo/tests/car.test.ts:44-60` read side); reject truncated/garbage CARs.
- `firehose_test` (parsing layer, no sockets): canned decoded messages (see fixtures below) → `parse-message`: commit fan-out per op, create/update record extraction from blocks, delete without record, collection filtering incl. `prefix.*`, account with `desynchronized` status passes through, identity without resolution, `#info` passthrough, unknown `$type` skipped with `on-error`.
- `runner_test`: `consecutive-list` semantics exactly as `consecutive-list.ts:1-14`'s docstring example; property test (test.check, already a `:test` dep) — random completion orders never advance the cursor past an incomplete seq and always reach max at the end; per-DID ordering preserved under concurrency.
- `cursor_test`, `tap/auth_test` (incl. wrong username, non-Basic header, timing-safe path), `tap/events_test` (wire→flat for record + identity, `is_active`→`:active`, invalid → `{:error "InvalidTapEvent"}`).

**Fixtures to vendor / generate:**

- Vendor `/Users/luke/github/bluesky-social/atproto/packages/repo/tests/car-file-fixtures.json` → `test/atproto/sync/fixtures/car-file-fixtures.json` (unmodified).
- Vendor `/Users/luke/github/bluesky-social/atproto/lexicons/com/atproto/sync/subscribeRepos.json` → `test/resources/lexicons/com/atproto/sync/subscribeRepos.json` for `:validate?` tests via `lexicon/load-resources!` + `register-specs!`.
- Tap wire events: copy the shapes from `packages/tap/tests/channel.test.ts:7-31` and `packages/tap/tests/_util.ts:18-41` into `test/atproto/tap/fixtures/tap-events.json` (valid record create/update/delete, identity, plus invalid variants).
- **Frame/message fixtures (no upstream interop files exist):** add a one-off Node script (committed under `test/atproto/sync/fixtures/generate.mjs`, with its output committed too) that runs inside the reference repo using `@atproto/lex-cbor` `encode` and `MessageFrame`/`ErrorFrame` from `packages/xrpc-server/src/stream/frames.ts` to emit: (a) `frames/*.bin` — message frame with `t:"#commit"`, message frame without `t`, error frame `{error:"ConsumerTooSlow"}`, and a 3-item malformed frame; (b) `subscribe-repos-messages.json` — a small set of realistic `#commit`/`#sync`/`#identity`/`#account`/`#info` bodies where `blocks` is a real CAR built from `car-file-fixtures.json` blocks (base64 in the JSON manifest, decoded in tests). This gives byte-exact interop coverage of WS-02's decoder through our frame codec without a live network.

**Integration tests (JVM, in-process, no network):**

- `test/atproto/support/ws_server.clj`: http-kit WebSocket server (http-kit is already a dep; its server supports binary sends) that replays fixture frames.
- `ws_test`: connect/receive binary + text; server kills the connection abnormally → client reconnects with backoff (configure tiny `:max-reconnect-ms`); `:url-fn` re-invoked per reconnect (assert cursor param changes); clean close → `on-close`, no reconnect; heartbeat terminates a server that stops ponging (java.net.http auto-pongs as a client; for the dead-peer case, stub the listener).
- `firehose_test` (socket layer): replay commit frames → handler receives parsed events in order; cursor-store written only after handler returns; kill + replay from cursor → reconnect URL carries `?cursor=`; out-of-order seq → `:gap` event + cast metric; error frame → `on-error` `{:error "ConsumerTooSlow"}`.
- `tap/client_test`: port `packages/tap/tests/channel.test.ts` scenarios — receive/parse record + identity events; ack sent after handler; handler throw → no ack; disconnect before ack → buffered, flushed in order on reconnect; admin endpoints against a stub Ring server (assert `Authorization: Basic ...`).
- `tap/webhook_test`: Ring handler — missing/wrong auth → 401; valid event → 200 and handler invoked with flattened event; bad payload → 400; handler throw → 500.
- `jetstream` typed parsing: canned Jetstream JSON (commit/identity/account) → typed events; cursor-store read/write.

**Live verification (manual/`^:integration`, documented in each ns's Rich comment block like `src/atproto/jetstream.clj:140-159`):**

- Firehose: `(firehose/consume {:service "wss://bsky.network" :handler prn ...})` — observe commits; also a PDS host (e.g. a personal PDS) for low-volume testing.
- Jetstream: existing comment block against `jetstream1.us-east.bsky.network`, plus `:typed?` and `:compress?`.
- Tap: run `indigo` `cmd/tap` locally (instructions link in ns docstring), `add-repos!` a known DID, observe backfill-then-live ordering and `:live` flags.
- Backfill: `list-records-walk` against a public PDS for a small repo; compare record count with `describeRepo`/`listRecords` totals.

## Acceptance criteria

- [x] `clj -X:test` green at every milestone PR; no reflection warnings in new namespaces (`*warn-on-reflection*` set like `src/atproto/runtime/http.cljc:16`).
- [x] `atproto.sync.frame` round-trips all generated `frames/*.bin` fixtures byte-for-byte and rejects malformed frames with `{:error "InvalidFrame"}`.
- [x] `atproto.sync.car/read-car` passes every entry in vendored `car-file-fixtures.json` (root + all block CIDs/bytes).
- [x] Firehose `consume` against the in-process replay server delivers `:create`/`:update`/`:delete`/`:sync`/`:identity`/`:account` events with the documented shapes; records extracted from commit CARs match fixture records.
- [x] Cursor: `CursorStore` consulted on every (re)connect (`?cursor=` present in the URL), written only after handler completion; with `memory-runner`, cursor never exceeds the highest consecutive completed seq (property test).
- [x] Seq gaps emit `:gap` events + `cast/metric`; error frames surface as `{:error "FutureCursor"|"ConsumerTooSlow" ...}` via `on-error`.
- [x] `:resolve-identity?` (default off): when true, `:identity` events are enriched with the resolved DID doc and a bidirectionally verified handle via `atproto.identity/resolve-identity` (port of `firehose/index.ts:336-368`); an unverifiable handle is omitted from the event (not an error), matching the reference. Covered by a test with a stubbed resolver (verified, unverifiable, and resolution-failure cases).
- [x] Reconnect uses exponential backoff with jitter capped at `:max-reconnect-ms`, never blocks a core.async go thread, and stops cleanly on `stop!`/clean server close.
- [x] Tap channel: events parsed per `parseTapEvent` semantics; acks `{"type":"ack","id":n}` sent post-handler, withheld on handler error, buffered across reconnects and flushed in order.
- [x] Tap webhook Ring handler: 401 on bad/missing shared-secret auth (timing-safe compare), 200 + handler call on valid events, 400/500 as specified; composes with other Ring handlers (returns nil off-path).
- [x] Jetstream: existing `consume` channel API unchanged (statusphere ingester compiles unmodified); `:typed?` mode yields `:identity`/`:account` events (no longer silently droppable); `:cursor-store` round-trips across a forced reconnect.
- [x] Jetstream `:compress?` (zstd) is **conditional scope per Risk 3 and excluded from acceptance**: if the dictionary/licensing question resolves and it ships, a canned zstd-compressed fixture must decode to the same events as JSON mode and `:compress?` without the `:zstd` alias must return `{:error "UnsupportedOption"}`; if it is dropped, this workstream is still complete.
- [x] `backfill/list-records-walk` paginates a stub PDS of >100 records completely, emitting `:live false` create events.
- [x] `backfill/backfill-repo` enumerates collections from a stubbed `com.atproto.repo.describeRepo`, honors `:filter-collections`, runs `list-records-walk` per collection, and reports `{:count n :collections [...]}` in the final callback.
- [x] Verified mode + `get-repo-walk` (final milestone, only if WS-04 has merged): commit events failing proof verification are dropped with `on-error`, with one forced key-refresh retry.
- [x] All public fns follow SDK conventions: trailing opts via `platform-async`, `{:error ...}` maps, specs for config maps and event shapes.

## Milestones

Each is one PR, independently mergeable, build green.

1. **`atproto.runtime.ws`** + http-kit test server + `ws_test`. No WS-02 dependency. (Pure addition; nothing else changes.)
2. **`atproto.sync.frame` + `atproto.sync.cursor`** + vendored/generated fixtures + cbor stub under `test/`. Frame tests run against the stub until WS-02 merges (gate with a `requiring-resolve` check or test selector so CI is green either way).
3. **`atproto.sync.firehose` raw mode**: connect, decode, `$type`, optional validation, seq-gap detection, cursor store, error frames; `#commit` events carry raw `:blocks` bytes + `:ops` (no record extraction yet); `#account`/`#identity`/`#info` fully parsed.
4. **`atproto.sync.car` + commit/sync record extraction** (unverified): commit fan-out to `:create`/`:update`/`:delete` with `:record`, `parse-sync-event`, collection filtering, `:resolve-identity?` enrichment.
5. **`atproto.sync.runner`** (consecutive-list + memory runner) + firehose `:runner` option.
6. **Tap part 1**: `atproto.tap.auth` + `atproto.tap.events` + `atproto.tap.client` (admin HTTP + channel with acks).
7. **Tap part 2**: `atproto.tap.webhook` Ring handler.
8. **Jetstream upgrades**: rebase onto `atproto.runtime.ws`, `:cursor-store`, `:typed?`; zstd as a follow-up commit in the same PR if the dictionary/licensing question (below) is resolved, else its own PR.
9. **`atproto.sync.backfill/list-records-walk` + `backfill-repo`**.
10. **WS-04-gated finale** (only after WS-04 merges): `parse-commit-verified` (+ firehose `:verify?` option defaulting to off), `backfill/get-repo-walk`, swap/delete provisional `atproto.sync.car` per the WS-04 agreement.

## Risks & open questions

1. **CAR reader ownership (WS-04 overlap).** Recommendation (encoded in File ownership): WS-05 ships the minimal read-only `atproto.sync.car` because unverified commit parsing needs it long before WS-04 lands; single call site makes later replacement trivial. **Needs orchestrator sign-off so WS-04's plan says the same thing.**
2. **WS-02 API drift.** `decode-multi` name/shape is assumed, not frozen. Mitigation: stub under `test/`, single require site, rename at rebase. If WS-02 decodes tag 42 to something other than `multiformats.cid` objects, `atproto.sync.car` and commit parsing must adapt — flag early in WS-02 review.
3. **Jetstream zstd**: requires `com.github.luben/zstd-jni` (native lib) **and** Jetstream's custom dictionary, which lives in the external `bluesky-social/jetstream` repo (`pkg/models/zstd_dictionary` — verify path/license at implementation time; it is not in the local reference checkout). Recommendation: optional `:zstd` deps.edn alias + dynamic `requiring-resolve`, dictionary vendored under `resources/atproto/jetstream/` with attribution; ship last, and drop from scope if licensing is unclear (plain JSON mode is the default anyway).
4. **Seq semantics**: relay seqs are increasing but not guaranteed contiguous (spec allows gaps, e.g. filtered/aborted events). Hard-erroring on gaps would be wrong. Recommendation (as specced): gaps are observable (`:gap` event + metric), regressions are `on-error`; consumers decide. Document this clearly in the ns docstring.
5. **Backpressure**: `java.net.http.WebSocket` uses explicit `request(n)` demand (jetstream.clj already requests 1-at-a-time, `src/atproto/jetstream.clj:30`). Keep 1-at-a-time request driven by handler completion for the firehose (matches the TS serial default and gives natural backpressure; the relay disconnects slow consumers with `ConsumerTooSlow`, which we surface and auto-reconnect from cursor). Risk: per-message `request(1)` may throttle throughput on busy relays — if so, raise the demand window and buffer; note in code.
6. **Event key style**: this plan keeps wire camelCase for lexicon fields and kebab-case for envelope keys (`:did`, `:collection`, `:active`). The Tap wire field `is_active` flattens to `:active` to match the firehose `#account` field name. If the orchestrator prefers strict wire fidelity (`:isActive`), it's a one-line change in `parse-tap-event` — decide before Milestone 6 freezes the Tap event shape.
7. **CLJS**: `atproto.runtime.ws` is specced `.cljc` with `:clj`-only implementation (cljs would use `js/WebSocket`; browsers can't set ping or custom headers, which affects Tap auth — likely needs query-param auth upstream). Recommendation: JVM-only now; leave `{:error "NotImplemented"}` stubs like `atproto.identity/fetch-did-doc "web"` does (`src/atproto/identity.cljc:110-112`).
8. **Tap is young** (`@atproto/tap` 0.3.0; protocol owned by indigo `cmd/tap`). The ack protocol or event schema may change. Mitigation: pin behavior to the reference at commit `b9ef557`, keep `parse-tap-event` lenient on unknown fields, and isolate wire shapes in `atproto.tap.events`.
9. **http-kit WS server for tests**: confirm http-kit 2.8.0 sends *binary* frames when handed `byte[]` (expected; if not, fall back to a minimal Jetty/JDK test server in `test/atproto/support/ws_server.clj` — test-only dep, no shipped-code impact).
