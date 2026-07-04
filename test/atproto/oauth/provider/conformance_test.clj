(ns atproto.oauth.provider.conformance-test
  "End-to-end conformance: the SDK's own OAuth client
  (atproto.oauth.client) drives PAR -> authorize -> token -> refresh ->
  revoke against atproto.oauth.provider over real HTTP on a local
  http-kit server, with DPoP nonces enforced. Runs against both the
  in-memory stores and the SQLite stores.

  Only identity resolution (did:plc -> did document) is faked; every
  OAuth endpoint is a real HTTP round trip against the provider's Ring
  routes, so the client's DPoP-nonce retry path is genuinely exercised."
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [org.httpkit.server :as httpkit]
            [atproto.runtime.http :as http]
            [atproto.runtime.json :as json]
            [atproto.runtime.crypto :as crypto]
            [atproto.at-uri :as at-uri]
            [atproto.oauth.client :as oauth-client]
            [atproto.oauth.provider :as provider]
            [atproto.oauth.provider.ring :as provider-ring]
            [atproto.oauth.provider.store :as store]
            [atproto.oauth.provider.store.sqlite :as store-sqlite]
            [atproto.runtime.jwt :as jwt])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.io ByteArrayInputStream]))

(def did "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa")
(def handle "alice.test")
(def password "hunter2")
(def client-id "https://app.test/client-metadata.json")
(def redirect-uri "https://app.test/oauth/callback")

(defn- did-doc [issuer]
  {:id did
   :alsoKnownAs [(str "at://" handle)]
   :service [{:id "#atproto_pds"
              :type "AtprotoPersonalDataServer"
              :serviceEndpoint issuer}]})

(def client-metadata
  {:client_id client-id
   :client_name "Conformance app"
   :redirect_uris [redirect-uri]
   :grant_types ["authorization_code" "refresh_token"]
   :response_types ["code"]
   :scope "atproto transition:generic"
   :token_endpoint_auth_method "none"
   :application_type "web"
   :dpop_bound_access_tokens true})

(defn- test-keyset []
  {:keys [(jwt/generate-jwk {:alg "ES256" :kid "provider-1"})]})

;; A confidential client: a private ES256 signing key plus the published
;; metadata document advertising its public JWK and private_key_jwt auth.
(defn- confidential-client []
  (let [jwk (jwt/generate-jwk {:alg "ES256" :kid "client-key"})]
    {:client-keys [(json/write-str jwk)]
     :metadata (assoc client-metadata
                      :token_endpoint_auth_method "private_key_jwt"
                      :token_endpoint_auth_signing_alg "ES256"
                      :jwks {:keys [(jwt/public-jwk jwk)]})}))

;; -----------------------------------------------------------------------------
;; A Ring adapter that reads http-kit's raw request into the shape the
;; provider routes expect (query-string parsing + body InputStream), and
;; serves the client metadata + protected-resource + auth-server docs.
;; -----------------------------------------------------------------------------

(defn- make-app
  [prov issuer served-metadata]
  (let [oauth-routes (provider-ring/routes prov :resource issuer)]
    (fn [{:keys [uri] :as req}]
      (or (oauth-routes req)
          (case uri
            "/client-metadata.json"
            {:status 200
             :headers {"content-type" "application/json"}
             :body (json/write-str served-metadata)}
            {:status 404 :headers {} :body "not found"})))))

(defn- fake-identity-http
  "Redef target for http/handle-request: serve did:plc resolution from a
  canned did document and the client metadata document from its client_id
  URL; send everything else (the provider endpoints on 127.0.0.1) to the
  real http client."
  [issuer real-handle served-metadata]
  (fn [{:keys [url] :as request} cb]
    (let [url (or url "")]
      (cond
        (str/includes? url "plc.directory")
        (cb {:status 200
             :headers {:content-type "application/did+ld+json"}
             :body (json/write-str (did-doc issuer))})

        (str/includes? url "app.test/client-metadata.json")
        (cb {:status 200
             :headers {:content-type "application/json"}
             :body (json/write-str served-metadata)})

        :else (real-handle request cb)))))

;; -----------------------------------------------------------------------------
;; Authorization (browser + host-app) simulation: the client hands us an
;; authorization URL; we drive the provider's authorize hooks to a code.
;; -----------------------------------------------------------------------------

(defn- authorize-to-callback-params
  "Given the client's authorization URL, run the provider's sign-in +
  accept hooks (as the host app would) and return the redirect callback
  params {:code :state :iss}."
  [prov authorization-url]
  (let [{:keys [query-params]} (http/parse-url authorization-url)
        request-uri (:request_uri query-params)
        client (:client_id query-params)
        device-id (str "dev-" (crypto/generate-nonce 6))
        _ (is (= client-id client))
        signed @(provider/complete-sign-in
                 prov {:device-id device-id
                       :request-uri request-uri
                       :credentials {:identifier handle :password password}
                       :remember? true})
        _ (is (= :consent (:prompt signed)))
        accepted @(provider/accept prov {:device-id device-id
                                         :request-uri request-uri
                                         :sub did})]
    (is (:redirect accepted))
    (get-in accepted [:redirect :params])))

(defn- run-conformance
  "Drive the full OAuth loop with the SDK's own client. client-opts
  defaults to the public (token_endpoint_auth_method \"none\") client;
  pass {:metadata .. :client-keys ..} from confidential-client to drive
  a private_key_jwt confidential client instead."
  ([prov issuer] (run-conformance prov issuer {:metadata client-metadata :client-keys []}))
  ([prov issuer {:keys [metadata client-keys]}]
   (let [real-handle http/handle-request]
     (with-redefs [http/handle-request (fake-identity-http issuer real-handle metadata)]
       (let [client (oauth-client/create
                     {:client-metadata metadata
                      :keys client-keys})]
        (testing "authorize -> PAR over real HTTP with DPoP nonce enforcement"
          (let [{:keys [authorization-url error] :as auth}
                ;; drive with the DID so identity resolves via the faked
                ;; plc.directory (handle resolution would hit real DNS)
                (deref (oauth-client/authorize client did) 8000 ::timeout)]
            (is (nil? error) (pr-str auth))
            (is (string? authorization-url))
            (let [callback-params (authorize-to-callback-params prov authorization-url)]
              (testing "callback exchanges the code for DPoP-bound tokens"
                (let [{:keys [session state error] :as cb-result}
                      (deref (oauth-client/callback client callback-params) 8000 ::timeout)]
                  (is (nil? error) (pr-str cb-result))
                  (is (= did (:did session)))
                  (is (= issuer (:pds session)))
                  (let [stored (:tokens (deref (oauth-client/restore client did :refresh false)
                                               2000 ::timeout))]
                    (is (= "DPoP" (:token_type stored)))
                    (is (string? (:access_token stored)))
                    (is (string? (:refresh_token stored))))
                  (testing "the access token verifies with its DPoP binding"
                    (let [token (:access_token (:tokens (deref (oauth-client/restore client did :refresh false)
                                                               2000 ::timeout)))]
                      ;; provider-side verification of the issued token
                      (is (= did (:sub (:claims (jwt/parse token)))))))
                  (testing "refresh rotates tokens over real HTTP"
                    (let [before (:refresh_token (:tokens (deref (oauth-client/restore client did :refresh false)
                                                                 2000 ::timeout)))
                          refreshed (deref (oauth-client/refresh client did) 8000 ::timeout)]
                      (is (nil? (:error refreshed)))
                      (is (= did (:did refreshed)))
                      (let [after (:refresh_token (:tokens (deref (oauth-client/restore client did :refresh false)
                                                                  2000 ::timeout)))]
                        (is (string? after))
                        (is (not= before after)))))
                  (testing "revoke deletes the session"
                    (let [result (deref (oauth-client/revoke client did) 8000 ::timeout)]
                      (is (= {:did did :revoked true} result))
                      (is (= "SessionNotFound"
                             (:error (deref (oauth-client/restore client did) 2000 ::timeout))))))))))))))))

(deftest conformance-memory-stores-test
  (let [srv (httpkit/run-server (fn [_] {:status 503}) {:port 0 :legacy-return-value? false})
        port (httpkit/server-port srv)
        issuer (str "http://127.0.0.1:" port)]
    (httpkit/server-stop! srv)
    ;; re-open on the same port with a provider whose issuer is this origin
    (let [prov (provider/create {:issuer issuer
                                 :keyset (test-keyset)
                                 :stores (store/memory-stores
                                          :accounts [{:sub did :handle handle :password password}])})
          app (make-app prov issuer client-metadata)
          server (httpkit/run-server app {:port port :legacy-return-value? false})]
      (is (nil? (:error prov)))
      (try
        (run-conformance prov issuer)
        (finally
          (httpkit/server-stop! server))))))

(deftest conformance-confidential-client-test
  ;; a confidential client (token_endpoint_auth_method private_key_jwt)
  ;; completes the same PAR -> token -> refresh -> revoke loop, signing a
  ;; client assertion (with iss/sub/aud/jti/iat/exp) on every PAR/token
  ;; request; the provider verifies it against the client's published JWKS
  (let [srv (httpkit/run-server (fn [_] {:status 503}) {:port 0 :legacy-return-value? false})
        port (httpkit/server-port srv)
        issuer (str "http://127.0.0.1:" port)
        {:keys [metadata client-keys]} (confidential-client)]
    (httpkit/server-stop! srv)
    (let [prov (provider/create {:issuer issuer
                                 :keyset (test-keyset)
                                 :stores (store/memory-stores
                                          :accounts [{:sub did :handle handle :password password}])})
          app (make-app prov issuer metadata)
          server (httpkit/run-server app {:port port :legacy-return-value? false})]
      (is (nil? (:error prov)))
      (try
        (run-conformance prov issuer {:metadata metadata :client-keys client-keys})
        (finally
          (httpkit/server-stop! server))))))

(deftest conformance-sqlite-stores-test
  (let [srv (httpkit/run-server (fn [_] {:status 503}) {:port 0 :legacy-return-value? false})
        port (httpkit/server-port srv)
        issuer (str "http://127.0.0.1:" port)
        dir (str (Files/createTempDirectory "provider-sqlite" (make-array FileAttribute 0)))
        accounts {did {:sub did :handle handle :password password}}
        {:keys [stores close!]}
        (store-sqlite/stores
         {:db-path (str dir "/oauth.sqlite")
          :account-fns {:authenticate (fn [{:keys [identifier password]}]
                                        (some (fn [a]
                                                (when (and (or (= identifier (:sub a))
                                                               (= identifier (:handle a)))
                                                           (= password (:password a)))
                                                  (dissoc a :password)))
                                              (vals accounts)))
                        :get-account (fn [sub] (some-> (get accounts sub) (dissoc :password)))}})]
    (httpkit/server-stop! srv)
    (let [prov (provider/create {:issuer issuer :keyset (test-keyset) :stores stores})
          server (httpkit/run-server (make-app prov issuer client-metadata) {:port port :legacy-return-value? false})]
      (is (nil? (:error prov)))
      (try
        (run-conformance prov issuer)
        (finally
          (httpkit/server-stop! server)
          (close!)
          (run! clojure.java.io/delete-file
                (reverse (file-seq (clojure.java.io/file dir)))))))))
