# WS-11: Service Pieces (umbrella — decompose before implementing)

| | |
|---|---|
| **Status** | Implemented (11A M1–M6, 11B M1–M3, 11C M1–M3 merged to `redesign` — PRs #22–#25; 11B M4 optional S3 blobstore not built; 11D memo written then dropped by decision, commit `41f23bf`) |
| **Priority** | P2 |
| **Estimated size** | XL |
| **Branch** | ws/11-service-pieces |
| **Depends on** | WS-02 (DAG-CBOR encode/decode + CID computation, the `atproto.data.cbor` contract), WS-03 (signing keys: secp256k1/P-256 keypair create/sign/verify, did:key encoding; owns `atproto.runtime.jwt` parse/verify/sign), WS-04 (MST/repo: blockstore protocol, CommitData shape, CAR read/write), WS-08 (server-side auth on the XRPC server + websocket subscription transport + binary event-stream frame codec), WS-05 (Clojure firehose *client*, used by 11C integration tests) |
| **Blocks** | A future "minimal Clojure PDS assembly" workstream (not yet chartered); nothing currently chartered consumes WS-11 contracts |

> **This is an UMBRELLA document.** Its deliverable is four sub-stream charters
> (11A–11D below) plus a dependency map and ordering. Do **not** implement all
> of this on one branch. Each sub-stream gets its own branch
> (`ws/11a-oauth-provider`, `ws/11b-repo-storage`, `ws/11c-stream-server`,
> `ws/11d-plc-directory`) and its own PR-sized milestones. The long-horizon
> goal that frames all four: **enough pieces to run a minimal Clojure PDS** —
> account repos with durable storage, signed commits flowing out of a
> `com.atproto.sync.subscribeRepos` firehose, blobs, and an atproto OAuth
> authorization server for auth. Final PDS assembly (account manager,
> `com.atproto.server.*` / `com.atproto.repo.*` route handlers, handle
> provisioning) is explicitly *not* part of WS-11; it should be chartered once
> these pieces exist.

## Goal

When WS-11 is complete, the SDK has service-side building blocks for the four
README matrix rows currently marked ⭕ (`README.md:28,31-33`): an atproto OAuth
authorization server (provider) that the SDK's own OAuth client can
authenticate against; durable per-actor repo storage (SQLite blockstore +
record index + blobstore protocols with disk implementation); a stream server
(sequencer + outbox + `com.atproto.sync.subscribeRepos` emission with correct
cursor/backfill semantics); and a written go/no-go decision (charter, not code)
on running a PLC-directory-compatible identity service. Each piece is
independently usable and tested against the SDK's existing client-side
implementations.

## Current state

Everything in WS-11 scope is greenfield — there are no provider, storage,
sequencer, or directory namespaces today. What exists and is load-bearing:

**Server-side HTTP (partial, reusable).**
- `src/atproto/xrpc/server.cljc:105-119` — `handle` multimethod dispatching on
  NSID; `:121-158` — request validation interceptor (lexicon spec validation,
  error→HTTP mapping); `:160-168` — `handle-http-request` entry point.
- `src/atproto/xrpc/server/ring.clj:37-43` — Ring handler adapter (matches
  `/xrpc/` prefix only). No websocket support, no auth hooks (auth is WS-08).
- `deps.edn` — `http-kit/http-kit {:mvn/version "2.8.0"}` is already a
  dependency; http-kit's `as-channel` provides a websocket *server*, which
  WS-08's subscription transport uses (11C registers through
  `atproto.xrpc.server/handle-subscription` rather than driving `as-channel`
  itself). There is **no SQL/SQLite dependency** today; 11A/11B/11C add one.

**OAuth client side (the conformance driver for 11A).**
- `src/atproto/oauth/client.cljc:58-70` (`create`), `:233-262` (`authorize`,
  PAR-only flow), `:334-362` (`callback`/code exchange), `:372-374`
  (`refresh-session` is literally `'todo` — fixing it belongs to the OAuth
  client workstream, but 11A's token endpoint gives it something to test
  against).
- `src/atproto/oauth/client/dpop.cljc:19-46` — DPoP proof *generation*
  (`htm`/`htu`/`jti`/`iat`/`nonce`/`ath` claims); `:59-76` — nonce-retry
  interceptor. 11A implements the *verification* side of exactly this.
- `src/atproto/oauth/client/store.clj:6-10` — synchronous `Store` protocol
  (`get*`/`set*`/`del*`) with a "should we make this async?" todo at line 5.
  11A's store protocols follow this precedent (synchronous protocols, async at
  the public API boundary).

**Runtime gaps in shared namespaces (WS-03 fills the base; 11A extends).**
- `src/atproto/runtime/jwt.cljc:79-103` — JWT `generate` (sign) only. There is
  **no JWT `verify`** today. **WS-03 owns this file** and adds
  `parse`/`verify`/`sign` (did:key / raw-pubkey / JWK key modes; see
  `03-crypto.md` §3 and 00-overview §4.2/conflict matrix). 11A needs
  verification for DPoP proofs with embedded JWKs and `private_key_jwt`
  client assertions, so 11A *extends* WS-03's `verify` with keyset +
  embedded-JWK key modes on top, rebasing onto `ws/03-crypto` if it hasn't
  merged (Nimbus JOSE is already a dep: `com.nimbusds/nimbus-jose-jwt` in
  `deps.edn`).

**Data model / identity (consumed, not owned).**
- `src/atproto/data.cljc:19-79` — CID create/encode/parse via
  `mvxcvi/multiformats`; `:91-138` — data-model specs. No DAG-CBOR codec (WS-02).
- `src/atproto/identity.cljc:62-75` — did:plc resolution against
  `https://plc.directory`; `:110-112` — did:web fetch returns
  `{:error "NotImplemented"}`; `:157-168` — handle resolution. 11D's charter
  concerns the *directory service*, not this client.
- `src/atproto/jetstream.clj:1-30` — JSON-only Jetstream client over
  `java.net.http.WebSocket`. The binary frame codec is WS-08; the binary
  firehose *client* is WS-05.

**Async/error conventions all new code must follow.**
- `src/atproto/runtime/interceptor.cljc:129-155` (`platform-async`),
  `:157-169` (`execute`); errors are `{:error "Name" :message "..."}` maps;
  observability via `atproto.runtime.cast` (`alert`/`event`/`metric`,
  `src/atproto/runtime/cast.cljc:62-77`).

Known bugs to fix: none — nothing exists yet in this area.

## Reference implementation guide

All paths below are into `/Users/luke/github/bluesky-social/atproto` and were
verified against the repo as of 2026-06.

### 11A — OAuth provider (authorization server)

**Size warning (honest):** `packages/oauth/oauth-provider/src` is **157 TS
files, ~13,000 lines** (excluding UI). The React login/consent UI
(`packages/oauth/oauth-provider-ui`) is another 135 source files, and scope
parsing (`packages/oauth/oauth-scopes/src`) is ~4,000 lines. A full port is
larger than the entire current Clojure SDK (~3,200 lines). 11A must subset
aggressively (see Scope) and must NOT port the UI — login/consent is delivered
as hooks the host app renders.

- `packages/oauth/oauth-provider/src/oauth-provider.ts` — the core state
  machine. `class OAuthProvider` at line 237 (extends `OAuthVerifier`);
  `pushedAuthorizationRequest` at 472; `authorize` at 588 (consumes a
  `request_uri`, drives the device/account session to a redirect or
  login-required result); `token` at 739 (authorization_code + refresh_token
  grants); `revoke` at 1043. Configuration/store wiring in
  `OAuthProviderConfig` (lines ~127-237): seven pluggable stores plus hooks.
- `packages/oauth/oauth-provider/src/oauth-verifier.ts` — resource-server-side
  verification, reused by the PDS to validate access tokens:
  `class OAuthVerifier` at 68, `checkDpopProof` at 115, `authenticateRequest`
  at 195.
- `packages/oauth/oauth-provider/src/dpop/dpop-manager.ts` — RFC 9449 proof
  validation: `class DpopManager` at 33, `nextNonce` at 45 (rotating
  HMAC-seeded nonces), `checkProof` at 52 (typ `dpop+jwt`, max age, `jti`
  replay, `htm`/`htu` normalization, `ath` binding, `use_dpop_nonce`
  challenge). Mirror of the Clojure client in
  `src/atproto/oauth/client/dpop.cljc`.
- `packages/oauth/oauth-provider/src/client/client-manager.ts` — client
  metadata fetching/validation: `getClient` at 91, `getClientMetadata` at 156,
  loopback-client synthesis at 173-178, atproto-profile validation rules
  (redirect URI schemes, `dpop_bound_access_tokens`, auth methods) throughout
  the 772-line file.
- Store interfaces (the contract 11A must define as Clojure protocols):
  `packages/oauth/oauth-provider/src/oauth-store.ts:6-12` re-exports all seven:
  - `request/request-store.ts:25-44` — `RequestStore`
    (`createRequest`/`readRequest`/`updateRequest`/`deleteRequest`/
    `consumeRequestCode`; note the comment requiring atomic single-use code
    consumption).
  - `token/token-store.ts:39` — `TokenStore` (create/read/delete/rotate +
    `findTokenByRefreshToken`/`findTokenByCode`).
  - `account/account-store.ts:118` — `AccountStore` (`createAccount` at 123,
    `authenticateAccount` at 135, `setAuthorizedClient` at 140,
    `getDeviceAccount` at 174 — device-session ↔ account binding).
  - `device/device-store.ts` — device sessions (cookie-bound).
  - `replay/replay-store.ts` — `jti` replay protection.
  - `client/client-store.ts` — optional first-party client registry.
  - `lexicon/lexicon-store.ts` — lexicon-defined permission sets (defer; see
    Out of scope).
- HTTP surface: `packages/oauth/oauth-provider/src/router/create-oauth-middleware.ts`
  lines 74-164: `GET /.well-known/oauth-authorization-server`,
  `GET /oauth/jwks`, `POST /oauth/par`, `POST /oauth/token`,
  `POST /oauth/revoke` (+ CORS preflights). The authorize page (HTML) is
  mounted by `router/create-authorization-page-middleware.ts` (GET handlers at
  66 and 213).
- How the PDS embeds it: `packages/pds/src/context.ts:356-381` — constructs
  `OAuthProvider` with `issuer`, an HS256 keyset, and a single combined store;
  `packages/pds/src/auth-routes.ts:13-52` — mounts the middleware and serves
  `/.well-known/oauth-protected-resource`. The combined SQLite-backed store is
  `packages/pds/src/account-manager/oauth-store.ts` (789 lines), with table
  schemas in `packages/pds/src/account-manager/db/schema/`
  (`authorization-request.ts`, `token.ts`, `device.ts`, `account-device.ts`,
  `authorized-client.ts`, `used-refresh-token.ts`).
- Wire types/validation: `packages/oauth/oauth-types/src` (metadata documents,
  token requests/responses, loopback client metadata synthesis in
  `atproto-loopback-client-metadata.ts`).
- Tests to mine: `packages/pds/tests/oauth.test.ts` (full-flow, puppeteer) and
  `packages/oauth/oauth-provider`'s unit logic (no dedicated test dir in the
  package — flow tests live in pds/dev-env).

### 11B — Durable repo storage

- Storage contracts: `packages/repo/src/storage/types.ts` — `RepoStorage`
  interface at 7-30 (`getRoot`/`putBlock`/`putMany`/`updateRoot`/`applyCommit`
  + readable side `getBytes`/`has`/`getBlocks`/`readObj`...), `BlobStore` at
  33-45 (`putTemp`/`makePermanent`/`putPermanent`/`quarantine`/`unquarantine`/
  `getBytes`/`getStream`/`hasTemp`/`hasStored`/`delete`/`deleteMany`),
  `BlobNotFoundError` at 47. The Clojure *protocol* equivalent of
  `RepoStorage`'s readable side is owned by **WS-04** (see
  `packages/repo/src/storage/readable-blockstore.ts:8`,
  `memory-blockstore.ts:7` for the reference shapes); 11B implements it.
- Actor-store layout: `packages/pds/src/actor-store/actor-store.ts` —
  `getLocation` at 28-34 (per-DID directory `{base}/{sha256(did)[0:2]}/{did}/`
  containing `store.sqlite` + `key`), `transact` at 79-92 (open DB per
  operation, run in transaction, close), `create` at 107-126 (mkdir, write
  exported signing key, migrate), `reserveKeypair` at 145-161, PLC-op staging
  at 176-192.
- Per-actor SQLite schema: `packages/pds/src/actor-store/db/schema/` — seven
  tables: `repo-block.ts` (cid, repoRev, size, content BLOB), `repo-root.ts`
  (did, cid, rev, indexedAt — single row), `record.ts` (uri, cid, collection,
  rkey, repoRev, indexedAt, takedownRef), `blob.ts` (cid, mimeType, size,
  tempKey, createdAt, takedownRef), `record-blob.ts` (blobCid, recordUri),
  `backlink.ts` (uri, path, linkTo), `account-pref.ts`.
- Blockstore-over-SQLite: `packages/pds/src/actor-store/repo/sql-repo-reader.ts`
  (`SqlRepoReader` at 14, `getRoot` 21, `getBytes` 38 with block cache,
  `getBlocks` 56) and `sql-repo-transactor.ts` (`SqlRepoTransactor` at 7,
  `putBlock` 33 with `ON CONFLICT DO NOTHING`, `putMany` 47 batched by 50,
  `applyCommit` 73 = updateRoot + putMany(newBlocks) + deleteMany(removedCids),
  `updateRoot` 79).
- Record indexing: `packages/pds/src/actor-store/record/transactor.ts` —
  `indexRecord` at 17 (record row + backlinks), `deleteRecord` at 69,
  backlink maintenance at 82-96.
- Blobstores: `packages/pds/src/disk-blobstore.ts` (`DiskBlobStore` at 17,
  `putTemp` 75 → random base32 key under `{tmp}/{did}/`, `makePermanent` 82 →
  copy to `{location}/{did}/{cid}`, `quarantine` 106, `getBytes` 124) and
  `packages/aws/src/s3.ts` (`S3BlobStore` at 22; key layout `tmp/{did}/{key}`,
  `blocks/{did}/{cid}`, `quarantine/{did}/{cid}` at lines 60-71; `putTemp` 108,
  `makePermanent` 114 via server-side copy, `getBytes` 172).
- Fixtures: `packages/repo/tests/car-file-fixtures.json` and
  `packages/repo/tests/commit-proof-fixtures.json` (shared with WS-04). There
  are **no repo/storage fixtures** in `interop-test-files/` — only `crypto/`
  and `syntax/` exist there.

### 11C — Stream server (sequencer + outbox + subscribeRepos)

- `packages/pds/src/sequencer/sequencer.ts` — `class Sequencer` at 31. Own
  SQLite DB (separate from actor stores). `curr` at 68 (max seq), `next` at 78
  (first row with seq > cursor), `earliestAfterTime` at 89, `requestSeqRange`
  at 100 (seq range query filtering `invalidated = 0`), `pollDb` at 134-155
  (poll loop emitting batches of ≤1000, exponential backoff capped at 1s when
  idle), `sequenceEvt` at 164-170 (INSERT ... RETURNING seq, then notify
  crawlers), typed emitters `sequenceCommit`/`sequenceSyncEvt`/
  `sequenceIdentityEvt`/`sequenceAccountEvt` at 172-199,
  `deleteAllForUser` at 201.
- Schema: `packages/pds/src/sequencer/db/schema.ts:1-20` — single `repo_seq`
  table: `seq` (autoincrement PK), `did`, `eventType`
  (`'append'|'sync'|'identity'|'account'`), `event` (DAG-CBOR bytes),
  `invalidated` (0/1), `sequencedAt` (ISO datetime).
- Event payloads: `packages/pds/src/sequencer/events.ts` — `formatSeqCommit`
  at 17-45 (CAR file of newBlocks+relevantBlocks rooted at commit CID; ops
  with `action`/`path`/`cid`/`prev`; deprecated-but-required `rebase`/
  `tooBig`/`blobs` fields), `formatSeqSyncEvt` 47-63, `formatSeqIdentityEvt`
  82-98, `formatSeqAccountEvt` 100-118; zod schemas for
  `CommitEvt`/`SyncEvt`/`IdentityEvt`/`AccountEvt` at 120-174.
- `packages/pds/src/sequencer/outbox.ts` — the three-phase
  backfill → cutover → live-streaming algorithm (comment at 25-33, `events`
  generator at 34-104: dedupe by `seq > lastSeen`, bounded `AsyncBuffer`
  (default 500) raising `ConsumerTooSlow` at 93-99; `getBackfill` at 107-122,
  page size 500, switches to cutover within half a page of the sequencer head).
- Wire handler: `packages/pds/src/api/com/atproto/sync/subscribeRepos.ts` —
  cursor semantics at lines 20-42: `FutureCursor` error if cursor > current
  seq (29-30); if the cursor's next event is older than the backfill window
  (`repoBackfillLimitMs`, default 1 day —
  `packages/pds/src/config/config.ts:182`), emit an `#info` frame named
  `OutdatedCursor` and restart from the earliest in-window event (31-38).
- Frame codec (owned by **WS-08**, reused here): 
  `packages/xrpc-server/src/stream/frames.ts:14-60` — a frame is two
  concatenated DAG-CBOR items: header `{op: 1, t: "#commit"}` (or `{op: -1}`
  for error) + body.
- Lexicon: `lexicons/com/atproto/sync/subscribeRepos.json` (message union
  `#commit|#sync|#identity|#account|#info`; errors `FutureCursor`,
  `ConsumerTooSlow`).
- Tests to mine: `packages/pds/tests/sequencer.test.ts` (ordering, backfill,
  cutover races), `packages/pds/tests/sync/subscribe-repos.test.ts`
  (cursor/window behavior over a live websocket).

### 11D — Identity directory (PLC)

- **Not in this monorepo.** The reference PLC directory server lives in
  `github.com/did-method-plc/did-method-plc`. The PDS consumes it as
  `@did-plc/lib ^0.0.4` (`packages/pds/package.json:54`) and runs
  `@did-plc/server` in tests (dev dependency, `packages/pds/package.json:85`).
  `packages/pds/tests/plc-operations.test.ts` shows the PDS-side operation
  signing flows.
- The monorepo's `packages/did/src` and `packages/identity/src` are
  *resolution clients* only (the Clojure equivalents already exist in
  `src/atproto/identity.cljc`).
- Service surface (from the PLC spec / reference server): `GET /:did` (DID
  doc), `GET /:did/log` + `/:did/log/audit` (operation log), `GET /:did/data`
  (current op data), `POST /:did` (submit signed operation; validate rotation
  key chain, 72h recovery window, op CID chaining), `GET /export` (paginated
  ops firehose). Backing store is an append-only operation log keyed by DID.

## Scope

### In scope

**Umbrella (this branch, `ws/11-service-pieces`):**
- This document: sub-stream charters, dependency map, ordering.
- Definition of the "minimal Clojure PDS" target the sub-streams build toward.
- Frozen Clojure protocol sketches for the cross-cutting contracts (blobstore,
  provider stores, sequencer) so sub-streams and the future assembly
  workstream can develop in parallel.

**11A — OAuth provider (`ws/11a-oauth-provider`):**
- Extend WS-03's `atproto.runtime.jwt/verify` with keyset + embedded-JWK key
  modes (WS-03 owns the file and lands `parse`/`verify`/`sign` first; 11A
  rebases onto `ws/03-crypto` if unmerged) — shared-runtime extension.
- Authorization server metadata document generation + spec validation
  (`/.well-known/oauth-authorization-server`), JWKS endpoint.
- Client registry: client-metadata fetching (`client_id` URL → metadata doc),
  atproto-profile validation, loopback client synthesis, metadata/JWKS caching.
- DPoP verification: proof checks per RFC 9449 (typ/alg/jti/htm/htu/iat/ath),
  rotating server nonces, `use_dpop_nonce` challenge responses, replay store.
- PAR endpoint (`POST /oauth/par`) — atproto requires PAR; reject non-PAR
  authorize requests.
- Authorization state machine: request store, `request_uri` issuance/expiry,
  authorize entry point that returns either a redirect result or a
  "login/consent required" result for the host app to render (login UI
  **hooks**, not UI).
- Token endpoint: `authorization_code` + `refresh_token` grants, PKCE
  verification, DPoP-bound token issuance (access + refresh), `sub`/`aud`/
  `scope` claims per atproto profile; token rotation with used-refresh-token
  detection; revocation endpoint.
- Client authentication: `none` and `private_key_jwt` assertion verification.
- Verifier API for resource servers (validate access token + DPoP binding) —
  the hook WS-08's server auth can call.
- Store protocols (request/token/account/device/replay/client) + in-memory
  implementations; SQLite implementations following the PDS table layout.
- Ring route set wiring all endpoints.
- Conformance testing using the SDK's own OAuth client
  (`src/atproto/oauth/client.cljc`) end-to-end.

**11B — Durable repo storage (`ws/11b-repo-storage`):**
- SQLite implementation of WS-04's blockstore protocol (`repo_block` +
  `repo_root` tables; `applyCommit` semantics from `sql-repo-transactor.ts`).
- Actor-store: per-DID directory layout (sha256-sharded), lifecycle
  (`create!`/`exists?`/`destroy!`), signing-key storage, reserved keypairs,
  read/transact wrappers.
- Record index: `record`, `backlink`, `record_blob` tables with
  index/delete/list/get operations.
- Blob metadata table + `BlobStore` protocol (temp → permanent → quarantine
  lifecycle) with disk implementation; S3 implementation as a follow-on,
  isolated so the AWS SDK never becomes a core dependency.
- Schema migration mechanism (ordered migration fns per DB, recorded in a
  migrations table — mirror the kysely migrator behavior, keep it tiny).

**11C — Stream server (`ws/11c-stream-server`):**
- Sequencer: dedicated SQLite DB with `repo_seq` table; `sequence-commit!`/
  `sequence-sync!`/`sequence-identity!`/`sequence-account!`; range/cursor
  queries (`current-seq`, `next-after`, `earliest-after-time`,
  `request-range`); poll-loop event emission with exponential backoff.
- Event formatting: commit/sync/identity/account event maps and their DAG-CBOR
  encoding, CAR assembly for commit/sync events (via WS-04 CAR writer).
- Outbox: backfill → cutover → live three-phase delivery, bounded buffer with
  `ConsumerTooSlow` disconnect, per-connection cursor dedupe.
- `subscribeRepos` registration through WS-08's
  `atproto.xrpc.server/handle-subscription` (`{:messages ch}`/`:close-ch`
  convention; WS-08 owns the http-kit upgrade, frame encoding, and close
  codes): cursor validation (`FutureCursor` via `{:frame/error ...}`),
  backfill window with `OutdatedCursor` info message, `ConsumerTooSlow` on
  outbox-buffer overflow.

**11D — Identity directory (`ws/11d-plc-directory`):**
- Charter/decision memo ONLY: what a PLC-directory-compatible service requires
  (op log validation, rotation-key recovery semantics, export firehose), what
  it would reuse from this SDK (11B's SQLite conventions, the XRPC server),
  and a recommendation. Default recommendation: **defer implementation
  indefinitely**; a minimal Clojure PDS does not need to *be* a directory — it
  needs a PLC *client* (operation signing/submission), which belongs with the
  "PLC Operations" README row, outside WS-11.

### Out of scope

- **MST construction, commit signing, diff/proof algorithms, CAR codec** —
  WS-04 owns these; 11B only persists blocks and roots, 11C only serializes
  blocks WS-04 hands it.
- **DAG-CBOR codec** — WS-02. 11C's event encoding calls it, never implements
  it.
- **Signing keys / did:key** — WS-03. The actor-store stores key material
  opaquely.
- **Websocket frame codec and subscription transport** — WS-08. 11C emits
  messages through WS-08's `handle-subscription`/frames machinery; the
  firehose *client* (consuming relays) is WS-05.
- **XRPC server auth middleware / service-auth JWT verification** — WS-08.
  11A only exposes the access-token verifier WS-08 can call.
- **OAuth client improvements** (session refresh `'todo` at
  `src/atproto/oauth/client.cljc:372-374`) — OAuth client workstream.
- **The provider login/consent UI** — never ported. Host apps render pages via
  hooks (`oauth-provider-ui`'s 135-file React app is replaced by a contract).
- **OIDC, hcaptcha, email/invite flows, branding/customization, redis-backed
  stores, entryway mode, account-management API**
  (`oauth-provider/src/oidc`, `customization/`, `lib/hcaptcha.ts`,
  `oauth-provider-api`) — not needed for a minimal PDS.
- **Lexicon permission sets / `LexiconStore`**
  (`oauth-provider/src/lexicon/lexicon-store.ts`) — the atproto scope model is
  still evolving; MVP supports plain scope strings
  (`atproto transition:generic transition:chat.bsky`) only.
- **PDS assembly**: account manager, `com.atproto.server.*`,
  `com.atproto.repo.*`, `com.atproto.sync.get*` route handlers, handle
  provisioning, read-after-write, image resizing, mailer, crawler
  notification (`packages/pds/src/crawlers.ts`) — future workstream that
  consumes WS-11's contracts.
- **ozone, bsync, bsky appview, feed generators** — permanently out of scope
  per the SDK's stated direction (`README.md:43`).

## Deliverables

New namespaces are JVM-first `.clj` where they touch SQLite/filesystem/
websockets; pure logic (DPoP claim checks, metadata validation, event
formatting) goes in `.cljc` so a future CLJS service runtime isn't foreclosed.
All public async fns follow the `atproto.runtime.interceptor/platform-async`
convention (kwargs `:callback`/`:promise`/`:channel`, default
platform-appropriate deferred) and return `{:error "Name" :message "..."}`
maps on failure. Store/storage *protocols* are synchronous (precedent:
`src/atproto/oauth/client/store.clj`); implementations may throw, public
wrappers convert to error maps.

### 11A — `atproto.oauth.provider` family

```clojure
(ns atproto.oauth.provider
  "atproto OAuth authorization server (provider).")

(defn create
  "Create a provider.

  :issuer        https origin of this server (string, required)
  :keyset        JWKS (private) used to sign tokens/assertions; ES256 required
  :stores        map of store implementations (see atproto.oauth.provider.store):
                 :request-store :token-store :account-store :device-store
                 :replay-store  :client-store (optional)
  :hooks         map of host-app callbacks (see below)
  :access-token-mode  :stateless (default) | :light
  :dpop          {:secret bytes, :rotation-interval-ms long} or false
  :metadata      extra entries merged into the AS metadata document"
  [opts])

(defn metadata
  "The OAuth authorization server metadata document (validated). Sync."
  [provider])

(defn jwks
  "Public JWKS document. Sync."
  [provider])

(defn pushed-authorization-request
  "Handle POST /oauth/par.
  Input: {:params <form params> :dpop-proof <header> :method :url :client-auth ...}
  Async. Success: {:request-uri \"urn:ietf:params:oauth:request_uri:...\"
                   :expires-in 299}
  Error: {:error \"invalid_client\" :message ... :status 400 :dpop-nonce ...}"
  [provider request & {:as opts}])

(defn authorize
  "Drive GET /oauth/authorize?client_id=..&request_uri=..
  given the current device session (from atproto.oauth.provider.device).
  Async. Returns one of:
  {:redirect {:uri ... :params {:code ... :state ... :iss ...}}}
  {:prompt :login   :request {...client metadata, scope description...}}
  {:prompt :consent :request {...}}
  {:error ...}"
  [provider {:keys [client-id request-uri device-id]} & {:as opts}])

(defn complete-sign-in
  "Called by the host app after its login UI authenticated the user
  (via the account-store). Binds account to device session and re-enters
  the authorize flow. Async."
  [provider {:keys [device-id request-uri sub remember?]} & {:as opts}])

(defn token
  "Handle POST /oauth/token (authorization_code | refresh_token grants).
  Verifies client auth, PKCE, DPoP binding; issues/rotates tokens.
  Async. Success: token response map (access_token, token_type \"DPoP\",
  refresh_token, expires_in, scope, sub). Error: OAuth error map + :status."
  [provider request & {:as opts}])

(defn revoke
  "Handle POST /oauth/revoke. Async. Always succeeds per RFC 7009."
  [provider request & {:as opts}])

(defn verify-access-token
  "Resource-server entry point (consumed by WS-08's auth middleware).
  Validates signature/expiry/aud and DPoP proof binding (ath/jkt).
  Async. Success: {:sub did :scope ... :client-id ... :token-id ...}"
  [provider {:keys [token dpop-proof method url]} & {:as opts}])
```

```clojure
(ns atproto.oauth.provider.store
  "Store protocols for the OAuth provider. Synchronous; in-memory impls here,
   SQLite impls in atproto.oauth.provider.store.sqlite.")

(defprotocol RequestStore
  (create-request! [_ request-id data])
  (read-request    [_ request-id])
  (update-request! [_ request-id data])
  (delete-request! [_ request-id])
  (consume-request-code! [_ code]
    "Atomically fetch-and-invalidate. Must never return the same code twice,
     including under concurrency."))

(defprotocol TokenStore
  (create-token! [_ token-id data refresh-token])
  (read-token    [_ token-id])
  (find-by-refresh-token [_ refresh-token])
  (find-by-code  [_ code])
  (rotate-token! [_ token-id new-token-id new-refresh-token data])
  (delete-token! [_ token-id]))

(defprotocol AccountStore
  (authenticate-account [_ {:keys [identifier password]}])
  (get-account     [_ sub])
  (add-device-account! [_ device-id sub remember?])
  (get-device-account  [_ device-id sub])
  (list-device-accounts [_ device-id])
  (remove-device-account! [_ device-id sub])
  (set-authorized-client! [_ sub client-id data])
  (get-authorized-client  [_ sub client-id]))

(defprotocol DeviceStore
  (create-device! [_ device-id data])
  (read-device    [_ device-id])
  (update-device! [_ device-id data])
  (delete-device! [_ device-id]))

(defprotocol ReplayStore
  (unique? [_ namespace jti expires-at]
    "True iff jti has not been seen in namespace; records it."))

(defn memory-stores
  "All-protocol in-memory implementation for tests/dev." [])
```

```clojure
(ns atproto.oauth.provider.dpop
  "Server-side DPoP proof verification (RFC 9449). Pure where possible.")

(defn check-proof
  "Validate a DPoP proof JWT against the request.
  {:proof <jwt> :method :post :url ... :access-token <string|nil>
   :nonce-required? bool}
  Sync. Success: {:jkt <thumbprint> :jti ...}
  Error: {:error \"InvalidDpopProof\" :message ...} or
         {:error \"UseDpopNonce\" :dpop-nonce <next>}"
  [verifier request])

(defn next-nonce
  "Current rotating nonce value for response headers." [verifier])
```

```clojure
(ns atproto.oauth.provider.client
  "OAuth client metadata fetching & atproto-profile validation.")

(defn get-client
  "Resolve client-id (URL or loopback) to validated client metadata + JWKS.
  Async (HTTP fetch + cache). Error: {:error \"InvalidClientMetadata\" ...}"
  [registry client-id & {:as opts}])

(defn authenticate-client
  "Verify client authentication (none | private_key_jwt assertion). Async."
  [registry client credentials & {:as opts}])
```

```clojure
(ns atproto.oauth.provider.ring
  "Ring routes for the provider endpoints (clj only).")

(defn routes
  "Ring handler covering:
   GET  /.well-known/oauth-authorization-server
   GET  /oauth/jwks
   POST /oauth/par
   POST /oauth/token
   POST /oauth/revoke
   GET  /oauth/authorize   (delegates rendering to :hooks)
   POST /oauth/authorize/sign-in | /accept | /reject (hook-driven)
  Adds DPoP-Nonce headers and OAuth-shaped error bodies."
  [provider & {:keys [hooks]}])
```

### 11B — `atproto.pds.actor-store` family (clj only)

```clojure
(ns atproto.pds.blobstore
  "BlobStore protocol; mirrors packages/repo/src/storage/types.ts:33-45.")

(defprotocol BlobStore
  (put-temp!      [_ bytes-or-stream] "-> temp key (random base32)")
  (make-permanent! [_ key cid])
  (put-permanent! [_ cid bytes-or-stream])
  (quarantine!    [_ cid])
  (unquarantine!  [_ cid])
  (get-blob-bytes [_ cid] "throws/returns nil if missing")
  (get-blob-stream [_ cid])
  (has-temp?      [_ key])
  (has-stored?    [_ cid])
  (delete-blob!   [_ cid])
  (delete-blobs!  [_ cids]))

(defn disk-blobstore
  "{:base-dir ... :tmp-dir ... :quarantine-dir ...} scoped per DID.
   Layout: {base}/{did}/{cid}, {tmp}/{did}/{key}, {quarantine}/{did}/{cid}."
  [did opts])
```

```clojure
(ns atproto.pds.actor-store
  "Per-actor durable storage: sharded directory of SQLite DBs + signing key.
   Layout: {base}/{sha256-hex(did)[0:2]}/{did}/store.sqlite , .../key")

(defn init
  "{:directory ... :blobstore-factory (fn [did] -> BlobStore)}" [config])

(defn create-actor!
  "Create directory, persist exported signing key, run migrations.
  Async. Error: {:error \"ActorAlreadyExists\"}"
  [store did keypair & {:as opts}])

(defn actor-exists? [store did & {:as opts}])
(defn destroy-actor! [store did & {:as opts}])

(defn read-actor
  "Open the actor DB read-only and call (f reader); reader exposes
  the WS-04 readable-blockstore protocol + record/blob read fns. Async."
  [store did f & {:as opts}])

(defn transact-actor!
  "Open the actor DB, run (f transactor) inside a SQLite transaction.
  transactor implements the WS-04 blockstore protocol (put-block!, put-blocks!,
  apply-commit!, update-root!) plus record index + blob fns below. Async."
  [store did f & {:as opts}])
```

```clojure
(ns atproto.pds.actor-store.record
  "Record + backlink index inside an actor transaction. Synchronous,
   called with the open transactor.")

(defn index-record! [txn {:keys [uri cid record repo-rev]}])
(defn delete-record! [txn uri])
(defn get-record [reader uri] "-> {:uri :cid :value :indexed-at} | nil")
(defn list-records [reader {:keys [collection limit cursor reverse?]}])
(defn list-collections [reader])
```

### 11C — `atproto.pds.sequencer` / `atproto.pds.firehose` (clj only)

```clojure
(ns atproto.pds.sequencer
  "Durable, totally-ordered event log backing subscribeRepos.
   Single SQLite DB with the repo_seq table
   (cf. packages/pds/src/sequencer/db/schema.ts).")

(defn init
  "{:db-path ...}. Runs migrations, starts the poll loop. Async."
  [config & {:as opts}])

(defn close! [seq'r & {:as opts}])

;; Write side — called by repo write paths (future PDS assembly).
;; Event maps are DAG-CBOR-encoded (WS-02); commit/sync blocks are CAR
;; bytes produced by WS-04's writer (cf. events.ts:17-45).
(defn sequence-commit!   [seq'r did commit-data & {:as opts}]) ; -> {:seq n}
(defn sequence-sync!     [seq'r did sync-data & {:as opts}])
(defn sequence-identity! [seq'r did handle & {:as opts}])
(defn sequence-account!  [seq'r did status & {:as opts}])

;; Read side — cursor queries (sync, cheap).
(defn current-seq [seq'r])              ; -> long | nil
(defn next-after [seq'r cursor])        ; -> event-row | nil
(defn earliest-after-time [seq'r time]) ; -> event-row | nil
(defn request-range
  "Decoded events with seq in (earliest-seq, latest-seq], optionally
   bounded by :earliest-time and :limit; skips invalidated rows."
  [seq'r {:keys [earliest-seq latest-seq earliest-time limit]}])

(defn subscribe
  "Register a live listener: (callback [events]) on every poll batch.
   Returns an unsubscribe thunk."
  [seq'r callback])
```

```clojure
(ns atproto.pds.firehose
  "com.atproto.sync.subscribeRepos emission, registered through WS-08's
   atproto.xrpc.server/handle-subscription (WS-08 owns the websocket
   upgrade, frame encoding, per-route auth, and close codes; this ns owns
   the outbox). Implements the outbox algorithm (backfill -> cutover ->
   live) from packages/pds/src/sequencer/outbox.ts and the cursor
   semantics from
   packages/pds/src/api/com/atproto/sync/subscribeRepos.ts:20-42.")

(defn handler
  "handle-subscription implementation for com.atproto.sync.subscribeRepos.
  Installed as
  (defmethod xrpc.server/handle-subscription \"com.atproto.sync.subscribeRepos\"
    [ctx request] (firehose/handler sequencer request ...)).
  Returns {:messages ch} per the WS-08 contract
  (08-service-auth-xrpc-server.md, Interface contract: \"WS-11 registers
  com.atproto.sync.subscribeRepos through this\"); the outbox feeds ch and
  stops when the request's :close-ch closes (client disconnect). Params
  and route auth are validated before upgrade by WS-08's
  subscription-request.

  opts:
  :max-buffer-size       bounded outbox buffer (default 500); overflow emits
                         {:frame/error \"ConsumerTooSlow\"} on ch — WS-08's
                         transport sends the error frame and closes 1008
  :backfill-window-ms    default 86400000 (1 day)

  Cursor semantics (checked against the sequencer at subscription time):
  - cursor > current seq  -> {:frame/error \"FutureCursor\"} as the first item
  - cursor older than backfill window -> #info message
    {:name \"OutdatedCursor\"} first, then resume from earliest in-window event
  - no cursor -> live-tail only.
  Message maps carry :$type; WS-08's frame layer lifts it into header
  t = #commit|#sync|#identity|#account|#info."
  [sequencer request & {:as opts}])
```

### 11D — decision memo

`docs/planning/11d-plc-directory-memo.md` (no code): requirements inventory,
reuse analysis, recommendation (default: defer; revisit if/when a Clojure PDS
needs a self-hosted directory for air-gapped deployments).

## Interface contract

**Provided (frozen once each sub-stream's first milestone merges):**
- `atproto.oauth.provider/verify-access-token` — consumed by WS-08's auth
  middleware and the future PDS assembly. Input/output exactly as sketched
  above.
- `atproto.pds.blobstore/BlobStore` protocol — consumed by future
  `uploadBlob`/`getBlob` handlers.
- `atproto.pds.actor-store` `read-actor`/`transact-actor!` — consumed by
  future repo write paths.
- `atproto.pds.sequencer` write API (`sequence-*!`) and
  `atproto.pds.firehose/handler` — consumed by future PDS assembly.

**Consumed (restated assumptions + how to develop before they merge):**
- **WS-02 (`atproto.data.cbor`)**: `(encode data) -> bytes`,
  `(decode bytes) -> data`, deterministic/canonical, CID-compatible. Needed by
  11C (event payloads) and 11B tests. *Before merge:* 11C M1 (sequencer
  storage/query logic) can develop against a stub
  `atproto.pds.sequencer/encode-event*` indirection storing EDN bytes; swap to
  CBOR before the firehose milestone. Frame-level golden tests wait for WS-02.
- **WS-03 (keys)**: keypair create/export/import, `sign`/`verify`, did:key.
  Needed by 11B (`create-actor!` key persistence) and the future commit path.
  *Before merge:* the actor-store treats key material as opaque bytes; only
  `keypair` accessor semantics need WS-03.
- **WS-04 (repo)**: the blockstore protocol (readable: `get-bytes`,
  `has-block?`, `get-blocks`; writable: `get-root`, `put-block!`,
  `put-blocks!`, `update-root!`, `apply-commit!` with CommitData
  `{:cid :rev :since :prev :new-blocks
  :removed-cids :relevant-blocks}`), `BlockMap`, and
  `blocks->car`/`car->blocks`. 11B *implements* the protocol; 11C consumes
  CommitData + CAR. *Before merge:* code 11B against a local copy of the
  protocol in a clearly-marked stub namespace and vendor
  `packages/repo/tests/car-file-fixtures.json` for read-side tests; rebase
  onto WS-04's protocol namespace before merging 11B M2 (the protocol names
  above must be reconciled with WS-04's published charter — see Risks).
- **WS-03 (jwt)**: `atproto.runtime.jwt/parse`/`verify`/`sign` (did:key /
  raw-pubkey / JWK key modes; 00-overview §4.2). 11A M1 extends `verify`
  with keyset + embedded-JWK modes. *Before merge:* rebase 11A onto
  `ws/03-crypto`; do not implement a parallel `verify`.
- **WS-08 (server auth + stream transport)**: (a) the auth integration
  point — WS-08's auth middleware calls `verify-access-token`; nothing for
  WS-11 to stub. (b) The frame codec `atproto.xrpc.frames`
  (`encode`/`decode`/`message-frame`/`error-frame`), matching
  `packages/xrpc-server/src/stream/frames.ts:14-60`. (c) The websocket
  subscription transport: `atproto.xrpc.server/handle-subscription`
  multimethod with the `{:messages ch}`/`:close-ch` convention,
  `{:frame/error ...}` → error frame + close 1008, and pre-upgrade
  params/auth validation via `subscription-request`
  (`08-service-auth-xrpc-server.md`, Interface contract). *Before merge:*
  11C M1-M2 (sequencer + outbox) are pure data and need no frames or
  transport; the firehose milestone waits for WS-08 (or stubs the codec
  with the same two-CBOR-items layout once WS-02 is in).
- **WS-05 (firehose client)**: the Clojure `com.atproto.sync.subscribeRepos`
  *client* (`atproto.sync.firehose`), used only by 11C integration tests.
  *Before merge:* 11C unit tests don't need it; the integration test waits
  for WS-05 or drives raw frames with a bare test websocket client.

## File ownership

Created by sub-streams (repo-relative):

```
src/atproto/oauth/provider.cljc            11A
src/atproto/oauth/provider/store.cljc      11A (protocols + memory impls)
src/atproto/oauth/provider/store/sqlite.clj 11A
src/atproto/oauth/provider/dpop.cljc       11A
src/atproto/oauth/provider/client.cljc     11A
src/atproto/oauth/provider/ring.clj        11A
src/atproto/runtime/jwt.cljc               11A (MODIFIED: extend WS-03's verify; WS-03 owns the file)
src/atproto/pds/blobstore.clj              11B
src/atproto/pds/blobstore/disk.clj         11B
src/atproto/pds/blobstore/s3.clj           11B (optional, alias-gated dep)
src/atproto/pds/actor_store.clj            11B
src/atproto/pds/actor_store/sqlite.clj     11B (blockstore impl + migrations)
src/atproto/pds/actor_store/record.clj     11B
src/atproto/pds/sql.clj                    11B (shared next.jdbc/SQLite helpers, WAL, retry; 11A+11C reuse)
src/atproto/pds/sequencer.clj              11C
src/atproto/pds/firehose.clj               11C
test/atproto/oauth/provider_test.clj       11A
test/atproto/oauth/provider/dpop_test.cljc 11A
test/atproto/oauth/provider/client_test.clj 11A
test/atproto/pds/blobstore_test.clj        11B
test/atproto/pds/actor_store_test.clj      11B
test/atproto/pds/sequencer_test.clj        11C
test/atproto/pds/firehose_test.clj         11C
test/interop-test-files/repo/car-file-fixtures.json       11B (vendored)
test/interop-test-files/repo/commit-proof-fixtures.json   11B (vendored)
docs/planning/11d-plc-directory-memo.md    11D
```

Shared-file conflicts and resolutions:
- `deps.edn` — 11A/11B/11C add `org.xerial/sqlite-jdbc` +
  `com.github.seancorfield/next.jdbc` (one addition, first sub-stream to land
  adds it); other workstreams also touch `deps.edn` — additions are
  single-line, **later branch rebases**.
- `src/atproto/runtime/jwt.cljc` — **WS-03 owns this file** and lands
  `parse`/`verify`/`sign` first (00-overview conflict matrix; `03-crypto.md`
  File ownership). Resolution: 11A extends WS-03's `verify` with keyset +
  embedded-JWK key modes on top, rebasing onto `ws/03-crypto` if it hasn't
  merged yet. WS-08 consumes `jwt/verify` for service auth but never touches
  the file; neither does WS-10.
- `test/interop-test-files/repo/*` — same fixtures WS-04 vendors. Whoever
  lands first creates the directory; the other rebases (files are identical
  copies from `packages/repo/tests/`, so conflicts are content-free).
- `README.md` progress matrix rows 28/31-33 — update only in each sub-stream's
  final milestone PR to avoid cross-branch conflicts.

## Test plan

**Unit (per sub-stream):**
- 11A: DPoP `check-proof` table-driven tests — generate proofs with the
  *existing client* (`src/atproto/oauth/client/dpop.cljc:19-46`) and assert
  acceptance, then mutate each claim (`htm`, `htu` with query/fragment
  normalization, stale `iat`, replayed `jti`, wrong `ath`, missing nonce →
  `UseDpopNonce`) and assert the right error map. Client-metadata validation
  tests (port rules from `client-manager.ts`: scheme checks, loopback
  synthesis, `dpop_bound_access_tokens` required). Token state machine tests
  over memory stores: code single-use (concurrent `consume-request-code!`),
  PKCE mismatch, refresh rotation + reuse detection, expiry. Metadata document
  validated against the spec'd required fields.
- 11B: blockstore conformance — run WS-04's storage conformance suite (or,
  until it exists, a local suite asserting `put/get/has/get-blocks/missing`,
  `apply-commit!` root+block+removal effects) against both the SQLite impl and
  WS-04's memory impl. Blobstore lifecycle tests on temp dirs: putTemp →
  makePermanent idempotency, quarantine/unquarantine, delete-many. Record
  index round-trips incl. backlinks. Actor-store sharding/layout +
  create/destroy/exists.
- 11C: sequencer ordering (monotonic seq across interleaved writers),
  `request-range` filters, `earliest-after-time`; outbox three-phase tests
  ported from `packages/pds/tests/sequencer.test.ts` scenarios: backfill-only,
  backfill+live cutover without dupes/gaps under concurrent sequencing,
  buffer-overflow → ConsumerTooSlow.

**Interop fixtures to vendor:**
- `packages/repo/tests/car-file-fixtures.json` and
  `packages/repo/tests/commit-proof-fixtures.json` →
  `test/interop-test-files/repo/` (11B read-side; coordinate with WS-04).
- `interop-test-files/` upstream has only `crypto/` and `syntax/` (already
  vendored under `test/interop-test-files/`) — there are no service-side
  fixtures to vendor; flow correctness comes from self-conformance and the
  reference-PDS comparison below.

**Integration:**
- 11A: full PAR → authorize (scripted hook auto-approves) → token → refresh →
  revoke loop driven by `atproto.oauth.client` against the Ring routes served
  on a local http-kit server, including the DPoP-nonce retry path the client
  already implements (`dpop.cljc:59-76`).
- 11C: serve `subscribeRepos` through WS-08's xrpc-server websocket transport
  on http-kit; consume with WS-05's Clojure firehose client; assert cursor
  semantics (`FutureCursor` error frame, `OutdatedCursor`
  info frame with a tiny configured backfill window, seq continuity across
  reconnects).

**Against a live/reference service:**
- Run the TS reference PDS via `packages/dev-env`
  (`pnpm --filter @atproto/dev-env start`), point the Clojure firehose client
  (WS-05) at it, capture frames, and byte-compare our frame *structure*
  (header/body CBOR layout, field sets per event type) for equivalent events
  emitted by 11C. For 11A, additionally run the reference
  `@atproto/oauth-client-node` example against the Clojure provider as a
  second, independent client.

## Acceptance criteria

- [x] Sub-stream charters (this doc) reviewed; protocol sketches in
      Deliverables accepted by the owners of WS-03, WS-04, WS-08 (names
      reconciled with their published contracts — the code consumes
      `atproto.repo.blockstore`, `atproto.xrpc.frames`,
      `xrpc.server/handle-subscription`, and `runtime.jwt/verify` as merged).
- [x] 11A: `atproto.oauth.client` completes authorize→token→refresh→revoke
      against `atproto.oauth.provider` over real HTTP with DPoP nonces
      enforced, using both memory and SQLite stores; `verify-access-token`
      rejects tokens with mismatched DPoP keys.
      (`test/atproto/oauth/provider/conformance_test.clj`;
      `provider_test.clj` `verify-access-token-test`.)
- [x] 11A: provider metadata + JWKS endpoints validate against
      `oauth-types` schema requirements (required fields, PAR advertised and
      required). (Required fields asserted directly in `metadata-test`/
      `jwks-test`; no formal `oauth-types` schema document is applied.)
- [x] 11B: SQLite blockstore passes the same conformance suite as the memory
      blockstore; vendored CAR fixtures load and round-trip
      block-for-block. (`actor_store_test.clj` runs one
      `blockstore-conformance-suite` against both, plus
      `car-fixtures-round-trip-test`.)
- [x] 11B: disk blobstore lifecycle (temp→permanent→quarantine→delete) green
      on CI temp dirs; actor-store create/read/transact/destroy green.
- [x] 11C: a consumer connecting with an old in-window cursor receives every
      event exactly once across backfill/cutover/live under concurrent writes;
      `FutureCursor`/`OutdatedCursor`/`ConsumerTooSlow` behaviors match the
      reference handler. (`firehose_test.clj`
      `backfill-cutover-live-exactly-once-test` et al.)
- [x] 11C: frames consumed and decoded by the WS-05 firehose client; structure
      matches reference-PDS frames for equivalent events.
      (`firehose_test.clj` connects `atproto.sync.firehose` to the served
      `subscribeRepos` endpoint; the byte-level comparison against a live
      reference PDS remains a manual check — see "Against a live/reference
      service" above.)
- [ ] 11D: decision memo merged with an explicit recommendation. *(Dropped by
      decision: the memo was written and then removed in commit `41f23bf` —
      deferring the PLC directory is a scope call, not a standing document.
      The README "Identity Directory" row stays ⭕.)*
- [x] README progress matrix rows for OAuth Backend / Repo Storage / Stream
      Server updated in each sub-stream's final PR.
- [x] All milestones below merged with green `clj -X:test` builds (except
      11B M4, which was optional and not built, and 11D M1, dropped as above).

## Milestones

Each is one PR, independently mergeable, build green. Ordering reflects the
dependency map: **11A can start any time after WS-03** (M1 extends WS-03's
`jwt/verify`; everything else needs only existing runtime); 11B M2+ wait on
WS-04's protocol; 11C M3 waits on WS-02 (+ WS-04 CAR for commit events); the
firehose milestone waits on WS-08's transport and uses WS-05's client for
integration tests.

1. **WS-11 M0 (this branch):** planning docs (this file); no code.
2. **11A M1 (after WS-03, or rebasing onto `ws/03-crypto`):** extend WS-03's
   `atproto.runtime.jwt/verify` with keyset + embedded-JWK key modes, with
   unit tests; no behavior change elsewhere.
3. **11A M2:** `atproto.oauth.provider.client` — metadata fetch, validation,
   loopback clients, caching; `metadata`/`jwks` document generation.
4. **11A M3:** `atproto.oauth.provider.dpop` (verification + rotating nonces)
   and `atproto.oauth.provider.store` protocols + memory stores.
5. **11A M4:** PAR endpoint + authorization request lifecycle + `authorize`/
   `complete-sign-in` state machine with login hooks.
6. **11A M5:** `token`/`revoke` + `verify-access-token`; end-to-end
   conformance test with `atproto.oauth.client`.
7. **11A M6:** `atproto.oauth.provider.ring` routes + SQLite stores (adds
   `atproto.pds.sql` helper ns + sqlite/next.jdbc deps if not yet present);
   README row flip.
8. **11B M1:** `atproto.pds.blobstore` protocol + disk impl + tests (no
   WS-04 dependency).
9. **11B M2 (after WS-04 protocol freeze):** SQLite blockstore +
   migrations + conformance suite + vendored repo fixtures.
10. **11B M3:** actor-store layout/lifecycle + record/backlink/blob index;
    README row flip.
11. **11C M1 (after WS-02):** sequencer (db, write API, cursor queries, poll
    loop) + ported ordering tests.
12. **11C M2:** outbox (backfill/cutover/live, bounded buffer) — pure
    core.async, tested without websockets.
13. **11C M3 (after WS-08, WS-04 CAR):** event formatting (CBOR/CAR) +
    `subscribeRepos` registration via WS-08's `handle-subscription` +
    integration test with the WS-05 firehose client; README row flip.
14. **11B M4 (optional):** S3 blobstore in an alias-gated namespace.
15. **11D M1:** PLC directory decision memo.

## Risks & open questions

1. **WS numbering/contract drift.** The exact namespace names for WS-02
   (`atproto.data.cbor`), WS-04's blockstore protocol fns, and WS-08's frame
   codec are assumptions from this charter, not published contracts. Each
   sub-stream's first dependent milestone must re-verify against the sibling
   planning docs before coding. *Mitigation:* the Interface contract section
   above states what is assumed; treat mismatches as doc fixes, not code
   workarounds.
2. **11A scale.** Even subsetted, the provider is the largest single piece in
   the parity plan (~13k TS lines upstream excluding UI/scopes). The milestone
   split keeps PRs reviewable, but expect 11A alone to take longer than most
   whole workstreams. If pressure mounts, M6 (SQLite stores) can slip — memory
   stores are enough for the conformance demo.
3. **Atproto OAuth spec evolution.** Upstream is mid-transition on scopes
   (`oauth-scopes` permission sets, `LexiconStore`). Recommendation: implement
   plain scope strings only, isolate scope parsing behind one fn so permission
   sets can be added without touching the token path. Track
   https://github.com/bluesky-social/proposals (oauth) before M5.
4. **Library choice — SQLite access.** Recommendation:
   `org.xerial/sqlite-jdbc` + `com.github.seancorfield/next.jdbc`, raw SQL
   strings (no HoneySQL), WAL mode + busy-timeout retry helper in
   `atproto.pds.sql` (mirrors `retrySqlite` in `packages/pds/src/db`).
   Datalevin/datahike rejected: the reference schema is intentionally
   relational-with-blobs and we want byte-identical storage semantics.
5. **Library choice — S3.** Recommendation: `cognitect.aws/api` (pure-Clojure,
   data-driven) over the AWS Java SDK, in an alias-gated namespace so core
   users never pull AWS deps. Decide at 11B M4; defer if unneeded.
6. **http-kit websocket backpressure.** http-kit's `send!` is fire-and-forget;
   the reference relies on a bounded `AsyncBuffer` to detect slow consumers
   (`outbox.ts:93-99`). WS-08 owns the socket/send loop and flags the same
   http-kit limitation in its doc (`08-service-auth-xrpc-server.md`, Risks:
   "Backpressure semantics on http-kit"). 11C implements the bound on its
   side of the contract: a bounded outbox feeding the `{:messages ch}`
   channel that emits `{:frame/error "ConsumerTooSlow"}` when the
   per-connection buffer overflows (WS-08's transport then sends the error
   frame and closes 1008). If http-kit proves too opaque, the transport
   fallback (e.g. Jetty 11 websocket API) is WS-08's call — raise it with
   WS-08 before 11C M3.
7. **`.cljc` ambition vs. reality.** Service pieces are JVM-only today
   (SQLite, filesystem, websocket server). Keep pure logic `.cljc` (dpop
   checks, metadata validation, outbox algorithm) but do not block milestones
   on CLJS support; the README matrix only credibly targets the Clojure
   column for these rows.
8. **Minimal-PDS gap: PLC client operations.** Account creation on a real
   network needs signed `did:plc` operations (or did:web). That is the README
   "PLC Operations" row, owned outside WS-11 — flag to the orchestrator that
   the future PDS-assembly workstream needs it chartered (the reference is
   `@did-plc/lib`, consumed at `packages/pds/package.json:54`).
9. **Sequencer single-writer assumption.** The reference uses one
   `repo_seq` DB with SQLite autoincrement as the total order; multi-process
   PDS deployments would break it. Accept the constraint (document it on
   `atproto.pds.sequencer/init`) — same constraint as upstream.
10. **Login UI hooks design.** Replacing 135 files of React with hooks is a
    design risk: the hook contract (what state the host app receives for
    sign-in/consent, CSRF handling, device cookies) has no Clojure precedent.
    Spike it inside 11A M4 with the Statusphere example app as the host before
    freezing the hook signatures.
