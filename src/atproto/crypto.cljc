(ns atproto.crypto
  "ECDSA keypairs, signatures, and did:key encoding for atproto.

  Two curves are supported, identified by their JWT `alg` string:
    \"ES256\"  — NIST P-256 / secp256r1 / prime256v1
    \"ES256K\" — secp256k1 / K-256

  Signatures are 64-byte compact R||S over the SHA-256 of the message,
  with low-S normalization. Verification rejects high-S and DER-encoded
  signatures unless :allow-malleable? is set.

  Key operations (generate, import-private-key, sign, verify,
  verify-did-sig) follow the SDK async convention: they take a trailing
  options map with :callback/:promise/:channel adapters so that a future
  ClojureScript backend can use the Promise-based WebCrypto API. On the
  JVM they compute synchronously and adapt. Parsing/encoding helpers are
  synchronous. Errors are {:error \"Name\" :message ...} maps, never
  thrown; verify yields plain false for well-formed-but-invalid
  signatures.

  See https://atproto.com/specs/cryptography"
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [multiformats.base :as mb]
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.bytes :as bytes]
            [atproto.runtime.crypto :as runtime.crypto])
  #?(:clj (:import [java.math BigInteger]
                   [java.security SecureRandom]
                   [java.util Arrays]
                   [org.bouncycastle.asn1 ASN1Integer ASN1Primitive ASN1Sequence]
                   [org.bouncycastle.crypto AsymmetricCipherKeyPair]
                   [org.bouncycastle.crypto.digests SHA256Digest]
                   [org.bouncycastle.crypto.ec CustomNamedCurves]
                   [org.bouncycastle.crypto.generators ECKeyPairGenerator]
                   [org.bouncycastle.crypto.params ECDomainParameters
                    ECKeyGenerationParameters ECPrivateKeyParameters
                    ECPublicKeyParameters]
                   [org.bouncycastle.crypto.signers ECDSASigner HMacDSAKCalculator]
                   [org.bouncycastle.math.ec ECPoint])))

#?(:clj (set! *warn-on-reflection* true))

(defn- async-opts
  "The async-adapter keys of an options map (see i/platform-async).
  Other keys must be stripped before requesting the platform default."
  [opts]
  (select-keys opts [:channel :callback :promise]))

(def p256-jwt-alg "ES256")
(def k256-jwt-alg "ES256K")

(def did-key-prefix "did:key:")

(s/def ::alg #{"ES256" "ES256K"})
(s/def ::did-key (s/and string? #(str/starts-with? % "did:key:z")))

#?(:clj (s/def ::compressed-pubkey (s/and bytes/bytes? #(= 33 (alength ^bytes %)))))
#?(:clj (s/def ::uncompressed-pubkey (s/and bytes/bytes? #(= 65 (alength ^bytes %)))))
#?(:clj (s/def ::compact-sig (s/and bytes/bytes? #(= 64 (alength ^bytes %)))))

;; -----------------------------------------------------------------------------
;; Curve parameters & byte helpers (JVM, BouncyCastle lightweight API)
;; -----------------------------------------------------------------------------

#?(:clj
   (def ^:private domains
     "JWT alg string -> BouncyCastle ECDomainParameters."
     (into {}
           (map (fn [[alg curve-name]]
                  (let [x9 (CustomNamedCurves/getByName curve-name)]
                    [alg (ECDomainParameters. (.getCurve x9) (.getG x9) (.getN x9) (.getH x9))])))
           {"ES256" "secp256r1"
            "ES256K" "secp256k1"})))

;; Multicodec varint prefixes (P-256 = 0x1200, secp256k1 = 0xe7), hardcoded
;; as in the reference implementation's const.ts.
#?(:clj
   (def ^:private did-prefixes
     {"ES256" (byte-array [(unchecked-byte 0x80) 0x24])
      "ES256K" (byte-array [(unchecked-byte 0xe7) 0x01])}))

#?(:clj
   (defn- prefix-match?
     [^bytes prefix ^bytes b]
     (and (<= (alength prefix) (alength b))
          (Arrays/equals prefix (Arrays/copyOfRange b 0 (alength prefix))))))

#?(:clj
   (defn- concat-bytes
     ^bytes [^bytes a ^bytes b]
     (let [out (byte-array (+ (alength a) (alength b)))]
       (System/arraycopy a 0 out 0 (alength a))
       (System/arraycopy b 0 out (alength a) (alength b))
       out)))

#?(:clj
   (defn- bigint->32-bytes
     "Fixed-width 32-byte big-endian encoding of a non-negative BigInteger
     (strips BigInteger/toByteArray's sign byte, left-pads short values)."
     ^bytes [^BigInteger v]
     (let [b (.toByteArray v)
           len (alength b)
           out (byte-array 32)]
       (if (<= len 32)
         (System/arraycopy b 0 out (- 32 len) len)
         (System/arraycopy b (- len 32) out 0 32))
       out)))

#?(:clj
   (defn- decode-point
     "Decode and validate a compressed or uncompressed public key on the
     given curve. Returns an ECPoint or nil if invalid."
     ^ECPoint [^ECDomainParameters domain ^bytes pubkey-bytes]
     (try
       (.decodePoint (.getCurve domain) pubkey-bytes)
       (catch Exception _ nil))))

;; -----------------------------------------------------------------------------
;; Public key encoding & did:key (FROZEN CONTRACT)
;; -----------------------------------------------------------------------------

(defn compress-pubkey
  "33-byte compressed form of a public key (accepts 33 or 65 byte input).
  Returns {:error \"InvalidPublicKey\"} if not a valid curve point."
  [alg pubkey-bytes]
  #?(:clj
     (if-let [^ECDomainParameters domain (domains alg)]
       (if-let [point (decode-point domain pubkey-bytes)]
         (.getEncoded point true)
         {:error "InvalidPublicKey"
          :message "Not a valid public key for this curve."})
       {:error "UnsupportedAlgorithm"
        :message (str "Unsupported algorithm: " (pr-str alg))})))

(defn decompress-pubkey
  "65-byte uncompressed form of a 33-byte compressed public key.
  Returns {:error \"InvalidPublicKey\"} on bad length or invalid point."
  [alg pubkey-bytes]
  #?(:clj
     (if-let [^ECDomainParameters domain (domains alg)]
       (if (not= 33 (alength ^bytes pubkey-bytes))
         {:error "InvalidPublicKey"
          :message "Expected a 33-byte compressed public key."}
         (if-let [point (decode-point domain pubkey-bytes)]
           (.getEncoded point false)
           {:error "InvalidPublicKey"
            :message "Not a valid public key for this curve."}))
       {:error "UnsupportedAlgorithm"
        :message (str "Unsupported algorithm: " (pr-str alg))})))

(defn format-multikey
  "Multikey string (\"z...\") for the public key — did:key without the
  \"did:key:\" prefix: base58btc of multicodec-prefix ++ compressed-pubkey.
  Returns {:error \"UnsupportedAlgorithm\"|\"InvalidPublicKey\" ...} on bad input."
  [alg pubkey-bytes]
  #?(:clj
     (if-let [^bytes prefix (did-prefixes alg)]
       (let [compressed (compress-pubkey alg pubkey-bytes)]
         (if (map? compressed)
           compressed
           (mb/format :base58btc (concat-bytes prefix compressed))))
       {:error "UnsupportedAlgorithm"
        :message (str "Unsupported algorithm: " (pr-str alg))})))

(defn parse-multikey
  "Parse a multikey string (\"z...\").
  Returns {:alg \"ES256\"|\"ES256K\" :bytes uncompressed-65-byte-pubkey}
  or {:error \"InvalidDidKey\"|\"UnsupportedKeyType\"|\"InvalidPublicKey\" ...}."
  [multikey]
  #?(:clj
     (if-not (and (string? multikey) (str/starts-with? multikey "z"))
       {:error "InvalidDidKey"
        :message "Multikey must be a base58btc ('z'-prefixed) string."}
       (let [prefixed (try (mb/parse multikey) (catch Exception _ nil))]
         (if (nil? prefixed)
           {:error "InvalidDidKey"
            :message "Invalid base58btc encoding."}
           (if-let [alg (some (fn [[alg ^bytes prefix]]
                                (when (prefix-match? prefix prefixed) alg))
                              did-prefixes)]
             (let [^bytes prefix (did-prefixes alg)
                   key-bytes (Arrays/copyOfRange ^bytes prefixed
                                                 (alength prefix)
                                                 (alength ^bytes prefixed))
                   uncompressed (decompress-pubkey alg key-bytes)]
               (if (map? uncompressed)
                 uncompressed
                 {:alg alg :bytes uncompressed}))
             {:error "UnsupportedKeyType"
              :message "Unsupported did:key multicodec prefix."}))))))

(defn pubkey->did-key
  "did:key string for the public key (compressed or uncompressed input):
  \"did:key:z\" + base58btc(multicodec-prefix ++ compressed-pubkey).
  Returns {:error \"UnsupportedAlgorithm\"|\"InvalidPublicKey\" ...} on bad input."
  [alg pubkey-bytes]
  (let [multikey (format-multikey alg pubkey-bytes)]
    (if (map? multikey)
      multikey
      (str did-key-prefix multikey))))

(defn did-key->pubkey
  "Parse a did:key string.
  Returns {:alg \"ES256\"|\"ES256K\" :bytes uncompressed-65-byte-pubkey}
  or {:error \"InvalidDidKey\"|\"UnsupportedKeyType\" :message ...}."
  [did]
  (if-not (and (string? did) (str/starts-with? did did-key-prefix))
    {:error "InvalidDidKey"
     :message (str "did:key must start with " (pr-str did-key-prefix) ".")}
    (parse-multikey (subs did (count did-key-prefix)))))

(defn multibase->bytes
  "Decode a multibase-prefixed string to bytes (multiformats.base/parse).
  Returns {:error \"InvalidMultibase\" ...} on unknown prefix or bad encoding."
  [s]
  (try
    (mb/parse s)
    (catch #?(:clj Exception :cljs :default) e
      {:error "InvalidMultibase" :message (ex-message e)})))

(defn bytes->multibase
  "Encode bytes as a multibase-prefixed string for the given base keyword,
  e.g. (bytes->multibase :base58btc b) => \"z...\" (multiformats.base/format)."
  [base-key b]
  (mb/format base-key b))

;; -----------------------------------------------------------------------------
;; ECDSA signing & verification internals (JVM)
;; -----------------------------------------------------------------------------

#?(:clj
   (defn- ecdsa-sign
     "64-byte compact low-S signature of (sha256 msg) with deterministic
     nonces (RFC 6979, parity with noble)."
     ^bytes [alg ^BigInteger d ^bytes msg]
     (let [^ECDomainParameters domain (domains alg)
           n (.getN domain)
           signer (ECDSASigner. (HMacDSAKCalculator. (SHA256Digest.)))]
       (.init signer true (ECPrivateKeyParameters. d domain))
       (let [^"[Ljava.math.BigInteger;" rs (.generateSignature signer (runtime.crypto/sha256 msg))
             r (aget rs 0)
             ^BigInteger s (aget rs 1)
             s (if (pos? (.compareTo s (.shiftRight n 1)))
                 (.subtract n s)
                 s)]
         (concat-bytes (bigint->32-bytes r) (bigint->32-bytes s))))))

#?(:clj
   (defn- parse-compact-sig
     "[r s] from a 64-byte compact signature, or nil."
     [^bytes sig]
     (when (and sig (= 64 (alength sig)))
       [(BigInteger. 1 (Arrays/copyOfRange sig 0 32))
        (BigInteger. 1 (Arrays/copyOfRange sig 32 64))])))

#?(:clj
   (defn- parse-der-sig
     "[r s] from a DER-encoded ECDSA signature, or nil."
     [^bytes sig]
     (try
       (let [obj (ASN1Primitive/fromByteArray sig)]
         (when (instance? ASN1Sequence obj)
           (let [^ASN1Sequence sq obj]
             (when (= 2 (.size sq))
               [(.getValue (ASN1Integer/getInstance (.getObjectAt sq 0)))
                (.getValue (ASN1Integer/getInstance (.getObjectAt sq 1)))]))))
       (catch Exception _ nil))))

#?(:clj
   (defn- parse-sig
     "[r s] from signature bytes per the atproto malleability rules, or nil.

     Strict (default): exactly 64-byte compact and low-S.
     Malleable: DER or compact, high-S accepted (noble parity: DER is
     tried first, then compact)."
     [^ECDomainParameters domain ^bytes sig allow-malleable?]
     (if allow-malleable?
       (or (parse-der-sig sig)
           (parse-compact-sig sig))
       (when-let [[_ ^BigInteger s :as rs] (parse-compact-sig sig)]
         (when-not (pos? (.compareTo s (.shiftRight (.getN domain) 1)))
           rs)))))

#?(:clj
   (defn- verify-sync
     "true/false, or {:error ...} for unsupported alg / invalid pubkey."
     [pubkey-bytes sig-bytes msg-bytes alg allow-malleable?]
     (if-let [^ECDomainParameters domain (domains alg)]
       (if-let [point (decode-point domain pubkey-bytes)]
         (if-let [[r s] (parse-sig domain sig-bytes allow-malleable?)]
           (let [signer (ECDSASigner.)]
             (.init signer false (ECPublicKeyParameters. point domain))
             (.verifySignature signer (runtime.crypto/sha256 msg-bytes)
                               ^BigInteger r ^BigInteger s))
           false)
         {:error "InvalidPublicKey"
          :message "Not a valid public key for this curve."})
       {:error "UnsupportedAlgorithm"
        :message (str "Unsupported algorithm: " (pr-str alg))})))

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
  (-sign [kp msg-bytes]
    "Synchronous signing primitive; use the async `sign` fn instead.")
  (export [kp]
    "Raw 32-byte private key, or {:error \"PrivateKeyNotExportable\"}
    if the keypair was not created with :exportable? true."))

#?(:clj
   (deftype ECKeypair [alg-str ^BigInteger d ^bytes pubkey did-str exportable?]
     Keypair
     (alg [_] alg-str)
     (public-key [_] pubkey)
     (did [_] did-str)
     (-sign [_ msg-bytes] (ecdsa-sign alg-str d msg-bytes))
     (export [_]
       (if exportable?
         (bigint->32-bytes d)
         {:error "PrivateKeyNotExportable"
          :message "Keypair was not created with :exportable? true."}))))

#?(:clj
   (defn- make-keypair
     [alg ^BigInteger d exportable?]
     (let [^ECDomainParameters domain (domains alg)
           pubkey (.getEncoded (.multiply (.getG domain) d) true)]
       (->ECKeypair alg d pubkey (pubkey->did-key alg pubkey) (boolean exportable?)))))

#?(:clj
   (defn- generate-sync
     [alg exportable?]
     (if-let [^ECDomainParameters domain (domains alg)]
       (let [gen (ECKeyPairGenerator.)]
         (.init gen (ECKeyGenerationParameters. domain (SecureRandom.)))
         (let [^AsymmetricCipherKeyPair kp (.generateKeyPair gen)
               d (.getD ^ECPrivateKeyParameters (.getPrivate kp))]
           (make-keypair alg d exportable?)))
       {:error "UnsupportedAlgorithm"
        :message (str "Unsupported algorithm: " (pr-str alg))})))

#?(:clj
   (defn- import-private-key-sync
     [alg priv exportable?]
     (if-let [^ECDomainParameters domain (domains alg)]
       (let [b (cond
                 (bytes/bytes? priv) priv
                 (string? priv) (runtime.crypto/hex-decode priv))]
         (cond
           (nil? b)
           {:error "InvalidPrivateKey"
            :message "Private key must be a 32-byte array or hex string."}

           (not= 32 (alength ^bytes b))
           {:error "InvalidPrivateKey"
            :message "Private key must be exactly 32 bytes."}

           :else
           (let [d (BigInteger. 1 ^bytes b)]
             (if (or (zero? (.signum d))
                     (<= 0 (.compareTo d (.getN domain))))
               {:error "InvalidPrivateKey"
                :message "Private key scalar out of range for the curve."}
               (make-keypair alg d exportable?)))))
       {:error "UnsupportedAlgorithm"
        :message (str "Unsupported algorithm: " (pr-str alg))})))

(defn generate
  "Generate a new keypair on the given curve (\"ES256\" or \"ES256K\").

  opts:
    :exportable?  allow `export` of the private key (default false)

  Async; yields a Keypair or {:error \"UnsupportedAlgorithm\" :message ...}."
  [alg & {:keys [exportable?] :as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (cb #?(:clj (generate-sync alg exportable?)
           :cljs {:error "NotImplemented"
                  :message "atproto.crypto is not yet implemented on ClojureScript."}))
    val))

(defn import-private-key
  "Build a keypair from a raw 32-byte private scalar.
  `priv` may be a byte array or a hex string (TS parity).

  opts:
    :exportable?  allow `export` of the private key (default false)

  Async; yields a Keypair or {:error \"InvalidPrivateKey\" :message ...}."
  [alg priv & {:keys [exportable?] :as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (cb #?(:clj (import-private-key-sync alg priv exportable?)
           :cljs {:error "NotImplemented"
                  :message "atproto.crypto is not yet implemented on ClojureScript."}))
    val))

(def ^:private alg->crv {"ES256" "P-256", "ES256K" "secp256k1"})
(def ^:private crv->alg {"P-256" "ES256", "secp256k1" "ES256K"})

(defn keypair->jwk
  "Private EC JWK map for the keypair:
  {:kty \"EC\" :crv \"P-256\"|\"secp256k1\" :x .. :y .. :d ..}
  (base64url-encoded coordinates, RFC 7518 §6.2 / RFC 8812).
  Returns {:error \"PrivateKeyNotExportable\"} unless exportable."
  [kp]
  #?(:clj
     (let [priv (export kp)]
       (if (map? priv)
         priv
         (let [^bytes uncompressed (decompress-pubkey (alg kp) (public-key kp))]
           {:kty "EC"
            :crv (alg->crv (alg kp))
            :x (runtime.crypto/base64url-encode (Arrays/copyOfRange uncompressed 1 33))
            :y (runtime.crypto/base64url-encode (Arrays/copyOfRange uncompressed 33 65))
            :d (runtime.crypto/base64url-encode ^bytes priv)})))))

(defn jwk->keypair
  "Keypair from a private EC JWK map (inverse of keypair->jwk).
  Returns {:error \"InvalidJwk\" :message ...} on bad input."
  [jwk & {:keys [exportable?]}]
  #?(:clj
     (let [{:keys [kty crv x y d]} jwk
           alg (crv->alg crv)]
       (if (or (not= "EC" kty) (nil? alg))
         {:error "InvalidJwk"
          :message "Expected an EC JWK with :crv \"P-256\" or \"secp256k1\"."}
         (let [db (when (string? d) (runtime.crypto/base64url-decode d))]
           (if (or (nil? db) (not= 32 (alength ^bytes db)))
             {:error "InvalidJwk"
              :message "Missing or invalid private key component (:d)."}
             (let [kp (import-private-key-sync alg db exportable?)]
               (if (map? kp)
                 {:error "InvalidJwk" :message (:message kp)}
                 (let [^bytes uncompressed (decompress-pubkey alg (public-key kp))]
                   (if (and (or (nil? x)
                                (bytes/eq? (Arrays/copyOfRange uncompressed 1 33)
                                           (runtime.crypto/base64url-decode x)))
                            (or (nil? y)
                                (bytes/eq? (Arrays/copyOfRange uncompressed 33 65)
                                           (runtime.crypto/base64url-decode y))))
                     kp
                     {:error "InvalidJwk"
                      :message "JWK public key does not match its private key."}))))))))))

;; -----------------------------------------------------------------------------
;; Signing & verification (FROZEN CONTRACT)
;; -----------------------------------------------------------------------------

(defn sign
  "ECDSA signature of (sha256 msg-bytes) with the keypair.
  Async; yields the 64-byte compact low-S R||S signature bytes."
  [keypair msg-bytes & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (cb (-sign keypair msg-bytes))
    val))

(defn verify
  "Verify an ECDSA signature over (sha256 msg-bytes).

  pubkey-bytes  compressed (33) or uncompressed (65) public key
  sig-bytes     signature bytes
  opts          {:alg \"ES256\"|\"ES256K\"   ;; required
                 :allow-malleable? false}    ;; default false

  Default (strict, atproto rules): sig must be exactly 64 bytes compact
  and low-S. With :allow-malleable? true: DER or compact accepted, high-S
  accepted (TS parity: noble `format: undefined, lowS: false`).

  Async; yields true/false. Malformed signatures yield false; an
  unsupported :alg or an invalid public key yields {:error ...}
  (programmer error, distinct from signature invalidity)."
  [pubkey-bytes sig-bytes msg-bytes & {:keys [alg allow-malleable?] :as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (cb #?(:clj (verify-sync pubkey-bytes sig-bytes msg-bytes alg allow-malleable?)
           :cljs {:error "NotImplemented"
                  :message "atproto.crypto is not yet implemented on ClojureScript."}))
    val))

(defn verify-did-sig
  "Verify a signature against the public key embedded in a did:key string,
  dispatching on its multicodec prefix (TS verifySignature).

  opts: {:alg ..               ;; optional assertion; error if it mismatches the did
         :allow-malleable? false}

  Async; yields true/false, or {:error ...} for a malformed/unsupported did:key."
  [did-key sig-bytes msg-bytes & {:keys [alg allow-malleable?] :as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (cb (let [parsed (did-key->pubkey did-key)]
          (cond
            (:error parsed)
            parsed

            (and alg (not= alg (:alg parsed)))
            {:error "AlgorithmMismatch"
             :message (str "Expected key alg " alg ", got " (:alg parsed) ".")}

            :else
            #?(:clj (verify-sync (:bytes parsed) sig-bytes msg-bytes
                                 (:alg parsed) allow-malleable?)
               :cljs {:error "NotImplemented"
                      :message "atproto.crypto is not yet implemented on ClojureScript."}))))
    val))
