(ns atproto.runtime.jwt
  "Cross platform JSON Web Token implementation for atproto."
  (:require [clojure.string :as str]
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.json :as json]
            [atproto.runtime.bytes :as bytes]
            [atproto.runtime.crypto :as runtime.crypto]
            [atproto.crypto :as crypto]
            #?(:clj [clojure.walk :refer [stringify-keys keywordize-keys]]))
  #?(:clj (:import [java.util Map Set]
                   [java.nio.charset StandardCharsets]
                   [com.nimbusds.jwt JWTClaimsSet$Builder SignedJWT]
                   [com.nimbusds.jose Algorithm JWSAlgorithm JWSHeader$Builder JOSEObjectType JWSSigner]
                   [com.nimbusds.jose.jwk JWKSet JWK JWKSelector JWKMatcher JWKMatcher$Builder ECKey RSAKey Curve KeyUse KeyType]
                   [com.nimbusds.jose.jwk.gen JWKGenerator ECKeyGenerator OctetKeyPairGenerator RSAKeyGenerator]
                   [com.nimbusds.jose.crypto.factories DefaultJWSSignerFactory DefaultJWSVerifierFactory])))

#?(:clj (set! *warn-on-reflection* true))

#?(:clj
   (do
     (defn jwks->clj [^JWKSet jwks] (->> jwks (.toJSONObject) (into {}) keywordize-keys))
     (defn ^JWKSet clj->jwks [m] (JWKSet/parse ^Map (stringify-keys m)))
     (defn jwk->clj  [^JWK jwk] (->> jwk (.toJSONObject) (into {}) keywordize-keys))
     (defn ^JWK clj->jwk  [m] (JWK/parse ^Map (stringify-keys m)))))

(defn public-jwks
  "Return a JSON Web Keyset with only the public keys."
  [jwks]
  #?(:clj (->> (clj->jwks ^Map jwks)
               (.toPublicJWKSet)
               (jwks->clj))))

(defn query-jwks
  "A seq of JSON Web Keys matching the query.

  The query accepts either of the following keys:
  :alg   a JSON Web Algorithm identifier
  :kid   a key identifier"
  [jwks {:keys [alg kid]}]
  #?(:clj (->> (clj->jwks ^Map jwks)
               (.select (JWKSelector.
                         (cond
                           kid
                           (-> (JWKMatcher$Builder.)
                               (.keyIDs ^Set (set [kid]))
                               (.build))

                           alg
                           (let [jws-alg (JWSAlgorithm/parse ^String alg)]
                             (-> (JWKMatcher$Builder.)
                                 (.keyType (KeyType/forAlgorithm jws-alg))
                                 (.curves ^Set (set (Curve/forJWSAlgorithm jws-alg)))
                                 (.build))))))
               (map jwk->clj))))

(defn generate-jwk
  "Generate a JSON Web Key for digital signatures."
  [{:keys [alg kid curve size] :as opts}]
  #?(:clj (when-let [kty (KeyType/forAlgorithm (JWSAlgorithm/parse ^String alg))]
            (when-let [generator (let [curve (or (Curve/forStdName ^String curve)
                                                 (first (Curve/forJWSAlgorithm (JWSAlgorithm/parse ^String alg))))]
                                   (case (str kty)
                                     "RSA" (RSAKeyGenerator. (or size 2048) false)
                                     "EC"  (ECKeyGenerator. curve)
                                     "OKP" (OctetKeyPairGenerator. curve)
                                     nil))]
              (-> ^JWKGenerator generator
                  (.keyUse KeyUse/SIGNATURE)
                  (.keyID ^String kid)
                  (.generate)
                  (jwk->clj))))))

(defn public-jwk
  "The public JWK for this private JWK."
  [jwk]
  #?(:clj (-> (clj->jwk jwk)
              (.toPublicJWK)
              (jwk->clj))))

(defn jwk-kid
  "The Key identifier for this JWK."
  [jwk]
  #?(:clj (.getKeyID (clj->jwk jwk))))

(defn generate
  "Base64url-encoded serialization of the JSON Web token signed with the given key."
  [jwk {:keys [alg] :as headers} claims]
  #?(:clj (let [header (->> headers
                            ^JWSHeader$Builder
                            (reduce (fn [^JWSHeader$Builder builder [k v]]
                                      (case k
                                        :alg builder
                                        :kid (.keyID builder v)
                                        :typ (.type builder (JOSEObjectType. v))
                                        :jwk (.jwk builder (clj->jwk v))
                                        (.customParam builder (name k) v)))
                                    (JWSHeader$Builder. (JWSAlgorithm/parse ^String alg)))
                            (.build))
                payload (->> claims
                             ^JWTClaimsSet$Builder
                             (reduce (fn [^JWTClaimsSet$Builder builder [k v]]
                                       (.claim builder (name k) v))
                                     (JWTClaimsSet$Builder.))
                             (.build))
                jwt (SignedJWT. header payload)
                signer (.createJWSSigner (DefaultJWSSignerFactory.)
                                         (clj->jwk jwk))]
            (.sign jwt signer)
            (.serialize jwt))))

;; -----------------------------------------------------------------------------
;; Compact JWS/JWT parsing, verification & signing (ES256/ES256K via
;; atproto.crypto; the Nimbus-based `generate` above stays the OAuth/DPoP
;; signing path).
;; -----------------------------------------------------------------------------

(defn- async-opts
  "The async-adapter keys of an options map (see i/platform-async)."
  [opts]
  (select-keys opts [:channel :callback :promise]))

(defn parse
  "Split and decode a compact JWS/JWT WITHOUT verifying it.

  Returns {:header {...}          ;; keywordized
           :claims {...}          ;; keywordized (payload)
           :signing-input bytes   ;; ASCII bytes of \"<b64h>.<b64p>\"
           :signature bytes}      ;; base64url-decoded
  or {:error \"MalformedJwt\" :message ...}."
  [jwt-str]
  #?(:clj
     (let [parts (when (string? jwt-str) (str/split jwt-str #"\." -1))]
       (if (not= 3 (count parts))
         {:error "MalformedJwt"
          :message "Expected three dot-separated base64url segments."}
         (let [[h p sig] parts
               header-bytes (runtime.crypto/base64url-decode h)
               claims-bytes (runtime.crypto/base64url-decode p)
               sig-bytes (runtime.crypto/base64url-decode sig)
               header (when header-bytes
                        (try (json/read-str (bytes/->utf8 header-bytes))
                             (catch Exception _ nil)))
               claims (when claims-bytes
                        (try (json/read-str (bytes/->utf8 claims-bytes))
                             (catch Exception _ nil)))]
           (if (and (map? header) (map? claims) sig-bytes)
             {:header header
              :claims claims
              :signing-input (.getBytes (str h "." p) StandardCharsets/US_ASCII)
              :signature sig-bytes}
             {:error "MalformedJwt"
              :message "Could not decode the JWT header, claims, or signature."}))))))

#?(:clj
   (defn- ec-jwk->pubkey
     "{:alg .. :bytes uncompressed-65-byte-pubkey} from a public EC JWK map,
     or nil if it is not a well-formed P-256/secp256k1 JWK."
     [{:keys [kty crv x y]}]
     (let [alg ({"P-256" "ES256", "secp256k1" "ES256K"} crv)
           ^bytes xb (when (string? x) (runtime.crypto/base64url-decode x))
           ^bytes yb (when (string? y) (runtime.crypto/base64url-decode y))]
       (when (and (= "EC" kty) alg
                  xb yb (= 32 (alength xb)) (= 32 (alength yb)))
         {:alg alg
          :bytes (byte-array (concat [0x04] xb yb))}))))

#?(:clj
   (defn- verify-es-sig
     "Verify the JWS signature against an ES256/ES256K public key.
     Returns boolean or {:error ...}."
     [{:keys [signing-input signature]} header-alg {key-alg :alg key-bytes :bytes} allow-malleable?]
     (if (not= header-alg key-alg)
       {:error "BadJwtSignature"
        :message (str "JWT alg " header-alg " does not match key alg " key-alg ".")}
       ;; atproto.crypto key operations are async; on the JVM they compute
       ;; synchronously, so the promise is already delivered.
       @(crypto/verify key-bytes signature signing-input
                       :alg key-alg
                       :allow-malleable? allow-malleable?))))

#?(:clj
   (defn- nimbus-verify
     "Verify a compact JWS with a (non-ES256/ES256K) JWK via Nimbus.
     Returns boolean or {:error ...}."
     [jwt-str jwk]
     (let [jwk-obj (clj->jwk jwk)
           key (case (str (.getKeyType jwk-obj))
                 "RSA" (.toRSAPublicKey ^RSAKey jwk-obj)
                 "EC" (.toECPublicKey ^ECKey jwk-obj)
                 nil)]
       (if (nil? key)
         {:error "UnsupportedAlgorithm"
          :message "Unsupported JWK key type for verification."}
         (try
           (let [signed (SignedJWT/parse ^String jwt-str)
                 verifier (.createJWSVerifier (DefaultJWSVerifierFactory.)
                                              (.getHeader signed)
                                              key)]
             (.verify signed verifier))
           (catch Exception _ false))))))

#?(:clj
   (declare check-signature))

#?(:clj
   (defn- check-keyset-signature
     "Try every JWKS key matching the JWT header (by kid when present,
     else by alg). Boolean, or {:error ...} when no key matches."
     [jwt-str {:keys [header] :as parsed} jwks allow-malleable?]
     (let [{:keys [kid alg]} header
           candidates (query-jwks jwks (if kid {:kid kid} {:alg alg}))]
       (if (empty? candidates)
         {:error "NoMatchingKey"
          :message (str "No key in the keyset matches "
                        (if kid (str "kid " (pr-str kid)) (str "alg " (pr-str alg))) ".")}
         (reduce (fn [_ candidate]
                   (let [res (check-signature jwt-str parsed {:jwk candidate} allow-malleable?)]
                     (if (true? res)
                       (reduced true)
                       res)))
                 false
                 candidates)))))

#?(:clj
   (defn- check-signature
     "Boolean, or {:error ...} for problems distinct from an invalid signature."
     [jwt-str {:keys [header] :as parsed} key allow-malleable?]
     (let [alg (:alg header)]
       (cond
         (string? key)
         (let [pk (crypto/did-key->pubkey key)]
           (if (:error pk)
             pk
             (verify-es-sig parsed alg pk allow-malleable?)))

         (:pubkey key)
         (verify-es-sig parsed alg (:pubkey key) allow-malleable?)

         (:jwk key)
         (if (#{"ES256" "ES256K"} alg)
           (if-let [pk (ec-jwk->pubkey (:jwk key))]
             (verify-es-sig parsed alg pk allow-malleable?)
             {:error "InvalidJwk"
              :message "JWK is not a well-formed P-256/secp256k1 EC key."})
           (nimbus-verify jwt-str (:jwk key)))

         (:jwks key)
         (check-keyset-signature jwt-str parsed (:jwks key) allow-malleable?)

         :else
         {:error "UnsupportedKeyType"
          :message "key must be a did:key string, {:pubkey ...}, {:jwk ...}, or {:jwks ...}."}))))

#?(:clj
   (defn- verify-sync
     [jwt-str key {:keys [now leeway] :or {leeway 0} :as opts}]
     (let [allow-malleable? (if (contains? opts :allow-malleable?)
                              (:allow-malleable? opts)
                              true)
           parsed (parse jwt-str)]
       (if (:error parsed)
         parsed
         (let [{:keys [header claims]} parsed
               now (or now (runtime.crypto/now))
               {:keys [exp nbf iat]} claims]
           (cond
             (not (string? (:alg header)))
             {:error "MalformedJwt"
              :message "Missing or invalid \"alg\" header."}

             (and (number? exp) (> now (+ exp leeway)))
             {:error "JwtExpired" :message "jwt expired"}

             (and (number? nbf) (< now (- nbf leeway)))
             {:error "JwtNotYetValid" :message "jwt not valid yet (nbf)"}

             (and (number? iat) (< now (- iat leeway)))
             {:error "JwtNotYetValid" :message "jwt issued in the future (iat)"}

             :else
             (let [result (check-signature jwt-str parsed key allow-malleable?)]
               (cond
                 (map? result) result
                 (true? result) {:header header :claims claims}
                 :else {:error "BadJwtSignature"
                        :message "JWT signature verification failed."}))))))))

(defn verify
  "Verify a compact JWS/JWT's signature and temporal claims.

  `key` is one of:
    - a did:key string                       (ES256/ES256K via atproto.crypto)
    - {:pubkey {:alg .. :bytes ..}}          (ES256/ES256K raw key)
    - {:jwk {...}}                           (delegates to Nimbus for other algs)
    - {:jwks {:keys [...]}}                  (keyset: candidates selected by the
                                              JWT header kid, else alg; any
                                              matching key may verify;
                                              {:error \"NoMatchingKey\"} when
                                              none matches)

  opts:
    :now              epoch seconds (default (runtime.crypto/now)) — for tests
    :leeway           seconds of clock skew tolerance (default 0)
    :allow-malleable? for ES256/ES256K sig checks (default true — matches
                      the reference service-JWT verification,
                      xrpc-server/src/auth.ts cryptoVerifySignatureWithKey;
                      pass false for strict atproto signature rules)

  Checks: signature over the signing input; :exp (expired → error);
  :nbf/:iat when present. Does NOT check aud/iss/lxm/typ — callers
  (WS-08 service auth) layer policy on the returned claims.

  Async; yields {:header {...} :claims {...}}
  or {:error \"MalformedJwt\"|\"BadJwtSignature\"|\"JwtExpired\"|
      \"UnsupportedAlgorithm\" ... :message ...}."
  [jwt-str key & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (cb #?(:clj (verify-sync jwt-str key opts)
           :cljs {:error "NotImplemented"
                  :message "JWT verification is not yet implemented on ClojureScript."}))
    val))

(defn sign
  "Compact JWS signed with an atproto.crypto Keypair (ES256/ES256K):
  base64url(JSON header) \".\" base64url(JSON claims) \".\" base64url(64-byte sig).
  The :alg header is set from (crypto/alg keypair); the ES256/ES256K JWS
  signature is exactly the 64-byte compact signature (RFC 7518 §3.4,
  RFC 8812 §3.2). Mirrors the reference createServiceJwt encoding.

  Async; yields the compact JWS string."
  [keypair headers claims & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    #?(:clj
       (let [b64url (fn [^String s]
                      (runtime.crypto/base64url-encode
                       (.getBytes s StandardCharsets/UTF_8)))
             header (assoc headers :alg (crypto/alg keypair))
             h64 (b64url (json/write-str header))
             p64 (b64url (json/write-str claims))
             signing-input (.getBytes (str h64 "." p64) StandardCharsets/US_ASCII)]
         (crypto/sign keypair signing-input
                      :callback (fn [sig]
                                  (cb (if (:error sig)
                                        sig
                                        (str h64 "." p64 "."
                                             (runtime.crypto/base64url-encode sig)))))))
       :cljs (cb {:error "NotImplemented"
                  :message "JWT signing with atproto.crypto keypairs is not yet implemented on ClojureScript."}))
    val))
