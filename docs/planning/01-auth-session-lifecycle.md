# WS-01: Auth & Session Lifecycle

| | |
|---|---|
| **Status** | Planning |
| **Priority** | P0 |
| **Estimated size** | M |
| **Branch** | ws/01-auth-session-lifecycle |
| **Depends on** | none |
| **Blocks** | WS-07 (touches `src/atproto/xrpc/client.cljc`; WS-01 lands first, WS-07 rebases). Any workstream that needs long-lived authenticated clients (sync, repo writes, examples). |

## Goal

Long-lived authenticated sessions work end-to-end for both auth mechanisms. A credentials
(identifier/password) session and an OAuth (DPoP) session both transparently refresh their tokens
when the server rejects an access token, retry the failed request exactly once, persist refreshed
OAuth tokens to the session store, and surface `{:error ...}` maps (never hang) when refresh fails.
OAuth sessions can be revoked (server-side best-effort + local store deletion), pending-authorization
state entries expire, and the statusphere example has a working logout.

## Current state

All paths are in `/Users/luke/github/atproto-clj/atproto-clj` unless noted. Line numbers verified
against branch `redesign` at commit `2f12811`.

### How auth is wired today

- `src/atproto/xrpc/client.cljc:38-41` defines the `Session` protocol (`:extend-via-metadata true`)
  with two methods: `(auth-interceptor [session])` and `(refresh-token [session cb])`.
- `src/atproto/xrpc/client.cljc:18-25` — `init` builds a client map `{:service ... :session (atom session) :validate-requests? ...}`.
  It throws `"A service or a session is required."` (line 22) when given neither `:service` nor `:session`.
- `src/atproto/xrpc/client.cljc:48-67` — `delegate-auth-interceptor` conses the session's
  auth interceptor onto the queue on `::i/enter`, and on `::i/leave` calls `refresh-token` when
  `expired-token-error?` matches and `(:refresh? @session)` is false, then either errors or retries
  by continuing with `(dissoc ctx ::i/response)`.
- Two session constructors implement the protocol via metadata:
  - credentials: `src/atproto/credentials.cljc:10-19` (private fn `session`).
  - OAuth: `src/atproto/oauth/client.cljc:26-36` (private fn `oauth-session`), wiring
    `` `xrpc-client/refresh-token `` to `#(refresh-session client %1 %2)` (line 36).

### Bug 1 — credentials refresh constructs the XRPC client wrong (hard crash)

`src/atproto/credentials.cljc:49-58`:

- Line 51: `(xrpc-client/init (assoc session :refresh? true))` passes the *session map itself* as
  the init config. The session map (`:accessJwt`, `:refreshJwt`, `:did`, `:pds`, `:handle`) has
  neither `:service` nor `:session`, so `init` throws `"A service or a session is required."`
  (`src/atproto/xrpc/client.cljc:21-22`). The intent was
  `(xrpc-client/init {:session (assoc session :refresh? true)})` — the `:refresh?` flag is what makes
  `auth-interceptor` (`credentials.cljc:60-66`) send the `refreshJwt`, and what stops
  `delegate-auth-interceptor` from recursing (`xrpc/client.cljc:59`).
- Lines 50, 57-58: the parameter `session` shadows the private session-constructor fn defined at
  `credentials.cljc:10`. `(session resp)` is therefore a *map lookup* (session map invoked with
  `resp` as key) returning `nil`, so `(merge session (session resp))` returns the stale session and
  the new `accessJwt`/`refreshJwt` are silently dropped (the metadata-attached protocol impls would
  also be lost if it did work, since `merge` is applied to the plain old map).

### Bug 2 — OAuth refresh is unimplemented and the caller hangs

- `src/atproto/oauth/client.cljc:372-374`:
  ```clojure
  (defn refresh-session
    [client session cb]
    'todo)
  ```
  Never invokes `cb`. The namespace header (`client.cljc:17-18`) reads `;; todo: - implement (auto-)refresh`.
- Why this *hangs* instead of erroring: `delegate-auth-interceptor`'s `::i/leave` fn
  (`xrpc/client.cljc:56-67`) returns whatever `refresh-token` returns — here the symbol `'todo`.
  `try-invoke` (`src/atproto/runtime/interceptor.cljc:53-80`) treats any non-nil, non-context return
  as an error and replaces the whole context with `{::request ::missing ::response {:error "InvalidInterceptorContext" ...}}`
  (lines 62-69) — a context with **no `::stack`**. `leave` (interceptor.cljc:82-90) then sees an
  empty stack and stops, so the `final` interceptor installed by `execute` (interceptor.cljc:163-167)
  never fires and the caller's promise is never delivered. Result: any XRPC call through an OAuth
  session that gets an `ExpiredToken` response blocks forever.

### Bug 3 — refresh-failure path delivers a bare string, and the retry path corrupts the body

`src/atproto/xrpc/client.cljc:60-66`:

- Error branch: `(i/continue (assoc ctx ::i/response error))` where `error` is the destructured
  *string* `(:error new-session)`, not the error map. Downstream leave fns
  (`procedure-interceptor`'s `handle-xrpc-response`, `xrpc/client.cljc:85-91`) then destructure a
  string and produce `{:error "HTTP_" :http-response "..."}` garbage. Must pass the full error map.
- Retry branch: `(i/continue (dissoc ctx ::i/response))` re-enters the queue
  `[delegate-auth atproto-json json http]` with the *already-serialized* request still in
  `::i/request`. `json/client-interceptor`'s enter (`src/atproto/runtime/json.cljc:30-36`) will
  `write-str` the body **again**, double-encoding any JSON procedure body on retry. The fix is the
  pattern already used by the DPoP interceptor (`src/atproto/oauth/client/dpop.cljc:44`,
  `::original-ctx`): snapshot the pristine request on `::i/enter` and restore it before retrying.
- No retry-once guard: if the retried request fails with `ExpiredToken` again, the leave fn will
  trigger another refresh, looping. The TS clients return the second response unconditionally
  (`packages/api/src/atp-agent.ts:248-265`).

### Bug 4 — brittle ExpiredToken detection

`src/atproto/xrpc/client.cljc:43-46`:

```clojure
(defn expired-token-error?
  [{:keys [headers body]}]
  (and (= "application/json" (:content-type headers))
       (= "ExpiredToken" (:error body))))
```

- Exact string match on content-type fails for `"application/json; charset=utf-8"` (note that
  `atproto.runtime.json/json-content-type?`, `src/atproto/runtime/json.cljc:12-17`, already
  prefix-matches; this fn does not).
- Ignores `:status` entirely.
- Never matches OAuth/DPoP rejections at all: a PDS rejecting a DPoP access token responds
  `401` with a `WWW-Authenticate: DPoP ... error="invalid_token"` header and no `ExpiredToken` JSON
  body (see reference: `packages/oauth/oauth-client/src/oauth-session.ts:162-170` and
  `packages/pds/src/auth-verifier.ts:350-387`). The bearer-token path responds `400` with body
  `{"error": "ExpiredToken"}` (`packages/pds/src/auth-verifier.ts:475`).

### Bug 5 — `restore` hangs when the session is not in the store

`src/atproto/oauth/client.cljc:364-370`: the `(when-let [session-data ...] (cb ...))` means `cb` is
never called for an unknown DID; the returned promise/channel never gets a value.

### Missing features

- **No OAuth token revocation / logout.** Reference: `oauth-client.ts:507-531` (`revoke`),
  `oauth-server-agent.ts:86-92` (best-effort POST to `revocation_endpoint`),
  `oauth-session.ts:85-95` (`signOut`). The `Store` protocol already has `del*`
  (`src/atproto/oauth/client/store.clj:6-10`) and is used for state deletion at
  `src/atproto/oauth/client.cljc:319`, but nothing ever deletes a *session* entry.
- **No credentials logout** (`com.atproto.server.deleteSession`, authenticated with the
  `refreshJwt`; reference `packages/api/src/atp-agent.ts:343-358`).
- **No state-store TTL/cleanup.** State entries are written by the PAR interceptor
  (`src/atproto/oauth/client.cljc:200-206`) and only deleted when the callback arrives
  (`client.cljc:319`). Abandoned flows accumulate forever. `store.clj:5` has a
  `;; todo: should we make this async?` note (async-ification is *out of scope* here, see below).
- **No persisted issuer / expiry metadata.** The session data persisted at
  `src/atproto/oauth/client.cljc:353-358` is `(merge identity {:did did :tokens resp :dpop-key dpop-key})`.
  `resp` (the token response + `:aud` added at `client.cljc:300-305`) contains no `:iss` and no
  computed `:expires-at`, and the issuer-metadata cache is an in-memory atom (`client.cljc:68,72-78`)
  that is empty after a process restart — so a restored session currently has no way to find its
  `token_endpoint`. Refresh requires persisting `:iss` and (re-)fetching issuer metadata on demand.
- **No single-flight refresh.** N concurrent requests through one client that all observe an
  expired token would each call `refresh-token`. OAuth refresh tokens are single-use
  (reuse → `400 invalid_grant`); the PDS bearer refresh JWT has a 2-hour rotation grace period
  (`packages/pds/src/account-manager/account-manager.ts:434-440`) but concurrent refresh is still
  wasteful and racy.
- **statusphere logout is a stub**: `examples/statusphere/src/xyz/statusphere/server.clj:48-51`
  clears the ring cookie session with `;; todo: destroy oauth session`.

## Reference implementation guide

All paths below are in `/Users/luke/github/bluesky-social/atproto`.

### `packages/oauth/oauth-client` — the OAuth session lifecycle to port

| Concern | File / lines | What it does |
|---|---|---|
| Token set shape | `src/oauth-server-agent.ts:31-42` | `TokenSet = {iss, sub, aud, scope, refresh_token?, access_token, token_type:'DPoP', expires_at?}` — this is the canonical persisted shape; note `iss` and ISO `expires_at` are stored, computed from `expires_in` at exchange/refresh time (lines 127-131, 171-175). |
| Refresh grant | `src/oauth-server-agent.ts:139-176` | `refresh`: error if no `refresh_token`; **re-verify issuer for the sub before refreshing** (`verifyIssuer`, lines 188-203: resolve identity fresh, require resolved issuer == stored issuer, returns the PDS URL used as `aud`); POST `{grant_type: "refresh_token", refresh_token}` to the `token_endpoint`; rebuild the TokenSet keeping the original `sub`. |
| Token/revocation request plumbing | `src/oauth-server-agent.ts:205-253` | One generic `request(endpoint, payload)`: looks up `${endpoint}_endpoint` in server metadata, merges client-auth credentials (`client_id` / `client_assertion`), DPoP-signs, posts `application/x-www-form-urlencoded`. The Clojure `exchange-code` (`atproto-clj src/atproto/oauth/client.cljc:264-305`) already does the same with a JSON body — reuse that plumbing (see Risks for content-type discussion). |
| Revocation | `src/oauth-server-agent.ts:86-92` | `revoke(token)`: POST `{token}` to revocation endpoint, **swallow all errors** (best-effort). |
| Client-level revoke | `src/oauth-client.ts:507-531` | `revoke(sub)`: read session from store (no refresh); try server revocation of the `access_token`; `finally` delete from session store. Local deletion happens even if the network call fails. |
| Sign-out from a session | `src/oauth-session.ts:85-95` | `signOut`: same, from the session object. |
| Invalid-token detection (resource server) | `src/oauth-session.ts:162-170` | `isInvalidTokenResponse`: status 401 + `WWW-Authenticate` starting with `Bearer ` or `DPoP ` and containing `error="invalid_token"`. |
| Retry-once semantics | `src/oauth-session.ts:97-155` | `fetchHandler`: initial request → if invalid-token, force refresh → retry once → if *still* invalid-token, delete the stored session and return the response. Never loops. |
| Single-flight + stale detection + concurrency recovery | `src/session-getter.ts:55-214` | Refresh runs under a per-sub lock (`runtime.usingLock`, local impl `src/lock.ts:25-33`); `isStale` (lines 175-187) treats tokens expiring within 10s (+0-30s jitter) as stale; on `400 invalid_grant` (lines 131-168) it waits ~1s, re-reads the store, and if another process stored *different* tokens it adopts them ("pretend this one succeeded"), otherwise deletes the session and raises `TokenRefreshError`. Sub mismatch checks at lines 85-88, 110-113. |
| Refresh failure ⇒ session deletion | `src/session-getter.ts:36-46, 211` | `deleteOnError`: TokenRefreshError/TokenRevokedError/TokenInvalidError delete the stored session. |
| State store contract & TTL | `src/state-store.ts` (whole file, note at lines 18-22) and `packages/oauth/oauth-client-node/README.md:259-261` | State entries are short-lived; implementations should expire them ("one hour should be more than enough"). |
| Orphan-session hygiene in callback | `src/oauth-client.ts:440-461` | Callback revokes any pre-existing session for the same sub before storing the new one; revokes the new tokens if storing fails. (Port the first part; the `onStoreError` path is optional hardening.) |

### `packages/api` — credentials (bearer JWT) session management

- `src/atp-agent.ts:204-265` (`CredentialSession.fetchHandler`): expired detection is
  `status === 401 || (status === 400 && body.error === 'ExpiredToken')` (lines 222-224); waits for any
  in-flight refresh before issuing requests (line 206); retries once with the new token; returns the
  original response if refresh fails or the token didn't change.
- `src/atp-agent.ts:400-417` (`refreshSession`): promise-guard single-flight — one in-flight refresh
  promise shared by all callers.
- `src/atp-agent.ts:422-507` (`_refreshSessionInner`): calls `com.atproto.server.refreshSession`
  with `authorization: Bearer <refreshJwt>`; verifies the DID is unchanged (lines 436-442); on
  401/`ExpiredToken`/`InvalidToken` failure clears the session ("expired"), otherwise keeps it
  ("network-error", lines 486-503).
- `src/atp-agent.ts:343-358` (`logout`): `com.atproto.server.deleteSession` with
  `authorization: Bearer <refreshJwt>`, errors ignored, session cleared in `finally`.

### Server-side behavior worth knowing (don't port, just rely on)

- `packages/pds/src/auth-verifier.ts:475` — expired bearer access JWT ⇒ `InvalidRequestError`
  (HTTP 400) with error code `ExpiredToken`; `auth-verifier.ts:350-387` — OAuth/DPoP failures ⇒
  RFC-style 401 with `WWW-Authenticate` (and `DPoP-Nonce` header handling).
- `packages/pds/src/account-manager/account-manager.ts:434-440` — rotated bearer refresh tokens stay
  valid for a 2-hour grace period (`REFRESH_GRACE_MS`), which makes cross-process credential refresh
  races survivable.
- `packages/oauth/oauth-provider/src/router/create-oauth-middleware.ts:135,167` +
  `packages/oauth/oauth-provider/src/lib/http/parser.ts:56,72-73` — the reference AS accepts **both**
  `application/json` and `application/x-www-form-urlencoded` bodies on the token *and* revocation
  endpoints, which is why the existing JSON-body `exchange-code` works.
- `lexicons/com/atproto/server/refreshSession.json` — refresh output: required
  `accessJwt`, `refreshJwt`, `handle`, `did`; optional `didDoc`, `active`, `status`; errors include
  `ExpiredToken`/`InvalidToken`. `lexicons/com/atproto/server/deleteSession.json` — no body, refresh
  JWT auth.

### Interop test fixtures

`/Users/luke/github/bluesky-social/atproto/interop-test-files/` contains only `crypto/` and
`syntax/` fixtures — **there are no OAuth or session-lifecycle interop fixtures**. The oauth-client
package has no test files either (verified: no `*.test.ts` under `packages/oauth/oauth-client/`).
Tests for this workstream are therefore built on stubbed HTTP responses (see Test plan). The only
vendorable files are lexicon JSONs (listed in Test plan).

## Scope

### In scope

- Fix `atproto.credentials/xrpc-refresh-session` (`credentials.cljc:49-58`): correct `init` call
  (`{:session (assoc session :refresh? true)}`), eliminate the `session` shadowing, rebuild the new
  session via the metadata-attaching constructor, preserve `:pds`/`:handle` when the refresh
  response omits `didDoc`/`handle`, verify the DID is unchanged.
- Fix `delegate-auth-interceptor` (`xrpc/client.cljc:48-67`): propagate the full error map on
  refresh failure (never a bare string, never hang); snapshot the request at `::i/enter` and restore
  it for the retry (fixes JSON double-encoding); retry at most once per logical request; explicitly
  return `nil` from the leave fn after calling `i/continue` asynchronously.
- Harden expired/invalid-token detection (`xrpc/client.cljc:43-46`): status-aware, content-type
  prefix match, and 401 + `WWW-Authenticate ... error="invalid_token"` (DPoP/Bearer) per
  `oauth-session.ts:162-170` and `atp-agent.ts:222-224`.
- In-process single-flight refresh at the XRPC-client level: concurrent requests through one client
  that all hit an invalid token trigger exactly one `refresh-token` call; the rest queue on it.
- Implement the OAuth `refresh_token` grant in `atproto.oauth.client` (DPoP-bound, reusing the
  `exchange-code` token-endpoint plumbing at `client.cljc:264-305`): re-verify issuer before
  refresh; persist `:iss` and computed `:expires-at` in the stored token map (at `callback` time
  too); single-flight per DID across XRPC clients sharing one OAuth client; `invalid_grant`
  concurrency recovery (re-read store, adopt foreign refresh, else delete session + error) per
  `session-getter.ts:131-168`; persist refreshed tokens via `store/set` *before* delivering the new
  session.
- On-demand issuer-metadata (re)fetch keyed by `:iss` so refresh/revoke work after process restart
  (today the `:issuers` atom is only populated during `authorize`).
- Implement OAuth revocation: `atproto.oauth.client/revoke` — best-effort POST to the AS
  `revocation_endpoint` (skip silently if the metadata has none), then unconditionally
  `store/del` the session; also make `callback` revoke any pre-existing session for the same DID
  before storing the new one (`oauth-client.ts:440-446`).
- Fix `restore` (`client.cljc:364-370`) to deliver `{:error "SessionNotFound" ...}` instead of
  hanging; tolerate legacy stored sessions missing `:iss`/`:expires-at` (resolve lazily).
- Credentials logout: `atproto.credentials/logout` calling `com.atproto.server.deleteSession` with
  the refresh JWT, errors ignored (parity with `atp-agent.ts:343-358`).
- State-store TTL: stamp `:created-at` into the state entry written by the PAR interceptor
  (`client.cljc:200-206`); `validate-callback-params` (`client.cljc:307-332`) rejects and deletes
  entries older than 1 hour; `memory-store` lazily evicts expired state entries; document that
  custom `Store` implementations should expire state rows (~1h) like the statusphere SQL store.
- Update statusphere logout (`examples/statusphere/src/xyz/statusphere/server.clj:48-51`) to call
  `oauth-client/revoke` for the logged-in DID before clearing the ring session.
- Specs (`clojure.spec`) for the session shapes, token map, and the public fn inputs added here.

### Out of scope

- **Making the `Store` protocol async** (`store.clj:5` todo). Changing `get*`/`set*`/`del*` to
  callback-style breaks the statusphere store implementations and is an independent interface
  decision. Record as an open question for a future workstream; everything here keeps the
  synchronous protocol.
- **Proactive pre-request refresh based on `:expires-at`** (the TS `isStale` path,
  `session-getter.ts:175-187`). We persist `:expires-at` so this can be added later, but the only
  refresh *trigger* in this workstream is a server invalid-token response (plus explicit
  `refresh`/`restore :refresh true`). Doing staleness checks on every request touches the hot path
  WS-07 is reworking.
- **Cross-process distributed locking** for refresh (TS `runtime.requestLock`). We implement
  in-process single-flight + the `invalid_grant` recovery dance; a pluggable lock is future work.
- **DPoP nonce store rework** (`dpop.cljc:11` global atom) and any other DPoP changes — the existing
  interceptor is reused as-is.
- **XRPC client ergonomics** (pagination, timeouts, generic retry, error taxonomy beyond what's
  needed here) — WS-07 owns `xrpc/client.cljc` productionization; this workstream only changes
  `init`, the `Session` protocol surface, `expired-token-error?` → `invalid-token-response?`, and
  `delegate-auth-interceptor`.
- **`com.atproto.server.getSession` backfill after refresh** (`atp-agent.ts:444-462`) — optional
  nicety; refresh output already includes `did`/`handle`.
- **ClojureScript/ClojureDart parity for the OAuth client.** `atproto.oauth.client` is `.cljc` but
  depends on JVM-only `store.clj` and the Nimbus-backed `jwt.cljc`; this workstream keeps the
  existing platform support level (JVM-tested), writing platform-neutral code where free.

## Deliverables

### `atproto.xrpc.client` (modified)

```clojure
(defn init
  "Initialize a new XRPC client and return it.

  config keys: :service, :session, :validate-requests? (unchanged), plus the
  returned client now carries ::refresh-state (atom) used to single-flight
  token refreshes. Throws if neither :service nor :session is provided."
  [{:keys [service session validate-requests?] :as config}])

(defprotocol Session
  :extend-via-metadata true
  (auth-interceptor [session]
    "Interceptor to authenticate HTTP requests.")
  (refresh-token [session cb]
    "Refresh the session's tokens.
     MUST eventually call cb exactly once with either a NEW session value
     (satisfying Session, with refreshed tokens) or an {:error ...} map.
     Implementations must never throw out of the calling thread without
     invoking cb."))

(defn invalid-token-response?
  "True if this HTTP response indicates the access token was rejected and a
  refresh should be attempted. Matches:
  - 400 with a JSON body (content-type prefix \"application/json\") whose
    :error is \"ExpiredToken\"
  - 401 with no WWW-Authenticate header (bearer-auth convention, see
    atp-agent.ts:222-224)
  - 401 whose WWW-Authenticate starts with \"Bearer \" or \"DPoP \" and
    contains error=\"invalid_token\" (RFC 6750/9449)."
  [{:keys [status headers body] :as http-response}])

;; Kept as a deprecated alias of invalid-token-response? so WS-07 rebases
;; cleanly; remove in a later cleanup.
(defn expired-token-error? [http-response])

(defn delegate-auth-interceptor
  "Delegate authentication to the session, if any.

  ::i/enter snapshots the pristine ::i/request under ::auth-retry-request and
  conses the session's auth-interceptor.

  ::i/leave, when (invalid-token-response? response) and the session is not
  itself a refresh client (:refresh?) and this request has not already been
  retried (::auth-retried?):
  - joins the client's single-flight refresh (::refresh-state): the first
    caller invokes (refresh-token @session f); concurrent callers enqueue.
  - on refresh success: (reset! session new-session), then retries by
    continuing with the snapshotted request, ::auth-retried? true, and no
    ::i/response. The retried request gets a fresh auth-interceptor from the
    NEW session.
  - on refresh failure: continues with ::i/response set to the FULL error map
    from the refresh callback (e.g. {:error \"TokenRefreshError\" :message ...}).
  - the leave fn returns nil after handing off to i/continue.
  A retried request that fails again with an invalid-token response is
  returned to the caller as-is (no second refresh)."
  [{:keys [session] :as client}])
```

Single-flight sketch (in-process, per client): `::refresh-state` is
`(atom nil)`; a waiter does
`(swap! refresh-state (fn [st] (if st (update st :waiters conj cb) {:waiters [cb]})))`
and the caller that installed the map performs the refresh; on completion it
`(reset! refresh-state nil)` and invokes all `:waiters` with the result. Pure
atom + callbacks; no threads, no core.async requirement, works for both session types.

### `atproto.credentials` (modified)

```clojure
(defn- session
  "Build a session map (with Session protocol metadata) from a
  createSession/refreshSession response, falling back to `prev` for
  :pds/:handle/:did when the response omits didDoc/handle."
  ([resp] (session nil resp))
  ([prev {:keys [did didDoc handle accessJwt refreshJwt] :as resp}]))

(defn- xrpc-refresh-session
  "Session protocol impl. POSTs com.atproto.server.refreshSession through a
  refresh-mode XRPC client: (xrpc-client/init {:session (assoc sess :refresh? true)}).
  cb receives the rebuilt session (new accessJwt/refreshJwt, metadata
  reattached) or {:error ...}. Delivers {:error \"InvalidDID\" ...} if the
  response :did differs from the current session's."
  [sess cb])

(defn create
  "Unchanged public signature."
  [credentials & {:as opts}])

(defn logout
  "Invalidate the session server-side (com.atproto.server.deleteSession,
  authenticated with the refreshJwt). Server errors are reported in the result
  but the session should be considered dead regardless.
  Async via :callback/:promise/:channel opts; resolves to {} or {:error ...}."
  [session & {:as opts}])
```

### `atproto.oauth.client` (modified)

Persisted session-store value (JSON via `store/set`, key = DID) gains fields; spec it:

```clojure
;; {:did "did:plc:..."
;;  :handle "..."            ; optional
;;  :did-doc {...}           ; from identity resolution
;;  :dpop-key {...}          ; private JWK map (JSON round-trips already)
;;  :iss "https://..."       ; NEW: issuer URL (TokenSet.iss)
;;  :tokens {:access_token "..." :refresh_token "..." :token_type "DPoP"
;;           :scope "..." :sub "did:..." :aud "https://pds..."
;;           :expires-at 1760000000}}  ; NEW: epoch seconds, from expires_in
```

```clojure
(defn- issuer-metadata
  "Authorization-server metadata for `iss`: the cached entry from (:issuers
  client), or fetched from <iss>/.well-known/oauth-authorization-server
  (reusing fetch-asmd), validated ((:issuer asmd) must equal iss), and cached.
  cb gets the metadata map or {:error ...}. Makes refresh/revoke work after
  process restart."
  [client iss cb])

(defn- token-request
  "POST `params` merged with (client-auth client issuer) to the issuer's
  token_endpoint, DPoP-signed with dpop-key. Same interceptor chain as
  exchange-code (dpop/interceptor + json + http). cb gets the parsed token
  response body or {:error ...} (OAuth error bodies pass through as
  {:error \"invalid_grant\" ...} etc.)."
  [client {:keys [issuer dpop-key]} params cb])

(defn- tokens+expiry
  "Normalize a token response: keep access_token/refresh_token/token_type/
  scope/sub, add :aud and :iss, compute :expires-at from :expires_in using
  crypto/now. Used by both exchange-code and refresh." 
  [issuer aud token-response])

(defn- refresh-session
  "Session-protocol refresh for OAuth sessions (wired in oauth-session meta).
  Single-flighted per DID via a ::refresh-inflight atom on the client (created
  in `create`). Algorithm (port of session-getter.ts:63-172 +
  oauth-server-agent.ts:139-203):
  1. Re-read the stored session for (:did session) — source of truth. Missing
     => {:error \"TokenRefreshError\" :message \"Session deleted\"} (cb'd to
     all waiters).
  2. No :refresh_token => {:error \"TokenRefreshError\" :message \"No refresh
     token available\"} and store/del.
  3. resolve the DID; require resolved issuer == stored :iss
     (=> {:error \"TokenRefreshError\" :message \"Issuer mismatch\"} + store/del
     on mismatch); resolved PDS becomes the new :aud.
  4. issuer-metadata; token-request {:grant_type \"refresh_token\"
     :refresh_token ...}.
  5. Success: new session-data = old session-data with (tokens+expiry ...) and
     updated :did-doc; store/set FIRST, then cb (oauth-session client data).
  6. Failure with :error \"invalid_grant\": wait ~1s, re-read store; if the
     stored access/refresh tokens differ from the ones we used, adopt the
     stored session (another process refreshed); otherwise store/del and
     return {:error \"TokenRefreshError\" :message <error_description>}.
  7. Other failures (network, 5xx): return the error map, do NOT delete the
     stored session."
  [client session cb])

(defn refresh
  "Force-refresh the stored session for this DID. Returns (async) the
  refreshed session or {:error ...}. Public escape hatch / used by tests."
  [client did & {:as opts}])

(defn restore
  "The OAuth session for this did. Resolves to the session or
  {:error \"SessionNotFound\" :did did} (FIX: never silently hangs).
  Tolerates legacy stored sessions without :iss/:expires-at."
  [client did & {:as opts}])

(defn revoke
  "Sign out: best-effort revocation at the AS, then delete the local session.
  1. Read session from store; if none, resolve {:did did :revoked false}.
  2. issuer-metadata for :iss; if metadata has a :revocation_endpoint, POST
     {:token access-token} with client-auth + DPoP; ALL errors ignored
     (oauth-server-agent.ts:86-92).
  3. Unconditionally (store/del (:session-store client) did).
  Resolves to {:did did :revoked true} or {:error ...} only for store
  failures."
  [client did & {:as opts}])

(defn callback
  "Unchanged signature. Now: stores :iss and :tokens with :expires-at;
  revokes any pre-existing session for the same DID before storing
  (oauth-client.ts:440-446); state entries older than 1h are rejected as
  {:error \"StateExpired\"} and deleted."
  [client params & {:as opts}])
```

### `atproto.oauth.client.store` (modified)

```clojure
;; Protocol unchanged (synchronous; async-ification explicitly deferred).
(defprotocol Store ...)

(defn memory-store
  "As today, plus: entries written via `set` with :expires-at metadata are
  lazily evicted on read. Used by the default state-store; the client stamps
  :created-at into state values and enforces the 1h max age, so custom stores
  need no TTL support for correctness — only for hygiene (document this on
  the protocol docstring, mirroring oauth-client-node README:259-261)."
  ([] ...) ([store-atom] ...))
```

(If lazy eviction inside `memory-store` proves awkward without changing the
protocol, the fallback is: TTL enforcement lives entirely in
`validate-callback-params` + a `sweep-state!` helper the client calls
opportunistically from `authorize`. Either way the `Store` protocol is frozen.)

### `examples/statusphere/src/xyz/statusphere/server.clj` (modified)

`POST /logout` becomes:

```clojure
(POST "/logout" req
  (when-let [did (get-in req [:session :did])]
    @(oauth-client/revoke (:oauth-client (:app-ctx req)) did))
  (assoc (r/redirect "/") :session nil))
```

### Error vocabulary (all `{:error <Name> :message <human> ...}` maps)

| `:error` | Produced by | Meaning |
|---|---|---|
| `"TokenRefreshError"` | oauth refresh, credentials refresh | refresh definitively failed; stored session deleted (oauth) / session dead |
| `"SessionNotFound"` | `restore`, `refresh` | no stored session for DID |
| `"StateExpired"` / `"Unknown state"` | `callback` | state entry expired / missing |
| `"InvalidDID"` | credentials refresh | DID changed across refresh |
| passthrough HTTP/OAuth errors | `token-request` etc. | transient failures; session retained |

## Interface contract

### Frozen for consumers (WS-07 and app code)

- `atproto.xrpc.client/Session` protocol: exactly `auth-interceptor [session]` and
  `refresh-token [session cb]`, metadata-extensible, with the cb contract documented above
  (new session value or `{:error ...}`, called exactly once).
- `atproto.xrpc.client/init` accepted config keys `:service` / `:session` / `:validate-requests?`
  are unchanged; the client map keeps `:service`, `:session` (atom), `:validate-requests?`. New keys
  added by WS-01 are namespaced (`::refresh-state`) so WS-07 must treat unknown namespaced keys as
  opaque and preserve them.
- `delegate-auth-interceptor` keeps its name and position in the `procedure`/`query` chains
  (`xrpc/client.cljc:111-120,135-144`). WS-07 may reorder/extend the chains but must keep this
  interceptor between the lexicon-level interceptors and `atproto-json`, and must preserve the
  `::auth-retried?` / request-snapshot keys it manages.
- `invalid-token-response?` is public; `expired-token-error?` remains as a deprecated alias until
  WS-07's cleanup.
- `atproto.oauth.client` public API after WS-01:
  `create`, `authorize`, `callback`, `restore`, `refresh`, `revoke` — all `& {:as opts}` async per
  `atproto.runtime.interceptor/platform-async` (`interceptor.cljc:129-155`).
- `atproto.credentials` public API: `create`, `logout`.
- `atproto.oauth.client.store/Store` protocol is frozen (synchronous, 3 methods).
- Persisted OAuth session JSON shape as specced above; readers must tolerate missing
  `:iss`/`:expires-at` (legacy rows).

### Consumed contracts

None — this workstream depends only on existing namespaces (`atproto.runtime.*`,
`atproto.identity`, `atproto.oauth.client.dpop`). No stubs or vendored contracts needed to start.

## File ownership

Created or modified when implemented (repo-relative, in
`/Users/luke/github/atproto-clj/atproto-clj`):

| File | Change | Conflicts |
|---|---|---|
| `src/atproto/xrpc/client.cljc` | modify (`init`, `Session` docstring, `invalid-token-response?`, `delegate-auth-interceptor`) | **Shared with WS-07. WS-01 lands first; WS-07 rebases.** |
| `src/atproto/credentials.cljc` | modify (refresh fix, `logout`) | none |
| `src/atproto/oauth/client.cljc` | modify (refresh, revoke, restore fix, callback/iss/expiry, state TTL check) | none |
| `src/atproto/oauth/client/store.clj` | modify (memory-store TTL hygiene, protocol docstring) | none |
| `examples/statusphere/src/xyz/statusphere/server.clj` | modify (logout) | none |
| `test/atproto/xrpc/client_test.cljc` | create | WS-07 will extend; same land-first rule |
| `test/atproto/credentials_test.cljc` | create | none |
| `test/atproto/oauth/client_test.clj` | create (JVM-only: uses Nimbus-backed jwt + store.clj) | none |
| `test/atproto/test_support/http.cljc` | create (scripted fake HTTP interceptor / `handle-request` stub, reusable by WS-07) | coordinate with WS-07 |
| `resources/lexicons/com/atproto/server/{createSession,refreshSession,deleteSession,getSession}.json` | create (vendored, only if request-validation tests need them; see Test plan) | **Shared with WS-09**, which owns `resources/lexicons/**` (251 files + `manifest.edn`). Vendor byte-identical copies from the same pinned reference commit (`b9ef5576beeb949bbac776dcbe4e4c2c7af60e74`); whoever lands second rebases; if WS-01 lands first, WS-09's `manifest.edn` must list these 4 files (per the 00-overview.md shared-path matrix). |

## Test plan

No OAuth interop fixtures exist upstream (verified: `interop-test-files/` has only `crypto/` and
`syntax/`), so the suite is built on stubbed HTTP. Stubbing mechanism: a `test-support` fake for
`atproto.runtime.http/handle-request` (`http.cljc:124-167`) via `with-redefs`, scripted as a queue
of canned `{:status :headers :body}` responses with a log of received requests — this exercises the
real json/dpop/auth interceptors.

### Unit tests

- `invalid-token-response?` table test:
  - 400 + `{:content-type "application/json"}` + body `{:error "ExpiredToken"}` → true
  - same with `"application/json; charset=utf-8"` → true (regression for Bug 4)
  - 401, no `www-authenticate` → true
  - 401 + `www-authenticate "DPoP error=\"invalid_token\", ..."` → true
  - 401 + `www-authenticate "DPoP error=\"use_dpop_nonce\""` → false (the DPoP interceptor owns that)
  - 200 / 400 with other error codes → false
- `delegate-auth-interceptor` with a stub session (metadata-implemented `Session`):
  - expired response → refresh called once → retry succeeds → caller gets the success body; assert
    the retried request carried the *new* token and the body was not double-serialized
    (regression for Bug 3: assert the stub saw a parseable JSON body twice).
  - refresh cb with `{:error "TokenRefreshError" :message "x"}` → caller's promise is delivered with
    that full map (regression for the hang + bare-string bugs). Use `deref` with timeout in the test.
  - retried request fails again with 401 → returned as-is, refresh called exactly once.
  - 32 concurrent `procedure` calls against an always-expired first response → exactly one
    `refresh-token` invocation (single-flight), all 32 promises delivered.
- Credentials:
  - `xrpc-refresh-session` against scripted `createSession` + `ExpiredToken` + `refreshSession`
    responses: new session has new `accessJwt`/`refreshJwt`, keeps `:pds`/`:handle`, still satisfies
    `Session` (metadata present); refresh request used `Bearer <refreshJwt>` and hit
    `/xrpc/com.atproto.server.refreshSession` (regression for Bugs 1-2).
  - refresh response with a different `:did` → `{:error "InvalidDID"}`.
  - `logout` posts `deleteSession` with the refresh JWT; server 400 still resolves.
- OAuth (JVM):
  - end-to-end refresh with scripted responses (plc.directory DID doc, PDS rsmd, AS asmd, token
    endpoint): assert `grant_type=refresh_token`, DPoP header present and signed with the stored
    `dpop-key`, issuer re-verified before the token call, `store/set` called with new tokens
    including `:iss` and integer `:expires-at`, cb result satisfies `Session`.
  - `invalid_grant` + store now holding *different* tokens → adopts stored session, no deletion.
  - `invalid_grant` + store unchanged → `store/del` called, `{:error "TokenRefreshError"}`.
  - refresh with no `:refresh_token` → error + deletion; network error → error, NO deletion.
  - `restore` unknown DID → `{:error "SessionNotFound"}` delivered (regression for Bug 5);
    `restore` of a legacy row (no `:iss`) still works for plain requests.
  - `revoke`: with `revocation_endpoint` → POST `{token ...}` observed, then `del*`; revocation 500
    → `del*` still called, resolves `{:revoked true}`; metadata without `revocation_endpoint` →
    no HTTP call, `del*` called.
  - `callback` with a state entry older than 1h → `{:error "StateExpired"}` and state deleted.
  - single-flight: two concurrent `refresh` calls for one DID → one token request.
- Store: `memory-store` evicts expired state entries; round-trips the full session map (including
  `dpop-key` JWK) through JSON.

### Vendored fixtures

Vendor only if the request-validation tests want real lexicons on the classpath (the XRPC client
skips schema validation when no lexicon is loaded, so these are optional):

- `/Users/luke/github/bluesky-social/atproto/lexicons/com/atproto/server/createSession.json`
- `/Users/luke/github/bluesky-social/atproto/lexicons/com/atproto/server/refreshSession.json`
- `/Users/luke/github/bluesky-social/atproto/lexicons/com/atproto/server/deleteSession.json`
- `/Users/luke/github/bluesky-social/atproto/lexicons/com/atproto/server/getSession.json`

→ place under `resources/lexicons/com/atproto/server/` (load in tests with the existing
`lexicon/load-resources!` used by statusphere, `examples/statusphere/src/xyz/statusphere/server.clj:83`).
Copy byte-identical from the reference repo at the pinned commit
`b9ef5576beeb949bbac776dcbe4e4c2c7af60e74` — WS-09 bundles all of `resources/lexicons/**` from that
same commit, so the copies must not diverge (see File ownership).

### Integration / live verification

- `^:integration`-tagged tests (skipped by default; run with env vars
  `ATPROTO_TEST_IDENTIFIER`/`ATPROTO_TEST_PASSWORD`): `credentials/create` against bsky.social, then
  force `xrpc-refresh-session` directly and assert new JWTs; then `logout` and assert the old
  refresh JWT is rejected.
- OAuth live path: run the reference dev environment
  (`/Users/luke/github/bluesky-social/atproto` → `make run-dev-env`, localhost PDS with its own AS),
  run statusphere against it, log in, then from a REPL call `oauth-client/refresh` and
  `oauth-client/revoke` for the session DID; verify the statusphere logout button deletes the
  `auth_session` row and the AS no longer accepts the old access token.
- Test runner: `clojure -X:test` (cognitect test-runner, configured in `deps.edn`).

## Acceptance criteria

- [ ] `credentials.cljc` refresh: an XRPC call returning 400/`ExpiredToken` on a credentials session
      transparently refreshes via `com.atproto.server.refreshSession` and the retried call succeeds
      (stubbed-HTTP test green); no `ex-info "A service or a session is required."` is reachable
      from the refresh path.
- [ ] OAuth refresh: `refresh-session` performs a DPoP-bound `refresh_token` grant, re-verifies the
      issuer, persists `{:iss ... :tokens {... :expires-at ...}}` via `store/set`, and delivers a
      session satisfying `xrpc.client/Session`.
- [ ] No auth path can hang: every refresh/restore failure mode delivers an `{:error ...}` map to
      the caller's promise/callback/channel (verified by tests that `deref` with timeouts).
- [ ] Refresh failure propagates the full error map (not a bare string) through
      `delegate-auth-interceptor`.
- [ ] Exactly one refresh occurs for N concurrent expired requests on one client (test with N≥32),
      and exactly one token-endpoint call for concurrent `oauth/refresh` calls on one DID.
- [ ] `invalid_grant` concurrency recovery: adopts a foreign refresh when store tokens changed;
      deletes the session and returns `TokenRefreshError` otherwise.
- [ ] Retried requests are byte-identical to the originals apart from auth/DPoP headers (no JSON
      double-encoding), and at most one retry happens per logical request.
- [ ] `invalid-token-response?` passes the detection table (charset suffix, 400+ExpiredToken,
      401 bare, 401+`invalid_token` WWW-Authenticate; `use_dpop_nonce` excluded).
- [ ] `oauth-client/revoke` calls the AS `revocation_endpoint` when present (best-effort) and always
      deletes the stored session; `restore` of the revoked DID then yields `SessionNotFound`.
- [ ] `credentials/logout` calls `deleteSession` with the refresh JWT.
- [ ] OAuth `callback` rejects state entries older than 1h and deletes them; `memory-store` does not
      grow unboundedly from abandoned `authorize` flows.
- [ ] Statusphere logout destroys the OAuth session (store row deleted; `;; todo: destroy oauth
      session` comment gone).
- [ ] `clojure -X:test` green on every milestone PR; existing tests unmodified except where behavior
      intentionally changed.

## Milestones

1. **PR-1: XRPC auth-retry core** — `invalid-token-response?` (+ deprecated alias),
   `delegate-auth-interceptor` rewrite (full-error propagation, request snapshot/restore,
   retry-once, single-flight `::refresh-state`), `test/atproto/test_support/http.cljc`,
   `test/atproto/xrpc/client_test.cljc` with a stub session. Green build; behavior for sessions
   whose `refresh-token` works is complete even though no real session implements it correctly yet.
2. **PR-2: credentials lifecycle** — fix `xrpc-refresh-session` (init bug, shadowing, session
   rebuild, DID check), add `logout`, `test/atproto/credentials_test.cljc`, optional vendored
   server lexicons. Credentials sessions now survive token expiry end-to-end.
3. **PR-3: OAuth refresh** — persisted `:iss`/`:expires-at` (in `callback` via `tokens+expiry`),
   `issuer-metadata`, `token-request`, `refresh-session` implementation with per-DID single-flight
   and `invalid_grant` recovery, `restore` no-hang fix + legacy-row tolerance, public `refresh`,
   `test/atproto/oauth/client_test.clj`. OAuth sessions now survive token expiry end-to-end.
4. **PR-4: revocation + state TTL + example** — `revoke`, callback orphan-revocation, state
   `:created-at` stamping + 1h rejection, `memory-store` eviction, `Store` docstring guidance,
   statusphere logout, remaining tests, delete the stale `;; todo` headers
   (`oauth/client.cljc:17-18`, `server.clj:49`).

## Risks & open questions

- **Token-endpoint body encoding.** The TS client sends `application/x-www-form-urlencoded`
  (`oauth-server-agent.ts:232-239`, per RFC 6749/7009); the existing Clojure `exchange-code` sends
  JSON, which the reference AS accepts (`oauth-provider/src/router/create-oauth-middleware.ts:135,167`
  + `lib/http/parser.ts:56,72-73`). **Recommendation:** keep JSON in this workstream for plumbing
  reuse (it demonstrably works against the reference provider, which is what every spec-compliant
  atproto AS today runs), but encapsulate encoding inside `token-request` and file a follow-up to
  switch to form-encoding (`http.cljc:65-72` already has `query-params->query-string`) for
  third-party-AS robustness. Flag for orchestrator visibility.
- **Single-flight design.** Recommended: pure atom-based waiter queue (no locks, no core.async
  dependency in `.cljc`), at two levels (XRPC client `::refresh-state`; OAuth client per-DID
  in-flight map). Rejected alternatives: JVM `locking` (blocks callback threads, deadlock risk with
  http-kit's callback pool), core.async (unavailable in the cljd target per
  `interceptor.cljc:45-47`).
- **Retry-by-re-entering-the-queue.** The retry mechanism reuses the interceptor queue captured in
  the leave-phase ctx. It is sensitive to chain composition; the request snapshot fixes the known
  double-encode bug, but WS-07's chain rework must keep the snapshot/`::auth-retried?` contract —
  called out in the interface contract; add a chain-composition test that WS-07 inherits.
- **Storing the access token vs refresh token for revocation.** TS revokes the `access_token` in
  `revoke`/`signOut` (`oauth-client.ts:529`, `oauth-session.ts:88`) but `refresh_token ?? access_token`
  in the store-failure path (`session-getter.ts:196`). Recommendation: follow `revoke(access_token)`
  for parity; the reference provider revokes the whole grant for any token of the session.
- **`memory-store` TTL without protocol change.** If lazy eviction inside `memory-store` is too
  clever, fall back to client-side-only TTL (state `:created-at` check) — correctness does not
  depend on store eviction. Decide in PR-4.
- **Clock skew on `:expires-at`.** We store it but only use it for explicit/`restore`-time refresh;
  the TS jittered staleness window (`session-getter.ts:175-187`) is deferred with proactive refresh
  (out of scope). Risk: none now; note for the follow-up.
- **Async `Store` protocol** (`store.clj:5` todo): deferred. If a future workstream makes stores
  async, `refresh-session`/`revoke` are already callback-shaped internally, so the migration is
  contained. Needs an owner — open question for the orchestrator.
- **Credentials cross-process races** are tolerated by the PDS's 2-hour refresh-rotation grace
  window (`account-manager.ts:434-440`); we deliberately do not build cross-process locking.
