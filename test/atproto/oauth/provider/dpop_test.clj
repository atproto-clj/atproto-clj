(ns atproto.oauth.provider.dpop-test
  "Table-driven DPoP verification tests: proofs are generated exactly as
  the SDK's own OAuth client generates them
  (atproto.oauth.client.dpop dpop-wrapper), then each claim is mutated."
  (:require [clojure.test :refer :all]
            [atproto.runtime.crypto :as crypto]
            [atproto.runtime.jwt :as jwt]
            [atproto.crypto :as atproto-crypto]
            [atproto.oauth.client.dpop :as client-dpop]
            [atproto.oauth.provider.dpop :as dpop]
            [atproto.oauth.provider.store :as store]))

(def url "https://as.test/oauth/par")

(def ^:dynamic *verifier* nil)

(defn- with-verifier
  [f]
  (binding [*verifier* (dpop/create {:replay-store (store/memory-replay-store)})]
    (f)))

(use-fixtures :each with-verifier)

(def dpop-key (client-dpop/generate-key))

(defn- proof
  "A client-style DPoP proof; claim overrides with nil values remove the
  claim. :header-jwk overrides the embedded JWK."
  [& {:keys [header-jwk header-typ] :as overrides}]
  (let [claims (merge {:iss "https://app.test/client-metadata.json"
                       :jti (crypto/generate-nonce 12)
                       :htm "POST"
                       :htu url
                       :iat (crypto/now)}
                      (dissoc overrides :header-jwk :header-typ))]
    (jwt/generate dpop-key
                  {:typ (or header-typ "dpop+jwt")
                   :alg "ES256"
                   :jwk (or header-jwk (jwt/public-jwk dpop-key))}
                  (into {} (remove (comp nil? val)) claims))))

(defn- check
  [proof-str & {:as request}]
  (dpop/check-proof *verifier* (merge {:proof proof-str :method :post :url url}
                                      request)))

(deftest valid-proof-test
  (let [result (check (proof))]
    (is (nil? (:error result)))
    (is (= (dpop/jwk-thumbprint (jwt/public-jwk dpop-key)) (:jkt result)))
    (is (string? (:jti result)))))

(deftest htu-normalization-test
  (testing "request URLs with query/fragment match a bare htu"
    (is (nil? (:error (check (proof) :url (str url "?foo=bar#frag"))))))
  (testing "scheme/host case is normalized"
    (is (nil? (:error (check (proof :htu "HTTPS://AS.test/oauth/par"))))))
  (testing "path mismatch is rejected"
    (is (= "InvalidDpopProof" (:error (check (proof :htu "https://as.test/oauth/token"))))))
  (testing "host mismatch is rejected"
    (is (= "InvalidDpopProof" (:error (check (proof :htu "https://evil.test/oauth/par")))))))

(deftest htm-test
  (is (= "InvalidDpopProof" (:error (check (proof) :method :get))))
  (is (= "InvalidDpopProof" (:error (check (proof :htm nil))))))

(deftest iat-test
  (testing "stale proofs are rejected"
    (is (= "InvalidDpopProof"
           (:error (check (proof :iat (- (crypto/now) (inc dpop/max-proof-age-s))))))))
  (testing "far-future proofs are rejected"
    (is (= "InvalidDpopProof" (:error (check (proof :iat (+ (crypto/now) 120)))))))
  (testing "missing iat is rejected"
    (is (= "InvalidDpopProof" (:error (check (proof :iat nil)))))))

(deftest jti-replay-test
  (let [p (proof)]
    (is (nil? (:error (check p))))
    (let [again (check p)]
      (is (= "InvalidDpopProof" (:error again)))
      (is (re-find #"jti" (:message again)))))
  (testing "missing jti"
    (is (= "InvalidDpopProof" (:error (check (proof :jti nil)))))))

(deftest ath-test
  (let [token "the-access-token"
        good-ath (crypto/base64url-encode (crypto/sha256 token))]
    (testing "correct ath accepted"
      (is (nil? (:error (check (proof :ath good-ath) :access-token token)))))
    (testing "wrong ath rejected"
      (is (= "InvalidDpopProof"
             (:error (check (proof :ath "nope") :access-token token)))))
    (testing "missing ath rejected when the request carries a token"
      (is (= "InvalidDpopProof" (:error (check (proof) :access-token token)))))
    (testing "unexpected ath rejected"
      (is (= "InvalidDpopProof" (:error (check (proof :ath good-ath))))))))

(deftest nonce-test
  (testing "missing nonce with nonce-required? yields the challenge"
    (let [result (check (proof) :nonce-required? true)]
      (is (= "UseDpopNonce" (:error result)))
      (is (= (dpop/next-nonce *verifier*) (:dpop-nonce result)))))
  (testing "retrying with the server nonce succeeds (client retry path)"
    (let [nonce (dpop/next-nonce *verifier*)]
      (is (nil? (:error (check (proof :nonce nonce) :nonce-required? true))))))
  (testing "a foreign nonce value yields the challenge"
    (let [result (check (proof :nonce "made-up") :nonce-required? true)]
      (is (= "UseDpopNonce" (:error result)))
      (is (string? (:dpop-nonce result)))))
  (testing "a foreign nonce is rejected even when the nonce is optional"
    (is (= "UseDpopNonce" (:error (check (proof :nonce "made-up"))))))
  (testing "next-nonce is stable within a rotation window"
    (is (= (dpop/next-nonce *verifier*) (dpop/next-nonce *verifier*)))))

(deftest header-checks-test
  (testing "wrong typ"
    (is (= "InvalidDpopProof" (:error (check (proof :header-typ "JWT"))))))
  (testing "private key material in the embedded JWK"
    ;; Nimbus refuses to embed a private JWK, so sign the proof manually
    (let [kp (atproto-crypto/jwk->keypair dpop-key)
          leaky @(jwt/sign kp
                           {:typ "dpop+jwt" :jwk dpop-key}
                           {:jti (crypto/generate-nonce 12)
                            :htm "POST"
                            :htu url
                            :iat (crypto/now)})]
      (is (= "InvalidDpopProof" (:error (check leaky))))))
  (testing "signature must match the embedded JWK"
    (let [other (client-dpop/generate-key)]
      (is (= "InvalidDpopProof"
             (:error (check (proof :header-jwk (jwt/public-jwk other))))))))
  (testing "malformed proofs"
    (doseq [bad [nil "" "garbage" "a.b.c"]]
      (is (= "InvalidDpopProof" (:error (check bad))) (pr-str bad)))))

(deftest client-interceptor-round-trip-test
  ;; a proof produced by the actual client dpop interceptor verifies,
  ;; including the ath binding for resource requests
  (let [access-token "at-123"
        interceptor (client-dpop/interceptor {:iss "https://app.test/c.json"
                                              :dpop-key dpop-key})
        enter (:atproto.runtime.interceptor/enter interceptor)
        ctx (enter {:atproto.runtime.interceptor/request
                    {:method :post
                     :url url
                     :server-nonce (dpop/next-nonce *verifier*)
                     :headers {:authorization (str "DPoP " access-token)}}})
        proof-str (get-in ctx [:atproto.runtime.interceptor/request :headers :dpop])
        result (check proof-str :access-token access-token :nonce-required? true)]
    (is (string? proof-str))
    (is (nil? (:error result)))
    (is (= (dpop/jwk-thumbprint (jwt/public-jwk dpop-key)) (:jkt result)))))
