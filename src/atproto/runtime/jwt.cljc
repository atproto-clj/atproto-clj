(ns atproto.runtime.jwt
  "Cross platform JSON Web Token implementation for atproto."
  #?(:clj (:require [clojure.walk :refer [stringify-keys keywordize-keys]]))
  #?(:clj (:import [java.util Map Set]
                   [com.nimbusds.jwt JWTClaimsSet$Builder SignedJWT]
                   [com.nimbusds.jose Algorithm JWSAlgorithm JWSHeader$Builder JOSEObjectType JWSSigner]
                   [com.nimbusds.jose.jwk JWKSet JWK JWKSelector JWKMatcher JWKMatcher$Builder ECKey Curve KeyUse KeyType]
                   [com.nimbusds.jose.jwk.gen JWKGenerator ECKeyGenerator OctetKeyPairGenerator RSAKeyGenerator]
                   [com.nimbusds.jose.crypto.factories DefaultJWSSignerFactory])))

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
