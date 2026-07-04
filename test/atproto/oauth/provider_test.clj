(ns atproto.oauth.provider-test
  "Provider unit tests over in-memory stores (metadata, PAR, the authorize
  state machine, the token endpoint, and verify-access-token). The full
  HTTP conformance loop with atproto.oauth.client lives in
  atproto.oauth.provider.conformance-test."
  (:require [clojure.test :refer :all]
            [atproto.runtime.crypto :as crypto]
            [atproto.runtime.http :as http]
            [atproto.runtime.jwt :as jwt]
            [atproto.test-support.http :as fake-http]
            [atproto.oauth.client.dpop :as client-dpop]
            [atproto.oauth.provider :as provider]
            [atproto.oauth.provider.dpop :as dpop]
            [atproto.oauth.provider.store :as store]))

(def issuer "https://as.test")
(def client-id "https://app.test/client-metadata.json")
(def redirect-uri "https://app.test/oauth/callback")
(def did "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa")

(def client-metadata
  {:client_id client-id
   :redirect_uris [redirect-uri]
   :grant_types ["authorization_code" "refresh_token"]
   :response_types ["code"]
   :scope "atproto transition:generic"
   :token_endpoint_auth_method "none"
   :application_type "web"
   :dpop_bound_access_tokens true})

(defn- test-keyset []
  {:keys [(jwt/generate-jwk {:alg "ES256" :kid "sign-1"})]})

(defn- make-provider [& {:as overrides}]
  (provider/create (merge {:issuer issuer
                           :keyset (test-keyset)
                           :stores (store/memory-stores
                                    :accounts [{:sub did :handle "alice.test" :password "pw"}])}
                          overrides)))

;; The client metadata document is resolved over (faked) HTTP so the
;; client is a normal third-party client (not a trusted first-party
;; ClientStore client), which exercises the consent path.
(use-fixtures :each
  (fn [f]
    (let [{:keys [handler]} (fake-http/routed [[client-id (fake-http/json-response client-metadata)]])]
      (with-redefs [http/handle-request handler]
        (f)))))

;; A client-side DPoP proof for a request to the provider.
(defn- proof
  [dpop-key method url & {:keys [nonce access-token]}]
  (jwt/generate dpop-key
                {:typ "dpop+jwt" :alg "ES256" :jwk (jwt/public-jwk dpop-key)}
                (into {} (remove (comp nil? val))
                      {:jti (crypto/generate-nonce 12)
                       :htm (clojure.string/upper-case (name method))
                       :htu url
                       :iat (crypto/now)
                       :nonce nonce
                       :ath (when access-token
                              (crypto/base64url-encode (crypto/sha256 access-token)))})))

(deftest create-validation-test
  (is (= "InvalidIssuer" (:error (provider/create {:issuer "not a url" :keyset (test-keyset)}))))
  (is (= "InvalidIssuer" (:error (provider/create {:issuer "https://as.test/path"
                                                   :keyset (test-keyset)}))))
  (is (= "InvalidKeyset" (:error (provider/create {:issuer issuer :keyset {:keys []}}))))
  (testing "loopback http issuers are allowed for dev"
    (is (nil? (:error (provider/create {:issuer "http://localhost" :keyset (test-keyset)}))))))

(deftest metadata-test
  (let [md (provider/metadata (make-provider))]
    (is (= issuer (:issuer md)))
    (is (= (str issuer "/oauth/par") (:pushed_authorization_request_endpoint md)))
    (is (true? (:require_pushed_authorization_requests md)))
    (is (= ["code"] (:response_types_supported md)))
    (is (= ["S256"] (:code_challenge_methods_supported md)))
    (is (contains? (set (:token_endpoint_auth_methods_supported md)) "private_key_jwt"))
    (is (true? (:client_id_metadata_document_supported md))))
  (testing "extra metadata merges in"
    (is (= "x" (:custom (provider/metadata (make-provider :metadata {:custom "x"})))))))

(deftest jwks-test
  (let [pub (provider/jwks (make-provider))]
    (is (= 1 (count (:keys pub))))
    (is (nil? (:d (first (:keys pub)))) "private key material is not exposed")))

;; -----------------------------------------------------------------------------
;; PAR + authorize + token, driven through the provider API
;; -----------------------------------------------------------------------------

(defn- do-par
  "Run a PAR through the provider, handling the DPoP nonce challenge."
  [prov dpop-key params]
  (let [url (str issuer "/oauth/par")
        first-try @(provider/pushed-authorization-request
                    prov {:params params :dpop-proof (proof dpop-key :post url)
                          :method :post :url url})]
    (if (= "use_dpop_nonce" (:error first-try))
      @(provider/pushed-authorization-request
        prov {:params params
              :dpop-proof (proof dpop-key :post url :nonce (:dpop-nonce first-try))
              :method :post :url url})
      first-try)))

(def pkce (crypto/generate-pkce 48))

(defn- par-params [& {:as overrides}]
  (merge {:client_id client-id
          :response_type "code"
          :redirect_uri redirect-uri
          :scope "atproto"
          :state "app-state"
          :code_challenge (:challenge pkce)
          :code_challenge_method "S256"}
         overrides))

(deftest par-test
  (let [prov (make-provider)
        dpop-key (client-dpop/generate-key)]
    (testing "the first PAR without a nonce is challenged"
      (let [result @(provider/pushed-authorization-request
                     prov {:params (par-params)
                           :dpop-proof (proof dpop-key :post (str issuer "/oauth/par"))
                           :method :post :url (str issuer "/oauth/par")})]
        (is (= "use_dpop_nonce" (:error result)))
        (is (string? (:dpop-nonce result)))))
    (testing "PAR with the nonce succeeds"
      (let [result (do-par prov dpop-key (par-params))]
        (is (nil? (:error result)))
        (is (clojure.string/starts-with? (:request-uri result)
                                         "urn:ietf:params:oauth:request_uri:"))
        (is (= provider/par-request-ttl (:expires-in result)))))
    (testing "unregistered redirect_uri is rejected"
      (is (= "invalid_request"
             (:error (do-par prov dpop-key (par-params :redirect_uri "https://evil.test/cb"))))))
    (testing "PKCE is required"
      (is (= "invalid_request"
             (:error (do-par prov dpop-key (dissoc (par-params) :code_challenge))))))
    (testing "scope beyond the client registration is rejected"
      (is (= "invalid_scope"
             (:error (do-par prov dpop-key (par-params :scope "atproto transition:chat.bsky"))))))))

(defn- authorize-to-code
  "PAR -> sign-in -> accept, returning the redirect params (with :code)."
  [prov dpop-key device-id & {:keys [remember?] :or {remember? true}}]
  (let [{:keys [request-uri]} (do-par prov dpop-key (par-params))
        signed @(provider/complete-sign-in prov {:device-id device-id
                                                 :request-uri request-uri
                                                 :credentials {:identifier "alice.test"
                                                               :password "pw"}
                                                 :remember? remember?})
        accepted @(provider/accept prov {:device-id device-id
                                         :request-uri request-uri
                                         :sub did})]
    {:request-uri request-uri :sign-in signed :accepted accepted}))

(deftest authorize-state-machine-test
  (let [prov (make-provider)
        dpop-key (client-dpop/generate-key)
        device-id "dev-1"]
    (testing "no device session -> login prompt"
      (let [{:keys [request-uri]} (do-par prov dpop-key (par-params))
            result @(provider/authorize prov {:client-id client-id
                                              :request-uri request-uri
                                              :device-id device-id})]
        (is (= :login (:prompt result)))
        (is (= client-id (get-in result [:request :client-id])))))
    (testing "sign-in binds the account and prompts for consent"
      (let [{:keys [request-uri sign-in]} (authorize-to-code prov dpop-key device-id)]
        (is (= :consent (:prompt sign-in)))
        (is (= did (get-in sign-in [:request :sub])))))
    (testing "bad credentials are rejected"
      (let [{:keys [request-uri]} (do-par prov dpop-key (par-params))
            result @(provider/complete-sign-in prov {:device-id device-id
                                                     :request-uri request-uri
                                                     :credentials {:identifier "alice.test"
                                                                   :password "wrong"}})]
        (is (= "invalid_credentials" (:error result)))))
    (testing "accept issues an authorization code redirect"
      (let [{:keys [accepted]} (authorize-to-code prov dpop-key "dev-2")]
        (is (= redirect-uri (get-in accepted [:redirect :uri])))
        (is (string? (get-in accepted [:redirect :params :code])))
        (is (= "app-state" (get-in accepted [:redirect :params :state])))
        (is (= issuer (get-in accepted [:redirect :params :iss])))))
    (testing "reject issues an access_denied redirect"
      (let [{:keys [request-uri]} (do-par prov dpop-key (par-params))
            _ @(provider/complete-sign-in prov {:device-id "dev-3" :request-uri request-uri
                                                :credentials {:identifier "alice.test" :password "pw"}})
            result @(provider/reject prov {:request-uri request-uri})]
        (is (= "access_denied" (get-in result [:redirect :params :error])))))
    (testing "a remembered account skips the login prompt on the next request"
      (authorize-to-code prov dpop-key "dev-remember")
      (let [{:keys [request-uri]} (do-par prov dpop-key (par-params))
            result @(provider/authorize prov {:client-id client-id
                                              :request-uri request-uri
                                              :device-id "dev-remember"})]
        ;; consent already recorded -> straight to a code redirect
        (is (:redirect result))))))

(defn- do-token
  [prov dpop-key params]
  (let [url (str issuer "/oauth/token")
        first-try @(provider/token prov {:params params
                                         :dpop-proof (proof dpop-key :post url)
                                         :method :post :url url})]
    (if (= "use_dpop_nonce" (:error first-try))
      @(provider/token prov {:params params
                             :dpop-proof (proof dpop-key :post url :nonce (:dpop-nonce first-try))
                             :method :post :url url})
      first-try)))

(deftest token-code-grant-test
  (let [prov (make-provider)
        dpop-key (client-dpop/generate-key)
        {:keys [accepted]} (authorize-to-code prov dpop-key "dev-token")
        code (get-in accepted [:redirect :params :code])]
    (testing "authorization_code grant issues DPoP-bound tokens"
      (let [result (do-token prov dpop-key {:grant_type "authorization_code"
                                            :client_id client-id
                                            :code code
                                            :redirect_uri redirect-uri
                                            :code_verifier (:verifier pkce)})]
        (is (nil? (:error result)))
        (is (= "DPoP" (:token_type result)))
        (is (= did (:sub result)))
        (is (string? (:access_token result)))
        (is (string? (:refresh_token result)))
        (testing "the access token is a DPoP-bound at+jwt"
          (let [{:keys [header claims]} (jwt/parse (:access_token result))]
            (is (= "at+jwt" (:typ header)))
            (is (= did (:sub claims)))
            (is (= (dpop/jwk-thumbprint (jwt/public-jwk dpop-key))
                   (get-in claims [:cnf :jkt])))))))
    (testing "the code is single-use"
      (is (= "invalid_grant"
             (:error (do-token prov dpop-key {:grant_type "authorization_code"
                                              :client_id client-id
                                              :code code
                                              :redirect_uri redirect-uri
                                              :code_verifier (:verifier pkce)})))))))

(deftest token-pkce-mismatch-test
  (let [prov (make-provider)
        dpop-key (client-dpop/generate-key)
        {:keys [accepted]} (authorize-to-code prov dpop-key "dev-pkce")
        code (get-in accepted [:redirect :params :code])]
    (is (= "invalid_grant"
           (:error (do-token prov dpop-key {:grant_type "authorization_code"
                                            :client_id client-id
                                            :code code
                                            :redirect_uri redirect-uri
                                            :code_verifier "wrong-verifier"}))))))

(deftest token-dpop-binding-test
  (testing "a token request with a different DPoP key than the PAR is rejected"
    (let [prov (make-provider)
          par-key (client-dpop/generate-key)
          other-key (client-dpop/generate-key)
          {:keys [accepted]} (authorize-to-code prov par-key "dev-bind")
          code (get-in accepted [:redirect :params :code])]
      (is (= "invalid_grant"
             (:error (do-token prov other-key {:grant_type "authorization_code"
                                               :client_id client-id
                                               :code code
                                               :redirect_uri redirect-uri
                                               :code_verifier (:verifier pkce)})))))))

(deftest refresh-rotation-and-reuse-test
  (let [prov (make-provider)
        dpop-key (client-dpop/generate-key)
        {:keys [accepted]} (authorize-to-code prov dpop-key "dev-refresh")
        code (get-in accepted [:redirect :params :code])
        tokens (do-token prov dpop-key {:grant_type "authorization_code"
                                        :client_id client-id
                                        :code code
                                        :redirect_uri redirect-uri
                                        :code_verifier (:verifier pkce)})
        rt1 (:refresh_token tokens)
        refreshed (do-token prov dpop-key {:grant_type "refresh_token"
                                           :client_id client-id
                                           :refresh_token rt1})]
    (testing "refresh rotates the tokens"
      (is (nil? (:error refreshed)))
      (is (string? (:refresh_token refreshed)))
      (is (not= rt1 (:refresh_token refreshed))))
    (testing "reusing the old refresh token is detected and kills the session"
      (is (= "invalid_grant"
             (:error (do-token prov dpop-key {:grant_type "refresh_token"
                                              :client_id client-id
                                              :refresh_token rt1}))))
      ;; the rotated token is now dead too (reuse invalidated the chain)
      (is (= "invalid_grant"
             (:error (do-token prov dpop-key {:grant_type "refresh_token"
                                              :client_id client-id
                                              :refresh_token (:refresh_token refreshed)})))))))

;; -----------------------------------------------------------------------------
;; verify-access-token
;; -----------------------------------------------------------------------------

(defn- issue-access-token
  [prov dpop-key]
  (let [{:keys [accepted]} (authorize-to-code prov dpop-key "dev-verify")
        code (get-in accepted [:redirect :params :code])]
    (do-token prov dpop-key {:grant_type "authorization_code"
                             :client_id client-id
                             :code code
                             :redirect_uri redirect-uri
                             :code_verifier (:verifier pkce)})))

(deftest verify-access-token-test
  (let [prov (make-provider)
        dpop-key (client-dpop/generate-key)
        tokens (issue-access-token prov dpop-key)
        access-token (:access_token tokens)
        resource-url "https://pds.test/xrpc/com.atproto.repo.createRecord"]
    (testing "a valid token with a matching DPoP proof verifies"
      (let [result @(provider/verify-access-token
                     prov {:token access-token
                           :dpop-proof (proof dpop-key :post resource-url :access-token access-token)
                           :method :post
                           :url resource-url})]
        (is (nil? (:error result)))
        (is (= did (:sub result)))
        (is (= client-id (:client-id result)))
        (is (string? (:token-id result)))))
    (testing "a token presented with a different DPoP key is rejected"
      (let [other-key (client-dpop/generate-key)
            result @(provider/verify-access-token
                     prov {:token access-token
                           :dpop-proof (proof other-key :post resource-url :access-token access-token)
                           :method :post
                           :url resource-url})]
        (is (contains? #{"InvalidDpopProof"} (:error result)))
        (is (= 401 (:status result)))))
    (testing "a proof whose ath does not match the token is rejected"
      (let [result @(provider/verify-access-token
                     prov {:token access-token
                           :dpop-proof (proof dpop-key :post resource-url :access-token "other")
                           :method :post
                           :url resource-url})]
        (is (= "InvalidDpopProof" (:error result)))))
    (testing "a garbage token is rejected"
      (is (= "InvalidToken"
             (:error @(provider/verify-access-token
                       prov {:token "not.a.jwt"
                             :dpop-proof (proof dpop-key :post resource-url :access-token "not.a.jwt")
                             :method :post :url resource-url})))))))

(deftest verify-access-token-light-mode-test
  (testing ":light mode rejects a revoked token even though the JWT is valid"
    (let [prov (make-provider :access-token-mode :light)
          dpop-key (client-dpop/generate-key)
          tokens (issue-access-token prov dpop-key)
          access-token (:access_token tokens)
          resource-url "https://pds.test/xrpc/x"
          token-id (:jti (:claims (jwt/parse access-token)))]
      (is (nil? (:error @(provider/verify-access-token
                          prov {:token access-token
                                :dpop-proof (proof dpop-key :post resource-url :access-token access-token)
                                :method :post :url resource-url}))))
      (store/delete-token! (get-in prov [:stores :token-store]) token-id)
      (is (= "InvalidToken"
             (:error @(provider/verify-access-token
                       prov {:token access-token
                             :dpop-proof (proof dpop-key :post resource-url :access-token access-token)
                             :method :post :url resource-url})))))))

(deftest revoke-test
  (let [prov (make-provider)
        dpop-key (client-dpop/generate-key)
        tokens (issue-access-token prov dpop-key)]
    (testing "revoke by refresh token deletes the row (always succeeds)"
      (is (= {} @(provider/revoke prov {:params {:token (:refresh_token tokens)}})))
      (is (= "invalid_grant"
             (:error (do-token prov dpop-key {:grant_type "refresh_token"
                                              :client_id client-id
                                              :refresh_token (:refresh_token tokens)})))))
    (testing "revoking an unknown token is not an error"
      (is (= {} @(provider/revoke prov {:params {:token "nope"}}))))))
