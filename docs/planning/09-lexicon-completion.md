# WS-09: Lexicon Resolution & Validation Modes

| | |
|---|---|
| **Status** | Planning |
| **Priority** | P1 |
| **Estimated size** | M |
| **Branch** | ws/09-lexicon-completion |
| **Depends on** | none (hard). Soft: WS-02 (atproto.data / atproto.data.json codec contract — legacy blob-ref handling at the *data* layer stays in WS-02; this workstream only adds the *lexicon-level* lenient toggle on top of whatever `::data/blob`-adjacent specs exist). Future: a repo/CAR/MST workstream is required before resolver proof-verification can be added (see Risks). |
| **Blocks** | Any workstream that consumes schema-driven validation or network lexicon resolution: the XRPC client/server workstreams (`request-spec-key`/`response-spec-key`/`register-specs!` contract), codegen (bundled lexicons as input), jetstream/firehose record validation, and the ClojureScript port (eval-free registration is the cljs unblock). |

## Goal

`atproto.lexicon.resolver` compiles, and resolves any NSID from the live network (DNS `_lexicon` TXT → authority DID → DID doc → PDS → `com.atproto.lexicon.schema` record), validates the fetched document, and caches it — with a dev helper that "installs" resolved schemas as JSON files under `resources/`. `atproto.lexicon/register-specs!` no longer uses `eval`, so schema-driven validation works on ClojureScript (with a build-time embedding macro for bundled schemas). Validation gains a strict/lenient mode toggle (lenient datetimes, legacy untyped blob refs), record-key constraints are enforceable, and the union-ref `type=object` TODO is resolved. `resources/` ships the canonical `com.atproto.*` (and `app.bsky.*`) lexicons with provenance and an update script, and `load-resources!` works from jars.

## Current state

All claims below re-verified on branch `redesign` (tests: `clojure -M:test` → "Ran 9 tests containing 731 assertions. 0 failures, 0 errors.").

**`src/atproto/lexicon/resolver.cljc` does not compile.**
`(require 'atproto.lexicon.resolver)` fails with `Syntax error compiling at (atproto/lexicon/resolver.cljc:24:36). No such namespace: nsid`. Specifically:

- `resolver.cljc:1-5` — the `ns` form requires only `atproto.runtime.interceptor`, `atproto.runtime.dns`, `atproto.runtime.json`, `atproto.xrpc.client`.
- `resolver.cljc:24` uses `nsid/parse` — no such namespace is required, and **no NSID-parsing helper exists anywhere in the SDK** (verified by grep; `atproto.lexicon` only has the `::nsid` conformer spec at `lexicon.cljc:51-56`).
- `resolver.cljc:25` uses `str/split` — `clojure.string` not required.
- `resolver.cljc:57` uses `did/resolve` and `resolver.cljc:62` uses `did/pds` — no `did` alias is required; the real functions are `atproto.identity/resolve-did` (`src/atproto/identity.cljc:47-54`) and `atproto.identity/did-doc-pds` (`identity.cljc:174-183`).
- `resolver.cljc:11-15` calls `xrpc/query` with a `{:atproto.session/service pds}` client and an `{:op ... :params ...}` request — a stale API. The current client is built with `atproto.xrpc.client/init` (`src/atproto/xrpc/client.cljc:18-25`, takes `{:service ...}`) and requests are `{:nsid ... :params ...}` maps (`client.cljc:122-144`).
- `resolver.cljc:18` uses raw `tap>` and `resolver.cljc:29` uses `println` instead of `atproto.runtime.cast`.
- `resolver.cljc:39-47` accepts the **first** `did=` TXT record when several exist; the reference implementation requires **exactly one** (see below) and validates the DID syntax (current code does neither).
- `resolver.cljc:31` returns a bare `{:error (str ...)}` rather than the SDK's `{:error "Name" :message "..."}` convention.

**`src/atproto/lexicon.cljc` gaps this workstream fixes:**

- `lexicon.cljc:709-714` — `register-specs!` `eval`s the forms produced by `translate`, which makes runtime schema registration JVM-only (no `eval` in compiled cljs).
- `lexicon.cljc:475-478` — record-key constraints deliberately ignored when translating `record` types ("ignore the record key in the validation (for now)"). The helper `rkey-type->spec` (`lexicon.cljc:466-473`) already maps conformed key types (`:tid`/`:nsid`/`:literal`/`:any`) to specs but is **dead code** (no callers).
- `lexicon.cljc:44-45` — TODO: "validate that the refs pointed to by `union` in the schema have `type=object`".
- `lexicon.cljc:726-728` — `*schema-validate*` defaults to `false`; no strict/lenient distinction exists anywhere (the datetime spec at `lexicon.cljc:62-68` is strict-only; the blob translation at `lexicon.cljc:577-582` only accepts typed `$type "blob"` refs via `::data/blob`, `src/atproto/data.cljc:103-112`).
- `lexicon.cljc:698-707` — `load-resources!` is `#?(:clj ...)`-only and uses `io/resource` → `io/file` → `file-seq`, which only works for exploded directories on disk — it will throw for resources inside a jar.
- `resources/` contains **only** `logo.png` — no lexicons are bundled, so `load-resources!` has nothing to load and `atproto.xrpc.server/init` (`src/atproto/xrpc/server.cljc:13-16`, calls `lexicon/register-specs!`) requires callers to supply schemas from elsewhere.
- `src/atproto/runtime/datetime.cljc:22-26` — `parse` has only a `:clj` branch (relevant when adding the lenient parser; keep the same `#?` discipline).
- `src/atproto/runtime/dns.cljc:14-37` — the DNS interceptor has only a `:clj` implementation; resolver DNS on cljs is therefore impossible today (noted under Risks, not fixed here).

**Contract points already consumed elsewhere (must not break):**

- `atproto.xrpc.client/request-validator` (`client.cljc:27-36`) binds `lexicon/*schema-validate*` and calls `s/valid?` on `(lexicon/request-spec-key nsid)`.
- `atproto.xrpc.server` (`server.cljc:14-15`, `131-139`) calls `lexicon/register-specs!` and uses `request-spec-key`/`response-spec-key` under `*schema-validate*` bindings.
- **The XRPC client has no response validation today.** `request-validator` is the only lexicon hook in `client.cljc`; responses pass through `handle-xrpc-response` (`client.cljc:85-91`) unvalidated, and `response-spec-key` is consumed only by the server (`server.cljc:138-141`). The TS lex-client *does* validate response bodies against the method's output schema (`packages/lex/lex-client/src/response.ts:261-273`, throwing `XrpcResponseValidationError` ⊂ `XrpcInvalidResponseError`, `errors.ts:322, 361-380`). WS-07 explicitly out-of-scopes this and defers it "to the lexicon workstream that owns `response-spec-key` consumers", reserving the `"InvalidResponse"` error name (`docs/planning/07-xrpc-client-ergonomics.md:134`). **Decision: WS-09 owns adding client response validation, in PR-3** (it owns the validator machinery and `*strict*`). This is a feature addition to `client.cljc`, not just a one-line binding — the 00-overview conflict-matrix row for `client.cljc` ("09 adds a one-line `*strict*` binding only", `00-overview.md:435`) and §4.9 should be amended by the orchestrator to record this.
- `test/atproto/lexicon_test.cljc:372-376` registers translated specs via `eval` (clj-only test today); the 731-assertion suite is the regression baseline for the eval-free compiler.

## Reference implementation guide

Reference repo: `/Users/luke/github/bluesky-social/atproto` at commit `b9ef5576beeb949bbac776dcbe4e4c2c7af60e74` (2026-06-10). Two generations of resolver exist; port behavior from both, prefer the newer for API shape:

**Resolution (older, self-contained): `packages/lexicon-resolver/`**
- `src/lexicon.ts:13-14` — constants `_lexicon` DNS subdomain, `did=` TXT prefix.
- `src/lexicon.ts:147-164` — DNS TXT resolution: join chunked TXT strings, filter `did=` prefix, require **exactly one** match (`found.length !== 1` → undefined).
- `src/lexicon.ts:54-96` — full resolution flow: NSID → DID authority (or `didAuthority` override, lines 133-145) → record resolution at `at://<did>/com.atproto.lexicon.schema/<nsid>` → check record `$matches` the `com.atproto.lexicon.schema` shape (line 75) → validate lexicon document (lines 79-84) → **check `lexicon.id` equals the NSID** (lines 87-92).
- `src/record.ts:68-122` — record resolution: resolve DID → extract `pds` + `signingKey` (lines 85-95) → `com.atproto.sync.getRecord` returns CAR proof bytes → `verifyRecordProof` (lines 155-184): read CAR, verify commit signature, walk MST for `collection/rkey`, read record block. **The Clojure SDK has no CAR/MST/commit-sig support; WS-09 uses unverified `com.atproto.repo.getRecord` instead — see Risks.**
- `src/record.ts:126-130` — fetch hardening: response size cap of 1 MB + 10 kB ("just a bit larger than max record size").
- `tests/lexicon.test.ts:12-21, 28-43` — tests run against a live dev-env PDS with a **mocked DNS layer** (in-memory `[entry, [["did=..."]]]` table). Nothing vendorable; this is the pattern for our stub-interceptor tests.

**Resolution (newer): `packages/lex/lex-resolver/src/lex-resolver.ts`**
- Lines 321-338 — two-step API: `resolve(nsid)` → AT-URI; `fetch(uri)` → `{uri, cid, lexicon}`; `get` composes them (lines 286-292).
- Lines 500-514 — `getDomainTxtDid`: exactly-one `did=` line, DID syntax asserted.
- Lines 411-482 — `fetchLexiconUri`: DID doc → pds + key, `com.atproto.sync.getRecord`, proof verification, schema validation, `lexicon.id === uri.rkey` check.
- Lines 102-155 — hook-based caching (`onFetch`/`onFetchResult`/`onResolveAuthority`...). We model this as an optional cache option instead.
- Line 340-343 — note that DNS is the only Node-specific piece (browser support requires injectable DNS) — same situation as our cljs story.

**Install/"lex CLI" mechanics: `packages/lex/lex-installer/src/lex-installer.ts`**
- Lines 317-328 — installed file path is `<lexicons-dir>/<nsid segments as dirs>.json` (e.g. `app.bsky.feed.post` → `app/bsky/feed/post.json`), written pretty-printed.
- Lines 104-119, 299-306, 336-342 — a manifest file records each installed NSID plus its resolution `{cid, uri}`; normalized for stable diffs.
- Lines 241-260 + 344-460 — recursive dependency install: walk every def for refs (`ref`, `union.refs`, `string.knownValues` token refs, nested `items`/`properties`/`input`/`output`/`message`), fetch missing NSIDs until fixpoint.

**Strict/lenient validation modes: `packages/lex/lex-schema/`**
- `src/core/validator.ts:150-188` — `ValidationOptions`: `strict` defaults to **true**; docstring: "allow more lax validation when parsing server responses, while enforcing strict validation for user input". (`mode: validate|parse` is about default-application/transformation — not in scope for us.)
- `src/core/string-format.ts:231-250` — the only formats with lenient variants are `at-uri` (`isAtUriStringLenient`, lines 66-74: rkey validity not enforced) and `datetime`.
- `packages/syntax/src/datetime.ts:128-155` — `isDatetimeStringLenient`: accepts "any ISO-ish datetime string" (e.g. missing timezone offset), falling back to the strict checker for valid-but-exotic fractions.
- `src/schema/blob.ts:59-112` — blob validation in non-strict mode: accepts **legacy untyped blob refs** and skips MIME-accept and maxSize checks; in strict mode a legacy ref (no size) under a `maxSize` constraint is a failure (lines 73-83).
- Legacy blob ref shape: `packages/lexicon/src/blob-refs.ts:15-21` — `{cid: string, mimeType: string}` (no `$type`, no `size`).

**Record-key constraints: `packages/lex/lex-schema/src/schema/record.ts:130-149`** — `recordKey(key)`: `"any"` → record-key format, `"tid"` → tid format, `"nsid"` → nsid format, `"literal:x"` → literal `x`. Note the record *value* validator does not check the key; the key validator is a separate schema applied where an rkey is in hand. Our `rkey-type->spec` (`lexicon.cljc:466-473`) already encodes the same mapping.

**Canonical schemas: `/Users/luke/github/bluesky-social/atproto/lexicons/`** — 388 JSON files total: `com/atproto/**` (95), `app/bsky/**` (156), plus `chat/bsky`, `tools/ozone`, `site`, `internal`. Key files:
- `lexicons/com/atproto/lexicon/schema.json` — the `com.atproto.lexicon.schema` record type (key: `nsid`, record requires `lexicon` int).
- `lexicons/com/atproto/lexicon/resolveLexicon.json` — `com.atproto.lexicon.resolveLexicon` query (server-side resolution endpoint returning `{uri, cid, schema}`, error `LexiconNotFound`) — optional fast-path, see Out of scope.

**Interop fixtures:** `/Users/luke/github/bluesky-social/atproto/interop-test-files/` contains only `crypto/` and `syntax/` — **no lexicon-resolution fixtures exist**. The syntax fixtures (incl. `datetime_syntax_valid.txt`, `datetime_parse_invalid.txt`) are already vendored at `test/interop-test-files/syntax/`. Resolver tests must therefore use stubbed DNS/HTTP interceptors, mirroring the TS test approach.

## Scope

### In scope

- Rewrite `atproto.lexicon.resolver` so it compiles and works:
  - `parse-nsid` helper (public, in `atproto.lexicon`): NSID string → `{:authority "feed.bsky.app" :name "post"}` with authority in *domain order* (segments minus name, reversed).
  - `resolve-nsid-authority`: DNS TXT lookup of `_lexicon.<authority>` via the `atproto.runtime.dns` interceptor; join/parse `did=` values; error unless exactly one; validate DID syntax.
  - `resolve-nsid`: authority DID (or `:did-authority` override) → `identity/resolve-did` → `identity/did-doc-pds` → `com.atproto.repo.getRecord` on the PDS with `{:repo did :collection "com.atproto.lexicon.schema" :rkey nsid}` via `atproto.xrpc.client` → validate the returned `:value` against `:atproto.lexicon.schema/file`, check `$type` and `(:id doc) == nsid` → return `{:nsid :did :uri :cid :lexicon}`.
  - Optional in-memory caching (`:cache` option + `:force-refresh`), simple atom-backed cache helper.
  - HTTP/DNS failures surfaced as `{:error "Name" :message ...}` maps; `cast` for diagnostics (no `tap>`/`println`).
- `install!` dev helper (clj-only): resolve an NSID and write the JSON document to `<dir>/<segments>.json` (default `resources/lexicons/`), with optional `:deps? true` transitive installation by walking refs (port `listDocumentNsidRefs`), and a manifest entry recording `{nsid {:uri ... :cid ...}}` plus source metadata.
- Eval-free spec registration: replace the `eval` in `register-specs!` (`lexicon.cljc:709-714`) with a closure-based validator compiler + internal registry (see Deliverables). `register-specs!`'s signature and the behavior of `request-spec-key`/`response-spec-key`/`record-spec`/`object-spec` are preserved. The 731-assertion suite must pass against the new path.
- Build-time embedding for cljs: a macro that reads lexicon JSON resources at macro-expansion time and emits literal schema data + a `register-specs!` call, so cljs builds get bundled schemas without runtime IO or eval. JVM keeps runtime `load-resources!`.
- Strict/lenient validation modes (lexicon level only):
  - New dynamic var `atproto.lexicon/*strict*` (default `true`).
  - Lenient `datetime`: add `atproto.runtime.datetime/parse-lenient` (ISO-8601-ish, timezone offset optional) and consult `*strict*` in the generated datetime format check.
  - Lenient `blob`: accept legacy untyped `{:cid string :mimeType string}` refs and skip `accept`/`maxSize` checks when `*strict*` is false; in strict mode, a `maxSize` constraint over a size-less ref fails (TS parity).
  - Lenient `at-uri`: don't enforce rkey record-key validity when `*strict*` is false.
  - Server side: bind `*strict* false` around the server's *existing* response validation (`server.cljc:138-141`) — a genuine one-line binding; request validation stays strict. TS guidance from `validator.ts:179-187`.
- **Client-side response validation (new feature, PR-3).** The client validates nothing about responses today (see Current state). Add a `response-validator` leave-stage interceptor to `atproto.xrpc.client`: on a successful (non-error) XRPC response, validate the body with `s/valid?` against `(lexicon/response-spec-key nsid)` under `*schema-validate*` (gated by a new `:validate-responses?` client-init flag mirroring `:validate-requests?`, `client.cljc:20-25`) with `*strict*` bound per the PR-3 policy decision (Risk 7); on failure return `{:error "InvalidResponse" :message ... :explain-data ...}` — the error name WS-07 reserved for exactly this (`07-xrpc-client-ergonomics.md:134`; TS `XrpcInvalidResponseError`/`XrpcResponseValidationError`, `lex-client/src/errors.ts:322, 361-380`, raised from `response.ts:261-273`). Error XRPC responses (bodies with `:error`) are never schema-validated against the output spec, matching `handle-xrpc-response` (`client.cljc:85-91`) and TS (`response.ts:204-215`). Owned here, not WS-07 — both docs previously punted to each other; this doc is now the owner of record.
- Record-key constraint enforcement: resurrect `rkey-type->spec` (`lexicon.cljc:466-473`) behind a public API — `record-key-spec`/`valid-record-key?` keyed by collection NSID — using the schema docs retained in the registry. Record *value* validation continues not to take an rkey (TS parity); the key check is a separate call for use by XRPC server / repo writers.
- Union-ref check (TODO `lexicon.cljc:44-45`): when constructing a Lexicon via `lexicon` (`lexicon.cljc:683-696`), resolve every `union` ref within the provided schema set; throw `ex-info` if a target resolves to a non-`object` type; `cast/dev` (don't fail) for refs that point outside the set (open-world).
- Bundle canonical lexicons: copy `com/atproto/**/*.json` (95 files) and `app/bsky/**/*.json` (156 files) from the reference repo into `resources/lexicons/`, add `resources/lexicons/manifest.edn` with provenance (source repo URL, commit SHA, fetch date, file list), and a `script/update-lexicons.sh` refresh script that regenerates files + manifest from a reference checkout.
- Make `load-resources!` jar-safe: enumerate via `manifest.edn` + `io/resource` per file instead of `file-seq` (`lexicon.cljc:698-707`); keep clj-only.

### Out of scope

- **Cryptographic proof verification of fetched lexicon records** (`com.atproto.sync.getRecord` + CAR/MST/commit-sig, as in `lex-resolver.ts:516-550`). Requires repo/CAR/MST machinery owned by the repo/sync workstream. The resolver's return shape includes `:cid`/`:uri` so verification can be layered in later without an API break.
- **Data-layer codec changes** (CBOR/JSON encode/decode of legacy blob refs, `atproto.data` / `atproto.data.json` specs): WS-02 owns `src/atproto/data.cljc` and `src/atproto/data/json.cljc`. WS-09 only consults `*strict*` inside *lexicon-generated* validators.
- **cljs DNS / full cljs resolver runtime**: `atproto.runtime.dns` has no `:cljs` implementation (`dns.cljc:14-37`); adding DNS-over-HTTPS belongs to the runtime/cljs workstream. WS-09 keeps the resolver `.cljc` with the same `#?(:clj ...)` posture as `dns.cljc` so it lights up when a cljs DNS interceptor lands.
- **Code generation** from lexicons (typed client namespaces à la `lex-cli`): separate workstream; this one only supplies the bundled schemas it would consume.
- **`com.atproto.lexicon.resolveLexicon` server endpoint implementation** (XRPC server feature). Optionally the resolver may *call* it as a fast path later; not in this workstream.
- **Identity-resolution hardening** (DID doc validation, retries, caching of DID docs): `atproto.identity` TODOs (`identity.cljc:16-19`) belong to the identity workstream; the resolver just calls `resolve-did`.

## Deliverables

### 1. `atproto.lexicon.resolver` (rewritten, `.cljc` with clj-gated internals)

```clojure
(ns atproto.lexicon.resolver
  "Network resolution of Lexicon schemas from NSIDs.

  See https://atproto.com/specs/lexicon#lexicon-publication-and-resolution"
  (:require [clojure.string :as str]
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.dns :as dns]
            [atproto.runtime.cast :as cast]
            [atproto.identity :as identity]
            [atproto.lexicon :as lexicon]
            [atproto.xrpc.client :as xrpc]
            #?(:clj [clojure.java.io :as io])))

(def lexicon-record-collection "com.atproto.lexicon.schema")

(defn resolve-nsid-authority
  "Resolve the DID with authority over this NSID via the `_lexicon` DNS TXT record.

  Looks up TXT records for `_lexicon.<domain-authority>` (e.g.
  `_lexicon.feed.bsky.app` for `app.bsky.feed.post`). Exactly one `did=`
  record must exist and contain a syntactically valid DID.

  Async (callback/promise/channel per atproto.runtime.interceptor/platform-async).
  Success: {:did \"did:plc:...\"}
  Errors:  {:error \"LexiconAuthorityNotFound\" :message ... :nsid ...}
           {:error \"InvalidNsid\" :message ...}"
  [nsid & {:as opts}])

(defn memory-cache
  "A simple atom-backed cache for resolve-nsid. Optional :ttl-ms."
  [& {:keys [ttl-ms]}])

(defn resolve-nsid
  "Resolve the NSID to its published Lexicon schema document.

  Steps: authority DID (DNS, or :did-authority override) -> resolve DID doc
  (atproto.identity/resolve-did) -> PDS endpoint (identity/did-doc-pds)
  -> com.atproto.repo.getRecord {:repo did
                                 :collection \"com.atproto.lexicon.schema\"
                                 :rkey nsid}
  -> validate (:value response) against :atproto.lexicon.schema/file,
     check (= (:id doc) nsid).

  Options:
  :did-authority  Skip DNS and use this DID as the authority.
  :cache          A cache from `memory-cache`; hit short-circuits the network.
  :force-refresh  Bypass and repopulate the cache.

  Success: {:nsid nsid
            :did \"did:...\"        ; authority
            :uri \"at://did:.../com.atproto.lexicon.schema/<nsid>\"
            :cid \"bafy...\"        ; from getRecord response, when present
            :lexicon {...}}         ; the schema document (Clojure map)
  Errors:  {:error \"LexiconAuthorityNotFound\" ...}
           {:error \"LexiconResolutionError\" :message ... :nsid ...}   ; DID/PDS/fetch failures
           {:error \"InvalidLexiconDocument\" :message ... :nsid ... :explain-data ...}
           {:error \"LexiconNsidMismatch\" :message ... :nsid ... :id ...}"
  [nsid & {:as opts}])

#?(:clj
   (defn install!
     "Dev helper: resolve `nsid` and write its schema document as JSON under `dir`,
      mirroring the NSID hierarchy (app.bsky.feed.post -> app/bsky/feed/post.json),
      à la the TS lex CLI installer.

      Options:
      :dir    target directory (default \"resources/lexicons\")
      :deps?  when true, recursively install schemas referenced by ref/union/
              knownValues until fixpoint.

      Synchronous; returns {:installed [nsid ...]} or {:error ...}.
      Updates <dir>/manifest.edn with {:nsid {:uri ... :cid ...}} entries."
     [nsid & {:keys [dir deps?] :as opts}]))
```

Implementation notes:
- Authority hostname construction: `(str "_lexicon." (:authority (lexicon/parse-nsid nsid)))`; keep the 253-char hostname guard from `resolver.cljc:30`.
- DNS via `(i/execute {::i/request {:hostname h :type "txt"} ::i/queue [dns/interceptor]} :callback ...)` exactly as `identity.cljc:121-142` does; TXT values may be chunked — concatenate before matching `#"^did=(.+)$"` (TS joins chunks, `lexicon.ts:158`).
- Use `(xrpc/init {:service pds})` + `xrpc/query` with `{:nsid "com.atproto.repo.getRecord" :params {:repo did :collection lexicon-record-collection :rkey nsid}}`. Success body is `{:uri ... :cid ... :value {...}}`.
- All public entry points follow the `(let [[cb val] (i/platform-async opts)] ... val)` pattern from `identity.cljc:244-249`.

### 2. `atproto.lexicon` — eval-free registration + registry

```clojure
;; Internal registry replacing the spec-registry-by-eval approach.
;; {:validators {spec-key (fn [x] boolean)}   ; compiled closures
;;  :docs       {nsid schema-doc}}            ; retained schema documents
(defonce ^:private registry (atom {:validators {} :docs {}}))

(defn register-specs!
  "Register validators for every schema in this Lexicon. Eval-free; works on cljs.
   Signature unchanged from today (lexicon.cljc:709)."
  [lexicon])

(defn registered-validator
  "The compiled validator fn for this spec key, or nil."
  [spec-key])

(defn parse-nsid
  "Parse a valid NSID and return {:authority \"feed.bsky.app\" :name \"post\"}
   (authority in domain order: segments minus name, reversed). nil if invalid."
  [nsid])
```

- New private `compile-field-type` multimethod (same dispatch values as `field-type-def->spec`, `lexicon.cljc:445-449`) returning *predicate closures* instead of code forms: e.g. the string case closes over `maxLength` and calls `utf8-length` directly; the object case builds required/optional/nullable key checks as data, not `s/keys` forms. `ref`/`union`/`unknown` resolve lazily through `registered-validator` (falling back to `s/get-spec` so hand-written specs still win), preserving the lazy-ref semantics of `lexicon.cljc:648-655`.
- `object-spec :typed` (`lexicon.cljc:659-663`), `record-spec :default` (`lexicon.cljc:730-737`), `request-spec-key` / `response-spec-key` / `message-spec-key` (`lexicon.cljc:768-782`) consult the registry first; since `s/valid?`/`s/explain-data` accept plain predicates, returning a closure where a keyword was returned before is compatible with the call sites in `xrpc/client.cljc:31-35` and `xrpc/server.cljc:131-139`.
- `translate` (form-emitting, `lexicon.cljc:455-464`) is kept but deprecated in its docstring; `test/atproto/lexicon_test.cljc:372-376` switches from `eval` to `register-specs!`.

### 3. `atproto.lexicon` — embedding macro + jar-safe loading

```clojure
(defmacro embed-resources!
  "Read all lexicon JSON files listed in <resource-path>/manifest.edn at
   macro-expansion time and emit code that registers them at load time.
   Usable from cljs (file IO happens on the compiling JVM)."
  [resource-path])

#?(:clj
   (defn load-resources!
     "Load schemas listed in <resource-path>/manifest.edn via io/resource
      (jar-safe) and return a Lexicon. Falls back to file-seq when no
      manifest is present (dev trees)."
     [resource-path]))
```

### 4. `atproto.lexicon` — validation modes, record keys, union check

```clojure
(def ^:dynamic *strict*
  "When true (default), validators enforce the full atproto spec.
   When false: datetimes may be ISO-8601-ish (offset optional), legacy
   untyped blob refs {:cid ... :mimeType ...} are accepted and blob
   accept/maxSize checks are skipped, and at-uri rkeys are not validated."
  true)

(defn record-key-spec
  "Spec/predicate for record keys of the given collection NSID, derived from
   the registered schema's :key (tid|nsid|literal:x|any). nil if the NSID is
   not a registered record type."
  [nsid])

(defn valid-record-key?
  "Whether rkey satisfies the registered record-key constraint for this
   collection NSID. Returns {:error \"UnknownCollection\" ...} map if the
   collection is not registered, else boolean."
  [nsid rkey])
```

- Datetime: generated `format "datetime"` checks become `(fn [s] (if *strict* (strict-datetime? s) (lenient-datetime? s)))`; add `atproto.runtime.datetime/parse-lenient` (`OffsetDateTime`-then-`LocalDateTime` fallback on clj, mirroring `isDatetimeStringLenient`, `syntax/src/datetime.ts:133-155`).
- Blob: the blob compile path (today `lexicon.cljc:577-582`) gains the legacy branch and conditional constraint checks per `lex-schema/src/schema/blob.ts:59-112`.
- Record key: implemented on top of the retained `:docs` in the registry + the existing conformer `::record/key` (`lexicon.cljc:374-379`) and `rkey-type->spec` (`lexicon.cljc:466-473`).
- Union check: in `lexicon` (`lexicon.cljc:683-696`), after all schemas validate individually, walk every conformed `union` def and verify in-set refs resolve to `type=object` defs; throw `ex-info` with the offending `{:nsid :ref :target-type}` otherwise.

### 5. Bundled lexicons + update script

- `resources/lexicons/com/atproto/**/*.json` (95 files), `resources/lexicons/app/bsky/**/*.json` (156 files) — byte-identical copies from the reference repo.
- `resources/lexicons/manifest.edn`:
  ```clojure
  {:source "https://github.com/bluesky-social/atproto"
   :commit "b9ef5576beeb949bbac776dcbe4e4c2c7af60e74"
   :fetched "2026-06-10"
   :files ["com/atproto/lexicon/schema.json" ...]}
  ```
- `script/update-lexicons.sh REF_CHECKOUT_DIR` — rsyncs `lexicons/{com/atproto,app/bsky}` into `resources/lexicons/`, regenerates the `:files` list **by globbing the resulting on-disk tree** (so files vendored by other workstreams, e.g. WS-01's `com/atproto/server/*.json`, are always enumerated) and `:commit` from `git -C REF_CHECKOUT_DIR rev-parse HEAD`.

## Interface contract

**Frozen for consumers (other workstreams may code against these):**

- `atproto.lexicon/register-specs!` — `[lexicon] -> nil`, eval-free, idempotent re-registration; same name/arity as today (consumed by `xrpc/server.cljc:15`).
- `atproto.lexicon/request-spec-key`, `response-spec-key`, `message-spec-key` — unchanged names/arities; return value remains "something accepted by `s/valid?`/`s/explain-data`" (keyword **or** predicate fn).
- `atproto.lexicon/*schema-validate*` — unchanged semantics (`lexicon.cljc:726-728`).
- `atproto.lexicon/*strict*` — dynamic boolean, default `true`; binding it to `false` is the only lenient-mode switch.
- `atproto.lexicon/valid-record-key?`, `record-key-spec` — as sketched above.
- `atproto.lexicon.resolver/resolve-nsid` — options/response maps as sketched; errors always `{:error "Name" :message ...}`; async via `platform-async` opts (`:callback`/`:promise`/`:channel`).
- Client response validation — `atproto.xrpc.client/init` gains `:validate-responses?` (boolean, default `false`, alongside `:validate-requests?`); schema-invalid successful responses yield `{:error "InvalidResponse" :message ... :explain-data ...}` (error name reserved by WS-07, `07-xrpc-client-ergonomics.md:134`; the map stays compatible with WS-07's XRPC error-map contract so WS-07's taxonomy can wrap it without rework).
- `resources/lexicons/manifest.edn` format — `{:source :commit :fetched :files}` (`:files` enumerates **every** JSON under `resources/lexicons/`, including any files vendored earlier by WS-01 — see File ownership).

**Consumed from others (assumptions + how to develop before they merge):**

- WS-02 (`atproto.data` / `atproto.data.json` codec): WS-09 assumes `::data/blob`, `data/blob-ref?`, `data/parse-cid` keep their current shapes (`data.cljc:40-79, 103-112`). Legacy blob refs at the lexicon level are validated structurally (`{:cid string :mimeType string}` with a parseable CID via `data/parse-cid`) without requiring new WS-02 API. If WS-02 introduces a dedicated legacy-blob representation, the lenient blob branch is the single integration point to update. Develop against the current `redesign` code; no stubs needed.
- `atproto.identity/resolve-did` + `did-doc-pds` (`identity.cljc:47-54, 174-183`): assumed stable. In resolver tests, stub by injecting interceptor queues / `with-redefs` so no live identity resolution is needed.

## File ownership

Created or modified when implemented (repo-relative):

| Path | Change | Conflicts |
|---|---|---|
| `src/atproto/lexicon/resolver.cljc` | rewrite | none known |
| `src/atproto/lexicon.cljc` | modify (registry, compiler, modes, record-key, union check, loading) | hottest file in the SDK; any workstream touching validation should rebase on WS-09's milestone 2 |
| `src/atproto/runtime/datetime.cljc` | add `parse-lenient` | none known |
| `src/atproto/xrpc/client.cljc` | add response validation: `response-validator` interceptor, `:validate-responses?` init flag, `"InvalidResponse"` error map, `*strict*` binding | **WS-01 owns the file and lands first; WS-07 rebases next; WS-09 rebases its change onto whatever 01/07 have landed** (per the overview single-owner rule, `00-overview.md:435`). Note: the overview row still says "09 adds a one-line `*strict*` binding only" — superseded by the client-response-validation decision recorded here; orchestrator to update `00-overview.md` §4.9/§5. |
| `src/atproto/xrpc/server.cljc` | one-line `*strict*` binding around the existing response validation (`server.cljc:138-141`) | **WS-08 owns the file** (`00-overview.md:447`); WS-09's trivial binding rebases onto WS-08 |
| `resources/lexicons/**` (251 JSON files) | new | shared with **WS-01**, which may vendor 4 `com/atproto/server/*.json` (`createSession`, `refreshSession`, `deleteSession`, `getSession`) here first (`01-auth-session-lifecycle.md:554, 612-615`). Per `00-overview.md:450`: byte-identical copies from the same reference commit; whoever lands second rebases (WS-09's bundle is a superset of WS-01's 4 files, so the rebase is content-free) |
| `resources/lexicons/manifest.edn` | new | none creates it before WS-09, but its `:files` list **must enumerate whatever WS-01 already vendored** under `resources/lexicons/` (`00-overview.md:450`) — PR-5 generates `:files` from the on-disk tree, not from a hardcoded list, so WS-01's files cannot be orphaned |
| `script/update-lexicons.sh` | new | none (`script/` doesn't exist yet) |
| `test/atproto/lexicon_test.cljc` | modify (eval → register-specs!; mode tests) | none known |
| `test/atproto/lexicon/resolver_test.cljc` | new | none |
| `test/atproto/lexicon/loading_test.clj` | new (resource/manifest/jar loading) | none |

## Test plan

**Unit (extend `test/atproto/lexicon_test.cljc`):**
- The full existing 731-assertion suite passes with `register-specs!` (closure compiler) instead of `eval` — this is the primary regression gate for milestone 2.
- Strict/lenient: datetime cases — strict set from `test/interop-test-files/syntax/datetime_syntax_valid.txt` / `datetime_syntax_invalid.txt` must behave identically in both modes for valid inputs; lenient-only inputs (e.g. `"1985-04-12T23:20:50"`, no offset) pass only with `*strict*` bound to `false`. Blob cases — legacy `{:cid "bafkrei..." :mimeType "image/png"}` valid only in lenient mode; typed blob violating `maxSize` fails in strict, passes in lenient; legacy ref under `maxSize` fails in strict.
- Record keys: schemas with `key` = `tid` / `nsid` / `literal:self` / `any`; assert `valid-record-key?` against `test/interop-test-files/syntax/tid_syntax_valid.txt`, `nsid_syntax_valid.txt`, `recordkey_syntax_valid.txt` (+ invalid counterparts).
- Union-ref check: a two-file lexicon where a union ref targets a `string` def must make `lexicon` throw; ref to an absent NSID must not throw.
- `parse-nsid`: cases from `test/interop-test-files/syntax/nsid_syntax_valid.txt`, asserting domain-order authority (e.g. `app.bsky.feed.post` → `"feed.bsky.app"`).

**Client response-validation tests (PR-3, new `test/atproto/xrpc/client_response_test.cljc` or extension of an existing xrpc client test ns — coordinate naming with WS-01/07, who own `client.cljc` tests):** with a registered lexicon and stubbed HTTP, a schema-valid response body passes through; a schema-invalid body yields `{:error "InvalidResponse" ...}` with `:explain-data`; error-bodied responses skip output validation; `:validate-responses?` absent/false preserves current behavior; lenient/strict binding per the Risk-7 decision.

**Resolver unit tests (`test/atproto/lexicon/resolver_test.cljc`):** stub the network exactly as the TS tests stub DNS (`packages/lexicon-resolver/tests/lexicon.test.ts:12-21`): `with-redefs` / injected interceptors returning canned DNS TXT responses and canned `com.atproto.repo.getRecord` bodies. Cover: happy path; zero / multiple `did=` records; invalid DID in TXT; DID doc missing PDS; invalid document (fails `::schema/file`); `id`≠nsid mismatch; `:did-authority` override skips DNS; cache hit/`:force-refresh`.

**Fixtures to vendor:**
- `/Users/luke/github/bluesky-social/atproto/lexicons/com/atproto/**/*.json` → `resources/lexicons/com/atproto/**` (95 files; doubles as test data — loading + registering all 95 must succeed, exercising every primary type).
- `/Users/luke/github/bluesky-social/atproto/lexicons/app/bsky/**/*.json` → `resources/lexicons/app/bsky/**` (156 files).
- `/Users/luke/github/bluesky-social/atproto/lexicons/com/atproto/lexicon/schema.json` is the schema the resolver validates fetched records against — included in the set above.
- No resolution fixtures exist under `/Users/luke/github/bluesky-social/atproto/interop-test-files/` (verified: only `crypto/`, `syntax/`); syntax fixtures are already vendored at `test/interop-test-files/syntax/`.

**Integration / live verification (manual, documented in the test ns as `^:integration`):**
- `(resolve-nsid "app.bsky.feed.post" :callback prn)` against the live network must return the published schema from Bluesky's authority (DNS `_lexicon.feed.bsky.app` is live today).
- Round-trip: `install!` an NSID into a temp dir, `load-resources!` it back, `register-specs!`, validate a known-good record.
- Jar-safety: build a jar containing `resources/lexicons/`, run `load-resources!` from it (regression for `lexicon.cljc:698-707`).

## Acceptance criteria

- [ ] `(require 'atproto.lexicon.resolver)` succeeds on the JVM; `clj-kondo`/compiler-clean ns form.
- [ ] `resolve-nsid` resolves a live NSID (e.g. `app.bsky.feed.post`) end-to-end and returns `{:nsid :did :uri :cid :lexicon}`; documented error maps for every failure mode listed above.
- [ ] DNS authority resolution requires exactly one `did=` TXT record and validates DID syntax (TS parity with `lex-resolver.ts:500-514`).
- [ ] `install!` writes `app/bsky/feed/post.json`-style files and a manifest; `:deps? true` reaches fixpoint on a schema with refs.
- [ ] `register-specs!` contains no `eval`; `grep -n "eval" src/atproto/lexicon.cljc` returns nothing (or only comments).
- [ ] Full test suite passes with ≥ the current 731 assertions; the lexicon translator tests run through the eval-free path.
- [ ] Binding `*strict*` to `false` accepts lenient datetimes and legacy blob refs; default behavior is byte-for-byte identical to today for all existing test cases.
- [ ] With `:validate-responses? true`, a schema-invalid successful XRPC response yields `{:error "InvalidResponse" :message ... :explain-data ...}` from the client; error-bodied responses and unknown NSIDs pass through unvalidated; default (`false`) leaves today's client behavior unchanged.
- [ ] `valid-record-key?` enforces `tid`/`nsid`/`literal:*`/`any` per registered schema.
- [ ] `lexicon` throws on union refs targeting non-`object` defs within the schema set; tolerates unresolvable refs.
- [ ] `resources/lexicons/` contains the 95 `com.atproto.*` + 156 `app.bsky.*` schemas, byte-identical to reference commit `b9ef557`, with `manifest.edn` provenance; `manifest.edn` `:files` matches the actual on-disk JSON tree exactly (including any WS-01-vendored `com/atproto/server/*.json`); `script/update-lexicons.sh` regenerates them.
- [ ] `load-resources!` works from a jar (manifest-driven) and registers all 251 bundled schemas without error.
- [ ] `embed-resources!` compiles in a cljs build (or, minimally, macro-expands on clj emitting literal data and is covered by a clj-side expansion test if no cljs CI exists yet).

## Milestones

Each lands as an independently green PR on `ws/09-lexicon-completion`:

1. **PR-1: Resolver compiles & resolves.** Rewrite `atproto.lexicon.resolver` (ns form, `parse-nsid` in `atproto.lexicon`, DNS authority with exactly-one rule, identity + `repo.getRecord` fetch, document validation, error maps, `cast` instead of `tap>`/`println`). Stub-based resolver tests. No caching yet.
2. **PR-2: Eval-free registration.** Closure compiler + registry in `atproto.lexicon`; `register-specs!` rewired; registry-aware `object-spec`/`record-spec`/`request-spec-key`/`response-spec-key`; `lexicon_test` migrated off `eval`; 731+ assertions green.
3. **PR-3: Strict/lenient modes + client response validation.** `*strict*`, lenient datetime (`datetime/parse-lenient`), legacy blob refs, lenient at-uri; `*strict*` binding around the server's existing response validation (`server.cljc:138-141`); **new** `response-validator` interceptor + `:validate-responses?` flag + `"InvalidResponse"` error map in `atproto.xrpc.client` (see In scope); settle the Risk-7 strictness policy in review; mode + response-validation tests. Rebases onto whatever WS-01/WS-07 have landed in `client.cljc`.
4. **PR-4: Record keys + union-ref check.** `record-key-spec`/`valid-record-key?` on registry docs; union `type=object` check in `lexicon`; delete TODO at `lexicon.cljc:44-45`.
5. **PR-5: Bundled lexicons + jar-safe loading.** `resources/lexicons/**` (251 files), `manifest.edn` (`:files` generated from the on-disk tree so any `com/atproto/server/*.json` already vendored by WS-01 are enumerated, per `00-overview.md:450`; WS-09's bundle supersedes them byte-identically), `script/update-lexicons.sh`, manifest-driven `load-resources!`, load-all-and-register test. If WS-01's vendoring PR is in flight, second lander rebases (content-free).
6. **PR-6: Cache, install!, cljs embedding.** `memory-cache` + `:cache`/`:force-refresh` in `resolve-nsid`; `install!` (with `:deps?`); `embed-resources!` macro + expansion test.

## Risks & open questions

1. **No proof verification (divergence from current TS).** Both TS resolvers verify a CAR inclusion proof + commit signature via `com.atproto.sync.getRecord` (`lex-resolver.ts:516-550`); WS-09 fetches via `com.atproto.repo.getRecord` and trusts the PDS. This is a deliberate scope cut: the SDK has no CAR/MST/commit-sig code. **Recommendation:** ship unverified resolution now with `:cid`/`:uri` in the result, and file a follow-up on the repo/sync workstream to add `verify-record-proof` behind the same `resolve-nsid` API (option `:verify? true` once available). Document the trust model in the resolver docstring.
2. **Spec-registry mechanism.** Two eval-free options: (a) internal registry of closures consulted by the existing multi-spec hooks (recommended — no dependence on spec internals; `s/valid?` accepts plain predicates at every existing call site), or (b) `clojure.spec.alpha/def-impl` / `cljs.spec.alpha/def-impl` to register real specs at runtime (keeps `s/form`/`s/explain` fidelity but leans on undocumented internals of an alpha library). **Recommendation: (a)**, accepting coarser `s/explain-data` output for schema-generated validators; revisit if spec-2 ever ships.
3. **Explain/error fidelity regression.** Closure validators report failure at the top-level predicate rather than the nested key. Mitigation: have compiled object validators optionally return failure paths via an `*explain*`-style dynamic collector, or defer; the XRPC server currently only surfaces `s/explain-data` blobs (`server.cljc:131-139`). Decide during PR-2 review.
4. **Jar size from bundling app.bsky.** 251 JSON files ≈ a few hundred KB uncompressed. **Recommendation:** bundle both `com.atproto.*` and `app.bsky.*` (the README-stated goal of the SDK is Bluesky app interop, and `deps.edn:1` already ships `resources/` on `:paths`); if size becomes a concern, split `app.bsky` into a separate artifact later.
5. **Record-key spec-key collisions** if record-key validators were registered under `<nsid>/record-key`: a lexicon def literally named `record-key` would collide. Avoided by the chosen design (derive from registry `:docs` on demand, never registered under a synthetic key).
6. **cljs runtime coverage.** `atproto.runtime.dns` (`dns.cljc:14-37`) and `atproto.runtime.datetime/parse` (`datetime.cljc:22-26`) are clj-only, and the repo has no cljs test runner today. WS-09 makes the *registration/validation* path cljs-clean (no eval, embedding macro) but cannot prove it in CI without a cljs build. Open question for the orchestrator: which workstream owns adding a cljs test target? Until then, milestone 6 ships a clj-side macro-expansion test.
7. **Lenient-mode policy for the new client response validator.** The two TS generations disagree: the lex-schema validator docstring recommends lax parsing of server responses (`validator.ts:179-187`), but the newer lex-client validates response bodies **strictly by default** with a `strictResponseProcessing` opt-out (`lex-client/src/response.ts:261-273`, default `?? true` at `:263`). Since WS-09 now adds the client response validator itself (PR-3, behind `:validate-responses?`, default off), the only open question is which `*strict*` value the validator binds when enabled — recommendation: lenient (`*strict* false`) for responses, strict for requests, no extra strictness knob; revisit if interop testing shows the lenient gap matters. Decide during PR-3 review.
8. **Cache policy.** TS leaves caching to hooks. Recommendation: ship `memory-cache` with optional TTL and no default cache (explicit opt-in), so behavior is predictable for servers that must observe lexicon updates.
9. **`com.atproto.lexicon.resolveLexicon` fast path** (`lexicons/com/atproto/lexicon/resolveLexicon.json`): a server-side query that returns `{uri cid schema}` directly. Worth adding later as `:via-service` option on `resolve-nsid`; out of scope now to keep parity with the TS resolver packages, which do not use it.
