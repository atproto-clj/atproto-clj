# WS-02: DAG-CBOR & Data Model Completion

| | |
|---|---|
| **Status** | Implemented (all 4 milestones; cljs CI verification deferred to WS-10) |
| **Priority** | P0 |
| **Estimated size** | L |
| **Branch** | ws/02-dag-cbor-data-model |
| **Depends on** | none |
| **Blocks** | WS-04 (repo/MST/CAR: needs `atproto.data.cbor` + `cid-for` + varint), WS-05 (firehose: needs `decode-multi` + `verify-cid`), WS-09 (lexicon lenient mode: consumes `::data/legacy-blob` spec), WS-10 (cljs parity: builds on the base64/bytes fixes landed here) |

## Goal

When this workstream is done, the SDK can serialize any atproto data-model value to canonical DAG-CBOR bytes, decode DAG-CBOR bytes (including streams of concatenated items, as found in firehose frames and CAR files) back into Clojure data, compute the CIDv1 of a record, and verify a CID against raw bytes — on both clj and cljs. It also closes the known data-model gaps that block downstream workstreams: the broken `cid-link`/`blob-ref` hashing, the clj-only (and spec-violating, padded) base64 codec, the missing cljs branch of `bytes/eq?`, the `data.cljc:144` TODO, and data-level support for legacy untyped blob refs.

## Current state

**There is no CBOR codec anywhere in the SDK.** `grep -ri cbor src/` matches only docstrings and the `:ipld-cbor` multicodec keyword (`src/atproto/data.cljc:4`, `:27`, `:30`, `:37`). `deps.edn` has no CBOR library: `com.cnuernber/charred` is JSON-only; `mvxcvi/multiformats {:mvn/version "1.0.125"}` covers CID/multihash/varint only.

What exists, with verified references:

- **CID support** — `src/atproto/data.cljc:19-79`, wrapping `mvxcvi/multiformats`: `cid?` (19-22), `cid-link` (24-31), `cid-link?` (33-38), `blob-ref` (40-47), `blob-ref?` (49-54), `encode-cid`/`decode-cid` (56-64), `format-cid` (66-69), `parse-cid` (71-79, returns nil on parse failure).
- **BUG: `cid-link` and `blob-ref` do not hash.** Both call `(mhash/create :sha2-256 bytes)` (`src/atproto/data.cljc:30-31` and `:46-47`). In multiformats 1.0.125, `multiformats.hash/create` *constructs* a multihash from an algorithm key and an **already-computed digest** (see `multiformats/hash.cljc`, `create`, "Constructs a new Multihash identifier from the given algorithm key (or numeric code) and digest byte array"); the function that actually hashes content is `multiformats.hash/sha2-256`. So today `(data/cid-link content-bytes)` builds a "CID" whose digest field is the raw content. The existing tests don't catch this: `test/atproto/data_test.cljc:16-19` and `test/atproto/data/json_test.cljc:19-21` pass arbitrary content bytes and only check spec validity / round-tripping, never a known-good CID string.
- **Data-model specs** — `src/atproto/data.cljc:85-138`: `js-max-integer`/`js-safe-int?` (86-89), `::integer` (97-98), `::bytes` (100, delegating to `atproto.runtime.bytes/bytes?`), `::link` (101), `::blob` (103-112), `::key` = unqualified keyword (117-119), `reserved-type?` (121), `::object` (123-126), `::value` (128-138). There is **no spec or helper for legacy untyped blob refs** (`{"cid": <cid-string>, "mimeType": <string>}`).
- **`eq?` and the bytes TODO** — `src/atproto/data.cljc:144-158`. Line 144: `;; todo: consider introducing an immutables bytes type to not have to do that`. `eq?` exists because platform byte arrays use identity equality.
- **`atproto.runtime.bytes`** — `src/atproto/runtime/bytes.cljc`: `bytes?` (10-13; cljs checks `js/Int8Array`), `eq?` (15-17) **has no `:cljs` branch** (returns nil on cljs), `->utf8` (19-22).
- **base64 is clj-only and spec-violating** — `src/atproto/runtime/crypto.cljc:29-35`. `base64-encode`/`base64-decode` have only `:clj` branches, so the JSON `$bytes` codec is completely broken on cljs. On clj, `Base64/getEncoder` emits **padded** output, but the atproto data model requires standard-alphabet base64 **without padding** for `$bytes` (the TS implementation strips padding — see `packages/lex/lex-data/src/uint8array-to-base64.ts:25`, `omitPadding: true`, and the comment at lines 38-41). Also `base64-encode` type-hints its arg `^String` while every call site passes bytes (forces reflection; works only accidentally). Only callers are `src/atproto/data/json.cljc:39`, `:69`, `:84` — safe to fix in place.
- **JSON codec** — `src/atproto/data/json.cljc`: specs (21-55, `$bytes` at 38-39), `encode` (61-72; line 69 emits padded `$bytes`), `decode` (74-87), HTTP interceptors (96-130). No legacy-blob awareness (legacy blobs happen to pass through as plain objects, but nothing validates or upgrades them).
- **varint** — nothing in the SDK, but the existing `mvxcvi/multiformats` dependency ships `multiformats.varint` (cross-platform `.cljc`) with `encode`, `decode`, `read-bytes` (returns `[value bytes-read]`), `write-bytes`. Verified in the 1.0.125 jar. No new dependency needed.
- **Multicodec keywords** — multiformats maps `:ipld-cbor` → `0x71` and `:raw` → `0x55` (`multiformats/codec.cljc` in the jar), matching `CBOR_DATA_CODEC`/`RAW_DATA_CODEC` in the TS reference.
- **Tests/fixtures conventions** — vendored interop files already live under `test/interop-test-files/` (crypto/, syntax/ + README). Tests are `.cljc`, run on the JVM via `clojure -X:test` (cognitect test-runner; see `deps.edn` `:test` alias). `org.clojure/test.check` is available in the `:dev`/`:test` aliases.

## Reference implementation guide

The TS monorepo recently refactored its data-model code into `packages/lex/*`; the old `@atproto/common` functions named in older docs (`cborEncode`, `cborDecode`, `cborDecodeMulti`, `cidForCbor`, `dataToCborBlock`, `verifyCidForBytes`) still exist but are deprecated wrappers. Port from the new packages; use `common` only to understand the legacy API surface.

All paths below are under `/Users/luke/github/bluesky-social/atproto`.

**Primary: `packages/lex/lex-cbor/src/encoding.ts`** — the DAG-CBOR codec, implemented as a strictly-configured [cborg](https://github.com/rvagg/cborg) profile. This file is the behavioral spec for our codec:
- `encodeOptions` (lines 31-69):
  - CID encoding (47-57): a CID encodes as **tag 42** (`d8 2a`) wrapping a byte string of `0x00` (identity multibase prefix, "for historical reasons") followed by the CID's binary form.
  - Map keys must be strings (35-46).
  - `undefined` map values are silently stripped; a bare `undefined` is an encode error (58-60, plus `ignoreUndefinedProperties: true` at 33).
  - Numbers: only safe integers; floats/NaN/±Infinity are encode errors (61-67).
  - cborg's canonical encoder produces **definite lengths, shortest-form integers, and map keys sorted length-first then bytewise** — the DAG-CBOR canonical form.
- `decodeOptions` (lines 75-95): `allowIndefinite: false`, `coerceUndefinedToNull: true` (CBOR `0xf7` decodes as null), `allowNaN: false`, `allowInfinity: false`, `strict: true` (rejects non-shortest-form encodings), `rejectDuplicateMapKeys: true`; tag-42 decoder (86-93) **errors unless the first byte of the tagged byte string is `0x00`**, then decodes the remaining bytes as a CID.
- `encode` (119-121), `decode` (150-152, rejects trailing bytes), `decodeAll` generator (184-192) — repeatedly `decodeFirst`s a buffer of concatenated CBOR items; this is `decode-multi`.

**`packages/lex/lex-cbor/src/index.ts`** — `cidForLex` (41-43): `cidForCbor(encode(value))`, i.e. DAG-CBOR encode, sha2-256, CIDv1 with codec `0x71`.

**`packages/lex/lex-data/src/cid.ts`** — CID algorithms:
- `CBOR_DATA_CODEC = 0x71` (line 15), `RAW_DATA_CODEC = 0x55` (line 25).
- `isDaslCid` (245-252): v1 + (raw|dag-cbor) + sha-256 with 32-byte digest.
- `isCidForBytes` (480-498): hash the bytes with the cid's multihash algorithm (sha-256/sha-512 supported), compare digests — basis for our `verify-cid`.
- `cidForCbor` (532-535), `cidForRawBytes` (545-548), `cidForRawHash` (557-565), `validateCidString` (415-420, checks canonical re-encoding).

**`packages/lex/lex-data/src/blob.ts`** — blob refs: `TypedBlobRef` (line 180), **`LegacyBlobRef`** (lines 308-311: `{ cid: string, mimeType: string }`), `isLegacyBlobRef` (line 333: validates cid string, non-empty mimeType, **no additional properties**).

**`packages/lex/lex-json/src/lex-json.ts`** — JSON↔data conversion: `jsonToLex` (line 154), `lexToJson` (line 251). Note at 348-349: legacy blob representations are deliberately **not** special-cased by the JSON codec — they're handled at the application/lexicon level. Our `atproto.data.json` should do the same (pass through; expose predicates/helpers only).

**Legacy API surface (for naming the contract): `packages/common/src/ipld.ts`** — `cborEncode`/`cborDecode` (25-32), `dataToCborBlock` (37-49), `cidForCbor` (54-57), `verifyCidForBytes` (81-88, throws on mismatch), `VerifyCidTransform` streaming verifier (104-126; out of scope, blob fetching may want it later). `packages/common/src/ipld-multi.ts` — `cborDecodeMulti` (7-9).

**Downstream consumer preview: `packages/repo/src/car.ts`** — lines 3, 20-25, 194-205 show varint-framed CAR block reading/writing (WS-04 will consume our varint util exactly like this).

### Interop test fixtures

`/Users/luke/github/bluesky-social/atproto/interop-test-files/` contains **only** `crypto/` and `syntax/` — there are no upstream data-model/CBOR fixtures there (verified). The vendorable fixtures live in the lex-cbor package tests:

- `packages/lex/lex-cbor/tests/data-model-fixtures.json` — 3 vectors of `{json, cbor_base64, cid}` covering nested objects, unicode (incl. multi-codepoint grapheme clusters), `$link`, `$bytes`, and typed blob. **Vendor this file verbatim.**
- `packages/lex/lex-cbor/tests/vectors.ts` — 5 named vectors (`basic`, `ipld`, `ipldArray`, `ipldNested`, `poorlyFormatted`) with explicit expected CBOR byte arrays and CIDs; `poorlyFormatted` exercises `$link`/`$bytes`-shaped maps with extra keys (which must round-trip as plain maps, not links/bytes).
- `packages/lex/lex-cbor/tests/dag-cbor.test.ts` — strictness cases with hex-encoded inputs to port: reject floats/NaN/Infinity on decode (163-197, e.g. `f97e00`, `fb7ff0000000000000`), coerce `0xf7` undefined → null (256-265, `f7` and `a26362617af763666f6f63626172`), reject duplicate map keys (267-273, `a3636261720363666f6f0163666f6f02`), reject bad CID lead-in byte (244-254, hex at 247-249), reject trailing data after a single `decode` (234-242), tag-42 count check (52-61).
- `packages/lex/lex-cbor/tests/codec.test.ts` — encode-side rejections: floats (15-17), non-string map keys (27-37); plus identity round-trip vectors (50+).
- `packages/lex/lex-cbor/tests/fixtures.test.ts` — the round-trip recipe our fixture test should mirror: `json → lex → cidForLex == cid`, `encode == base64(cbor)`, `decodeAll` yields exactly one value, `lexToJson(decoded) == json`.

Spec documents: <https://atproto.com/specs/data-model> (atproto restrictions: no floats, i53 integer range, blob shape), <https://ipld.io/specs/codecs/dag-cbor/spec/> (canonical form: definite lengths, shortest-form ints, length-first-then-bytewise key sort, tag 42), <https://dasl.ing/drisl.html> and <https://dasl.ing/cid.html> (DASL profiles atproto follows).

## Scope

### In scope

- New namespace `atproto.data.cbor` (`.cljc`, clj + cljs branches): `encode`, `decode`, `decode-first`, `decode-multi` implementing the atproto DAG-CBOR subset:
  - Major types 0/1 (ints, shortest form), 2 (byte strings), 3 (UTF-8 text), 4 (arrays), 5 (maps, string keys only), 6 (tag 42 only), 7 (`true`/`false`/`null` only; `0xf7` undefined coerced to null on decode).
  - Definite lengths only; reject indefinite (`0x*f` additional-info 31) on decode.
  - Canonical encode: map keys sorted by UTF-8 byte length, then bytewise (unsigned); shortest-form integer/length encodings.
  - Strict decode: reject non-shortest-form ints/lengths, duplicate map keys, floats (half/single/double), tags other than 42, simple values other than true/false/null/undefined, trailing bytes (for `decode`), truncated input.
  - CID links: encode CID values as tag 42 + `0x00`-prefixed CID bytes; decode requires the `0x00` prefix and returns a multiformats CID.
  - Map keys: encode accepts unqualified keywords (and strings); decode produces unqualified keywords, matching `::data/key` (`src/atproto/data.cljc:117-119`).
  - Integers: encode rejects non-integers and values outside ±(2^53 − 1) (matches TS and `::data/integer`); decode accepts the full int64 range on clj, errors outside the safe range on cljs.
- `atproto.data` additions: `cid-for` (data → CIDv1/dag-cbor/sha2-256), `verify-cid` (cid + bytes → `true` or error map), and a corrected raw-blob CID constructor.
- **Fix the `cid-link`/`blob-ref` hashing bug** (`src/atproto/data.cljc:30-31`, `:46-47`): use `multiformats.hash/sha2-256` (which hashes) instead of `multiformats.hash/create` (which wraps a digest). Add known-answer tests so this can never regress silently.
- New namespace `atproto.runtime.varint` — thin `.cljc` wrapper over `multiformats.varint` (`encode`, `decode`, `read-bytes`) so WS-04/WS-05 don't bind directly to the mvxcvi library.
- **Fix base64** in `src/atproto/runtime/crypto.cljc:29-35`: `base64-encode` produces unpadded standard-alphabet base64 from bytes (clj: `Base64/getEncoder` + `.withoutPadding`, proper `^bytes` hint; cljs: `goog.crypt.base64`), `base64-decode` accepts padded and unpadded input and returns platform bytes, nil on invalid input. Add a `:cljs` branch to `atproto.runtime.bytes/eq?` (`src/atproto/runtime/bytes.cljc:15-17`).
- **Resolve the TODO at `src/atproto/data.cljc:144`**: decision — keep raw platform byte arrays as the data-model bytes representation (no wrapper type), document that `data/eq?` is the structural-equality entry point, and make `eq?` correct on both platforms. (Rationale in Risks.)
- **Legacy untyped blob refs**, data level only: `::data/legacy-blob` spec + `legacy-blob?` predicate (shape `{:cid <valid cid string> :mimeType <non-empty string>}`, no extra keys, per `lex-data/src/blob.ts:308-333`) and an `upgrade-legacy-blob` helper (legacy → typed blob map with parsed `ref` and `:size -1` sentinel, mirroring `lex-data/src/blob.ts:255`). JSON/CBOR codecs treat legacy blobs as plain maps (TS parity).
- Vendor `packages/lex/lex-cbor/tests/data-model-fixtures.json` into `test/interop-test-files/data-model/` and port the strictness vectors from `dag-cbor.test.ts`/`codec.test.ts` as hex literals.

### Out of scope

- **CAR file reading/writing, MST, repo structure** — WS-04 (it consumes `encode`/`decode`/`decode-first`, `cid-for`, and `atproto.runtime.varint`).
- **Firehose framing semantics** (`#frame` header/body interpretation, websocket transport, sequence handling) — WS-05 (it consumes `decode-multi` and `verify-cid`).
- **Lexicon-level lenient-mode validation toggles** (accepting legacy blobs when validating records against schemas) — WS-09; this workstream only provides the data-level spec/predicates/upgrade helper.
- **cljs build/test infrastructure** — WS-10. This workstream *writes* `:cljs` branches for everything it touches, but CI verification of cljs stays with WS-10 (there is currently no cljs test runner in the repo).
- A streaming/`Transform`-style CID verifier for blob downloads (`packages/common/src/ipld.ts:104-126`) — defer to the workstream that implements blob fetch.
- Canonical-JSON or any change to `atproto.runtime.json`; charred stays JSON-only.
- `cid-link?`/`blob-ref?` semantics, lexicon specs, XRPC — unchanged except where noted.

## Deliverables

### `src/atproto/data/cbor.cljc` (new)

Pure, synchronous functions (no I/O — the SDK's interceptor/callback convention applies to I/O-bound operations like those in `atproto.identity`; pure codecs follow the synchronous style of `atproto.data.json/encode|decode`). Invalid input **throws** `ex-info` whose `ex-data` is an SDK-style error map — error *returns* are not used here because decoded data is arbitrary and `{:error ...}` is itself a valid atproto data value.

```clojure
(ns atproto.data.cbor
  "DAG-CBOR codec for the atproto data model subset.

  Canonical form: definite lengths only, shortest-form integers,
  map keys sorted length-first then bytewise; CID links as tag 42
  with 0x00 identity-multibase prefix; no floats.

  See https://atproto.com/specs/data-model and
  https://ipld.io/specs/codecs/dag-cbor/spec/")

(defn encode
  "Encode atproto data to canonical DAG-CBOR bytes.

  Accepts: nil, booleans, integers in [-2^53+1, 2^53-1], strings,
  platform bytes, CIDs (multiformats.cid), vectors/sequentials, and
  maps with unqualified-keyword (or string) keys.

  Returns platform bytes (clj: byte[]).
  Throws ex-info with ex-data {:error \"InvalidDataModel\"
                               :message <human-readable>
                               :path <vector path into data>}
  on floats, out-of-range ints, qualified/non-string keys, or any
  unsupported value type."
  ^bytes [data] ...)

(defn decode
  "Decode a single DAG-CBOR item; rejects trailing bytes.

  Map keys become unqualified keywords; tag 42 becomes a CID;
  byte strings become platform bytes; 0xf7 (undefined) becomes nil.

  Throws ex-info with ex-data {:error \"InvalidCbor\"
                               :message <...> :offset <byte offset>}
  on floats, indefinite lengths, non-shortest-form encodings,
  duplicate map keys, unknown tags/simple values, bad tag-42 payloads
  (missing 0x00 prefix or invalid CID), truncation, or trailing bytes."
  [bytes] ...)

(defn decode-first
  "Decode the first DAG-CBOR item in bytes.

  Returns [value bytes-consumed]. Same strictness/errors as decode,
  except trailing bytes are expected and left for the caller."
  [bytes] ...)

(defn decode-multi
  "Decode a buffer of concatenated DAG-CBOR items (e.g. a firehose
  frame: header item followed by body item).

  Returns a vector of decoded values. Throws (as decode) if any item
  is invalid or the final item is truncated."
  [bytes] ...)
```

Implementation notes for the encoder (no library, see Risks for the decision):
- clj: build into a `java.io.ByteArrayOutputStream`/`ByteBuffer`; cljs: grow a `js/Uint8Array` (or accumulate chunks, then concat).
- Key sort: precompute `[utf8-bytes encoded-value]` pairs, sort by `(count utf8-bytes)` then unsigned bytewise; write count-prefixed map.
- CID write: `(let [b (multiformats.cid/encode cid)] (write-tag 42) (write-byte-string (cons 0x00 b)))`.
- Integer head encoding: additional info 0-23 inline, 24/25/26/27 for 1/2/4/8-byte arguments, always smallest that fits (this also covers string/bytes/array/map length heads).

### `src/atproto/data.cljc` (modified)

```clojure
(defn cid-for
  "Compute the CID of atproto data: DAG-CBOR encode, sha2-256,
  CIDv1 with the dag-cbor (0x71) multicodec.

  Throws (as atproto.data.cbor/encode) if data is not valid
  atproto data."
  [data]
  (cid/create :ipld-cbor (mhash/sha2-256 (cbor/encode data))))

(defn blob-ref          ;; FIXED: must hash; today it wraps content as digest
  "CID for raw blob bytes: sha2-256, CIDv1, raw (0x55) multicodec."
  [bytes]
  (cid/create :raw (mhash/sha2-256 bytes)))

(defn cid-link          ;; FIXED + docstring clarified: takes DAG-CBOR bytes
  "CID for already-encoded DAG-CBOR bytes (dag-cbor 0x71, sha2-256).
  For un-encoded data, use cid-for."
  [cbor-bytes]
  (cid/create :ipld-cbor (mhash/sha2-256 cbor-bytes)))

(defn verify-cid
  "Verify that cid matches the given bytes.

  Hashes bytes with the cid's multihash algorithm (sha2-256 or
  sha2-512) and compares digests.

  Returns true on match, otherwise an error map:
  {:error \"CidMismatch\" :message <...> :expected <cid-str> :actual <cid-str>}
  {:error \"UnsupportedHashAlgorithm\" :message <...>}"
  [cid bytes] ...)

;; Legacy untyped blob refs (data level; lexicon lenient mode is WS-09)
(s/def ::legacy-blob ...)   ;; exactly {:cid <valid cid string> :mimeType <non-empty string>}
(defn legacy-blob? [v] ...)
(defn upgrade-legacy-blob
  "Convert a legacy untyped blob ref to the typed form.
  Returns {:$type \"blob\" :ref <parsed CID> :mimeType <mimeType> :size -1},
  or nil if input is not a valid legacy blob ref."
  [m] ...)
```

The TODO at line 144 is replaced by a comment documenting the decision (raw byte arrays + `eq?`; see Risks), and `eq?` keeps working unchanged on top of the fixed `bytes/eq?`.

### `src/atproto/runtime/varint.cljc` (new)

```clojure
(ns atproto.runtime.varint
  "Unsigned LEB128 varints (multiformats unsigned-varint).
  Used for CAR block framing (WS-04) and CID prefixes."
  (:require [multiformats.varint :as varint]))

(defn encode "n -> bytes" ^bytes [n] (varint/encode n))
(defn decode "bytes -> n (reads from offset 0)" [bytes] (varint/decode bytes))
(defn read-bytes
  "Read a varint at offset. Returns [value bytes-read].
  Throws ex-info on truncation or >9-byte varints."
  [bytes offset] (varint/read-bytes bytes offset))
```

### `src/atproto/runtime/crypto.cljc` (modified)

```clojure
(defn base64-encode
  "bytes -> standard-alphabet base64 string, no padding (atproto $bytes form)."
  [^bytes b]
  #?(:clj (.encodeToString (.withoutPadding (Base64/getEncoder)) b)
     :cljs (goog.crypt.base64/encodeByteArray b ...)))  ;; strip padding

(defn base64-decode
  "Standard-alphabet base64 string (padded or unpadded) -> bytes.
  Returns nil if the input is not valid base64."
  [s] ...)
```

### `src/atproto/runtime/bytes.cljc` (modified)

`eq?` gains a `:cljs` branch (elementwise compare); `bytes?`'s cljs type stays as-is in this workstream unless WS-10 has already standardized it (see Risks/open question on `Int8Array` vs `Uint8Array`).

### `src/atproto/data/json.cljc` (modified)

No API change. `$bytes` output becomes unpadded via the crypto fix; add a docstring note + test that legacy blob refs pass through `encode`/`decode` unchanged as plain maps (TS parity, `lex-json.ts:348-349`).

## Interface contract

**Frozen API that WS-04, WS-05, and WS-09 may code against** (all synchronous, all `.cljc`):

```clojure
(atproto.data.cbor/encode data)            ;; => platform bytes        (throws ex-info)
(atproto.data.cbor/decode bytes)           ;; => data, no trailing     (throws ex-info)
(atproto.data.cbor/decode-first bytes)     ;; => [data bytes-consumed] (throws ex-info)
(atproto.data.cbor/decode-multi bytes)     ;; => vector of data        (throws ex-info)
(atproto.data/cid-for data)                ;; => CID (v1, dag-cbor 0x71, sha2-256)
(atproto.data/verify-cid cid bytes)        ;; => true | {:error "CidMismatch" ...}
                                           ;;         | {:error "UnsupportedHashAlgorithm" ...}
(atproto.data/blob-ref bytes)              ;; => CID (v1, raw 0x55, sha2-256) — now actually hashes
(atproto.data/cid-link cbor-bytes)         ;; => CID (v1, dag-cbor, sha2-256) — now actually hashes
(atproto.data/legacy-blob? v)              ;; => boolean
(atproto.data/upgrade-legacy-blob m)       ;; => typed blob map | nil
:atproto.data/legacy-blob                  ;; spec (consumed by WS-09)
(atproto.runtime.varint/encode n)          ;; => bytes
(atproto.runtime.varint/decode bytes)      ;; => n
(atproto.runtime.varint/read-bytes b off)  ;; => [n bytes-read]
```

Decoded data shape (what WS-04/WS-05 will see): unqualified-keyword map keys, multiformats CIDs for links, platform bytes for byte strings, vectors for arrays, `nil`/booleans/longs/strings for scalars — i.e. exactly the values accepted by `:atproto.data/value` (`src/atproto/data.cljc:128-138`).

This workstream consumes no other workstream's contract. Downstream development before this merges: WS-04/WS-05 can stub `atproto.data.cbor` against the vendored `data-model-fixtures.json` (the `cbor_base64` field gives ready-made expected bytes for the three fixture values).

**Breaking-change note for parallel workstreams**: `cid-link`/`blob-ref` change behavior (they now hash). Today's behavior is simply wrong, and the only in-repo callers are the two test files cited above, but any workstream branch creating CIDs must rebase onto this fix rather than copy the old call.

## File ownership

Created by this workstream:
- `src/atproto/data/cbor.cljc`
- `src/atproto/runtime/varint.cljc`
- `test/atproto/data/cbor_test.cljc`
- `test/atproto/runtime/varint_test.cljc`
- `test/interop-test-files/data-model/data-model-fixtures.json` (vendored from `packages/lex/lex-cbor/tests/data-model-fixtures.json`)

Modified by this workstream:
- `src/atproto/data.cljc` (cid-for/verify-cid/legacy-blob; fix cid-link/blob-ref; resolve TODO:144)
- `src/atproto/data/json.cljc` (docstring + legacy-blob passthrough notes; unpadded `$bytes` via crypto fix)
- `src/atproto/runtime/crypto.cljc` (base64 encode/decode: unpadded, `:cljs` branches, correct hints)
- `src/atproto/runtime/bytes.cljc` (`eq?` `:cljs` branch)
- `test/atproto/data_test.cljc`, `test/atproto/data/json_test.cljc` (known-answer CID tests; unpadded `$bytes`)

Shared-file conflicts and agreed resolution:
- `src/atproto/runtime/crypto.cljc` — **WS-03 (crypto)** also modifies this file (sha256 bytes fix, hint fix, `base64url-decode`/hex helpers; see `03-crypto.md` Deliverables §1) and its API sketch currently specs `base64-encode` as "with padding" (`03-crypto.md:114-116`), the exact fn this workstream rewrites. **Agreed resolution (00-overview.md §4.9 item 4 and the conflict matrix at `00-overview.md:437`): WS-03 owns the file; WS-02 owns the base64 semantics — the unpadded encode (+ padded-or-unpadded decode, `:cljs` branches) specified here wins. The overlap is only `base64-encode`; land WS-02/WS-03 in either order, whoever lands second rebases.** WS-03 should correct its docstring at kickoff per §4.9 item 4. (Per the same matrix row: WS-06 drops its sha256 change, WS-08 drops its `base64url-decode`/hex duplicates, WS-10 never touches this file.)
- `src/atproto/runtime/bytes.cljc` — **WS-04 (MST/repo/CAR)** adds `concat-bytes`/`slice` to this file (`04-mst-repo-car.md:489`). **Agreed resolution (`00-overview.md:439`, mirrored in `04-mst-repo-car.md:489`): WS-02 owns the file and lands the `eq?` `:cljs` fix first; WS-04's additions are purely additive and rebase on top.** If WS-04's CAR milestone is ready earlier, WS-04 adds only `concat-bytes`/`slice` in a standalone commit and WS-02 rebases that one file.
- `src/atproto/runtime/crypto.cljc` and `src/atproto/runtime/bytes.cljc` are also in WS-10's (cljs runtime parity) blast radius. **WS-02 owns the base64 and `bytes/eq?` fixes and lands first; WS-10 rebases on top.** WS-10 owns any broader cljs byte-type standardization (`Int8Array` → `Uint8Array`); if WS-10 lands such a change first, WS-02 rebases its `:cljs` branches onto the new type.
- `src/atproto/data.cljc` specs are consumed by `src/atproto/lexicon.cljc` (e.g. `::data/blob` at `lexicon.cljc:577-579`); WS-09 must not redefine `::data/legacy-blob` — it consumes it.

## Test plan

All tests `.cljc` under `test/`, runnable with `clojure -X:test` on the JVM (cljs runs deferred to WS-10).

1. **Vendored interop fixtures** — `test/interop-test-files/data-model/data-model-fixtures.json` (source: `/Users/luke/github/bluesky-social/atproto/packages/lex/lex-cbor/tests/data-model-fixtures.json`). For each `{json, cbor_base64, cid}` fixture, mirror `packages/lex/lex-cbor/tests/fixtures.test.ts`:
   - `(json/decode fixture-json)` → data; `(cbor/encode data)` bytes-equals base64-decoded `cbor_base64`;
   - `(data/format-cid (data/cid-for data))` equals `cid`;
   - `(cbor/decode-multi cbor-bytes)` yields exactly one value; `(json/encode that-value)` equals the original JSON;
   - `(data/verify-cid (data/parse-cid cid) cbor-bytes)` is `true`.
2. **Strictness vectors ported as hex literals** (sources: `packages/lex/lex-cbor/tests/dag-cbor.test.ts` lines 163-273 and `tests/codec.test.ts` lines 15-37):
   - decode rejects: `f97e00`, `f97ff8`, `fa7ff80000`, `fb7ff8000000000000` (NaN forms), `f97c00`/`fb7ff0000000000000` (+Inf), `f9fc00`/`fbfff0000000000000` (−Inf), plus *any* float (stricter than TS — see Risks), `a3636261720363666f6f0163666f6f02` (duplicate keys), the bad-CID-lead-in vector at `dag-cbor.test.ts:247-249`, indefinite-length items (e.g. `9f...ff`, `5f...ff`), non-shortest ints (e.g. `1817` for 23), truncated input, trailing bytes after `decode`;
   - decode coerces: `f7` → nil, `a26362617af763666f6f63626172` → `{:baz nil :foo "bar"}`;
   - encode rejects: floats (`3.14`), NaN/Infinity, ints outside ±(2^53−1), qualified keyword keys, unsupported types (keywords as *values*, sets, dates).
3. **Canonical-form unit tests**: map-key ordering (incl. keys of differing byte lengths and multi-byte UTF-8 keys); shortest-form heads across boundaries (0, 23, 24, 255, 256, 65535, 65536, 2^32−1, 2^32, ±2^53−1); `{:hello "world"}` encodes to `a16568656c6c6f65776f726c64` (`codec.test.ts:7-13`); tag-42 byte pattern `d8 2a 58 25 00 ...` appears once per link.
4. **`decode-first`/`decode-multi`**: two+ concatenated fixture items decode in order; `decode-first` returns correct `bytes-consumed`; truncated second item throws.
5. **Generative round-trip**: with test.check (already in `:test` alias), generate `:atproto.data/value` instances (custom generators for bytes/CIDs) and assert `(data/eq? v (cbor/decode (cbor/encode v)))` and `(= (seq (cbor/encode v)) (seq (cbor/encode (cbor/decode (cbor/encode v)))))` (canonical stability).
6. **CID/hash regression**: `(data/blob-ref bytes)` and `(data/cid-for data)` against the three fixture CIDs plus the `bafkreia...`-style raw CID embedded in fixture 2's blob — these are the tests that would have caught the `mhash/create` bug.
7. **base64**: `$bytes` value `"nFERjvLLiw9qm45JrqH9QTzyC2Lu1Xb4ne6+sBrCzI0"` (fixture 2) decodes to 32 bytes and re-encodes **without padding** to the identical string; padded input also decodes; invalid input → nil.
8. **varint**: round-trip 0, 1, 127, 128, 16383, 16384, large CAR-realistic sizes; `read-bytes` offset/length behavior; cross-check against `multiformats.varint`.
9. **Legacy blob**: `legacy-blob?` accepts `{:cid "bafkrei..." :mimeType "image/jpeg"}`, rejects extra keys/invalid cid/empty mimeType; `upgrade-legacy-blob` produces a `::data/blob`-conforming map except `:size -1`; JSON and CBOR codecs round-trip a legacy blob as a plain map.
10. **Live verification (manual, documented in the test ns docstring, not CI)**: `com.atproto.repo.getRecord` for any public bsky post returns `{:uri ... :cid ... :value ...}`; check `(= cid (data/format-cid (data/cid-for value)))`. This proves end-to-end CID parity against a production PDS.

## Acceptance criteria

- [x] `clojure -X:test` is green on JVM with all new tests enabled.
- [x] All 3 vendored data-model fixtures pass: byte-exact `encode`, CID-exact `cid-for`, JSON round-trip.
- [x] All ported strictness vectors behave as specified (floats, duplicate keys, indefinite lengths, non-shortest forms, bad tag-42 lead-in, trailing bytes, `f7` coercion).
- [x] `(data/format-cid (data/blob-ref (bytes-of "...")))` matches an independently computed raw CID (the `mhash/create` bug is fixed and pinned by a known-answer test).
- [x] `data.json/encode` emits unpadded `$bytes`; `base64-encode`/`base64-decode` and `bytes/eq?` have working `:cljs` branches (code-reviewed; cljs CI execution is WS-10).
- [x] `decode-multi` decodes a buffer of ≥2 concatenated items (firehose-frame shape) and errors on truncation.
- [x] `atproto.runtime.varint` round-trips and matches `multiformats.varint` byte-for-byte.
- [x] `::data/legacy-blob`, `legacy-blob?`, `upgrade-legacy-blob` exist and are spec'd; codecs pass legacy blobs through unchanged.
- [x] The TODO at `src/atproto/data.cljc:144` is replaced with the documented decision.
- [x] No new dependency added to `deps.edn` (or, if the library route is taken after all, the decision is recorded in this doc's Risks section and the dep is JVM+cljs compatible).
- [x] Manual live check against a real PDS record documented and performed once (`getRecord` CID parity).

## Milestones

Each is an independently mergeable, green PR on `ws/02-dag-cbor-data-model`:

1. **PR 1 — runtime fixes + varint** (S): `atproto.runtime.varint`; base64 unpadded + `:cljs` branches; `bytes/eq?` `:cljs`; fix `cid-link`/`blob-ref` hashing with known-answer CID tests; update `data_test`/`json_test`. No new namespaces beyond varint; nothing depends on CBOR yet.
2. **PR 2 — DAG-CBOR encode** (M): `atproto.data.cbor/encode` (canonical form, all rejections); vendor `data-model-fixtures.json`; byte-exact fixture tests + `cid-for` (which only needs encode) + fixture CID tests.
3. **PR 3 — DAG-CBOR decode + multi** (M): `decode`, `decode-first`, `decode-multi`; ported strictness vectors; generative round-trip; `verify-cid` + fixture verification tests. Contract is now fully usable by WS-04/WS-05.
4. **PR 4 — legacy blobs + data-model polish** (S): `::data/legacy-blob` + helpers; resolve TODO:144 with documented decision; JSON-codec passthrough tests; finalize docstrings; perform/document the live `getRecord` parity check.

## Risks & open questions

1. **Library decision: hand-rolled `.cljc` codec vs `mvxcvi/clj-cbor` (JVM). Recommendation: hand-rolled.**
   - `clj-cbor` is JVM-only, so cljs would need a second implementation anyway — the worst of both worlds for a `.cljc`-first SDK.
   - `clj-cbor`'s canonical mode targets RFC 7049 canonical CBOR generally; its key ordering (length-then-bytewise) is compatible, but out of the box it (a) encodes keywords/sets/tagged-literals with its own self-describing tags, (b) accepts indefinite lengths and floats on decode, (c) has no tag-42-with-`0x00`-prefix handling, and (d) doesn't reject duplicate keys or enforce shortest-form on decode — so we'd be writing nearly all the strictness layer ourselves on top of a JVM-only dependency.
   - The TS reference itself doesn't use a stock codec: it heavily constrains `cborg` (`lex-cbor/src/encoding.ts`). The atproto subset (7 value kinds + 1 tag, definite lengths, no floats) is small; a focused two-function codec is roughly 300-500 lines of `.cljc` with full control over error messages and zero new deps.
2. **Float handling on decode — stricter than TS.** TS `decodeOptions` rejects NaN/Infinity but would decode ordinary floats; the atproto data-model spec says floats are not supported, and the Go implementation rejects them. Recommendation: reject all floats on decode (spec-aligned); record the TS divergence here. If repo interop later surfaces real-world floats in legacy records, add an opt-in lenient flag (owner: whichever of WS-04/WS-05 hits it first, with WS-02 review).
3. **Decode does not enforce sorted map keys** (matches TS/cborg). Consequence: decode→encode of non-canonical third-party bytes may not be byte-identical, so commit verification (WS-05) and MST hashing (WS-04) must always hash the *original* bytes, never re-encoded ones. This is called out in the contract; flagging here so downstream docs repeat it.
4. **cljs byte type: `js/Int8Array` (current `bytes/bytes?`, `src/atproto/runtime/bytes.cljc:13`) vs `js/Uint8Array` (what goog.crypt and multiformats/alphabase produce).** Recommendation: standardize on `Uint8Array`; owner WS-10, with WS-02 writing its `:cljs` branches against whatever `bytes/bytes?` says at merge time. Open until WS-10 confirms.
5. **Immutable-bytes wrapper (TODO `data.cljc:144`). Recommendation: do not introduce one.** A wrapper type would ripple through every spec, codec, and user-facing record API, and downstream workstreams would have to unwrap at each platform boundary (hashing, websockets, HTTP bodies). Keeping raw platform arrays + `data/eq?` matches the TS SDK (raw `Uint8Array`) and keeps the WS-04/WS-05 contract simple. The cost — maps containing bytes don't compare with `=` — is documented on `data/eq?`.
6. **Integers beyond ±2^53 in foreign data** (CBOR can carry full uint64): decode errors on values that don't fit a signed 64-bit long (clj) or the JS safe range (cljs). Real atproto data must be within i53 anyway (`::data/integer`); flagged in case repo history contains out-of-range values.
7. **Unpaired surrogates in strings**: JVM `String/getBytes UTF-8` silently substitutes `?`, TS throws via cborg's strict UTF-8. Initial implementation may accept the JVM substitution; add a strict check if interop testing shows it matters (cheap: validate with `CharsetEncoder` configured to REPORT).
8. **`base64-decode` leniency**: kept lenient about padding on input (accepts both) while output is always unpadded. The `$bytes` spec at `src/atproto/data/json.cljc:38-39` therefore accepts padded JSON input; if strict rejection of padded `$bytes` is ever required, that's a WS-09 lexicon-validation concern.
