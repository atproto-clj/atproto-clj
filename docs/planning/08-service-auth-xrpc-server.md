# WS-08: Service Auth & XRPC Server Completion

| | |
|---|---|
| **Status** | Implemented on `claude/epic-cannon-wwck1e` (2026-06-11); pending live-PDS check (acceptance item 4) |
| **Priority** | P1 |
| **Estimated size** | M |
| **Branch** | ws/08-service-auth-xrpc-server |
| **Depends on** | WS-03 (atproto.crypto signing/verification + did:key contract), WS-06 (atproto.identity DID resolution contract), WS-02 (atproto.data.cbor contract — subscription frames only) |
| **Blocks** | WS-11 (event sequencer/firehose — consumes the subscription transport and frame codec), any workstream building authenticated services (feed generators, labelers, PDS-like servers) |

## Goal

When this workstream is done, the SDK can mint and verify atproto **inter-service JWTs** (`iss`/`aud`/`lxm`/`exp`/`jti`, ES256K/ES256), both self-signed and via `com.atproto.server.getServiceAuth`. The XRPC server gains **pluggable per-route authentication** (with a ready-made service-JWT verifier that resolves the issuer's DID document), **spec-conformant error mapping** (full XRPC status-code/error-name table, no internal-detail leaks), a **websocket subscription transport** for lexicon `subscription` endpoints (binary CBOR frames, error frames, close codes), and **rate-limit hook points** (protocol only). The XRPC client gains an auth interceptor that attaches service JWTs to outbound inter-service calls.

## Current state

Service auth is effectively missing; the XRPC server handles only lexicon-validated queries/procedures with a minimal error model.

- `src/atproto/runtime/jwt.cljc` — generic JWS support via Nimbus, **JVM-only** (every body is `#?(:clj ...)`): `generate` signs a JWT from a JWK (lines 79–103), `generate-jwk` (lines 50–65), JWKS helpers. There is **no verify/parse function anywhere in the namespace**, and it only works with JWK-formatted keys, not atproto did:key/multibase signing keys.
- No `lxm`/`aud` service-JWT minting or verification, no `getServiceAuth` helper anywhere: `grep -rn "lxm\|getServiceAuth\|service-auth" src/ test/` returns nothing (verified 2026-06-10, branch `redesign`).
- `src/atproto/xrpc/server.cljc`:
  - Error helpers cover only `InvalidRequest` (400), `InvalidResponse` (500), `InternalServerError` (500), `MethodNotImplemented` (501), `AuthenticationRequired` (401) (lines 18–46). `auth-required` (lines 42–46) is **defined but never called** — there is no auth hook at all.
  - `http-request->xrpc-request` (lines 79–103): extracts the NSID by `subs` on the URL path (line 82–83) with no validation that the path actually starts with `/xrpc/` or that the NSID is syntactically valid; an NSID missing from the lexicon yields `400 InvalidRequest "Unknown NSID"` (lines 85–86), where the reference returns `501 MethodNotImplemented` for valid-but-unknown methods and `400` only for malformed paths (see TS `catchall`, below). Subscriptions are mapped to `:get` (line 91) but nothing downstream can handle them.
  - Latent bug: `decode-query-param` calls `clojure.edn/read-string` (line 57) but the `ns` form (lines 1–11) never requires `clojure.edn`; this only works if some other namespace happens to load it first.
  - `handle` multimethod (lines 105–119) is **synchronous** — the interceptor calls it inline (line 135). There is no way to do async work (e.g. DID resolution for auth) inside a handler.
  - `interceptor` `::i/leave` (lines 143–158) maps any handler `{:error ...}` without a `:status` to a generic 500; there is no notion of lexicon-defined custom error names, 403/413/429/502/503/504, or hiding internal messages on 500s (it does pass through whatever `:message` the error carries on non-500s, which is fine).
  - `handle-http-request` (lines 160–168) wires `json/server-interceptor`, `atproto-json/server-interceptor`, and the XRPC interceptor; the `:app-ctx` carried on the request (line 135) is the only extension point handlers get today.
- `src/atproto/xrpc/server/ring.clj` — synchronous Ring adapter only; `handler` (lines 37–43) blocks with `@` on the promise and only handles paths starting with `/xrpc/`. No websocket upgrade path of any kind.
- `src/atproto/lexicon.cljc` — subscriptions are already translated to specs: `translate-primary-type-def "subscription"` registers request and message specs (lines 523–529), and `message-spec-key` exists (lines 780–782). `type-def` (lines 716–720) and `request-spec-key` (lines 768–772) are what the server uses today.
- `src/atproto/xrpc/client.cljc` — the `Session` protocol (`auth-interceptor`, `refresh-token`, lines 38–41) and `delegate-auth-interceptor` (lines 48–67) are the client-side auth extension point; `atproto.credentials/auth-interceptor` (src/atproto/credentials.cljc lines 60–66) and `atproto.oauth.client/auth-interceptor` are the two existing implementations. Service auth must become a third.
- `src/atproto/jetstream.clj` — a JVM websocket *client* for Jetstream's JSON text frames using `java.net.http.WebSocket` (connect at lines 32–55). Useful as prior art for the integration test client, but it is not a binary-frame transport and not a server.
- `src/atproto/identity.cljc` — `resolve-did` exists (lines 47–54) but only `did:plc` is implemented; `did:web` returns `{:error "NotImplemented"}` (lines 110–112). No verification-material/signing-key extraction from DID docs. Both are WS-06's to fix; WS-08 consumes them (see Interface contract).
- `deps.edn` — already has `http-kit` 2.8.0 (which includes `org.httpkit.server` with websocket support), `nimbus-jose-jwt`, `tink`, `core.async`. No BouncyCastle (relevant to ES256K — WS-03's problem).
- Tests: `test/atproto/xrpc/server_test.cljc` covers query/procedure dispatch and request/response validation; subscriptions are explicitly TODO (lines 11–12); auth has no tests or TODO at all.

## Reference implementation guide

All paths under `/Users/luke/github/bluesky-social/atproto`.

**Service JWT mint/verify — `packages/xrpc-server/src/auth.ts`** (the canonical implementation):
- `createServiceJwt` (lines 28–52): header `{typ "JWT", alg <keypair jwt alg>}`; payload `{iat, iss, aud, exp, lxm?, jti}` with `nil`-valued keys stripped; `iat` defaults to now (seconds), `exp` defaults to `iat + 60`, `jti` is 16 random bytes hex-encoded; signing input is `base64url(json(header)) + "." + base64url(json(payload))` signed **directly with the atproto signing keypair** (raw ECDSA, not a JOSE library), signature base64url appended.
- `createServiceAuthHeaders` (lines 54–59): wraps the JWT as `{:headers {:authorization "Bearer <jwt>"}}`.
- `verifyJwt` (lines 72–176): split into 3 parts → parse header (must have string `alg`; reject `typ` in `#{"at+jwt" "refresh+jwt" "dpop+jwt"}`, lines 91–105) → parse payload (string `iss`/`aud`, number `exp`, optional string `lxm`, lines 202–216) → check `exp` against now (lines 110–112) → check `aud` equals own DID unless caller passes null (lines 113–118) → check `lxm` equals the expected method NSID unless null, with distinct messages for wrong vs. missing (lines 119–126) → check `iss` is a DID or `did#serviceId` (lines 127–129, 218–235) → resolve signing key via caller-supplied `getSigningKey(iss, forceRefresh)` and verify signature over `header.payload` bytes; **on failure, re-resolve with `forceRefresh=true` and retry once if the key changed** (lines 134–173). All failures throw `AuthRequiredError` (HTTP 401) with error names: `BadJwt`, `BadJwtType`, `JwtExpired`, `BadJwtAudience`, `BadJwtLexiconMethod`, `BadJwtIss`, `BadJwtSignature`.
- `cryptoVerifySignatureWithKey` (lines 178–188): verifies with `allowMalleableSig: true` (service JWTs tolerate high-S signatures, unlike repo commits).

**Verifier in production use — `packages/pds/src/auth-verifier.ts`**:
- `verifyServiceJwt` (lines 518–564): bearer token from the request, expected `lxm` = NSID parsed from the request URL (`parseReqNsid`, `packages/xrpc-server/src/util.ts` lines 636–646), `getSigningKey` resolves the issuer DID doc and picks verification method `#atproto` (or `#atproto_label` when the iss service fragment is `#atproto_labeler`, lines 536–538), converts multibase to a did:key string, then checks `aud` against own DID(s) (lines 554–562).
- `modService` verifier (lines 145–159): same, plus issuer allow-list. `userServiceAuth` (lines 297–308): exposes `{:type :user_service_auth :did (:iss payload)}` as credentials.
- DID-doc → key extraction: `packages/common-web/src/did-doc.ts` `getVerificationMaterial` (lines 39–48, finds `verificationMethod` whose id ends in `#<keyId>`) and `packages/identity/src/did/atproto-data.ts` `getDidKeyFromMultibase` (lines 26–41, maps `EcdsaSecp256r1VerificationKey2019`/`EcdsaSecp256k1VerificationKey2019`/`Multikey` to a did:key string).

**getServiceAuth endpoint — `packages/pds/src/api/com/atproto/server/getServiceAuth.ts`** (lines 21–111): validates `aud` is a DID or `did#serviceId`; `exp` bounds: in the past → `BadExpiration`, more than 1 hour out → `BadExpiration`, method-less token more than 1 minute out → `BadExpiration` (lines 68–86); refuses `lxm` in the PROTECTED_METHODS set (lines 88–92); mints with the account's repo signing keypair. The lexicon schema is `lexicons/com/atproto/server/getServiceAuth.json` (params `aud` required, `exp`, `lxm` nsid; output `{token}`; error `BadExpiration`).

**Server auth/middleware model — `packages/xrpc-server/src/server.ts` and `src/types.ts`**:
- Per-route config `{handler, auth?, opts?, rateLimit?}` (`types.ts` lines 196–206); auth verifier gets `{params, req, res}` and returns `{credentials, artifacts?}` or an error (`types.ts` lines 49–52, 99–112).
- Request pipeline order (`createHandlerInternal`, `server.ts` lines 421–517): parse/validate params → **authenticate** → parse/validate input → rate-limit → handler → validate output. Auth failures must fire before input parsing (there's a test for "fails on bad auth before invalid request payload" in `tests/auth.test.ts`).
- `catchall` (`server.ts` lines 352–399): malformed `/xrpc/` path → 400 `InvalidRequest`; known method with wrong HTTP verb → 400; unknown method → 501 `MethodNotImplemented`. Global rate limiter runs here for all XRPC paths.
- Error rendering (`createErrorMiddleware`, `server.ts` lines 727–772): every error becomes `{:status n :body {:error <name> :message <msg>}}`; `XRPCError.payload` (`src/errors.ts` lines 63–71) **replaces the message of 500s with the generic string** so internals never leak.
- Status table: `packages/xrpc/src/types.ts` `ResponseType` (lines 26–49): 400 InvalidRequest, 401 AuthenticationRequired, 403 Forbidden, 404 XRPCNotSupported, 406 NotAcceptable, 413 PayloadTooLarge, 415 UnsupportedMediaType, 429 RateLimitExceeded, 500 InternalServerError, 501 MethodNotImplemented, 502 UpstreamFailure, 503 NotEnoughResources, 504 UpstreamTimeout; `httpResponseCodeToEnum` (lines 51–65) folds other codes.

**Rate limiting — `packages/xrpc-server/src/rate-limiter.ts`**: interface `RateLimiterI` = `{consume(ctx, opts?), reset(ctx, opts?)}` (lines 17–20) with `calcKey`/`calcPoints` fns (lines 14–15; `calcKey` returning null and `calcPoints` < 1 both mean skip); `RateLimiterStatus` (lines 32–39); `consume` throws `RateLimitExceededError` (= XRPCError 429, lines 258–279); combinators `WrappedRateLimiter` (lines 187–213) and `CombinedRateLimiter` (tightest-wins, lines 221–256); memory/redis impls (lines 141–162). Server wiring: global/shared/route limiters and `bypass` (`server.ts` lines 118–137, 660–725); handlers get `resetRouteRateLimits` in ctx. WS-08 ports **the interface and wiring points only**, not the implementations.

**Subscription transport — `packages/xrpc-server/src/stream/`**:
- `types.ts` (lines 3–27): frame header is dag-CBOR `{op 1, t? "<type>"}` for messages, `{op -1}` for errors; error body `{error, message?}`.
- `frames.ts`: `toBytes` = concat of two dag-CBOR items, header then body (lines 21–23); `fromBytes` decodes exactly two items, rejects extra/missing (lines 30–59); `MessageFrame.fromLexValue` (lines 78–99) lifts the body's `$type` into the header `t`, compressing `<nsid>#frag` to `#frag` when the nsid matches the subscription's; `ErrorFrame.fromError` (lines 120–124).
- `server.ts` (lines 8–47): per-connection handler is an async iterable of frames; frames are sent as **binary** websocket messages with backpressure (await each send); after sending an `ErrorFrame` the socket is closed with ws close code 1008 (Policy) and the error name as the close reason; normal completion closes with 1000. Close codes: `packages/ws-client/src/index.ts` `CloseCode` (lines 156–160: Normal=1000, Abnormal=1006, Policy=1008).
- Wiring (`server.ts` in xrpc-server, lines 534–566 and 643–658): on HTTP upgrade, extract NSID from the URL, find the registered subscription, then inside the stream handler: validate params → run auth verifier with `{req, params}` → iterate the user handler, wrapping yielded data in `MessageFrame.fromLexValue`; any thrown error becomes an `ErrorFrame`.

**Tests to port**: `packages/xrpc-server/tests/auth.test.ts` (mint/verify round-trip incl. `lxm` binding, expired token, aud mismatch, auth-before-input ordering), `tests/frames.test.ts` (exact frame byte fixtures, lines 20–29), `tests/subscriptions.test.ts` (params/auth on streams, error frame → close 1008, message `t` headers), `tests/errors.test.ts` (error body shape), `tests/rate-limiter.test.ts` (interface semantics).

**Interop fixtures**: `interop-test-files/` contains only `crypto/` and `syntax/` — there are **no service-JWT or frame fixtures upstream**. `crypto/signature-fixtures.json` (ES256/ES256K low-S/high-S/DER cases) is already vendored at `test/interop-test-files/crypto/signature-fixtures.json` and is the right fixture for stubbing the WS-03 verify contract. Frame byte fixtures must be vendored from `packages/xrpc-server/tests/frames.test.ts`; service-JWT fixtures must be generated from the TS reference (see Test plan).

## Scope

### In scope

- `atproto.service-auth` namespace (.cljc, JVM-first):
  - Mint service JWTs: `iss`/`aud`/`exp`/`iat`/`lxm`/`jti` claims, header `{typ "JWT", alg}`, signed with an atproto signing key via the WS-03 contract (ES256K and ES256).
  - `auth-headers` convenience producing `{:headers {:authorization "Bearer ..."}}`.
  - Verify service JWTs: structural parsing, `typ` deny-list (`at+jwt`, `refresh+jwt`, `dpop+jwt`), `exp`, `aud`, `lxm`, `iss` (DID or `did#serviceId`) checks, signature verification with one forced-refresh key-rotation retry; error names matching the reference (`BadJwt`, `BadJwtType`, `JwtExpired`, `BadJwtAudience`, `BadJwtLexiconMethod`, `BadJwtIss`, `BadJwtSignature`, `MissingJwt`).
  - DID-doc signing-key extraction (`#atproto` / `#atproto_label` verification methods → did:key) layered on WS-06 resolution + WS-03 multibase.
- Client side:
  - `get-service-auth` helper that calls `com.atproto.server.getServiceAuth` on the user's PDS and returns the token.
  - A service-auth `Session` implementation / auth interceptor for `atproto.xrpc.client` that mints (or fetches) a JWT per request, binding `lxm` to the request NSID and `aud` to the target service DID.
- Server side:
  - Pluggable auth: per-route (and default) auth verifiers in the server config; verifiers are async (callback-convention); verified credentials exposed to `handle` as `:auth` on the xrpc-request; auth runs after param validation and **before input validation**.
  - Ready-made service-JWT auth verifier factory (own-DID audience check, lxm = request NSID, optional issuer allow-list).
  - Spec-conformant error mapping: full status table (403/404/406/413/415/429/501/502/503/504 added), error helpers, custom lexicon error names passed through, 500 messages sanitized, malformed `/xrpc/` path → 400 vs. unknown method → 501, wrong HTTP verb → 400. Fix the `clojure.edn` require bug while in the file.
  - Rate-limit hook points: a `RateLimiter` protocol (`consume`/`reset` with calc-key/calc-points semantics), config wiring (global + per-route), 429 error mapping, and `RateLimit-*` response-header pass-through. **No production limiter implementation** (a trivial in-memory one for tests only).
  - Websocket subscription transport: frame codec (message/error frames, dag-CBOR via WS-02), `handle-subscription` registration analogous to `handle`, params validation + auth on upgrade, message `$type` → frame `t` lifting, error-frame-then-close(1008) semantics, normal close(1000), backpressure-respecting send loop; http-kit server adapter.
- Vendoring/generating test fixtures for all of the above.

### Out of scope

- The event **sequencer/outbox/firehose** itself (cursor backfill, `#info`/`FutureCursor` semantics, repo event encoding) — WS-11. WS-08 only provides the transport that WS-11 plugs a channel into.
- secp256k1/secp256r1 signing & verification primitives, low-S handling, did:key/multibase encode/decode — WS-03. WS-08 codes against its contract and stubs it until merged.
- DID resolution (incl. `did:web`, caching, force-refresh) — WS-06.
- dag-CBOR encode/decode — WS-02.
- OAuth/DPoP resource-server verification (verifying OAuth access tokens server-side) — out of scope for the whole SDK at this stage; the auth hook is pluggable so it can be added later.
- Access/refresh **session** JWT verification (PDS `verifyBearerJwt` path, `packages/pds/src/auth-verifier.ts` lines 462–516) — that is PDS-account machinery, not inter-service auth. Not planned for any workstream yet; the pluggable hook accommodates it.
- The `getServiceAuth` *server* endpoint policy (PROTECTED_METHODS, scope checks) — application logic for a PDS implementation, not the SDK. We only ship the mint primitive it would use.
- Production rate limiter implementations (redis-backed etc.).
- Websocket *client* (subscription consumption) — WS-05 owns the firehose client (`atproto.sync.firehose`, 05-sync-streaming.md; same 05↔08↔11 mislabel family as overview §4.9 item 3); `atproto.jetstream` remains as-is.
- ClojureScript/ClojureDart server transports (the .cljc namespaces must still compile for cljs where practical, but websocket server support is JVM-only, matching the existing `ring.clj`).

## Deliverables

New/changed namespaces. All async functions follow the SDK convention: kwarg opts `:callback`/`:promise`/`:channel` via `atproto.runtime.interceptor/platform-async`; errors are `{:error "Name" :message "..."}` maps (plus `:status` where they map to HTTP).

### `atproto.service-auth` (new, src/atproto/service_auth.cljc)

```clojure
(ns atproto.service-auth
  "Mint and verify atproto inter-service JWTs (iss/aud/lxm/exp/jti)."
  (:require [clojure.spec.alpha :as s]
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.crypto :as runtime-crypto]
            [atproto.runtime.jwt :as jwt]      ;; WS-03 contract (parse/verify/sign additions)
            [atproto.crypto :as crypto]        ;; WS-03 contract
            [atproto.identity :as identity]))  ;; WS-06 contract

(s/def ::iss ...)   ;; DID or "did#serviceId" string
(s/def ::aud ...)   ;; DID or "did#serviceId" string
(s/def ::lxm ...)   ;; NSID string or nil
(s/def ::exp pos-int?)  ;; seconds since epoch

(defn create-jwt
  "Mint a service JWT.

  `params`:
  :iss      (required) issuer DID, optionally with #serviceId fragment.
  :aud      (required) audience DID (or did#serviceId).
  :keypair  (required) WS-03 signing keypair (provides alg + sign).
  :lxm      NSID of the method this token is bound to. Strongly recommended.
  :exp      expiration, seconds since epoch. Default: iat + 60.
  :iat      issued-at, seconds since epoch. Default: now.

  Async; yields {:token \"<jwt>\"} or {:error ...}.
  A fresh 16-byte hex :jti claim is generated for every token."
  [params & {:as opts}])

(defn auth-headers
  "Async; yields {:headers {:authorization \"Bearer <jwt>\"}} for `create-jwt` params."
  [params & {:as opts}])

(defn verify-jwt
  "Verify a service JWT string and return its claims.

  `opts-map`:
  :aud              own service DID; nil skips the audience check.
  :lxm              expected method NSID; nil skips the lxm check.
  :get-signing-key  (required) (fn [iss force-refresh? cb]) yielding
                    {:key \"did:key:...\"} or {:error ...}. Called a second
                    time with force-refresh? true when signature
                    verification fails (key rotation).
  :verify-signature optional override for the raw signature check
                    (tests/stubs). Defaults to WS-03's async
                    (crypto/verify-did-sig did-key sig-bytes signing-input
                     :allow-malleable? true) ~> boolean | {:error ...}
                    (overview §4.2; jwt/verify's lenient default).

  Async; yields the payload {:iss :aud :exp :iat :lxm :jti ...} on success, or
  {:error <Name> :message ... :status 401} where <Name> is one of:
  BadJwt, BadJwtType, JwtExpired, BadJwtAudience, BadJwtLexiconMethod,
  BadJwtIss, BadJwtSignature."
  [jwt opts-map & {:as opts}])

(defn did-signing-key-resolver
  "Build a :get-signing-key fn backed by atproto.identity DID resolution.

  Resolves the issuer's DID document and extracts the did:key for the
  verification method `#atproto` (or `#atproto_label` when iss has the
  #atproto_labeler service fragment), per the reference PDS.

  `opts-map`:
  :allowed-issuers  optional set of permitted iss values (incl. fragments);
                    others yield {:error \"UntrustedIss\" :status 401}."
  [opts-map])

(defn get-service-auth
  "Ask the user's PDS to mint a service token on their behalf
  (com.atproto.server.getServiceAuth).

  `client` is an authenticated atproto.client/xrpc client.
  `params`: {:aud ... :lxm ... :exp ...} per the lexicon.
  Async; yields {:token \"...\"} or the XRPC error (e.g. BadExpiration)."
  [client params & {:as opts}])

(defn session
  "An xrpc-client Session that authenticates requests with self-minted
  service JWTs (for services that hold their own signing key).

  config:
  :iss      issuer DID (this service).
  :keypair  WS-03 signing keypair.
  :aud      audience DID of the target service.
  :service  target service URL (becomes the session :pds).

  The auth interceptor derives :lxm from the request URL's /xrpc/<nsid>
  path and mints a fresh short-lived token per request. Implements
  atproto.xrpc.client/auth-interceptor via metadata, like
  atproto.credentials/session. refresh-token MUST satisfy WS-01's frozen
  Session contract (01-auth-session-lifecycle.md, overview §4.3: cb
  called EXACTLY ONCE with a new Session value or an {:error ...} map;
  never hangs): it immediately invokes cb with the session itself —
  there is no refresh state to update, and the retried request mints a
  fresh token anyway. A literal no-op is forbidden: if
  delegate-auth-interceptor sees an invalid-token 401 on a
  service-auth'd request, it calls refresh-token, and a no-op would
  leave the caller's promise undelivered forever."
  [config])
```

Notes for the implementer:
- Do **not** re-implement the compact-JWS layer here — WS-03 delivers it in `atproto.runtime.jwt` explicitly for this consumer (03-crypto.md "runtime/jwt.cljc (modified — additions only)" and its Out of scope: WS-08 "layers policy on the returned claims"; overview §4.2). `create-jwt` builds the claims map (nil-valued keys removed, `iat`/`exp` defaults, fresh `jti`) and calls `(jwt/sign keypair {:typ "JWT" :alg (crypto/alg keypair)} claims)` — WS-03's `sign` mirrors `createServiceJwt`'s `base64url(JSON(header)) "." base64url(JSON(payload))` encoding exactly. Do **not** route through the Nimbus-based `atproto.runtime.jwt/generate` — the key is a WS-03 keypair (did:key material), not a JWK, and Nimbus can't sign ES256K without BouncyCastle.
- `verify-jwt` = `(jwt/parse jwt-str)` for structure/header/claims/signing-input (`{:error "MalformedJwt"}` → `BadJwt`), then the policy layer this namespace owns, in reference order: header alg present → typ deny-list → payload shape → exp → aud → lxm (distinct messages for wrong vs. missing) → iss shape → resolve key via `:get-signing-key` → signature check through WS-03 with `:allow-malleable?` true (the `jwt/verify` default — service-JWT parity, xrpc-server/src/auth.ts:178-187), plus one forced-refresh retry only if the refreshed key differs. The key-rotation retry lives here, not in `jwt/verify`, because only service auth has `:get-signing-key`. WS-08 never modifies `runtime/jwt.cljc` (overview conflict matrix: WS-03 owns it, 11A extends it).
- Until WS-03 merges, develop with the sign/verify/parse fns injected as args behind tiny internal wrappers (overview §5), so integration is a two-line change.
- Use `atproto.runtime.crypto/now` (src/atproto/runtime/crypto.cljc lines 11–14) and `random-bytes` (lines 16–21) for `iat`/`jti`; hex via WS-03's `runtime.crypto/hex-encode` (overview §4.2).
- JSON via `atproto.runtime.json`; base64url via `atproto.runtime.crypto/base64url-encode` (lines 37–40) plus `base64url-decode`, which is WS-03's addition to that file (overview §4.2) — not WS-08's.

### `atproto.xrpc.server` (modified)

```clojure
(defn init
  "config gains:
  :auth     map of nsid (string) -> auth verifier fn, plus optional
            :default entry applied to routes without a specific entry.
            Routes absent from the map (and no :default) are unauthenticated.
  :rate-limit {:global [limiter ...]          ;; RateLimiter instances
               :routes {nsid [limiter ...]}}  ;; per-route, interface only"
  [config])

;; Auth verifier contract (async, callback style):
;; (fn [ctx cb]) where ctx is
;;   {:nsid    string
;;    :params  parsed+validated params map
;;    :request the raw http request (headers etc.)
;;    :app-ctx the app context}
;; and cb receives {:credentials ... & anything} on success or
;; {:error "..." :message "..." :status 401/403} on failure.

(defmulti handle
  "Unchanged signature (app-ctx, request), but the request map now also
  carries :auth — the verifier's success map — when the route has an
  auth verifier configured."
  (fn [app-ctx request] (:nsid request)))

(defn service-auth-verifier
  "Ready-made auth verifier for service JWTs.

  config:
  :own-did          this service's DID (audience check; nil to skip).
  :allowed-issuers  optional issuer allow-list.
  :get-signing-key  optional override; defaults to
                    (service-auth/did-signing-key-resolver config).

  Extracts the Bearer token ({:error \"MissingJwt\" :status 401} when
  absent), verifies it with :lxm bound to the route nsid, and yields
  {:credentials {:type :service :did <iss-did> :iss <full-iss>
                 :payload <claims>}}."
  [config])

;; Error helpers (new + existing), all returning {:status :error :message}:
;; invalid-request 400, auth-required 401, forbidden 403,
;; xrpc-not-supported 404, not-acceptable 406, payload-too-large 413,
;; unsupported-media-type 415, rate-limit-exceeded 429,
;; invalid-response 500, internal-server-error 500,
;; method-not-implemented 501, upstream-failure 502,
;; not-enough-resources 503, upstream-timeout 504.
;; Handlers may return {:error "CustomName" :status 400 :message ...}
;; to surface lexicon-defined error names. An :error without :status
;; keeps the current behavior of mapping to 500. Sanitization: any 5xx
;; response body :message is replaced with the generic status string
;; (mirror XRPCError.payload, errors.ts lines 63-71).

(defmulti handle-subscription
  "Handle a subscription (websocket) request for an NSID.

  Takes [app-ctx request] where request has :nsid, :params, :auth.
  Must return a map:
    {:messages ch}    core.async channel of outgoing messages. Each item
                      is either atproto data (maps; :$type is lifted into
                      the frame header) or {:frame/error \"Name\"
                      :frame/message \"...\"} to emit an error frame and
                      close. Closing the channel closes the socket
                      normally (1000).
  or {:error ...} to reject the subscription before upgrade."
  (fn [app-ctx request] (:nsid request)))

(defn subscription-request
  "Validate an HTTP upgrade request against the lexicon and run the
  route's auth verifier. Async; yields the xrpc-request for
  handle-subscription, or {:error ...}. Shared by transport adapters."
  [server http-request & {:as opts}])
```

Pipeline change inside `interceptor`: params parse/validate → auth (async; on failure short-circuit with its error) → input validate → rate-limit consume (global then route; on throw → 429 with `RateLimit-*` headers when available) → `handle` → response validate. This requires the enter fn to use `i/continue` from callbacks instead of returning synchronously — the interceptor framework already supports that (src/atproto/runtime/interceptor.cljc lines 1–47 docstring).

### `atproto.xrpc.rate-limit` (new, src/atproto/xrpc/rate_limit.cljc — interface only)

```clojure
(defprotocol RateLimiter
  (consume [limiter ctx cb]
    "ctx is the same map auth verifiers receive plus :auth.
     cb receives nil (not limited / skipped), a status map
     {:limit :duration :remaining-points :ms-before-next :consumed-points}
     or {:error \"RateLimitExceeded\" :status 429 :ratelimit-status <status>}.")
  (reset [limiter ctx cb]))

(defn wrapped   "Override calc-key/calc-points of a limiter." [limiter opts])
(defn combined  "Tightest-wins combination."                  [limiters])
(defn memory    "Trivial in-memory limiter (tests/dev only)."
  [{:keys [key-prefix duration-ms points calc-key calc-points]}])
```

### `atproto.xrpc.frames` (new, src/atproto/xrpc/frames.cljc)

```clojure
(ns atproto.xrpc.frames
  "Binary event-stream frames: a dag-CBOR header item followed by a
   dag-CBOR body item."
  (:require [atproto.data.cbor :as cbor])) ;; WS-02 contract

(defn message-frame
  "Frame for a message body. The body's :$type (if any) is moved to the
  header :t, compressed to \"#frag\" when its nsid equals `nsid`."
  [body & {:keys [nsid]}])

(defn error-frame
  "Frame carrying {:error name :message msg?}; header {:op -1}."
  [error & [message]])

(defn encode
  "Frame -> bytes (header item ++ body item)."
  [frame])

(defn decode
  "bytes -> {:op 1 :t \"#x\" :body {...}} | {:op -1 :body {:error ...}}
   or {:error \"InvalidFrame\" :message ...} for missing body, more than
   two CBOR items, or an invalid header."
  [bytes])
```

This is sync/pure (no async needed); cljc so WS-05's firehose client and WS-11C's stream server can reuse it (overview §4.7/§4.9 item 8: this codec is the canonical one; WS-05's duplicate `atproto.sync.frame` consolidates onto it).

### `atproto.xrpc.server.ring` (modified) + websocket transport

```clojure
;; ring.clj additions
(defn handler
  "Now also recognizes lexicon subscription NSIDs: for those, performs
  the websocket upgrade using http-kit's as-channel and drives the
  frame send loop; query/procedure behavior unchanged."
  [server])

;; send-loop semantics (JVM, http-kit org.httpkit.server):
;; - on upgrade: run subscription-request; if {:error ...} respond with
;;   the mapped XRPC error JSON instead of upgrading.
;; - take from the :messages channel; encode each item with
;;   atproto.xrpc.frames; send as binary.
;; - {:frame/error ...} item -> send error frame, close with ws code
;;   1008 and the error name as the close reason.
;; - channel closed -> close with 1000.
;; - client disconnect -> close the :messages channel's sibling control
;;   side: handle-subscription receives a :close-ch in the request map
;;   that is closed on disconnect so producers can stop.
```

### `atproto.runtime.crypto` (consumed, not modified)
- `base64url-decode` and `hex-encode` (used by jti and JWT parsing) are **WS-03 deliverables** in this file (overview §4.2; conflict matrix: WS-03 owns `runtime/crypto.cljc`, "08 drops its `base64url-decode`/hex duplicates"). WS-08 consumes them; if WS-08 develops ahead of WS-03's merge, stopgap copies live as private fns in `service_auth.cljc` and are deleted at rebase.

## Interface contract

### Provided (frozen — other workstreams may code against this)

- `atproto.service-auth/create-jwt`, `auth-headers`, `verify-jwt`, `did-signing-key-resolver`, `get-service-auth`, `session` as specified above. Error names are fixed: `MissingJwt`, `BadJwt`, `BadJwtType`, `JwtExpired`, `BadJwtAudience`, `BadJwtLexiconMethod`, `BadJwtIss`, `BadJwtSignature`, `UntrustedIss`.
- `atproto.xrpc.frames/message-frame`, `error-frame`, `encode`, `decode` with the frame map shapes above (WS-05's firehose client + WS-11C's sequencer consume this).
- `atproto.xrpc.server/handle-subscription` multimethod + `{:messages ch}` / `:close-ch` convention, and the `:auth` config key with the async verifier fn contract (WS-11 registers `com.atproto.sync.subscribeRepos` through this).
- `atproto.xrpc.rate-limit/RateLimiter` protocol.

### Consumed (assumed contracts; restated exactly)

**WS-03 (`atproto.crypto`, `atproto.runtime.jwt`, `atproto.runtime.crypto`)** — frozen signatures per overview §4.2 / 03-crypto.md Interface contract (**key operations — `sign`/`verify`/`verify-did-sig`/`generate`/`jwt sign+verify` — are async per the SDK callback convention; parsing/encoding sync**; errors are `{:error ...}` maps, never thrown):
```clojure
;; atproto.crypto
(crypto/sign keypair msg-bytes)                     ;; -> 64-byte compact low-S sig bytes
(crypto/verify-did-sig did-key sig-bytes msg-bytes
                       & opts)                      ;; -> boolean | {:error ...} for a bad did:key
                                                    ;;    opts incl. :allow-malleable?
(crypto/alg keypair)                                ;; -> "ES256" | "ES256K"
(crypto/did keypair)                                ;; -> "did:key:z..."
(crypto/generate alg & {:keys [exportable?]})       ;; tests
;; DID-doc verification-method -> did:key is a composition (there is NO multibase->did-key fn):
;;   Multikey type:          (crypto/parse-multikey s) then (crypto/pubkey->did-key alg bytes)
;;   legacy Ecdsa*2019 types: (crypto/pubkey->did-key alg (crypto/multibase->bytes s))

;; atproto.runtime.jwt (WS-03 additions; the existing Nimbus generate/JWK fns are untouched)
(jwt/parse jwt-str)               ;; -> {:header :claims :signing-input :signature}
                                  ;;    | {:error "MalformedJwt"}
(jwt/verify jwt-str key & opts)   ;; -> {:header :claims} | {:error "MalformedJwt"|"BadJwtSignature"
                                  ;;    |"JwtExpired"|"UnsupportedAlgorithm" ...}; key may be a
                                  ;;    did:key string; :allow-malleable? defaults TRUE
                                  ;;    (service-JWT parity); checks signature + exp/nbf/iat only —
                                  ;;    aud/lxm/iss/typ policy is WS-08's layer
(jwt/sign keypair headers claims) ;; -> compact JWS string (mirrors createServiceJwt's encoding)

;; atproto.runtime.crypto (WS-03 additions)
(runtime-crypto/base64url-decode s)  ;; -> bytes | nil
(runtime-crypto/hex-encode b)        ;; -> hex string
```
Mirrors `packages/crypto` (`Keypair.sign/jwtAlg/did`, `verifySignature` with `allowMalleableSig`) and `packages/identity/src/did/atproto-data.ts` `getDidKeyFromMultibase` (as the composition above). Until WS-03 merges: develop `service-auth` with the sign/verify/parse fns injected, and a **JVM stub** in `test/` implementing ES256 only via `java.security` (P-256 is in the JDK; secp256k1 is not) plus the vendored `test/interop-test-files/crypto/signature-fixtures.json` to sanity-check the stub. All ES256K test cases are written but marked pending until WS-03 lands.

**WS-06 (`atproto.identity`)** — frozen signature per overview §4.5 (the kwarg is `:force-refresh`, not `force-refresh?`; WS-08's *internal* `(fn [iss force-refresh? cb])` callback arg name is its own):
```clojure
(identity/resolve-did did & {:keys [cache cache-policy force-refresh plc-url]})
  ;; async -> {:did-doc {...}} | {:error ...}; :force-refresh bypasses the cache read
```
Today `resolve-did` exists without the `:force-refresh` option (src/atproto/identity.cljc lines 47–54) and `did:web` is unimplemented (lines 110–112). `did-signing-key-resolver` must accept any `(fn [did force-refresh? cb])`, so WS-08 can ship using the current `resolve-did` (ignoring the force-refresh flag — there is no cache yet, every call is fresh) and pick up WS-06's behavior transparently. DID-doc verification-method extraction is implemented inside WS-08 (it's pure map traversal over the resolved doc; only the multibase conversion comes from WS-03).

**WS-02 (`atproto.data.cbor`)** — frozen signatures per overview §4.1 (the multi-item decode is `decode-multi`/`decode-first`, not `decode-seq`; §4.9 item 2):
```clojure
(cbor/encode data)         ;; -> dag-CBOR bytes          (throws ex-info on invalid input)
(cbor/decode-first bytes)  ;; -> [data bytes-consumed]   (throws ex-info)
(cbor/decode-multi bytes)  ;; -> vector of decoded items (throws ex-info)
```
Only `atproto.xrpc.frames` needs this; it covers the frame requirement of decoding exactly two concatenated CBOR items (and rejecting extras). Note these codecs **throw** `ex-info` rather than returning error maps (§4.1), so `frames/decode` catches and maps to `{:error "InvalidFrame"}`. Milestones order frame work after the WS-02 contract merges; the frame namespace and tests are written against the contract and the vendored byte fixtures, so they light up as soon as `atproto.data.cbor` exists.

**WS-11** consumes (does not modify) `atproto.xrpc.server` and `atproto.xrpc.frames`. If WS-11 needs server.cljc changes they rebase on WS-08.

## File ownership

| File | Action | Conflicts |
|---|---|---|
| `src/atproto/service_auth.cljc` | create | none |
| `src/atproto/xrpc/frames.cljc` | create | WS-11 consumes; WS-08 owns |
| `src/atproto/xrpc/rate_limit.cljc` | create | none |
| `src/atproto/xrpc/server.cljc` | modify | WS-11 consumes; WS-08 lands first, WS-11 rebases |
| `src/atproto/xrpc/server/ring.clj` | modify | none known |
| `src/atproto/xrpc/client.cljc` | modify (only if the Session protocol needs an arity tweak; avoid if possible) | **WS-01 owns and lands first**; WS-07 also modifies (rebasing on WS-01) and WS-09 adds a one-line `*strict*` binding (overview §5 conflict matrix) — WS-08 avoids touching it and rebases |
| `src/atproto/runtime/crypto.cljc` | no change planned (consume WS-03's `base64url-decode`/`hex-encode`, overview §4.2) | **WS-03 owns the file and lands first**; if WS-08 develops ahead of it, stopgap copies live as private fns in `service_auth.cljc` and are deleted at rebase |
| `deps.edn` | no change expected (http-kit server already present); only touched if the Ring-websocket route is chosen | orchestrator-level coordination on any dep change |
| `test/atproto/service_auth_test.cljc` | create | none |
| `test/atproto/xrpc/frames_test.cljc` | create | none |
| `test/atproto/xrpc/server_test.cljc` | modify (auth + error-mapping cases) | none |
| `test/atproto/xrpc/subscription_test.clj` | create (JVM integration) | none |
| `test/atproto/service_auth/jwt_fixtures.json` | create (generated, see Test plan) | none |
| `test/atproto/xrpc/frame_fixtures.json` | create (vendored bytes) | none |

Not touched: `src/atproto/runtime/jwt.cljc` (WS-03 owns it and is adding the `parse`/`verify`/`sign` primitives WS-08 consumes — see Interface contract; the existing Nimbus `generate`/JWK fns stay OAuth/DPoP-only; WS-08 never modifies the file), `src/atproto/jetstream.clj`, `README.md`, `examples/`.

## Test plan

**Unit — service auth** (`test/atproto/service_auth_test.cljc`):
- Mint→verify round-trip with a generated keypair (ES256 via stub until WS-03; ES256K cases pending-flagged): claims present, `exp` defaults inside `(now, now+60s]`, `jti` 32 hex chars, `lxm` round-trips (port of `packages/xrpc-server/tests/auth.test.ts` lines 78–120).
- Each failure mode: malformed string (`BadJwt`), forbidden `typ` headers (`BadJwtType`), expired (`JwtExpired`), wrong aud (`BadJwtAudience`), wrong/missing lxm (`BadJwtLexiconMethod`, distinct messages), non-DID iss (`BadJwtIss`), bad signature (`BadJwtSignature`), key-rotation retry (get-signing-key returns stale key first, fresh key on `force-refresh? true` → success; same key twice → `BadJwtSignature`).
- `did-signing-key-resolver`: fixture DID docs exercising `#atproto` and `#atproto_label` selection and `:allowed-issuers` (`UntrustedIss`), with a stubbed `resolve-did`.
- `session` honors the WS-01 Session contract: `refresh-token` invokes its callback exactly once (with the session itself); a 401 invalid-token response routed through `atproto.xrpc.client/delegate-auth-interceptor` (src/atproto/xrpc/client.cljc lines 48–67) on a service-auth session **delivers** an error/response to the caller (promise realized) rather than hanging.
- Cross-implementation fixtures: generate `test/atproto/service_auth/jwt_fixtures.json` by running a small Node script in the reference repo (using `createServiceJwt` from `/Users/luke/github/bluesky-social/atproto/packages/xrpc-server/src/auth.ts` with fixed `iat`/`exp`/`jti`-seed and both `Secp256k1Keypair`/`P256Keypair` with exported did:keys); commit the script's output (tokens + signer did:keys + expected claims). Verify-side tests consume it directly; this is the substitute for the missing upstream interop fixtures.
- Already-vendored `test/interop-test-files/crypto/signature-fixtures.json` validates the test-only ES256 verify stub (source: `/Users/luke/github/bluesky-social/atproto/interop-test-files/crypto/signature-fixtures.json`).

**Unit — server auth & error mapping** (extend `test/atproto/xrpc/server_test.cljc`):
- Route with auth verifier: success exposes `:auth` to `handle`; failure returns its `:status` + `{:error :message}` JSON body; auth failure wins over invalid input body (port of "fails on bad auth before invalid request payload", `tests/auth.test.ts`).
- `service-auth-verifier` against the xrpc server with a stubbed `get-signing-key`: valid token 200; missing Authorization → 401 `MissingJwt`; lxm bound to a different nsid → 401 `BadJwtLexiconMethod`.
- Error table: unknown-but-valid NSID → 501; malformed `/xrpc/` path → 400; wrong verb → 400; handler returns custom error name+status → passed through; 5xx messages sanitized; rate-limit hook returning exceeded → 429 body `{"error":"RateLimitExceeded",...}`.

**Unit — frames** (`test/atproto/xrpc/frames_test.cljc`, requires WS-02):
- Exact byte vectors vendored from `/Users/luke/github/bluesky-social/atproto/packages/xrpc-server/tests/frames.test.ts` (message frame with `t`, error frame) into `test/atproto/xrpc/frame_fixtures.json`; encode equals fixture bytes, decode inverts.
- Reject: extra CBOR items, missing body, invalid header op; `$type` lifting/compression rules from `MessageFrame.fromLexValue` (frames.ts lines 78–99).

**Integration — subscriptions** (`test/atproto/xrpc/subscription_test.clj`, JVM):
- Start http-kit server with the ring adapter; lexicon with a countdown subscription (mirror `tests/subscriptions.test.ts` LEXICONS); client = `java.net.http.WebSocket` (pattern from `src/atproto/jetstream.clj` lines 32–55, binary variant).
- Assert: N message frames then close 1000; bad params → no stream of messages, error frame `InvalidRequest` and close 1008; auth verifier rejection → error frame + 1008; `{:frame/error "..."}` from the handler mid-stream → error frame + close 1008 with reason = error name; producer sees `:close-ch` close on client disconnect.

**Live verification (manual, documented in the test ns comment block)**:
- `get-service-auth`: with real credentials (`atproto.credentials/create`), call `get-service-auth` against the user's PDS with `:aud` = the PDS DID and `:lxm "com.atproto.server.getSession"`; then `verify-jwt` it locally with `did-signing-key-resolver` (real `resolve-did`) and `:aud` nil / `:lxm` as requested — proves end-to-end verify against bsky.network-minted tokens.
- Mint with a throwaway keypair and confirm a real relay/PDS rejects it with `BadJwtSignature`-class 401 (negative check).

Run everything with `clj -X:test` (cognitect test-runner, already configured in deps.edn).

## Acceptance criteria

- [x] `atproto.service-auth/create-jwt` produces tokens that `packages/xrpc-server` `verifyJwt` accepts (validated via the generated fixture suite in both directions).
- [x] `verify-jwt` accepts reference-minted fixture tokens and returns claims; every reference error name (`BadJwt`, `BadJwtType`, `JwtExpired`, `BadJwtAudience`, `BadJwtLexiconMethod`, `BadJwtIss`, `BadJwtSignature`) is produced by a test and carries `:status 401`.
- [x] Signature-failure path retries exactly once with `force-refresh? true` and only when the refreshed key differs.
- [ ] `get-service-auth` returns `{:token ...}` from a real PDS (not performed: no live credentials in the implementation environment; instructions in the test ns comment block) (manual live check documented and performed once before merge).
- [x] `service-auth/session` attaches `Bearer` service JWTs with `lxm` = request NSID; verified by round-tripping against the SDK's own server with `service-auth-verifier`.
- [x] XRPC server: routes can declare auth; `handle` receives `:auth`; auth runs before input validation; unauthenticated routes unchanged (existing server tests still pass).
- [x] Error responses: JSON body always `{"error": <name>, "message": <msg>}`; status table matches `ResponseType` (400/401/403/404/406/413/415/429/500/501/502/503/504); unknown method → 501; malformed xrpc path → 400; 5xx bodies never contain internal exception messages; `clojure.edn` is properly required.
- [x] `atproto.xrpc.frames` encode/decode byte-exact against vendored reference fixtures.
- [x] A lexicon subscription endpoint served over http-kit streams binary frames to a vanilla websocket client, with error-frame + close 1008 and normal close 1000 semantics.
- [x] `RateLimiter` protocol exists, is invoked at the documented points, maps exceeded → 429; no production limiter shipped.
- [x] `clj -X:test` green on every merged PR; cljs compilation of touched .cljc namespaces not broken (service-auth/frames may be JVM-stubbed under `#?` like `runtime/jwt.cljc` is today, but must still read as cljc).

## Milestones

1. **PR-1: service JWT mint/verify core.** `atproto.service-auth` (mint, verify, claims spec, error taxonomy) with the WS-03 sign/verify/parse fns injected via args (post-WS-03 it calls `atproto.runtime.jwt/sign|parse` + `atproto.crypto/verify-did-sig` and `runtime.crypto/base64url-decode`/`hex-encode` directly; any pre-merge stopgap helpers are private to `service_auth.cljc` and dropped at rebase); test-only ES256 stub + vendored signature fixtures; generated JWT fixture file + generation script note. No dependents touched. Green standalone.
2. **PR-2: DID-doc key resolution + client side.** `did-signing-key-resolver` (against current `identity/resolve-did`), `get-service-auth`, `service-auth/session` + xrpc-client interceptor; unit tests with stub resolver; manual live-check instructions in a comment block. (Rebase to real WS-03/WS-06 contracts here if they've merged.)
3. **PR-3: server auth + error mapping overhaul.** Async-capable interceptor pipeline (params → auth → input → rate-limit hook → handle → output), `:auth` config + `service-auth-verifier`, full error helper set + status table + 500 sanitization + 501-for-unknown-method + edn require fix, `atproto.xrpc.rate-limit` protocol + memory impl + 429 wiring. Extends `server_test.cljc`. Existing behavior covered by existing tests stays intact.
4. **PR-4: frames.** `atproto.xrpc.frames` + vendored byte fixtures. **Gated on WS-02 contract merge**; if WS-02 slips, this PR carries the ns + tests behind a pending flag with a temporary test-only CBOR shim and is explicitly noted as such.
5. **PR-5: websocket subscription transport.** `handle-subscription`, `subscription-request`, http-kit upgrade path in `ring.clj`, integration test with a countdown stream + auth + error-frame close codes.
6. **PR-6 (small): polish.** Live-service verification write-up, docstrings, `cast/event`s at auth/subscription lifecycle points, README progress-matrix update *proposal* left to the orchestrator (README not owned by this workstream).

## Implementation notes (2026-06-11)

- Implemented against the merged WS-02/WS-03 contracts (no stubs needed); WS-06 not yet
  merged, so `did-signing-key-resolver` runs against the current `identity/resolve-did`
  and forwards `:force-refresh` for forward compatibility (its `:resolve-did` override is
  the test seam).
- http-kit's `AsyncChannel.serverClose` encodes only the 2-byte close status; the close
  *reason* string cannot be attached (the `serverClose(int, String)` overload ignores the
  reason). Close codes 1008/1000 are sent per the reference; the error name reaches
  clients in the error frame body. Documented in `atproto.xrpc.server.ring`.
- Cross-implementation fixtures: `test/atproto/service_auth/jwt_fixtures.json` generated
  from `@atproto/xrpc-server` 0.11.1 / `@atproto/crypto` 0.5.0 (script committed beside
  it); the reverse direction (Clojure-minted tokens verified by the reference `verifyJwt`)
  was performed for both curves at implementation time.
- WS-05 had not landed `atproto.sync.frame`; `atproto.xrpc.frames` is the only frame
  codec, as required by overview §4.9 item 8.

## Risks & open questions

1. **WebSocket server API choice (decision needed, recommendation included).** Options: (a) http-kit `org.httpkit.server/as-channel` — already a dependency, zero new deps, but ties the websocket adapter to http-kit; (b) Ring 1.14 `ring.websocket` protocols — server-agnostic (Jetty et al.) but adds a `ring/ring-core` dependency and http-kit 2.8 doesn't implement it. **Recommendation: (a) http-kit adapter**, with all transport-agnostic logic (`subscription-request`, frame loop policy) kept in `atproto.xrpc.server`/`atproto.xrpc.frames` so a second adapter is a ~50-line addition later.
2. **Async `handle` for queries/procedures.** Auth/rate-limit become async in PR-3, but the `handle` multimethod itself stays synchronous (matching today's contract; src/atproto/xrpc/server.cljc line 135). Services needing async handlers must block inside `handle` for now. Recommendation: keep sync in WS-08, note a follow-up (allow `handle` to return a core.async channel) — changing the multimethod contract mid-stream would break `examples/statusphere` and the existing tests for little WS-08 benefit.
3. **ES256K on the JVM** (secp256k1 was removed from the JDK; Nimbus needs BouncyCastle, Tink doesn't expose plain ECDSA-secp256k1 signing ergonomically). This is WS-03's risk, but it is **on WS-08's critical path** for real-world verification (all PLC repo signing keys are overwhelmingly K-256). Mitigation: everything in WS-08 takes the verify/sign fns as data, fixtures cover both curves, ES256K tests flip on when WS-03 lands.
4. **`iss` with service fragment.** The reference accepts `did#serviceId` in `iss` and uses the fragment to pick the verification method (`#atproto_labeler` → `#atproto_label` key, pds/auth-verifier.ts lines 536–538). Easy to miss; encoded in `did-signing-key-resolver` tests.
5. **`jti` replay protection** — the reference does **not** track jti server-side; WS-08 matches (document that consumers can layer replay caching in their own verifier). No `iat`/clock-skew check either (only `exp`), again matching the reference (auth.ts lines 110–112).
6. **Error-name drift between current SDK and spec.** Existing `xrpc.client` consumers match on `"ExpiredToken"` etc. from server bodies (src/atproto/xrpc/client.cljc lines 43–46); WS-08's server-side names follow the reference exactly, so no client change is needed, but any handler-authored error names are passed through unvalidated (same as TS — lexicon `errors` arrays are not enforced server-side).
7. **Fixture generation reproducibility.** The JWT fixtures are generated from the TS repo at a pinned commit; record the commit hash and the script inline in the fixture JSON's `_meta` so regeneration is mechanical.
8. **Backpressure semantics on http-kit** — http-kit's `send!` doesn't expose a completion callback like `ws.send(cb)` in Node (stream/server.ts lines 24–31). Mitigation: bounded `:messages` channel (producer-side backpressure) + monitor `org.httpkit.server/channel` open state per send; document that absolute send-buffer backpressure is best-effort on http-kit. This mainly matters for WS-11's firehose volume — flag it to that workstream.
