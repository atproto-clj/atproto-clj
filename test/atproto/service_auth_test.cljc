(ns atproto.service-auth-test
  "Tests for atproto inter-service JWT minting and verification.

  Live verification (manual, run once before merge — not part of CI):

    ;; 1. getServiceAuth round-trip against a real PDS:
    (require '[atproto.credentials :as credentials]
             '[atproto.xrpc.client :as xrpc-client]
             '[atproto.service-auth :as service-auth])
    (def session @(credentials/create {:identifier \"<handle>\" :password \"<app-password>\"}))
    (def client (xrpc-client/init {:session session}))
    (def pds-did \"did:web:...\") ;; the PDS's service DID
    (def resp @(service-auth/get-service-auth
                client {:aud pds-did :lxm \"com.atproto.server.getSession\"}))
    ;; => {:token \"eyJ...\"}
    ;; 2. verify it locally with real DID resolution:
    @(service-auth/verify-jwt (:token resp)
                              {:aud nil
                               :lxm \"com.atproto.server.getSession\"
                               :get-signing-key (service-auth/did-signing-key-resolver {})})
    ;; => claims map with :iss = your account DID
    ;; 3. negative check: mint with a throwaway keypair and confirm a real
    ;;    relay/PDS rejects it with a BadJwtSignature-class 401.

  Cross-implementation interop: tokens minted by the TS reference verify
  here (see reference-fixture-verification-test below), and tokens minted
  by atproto.service-auth/create-jwt with the fixture private keys were
  verified by the reference verifyJwt (@atproto/xrpc-server 0.11.1) for
  both ES256K and ES256 on 2026-06-11 (script: jwt_fixtures.json _meta)."
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            #?(:clj [clojure.java.io :as io])
            [clojure.string :as str]
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.crypto :as runtime-crypto]
            [atproto.runtime.json :as json]
            [atproto.runtime.jwt :as jwt]
            [atproto.crypto :as crypto]
            [atproto.service-auth :as service-auth]
            [atproto.xrpc.client :as xrpc-client]))

(defn- result-of
  [async-val]
  #?(:clj (deref async-val 1000 ::timeout)))

(def iss "did:example:alice")
(def aud "did:example:bob")
(def lxm "com.atproto.repo.createRecord")

#?(:clj
   (defn- keypair [alg]
     (result-of (crypto/generate alg))))

(defn- stub-signing-key
  "A :get-signing-key fn that always yields this did:key."
  [did-key]
  (fn [_iss _force-refresh? cb] (cb {:key did-key})))

#?(:clj
   (defn- verify
     [token opts-map]
     (result-of (service-auth/verify-jwt token opts-map))))

;; -----------------------------------------------------------------------------
;; Mint & verify round-trip
;; -----------------------------------------------------------------------------

#?(:clj
   (deftest mint-verify-round-trip-test
     (doseq [alg ["ES256" "ES256K"]]
       (let [kp (keypair alg)
             get-key (stub-signing-key (crypto/did kp))]
         (testing (str alg " round-trip with lxm")
           (let [{:keys [token]} (result-of (service-auth/create-jwt
                                             {:iss iss :aud aud :keypair kp :lxm lxm}))
                 claims (verify token {:aud aud :lxm lxm :get-signing-key get-key})]
             (is (string? token))
             (is (= iss (:iss claims)))
             (is (= aud (:aud claims)))
             (is (= lxm (:lxm claims)))
             (is (re-matches #"[0-9a-f]{32}" (:jti claims)))))
         (testing (str alg " exp defaults to (now, now+60]")
           (let [{:keys [token]} (result-of (service-auth/create-jwt
                                             {:iss iss :aud aud :keypair kp :lxm nil}))
                 claims (verify token {:aud nil :lxm nil :get-signing-key get-key})
                 now (runtime-crypto/now)]
             (is (nil? (:lxm claims)))
             (is (< now (:exp claims)))
             (is (<= (:exp claims) (+ now 60)))))
         (testing (str alg " auth-headers wraps the token as a Bearer header")
           (let [{:keys [headers]} (result-of (service-auth/auth-headers
                                               {:iss iss :aud aud :keypair kp :lxm lxm}))]
             (is (str/starts-with? (:authorization headers) "Bearer "))))))))

#?(:clj
   (deftest create-jwt-param-validation-test
     (let [kp (keypair "ES256")]
       (is (= "InvalidServiceJwtParams"
              (:error (result-of (service-auth/create-jwt
                                  {:iss "not-a-did" :aud aud :keypair kp})))))
       (is (= "InvalidServiceJwtParams"
              (:error (result-of (service-auth/create-jwt {:iss iss :aud aud}))))))))

;; -----------------------------------------------------------------------------
;; Verification failure modes
;; -----------------------------------------------------------------------------

#?(:clj
   (defn- mint
     "Mint a token with full control over headers and claims via jwt/sign."
     [kp headers claims]
     (result-of (jwt/sign kp headers claims))))

#?(:clj
   (deftest verify-failure-modes-test
     (let [kp (keypair "ES256K")
           get-key (stub-signing-key (crypto/did kp))
           now (runtime-crypto/now)
           base-claims {:iat now :iss iss :aud aud :exp (+ now 60) :lxm lxm}
           verify-error (fn [token opts-map]
                          (let [resp (verify token (merge {:get-signing-key get-key}
                                                          opts-map))]
                            (is (= 401 (:status resp)) (pr-str resp))
                            resp))]

       (testing "malformed JWT strings"
         (doseq [bad ["" "garbage" "a.b" "a.b.c.d" "!!.@@.##"]]
           (is (= "BadJwt" (:error (verify-error bad {}))))))

       (testing "forbidden typ headers"
         (doseq [typ ["at+jwt" "refresh+jwt" "dpop+jwt"]]
           (let [token (mint kp {:typ typ} base-claims)]
             (is (= "BadJwtType" (:error (verify-error token {})))))))

       (testing "poorly shaped payloads"
         (doseq [claims [(dissoc base-claims :aud)
                         (dissoc base-claims :exp)
                         (assoc base-claims :exp "soon")
                         (assoc base-claims :lxm 42)
                         (dissoc base-claims :iss)]]
           (let [token (mint kp {:typ "JWT"} claims)]
             (is (= "BadJwt" (:error (verify-error token {})))))))

       (testing "expired token"
         (let [token (mint kp {:typ "JWT"} (assoc base-claims :exp (- now 10)))]
           (is (= "JwtExpired" (:error (verify-error token {}))))))

       (testing "wrong audience"
         (let [token (mint kp {:typ "JWT"} base-claims)]
           (is (= "BadJwtAudience"
                  (:error (verify-error token {:aud "did:example:carol"}))))))

       (testing "wrong vs missing lxm have distinct messages"
         (let [wrong (verify-error (mint kp {:typ "JWT"} base-claims)
                                   {:lxm "com.atproto.repo.deleteRecord"})
               missing (verify-error (mint kp {:typ "JWT"} (dissoc base-claims :lxm))
                                     {:lxm lxm})]
           (is (= "BadJwtLexiconMethod" (:error wrong)))
           (is (= "BadJwtLexiconMethod" (:error missing)))
           (is (str/starts-with? (:message wrong) "bad jwt lexicon method"))
           (is (str/starts-with? (:message missing) "missing jwt lexicon method"))))

       (testing "non-DID iss"
         (doseq [bad-iss ["alice" "did:" "did:example:alice#a#b" "did:example:alice#"]]
           (let [token (mint kp {:typ "JWT"} (assoc base-claims :iss bad-iss))]
             (is (= "BadJwtIss" (:error (verify-error token {})))))))

       (testing "iss with a service fragment is accepted"
         (let [token (mint kp {:typ "JWT"}
                           (assoc base-claims :iss (str iss "#atproto_labeler")))]
           (is (= (str iss "#atproto_labeler")
                  (:iss (verify token {:get-signing-key get-key}))))))

       (testing "bad signature"
         (let [other (keypair "ES256K")
               token (mint other {:typ "JWT"} base-claims)]
           (is (= "BadJwtSignature" (:error (verify-error token {})))))))))

#?(:clj
   (deftest key-rotation-retry-test
     (let [kp (keypair "ES256")
           stale-kp (keypair "ES256")
           token (:token (result-of (service-auth/create-jwt
                                     {:iss iss :aud aud :keypair kp :lxm lxm})))
           calls (atom [])]

       (testing "stale key first, fresh key on force-refresh -> success"
         (reset! calls [])
         (let [get-key (fn [_iss force-refresh? cb]
                         (swap! calls conj force-refresh?)
                         (cb {:key (crypto/did (if force-refresh? kp stale-kp))}))
               claims (verify token {:get-signing-key get-key})]
           (is (= iss (:iss claims)))
           (is (= [false true] @calls))))

       (testing "same key twice -> BadJwtSignature without a second signature check"
         (reset! calls [])
         (let [sig-checks (atom 0)
               get-key (fn [_iss force-refresh? cb]
                         (swap! calls conj force-refresh?)
                         (cb {:key (crypto/did stale-kp)}))
               counting-verify (fn [did-key sig msg & {:keys [callback] :as opts}]
                                 (swap! sig-checks inc)
                                 (crypto/verify-did-sig did-key sig msg
                                                        (assoc opts :callback callback)))
               resp (verify token {:get-signing-key get-key
                                   :verify-signature counting-verify})]
           (is (= "BadJwtSignature" (:error resp)))
           (is (= 401 (:status resp)))
           (is (= [false true] @calls))
           (is (= 1 @sig-checks))))

       (testing "get-signing-key errors pass through"
         (let [get-key (fn [_iss _force-refresh? cb]
                         (cb {:error "UntrustedIss" :message "Untrusted issuer"}))
               resp (verify token {:get-signing-key get-key})]
           (is (= "UntrustedIss" (:error resp)))
           (is (= 401 (:status resp))))))))

;; -----------------------------------------------------------------------------
;; Cross-implementation fixtures (tokens minted by the TS reference)
;; -----------------------------------------------------------------------------

#?(:clj
   (def jwt-fixtures
     (json/read-str (slurp (io/resource "atproto/service_auth/jwt_fixtures.json")))))

#?(:clj
   (deftest reference-fixture-verification-test
     (doseq [{:keys [alg did_key private_key_hex tokens]} (:cases jwt-fixtures)]
       (let [get-key (stub-signing-key did_key)]
         (testing (str alg " reference token with lxm verifies")
           (let [{:keys [token claims]} (:with_lxm tokens)
                 verified (verify token {:aud (:aud jwt-fixtures)
                                         :lxm (:lxm jwt-fixtures)
                                         :get-signing-key get-key})]
             (is (= (dissoc claims :jti) (dissoc verified :jti)))
             (is (re-matches #"[0-9a-f]{32}" (:jti verified)))))
         (testing (str alg " reference token without lxm verifies (lxm check skipped)")
           (let [{:keys [token]} (:no_lxm tokens)]
             (is (= (:iss jwt-fixtures)
                    (:iss (verify token {:aud nil :lxm nil :get-signing-key get-key}))))))
         (testing (str alg " reference token without lxm fails a bound route")
           (let [{:keys [token]} (:no_lxm tokens)]
             (is (= "BadJwtLexiconMethod"
                    (:error (verify token {:lxm (:lxm jwt-fixtures)
                                           :get-signing-key get-key}))))))
         (testing (str alg " expired reference token")
           (let [{:keys [token]} (:expired tokens)]
             (is (= "JwtExpired"
                    (:error (verify token {:get-signing-key get-key}))))))
         (testing (str alg " fixture private key imports to the fixture did:key")
           (let [kp (result-of (crypto/import-private-key alg private_key_hex))]
             (is (= did_key (crypto/did kp)))))))))

;; -----------------------------------------------------------------------------
;; did-signing-key-resolver
;; -----------------------------------------------------------------------------

#?(:clj
   (defn- stub-resolve-did
     "An identity/resolve-did stand-in serving canned DID documents."
     [docs-by-did & [calls]]
     (fn [did & {:keys [force-refresh callback]}]
       (when calls (swap! calls conj [did (boolean force-refresh)]))
       (callback (if-let [doc (get docs-by-did did)]
                   {:did-doc doc}
                   {:error "DidNotFound"})))))

#?(:clj
   (deftest did-signing-key-resolver-test
     (let [kp (keypair "ES256K")
           label-kp (keypair "ES256")
           multikey (crypto/format-multikey "ES256K" (crypto/public-key kp))
           label-multikey (crypto/format-multikey "ES256" (crypto/public-key label-kp))
           did-doc {:id iss
                    :verificationMethod
                    [{:id (str iss "#atproto")
                      :type "Multikey"
                      :controller iss
                      :publicKeyMultibase multikey}
                     {:id (str iss "#atproto_label")
                      :type "Multikey"
                      :controller iss
                      :publicKeyMultibase label-multikey}]}
           resolve-did (stub-resolve-did {iss did-doc})
           resolver (service-auth/did-signing-key-resolver {:resolve-did resolve-did})
           get-key (fn [resolver iss force-refresh?]
                     (let [p (promise)]
                       (resolver iss force-refresh? #(deliver p %))
                       (deref p 1000 ::timeout)))]

       (testing "plain DID iss selects #atproto"
         (is (= {:key (crypto/did kp)} (get-key resolver iss false))))

       (testing "#atproto_labeler service fragment selects #atproto_label"
         (is (= {:key (crypto/did label-kp)}
                (get-key resolver (str iss "#atproto_labeler") false))))

       (testing "other service fragments still select #atproto"
         (is (= {:key (crypto/did kp)}
                (get-key resolver (str iss "#atproto_pds") false))))

       (testing "force-refresh is forwarded to resolve-did"
         (let [calls (atom [])
               resolver (service-auth/did-signing-key-resolver
                         {:resolve-did (stub-resolve-did {iss did-doc} calls)})]
           (get-key resolver iss true)
           (is (= [[iss true]] @calls))))

       (testing "legacy 2019 verification-method types convert"
         (let [eckey-doc {:id iss
                          :verificationMethod
                          [{:id (str iss "#atproto")
                            :type "EcdsaSecp256k1VerificationKey2019"
                            :controller iss
                            :publicKeyMultibase (crypto/bytes->multibase
                                                 :base58btc (crypto/public-key kp))}]}
               resolver (service-auth/did-signing-key-resolver
                         {:resolve-did (stub-resolve-did {iss eckey-doc})})]
           (is (= {:key (crypto/did kp)} (get-key resolver iss false)))))

       (testing "allowed-issuers"
         (let [resolver (service-auth/did-signing-key-resolver
                         {:resolve-did resolve-did
                          :allowed-issuers #{iss}})]
           (is (= {:key (crypto/did kp)} (get-key resolver iss false)))
           (let [resp (get-key resolver "did:example:mallory" false)]
             (is (= "UntrustedIss" (:error resp)))
             (is (= 401 (:status resp))))))

       (testing "unresolvable DID"
         (let [resp (get-key resolver "did:example:nobody" false)]
           (is (= "AuthenticationRequired" (:error resp)))
           (is (= "could not resolve iss did" (:message resp)))))

       (testing "missing verification method"
         (let [resolver (service-auth/did-signing-key-resolver
                         {:resolve-did (stub-resolve-did
                                        {iss {:id iss :verificationMethod []}})})
               resp (get-key resolver iss false)]
           (is (= "AuthenticationRequired" (:error resp)))
           (is (= "missing or bad key in did doc" (:message resp))))))))

;; -----------------------------------------------------------------------------
;; Session (client-side service auth)
;; -----------------------------------------------------------------------------

#?(:clj
   (deftest session-test
     (let [kp (keypair "ES256K")
           sess (service-auth/session {:iss iss
                                       :aud aud
                                       :keypair kp
                                       :service "https://service.example.com"})]

       (testing "session exposes the target service as :pds"
         (is (= "https://service.example.com" (:pds sess))))

       (testing "refresh-token invokes its callback exactly once, with the session"
         (let [calls (atom [])]
           (xrpc-client/refresh-token sess #(swap! calls conj %))
           (is (= [sess] @calls))))

       (testing "auth interceptor attaches a Bearer JWT with lxm bound to the request NSID"
         (let [interceptor (xrpc-client/auth-interceptor sess)
               final {::i/name ::final
                      ::i/enter (fn [ctx] (assoc ctx ::i/response (::i/request ctx)))}
               request @(i/execute
                         {::i/request {:method :post
                                       :url "https://service.example.com/xrpc/com.example.method"}
                          ::i/queue [interceptor final]})
               authz (get-in request [:headers :authorization])
               token (subs authz (count "Bearer "))
               claims (verify token {:aud aud
                                     :lxm "com.example.method"
                                     :get-signing-key (stub-signing-key (crypto/did kp))})]
           (is (str/starts-with? authz "Bearer "))
           (is (= iss (:iss claims)))
           (is (= "com.example.method" (:lxm claims)))))

       (testing "a 401 through delegate-auth-interceptor delivers (no hang)"
         (let [client (xrpc-client/init {:session sess})
               http-401 {::i/name ::http-401
                         ::i/enter (fn [ctx]
                                     (assoc ctx ::i/response
                                            {:status 401
                                             :headers {}
                                             :body "unauthorized"}))}
               response (deref (i/execute
                                {::i/request {:method :get
                                              :url "https://service.example.com/xrpc/com.example.method"}
                                 ::i/queue [(xrpc-client/delegate-auth-interceptor client)
                                            http-401]})
                               2000 ::timeout)]
           (is (not= ::timeout response))
           (is (= 401 (:status response))))))))
