# WS-03: Cryptography Parity

| | |
|---|---|
| **Status** | Planning |
| **Priority** | P0 |
| **Estimated size** | M |
| **Branch** | ws/03-crypto |
| **Depends on** | none (mvxcvi/multiformats and test fixtures are already in the repo) |
| **Blocks** | WS-04 (repo/MST: commit signature verification), WS-06 (identity: DID-doc signing-key extraction + did:key), WS-08 (XRPC server: service-auth JWT verification), WS-10 (cljs runtime — stretch, coordinated after this lands) |

## Goal

When this workstream is done, atproto-clj can do everything `@atproto/crypto` can do on the JVM: generate/import/export ECDSA keypairs on both atproto curves (P-256/"ES256" and secp256k1/"ES256K"), produce 64-byte compact low-S signatures over the SHA-256 of a message, verify signatures with the atproto malleability rules (reject high-S and DER-encoded signatures by default), and encode/decode `did:key` strings with multicodec + base58btc multibase. It also adds JWT/JWS *verification* (the SDK is currently sign-only) and fixes latent bugs in `atproto.runtime.crypto`. The new `atproto.crypto` namespace is the frozen foundation contract that WS-04, WS-06, and WS-08 code against.

Authoritative spec: <https://atproto.com/specs/cryptography>.

## Current state

All paths relative to the repo root unless absolute.

- `src/atproto/runtime/crypto.cljc` — contains ONLY `now` (lines 11-14), `random-bytes` (16-21), `sha256` (23-27), `base64-encode`/`base64-decode` (29-35), `base64url-encode` (37-40), `generate-pkce` (42-48), `generate-nonce` (50-53). JVM-only bodies inside `#?(:clj ...)` reader conditionals; no cljs branches.
  - **Bug 1**: `sha256` (lines 23-27) is `^String`-hinted and only accepts strings (`(.getBytes ^String s StandardCharsets/UTF_8)`). Everything in atproto signs *bytes* (DAG-CBOR commit bytes, JWT signing input). Must accept byte arrays (and keep accepting strings for the existing caller at `src/atproto/oauth/client/dpop.cljc:28-30`, which hashes the access-token string).
  - **Bug 2**: `base64-encode` (lines 29-31) is hinted `^String` but `java.util.Base64$Encoder.encodeToString` takes `byte[]`. The hint never matches, so the call silently falls back to reflection; it works (slowly) when passed a byte array (the actual usage — see `src/atproto/data/json.cljc:69`) and silently returns `nil` when passed a string (the `catch Exception _` swallows it). Re-hint as `^bytes` and remove the try/catch. Per `00-overview.md` §4.9 item 4, the encode output must also become **unpadded** (atproto `$bytes` requirement; WS-02 owns the base64 semantics — whichever of WS-02/WS-03 lands second rebases over the other's `base64-encode` change).
  - Missing: `base64url-decode`, hex encode/decode (needed for key import/export and JWS verification).
- `src/atproto/runtime/jwt.cljc` — Nimbus-JOSE-backed, **sign-only**: `jwks->clj`/`clj->jwks`/`jwk->clj`/`clj->jwk` (lines 13-18), `public-jwks` (20-25), `query-jwks` (27-48), `generate-jwk` (50-65, EC/RSA/OKP key generation), `public-jwk` (67-72), `jwk-kid` (74-77), `generate` (79-103, compact JWS signing via `DefaultJWSSignerFactory`). There is **no parse and no verify of any kind**, and no ES256K support (Nimbus on JDK 15+ has no secp256k1 in the default JCA provider).
- **No secp256k1 anywhere. No did:key anywhere. No signature verification anywhere.**
- `deps.edn` — already has `mvxcvi/multiformats {:mvn/version "1.0.125"}` (used by `src/atproto/data.cljc:9-10` for CID/multihash). Its `multiformats.base` namespace provides multibase `format`/`parse` including `:base58btc` with the `z` prefix — exactly what did:key needs. `deps.edn` also has `com.google.crypto.tink/tink {:mvn/version "1.7.0"}` which is **referenced nowhere** in the codebase (verified by grep) and does not support secp256k1 anyway. There is **no BouncyCastle dependency yet**.
- Interop fixtures are **already vendored** at `test/interop-test-files/crypto/` (`signature-fixtures.json`, `w3c_didkey_K256.json`, `w3c_didkey_P256.json`) and are byte-identical to `/Users/luke/github/bluesky-social/atproto/interop-test-files/crypto/` (verified with `diff -r`). No tests use them yet.
- Conventions to follow (read these before writing code):
  - `src/atproto/runtime/interceptor.cljc` — `platform-async` (lines 129-155) and `execute` (157-169): public async fns take `& {:as opts}` with `:callback`/`:promise`/`:channel` adapters. Key operations follow this **async** convention so the contract holds when a cljs backend uses WebCrypto (Promise-based); pure parsing/encoding helpers stay synchronous (see Interface contract).
  - Error maps: `{:error "PascalCaseName" :message "..."}` — e.g. `src/atproto/identity.cljc:74` (`{:error "DidNotFound"}`) and `:112` (`{:error "NotImplemented"}`).
  - `clojure.spec.alpha` for input validation (see `::did` spec in `src/atproto/identity.cljc:36-41`).
  - `.cljc` files with `#?(:clj ...)` platform branches; `(set! *warn-on-reflection* true)` at top.
  - Test fixture loading convention: `(slurp (io/resource (str "interop-test-files/" path)))` — see `test/atproto/lexicon_test.cljc:21`.

## Reference implementation guide

Primary package: `/Users/luke/github/bluesky-social/atproto/packages/crypto` (read its `README.md` — it summarizes the curve/encoding rules).

| Concern | Reference file | Key items |
|---|---|---|
| Constants | `packages/crypto/src/const.ts` (whole file, lines 1-7) | `P256_DID_PREFIX = [0x80, 0x24]` (varint of multicodec `0x1200`), `SECP256K1_DID_PREFIX = [0xe7, 0x01]` (varint of `0xe7`), `BASE58_MULTIBASE_PREFIX = 'z'`, `DID_KEY_PREFIX = 'did:key:'`, `P256_JWT_ALG = 'ES256'`, `SECP256K1_JWT_ALG = 'ES256K'` |
| did:key encode/decode | `packages/crypto/src/did.ts` — `parseMultikey` (line 11), `formatMultikey` (26), `parseDidKey` (43), `formatDidKey` (48) | format = `'did:key:' + 'z' + base58btc(prefix ++ compressed-pubkey)`; parse strips prefix, matches multicodec prefix, **decompresses** the key (returns 65-byte uncompressed) |
| Generic verify dispatch | `packages/crypto/src/verify.ts` — `verifySignature` (line 6), `verifySignatureUtf8` (25) | dispatches on the did:key multicodec prefix; optional `jwtAlg` assertion; opts `{allowMalleableSig}` |
| P-256 ops | `packages/crypto/src/p256/operations.ts` — `verifyDidSig` (line 8), `verifySig` (22-33) | `sha256(data)` then `p256.verify(sig, msgHash, publicKey, {format: allowMalleable ? undefined : 'compact', lowS: !allowMalleable})` — i.e. **strict mode requires a 64-byte compact sig AND low-S; malleable mode accepts DER or compact and high-S** |
| K-256 ops | `packages/crypto/src/secp256k1/operations.ts` — same shape, `verifySig` at lines 22-33 | identical semantics on secp256k1 |
| Keypairs | `packages/crypto/src/p256/keypair.ts` and `packages/crypto/src/secp256k1/keypair.ts` — `create` (line 27), `import` (35, raw bytes or hex string), `publicKeyBytes` (45), `sign` (57-61), `export` (64) | `sign` = sha256 the message, ECDSA-sign the digest with `{lowS: true}`, return `toCompactRawBytes()` (64-byte R‖S). `export` throws unless created with `exportable: true`. Note: noble's `getPublicKey` returns the **compressed** 33-byte key, so `publicKeyBytes()`/`did()` work from the compressed form |
| Pubkey compression | `packages/crypto/src/p256/encoding.ts`, `packages/crypto/src/secp256k1/encoding.ts` | `compressPubkey` accepts either form (point decode), returns 33 bytes; `decompressPubkey` requires exactly 33 bytes, returns 65 bytes |
| Multibase helpers | `packages/crypto/src/multibase.ts` | `multibaseToBytes` / `bytesToMultibase` for prefixes `f F b B z m u U` — `mvxcvi/multiformats`'s `multiformats.base/parse`/`format` covers these |
| Plugin/type shapes | `packages/crypto/src/types.ts` (Keypair interface line 10, `DidKeyPlugin` 16, `VerifyOptions` 30), `packages/crypto/src/plugins.ts` | the "keypair protocol" to mirror |
| sha256/random | `packages/crypto/src/sha.ts`, `packages/crypto/src/random.ts` | `sha256` accepts bytes **or** utf-8 string — the model for our `sha256` fix |
| JWT (service auth) consumer | `packages/xrpc-server/src/auth.ts` — `createServiceJwt` (line 28), `verifyJwt` (72), `cryptoVerifySignatureWithKey` (178-187) | JWS signing input is ASCII `b64url(header) + "." + b64url(payload)`; ES256/ES256K JWS signature **is** the 64-byte compact sig, base64url'd. NOTE: service-JWT verification passes `allowMalleableSig: true` (line 186) — JWTs are verified leniently; repo commits are verified strictly |
| Downstream did:key consumers (for contract shape) | `packages/repo/src/util.ts:94` (`verifyCommitSig`), `packages/identity/src/did/atproto-data.ts:26` (`getDidKeyFromMultibase`), `packages/common-web/src/did-doc.ts:33-64` (`getSigningKey`/`getSigningDidKey`) | what WS-04/WS-06/WS-08 will build on top of this contract |

Interop test fixtures (exact paths, all three already vendored in this repo — see Test plan):

- `/Users/luke/github/bluesky-social/atproto/interop-test-files/crypto/signature-fixtures.json` — 6 vectors: 2 valid low-S (P-256 + K-256), 2 high-S tagged `["high-s"]` with `validSignature: false`, 2 DER-encoded tagged `["der-encoded"]` with `validSignature: false`. Fields: `messageBase64`, `algorithm` (`ES256`/`ES256K`), `publicKeyDid`, `publicKeyMultibase` (z-base58btc of the **compressed key without multicodec prefix**), `signatureBase64`, `validSignature`, `tags`. (Identical copy also at `packages/crypto/tests/signature-fixtures.json`.)
- `/Users/luke/github/bluesky-social/atproto/interop-test-files/crypto/w3c_didkey_K256.json` — 5 vectors of `privateKeyBytesHex` → `publicDidKey`.
- `/Users/luke/github/bluesky-social/atproto/interop-test-files/crypto/w3c_didkey_P256.json` — 1 vector of `privateKeyBytesBase58` → `publicDidKey`.
- Reference test suites worth mirroring: `packages/crypto/tests/signatures.test.ts` (fixture verification incl. the "verifies with explicit malleable option" cases), `did.test.ts` (W3C vectors, format/parse round-trip), `key-compression.test.ts` (33↔65-byte round-trips, 100 random keys), `keypairs.test.ts` (export/import preserves DID; sign/verify round-trip).

### Algorithm notes (what "parity" means precisely)

1. **Signing**: `sig = ECDSA(curve, privkey, SHA-256(msg))` with deterministic nonce (RFC 6979 — what noble does) and **low-S normalization**: if `s > n/2`, replace `s` with `n - s`. Output is 64 bytes: 32-byte big-endian `r` ‖ 32-byte big-endian `s`.
2. **Strict verification** (default): signature MUST be exactly 64 bytes (reject DER), `s` MUST be low (reject high-S), then standard ECDSA verify against `SHA-256(msg)`.
3. **Malleable verification** (`:allow-malleable? true`): accept DER **or** 64-byte compact, do not enforce low-S.
4. **did:key**: `"did:key:z" + base58btc(multicodec-prefix ++ compressed-33-byte-pubkey)`. Prefixes: P-256 → `0x80 0x24`; secp256k1 → `0xe7 0x01`. Parsing returns the **uncompressed** (65-byte, `0x04`-prefixed) key, matching `parseDidKey`.
5. **JCA caveat**: the JDK removed secp256k1 from SunEC (disabled in JDK 15, gone since). BouncyCastle is required on the JVM — see Risks for the recommendation (BC lightweight API, not provider registration).

## Scope

### In scope

- New namespace `src/atproto/crypto.cljc` (JVM implementation; cljs branches absent or throwing `{:error "NotImplemented"}`-style as appropriate):
  - Keypair protocol: generate, import (raw 32-byte scalar bytes or hex string), export raw private key (gated by an `:exportable?` flag, matching TS), export/import as EC JWK maps.
  - `sign` for both ES256 and ES256K: 64-byte compact, low-S, deterministic (RFC 6979).
  - `verify` for both curves with strict-by-default malleability rules and `:allow-malleable?` escape hatch.
  - Compressed (33-byte) ↔ uncompressed (65-byte) public key conversion, with on-curve validation.
  - `did:key` encode/decode (`pubkey->did-key`, `did-key->pubkey`) plus multikey helpers (`format-multikey`, `parse-multikey`) and thin multibase wrappers over `multiformats.base`.
  - `verify-did-sig` convenience (verify against a did:key string, dispatching on its prefix) — the shape WS-04 needs for commit sigs.
  - clojure.spec defs for `::alg`, `::did-key`, key/sig byte shapes.
- `src/atproto/runtime/crypto.cljc` fixes: `sha256` accepts bytes *and* strings; fix `base64-encode` type hint; add `base64url-decode`, `sha256-hex`, `hex-encode`/`hex-decode`.
- `src/atproto/runtime/jwt.cljc` additions: compact JWS/JWT `parse` (no verification), `verify` (signature + temporal claims) supporting ES256/ES256K via `atproto.crypto` (raw R‖S JWS sigs per RFC 7518 §3.4 / RFC 8812 §3.2), and `sign` with an `atproto.crypto` keypair (mirrors `createServiceJwt`'s encoding). The existing Nimbus-based `generate` & JWK utilities stay untouched for the OAuth/DPoP path.
- `deps.edn`: add BouncyCastle (`org.bouncycastle/bcprov-jdk18on`).
- Tests: new unit + interop-fixture test namespaces (fixtures are already vendored; write the tests).
- Freezing the interface contract below for WS-04/WS-06/WS-08.

### Out of scope

- **DID document parsing / signing-key extraction** (`getSigningKey`, `getDidKeyFromMultibase`, legacy `EcdsaSecp256k1VerificationKey2019` vs `Multikey` verificationMethod handling) — **WS-06 (identity)** owns that, building on `parse-multikey`/`pubkey->did-key` from this contract.
- **Repo commit signature verification** (`verifyCommitSig` — CBOR-encode the unsigned commit, verify) — **WS-04 (repo/MST)** owns it; this workstream only supplies `verify-did-sig`.
- **Service-auth JWT policy** (aud/lxm/iss checks, `typ` denylist `at+jwt`/`refresh+jwt`/`dpop+jwt`, signing-key refresh-and-retry — `packages/xrpc-server/src/auth.ts:72-176`) — **WS-08** owns it; this workstream supplies `jwt/parse`, `jwt/verify`, `jwt/sign` primitives with hooks for those checks.
- **ClojureScript crypto** — stretch goal owned by/coordinated with **WS-10** *after* this lands. WebCrypto has no secp256k1, so cljs would need a JS dep such as `@noble/curves` for secp256k1, while P-256 can use WebCrypto (async; enables non-extractable browser keys). The key-operation API is async precisely so either backend fits. Document the seam; do not implement.
- DPoP / OAuth changes (`src/atproto/oauth/client/dpop.cljc`, `src/atproto/oauth/client.cljc`) — untouched; the `sha256` fix is backward compatible with the string call at `dpop.cljc:28-30`.
- RSA/OKP signing & verification beyond what Nimbus already provides — not needed for atproto parity.
- Removing the unused Tink dependency — see Open questions; do it only if the orchestrator approves (trivial separate commit).

## Deliverables

### 1. `src/atproto/runtime/crypto.cljc` (modified)

```clojure
(defn sha256
  "SHA-256 digest of the input as a byte array.
  Accepts a byte array, or a string which is UTF-8 encoded first."
  [bytes-or-str]
  ...)

(defn sha256-hex
  "Lowercase hex string of (sha256 bytes-or-str)."
  [bytes-or-str] ...)

(defn base64-encode
  "Standard base64 (RFC 4648 §4) string of the byte array, WITHOUT padding
  (required by the atproto $bytes form — 00-overview §4.9 item 4; WS-02 owns
  these semantics). base64-decode accepts both padded and unpadded input."
  [^bytes b] ...)            ;; fixed hint; same name/arity as today

(defn base64url-decode
  "Decode a base64url string (padding optional) to bytes, or nil if invalid."
  [s] ...)

(defn hex-encode  "Lowercase hex string of bytes." [^bytes b] ...)
(defn hex-decode  "Bytes from a hex string, or nil if invalid." [s] ...)
```

`now`, `random-bytes`, `base64-decode`, `base64url-encode`, `generate-pkce`, `generate-nonce` are unchanged.

### 2. `src/atproto/crypto.cljc` (new)

```clojure
(ns atproto.crypto
  "ECDSA keypairs, signatures, and did:key encoding for atproto.

  Two curves are supported, identified by their JWT `alg` string:
    \"ES256\"  — NIST P-256 / secp256r1 / prime256v1
    \"ES256K\" — secp256k1 / K-256

  Signatures are 64-byte compact R||S over the SHA-256 of the message,
  with low-S normalization. Verification rejects high-S and DER-encoded
  signatures unless :allow-malleable? is set.

  See https://atproto.com/specs/cryptography"
  (:require [clojure.spec.alpha :as s]
            [multiformats.base :as mb]
            [atproto.runtime.crypto :as runtime.crypto])
  #?(:clj (:import [org.bouncycastle.crypto.params ...] ...)))

(def p256-jwt-alg "ES256")
(def k256-jwt-alg "ES256K")

(s/def ::alg #{"ES256" "ES256K"})

;; -----------------------------------------------------------------------------
;; Keypairs
;; -----------------------------------------------------------------------------

(defprotocol Keypair
  (alg [kp]
    "The JWT algorithm string for this keypair: \"ES256\" or \"ES256K\".")
  (public-key [kp]
    "Compressed public key bytes (33 bytes, 0x02/0x03-prefixed).")
  (did [kp]
    "The did:key string for this keypair's public key.")
  (sign [kp msg-bytes]
    "ECDSA signature of (sha256 msg-bytes): 64-byte compact low-S R||S.")
  (export [kp]
    "Raw 32-byte private key, or {:error \"PrivateKeyNotExportable\"}
     if the keypair was not created with :exportable? true."))

(defn generate
  "Generate a new keypair on the given curve.

  opts:
    :exportable?  allow `export` of the private key (default false)

  Returns a Keypair, or {:error \"UnsupportedAlgorithm\" :message ...}."
  [alg & {:keys [exportable?] :as opts}] ...)

(defn import-private-key
  "Build a keypair from a raw 32-byte private scalar.
  `priv` may be a byte array or a hex string (TS parity).
  Returns a Keypair or {:error \"InvalidPrivateKey\" :message ...}."
  [alg priv & {:keys [exportable?] :as opts}] ...)

(defn keypair->jwk
  "Private EC JWK map for the keypair:
  {:kty \"EC\" :crv \"P-256\"|\"secp256k1\" :x .. :y .. :d ..} (base64url, RFC 7518/8812).
  Returns {:error \"PrivateKeyNotExportable\"} unless exportable."
  [kp] ...)

(defn jwk->keypair
  "Keypair from a private EC JWK map (inverse of keypair->jwk).
  Returns {:error \"InvalidJwk\" :message ...} on bad input."
  [jwk & {:keys [exportable?]}] ...)

;; -----------------------------------------------------------------------------
;; Verification (FROZEN CONTRACT)
;; -----------------------------------------------------------------------------

(defn verify
  "Verify an ECDSA signature over (sha256 msg-bytes).

  pubkey-bytes  compressed (33) or uncompressed (65) public key
  sig-bytes     signature bytes
  opts          {:alg \"ES256\"|\"ES256K\"   ;; required
                 :allow-malleable? false}    ;; default false

  Default (strict, atproto rules): sig must be exactly 64 bytes compact
  and low-S. With :allow-malleable? true: DER or compact accepted, high-S
  accepted (TS parity: noble `format: undefined, lowS: false`).

  Returns true/false. Malformed signatures return false; an unsupported
  :alg or an invalid public key returns {:error ...} (programmer error,
  distinct from signature invalidity)."
  [pubkey-bytes sig-bytes msg-bytes {:keys [alg allow-malleable?]}] ...)

(defn verify-did-sig
  "Verify a signature against the public key embedded in a did:key string,
  dispatching on its multicodec prefix (TS verifySignature).
  opts: {:alg ..        ;; optional assertion; error if it mismatches the did
         :allow-malleable? false}
  Returns true/false, or {:error ...} for malformed/unsupported did:key."
  [did-key sig-bytes msg-bytes & {:as opts}] ...)

;; -----------------------------------------------------------------------------
;; Public key encoding & did:key (FROZEN CONTRACT)
;; -----------------------------------------------------------------------------

(defn compress-pubkey
  "33-byte compressed form of a public key (accepts 33 or 65 byte input).
  Returns {:error \"InvalidPublicKey\"} if not a valid curve point."
  [alg pubkey-bytes] ...)

(defn decompress-pubkey
  "65-byte uncompressed form of a 33-byte compressed public key.
  Returns {:error \"InvalidPublicKey\"} on bad length or invalid point."
  [alg pubkey-bytes] ...)

(defn pubkey->did-key
  "did:key string for the public key (compressed or uncompressed input):
  \"did:key:z\" + base58btc(multicodec-prefix ++ compressed-pubkey).
  Returns {:error \"UnsupportedAlgorithm\"|\"InvalidPublicKey\" ...} on bad input."
  [alg pubkey-bytes] ...)

(defn did-key->pubkey
  "Parse a did:key string.
  Returns {:alg \"ES256\"|\"ES256K\" :bytes uncompressed-65-byte-pubkey}
  or {:error \"InvalidDidKey\"|\"UnsupportedKeyType\" :message ...}."
  [did] ...)

(defn format-multikey
  "Multikey string (\"z...\") for the public key — did:key without the
  \"did:key:\" prefix (TS formatMultikey)."
  [alg pubkey-bytes] ...)

(defn parse-multikey
  "Parse a multikey string (\"z...\") → {:alg .. :bytes uncompressed}
  or {:error ...} (TS parseMultikey). WS-06 uses this for DID-doc
  verificationMethod entries of type Multikey."
  [multikey] ...)

;; Thin wrappers over multiformats.base for TS multibase.ts parity:
(defn multibase->bytes [s] ...)          ;; multiformats.base/parse
(defn bytes->multibase [base-key b] ...) ;; multiformats.base/format, e.g. :base58btc
```

Implementation notes (JVM):

- Use the **BouncyCastle lightweight API** (no `Security/addProvider` global mutation): curve params via `org.bouncycastle.crypto.ec.CustomNamedCurves/getByName` (`"secp256k1"`, `"secp256r1"`); signing via `org.bouncycastle.crypto.signers.ECDSASigner` constructed with `HMacDSAKCalculator.` of a `SHA256Digest` (RFC 6979 deterministic nonces — parity with noble); verification via the same signer; point compression via `ECPoint.getEncoded(true/false)`; point parsing/validation via `ECCurve.decodePoint` (throws on off-curve → map to `{:error "InvalidPublicKey"}`).
- Low-S: after signing, if `s > n/2` then `s ← n - s`. On strict verify, reject when `s > n/2`.
- Compact encoding: fixed-width 32-byte big-endian `r` then `s` (mind `BigInteger/toByteArray` sign-byte stripping/padding).
- DER handling for malleable verify: try `org.bouncycastle.asn1.ASN1Sequence` parse of two INTEGERs; if that fails and length is 64, parse as compact.
- Multicodec prefixes are hardcoded 2-byte constants (as in `const.ts`); do not compute varints at runtime.
- Keypair: a `deftype`/`defrecord` holding `alg`, private `BigInteger` d, compressed pubkey bytes, `exportable?` flag, implementing the protocol. Cache the did:key string.

### 3. `src/atproto/runtime/jwt.cljc` (modified — additions only)

```clojure
(defn parse
  "Split and decode a compact JWS/JWT WITHOUT verifying it.
  Returns {:header {...}          ;; keywordized
           :claims {...}          ;; keywordized (payload)
           :signing-input bytes   ;; ASCII bytes of \"<b64h>.<b64p>\"
           :signature bytes}      ;; base64url-decoded
  or {:error \"MalformedJwt\" :message ...}."
  [jwt-str] ...)

(defn verify
  "Verify a compact JWS/JWT's signature and temporal claims.

  `key` is one of:
    - a did:key string                       (ES256/ES256K via atproto.crypto)
    - {:pubkey {:alg .. :bytes ..}}          (ES256/ES256K raw key)
    - {:jwk {...}}                           (delegates to Nimbus for other algs)

  opts:
    :now              epoch seconds (default (crypto/now)) — for tests
    :leeway           seconds of clock skew tolerance (default 0)
    :allow-malleable? for ES256/ES256K sig checks (default true — matches
                      packages/xrpc-server/src/auth.ts:178-187; pass false
                      for strict atproto signature rules)

  Checks: signature over the signing input; :exp (expired → error);
  :nbf/:iat when present. Does NOT check aud/iss/lxm/typ — callers
  (WS-08 service auth) layer policy on the returned claims.

  Returns {:header {...} :claims {...}}
  or {:error \"MalformedJwt\"|\"BadJwtSignature\"|\"JwtExpired\"|\"UnsupportedAlgorithm\"
      :message ...}."
  [jwt-str key & {:as opts}] ...)

(defn sign
  "Compact JWS signed with an atproto.crypto Keypair (ES256/ES256K).
  Encoding mirrors packages/xrpc-server/src/auth.ts createServiceJwt:
  base64url(JSON header) \".\" base64url(JSON claims) \".\" base64url(64-byte sig).
  The :alg header is set from (crypto/alg keypair)."
  [keypair headers claims] ...)
```

JSON via `atproto.runtime.json/read-str`/`write-str` (charred); base64url via `atproto.runtime.crypto`. The ES256/ES256K JWS signature is exactly the 64-byte compact sig (RFC 7518 §3.4, RFC 8812 §3.2), so `jwt/sign`/`jwt/verify` are thin layers over `atproto.crypto/sign`/`verify`.

### 4. `deps.edn` (modified)

```clojure
org.bouncycastle/bcprov-jdk18on {:mvn/version "1.80"} ;; or latest at implementation time
```

## Interface contract

**Frozen for WS-04 / WS-06 / WS-08** (they may code against these signatures before this workstream merges, stubbing with the vendored fixtures):

```clojure
;; atproto.crypto — KEY OPERATIONS ARE ASYNC (SDK convention: & {:as opts} with
;; :callback/:promise/:channel adapters via atproto.runtime.interceptor/platform-async),
;; so a cljs backend can use WebCrypto (Promise-based). Parsing/encoding is sync.
(sign keypair msg-bytes & opts)                       ;; async => 64-byte compact low-S sig bytes
(verify pubkey-bytes sig-bytes msg-bytes
        & {:keys [alg allow-malleable?] :as opts})    ;; async => boolean
(verify-did-sig did-key sig-bytes msg-bytes & opts)   ;; async => boolean
(generate alg & {:keys [exportable?] :as opts})       ;; async => Keypair | {:error ...}
(import-private-key alg priv & opts)                  ;; async => Keypair | {:error ...}
(did-key->pubkey did)                                 ;; sync  => {:alg "ES256"|"ES256K"
                                                      ;;     :bytes uncompressed-65-byte-pubkey}
                                                      ;;    | {:error ...}
(pubkey->did-key alg pubkey-bytes)                    ;; sync  => "did:key:z..." | {:error ...}
(parse-multikey multikey-str)                         ;; sync  => {:alg .. :bytes ..} | {:error ...}
(alg keypair)                                         ;; sync  => "ES256" | "ES256K"
(public-key keypair)                                  ;; sync  => 33-byte compressed pubkey bytes
                                                      ;;    (public bytes are captured at key
                                                      ;;     creation, so accessors stay sync
                                                      ;;     even over WebCrypto handles)
(did keypair)                                         ;; sync  => "did:key:z..."

;; atproto.runtime.jwt
(parse jwt-str)                                       ;; sync  => {:header :claims :signing-input :signature} | {:error ...}
(verify jwt-str key & opts)                           ;; async => {:header :claims} | {:error ...}
(sign keypair headers claims & opts)                  ;; async => compact JWS string

;; atproto.runtime.crypto
(sha256 bytes-or-str)                                 ;; sync => digest bytes (NOW ACCEPTS BYTES)
                                                      ;; sha256 stays SYNC permanently: it is used
                                                      ;; in tight loops (MST key depth, CID hashing);
                                                      ;; cljs uses goog.crypt/noble, never WebCrypto.
```

Key operations (`sign`, `verify`, `verify-did-sig`, `generate`, `import-private-key`, `jwt/verify`, `jwt/sign`) are **async** per the SDK callback convention; on the JVM they compute synchronously and adapt via `platform-async`. Everything else is synchronous pure computation. Error returns are `{:error "Name" :message "..."}` maps, never thrown, except `verify` yielding plain `false` for invalid-but-well-formed-input signatures.

This workstream consumes no other workstream's contract. It does consume `mvxcvi/multiformats`'s `multiformats.base/format`/`parse` (`:base58btc`, `z` prefix), already a dependency.

Consumer expectations to honor (verified in the reference impl):

- WS-04 verifies repo commit sigs **strictly** (`packages/repo/src/util.ts:94-101` passes no malleable option).
- WS-08 verifies service JWTs **leniently** (`packages/xrpc-server/src/auth.ts:178-187`, `allowMalleableSig: true`); `jwt/verify` defaults accordingly.
- WS-06 turns DID-doc `publicKeyMultibase` values into did:key strings: legacy types (`EcdsaSecp256k1VerificationKey2019`, `EcdsaSecp256r1VerificationKey2019`) are plain multibase of the compressed key → `(pubkey->did-key alg (multibase->bytes s))`; `Multikey` type → `(parse-multikey s)` then `pubkey->did-key` (see `packages/identity/src/did/atproto-data.ts:26-41`).

## File ownership

Created or modified by WS-03. WS-03 owns these files, with the coordination carve-outs from the 00-overview conflict matrix noted per row (it is *not* absolute exclusivity):

| File | Action |
|---|---|
| `src/atproto/crypto.cljc` | create |
| `src/atproto/runtime/crypto.cljc` | modify (sha256 bytes fix, base64 hint fix, new helpers). **Shared with WS-02**, which owns the base64 *semantics* (unpadded encode): WS-02 and WS-03 may land in either order, whoever lands second rebases (overlap is only `base64-encode`). WS-06 drops its sha256 change (WS-03 covers it); WS-08 drops its duplicate `base64url-decode`/hex helpers; WS-10 never touches it. |
| `src/atproto/runtime/jwt.cljc` | modify (add parse/verify/sign; existing fns untouched). **WS-11A extends `verify`** (keyset/embedded-JWK modes) on top *after* WS-03 lands, rebasing; WS-08/WS-10 never touch it. |
| `test/atproto/crypto_test.cljc` | create |
| `test/atproto/runtime/crypto_test.cljc` | create |
| `test/atproto/runtime/jwt_test.cljc` | create |
| `deps.edn` | modify (add BouncyCastle — **shared file**: additive one-line change; whoever lands second rebases trivially) |

Already present, used read-only: `test/interop-test-files/crypto/*.json` (verified byte-identical to the reference repo; re-`diff` at implementation time and refresh if upstream changed).

Explicit coordination notes: **WS-10 (cljs) must NOT touch `src/atproto/runtime/crypto.cljc`, `src/atproto/runtime/jwt.cljc`, or `src/atproto/crypto.cljc`** — cljs crypto (WebCrypto for P-256, `@noble/curves` for secp256k1) is a stretch goal scheduled after WS-03 lands. `src/atproto/oauth/client/dpop.cljc` and `src/atproto/data/json.cljc` call into `runtime/crypto` but are not modified here; the changes are backward compatible (verify by running their existing tests).

## Test plan

Fixture loading follows `test/atproto/lexicon_test.cljc:21`: `(slurp (io/resource "interop-test-files/crypto/signature-fixtures.json"))` + `atproto.runtime.json/read-str`. Note the fixture base64 fields are *standard* base64 **without padding** (e.g. `"oWVoZWxsb2V3b3JsZA"`); `java.util.Base64/getDecoder` accepts absent padding, but add a helper in the test ns rather than relying on `runtime.crypto/base64-decode`'s silent-nil behavior.

### Unit tests — `test/atproto/runtime/crypto_test.cljc`

- `sha256` of byte array == `sha256` of the equivalent UTF-8 string; known test vector (e.g. sha256 of `"abc"`).
- `base64-encode` round-trips bytes (and no longer relies on reflection — build with `*warn-on-reflection*` clean).
- `base64url-decode`/`base64url-encode` round-trip, with and without padding; `hex-encode`/`hex-decode` round-trip.

### Unit tests — `test/atproto/crypto_test.cljc`

- **Keypairs** (mirrors `packages/crypto/tests/keypairs.test.ts`): generate → export → import preserves `did`; `export` on a non-exportable keypair returns `{:error "PrivateKeyNotExportable"}`; `keypair->jwk` → `jwk->keypair` round-trips; sign → `verify` true for both curves; mutated sig/message → false.
- **Compression** (mirrors `key-compression.test.ts`): compress → 33 bytes; decompress → 65 bytes == original; round-trip over ~100 random keys per curve (use `test.check`, already a test dep).
- **did:key W3C vectors** (mirrors `did.test.ts`): for each entry in `interop-test-files/crypto/w3c_didkey_K256.json`, `(did (import-private-key "ES256K" privateKeyBytesHex))` == `publicDidKey`; same for `w3c_didkey_P256.json` (private key is base58btc — decode with `multiformats.base/parse*` or `:base58btc`); `pubkey->did-key` ∘ `did-key->pubkey` round-trips (input compressed, output uncompressed).
- **Signature interop vectors** (mirrors `signatures.test.ts`, the core parity test): for each of the 6 vectors in `interop-test-files/crypto/signature-fixtures.json`:
  - `(multibase->bytes publicKeyMultibase)` == compressed form of `(:bytes (did-key->pubkey publicKeyDid))`;
  - strict `verify` == `validSignature` (i.e. the 2 valid vectors verify; the 2 `high-s` and 2 `der-encoded` vectors return **false**);
  - with `:allow-malleable? true`, all `high-s` and `der-encoded` vectors verify **true**.
- **Negative/edge cases**: wrong `:alg` for the key → false (cross-curve confusion); 63/65-byte sigs → false; `did-key->pubkey` on `did:key:z6Mk...` (ed25519, prefix `0xed 0x01`) → `{:error "UnsupportedKeyType"}`; non-`z` multibase → `{:error "InvalidDidKey"}`; off-curve pubkey bytes → `{:error "InvalidPublicKey"}`.
- **Low-S property**: for many random keys/messages, `s` of every produced sig ≤ n/2, and strict verify accepts own sigs (determinism: signing twice yields identical bytes, RFC 6979).

### Unit tests — `test/atproto/runtime/jwt_test.cljc`

- `sign` (ES256K keypair) → `verify` with `{:pubkey ...}` and with the did:key string → claims round-trip.
- `verify` of an **ES256** token produced by the existing Nimbus `generate` (cross-implementation check within the SDK).
- Expired token → `{:error "JwtExpired"}` (use `:now` opt); tampered payload → `{:error "BadJwtSignature"}`; garbage → `{:error "MalformedJwt"}`.

### Integration / live verification (optional, manual or env-gated)

- Resolve a known DID via `https://plc.directory/<did>` (machinery exists in `atproto.identity/resolve-did`), extract `verificationMethod[0].publicKeyMultibase`, and confirm `did-key->pubkey`/`pubkey->did-key` round-trips it. Full commit-sig verification against a live PDS belongs to WS-04.
- Cross-implementation spot check during development: sign a message with `atproto.crypto`, verify with `@atproto/crypto` in a node REPL in the reference repo (and vice versa). Not CI.

CI: `clojure -X:test` must stay green at every milestone.

## Acceptance criteria

- [ ] `atproto.crypto/generate`, `import-private-key`, `export`, `keypair->jwk`/`jwk->keypair` work for both `"ES256"` and `"ES256K"` on the JVM.
- [ ] `sign` produces 64-byte compact, low-S, RFC 6979-deterministic signatures for both curves; own sigs verify strictly.
- [ ] All 6 vectors of `test/interop-test-files/crypto/signature-fixtures.json` pass: strict `verify` matches `validSignature` exactly, and `high-s`/`der-encoded` vectors verify `true` with `:allow-malleable? true`.
- [ ] All 5 `w3c_didkey_K256.json` and 1 `w3c_didkey_P256.json` vectors derive the expected `did:key` from the private key.
- [ ] `did-key->pubkey` returns uncompressed 65-byte keys with the correct `:alg`; unsupported prefixes (e.g. ed25519) yield `{:error "UnsupportedKeyType"}`.
- [ ] `atproto.runtime.crypto/sha256` accepts byte arrays and strings; `base64-encode` is correctly hinted and emits **unpadded** output (`base64-decode` accepts padded and unpadded — 00-overview §4.9 item 4); OAuth/DPoP and `atproto.data.json` test suites still pass.
- [ ] `atproto.runtime.jwt/parse`/`verify`/`sign` round-trip ES256 and ES256K compact JWTs, including a Nimbus-`generate`d ES256 token verified by the new `verify`; temporal claim failures produce the documented error maps.
- [ ] No reflection warnings in the touched namespaces (`*warn-on-reflection*` is set).
- [ ] Frozen contract functions exist with the exact signatures in "Interface contract".
- [ ] `clojure -X:test` green; no files outside the "File ownership" table changed.

## Milestones

1. **PR 1 — runtime crypto fixes** (`src/atproto/runtime/crypto.cljc`, `test/atproto/runtime/crypto_test.cljc`): sha256 bytes+string, base64 hint fix, base64url-decode/hex helpers. No caller changes; existing suites green.
2. **PR 2 — curve primitives** (`deps.edn` + `src/atproto/crypto.cljc` part 1, `test/atproto/crypto_test.cljc` part 1): BouncyCastle dep; keypair protocol, generate/import/export/JWK, `sign`, `verify` with low-S/compact/DER rules for both curves; keypair + low-S property tests. (did:key not yet in.)
3. **PR 3 — did:key & multibase, contract freeze** (`src/atproto/crypto.cljc` part 2, tests part 2): compress/decompress, `pubkey->did-key`/`did-key->pubkey`/`format-multikey`/`parse-multikey`/`multibase->bytes`/`bytes->multibase`, `verify-did-sig`; W3C + signature-fixture interop tests. After this PR merges, the contract is frozen and WS-04/WS-06/WS-08 may build.
4. **PR 4 — JWS/JWT verification** (`src/atproto/runtime/jwt.cljc`, `test/atproto/runtime/jwt_test.cljc`): `parse`, `verify`, `sign`; cross-check against Nimbus `generate`; document WS-08 policy seam.

Each PR is independently mergeable and leaves `clojure -X:test` green.

## Risks & open questions

1. **JVM secp256k1 library choice** — *recommendation: BouncyCastle `bcprov-jdk18on`, lightweight API*. The JDK removed secp256k1 from SunEC (disabled JDK 15+), so JCA-only is not an option. Tink (already in deps, unused) deliberately excludes secp256k1. BC's lightweight API (`ECDSASigner` + `HMacDSAKCalculator`) gives RFC 6979 determinism matching noble, needs no global `Security/addProvider`, and exposes point compression directly. Downside: ~8 MB jar; acceptable for a JVM SDK. Alternative considered and rejected: registering BC as a JCA provider and driving Nimbus's `ECDSASigner` for ES256K — global provider mutation is hostile to host applications, and Nimbus's ES256K support on modern JDKs is version-sensitive.
2. **Hand-rolled JWS vs Nimbus for verification** — *recommendation: hand-roll* the compact JWS layer for ES256/ES256K on top of `atproto.crypto` (the JWS sig for these algs is exactly our 64-byte compact sig; encoding is 3 base64url segments). Keeps Nimbus untouched for the working OAuth/DPoP sign path and avoids the provider problem. Risk: JSON serialization differences are irrelevant for verification (we verify the received bytes) and for signing we control both ends.
3. **`jwt/verify` malleability default** — TS verifies service JWTs with `allowMalleableSig: true` (`packages/xrpc-server/src/auth.ts:186`). Recommendation: default `:allow-malleable? true` in `jwt/verify` (TS parity) while `atproto.crypto/verify` defaults strict. **WS-08 should confirm** this default when implementing service-auth policy.
4. **Async API (DECIDED 2026-06-10, supersedes the earlier sync proposal)** — key operations are async per the SDK callback convention so a future cljs backend can use WebCrypto (Promise-based; P-256; non-extractable keys) interchangeably with `@noble/curves` (secp256k1). JVM implementations compute synchronously and adapt via `platform-async`. Pure parsing/encoding and `sha256` stay sync. Consequence for consumers: WS-04 commit signing/verification, WS-06 PLC op signing, and WS-08 JWT mint/verify must treat these call sites as async.
5. **`did-key->pubkey` returns uncompressed bytes** — matches TS `parseDidKey` (downstream TS code, e.g. fixture tests, compares against decompressed keys). Consumers needing compressed form use `compress-pubkey`. Don't silently change this; it's part of the frozen contract.
6. **Unused Tink dependency** — `com.google.crypto.tink/tink {:mvn/version "1.7.0"}` in `deps.edn` is referenced nowhere. Recommendation: remove it in PR 2 (same `deps.edn` touch). **Open question for orchestrator**: confirm no other planned workstream intends to use Tink.
7. **Fixture drift** — vendored fixtures are byte-identical to the reference repo today (2026-06-10, verified by `diff -r`); re-verify at implementation time.
8. **BigInteger encoding pitfalls** — `BigInteger/toByteArray` adds sign bytes and strips leading zeros; the compact codec must left-pad to exactly 32 bytes and parse with the unsigned `(BigInteger. 1 bytes)` constructor. Cover with the W3C/interop vectors plus property tests (a randomized round-trip catches this class of bug quickly).
9. **cljs (informational, not committed)** — WebCrypto has no secp256k1; a cljs implementation needs `@noble/curves` (+ `@noble/hashes`). The `.cljc` layout should keep platform-neutral logic (compact codec, low-S math on bigints is platform-specific, did:key string handling, JWS assembly) outside `#?(:clj ...)` branches where practical to shrink that future diff. Owned by WS-10, after this lands.
