(ns atproto.runtime.jwt-test
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            [clojure.string :as str]
            [atproto.runtime.crypto :as runtime.crypto]
            [atproto.runtime.jwt :as jwt]
            [atproto.crypto :as crypto])
  #?(:clj (:import [java.math BigInteger]
                   [java.util Arrays])))

(defn- result-of
  [async-val]
  (deref async-val 1000 ::timeout))

(def claims
  {:iss "did:example:alice"
   :aud "did:example:bob"
   :lxm "com.example.method"
   :exp 4102444800}) ;; 2100-01-01

#?(:clj
   (deftest parse-test
     (let [kp (result-of (crypto/generate "ES256K"))
           token (result-of (jwt/sign kp {:typ "JWT"} claims))
           parsed (jwt/parse token)]
       (testing "decodes header, claims, signing input, and signature"
         (is (= {:typ "JWT" :alg "ES256K"} (:header parsed)))
         (is (= claims (:claims parsed)))
         (is (= (str/join "." (take 2 (str/split token #"\.")))
                (String. ^bytes (:signing-input parsed) "US-ASCII")))
         (is (= 64 (alength ^bytes (:signature parsed)))))
       (testing "malformed input"
         (doseq [bad [nil 42 "" "garbage" "a.b" "a.b.c.d" "!!.??.~~"]]
           (is (= "MalformedJwt" (:error (jwt/parse bad))) (pr-str bad)))))))

#?(:clj
   (deftest sign-verify-round-trip-test
     (doseq [alg ["ES256" "ES256K"]]
       (let [kp (result-of (crypto/generate alg))
             token (result-of (jwt/sign kp {:typ "JWT"} claims))]
         (testing (str alg " verify with the did:key string")
           (let [res (result-of (jwt/verify token (crypto/did kp)))]
             (is (nil? (:error res)))
             (is (= claims (:claims res)))
             (is (= alg (-> res :header :alg)))))
         (testing (str alg " verify with {:pubkey ...}")
           (let [res (result-of (jwt/verify token {:pubkey {:alg alg
                                                            :bytes (crypto/public-key kp)}}))]
             (is (= claims (:claims res)))))
         (testing (str alg " verify with a public EC {:jwk ...}")
           (let [exportable (result-of (crypto/import-private-key
                                        alg (crypto/export (result-of (crypto/generate alg :exportable? true)))
                                        :exportable? true))
                 token2 (result-of (jwt/sign exportable {:typ "JWT"} claims))
                 public-jwk (dissoc (crypto/keypair->jwk exportable) :d)
                 res (result-of (jwt/verify token2 {:jwk public-jwk}))]
             (is (= claims (:claims res)))))
         (testing (str alg " wrong key fails")
           (let [other (result-of (crypto/generate alg))]
             (is (= "BadJwtSignature"
                    (:error (result-of (jwt/verify token (crypto/did other))))))))))))

#?(:clj
   (deftest verify-nimbus-cross-check-test
     (testing "verifies an ES256 token produced by the Nimbus-based generate"
       (let [jwk (jwt/generate-jwk {:alg "ES256" :kid "test-key"})
             token (jwt/generate jwk {:alg "ES256" :typ "JWT"} claims)
             res (result-of (jwt/verify token {:jwk (jwt/public-jwk jwk)}))]
         (is (nil? (:error res)))
         (is (= "did:example:alice" (-> res :claims :iss)))))
     (testing "delegates non-ES256K algs to Nimbus"
       (let [jwk (jwt/generate-jwk {:alg "RS256" :kid "rsa-key"})
             token (jwt/generate jwk {:alg "RS256" :typ "JWT"} claims)
             res (result-of (jwt/verify token {:jwk (jwt/public-jwk jwk)}))]
         (is (nil? (:error res)))
         (is (= claims (:claims res)))))))

#?(:clj
   (deftest verify-temporal-claims-test
     (let [kp (result-of (crypto/generate "ES256K"))
           did (crypto/did kp)
           token (result-of (jwt/sign kp {:typ "JWT"} {:iss "x" :exp 1000}))]
       (testing "expired token"
         (is (= "JwtExpired" (:error (result-of (jwt/verify token did :now 2000))))))
       (testing "not yet expired"
         (is (nil? (:error (result-of (jwt/verify token did :now 500))))))
       (testing "leeway tolerates clock skew"
         (is (nil? (:error (result-of (jwt/verify token did :now 1010 :leeway 30))))))
       (testing "nbf in the future"
         (let [token (result-of (jwt/sign kp {:typ "JWT"} {:iss "x" :nbf 1000}))]
           (is (= "JwtNotYetValid" (:error (result-of (jwt/verify token did :now 500)))))
           (is (nil? (:error (result-of (jwt/verify token did :now 1500))))))))))

#?(:clj
   (deftest verify-tampering-test
     (let [kp (result-of (crypto/generate "ES256K"))
           did (crypto/did kp)
           token (result-of (jwt/sign kp {:typ "JWT"} claims))
           [h p sig] (str/split token #"\.")]
       (testing "tampered payload"
         (let [evil (runtime.crypto/base64url-encode
                     (.getBytes "{\"iss\":\"did:example:mallory\"}" "UTF-8"))]
           (is (= "BadJwtSignature"
                  (:error (result-of (jwt/verify (str h "." evil "." sig) did)))))))
       (testing "tampered signature"
         (let [^bytes sig-bytes (runtime.crypto/base64url-decode sig)
               _ (aset sig-bytes 10 (unchecked-byte (bit-xor (aget sig-bytes 10) 0xff)))
               bad (runtime.crypto/base64url-encode sig-bytes)]
           (is (= "BadJwtSignature"
                  (:error (result-of (jwt/verify (str h "." p "." bad) did))))))))))

;; secp256k1 curve order, for constructing a high-S variant of a valid sig.
(def ^:private k256-n
  #?(:clj (BigInteger. "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141" 16)))

#?(:clj
   (deftest verify-malleability-default-test
     ;; Service JWTs are verified leniently in the reference implementation
     ;; (xrpc-server/src/auth.ts cryptoVerifySignatureWithKey passes
     ;; allowMalleableSig: true), so jwt/verify defaults :allow-malleable? true.
     (let [kp (result-of (crypto/generate "ES256K"))
           did (crypto/did kp)
           token (result-of (jwt/sign kp {:typ "JWT"} claims))
           [h p sig] (str/split token #"\.")
           ^bytes sig-bytes (runtime.crypto/base64url-decode sig)
           s (BigInteger. 1 (Arrays/copyOfRange sig-bytes 32 64))
           ^bytes high-s (.toByteArray (.subtract k256-n s))
           ;; left-pad/strip to exactly 32 bytes
           high-s (let [out (byte-array 32)
                        len (alength high-s)]
                    (if (<= len 32)
                      (System/arraycopy high-s 0 out (- 32 len) len)
                      (System/arraycopy high-s (- len 32) out 0 32))
                    out)
           malleated (byte-array 64)]
       (System/arraycopy sig-bytes 0 malleated 0 32)
       (System/arraycopy high-s 0 malleated 32 32)
       (let [bad-token (str h "." p "." (runtime.crypto/base64url-encode malleated))]
         (testing "high-S signature verifies by default (service-JWT parity)"
           (is (nil? (:error (result-of (jwt/verify bad-token did))))))
         (testing "high-S signature is rejected with :allow-malleable? false"
           (is (= "BadJwtSignature"
                  (:error (result-of (jwt/verify bad-token did :allow-malleable? false))))))))))
