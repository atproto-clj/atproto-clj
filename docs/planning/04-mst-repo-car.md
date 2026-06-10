# WS-04: MST, Repository & CAR Files

| | |
|---|---|
| **Status** | Planning |
| **Priority** | P1 |
| **Estimated size** | XL |
| **Branch** | ws/04-mst-repo-car |
| **Depends on** | WS-02 (`atproto.data.cbor` encode/decode + `cid-for` contract), WS-03 (commit sign/verify contract). Both at contract level only — see "Interface contract" for how to start before they merge. |
| **Blocks** | WS-11 (durable repo storage implements this workstream's blockstore protocol), firehose/relay consumer work (commit verification of `#commit` frames), any PDS-side workstream (repos are the PDS's core data structure) |

## Goal

When this workstream is done, the SDK can create, read, mutate, sign, export, and verify atproto data repositories entirely client-side: a full Merkle Search Tree (MST) implementation with deterministic root CIDs that match the reference implementation byte-for-byte, a repository layer (signed commits, record CRUD, inclusion/exclusion proofs), CAR v1 read/write (including streaming reads on the JVM), and sync v1.1 verification helpers that can validate a `com.atproto.sync.getRecord` proof CAR or walk and verify a full `com.atproto.sync.getRepo` export. A blockstore protocol with an in-memory implementation provides the storage seam that WS-11 (durable storage) will plug into.

## Current state

Nothing of this workstream exists today. Verified:

- `grep -ri "mst\|merkle\|car\|blockstore\|varint\|commit" src/` finds no repo/MST/CAR code. The only "repo" artifact is `examples/repo.clj`, which is an XRPC *client* demo calling `com.atproto.repo.*` HTTP endpoints (`examples/repo.clj:1-22`) — it never touches MST/CAR structures.
- `README.md:20` tracks this as `| MST and Repo | ⭕ | ⭕ | ⭕ |` (not started on all three platforms).
- No DAG-CBOR codec exists anywhere (`src/atproto/data/json.cljc` covers only the JSON representation). That codec is WS-02's deliverable.

What *does* exist and will be consumed:

- `src/atproto/data.cljc:19-79` — CID helpers built on `mvxcvi/multiformats` (already in `deps.edn`): `cid?`, `cid-link`, `cid-link?`, `blob-ref`, `encode-cid` (`:56-59`), `decode-cid` (`:61-64`), `format-cid` (base32, `:66-69`), `parse-cid` (`:71-79`).
- **Known bug (blocking, owned by WS-02):** `atproto.data/cid-link` (`src/atproto/data.cljc:24-31`) and `blob-ref` (`:40-47`) call `multiformats.hash/create`, which *constructs* a multihash from an already-computed digest — it does **not** hash. Passing content bytes produces a garbage "CID" whose digest field is the raw content. The correct call is `multiformats.hash/sha2-256` (which hashes content; see `multiformats/hash.cljc` `sha2-256` vs `create` in the 1.0.125 jar). Every MST node CID and commit CID flows through this path, so the WS-04 interop fixtures (known root CIDs, below) will fail loudly until it is fixed. The fix belongs to WS-02 (it owns the `cid-for` contract and `atproto.data` changes); if WS-02 has not landed by WS-04's M2, fix it inside the temporary stub instead (see Milestones) and let WS-02 land the permanent fix.
- `src/atproto/tid.cljc:41-45` — `next-tid` for commit `rev` generation (monotonic).
- `src/atproto/runtime/crypto.cljc:23-27` — `sha256` exists but is **`:clj`-only** (the body is a `#?(:clj ...)` form with no `:cljs` branch, so it returns `nil` on CLJS) and only accepts a `String`. No workstream adds a `:cljs` branch (WS-03's cljs crypto is an unimplemented stretch goal), and WS-04's acceptance criteria require the `.cljc` surface to be green on both platforms — so **`leading-zeros` must NOT use `atproto.runtime.crypto/sha256`**. Instead hash MST keys with `multiformats.hash/sha2-256`, which is cross-platform in the 1.0.125 jar (`MessageDigest` on `:clj`, `goog.crypt.Sha256` on `:cljs`; verified `multiformats/hash.cljc:468-474` in the jar) and is already what CAR block CID verification uses. It returns a multihash, not raw digest bytes — take the digest by slicing the 2-byte `0x12 0x20` header off `(multiformats.hash/encode mh)` (or hex-decoding `(:digest mh)`). No change to `atproto.runtime.crypto` is required by this workstream.
- `src/atproto/runtime/bytes.cljc:10-22` — minimal byte helpers; note `eq?` (`:15-17`) is CLJ-only today. WS-04 needs byte concatenation and slicing utilities on both platforms (see Deliverables).
- `src/atproto/runtime/interceptor.cljc:129-155` (`platform-async`) and `:157-169` (`execute`) — the SDK async convention. See "Deliverables" for why most of WS-04's API is deliberately synchronous (pure computation), matching the `atproto.data`/`atproto.tid` precedent rather than the IO-bound `atproto.identity` style (`src/atproto/identity.cljc:47-54`).
- `src/atproto/lexicon.cljc:51` (`::lexicon/nsid`), `:101` (`::lexicon/record-key`), `:107` (`::lexicon/tid`) — specs reused for MST key / write-op validation.
- `mvxcvi/multiformats {:mvn/version "1.0.125"}` (`deps.edn:10`) ships `multiformats/varint.cljc` with `encode`, `write-bytes`, `read-bytes`, `decode` — exactly what CAR framing needs; no new dependency required. **However, WS-04 consumes varints via WS-02's `atproto.runtime.varint` wrapper** (part of WS-02's frozen contract, `02-dag-cbor-data-model.md` — it exists so WS-04/WS-05 don't bind directly to the mvxcvi library), not `multiformats.varint` directly.
- `multiformats.cid/ContentID` implements `equals`/`hashCode` (CLJ) and `-equiv`/`-hash` (CLJS) (`multiformats/cid.cljc:79-105`, `:168-195` in the jar), so CIDs can be used directly as keys in Clojure maps and sets. No `BlockMap`/`CidSet` classes are needed — plain persistent maps/sets suffice.

## Reference implementation guide

All paths below are under `/Users/luke/github/bluesky-social/atproto`.

Primary package: **`packages/repo`** ("atproto repo and MST implementation", `packages/repo/package.json`). It depends on `@atproto/lex-cbor` (`packages/lex/lex-cbor` — DAG-CBOR `encode`/`decode` + `cidForLex`, `packages/lex/lex-cbor/src/index.ts:41-43`) and `@atproto/lex-data` (`packages/lex/lex-data` — Cid, byte utils). Note the charter's guess about package layout: there IS a `lex-*` family, but it lives under `packages/lex/<subpackage>`, not at the top level. CAR handling is **hand-rolled in `packages/repo/src/car.ts`** using the `varint` npm package — the repo no longer uses `@ipld/car` at all.

### Key algorithms / data structures and where each lives

| Concept | Reference location |
|---|---|
| MST overview + node CBOR format (`l` left subtree, `e` entries of `{p, k, v, t}` with prefix compression) | `packages/repo/src/mst/mst.ts:12-55` |
| Key depth: count of leading zero **bits** of sha256(key), counted 2 bits/layer via byte comparisons (`<64`, `<16`, `<4`, `==0`) | `packages/repo/src/mst/util.ts:23-38` (`leadingZerosOnHash`) |
| Node (de)serialization, prefix compression, key validation (`[a-zA-Z0-9_~\-:.]`, `collection/rkey`, ≤1024 chars) | `packages/repo/src/mst/util.ts:48-160` (`deserializeNodeData` :48-78, `serializeNodeData` :80-115, `countPrefixLen` :117-125, `isValidMstKey` :132-148) |
| Lazy node loading + cached/outdated pointer | `packages/repo/src/mst/mst.ts:133-174` (`getEntries`, `getPointer`, `serialize`) |
| Layer inference for partial trees | `packages/repo/src/mst/mst.ts:180-203` (`getLayer`/`attemptGetLayer`), `util.ts:40-46` |
| `add` (same-layer splice, lower-layer recursion, higher-layer split incl. multi-layer gap) | `packages/repo/src/mst/mst.ts:228-295` |
| `get` / `update` / `delete` (delete merges neighbor subtrees and trims top) | `packages/repo/src/mst/mst.ts:298-365`, `trimTop` :442-458 |
| `splitAround` / `appendMerge` / `createParent`/`createChild` | `packages/repo/src/mst/mst.ts:464-533` |
| Ordered traversal: `walkFrom`/`list`/`listWithPrefix`, full `walk`, `walkReachable` (skips missing blocks — needed for proof CARs) | `packages/repo/src/mst/mst.ts:554-723` |
| BFS block stream for full-repo CAR export | `packages/repo/src/mst/mst.ts:727-764` (`carBlockStream`) |
| Path CIDs for record proofs (`getRecords` sync provider) | `packages/repo/src/mst/mst.ts:766-778` (`cidsForPath`) |
| Covering proof (self + left sibling + right sibling proof paths) | `packages/repo/src/mst/mst.ts:784-850` |
| Tree diff via twin walkers (yields adds/updates/deletes + new MST blocks + removed CIDs) | `packages/repo/src/mst/diff.ts:13-114`, walker state machine `packages/repo/src/mst/walker.ts:16-118`, accumulator `packages/repo/src/data-diff.ts:6-101` |
| Commit object (`did`, `version` 3, `data`, `rev`, `prev` always null in v3, `sig`), legacy v2 | `packages/repo/src/types.ts:31-55`, `ensureV3Commit` `packages/repo/src/util.ts:115-125` |
| Commit signing / sig verification (sign over DAG-CBOR of unsigned commit; verify strips `sig`) | `packages/repo/src/util.ts:82-101`; `verifySignature(didKey, data, sig)` lives in `packages/crypto/src/verify.ts:6` |
| `CommitData` (cid, rev, since, prev, newBlocks, relevantBlocks, removedCids) | `packages/repo/src/types.ts:171-179` |
| Repo: init commit, load, formatCommit (apply writes to MST, diff, covering proofs → `relevantBlocks`, sign), applyWrites, resign | `packages/repo/src/repo.ts:37-233`; read-only repo (`walkRecords`, `getRecord`, `getContents`) `packages/repo/src/readable-repo.ts:17-86` |
| Write ops (`create`/`update`/`delete` × collection/rkey/record) and write descripts | `packages/repo/src/types.ts:111-166`; data-key helpers `packages/repo/src/util.ts:68-76` |
| CAR v1 write (varint header length + DAG-CBOR `{version 1, roots}`; then per block varint(len(cid)+len(bytes)) ‖ cid ‖ bytes) | `packages/repo/src/car.ts:10-49` |
| CAR v1 read (in-memory + streaming with buffered reader; CID-for-bytes verification by default, skippable) | `packages/repo/src/car.ts:58-206`. ⚠ Reads block CIDs as a **fixed 36-byte prefix** (`car.ts:160-161`) — valid for atproto CIDv1/sha2-256 blocks but not general; the Clojure port should parse version-varint + codec-varint + multihash instead (see Risks). |
| Blockstore interfaces: readable (getBytes/has/getBlocks + typed reads), writable `RepoStorage`, in-memory impl, staged/saved overlay | `packages/repo/src/storage/readable-blockstore.ts:8-53`, `storage/types.ts:7-30`, `storage/memory-blockstore.ts:7-74` (`applyCommit` :52-61), `storage/sync-storage.ts:5-33` |
| Sync v1.1 consumer: `verifyRepoCar`/`verifyRepo` (full export → creates), `verifyDiffCar`/`verifyDiff`, `verifyRepoRoot` (did + sig check), `verifyProofs` (record claims incl. nonexistence), `verifyRecords` | `packages/repo/src/sync/consumer.ts:21-207` |
| Sync provider: `getFullRepo` (commit block + MST BFS → CAR stream), `getRecords` (commit + proof paths → CAR) | `packages/repo/src/sync/provider.ts:13-67` |
| Block parse helpers (decode + schema check) | `packages/repo/src/parse.ts:8-44`; error taxonomy `packages/repo/src/error.ts:3-43` |

### Interop test fixtures

`/Users/luke/github/bluesky-social/atproto/interop-test-files/` contains only `crypto/` and `syntax/` — **there are no repo/MST/CAR fixtures in the shared interop directory**. The vendorable fixtures live in the repo package's test dir:

- `packages/repo/tests/car-file-fixtures.json` — array of `{root, blocks: [{cid, bytes(base64)}], car(base64)}`; drives byte-exact CAR write and read tests (`packages/repo/tests/car.test.ts:23-61`).
- `packages/repo/tests/commit-proof-fixtures.json` — 6 cases of `{comment, leafValue, keys, adds, dels, rootBeforeCommit, rootAfterCommit, blocksInProof}`; drives covering-proof generation + proof-invertibility tests (`packages/repo/tests/commit-proofs.test.ts:7-54`).
- Hard-coded interop vectors inside `packages/repo/tests/mst.test.ts`:
  - Known-map root CIDs: empty tree `bafyreie5737g…`, trivial, singlelayer2 (layer 2), simple 5-leaf tree (`mst.test.ts:256-307`).
  - Edge cases with exact root CIDs: delete trims top (`:325-346`), insertion splitting two layers (`:362-396`), key two layers higher than tree (`:410-448`). Comments document per-key layers (e.g. `com.example.record/3jqfcqzm3fx2j` → layer 2) — these double as `leading-zeros` test vectors.
  - Key validation accept/reject lists (`mst.test.ts:176-254`).
- Scenario tests worth porting as property/integration tests: 1000-key add/edit/delete with order-independence (`mst.test.ts:9-157`), `repo.test.ts`, `sync.test.ts:15-95`, `proofs.test.ts`, `commit-data.test.ts:10-94` (demonstrates why `relevantBlocks` ≠ `newBlocks`: deleting a key can require a proof block that already existed in the repo).

## Scope

### In scope

- **Blockstore** (`atproto.repo.blockstore`): `ReadableBlockstore` and `WritableBlockstore` protocols; in-memory implementation; read-through overlay of two readable stores (port of `SyncStorage`); helpers to read+decode+spec-validate a block at a CID.
- **MST** (`atproto.repo.mst`):
  - Key validation (`collection/rkey`, charset, ≤1024) as a spec + predicate.
  - `leading-zeros` key-depth fn (2 bits per layer over sha256(key)).
  - Node format: serialization to/from the `{l, e:[{p,k,v,t}]}` data shape with prefix compression; node bytes/CID via the WS-02 CBOR contract.
  - Lazy tree handles over a blockstore (entries loaded on demand; pointer cached and recomputed when stale).
  - `add`, `update`, `delete`, `get` with full layer-split/merge/trim semantics; deterministic, insertion-order-independent roots.
  - Ordered traversal (`leaf-seq` from a key, `list`, `list-with-prefix`), full walk, reachable-only walk (tolerates missing blocks for proof CARs).
  - Diff between two trees (twin-walker algorithm) yielding record-level adds/updates/deletes plus `new-mst-blocks`, `new-leaf-cids`, `removed-cids`.
  - Covering-proof generation (`covering-proof` = proof-for-key + left-sibling + right-sibling), `cids-for-path`, BFS `car-block-seq`, `unstored-blocks`.
- **Repository layer** (`atproto.repo`):
  - Commit map spec (v3: `did`, `version`, `data`, `rev`, `prev`, `sig`) + read-side acceptance of legacy v2 commits (normalize via `ensure-v3`).
  - `sign-commit` / `verify-commit-sig` over the DAG-CBOR of the unsigned commit (consumes WS-03 contract).
  - `create` (init commit, optional initial writes), `load`, `format-commit` (writes → MST ops → diff → covering proofs → signed commit → commit-data), `apply-commit`, `apply-writes`, resign.
  - Record CRUD/read: `get-record`, `record-seq`, `contents`.
- **CAR v1** (`atproto.repo.car`): write (bytes; plus JVM `OutputStream`/lazy-seq streaming), read (bytes → roots+blocks; plus JVM streaming `InputStream` reader that yields blocks incrementally), default CID-to-content verification with opt-out, single-root convenience.
- **Sync v1.1 verification** (`atproto.repo.sync`): `verify-repo` / `verify-repo-car` (full getRepo export → verified creates + commit-data), `verify-diff` / `verify-diff-car`, `verify-proofs` (record CID claims incl. nonexistence proofs, for getRecord CARs), `verify-records`; provider-side `repo->car` and `records->car` (needed to test the consumers and useful for PDS work).
- **Fixtures**: vendor the two JSON fixture files and port the hard-coded interop vectors (root CIDs, layer assignments, key validation lists).
- **Byte utilities**: small additions to `atproto.runtime.bytes` (concat, subarray/slice, CLJS `eq?`) needed by CAR framing — coordinate, see File ownership.

### Out of scope

- **DAG-CBOR codec and `cid-for`** — WS-02 (`atproto.data.cbor`). WS-04 only *consumes* the contract. The `cid-link` digest bug fix in `atproto.data` also belongs to WS-02 (noted above).
- **Key generation, ECDSA sign/verify primitives, did:key parsing/low-S rules** — WS-03. WS-04 consumes `sign`/`verify-did-sig`/`did` only (see Interface contract for exact signatures).
- **Durable blockstore implementations** (SQL/disk/etc.) and repo persistence — WS-11. WS-04 freezes the protocol WS-11 implements.
- **Firehose/`subscribeRepos` client, event frames, `#commit` message decoding** — the stream-client workstream; it will *use* `atproto.repo.car/read-car` and `atproto.repo.sync/verify-diff-car` but frame handling is not here.
- **Blob storage (`BlobStore`)** — `packages/repo/src/storage/types.ts:33-45` is PDS-side; not ported.
- **XRPC endpoints** (`com.atproto.sync.*` HTTP calls) — the existing `atproto.client` already does generic XRPC; WS-04 ships pure verification helpers, not network fetchers. A live-PDS walkthrough lands as an example/integration test only.
- **PLC operations, identity resolution of the signing key** — callers resolve the DID document themselves (WS owning identity already provides `atproto.identity`); `verify-*` fns take an explicit `did`/`did-key`.
- **ClojureDart support** — `.cljc` with `:clj`/`:cljs` branches only, matching the rest of the SDK.

## Deliverables

All namespaces are new `.cljc` files. Design note on async: every function in this workstream is pure computation over in-memory data (the only "IO" is blockstore lookups, which are in-memory until WS-11). Following the precedent of `atproto.data` and `atproto.tid` (synchronous) versus `atproto.identity`/`atproto.oauth.client` (async because they do network IO), **the public API here is synchronous**; errors are `{:error "Name" :message "..."}` maps, never thrown, at the public boundary (internal helpers may throw `ex-info` carrying the same map in `ex-data`, caught at the boundary). WS-11 can layer async adapters via `atproto.runtime.interceptor/platform-async` when real IO appears. This decision is flagged in Risks if reviewers prefer callback-style protocols now. **Exception (crypto-async decision, overview §4.2):** entry points that invoke WS-03 key operations — commit signing during repo creation/writes, and the signature checks inside the `atproto.repo.sync` verifiers — are **async** per the SDK callback convention, because `crypto/sign` and `verify-did-sig` are async. All MST/CAR/diff computation beneath them stays synchronous.

A "block-map" below is a plain persistent map of CID → byte-array. A "cid-set" is a plain persistent set of CIDs (CID equality/hash verified, see Current state).

### `atproto.repo.blockstore`

```clojure
(ns atproto.repo.blockstore
  "Content-addressed block storage for atproto repositories.")

(defprotocol ReadableBlockstore
  (get-bytes [bs cid]
    "Raw block bytes for cid, or nil if not present.")
  (has-block? [bs cid]
    "Whether a block exists for cid.")
  (get-blocks [bs cids]
    "Batch fetch. Returns {:blocks block-map, :missing [cid ...]}."))

(defprotocol WritableBlockstore
  "Mutable repo storage. In-memory impl here; durable impls are WS-11."
  (get-root [bs] "CID of the current root commit, or nil.")
  (put-block! [bs cid bytes])
  (put-blocks! [bs block-map])
  (update-root! [bs cid rev])
  (apply-commit! [bs commit-data]
    "Atomically delete :removed-cids, add :new-blocks, set root to :cid.
     See packages/repo/src/storage/memory-blockstore.ts:52-61."))

(defn memory-blockstore
  "In-memory blockstore (atom-backed). Optionally seeded with a block-map.
  Satisfies ReadableBlockstore and WritableBlockstore."
  ([] ...)
  ([block-map] ...))

(defn overlay
  "Read-only blockstore consulting `staged` then `saved` (port of SyncStorage)."
  [staged saved])

(defn read-block
  "Fetch the block at `cid` and DAG-CBOR-decode it (WS-02 contract).
  With `spec`, also validate. Returns {:data x :bytes bytes} or
  {:error \"MissingBlock\" :cid cid} / {:error \"InvalidBlock\" :cid cid :message ...}."
  ([bs cid] ...)
  ([bs cid spec] ...))

(defn add-block
  "Encode `data` (DAG-CBOR), compute its CID, and assoc into block-map.
  Returns [cid block-map']."
  [block-map data])
```

### `atproto.repo.mst`

A tree value is an opaque map (callers use the API): `{:blockstore bs, :pointer cid-or-nil, :entries entries-or-nil, :layer int-or-nil, :outdated? bool}` where entries is a vector of `{:type :tree, ...}` / `{:type :leaf, :key string, :value cid}`. Entries are loaded lazily from the blockstore; the implementation may cache loaded entries (e.g. delay/atom) but trees must behave as immutable values: every mutation returns a new tree.

```clojure
(ns atproto.repo.mst
  "Merkle Search Tree: ordered, insertion-order-independent, deterministic tree.
  Keys at layer = floor(leading-zero-bits(sha256(key)) / 2). ~4-way fanout.
  See https://atproto.com/specs/repository and packages/repo/src/mst/mst.ts.")

(s/def ::key ...)  ;; "collection/rkey": two non-empty [a-zA-Z0-9_~\-:.]+ segments, total <= 1024
(defn valid-key? [s] ...)

(defn leading-zeros
  "Count of leading zero bits in sha256(key-string). Determines MST layer (2 bits/layer).
  Port of packages/repo/src/mst/util.ts:23-38.
  Hash via multiformats.hash/sha2-256 (cross-platform), NOT
  atproto.runtime.crypto/sha256 (:clj-only — see Current state)."
  [key])

(defn create
  "New empty tree over blockstore." [bs])
(defn load
  "Lazy handle on an existing tree by root CID. Does not touch storage."
  [bs root-cid])

(defn pointer
  "Root CID, (re)serializing dirty nodes as needed. Pure given the tree value."
  [tree])

(defn add
  "Add leaf key->value-cid. Returns tree' or
  {:error \"InvalidMstKey\"} / {:error \"KeyAlreadyExists\" :key key}
  / {:error \"MissingBlock\" :cid cid} (partial tree)."
  [tree key value-cid])

(defn update-value
  "Replace value at existing key. {:error \"KeyNotFound\"} if absent."
  [tree key value-cid])

(defn delete
  "Remove key, merging neighbor subtrees and trimming the top.
  {:error \"KeyNotFound\"} if absent."
  [tree key])

(defn get-value
  "Value CID at key, or nil." [tree key])

(defn leaf-seq
  "Lazy ordered seq of {:key k :value cid} leaves, optionally starting at `from`."
  ([tree]) ([tree from]))
(defn list-keys [tree & {:keys [after before limit]}])
(defn list-with-prefix [tree prefix & {:keys [limit]}])

(defn serialize
  "Serialize this node: {:cid cid :bytes bytes :data node-data}.
  node-data = {:l cid-or-nil :e [{:p int :k bytes :v cid :t cid-or-nil} ...]}."
  [tree])

(defn unstored-blocks
  "{:root cid :blocks block-map} of nodes not yet in the blockstore.
  Port of mst.ts getUnstoredBlocks (:209-224)." [tree])

(defn diff
  "Difference between curr and prev (prev may be nil = everything added).
  Returns {:adds {key {:key k :cid c}}
           :updates {key {:key k :prev c :cid c}}
           :deletes {key {:key k :cid c}}
           :new-mst-blocks block-map
           :new-leaf-cids cid-set
           :removed-cids cid-set}
  Port of packages/repo/src/mst/diff.ts:13-114 + data-diff.ts."
  [curr prev])

(defn covering-proof
  "block-map of all MST nodes proving key and its immediate left/right
  siblings (mst.ts:784-850). {:error \"MissingBlock\"} on partial trees."
  [tree key])

(defn cids-for-path
  "CIDs of nodes along the path to key (+ leaf value cid if present)."
  [tree key])

(defn car-block-seq
  "Seq of {:cid c :bytes b} for all tree nodes (BFS) then leaf blocks; for
  full-repo export. Errors as ex-info {:error \"MissingBlock\"} during reduction."
  [tree])

(defn reachable-leaf-seq
  "Like leaf-seq but skips subtrees whose blocks are missing (proof CARs)."
  [tree])
```

### `atproto.repo.car`

```clojure
(ns atproto.repo.car
  "CAR v1 (Content ARchive) reading and writing. https://ipld.io/specs/transport/car/carv1/")

(defn write-car
  "CAR v1 bytes: varint header length, DAG-CBOR {:version 1 :roots [root]},
  then for each {:cid c :bytes b} in blocks (any seqable, order preserved):
  varint(len(cid-bytes)+len(b)) ‖ cid-bytes ‖ b. `root` may be nil (empty roots)."
  [root blocks])

(defn read-car
  "Parse CAR bytes. Returns {:roots [cid ...] :blocks [{:cid c :bytes b} ...]
  :block-map block-map} or {:error \"InvalidCar\" :message ...}.
  Verifies sha256(bytes) matches each CID unless :skip-cid-verification? is true
  (then {:error \"InvalidCarBlock\" :cid cid} on mismatch)."
  [bytes & {:keys [skip-cid-verification?]}])

(defn read-car-with-root
  "Like read-car but requires exactly one root: {:root cid :blocks ... :block-map ...}
  or {:error \"InvalidCar\" :message \"Expected one root...\"}."
  [bytes & opts])

#?(:clj
   (defn block-reader
     "Streaming CAR reader over an InputStream. Returns
     {:roots [cid ...] :blocks <reducible of {:cid c :bytes b}>}.
     Blocks are verified during reduction (same opt-out); reduction throws
     ex-info {:error \"InvalidCarBlock\"} on bad block. Does not load the whole
     CAR into memory; suitable for multi-GB getRepo exports."
     [^java.io.InputStream in & {:keys [skip-cid-verification?]}]))

#?(:clj
   (defn write-car-stream
     "Stream a CAR to an OutputStream from a (possibly lazy) seq of blocks."
     [root blocks ^java.io.OutputStream out]))
```

Implementation notes: varints via **`atproto.runtime.varint`** (`encode`/`decode`/`read-bytes`, WS-02's frozen contract — the wrapper exists precisely so WS-04/WS-05 don't bind directly to `multiformats.varint`; see `02-dag-cbor-data-model.md`); block CIDs parsed structurally (version varint + codec varint + multihash) via `multiformats.cid`, not the reference's fixed 36-byte slice (`packages/repo/src/car.ts:160-161`); header decoding uses the WS-02 `decode` (CIDs in the header are CBOR tag 42 values, which WS-02's codec must produce/consume).

### `atproto.repo`

```clojure
(ns atproto.repo
  "Signed atproto data repositories: commits, record CRUD, proofs.
  See https://atproto.com/specs/repository")

(s/def ::commit
  ;; {:did did, :version 3, :data cid, :rev tid-string, :prev cid-or-nil, :sig bytes}
  ...)
(s/def ::unsigned-commit ...)  ;; same minus :sig
;; v2 commits (version 2, optional rev) accepted on read & normalized:
(defn ensure-v3 [commit] ...)  ;; port of packages/repo/src/util.ts:115-125

(defn data-key [collection rkey] "collection/rkey string.")
(defn parse-data-key [k] "{:collection nsid :rkey rkey} or {:error \"InvalidDataKey\"}.")

(defn sign-commit
  "Sign the DAG-CBOR encoding of the unsigned commit with `signing-key`
  (WS-03 contract object). Returns commit with :sig bytes."
  [unsigned-commit signing-key])

(defn verify-commit-sig
  "True iff :sig verifies against did-key (\"did:key:z...\") over the
  DAG-CBOR of the commit minus :sig. Port of packages/repo/src/util.ts:94-101."
  [commit did-key])

;; A repo handle: {:storage bs, :commit commit, :cid commit-cid, :tree mst}
(defn load
  "Load a repo from storage at commit-cid (or the storage root).
  {:error \"MissingBlock\"/\"InvalidCommit\"} on failure."
  ([storage]) ([storage commit-cid]))

(defn create
  "Initialize a repo: empty MST (+ optional initial create ops), signed init
  commit (version 3, rev = next TID, prev nil), applied to storage.
  Returns repo handle. Port of repo.ts:37-100."
  [storage did signing-key & {:keys [initial-writes]}])

;; A write op: {:action :create|:update|:delete, :collection nsid, :rkey rkey, :value data}
;; (:value absent for :delete). Validated against ::write-op spec.
(defn format-commit
  "Apply write ops to the MST (without committing), diff against current tree,
  collect covering proofs for every written key, sign the new commit.
  Returns commit-data:
  {:cid cid :rev tid :since tid-or-nil :prev cid-or-nil
   :new-blocks block-map        ;; new MST nodes + new leaf records + commit
   :relevant-blocks block-map   ;; new-blocks + covering-proof blocks (may include pre-existing blocks)
   :removed-cids cid-set}
  Errors: {:error \"KeyAlreadyExists\"/\"KeyNotFound\"/\"InvalidWriteOp\" ...}.
  Port of repo.ts:118-191; relevant-blocks subtlety per tests/commit-data.test.ts:40-66."
  [repo writes signing-key])

(defn apply-commit
  "apply-commit! to storage and reload. Returns repo'." [repo commit-data])
(defn apply-writes
  "(apply-commit repo (format-commit repo writes signing-key))."
  [repo writes signing-key])

(defn get-record
  "{:cid cid :value data} at collection/rkey, or nil."
  [repo collection rkey])
(defn record-seq
  "Lazy ordered seq of {:collection c :rkey r :cid cid :value data}."
  [repo & {:keys [from]}])
(defn contents
  "{collection {rkey value}} for the whole repo." [repo])
```

### `atproto.repo.sync`

```clojure
(ns atproto.repo.sync
  "Sync v1.1 verification of repository CARs.
  Port of packages/repo/src/sync/consumer.ts and provider.ts.")

(defn verify-repo
  "Verify a full repo export (block-map + commit root). Checks commit shape,
  optional :did match, optional :signing-key (did:key) signature, walks
  commit -> MST -> records, requires all leaves present.
  Returns {:creates [{:action :create :collection c :rkey r :cid cid} ...]
           :commit commit-data}
  or {:error \"RepoVerification\" :message ...} / {:error \"MissingBlock\" ...}."
  [block-map root & {:keys [did signing-key ensure-leaves?] :or {ensure-leaves? true}}])

(defn verify-repo-car
  "read-car-with-root + verify-repo." [car-bytes & opts])

(defn verify-diff
  "Verify an update CAR against a (possibly nil) known repo: staged blocks
  overlay repo storage; verifies new root, diffs new MST against old, returns
  {:writes [write-descript ...] :commit commit-data}.
  write-descript = {:action :create|:update|:delete :collection c :rkey r
                    :cid cid (& :prev cid on :update)}."
  [repo block-map root & {:keys [did signing-key ensure-leaves?]}])

(defn verify-diff-car [repo car-bytes & opts])

(defn verify-proofs
  "Verify com.atproto.sync.getRecord-style proof CARs.
  claims: [{:collection c :rkey r :cid cid-or-nil} ...] (nil cid = claim of absence).
  Returns {:verified [claim ...] :unverified [claim ...]} or
  {:error \"RepoVerification\"/\"MissingBlock\" ...}. Port of consumer.ts:129-170."
  [car-bytes claims did did-key])

(defn verify-records
  "All records reachable in a proof CAR, after did+sig check:
  [{:collection c :rkey r :cid cid :value data} ...]." [car-bytes did did-key])

;; Provider side (testing + future PDS use)
(defn repo->car
  "Full repo export CAR bytes (commit block + MST BFS + leaves), root = commit cid.
  JVM arity streams to an OutputStream." 
  ([storage commit-cid]) #?(:clj ([storage commit-cid out])))

(defn records->car
  "Proof CAR for specific record paths [{:collection c :rkey r} ...]."
  [storage commit-cid paths])
```

### `atproto.runtime.bytes` additions

`concat-bytes [& byte-arrays]`, `slice [bytes start end]`, CLJS implementation of `eq?` (currently CLJ-only, `src/atproto/runtime/bytes.cljc:15-17`). Small, additive, no signature changes.

## Interface contract

### Provided (frozen once M1/M5 merge — other workstreams may code against these)

- `atproto.repo.blockstore/ReadableBlockstore` + `WritableBlockstore` exactly as sketched above — **this is the contract WS-11 implements**. Methods are synchronous; batch reads return `{:blocks ... :missing ...}`; `apply-commit!` takes the `commit-data` map shape from `atproto.repo/format-commit`.
- `commit-data` map shape (`:cid :rev :since :prev :new-blocks :relevant-blocks :removed-cids`) — consumed by WS-11 and firehose work.
- `atproto.repo.car/read-car`, `read-car-with-root`, `write-car` — consumed by firehose/stream client.
- `atproto.repo.sync/verify-diff`, `verify-repo`, `verify-proofs` signatures as above.

### Consumed

**WS-02 (`atproto.data.cbor` + `cid-for`)** — assumed contract, restated:

```clojure
(atproto.data.cbor/encode data)   ;; atproto data value -> DAG-CBOR bytes (canonical
                                  ;; map-key sort, CID = tag 42 w/ 0x00 prefix, bytes
                                  ;; as major type 2, no floats/indefinite lengths)
(atproto.data.cbor/decode bytes)  ;; bytes -> data value; {:error "InvalidCbor" ...} or
                                  ;; throws ex-info on malformed input (confirm w/ WS-02)
(atproto.data/cid-for data)       ;; = (atproto.data/cid-link (atproto.data.cbor/encode data))
                                  ;; CIDv1, dag-cbor codec, sha2-256
```

Keyword↔string map-key convention must match `atproto.data`'s existing model (unqualified keywords on the Clojure side, `src/atproto/data.cljc:117-126`). MST node fields therefore appear as `:l`, `:e`, `:p`, `:k`, `:v`, `:t`; commit fields `:did`, `:version`, `:data`, `:rev`, `:prev`, `:sig`. **Open coordination item:** WS-02 must guarantee `nil`-valued keys round-trip (commit `:prev` and node `:l`/`:t` are encoded as CBOR null, never omitted — see `packages/repo/src/mst/mst.ts:45-55` and `types.ts:31-39`).

**WS-03 (crypto sign/verify)** — frozen contract, restated per `03-crypto.md` "Interface contract" (and overview §4.9 item 1). Note the names and arg order: the verify fn is `verify-did-sig` with **sig-bytes before msg-bytes** (the *reverse* of TS `verifySignature(didKey, data, sig)` at `packages/crypto/src/verify.ts:6`), and the did:key accessor is the `Keypair` protocol method `did`, not `did-key`:

```clojure
(atproto.crypto/sign keypair msg-bytes)             ;; -> 64-byte compact low-S sig bytes
                                                    ;;    (Keypair protocol method; synchronous)
(atproto.crypto/verify-did-sig did-key sig-bytes msg-bytes & opts)
                                                    ;; -> boolean; opts {:allow-malleable? true}
                                                    ;;    for legacy high-S/DER acceptance
(atproto.crypto/did keypair)                        ;; -> "did:key:z..." for the public key
```

### Developing before WS-02 / WS-03 merge

1. **Code against the frozen signatures.** Reference the namespaces above; do not invent alternates. Keep every call to the WS-02/WS-03 surface behind two tiny internal wrapper fns (`atproto.repo.mst/-encode*`, `atproto.repo/-sign*` style) so the final integration milestone is a two-line change plus deletions.
2. **Test-only DAG-CBOR stub.** Until WS-02 lands, add `test/atproto/repo/test_support/cbor_stub.cljc`: a ~100-line DAG-CBOR encoder/decoder covering only what repo blocks need — maps with short string keys, ASCII strings, ints, byte strings, null, arrays, and tag-42 CIDs (via `atproto.data/encode-cid` + 0x00 prefix). Validate the stub itself against the known-CID fixtures (empty-tree root `bafyreie5737gdxlw5i64vzichcalba3z2v5n6icifvx5xytvske7mr3hpm`, etc. — if the stub computes these correctly it is correct for our block shapes). Bind the wrapper fns to the stub in tests only (e.g. via dynamic var or alter-var-root in a test fixture). Delete the stub in the integration milestone. Note: this requires the `cid-link` digest bug fix (see Current state) — apply it locally in the stub's `cid-for` if WS-02 hasn't landed.
3. **Fake signer for WS-03.** Commit signing/verification is structurally testable with a stub signer (e.g. `sig = sha256(bytes)`, verify by recomputation). Real ECDSA + did:key interop tests are gated to the integration milestone.
4. **Vendored fixtures** (below) exercise all tree logic without any network or real keys; only `sync`-level signature checks need WS-03.

## File ownership

Created by this workstream:

- `src/atproto/repo.cljc` — **⚠ also claimed by WS-07** (`07-xrpc-client-ergonomics.md` file-ownership table lists `src/atproto/repo.cljc` + `test/atproto/repo_test.cljc` as "create, conflicts: none" for its XRPC CRUD convenience ns). Resolved in the overview (§4.9 item 5 + conflict matrix): **WS-04 keeps `atproto.repo`** (parity with TS `@atproto/repo` = MST/commits); **WS-07 renames** its CRUD-wrapper ns (recommendation: `atproto.client.repo`, test at `test/atproto/client/repo_test.cljc`). Confirm WS-07 has adopted the rename before M5 lands.
- `src/atproto/repo/mst.cljc`
- `src/atproto/repo/car.cljc`
- `src/atproto/repo/blockstore.cljc`
- `src/atproto/repo/sync.cljc`
- `test/atproto/repo_test.cljc` — **⚠ also claimed by WS-07**; same resolution as `src/atproto/repo.cljc` above (WS-04 keeps it, WS-07 moves to `test/atproto/client/repo_test.cljc`).
- `test/atproto/repo/mst_test.cljc`
- `test/atproto/repo/car_test.cljc`
- `test/atproto/repo/sync_test.cljc`
- `test/atproto/repo/test_support/cbor_stub.cljc` (temporary; deleted at integration)
- `test/atproto/repo/test_support/util.cljc` (bulk key/record generators, port of `packages/repo/tests/_util.ts`)
- `test/interop-test-files/repo/car-file-fixtures.json` (vendored — **shared with WS-11B**, which vendors the same two fixture files per `11-service-pieces.md`; the copies are byte-identical from `packages/repo/tests/`, so whoever lands first creates the directory and the other rebases, conflict-free)
- `test/interop-test-files/repo/commit-proof-fixtures.json` (vendored — same WS-11B sharing note as above)
- `test/interop-test-files/repo/README.md` (provenance note: copied from `bluesky-social/atproto` `packages/repo/tests/`, not from the shared interop dir)

Modified (shared — coordinate):

- `src/atproto/runtime/bytes.cljc` — additive helpers only. WS-02 (CBOR) very likely also touches this file. **Resolution: WS-02 lands first; WS-04 rebases.** If WS-04's M4 (CAR) is ready earlier, add only `concat-bytes`/`slice` in a standalone commit and flag WS-02 to rebase that one file.
- `src/atproto/data.cljc` — **not modified by WS-04.** The `cid-link` bug fix and `cid-for` addition are WS-02's. WS-04 must not race it.
- `README.md` progress table (`README.md:20`) — updated in the final milestone only; trivial conflict surface, last-to-land rebases.
- `deps.edn` — no changes expected (varint/CID/sha256 all come from `mvxcvi/multiformats`, already present). If WS-02 adds a CBOR dep it owns that change.

## Test plan

Unit tests (no WS-02/03 needed):

- `mst-test`: `leading-zeros` vectors derived from reference test comments (`com.example.record/3jqfcqzm3fo2j`→layer 0, `…3fs2j`→1, `…3fx2j`→2, `…4fd2j`→1; `packages/repo/tests/mst.test.ts:296-301,368-379`); key validation accept/reject lists ported verbatim from `mst.test.ts:176-254`; `count-prefix-len`; node-data serialize/deserialize round-trip at the data-shape level (prefix compression, no-adjacent-subtrees invariant).
- `car-test`: varint round-trips; framing errors (`truncated header`, `truncated block`) → `{:error "InvalidCar"}`.
- `blockstore-test`: memory store + overlay semantics; `get-blocks` missing reporting.

Interop fixture tests (stub CBOR until WS-02, then re-run against the real codec):

- Known-map/edge-case root CIDs: port `mst.test.ts:256-307` (empty/trivial/singlelayer2/simple) and `:309-449` (trim-on-delete, two-layer split with insert+delete inversion, two-layers-higher insert) as table-driven tests with the exact CID strings.
- Bulk determinism: 1000 random keys inserted in shuffled order produce identical roots; add/edit/delete round-trips (`mst.test.ts:9-157`); diff correctness against recorded ops.
- `test/interop-test-files/repo/commit-proof-fixtures.json` (vendor from `/Users/luke/github/bluesky-social/atproto/packages/repo/tests/commit-proof-fixtures.json`): build tree from `keys`, assert `rootBeforeCommit`; apply `adds`/`dels`, assert `rootAfterCommit`; assert every `blocksInProof` CID ∈ covering proofs; assert proofs are invertible in **all permutations** of inverse ops over a blockstore containing only the proof blocks (`commit-proofs.test.ts:7-54`).
- `test/interop-test-files/repo/car-file-fixtures.json` (vendor from `/Users/luke/github/bluesky-social/atproto/packages/repo/tests/car-file-fixtures.json`): byte-exact CAR write from `{root, blocks}`; read back the `car` base64 and match roots/blocks/cids (`car.test.ts:23-61`); CID-verification failure test with a corrupted block (`car.test.ts:79-100`).

Integration tests (after WS-02/WS-03 land; final milestone):

- Port `repo.test.ts` (create/apply-writes/load round-trip), `sync.test.ts:15-95` (full-repo verify → rebuild → contents equal; diff sync of a repo that is behind; bad-signature rejection), `proofs.test.ts` (claims incl. nonexistence and tampered claims), and `commit-data.test.ts:10-94` (`relevant-blocks` includes the pre-existing proof block that `new-blocks` misses — the delete-first-key case).
- Live-service check (manual / `:dev` alias, not CI): fetch `https://<pds>/xrpc/com.atproto.sync.getRepo?did=<did>` for a known account with the existing HTTP client, `verify-repo-car` against the signing key from the DID document (`atproto.identity/resolve-did`), and spot-check a record against `com.atproto.repo.getRecord`. Ship as `examples/repo_verify.clj` or a `^:integration`-tagged test.

CLJS: all unit + fixture tests must pass under a CLJS runner for the `.cljc` namespaces (byte handling via `js/Int8Array` per `atproto.runtime.bytes`); the JVM streaming reader tests are `:clj`-only.

## Acceptance criteria

- [ ] All four known-map root CIDs and all three edge-case sequences from `mst.test.ts` reproduce exactly (string-equal CIDs).
- [ ] 1000-key shuffled insert/edit/delete produces order-independent, reference-identical roots; `diff` reports exactly the applied ops.
- [ ] All 6 cases in vendored `commit-proof-fixtures.json` pass, including proof-only inversion in every op permutation.
- [ ] Vendored `car-file-fixtures.json` round-trips byte-exactly (write) and structurally (read), with CID verification on by default and skippable.
- [ ] JVM streaming CAR reader processes a multi-MB CAR without materializing it (test with a generated 50k-block CAR; bounded memory).
- [ ] `format-commit` produces `relevant-blocks ⊇ new-blocks` and the `commit-data.test.ts` delete-first-key proof scenario verifies via `verify-proofs`.
- [ ] Commit sign + verify round-trip with real WS-03 keys (P-256 and K-256); `verify-repo-car` rejects a resigned/bad-sig repo with `{:error "RepoVerification"}`.
- [ ] `verify-proofs` verifies existence and nonexistence claims and rejects mismatched CIDs (claims sorted into `:verified`/`:unverified`).
- [ ] Full pipeline: `create` → `apply-writes` (creates/updates/deletes) → `repo->car` → `verify-repo-car` → rebuilt repo `contents` equals original.
- [ ] No thrown exceptions cross the public API: all failures are `{:error ... :message ...}` maps; all public fns have docstrings and specs for inputs.
- [ ] All tests green on JVM **and** CLJS for the `.cljc` surface; `clj -X:test` green at every milestone merge.
- [ ] Temporary CBOR stub and fake signer deleted; wrappers call `atproto.data.cbor`/`atproto.crypto` directly.

## Milestones

Each is one PR, independently mergeable, build green:

1. **M1 — blockstore + MST foundations (no CBOR needed).** `atproto.repo.blockstore` (protocols, memory impl, overlay); `atproto.repo.mst` key spec/validation, `leading-zeros`, `count-prefix-len`, node-data shape (de)serialization sans bytes/CIDs; `atproto.runtime.bytes` additions (coordinate w/ WS-02). Unit tests incl. ported key-validation vectors.
2. **M2 — MST core ops + interop roots.** Test-only CBOR stub (with local `cid-for` digest fix if WS-02 unlanded); lazy tree handles; `add`/`get-value`/`update-value`/`delete`/`pointer`/`serialize`/`unstored-blocks`; known-map + edge-case fixture tests; bulk determinism test.
3. **M3 — traversal, diff, proofs.** `leaf-seq`/`list*`/walks/`reachable-leaf-seq`; twin-walker `diff`; `covering-proof`, `cids-for-path`, `car-block-seq`; vendor `commit-proof-fixtures.json` + permutation-inversion tests.
4. **M4 — CAR v1.** `write-car`, `read-car`, `read-car-with-root`, JVM `block-reader`/`write-car-stream`; vendor `car-file-fixtures.json`; corruption/truncation tests; bounded-memory streaming test.
5. **M5 — repository layer.** Commit specs + `ensure-v3`, `sign-commit`/`verify-commit-sig` (fake signer behind wrapper if WS-03 unlanded), `create`/`load`/`format-commit`/`apply-commit`/`apply-writes`, record CRUD/`contents`. Port `repo.test.ts` + `commit-data` relevant-blocks scenario (structural, stub signer ok).
6. **M6 — sync verification.** `atproto.repo.sync` consumers + providers; port `sync.test.ts`/`proofs.test.ts` scenarios (signature assertions still stub-gated if WS-03 unlanded — structure/diff/claims assertions run regardless).
7. **M7 — integration & de-stub (after WS-02 + WS-03 merge).** Delete CBOR stub + fake signer; wire `atproto.data.cbor`/`atproto.crypto`; enable real-signature tests (P-256 + K-256); live `getRepo` walkthrough example; CLJS test pass; update `README.md:20` progress row.

## Risks & open questions

1. **Sync API vs. the SDK's async convention (decision needed at M1 review).** Recommendation: synchronous protocols/fns as sketched — all computation is in-memory; matches `atproto.data`/`atproto.tid`; avoids callback-hell in deeply recursive tree algorithms. Risk: WS-11 durable storage on CLJS (IndexedDB is async-only) cannot implement a synchronous `ReadableBlockstore`. Mitigation: WS-11 on CLJS preloads blocks (e.g. reads the CAR/store into a memory blockstore) or adds an async adapter namespace then; the JVM (the only platform with planned durable storage) is unaffected.
2. **`atproto.data/cid-link` digest bug** (`src/atproto/data.cljc:24-31`, uses `multiformats.hash/create` instead of `sha2-256`). Owned by WS-02 but it silently breaks every WS-04 fixture. Open question for orchestrator: confirm WS-02 will fix it in its first PR; otherwise WS-04 carries the fix in its test stub only (never in `src/`).
3. **DAG-CBOR canonicality is the single biggest interop risk.** A one-byte difference in map-key ordering or CID tagging changes every root CID. Mitigation: the known-CID fixtures in M2 validate the WS-02 codec end-to-end; coordinate with WS-02 to also run those fixtures in its own test suite. Must confirm WS-02 round-trips CBOR `null` for `:prev`/`:l`/`:t` (never key-omission) and rejects floats per the data model.
4. **CID parsing inside CAR blocks.** Reference uses a fixed 36-byte prefix (`packages/repo/src/car.ts:160-161`). Recommendation: parse structurally with `multiformats.cid/decode` semantics (version varint + codec varint + multihash) — strictly more correct, still cheap; add a test with a non-36-byte (e.g. identity-hash) CID to pin behavior (accept or clean `{:error "InvalidCar"}`, not a crash).
5. **Performance of persistent structures for 1000+ key trees.** TS caches entries and defers pointer hashing (`mst.ts:151-159`). The Clojure port should keep the `:outdated?`/lazy-entries design rather than recursively rehashing on every op; the 1000-key test doubles as a smoke benchmark. If CLJS `Int8Array` hashing is hot, key blocks by CID (multiformats CIDs hash cheaply), never by bytes. No new deps recommended.
6. **Library choice: keep `mvxcvi/multiformats` for varint/CID/multihash** (already a dep; verified APIs above). Alternative (`clj-multiformats`/hand-rolled) rejected — no gap identified. Its varint hard-stops at 9 bytes; fine, since CAR section lengths fit easily.
7. **`rev`/TID semantics.** `format-commit` must guarantee the new `rev` > previous `rev` (TS passes `prev` to `TID.nextStr(prev)`, `repo.ts:161`). `atproto.tid/next-tid` (`src/atproto/tid.cljc:41-45`) is monotonic per-process but takes no floor argument. Open question: extend `atproto.tid/next-tid` with an optional `floor` arity (tiny additive change; coordinate with whoever owns `atproto.tid`, likely fine inside WS-04 M5) or clamp inside `atproto.repo`.
8. **Legacy v2 commits.** Recommendation: accept on read via `ensure-v3` (matches `packages/repo/src/util.ts:115-125`), never write v2. Low risk; mostly affects very old repo exports.
9. **`:cljd` (ClojureDart) branches** appear in `atproto.runtime.interceptor` (`:45-47`) but the SDK doesn't ship Dart support for new namespaces. Recommendation: target `:clj`/`:cljs` only; do not add `:cljd` branches.
10. **Naming collision check:** `atproto.repo` (this WS, repository data structure) vs. the XRPC `com.atproto.repo.*` lexicon endpoints used by `atproto.client`. Judged acceptable — the lexicon NSIDs are strings, not namespaces — but flag in docs so users don't expect `atproto.repo` to make HTTP calls. Separately, **WS-07 also plans to create `src/atproto/repo.cljc`/`test/atproto/repo_test.cljc`** for its XRPC CRUD wrappers; per overview §4.9 item 5 WS-04 keeps the name and WS-07 renames (recommendation `atproto.client.repo`) — see File ownership; confirm before M5.
