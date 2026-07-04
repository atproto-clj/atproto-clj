# WS-06: Identity Completion & PLC Operations

| | |
|---|---|
| **Status** | Implemented (M1–M5 merged together, 2026-06-11) |
| **Priority** | P1 |
| **Estimated size** | M |
| **Branch** | ws/06-identity-plc |
| **Depends on** | WS-03 (crypto contract: ES256K/ES256 sign/verify + did:key) — PLC-ops milestones only; WS-02 (atproto.data.cbor contract: deterministic DAG-CBOR encode) — PLC-ops milestones only. Milestones M1–M3 (did:web, cache, PLC read client) have **zero** dependencies. |
| **Blocks** | Any workstream that needs verified identities, DID-doc signing-key extraction, or signature verification by DID (e.g. firehose/sync commit verification, service-auth JWT verification, PDS/account tooling). Existing consumers (`atproto.oauth.client`, `atproto.credentials`) keep working unchanged. |

## Goal

`atproto.identity` becomes a complete, production-grade identity layer: all atproto-blessed DID methods resolve (`did:plc` *and* `did:web`), DID documents are validated against the requested DID, resolution results are cached through a pluggable SDK-level cache with stale-while-revalidate semantics, and a new `atproto.identity.plc` namespace can build, sign, derive, submit, and verify `did:plc` operations (genesis, update, key rotation, tombstone, audit-log verification) against the PLC directory.

## Current state

All refs verified on branch `redesign`.

**What works** (`src/atproto/identity.cljc`):

- DID parsing and spec validation: `parse-did` (identity.cljc:25-30), `::did` spec built from `::lexicon/did` + per-method multi-spec (identity.cljc:36-41). Method specs for `plc` (24-char base32, identity.cljc:56-60) and `web` (no path, no port except localhost, identity.cljc:98-108).
- `did:plc` resolution via `https://plc.directory/{did}` with redirect-following disabled and proper accept header (identity.cljc:62-75). 404 → `{:error "DidNotFound"}`, other failures → `http/error-map` (src/atproto/runtime/http.cljc:33-37).
- did:web ⇄ URL mapping helpers: `web-did-msid->url` (identity.cljc:77-84), `web-did->url` (86-89), `url->web-did` (91-96).
- Handle resolution: DNS TXT on `_atproto.{handle}` parsing `did=` values, exactly-one rule (identity.cljc:121-142) over a real JNDI DNS interceptor (src/atproto/runtime/dns.cljc:14-37); HTTPS fallback `GET https://{handle}/.well-known/atproto-did` with 3000ms timeout (identity.cljc:144-155); orchestration DNS-then-HTTPS in `resolve-handle` (identity.cljc:157-168).
- DID-document accessors: `did-doc-pds` (identity.cljc:174-183), `did-doc-handles` (185-191), `did-doc-also-known-as?` (193-196).
- Bidirectional handle verification in `verified-identity` (identity.cljc:202-212) and the public entry point `resolve-identity` (identity.cljc:237-249).

**What's missing / broken** (this workstream fixes all of these):

1. **`did:web` resolution is `NotImplemented`** — `(defmethod fetch-did-doc "web" [did cb] (cb {:error "NotImplemented"}))` (identity.cljc:110-112).
2. **No SDK-level cache.** The only caching anywhere is an app-level atom in the statusphere example (`examples/statusphere/src/xyz/statusphere/api.clj:11`, `(defonce did-handle-store (atom {}))`). Every `resolve-identity` call hits the network.
3. **No PLC operations.** Verified: nothing in the SDK can create, sign, or submit PLC ops, fetch operation/audit logs, or derive a `did:plc` from a genesis op. No DAG-CBOR encoder exists yet either (`src/atproto/data.cljc` only creates/parses CIDs from already-encoded bytes, data.cljc:24-31, 56-79).
4. **Bug: `resolve-identity` never completes for invalid input.** The `cond` at identity.cljc:246-248 has no `:else`; if the input is neither a valid `::did` nor a valid `::handle`, the callback is never invoked and the returned promise/channel hangs forever.
5. **Bug: unsupported DID methods throw instead of erroring.** `fetch-did-doc` is a defmulti with no `:default` (identity.cljc:43-45); `(resolve-did "did:key:zXyz")` throws `IllegalArgumentException` synchronously out of `resolve-did` (identity.cljc:47-54) instead of returning `{:error "UnsupportedDidMethod"}` through the async value. (Note `resolve-identity` guards via spec, but `resolve-did` is public.)
6. **Bug/gap: no DID-document validation.** Acknowledged in the todo at identity.cljc:17. The resolved document's `:id` is never checked against the requested DID — `verified-identity` (identity.cljc:202-212) trusts `(:id did-doc)`, so a malicious or misconfigured server could return a document for a *different* DID and the SDK would report that DID as resolved. The reference rejects this (`validateDidDoc`, packages/identity/src/did/base-resolver.ts:18-26).
7. **Bug (minor): HTTPS handle fallback is too lax.** identity.cljc:152-155 trims the entire body and returns it as a DID without checking it parses as a DID, and doesn't split on newline. Reference takes the first line and requires the `did:` prefix (packages/identity/src/handle/index.ts:52-57). Also, `resolve-handle-with-https` routes the plain-text body through `json/client-interceptor` — harmless today because that interceptor only parses JSON content-types (src/atproto/runtime/json.cljc:12-17, 37-43) but it should simply be dropped from the queue.
8. **Gap: no signing-key extraction.** There is no equivalent of `getSigningKey`/`getDidKeyFromMultibase` to pull the `#atproto` verification method out of a DID doc as a `did:key` (needed by future signature-verification consumers).
9. **Nit: loose service-id matching.** `did-doc-pds` matches services with `(str/ends-with? (:id service) "#atproto_pds")` (identity.cljc:180); the reference requires the id to be exactly `#atproto_pds` or `{doc.id}#atproto_pds` (packages/common-web/src/did-doc.ts:109-140).

## Reference implementation guide

All paths under `/Users/luke/github/bluesky-social/atproto` unless noted.

### Resolution & caching: `packages/identity`

- `src/id-resolver.ts:5-17` — `IdResolver` composes a `HandleResolver` and a `DidResolver`; defaults: timeout 3000ms, `plcUrl` `https://plc.directory`.
- `src/did/did-resolver.ts:10-38` — method dispatch on the segment between `did:` and the second `:`; unknown method → `UnsupportedDidMethodError`. Note line 16: the cache is held by the *outer* resolver only ("do not pass cache to sub-methods or we will be double caching").
- `src/did/base-resolver.ts` — the heart of the caching algorithm:
  - `validateDidDoc` (18-26): schema-check the doc AND require `doc.id === did`, else `PoorlyFormattedDidDocumentError`.
  - `resolve` (42-64): if cached and not expired → if stale, kick a refresh (`refreshCache`, 34-40) then return the stale doc (**stale-while-revalidate**); on fresh miss, resolve; on positive not-found, **clear** the cache entry (58-60 — note: the reference does *not* negative-cache); on success, populate cache.
  - `verifySignature` (91-99): resolve the `#atproto` key as did:key, verify bytes — this is the contract downstream verification consumers want.
- `src/did/plc-resolver.ts:14-32` — GET `{plcUrl}/{did}`, redirect error, accept `application/did+ld+json,application/json`; 404 → null (positively not found) vs other non-ok → throw.
- `src/did/web-resolver.ts:9,19-50` — `DOC_PATH = '/.well-known/did.json'`; msid is split on `:` and URI-decoded; exactly one part → `https://{host}/.well-known/did.json`; more than one part → `UnsupportedDidWebPathError` (atproto does not support path-form did:web; the commented-out line 30 shows how the W3C `/did.json` path form *would* work); `localhost` host → `http` scheme (33-36); redirect error; non-ok → null.
- `src/did/memory-cache.ts:9-54` — default TTLs: `staleTTL` = 1 hour, `maxTTL` = 1 day (12-15); entries are `{doc, updatedAt}`; `checkCache` computes `stale`/`expired` booleans (33-45).
- `src/types.ts:31-53` — `CacheResult` shape and the `DidCache` interface (`cacheDid`, `checkCache`, `refreshCache`, `clearEntry`, `clear`).
- `src/did/atproto-data.ts:20-41` — signing-key → did:key conversion: `Multikey` multibase is parsed directly; legacy `EcdsaSecp256k1VerificationKey2019`/`EcdsaSecp256r1VerificationKey2019` types decode multibase bytes then format as did:key with the curve implied by the type.
- `src/handle/index.ts:17-34` — DNS and HTTPS race in parallel; DNS wins if it answers; HTTPS is aborted when DNS succeeds. `parseDnsResult` (77-84): join chunked TXT strings, filter `did=` prefix, require exactly one. (Current Clojure impl is sequential — acceptable, parallelizing is in scope as a nice-to-have, see todo identity.cljc:18.)
- `src/errors.ts:1-32` — the error taxonomy to mirror as `:error` strings: `DidNotFound`, `PoorlyFormattedDid`, `UnsupportedDidMethod`, `PoorlyFormattedDidDocument`, `UnsupportedDidWebPath`.

### DID syntax & doc parsing: `packages/did`, `packages/common-web`

- `packages/did/src/methods/web.ts:51-83` — canonical did:web ⇄ URL transforms incl. port (`%3A`) and path (`:` → `/`) handling, `localhost` http rule (76-79).
- `packages/did/src/atproto.ts:51-69` — `assertAtprotoDidWeb`: atproto rejects path components and non-localhost ports (matches the existing Clojure spec at identity.cljc:98-108).
- `packages/common-web/src/did-doc.ts` — `getSigningKey` (33-37), `getVerificationMaterial` (39-58), `getPdsEndpoint` (66-71), `getServiceEndpoint` (87-107), strict id matching in `findItemById` (109-140), the `didDocument` zod schema (188-200) to port as a clojure.spec.

### PLC operations: outside the monorepo

`did:plc` operation code lives in **https://github.com/did-method-plc/did-method-plc** (not in the bluesky-social/atproto monorepo, and not vendored in its node_modules — the PDS consumes it as the npm package `@did-plc/lib`, see packages/pds/package.json:54). Spec: **https://web.plc.directory/spec/v0.1/did-plc** (URL verified live 2026-06-10).

Key sources in that repo, `packages/lib/src/` (verified file list: client.ts, data.ts, document.ts, error.ts, index.ts, operations.ts, types.ts):

- `operations.ts` — `didForCreateOp` (DAG-CBOR encode signed genesis op → SHA-256 → lowercase base32 → truncate to 24 chars → `did:plc:{...}`); `addSignature`/`signOperation` (DAG-CBOR encode op *without* `sig`, sign, base64url-encode signature without padding); `formatAtprotoOp`/`atprotoOp`/`createOp`; `createUpdateOp` (takes last op, computes its CID for `prev`, applies a transform fn, signs); convenience ops `updateAtprotoKeyOp`, `updateHandleOp`, `updatePdsOp`, `updateRotationKeysOp`; `tombstoneOp`; `normalizeOp` (legacy `create` → `plc_operation` shape); `assureValidCreationOp`, `assureValidSig`.
- `data.ts` — `validateOperationLog`: genesis must validate via `assureValidCreationOp`; each subsequent op's `prev` must equal the CID of the previous op; signatures checked against the rotation keys of the doc state at that point; tombstone only valid as final op (`MisorderedOperationError`); nullified-op forks only allowed within a **72-hour recovery window** (`LateRecoveryError`). `opToData` extracts `DocumentData` (`did`, `verificationMethods`, `rotationKeys`, `alsoKnownAs`, `services`).
- `client.ts` — directory HTTP surface: GET `/{did}` (DID doc), GET `/{did}/data`, GET `/{did}/log`, GET `/{did}/log/audit`, GET `/{did}/log/last`, POST `/{did}` (submit signed op), GET `/export?after=&count=`, GET `/_health`.

Spec facts to encode (from the v0.1 spec page):

- Operation shape (`type` `"plc_operation"`): `rotationKeys` (1–5 did:keys, secp256k1 or P-256 only), `verificationMethods` (map name → did:key, e.g. `"atproto"`), `alsoKnownAs` (ordered URI array, handles as `at://` URIs), `services` (map name → `{type, endpoint}`, e.g. `"atproto_pds"` → `{"type" "AtprotoPersonalDataServer", "endpoint" "https://..."}`), `prev` (string CID of previous op; `null` for genesis), `sig` (base64url, no padding).
- Tombstone: `{type "plc_tombstone", prev <cid>, sig <sig>}` only.
- Legacy genesis (`type` `"create"`): `signingKey`, `recoveryKey`, `handle` (bare), `service`, `prev: null`, `sig` — must be accepted when verifying old audit logs (normalize per `normalizeOp`).
- Signatures: ECDSA over SHA-256 of the DAG-CBOR bytes of the op minus `sig`; **low-S required**; encoded as 64 bytes r‖s big-endian; base64url unpadded. High-S or non-canonical encodings are rejected on verification.
- `prev` CIDs: CIDv1, `dag-cbor` (0x71), `sha2-256`, base32 string form — exactly what `atproto.data/cid-link` + `format-cid` already produce given the bytes (src/atproto/data.cljc:24-31, 66-69).

### PDS usage patterns worth mirroring (monorepo)

- `packages/pds/src/api/com/atproto/identity/signPlcOperation.ts:53-76` — fetch last op (`getLastOp`), reject tombstones, `createUpdateOp` with a transform that merges user-provided `rotationKeys`/`alsoKnownAs`/`verificationMethods`/`services` over the last op.
- `packages/pds/src/api/com/atproto/identity/submitPlcOperation.ts:19-53` — validation before submit (op schema, rotation key present, `atproto_pds` service type/endpoint, signing key, handle in `alsoKnownAs[0]`), then `sendOperation` and force-refresh the DID cache.

### Interop test fixtures

- `/Users/luke/github/bluesky-social/atproto/interop-test-files/` contains only `crypto/` and `syntax/` — **no identity- or PLC-specific fixtures exist there**. Relevant and already vendored into this repo at `test/interop-test-files/`: `crypto/signature-fixtures.json` (incl. high-S rejection cases — WS-03's problem, but PLC verification tests build on it), `syntax/did_syntax_{valid,invalid}.txt`, `syntax/handle_syntax_{valid,invalid}.txt`.
- Test-data goldmines to port as fixtures (inline data in tests, vendor as JSON/EDN):
  - `packages/identity/tests/did-document.test.ts:4-103` — bad doc, legacy-key doc, Multikey doc, with expected `did/handle/pds/signingKey`.
  - `packages/did/tests/methods/web.test.ts:11-41` — did:web valid/invalid ⇄ URL tables.
  - `packages/identity/tests/handle-resolver.test.ts:3-37` — DNS TXT parse cases (simple/noisy/bad/multi).
  - `packages/identity/tests/did-cache.test.ts:48-102` — cache behaviors to replicate (cache-on-lookup, stale-serve+revalidate, expired-forces-refresh).
- For PLC: no vendorable fixtures in either repo; vendor **live audit logs** (see Test plan).

## Scope

### In scope

- Implement `fetch-did-doc "web"`: bare-domain msid → `https://{host}/.well-known/did.json` (http for `localhost`, the only place a port is allowed), redirects disabled, accept header as for plc; path-form did:web → `{:error "UnsupportedDidWebPath"}`; 404/non-success → `{:error "DidNotFound"}`.
- Add `:default` method for `fetch-did-doc` returning `{:error "UnsupportedDidMethod"}`; make `resolve-did` never throw.
- DID-document validation: `::did-doc` spec (port of common-web didDocument schema) + enforcement that `(:id did-doc)` equals the requested DID → `{:error "PoorlyFormattedDidDocument"}`.
- Fix `resolve-identity` hang on invalid identifier (`:else` → `{:error "InvalidAtIdentifier"}`).
- Harden HTTPS handle fallback (first line, trim, must conform to `::did`); drop the JSON interceptor from that request.
- Strict service/verification-method id matching (`#atproto_pds` / `{did}#atproto_pds`) in `did-doc-pds`; new `did-doc-signing-key` accessor (returns `{:type ... :public-key-multibase ...}`) and `did-doc-signing-did-key` (Multikey → `did:key:` directly; legacy 2019 key types via WS-03's `pubkey->did-key` + `multibase->bytes` composition once available).
- `atproto.identity.cache`: pluggable cache protocol (get/set/del/clear of entries with `:updated-at`), default in-memory implementation, TTL policy (`:stale-ttl`/`:max-ttl`, defaults 1h/24h), stale-while-revalidate, optional negative caching via `:negative-ttl` (off by default — parity with reference), integration into `resolve-did`/`resolve-identity` via a `:cache` option plus `:force-refresh` flag.
- `atproto.identity.plc`: operation specs (`plc_operation`, `plc_tombstone`, legacy `create`), `normalize-op`, deterministic signing payload, `sign-op`, `did-for-create-op` (base32(sha256(dag-cbor(signed-genesis-op)))[0:24]), `cid-for-op`, genesis creation (`create-op`), generic `update-op` plus handle/pds/rotation-keys/signing-key convenience builders, `tombstone-op`.
- PLC directory HTTP client: `get-did-doc`, `get-data`, `get-operation-log`, `get-audit-log`, `get-last-op`, `submit`, `export` against a configurable `:plc-url` (default `https://plc.directory`).
- Audit-log verification (optional final milestone): port of `validateOperationLog` — genesis validation, CID chain, rotation-key signature authority, tombstone-must-be-last, 72h recovery-window check for nullified ops.
- Tests for all of the above, including vendored fixture data (see Test plan).

### Out of scope

- **ES256K/ES256 keypair generation, signing, verification, low-S enforcement, did:key formatting/parsing, multibase/multicodec decoding** — owned by **WS-03**. WS-06 only *calls* that contract.
- **DAG-CBOR encoder/decoder** — owned by **WS-02** (`atproto.data.cbor`). WS-06 only calls `encode`.
- `com.atproto.identity.*` XRPC endpoints (signPlcOperation/submitPlcOperation lexicon calls against a PDS) — these are plain XRPC procedures; whichever workstream owns generated/typed XRPC client coverage owns them. WS-06 talks to the PLC *directory* directly.
- Handle→DID result caching and parallel DNS/HTTPS handle racing — the reference IdResolver does not cache handles; defer (note in Risks).
- Backup-nameserver DNS fallback (packages/identity/src/handle/index.ts:63-75) — defer; JNDI configuration differs materially.
- The PLC directory `/export` firehose-style streaming endpoint (`wss://plc.directory/export/stream`) — only the paginated HTTP GET is in scope.
- App-level identity storage (statusphere keeps its own atom; migrating the example to the new cache is a nice-to-have follow-up, not required for green).

## Deliverables

### 1. `atproto.identity.cache` (new, .cljc)

Mirrors the protocol style of `atproto.oauth.client.store` (src/atproto/oauth/client/store.clj:6-23): synchronous protocol, the SDK computes TTL policy so implementations stay dumb.

```clojure
(ns atproto.identity.cache
  "Pluggable cache for identity resolution results.")

(defprotocol Cache
  "Storage interface. Entries are maps with at least :val and :updated-at (epoch ms).
   Implementations need no TTL logic; policy is computed by `check`."
  (get* [cache k] "The entry map for k, or nil.")
  (set* [cache k entry] "Store the entry map under k. Returns nil.")
  (del* [cache k] "Remove k. Returns nil.")
  (clear* [cache] "Remove all entries. Returns nil."))

(defn memory-cache
  "Default in-memory Cache backed by an atom. Unbounded; pass your own
   implementation (e.g. LRU, redis) for production multi-node setups."
  ([] ...)
  ([entries-atom] ...))

(def default-policy
  {:stale-ttl (* 1000 60 60)        ; 1 hour, ref: memory-cache.ts:13
   :max-ttl   (* 1000 60 60 24)     ; 1 day,  ref: memory-cache.ts:14
   :negative-ttl nil})              ; negative caching disabled by default

(defn check
  "Look up k and classify against the policy. Returns nil on miss, else
   {:val <cached value>
    :updated-at <ms>
    :stale? <bool>      ; updated-at + stale-ttl < now
    :expired? <bool>    ; updated-at + max-ttl < now
    :negative? <bool>}  ; entry is a cached not-found marker"
  [cache policy k] ...)

(defn store
  "Cache a successful value under k (sets :updated-at to now)."
  [cache k val] ...)

(defn store-negative
  "Cache a not-found marker under k (only if (:negative-ttl policy))."
  [cache policy k] ...)

(defn evict [cache k] ...)
```

### 2. `atproto.identity` (modified)

All public fns keep the existing async convention: trailing `& {:as opts}`, `i/platform-async`, errors as `{:error "Name" :message "..."}` maps (see src/atproto/runtime/interceptor.cljc:129-169).

```clojure
(s/def ::did-doc
  "DID document spec ported from packages/common-web/src/did-doc.ts:188-200:
   required :id string; optional :alsoKnownAs [string], :verificationMethod
   [{:id :type :controller & :publicKeyMultibase/:publicKeyJwk}], :service
   [{:id :type :serviceEndpoint}]."
  ...)

(defn validate-did-doc
  "Validate a fetched doc against ::did-doc and check (:id doc) = did.
   Returns the doc or {:error \"PoorlyFormattedDidDocument\" :did did}."
  [did doc] ...)

;; (defmethod fetch-did-doc "web" [did cb] ...)
;;   bare domain  -> GET https://{host}/.well-known/did.json
;;   localhost    -> http scheme (the only host allowed a port)
;;   path-form    -> (cb {:error "UnsupportedDidWebPath"})
;;   404/non-2xx  -> (cb {:error "DidNotFound"})
;;   ref: packages/identity/src/did/web-resolver.ts:19-50

;; (defmethod fetch-did-doc :default [did cb]
;;   (cb {:error "UnsupportedDidMethod" :did did}))

(defn resolve-did
  "Resolve a DID to a validated DID document.

  Options:
    :cache          atproto.identity.cache/Cache impl (no caching when absent)
    :cache-policy   merged over atproto.identity.cache/default-policy
    :force-refresh  bypass the cache read (still writes on success)
    :plc-url        PLC directory base URL (default \"https://plc.directory\")

  Caching semantics (ref base-resolver.ts:42-64):
    fresh hit -> cached doc; stale hit -> cached doc + async revalidate;
    expired/miss -> fetch; DidNotFound -> evict entry (or store-negative
    when :negative-ttl set); success -> store.
  Async: returns {:did-doc doc} or error map."
  [did & {:as opts}] ...)

(defn resolve-handle
  "Unchanged signature; hardened HTTPS fallback (first line, ::did check)."
  [handle & {:as opts}] ...)

(defn resolve-identity
  "Unchanged signature plus the same :cache/:cache-policy/:force-refresh/:plc-url
   options as resolve-did. Now returns {:error \"InvalidAtIdentifier\"} for
   inputs that are neither a valid DID nor a valid handle."
  [at-identifier & {:as opts}] ...)

(defn did-doc-signing-key
  "The verification material registered for the given key id (default \"atproto\").
   Returns {:type ... :public-key-multibase ...} or nil.
   ref: common-web/src/did-doc.ts:39-58 (strict id matching)."
  [did-doc & {:keys [key-id]}] ...)

(defn did-doc-signing-did-key
  "did:key string for the doc's atproto signing key. Multikey types map
   directly to did:key:{publicKeyMultibase}; legacy EcdsaSecp256k1/r1
   VerificationKey2019 types convert via WS-03's composition
   (pubkey->did-key alg (multibase->bytes publicKeyMultibase)).
   ref: packages/identity/src/did/atproto-data.ts:20-41"
  [did-doc] ...)
```

### 3. `atproto.identity.plc` (new, .cljc)

Pure operation building/derivation is synchronous; anything touching the network is async per SDK convention.

```clojure
(ns atproto.identity.plc
  "did:plc operations and PLC directory client.
   Spec: https://web.plc.directory/spec/v0.1/did-plc
   Reference: https://github.com/did-method-plc/did-method-plc packages/lib")

(def default-plc-url "https://plc.directory")

;; --- specs -------------------------------------------------------------
;; ::operation        {:type "plc_operation" :rotationKeys [did-key...](1-5)
;;                     :verificationMethods {name did-key} :alsoKnownAs [uri]
;;                     :services {name {:type s :endpoint s}}
;;                     :prev (nilable cid-string) :sig base64url}
;; ::tombstone        {:type "plc_tombstone" :prev cid-string :sig base64url}
;; ::legacy-create-op {:type "create" :signingKey :recoveryKey :handle
;;                     :service :prev nil :sig}
;; ::op               (s/or ::operation ::tombstone ::legacy-create-op)

(defn normalize-op
  "Legacy create op -> plc_operation shape; pass plc_operation/tombstone through.
   ref: operations.ts normalizeOp"
  [op] ...)

(defn signing-payload
  "DAG-CBOR bytes of (dissoc op :sig). Consumes WS-02 atproto.data.cbor/encode."
  [op] ...)

(defn sign-op
  "Sign an unsigned op with a rotation keypair (WS-03 contract: low-S ES256K/ES256
   compact 64-byte signature). Adds :sig as unpadded base64url. Async per the
   SDK callback convention (crypto/sign is async — overview §4.2).
   Yields the signed op or {:error ...}."
  [op rotation-keypair & {:as opts}] ...)

(defn cid-for-op
  "CIDv1 dag-cbor sha2-256 base32 string of the signed op's DAG-CBOR bytes;
   used as :prev in the next op. Uses atproto.data/cid-link + format-cid
   (src/atproto/data.cljc:24-31, 66-69)."
  [signed-op] ...)

(defn did-for-create-op
  "did:plc derivation: \"did:plc:\" + first 24 chars of lowercase, unpadded
   RFC 4648 base32 of (sha256 (dag-cbor signed-genesis-op)).
   ref: operations.ts didForCreateOp; spec §DID Creation."
  [signed-genesis-op] ...)

(defn create-op
  "Build and sign a genesis op; derive its DID.
   opts: :signing-key (did:key string), :rotation-keys [did:key...],
         :handle, :pds, :signer (rotation keypair, WS-03).
   Returns {:did ... :op signed-op} or {:error ...}.
   ref: operations.ts createOp / atprotoOp; did-cache.test.ts:28-34 for shape."
  [opts] ...)

(defn update-op
  "Build and sign an update: prev = (cid-for-op last-op), state = (f (normalize-op
   last-op)). Rejects tombstoned last-op with {:error \"DidTombstoned\"}.
   ref: operations.ts createUpdateOp; pds signPlcOperation.ts:53-76."
  [last-op signer f] ...)

(defn update-handle-op        [last-op signer handle] ...)  ; alsoKnownAs[0] = at://handle
(defn update-pds-op           [last-op signer url] ...)     ; services.atproto_pds
(defn update-rotation-keys-op [last-op signer did-keys] ...)
(defn update-signing-key-op   [last-op signer did-key] ...) ; verificationMethods.atproto

(defn tombstone-op
  "{:type \"plc_tombstone\" :prev (cid-for-op last-op)} signed."
  [last-op signer] ...)

;; --- directory client (async; all take & {:as opts} with :plc-url) -----

(defn get-did-doc       [did & {:as opts}] ...)  ; GET /{did}
(defn get-data          [did & {:as opts}] ...)  ; GET /{did}/data
(defn get-operation-log [did & {:as opts}] ...)  ; GET /{did}/log
(defn get-audit-log     [did & {:as opts}] ...)  ; GET /{did}/log/audit
(defn get-last-op       [did & {:as opts}] ...)  ; GET /{did}/log/last
(defn submit
  "POST the signed op as JSON to /{did}. Async {:success true} or error map
   (HTTP 4xx surfaces the directory's message)."
  [did signed-op & {:as opts}] ...)
(defn export
  "GET /export?count=&after= ; returns {:ops [...]} (JSON lines parsed)."
  [& {:keys [count after] :as opts}] ...)

;; --- verification (optional milestone; needs WS-03 verify-did-sig) -----

(defn verify-op-sig
  "Verify op signature against a collection of allowed rotation did:keys.
   ref: operations.ts assureValidSig" [allowed-did-keys op] ...)

(defn verify-create-op
  "Genesis validation: normalized, prev nil, sig valid against own
   rotationKeys, did matches did-for-create-op.
   ref: operations.ts assureValidCreationOp" [did op] ...)

(defn verify-operation-log
  "Port of data.ts validateOperationLog: genesis valid; each op's :prev equals
   cid-for-op of predecessor; sig authorized by rotation keys of prior state
   (fork recovery within 72h window, else {:error \"LateRecovery\"}); tombstone
   only last ({:error \"MisorderedOperation\"}). Returns the final document
   data {:did :verificationMethods :rotationKeys :alsoKnownAs :services} or
   {:tombstoned true} or an error map. Takes the did + ops vector (e.g. from
   get-audit-log)."
  [did ops] ...)
```

### 4. `atproto.runtime.crypto` (small additions, coordinate with WS-03)

`sha256` currently only accepts a String (src/atproto/runtime/crypto.cljc:23-27); PLC derivation needs it over bytes. Add a bytes-accepting arity (or `sha256-bytes`). Lowercase unpadded base32 can come from the existing `mvxcvi/multiformats` dep (`multiformats.base`) — prefer that over adding to crypto. **WS-03 also modifies this file: WS-03 lands first, WS-06 rebases.**

## Interface contract

### Provided (frozen once M1/M2 merge — other workstreams may code against this)

- `atproto.identity/resolve-identity`, `resolve-did`, `resolve-handle` — signatures unchanged from today plus the new options `{:cache :cache-policy :force-refresh :plc-url}`; error names: `DidNotFound`, `HandleNotFound`, `UnsupportedDidMethod`, `UnsupportedDidWebPath`, `PoorlyFormattedDidDocument`, `InvalidAtIdentifier`, `HandleTooLong`, `HTTP_*`.
- `atproto.identity/did-doc-signing-did-key` — `did-doc -> did:key string | nil` (verification consumers, e.g. sync/firehose).
- `atproto.identity.cache/Cache` protocol + `memory-cache` — anyone may supply an implementation.
- `atproto.identity.plc` — the API above; in particular `verify-operation-log` and the directory getters for any future PDS/account-migration tooling.

### Consumed (frozen contracts — signatures below match overview §4.2/§4.1 and the owning docs' "Interface contract" sections)

From **WS-03** (crypto, `atproto.crypto` — key operations are **async** per the SDK callback convention, parsing/encoding sync; frozen per 03-crypto.md Interface contract and overview §4.2):

- `(generate alg & {:keys [exportable?] :as opts}) -> async Keypair | {:error ...}` — alg is `"ES256K"` (secp256k1) or `"ES256"` (P-256).
- `(sign keypair msg-bytes & opts) -> async 64-byte compact low-S r‖s signature bytes`.
- `(verify-did-sig did-key sig-bytes msg-bytes & opts) -> boolean` — note arg order: **sig-bytes before msg-bytes**; strict by default (rejects high-S/non-canonical); returns `{:error ...}` only for a malformed/unsupported did:key.
- `(did keypair) -> "did:key:z..."` — the keypair's did:key accessor (not `did-key`).
- Legacy verification-method conversion is a **composition, not a single fn** (overview §4.2 — there is no `multibase->did-key`): `Multikey` type → `(parse-multikey multikey-str)` then `(pubkey->did-key alg bytes)`; legacy `EcdsaSecp256k1VerificationKey2019`/`EcdsaSecp256r1VerificationKey2019` → `(pubkey->did-key alg (multibase->bytes publicKeyMultibase))`. (Overview §8.3 item 3 leaves a `multibase->did-key` convenience wrapper as an orchestrator call; compose unless that lands in WS-03.)

From **WS-02** (data/CBOR): `(atproto.data.cbor/encode data) -> bytes`, deterministic DAG-CBOR (RFC 8949 canonical map-key ordering as profiled by DAG-CBOR).

**Developing before the deps merge:** PLC op maps contain only strings, arrays, maps, and one `nil` (`prev` in genesis) — no byte strings, no CID links — so a ~40-line test-only canonical CBOR encoder (or vendored golden byte fixtures from real PLC ops) unblocks `signing-payload`/`cid-for-op`/`did-for-create-op` development. For signing, stub WS-03 with a fake signer returning fixed bytes and assert payload bytes + structure; real-signature tests are gated on WS-03 merging. Real audit-log fixtures (vendored from plc.directory, see Test plan) let `cid-for-op`, `did-for-create-op`, chain checking, and `normalize-op` be validated end-to-end *without* any crypto: every derived CID/DID must match the `cid` fields in the audit log.

## File ownership

| File | Status | Conflicts |
|---|---|---|
| `src/atproto/identity.cljc` | modify | None known. |
| `src/atproto/identity/cache.cljc` | create | None. |
| `src/atproto/identity/plc.cljc` | create | None. |
| `src/atproto/runtime/crypto.cljc` | modify (bytes sha256 arity only) | **WS-03 owns this file; WS-03 lands first, WS-06 rebases.** If WS-03 already adds a bytes-capable sha256, drop this change. |
| `test/atproto/identity_test.cljc` | modify | None. |
| `test/atproto/identity/cache_test.cljc` | create | None. |
| `test/atproto/identity/plc_test.cljc` | create | None. |
| `test/atproto/identity/fixtures/*.json` | create (vendored fixtures, see below) | None. |
| `deps.edn` | no change expected (multiformats/nimbus/tink already present) | Flag to orchestrator if a CBOR lib decision (WS-02) adds one. |

## Test plan

### Unit tests (no network)

- **did:web** (`test/atproto/identity_test.cljc`): extend the existing mapping table (identity_test.cljc:33-39) with cases from `packages/did/tests/methods/web.test.ts:11-41`; assert path/port forms are rejected by the spec and that resolution of a path-form did returns `{:error "UnsupportedDidWebPath"}`. Resolution plumbing tested by stubbing the HTTP interceptor (swap the `::i/queue`-built request through a fake interceptor, the pattern used implicitly by the interceptor design — no HTTP needed).
- **DID-doc validation**: vendor the three documents from `packages/identity/tests/did-document.test.ts:4-103` into `test/atproto/identity/fixtures/did-doc-{bad,legacy-key,multikey}.json`; assert bad doc → `PoorlyFormattedDidDocument`, mismatched `:id` → same error, and `did/handle/pds` extraction matches the TS expectations (signing-key did:key equality for the legacy doc is gated on WS-03).
- **Handle DNS parsing**: port the four TXT scenarios from `packages/identity/tests/handle-resolver.test.ts:3-37` (simple/noisy/bad/multi) by faking the dns interceptor's `:values`; assert HTTPS fallback DID hygiene (multi-line body, junk body → `HandleNotFound`).
- **Cache** (`test/atproto/identity/cache_test.cljc`): replicate `packages/identity/tests/did-cache.test.ts:48-102` against `memory-cache` with an injectable clock: cache-on-lookup; stale entry served and then revalidated (assert refreshed value on second read); expired entry not served (forces refetch); `:force-refresh` bypass; negative caching honored when `:negative-ttl` set and absent by default; `resolve-identity` end-to-end with a stubbed `fetch-did-doc` counting fetches.
- **PLC ops** (`test/atproto/identity/plc_test.cljc`):
  - Operation/tombstone/legacy specs accept/reject vectors.
  - `normalize-op` legacy → plc_operation equivalence.
  - With WS-02 (or the test-only encoder): `signing-payload` excludes `:sig`; `did-for-create-op` is 24 lowercase base32 chars and stable; `cid-for-op` matches `atproto.data/format-cid` of `cid-link`.
  - **Golden audit-log fixtures**: vendor (one-time fetch, commit the JSON) `https://plc.directory/did:plc:yk4dd2qkboz2yv6tpubpc6co/log/audit` (an old DID with a legacy `create` genesis op — same DID used in packages/identity/tests/did-document.test.ts:5) and one recent DID with `plc_operation` genesis and ≥1 update, into `test/atproto/identity/fixtures/audit-log-*.json`. Assert: recomputed genesis DID equals the fixture DID; each op's recomputed CID equals the audit log's recorded `cid`; `prev` chain validates; final state matches `GET /{did}/data` (also vendored).
  - With WS-03: `sign-op` round-trips through `verify-op-sig`; fixture signatures verify; mutated op bytes fail.
  - `verify-operation-log`: happy path on fixtures; tombstone-not-last → `MisorderedOperation`; broken `prev` → error; op signed by non-rotation key → error. (Recovery-window/fork tests use hand-built logs with the fake signer.)
- **Regression**: `resolve-identity` on garbage input returns `{:error "InvalidAtIdentifier"}` (deref with timeout to prove no hang); `resolve-did` on `did:key:...` returns `{:error "UnsupportedDidMethod"}` without throwing.

### Integration tests (live network, behind a `:live` test selector / manual)

- Resolve `did:plc:` for a stable account against `https://plc.directory`; resolve a known live `did:web` identity; resolve a handle with DNS TXT (e.g. one of the maintainer's domains) and one that only serves `.well-known/atproto-did`.
- Fetch + verify the full audit log of a real DID live.
- PLC submit: run the reference PLC server locally from https://github.com/did-method-plc/did-method-plc (`packages/server`); create-op → submit → resolve-did against it → update-handle-op → submit → tombstone-op → submit → resolve returns not-found. (This mirrors `packages/identity/tests/did-cache.test.ts:17-42`.) Manual/CI-optional; never run against the production directory.

Run with `clj -M:test` (cognitect test-runner, deps.edn `:test` alias).

## Acceptance criteria

- [x] `(resolve-did "did:web:example.com")`-shaped calls fetch and validate `https://example.com/.well-known/did.json`; localhost gets http; path-form did:web returns `{:error "UnsupportedDidWebPath"}`; `fetch-did-doc "web"` no longer returns `NotImplemented`.
- [x] Every resolved DID document is schema-validated and its `:id` checked against the requested DID; mismatch yields `{:error "PoorlyFormattedDidDocument"}`.
- [x] `resolve-did`/`resolve-identity` accept `:cache`, `:cache-policy`, `:force-refresh`; with a `memory-cache`, a second resolve within `:stale-ttl` performs zero network calls; a stale-but-not-expired hit returns the cached doc and triggers a background revalidate; an expired hit refetches.
- [x] `resolve-identity` with an invalid identifier returns `{:error "InvalidAtIdentifier"}` (no hang); `resolve-did` with an unsupported method returns `{:error "UnsupportedDidMethod"}` (no throw).
- [x] `did-for-create-op` recomputes the exact DID of vendored real-world audit logs (both legacy `create` and `plc_operation` genesis forms); `cid-for-op` reproduces every `cid` recorded in those logs.
- [x] `create-op`/`update-op`/`update-handle-op`/`update-pds-op`/`update-rotation-keys-op`/`update-signing-key-op`/`tombstone-op` produce ops conforming to the PLC v0.1 spec, signed with low-S unpadded-base64url signatures that `verify-op-sig` (and the reference directory, in the manual integration test) accept.
- [x] PLC directory client covers GET `/{did}`, `/{did}/data`, `/{did}/log`, `/{did}/log/audit`, `/{did}/log/last`, `/export`, POST `/{did}`, with directory error bodies surfaced in error maps.
- [x] `verify-operation-log` validates vendored audit logs end-to-end and rejects misordered tombstones, broken prev chains, unauthorized signers, and >72h-late recoveries.
- [x] All public fns follow SDK conventions: .cljc, `& {:as opts}` + `i/platform-async`, `{:error ...}` maps, specs for inputs; `clj -M:test` green on every milestone merge.

## Milestones

1. **M1 — did:web + resolution hardening** ✅ (no deps). Implement `fetch-did-doc "web"`, `:default` method, `::did-doc` validation, `resolve-identity` `:else` fix, HTTPS-fallback hygiene, strict id matching, `did-doc-signing-key`. Tests: did:web tables, DID-doc fixtures, DNS parse cases, regression tests.
2. **M2 — identity cache** ✅ (no deps). `atproto.identity.cache` (protocol, memory impl, policy/check/store), wire into `resolve-did`/`resolve-identity` with SWR + optional negative caching. Tests: cache suite with fake clock.
3. **M3 — PLC read-side** ✅ (no deps). `atproto.identity.plc` specs, `normalize-op`, directory GET client (`get-did-doc`/`get-data`/`get-operation-log`/`get-audit-log`/`get-last-op`/`export`). Vendor audit-log + data fixtures. Tests: specs, normalization, client error mapping (stubbed HTTP).
4. **M4 — op construction & DID derivation** ✅ (needs WS-02 contract; WS-03 stubbable). `signing-payload`, `cid-for-op`, `did-for-create-op`, `sign-op`, `create-op`, `update-op` + convenience builders, `tombstone-op`, `submit`. Tests: golden CID/DID recomputation against fixtures; structure tests with fake signer; real-signature tests if WS-03 has merged.
5. **M5 — audit-log verification** ✅ (needs WS-03 `verify-did-sig`). `verify-op-sig`, `verify-create-op`, `verify-operation-log` incl. recovery-window logic. Tests: fixture verification + adversarial hand-built logs. Optional manual integration against a locally run reference PLC server.

Each milestone is an independently mergeable PR leaving `clj -M:test` green (M4 merges with fake-signer tests if WS-03 hasn't landed; the WS-03-gated tests are added in M5 or a follow-up).

## Risks & open questions

- **WS-03/WS-02 contract dependency.** The fn names under "Consumed" have been reconciled against the frozen contracts (docs/planning/03-crypto.md:330-344, docs/planning/02-dag-cbor-data-model.md:259-267, overview §4.1/§4.2) — `verify-did-sig` (sig-bytes before msg-bytes), `(did keypair)`, and the `parse-multikey`/`pubkey->did-key`/`multibase->bytes` compositions. If WS-03 changes its surface before merging (e.g. the §8.3 `multibase->did-key` convenience question), re-reconcile before M4. Mitigation: M1–M3 have zero deps; M4 development uses the test-only CBOR encoder + fake signer described in the Interface contract.
- **If WS-02 slips**: PLC ops need only the {string, array, map, null} subset of DAG-CBOR. Decision needed: wait, or land a private `atproto.identity.plc/encode-op-cbor` (clearly marked internal, deleted when WS-02 merges). **Recommendation: wait for the WS-02 contract unless it slips more than ~2 weeks past M3; the duplicate-encoder risk (canonical key ordering subtleties: length-first, then bytewise UTF-8) outweighs the schedule gain.**
- **`atproto.runtime.crypto` is contended with WS-03.** Agreed resolution: WS-03 lands first; WS-06 rebases and drops its `sha256` change if WS-03 provides a bytes digest.
- **Negative caching diverges from the reference** (base-resolver.ts:58-60 evicts rather than negative-caches). Recommendation implemented above: support `:negative-ttl` but default it to `nil` so default behavior is reference-exact.
- **Handle caching**: the reference IdResolver does not cache handle→DID. The cache protocol here is key-generic so it *could*, but wiring it is deferred to keep parity. Revisit if statusphere-style apps need it in the SDK.
- **Library choice (base32)**: use `mvxcvi/multiformats` (`multiformats.base`, already a dep — deps.edn:10) for the lowercase unpadded base32 in `did-for-create-op` rather than hand-rolling or adding a dep. Verify its alphabet/padding matches RFC 4648 lowercase-no-pad in M4's golden tests (the fixture DIDs will catch any mismatch immediately).
- **JNDI DNS** has no per-query timeout configured (src/atproto/runtime/dns.cljc:11-12); slow resolvers delay handle resolution. Out of scope to rework, but M1 may set `com.sun.jndi.dns.timeout.initial/retries` env values via the `InitialDirContext` Hashtable if trivially doable.
- **Live-service tests are flaky by nature**; keep them behind a selector and never auto-run submit tests against the production directory (tombstones are irreversible).
- **CIDv1 base32 leading multibase prefix**: `atproto.data/format-cid` emits the `b`-prefixed multibase string (data.cljc:66-69) which is exactly what PLC `prev`/audit `cid` fields use — but assert this in the golden tests rather than trusting it.
