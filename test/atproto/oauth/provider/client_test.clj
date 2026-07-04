(ns atproto.oauth.provider.client-test
  (:require [clojure.test :refer :all]
            [atproto.runtime.http :as http]
            [atproto.runtime.crypto :as crypto]
            [atproto.runtime.jwt :as jwt]
            [atproto.test-support.http :as fake-http]
            [atproto.oauth.provider.client :as client]
            [atproto.oauth.provider.store :as store]))

(def client-id "https://app.test/client-metadata.json")
(def issuer "https://as.test")

(def valid-metadata
  {:client_id client-id
   :client_name "Test app"
   :redirect_uris ["https://app.test/oauth/callback"]
   :grant_types ["authorization_code" "refresh_token"]
   :response_types ["code"]
   :scope "atproto transition:generic"
   :token_endpoint_auth_method "none"
   :application_type "web"
   :dpop_bound_access_tokens true})

(deftest fetch-validate-and-cache-test
  (let [registry (client/registry)
        {:keys [handler requests]} (fake-http/routed
                                    [[client-id (fake-http/json-response valid-metadata)]])]
    (with-redefs [http/handle-request handler]
      (let [resolved (deref (client/get-client registry client-id) 1000 ::timeout)]
        (is (nil? (:error resolved)))
        (is (= client-id (:client-id resolved)))
        (is (= "Test app" (get-in resolved [:metadata :client_name])))
        (is (nil? (:trusted? resolved))))
      (testing "second resolution hits the cache"
        (deref (client/get-client registry client-id) 1000 ::timeout)
        (is (= 1 (count @requests))))
      (testing "the cache expires after the TTL"
        (let [real-now crypto/now]
          (with-redefs [crypto/now #(+ (real-now) (* 2 client/metadata-ttl))
                        http/handle-request handler]
            (deref (client/get-client registry client-id) 1000 ::timeout)
            (is (= 2 (count @requests)))))))))

(deftest fetch-failure-test
  (let [registry (client/registry)
        {:keys [handler]} (fake-http/routed [])]
    (with-redefs [http/handle-request handler]
      (is (= "InvalidClientMetadata"
             (:error (deref (client/get-client registry client-id) 1000 ::timeout))))))
  (testing "non-https client ids are rejected outright"
    (is (= "InvalidClientId"
           (:error (deref (client/get-client (client/registry) "ftp://nope") 1000 ::timeout))))))

(deftest metadata-validation-test
  (let [check (fn [metadata] (:error (client/validate-client-metadata client-id metadata)))]
    (is (nil? (check valid-metadata)))
    (testing "client_id must match the document"
      (is (= "InvalidClientMetadata" (check (assoc valid-metadata :client_id "https://other.test/x")))))
    (testing "redirect_uris required"
      (is (= "InvalidClientMetadata" (check (dissoc valid-metadata :redirect_uris))))
      (is (= "InvalidClientMetadata" (check (assoc valid-metadata :redirect_uris [])))))
    (testing "web clients need https redirects"
      (is (= "InvalidClientMetadata"
             (check (assoc valid-metadata :redirect_uris ["http://app.test/cb"])))))
    (testing "native clients accept custom schemes and loopback, nothing else"
      (let [native (assoc valid-metadata :application_type "native")]
        (is (nil? (check (assoc native :redirect_uris ["com.example.app:/callback"]))))
        (is (nil? (check (assoc native :redirect_uris ["http://127.0.0.1/cb"]))))
        (is (nil? (check (assoc native :redirect_uris ["http://[::1]/cb"]))))
        (is (= "InvalidClientMetadata"
               (check (assoc native :redirect_uris ["http://localhost/cb"]))))
        (is (= "InvalidClientMetadata"
               (check (assoc native :redirect_uris ["https://app.test/cb"]))))))
    (testing "grant/response types"
      (is (= "InvalidClientMetadata" (check (assoc valid-metadata :grant_types ["refresh_token"]))))
      (is (= "InvalidClientMetadata"
             (check (assoc valid-metadata :grant_types ["authorization_code" "implicit"]))))
      (is (= "InvalidClientMetadata" (check (assoc valid-metadata :response_types ["token"])))))
    (testing "scope must include atproto"
      (is (= "InvalidClientMetadata" (check (assoc valid-metadata :scope "transition:generic"))))
      (is (= "InvalidClientMetadata" (check (dissoc valid-metadata :scope)))))
    (testing "dpop_bound_access_tokens is mandatory"
      (is (= "InvalidClientMetadata" (check (dissoc valid-metadata :dpop_bound_access_tokens))))
      (is (= "InvalidClientMetadata" (check (assoc valid-metadata :dpop_bound_access_tokens false)))))
    (testing "auth methods"
      (is (= "InvalidClientMetadata"
             (check (assoc valid-metadata :token_endpoint_auth_method "client_secret_basic"))))
      (is (= "InvalidClientMetadata"
             (check (assoc valid-metadata :token_endpoint_auth_method "private_key_jwt"))))
      (is (nil? (check (assoc valid-metadata
                              :token_endpoint_auth_method "private_key_jwt"
                              :jwks {:keys []})))))))

(deftest loopback-client-test
  (testing "bare loopback id synthesizes default metadata"
    (let [resolved (deref (client/get-client (client/registry) "http://localhost")
                          1000 ::timeout)]
      (is (true? (:loopback? resolved)))
      (let [m (:metadata resolved)]
        (is (= "http://localhost" (:client_id m)))
        (is (= ["http://127.0.0.1/" "http://[::1]/"] (:redirect_uris m)))
        (is (= "atproto" (:scope m)))
        (is (= "native" (:application_type m)))
        (is (= "none" (:token_endpoint_auth_method m)))
        (is (true? (:dpop_bound_access_tokens m))))))
  (testing "query params override redirect_uri and scope"
    (let [id "http://localhost?redirect_uri=http://127.0.0.1:8080/cb&scope=atproto%20transition:generic"
          resolved (deref (client/get-client (client/registry) id) 1000 ::timeout)]
      (is (= ["http://127.0.0.1:8080/cb"] (get-in resolved [:metadata :redirect_uris])))
      (is (= "atproto transition:generic" (get-in resolved [:metadata :scope])))))
  (testing "a path or a non-loopback redirect_uri is rejected"
    (is (= "InvalidClientId"
           (:error (deref (client/get-client (client/registry) "http://localhost/app")
                          1000 ::timeout))))
    (is (= "InvalidClientMetadata"
           (:error (deref (client/get-client (client/registry)
                                             "http://localhost?redirect_uri=http://localhost/cb")
                          1000 ::timeout))))))

(deftest redirect-uri-matching-test
  (let [web {:metadata valid-metadata}
        native {:metadata (assoc valid-metadata
                                 :application_type "native"
                                 :redirect_uris ["http://127.0.0.1/cb"])}]
    (is (client/redirect-uri-allowed? web "https://app.test/oauth/callback"))
    (is (not (client/redirect-uri-allowed? web "https://app.test/other")))
    (testing "loopback redirects match on any port for native clients"
      (is (client/redirect-uri-allowed? native "http://127.0.0.1:49152/cb"))
      (is (not (client/redirect-uri-allowed? native "http://127.0.0.1:49152/other")))
      (is (not (client/redirect-uri-allowed? native "http://[::1]:49152/cb"))))))

(deftest client-store-test
  (let [registry (client/registry :client-store (store/memory-client-store
                                                 {client-id valid-metadata}))
        {:keys [handler requests]} (fake-http/routed [])]
    (with-redefs [http/handle-request handler]
      (let [resolved (deref (client/get-client registry client-id) 1000 ::timeout)]
        (is (nil? (:error resolved)))
        (is (true? (:trusted? resolved)))
        (is (empty? @requests) "no HTTP fetch for first-party clients")))))

(deftest jwks-uri-fetch-test
  (let [jwk (jwt/generate-jwk {:alg "ES256" :kid "client-key"})
        jwks {:keys [(jwt/public-jwk jwk)]}
        metadata (assoc valid-metadata
                        :token_endpoint_auth_method "private_key_jwt"
                        :jwks_uri "https://app.test/jwks.json")
        registry (client/registry)
        {:keys [handler]} (fake-http/routed
                           [[client-id (fake-http/json-response metadata)]
                            ["/jwks.json" (fake-http/json-response jwks)]])]
    (with-redefs [http/handle-request handler]
      (let [resolved (deref (client/get-client registry client-id) 1000 ::timeout)]
        (is (nil? (:error resolved)))
        (is (= 1 (count (get-in resolved [:jwks :keys]))))))))

(deftest authenticate-client-test
  (let [jwk (jwt/generate-jwk {:alg "ES256" :kid "client-key"})
        client-entry {:client-id client-id
                      :metadata (assoc valid-metadata
                                       :token_endpoint_auth_method "private_key_jwt"
                                       :jwks {:keys [(jwt/public-jwk jwk)]})
                      :jwks {:keys [(jwt/public-jwk jwk)]}}
        replay-store (store/memory-replay-store)
        assertion (fn [claims]
                    (jwt/generate jwk {:alg "ES256" :kid "client-key"}
                                  (into {}
                                        (remove (comp nil? val))
                                        (merge {:iss client-id
                                                :sub client-id
                                                :aud issuer
                                                :jti (crypto/generate-nonce 16)
                                                :iat (crypto/now)
                                                :exp (+ (crypto/now) 60)}
                                               claims))))
        authenticate (fn [credentials]
                       (deref (client/authenticate-client
                               (client/registry) client-entry credentials
                               :issuer issuer
                               :replay-store replay-store)
                              1000 ::timeout))]
    (testing "public clients authenticate as none"
      (let [public {:client-id client-id :metadata valid-metadata}]
        (is (= {:method "none"}
               (deref (client/authenticate-client (client/registry) public {}
                                                  :issuer issuer
                                                  :replay-store replay-store)
                      1000 ::timeout)))))
    (testing "valid private_key_jwt assertion"
      (is (= {:method "private_key_jwt"}
             (authenticate {:client-assertion-type client/client-assertion-jwt-bearer
                            :client-assertion (assertion {})}))))
    (testing "jti replay is rejected"
      (let [a (assertion {:jti "fixed-jti"})]
        (is (= {:method "private_key_jwt"}
               (authenticate {:client-assertion-type client/client-assertion-jwt-bearer
                              :client-assertion a})))
        (is (= "invalid_client"
               (:error (authenticate {:client-assertion-type client/client-assertion-jwt-bearer
                                      :client-assertion a}))))))
    (testing "claim mismatches are rejected"
      (doseq [bad [{:iss "https://other.test/c"}
                   {:sub "https://other.test/c"}
                   {:aud "https://other-as.test"}
                   {:exp nil}
                   {:jti nil}]]
        (is (= "invalid_client"
               (:error (authenticate {:client-assertion-type client/client-assertion-jwt-bearer
                                      :client-assertion (assertion bad)})))
            (pr-str bad))))
    (testing "assertions signed by a foreign key are rejected"
      (let [foreign (jwt/generate-jwk {:alg "ES256" :kid "client-key"})
            forged (jwt/generate foreign {:alg "ES256" :kid "client-key"}
                                 {:iss client-id :sub client-id :aud issuer
                                  :jti (crypto/generate-nonce 16)
                                  :iat (crypto/now) :exp (+ (crypto/now) 60)})]
        (is (= "invalid_client"
               (:error (authenticate {:client-assertion-type client/client-assertion-jwt-bearer
                                      :client-assertion forged}))))))
    (testing "missing assertion"
      (is (= "invalid_client"
             (:error (authenticate {})))))))
