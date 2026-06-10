(ns atproto.oauth.client-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.http :as http]
            [atproto.runtime.json :as json]
            [atproto.runtime.crypto :as crypto]
            [atproto.test-support.http :as fake-http]
            [atproto.xrpc.client :as xrpc-client]
            [atproto.oauth.client :as oauth]
            [atproto.oauth.client.dpop :as dpop]
            [atproto.oauth.client.store :as store]))

(def did "did:plc:bbbbbbbbbbbbbbbbbbbbbbbb")
(def issuer "https://auth.test")
(def pds "https://pds.test")

(def did-doc
  {:id did
   :alsoKnownAs ["at://bob.test"]
   :service [{:id "#atproto_pds"
              :type "AtprotoPersonalDataServer"
              :serviceEndpoint pds}]})

(def asmd
  {:issuer issuer
   :token_endpoint (str issuer "/token")
   :revocation_endpoint (str issuer "/revoke")
   :pushed_authorization_request_endpoint (str issuer "/par")
   :authorization_endpoint (str issuer "/authorize")
   :require_pushed_authorization_requests true
   :token_endpoint_auth_methods_supported ["none"]})

(def rsmd
  {:resource pds
   :authorization_servers [issuer]})

(defn- test-client
  []
  (oauth/create {:client-metadata {:client_id "https://app.test/client-metadata.json"
                                   :redirect_uris ["https://app.test/oauth/callback"]
                                   :scope "atproto"}}))

(defn- tokens
  "A stored token map with a future expiry, merged with `overrides`."
  [& [overrides]]
  (merge {:access_token "at-1"
          :refresh_token "rt-1"
          :token_type "DPoP"
          :scope "atproto"
          :sub did
          :aud pds
          :iss issuer
          :expires-at (+ (crypto/now) 3600)}
         overrides))

(defn- seed-session!
  "Store a session for `did` and return the session data (with the dpop-key)."
  [client & [overrides]]
  (let [dpop-key (dpop/generate-key)
        session-data (merge {:did did
                             :handle "bob.test"
                             :did-doc did-doc
                             :iss issuer
                             :dpop-key dpop-key
                             :tokens (tokens)}
                            overrides)]
    (store/set (:session-store client) did session-data)
    session-data))

(def resolution-routes
  [["plc.directory" (fake-http/json-response did-doc)]
   ["/.well-known/oauth-protected-resource" (fake-http/json-response rsmd)]
   ["/.well-known/oauth-authorization-server" (fake-http/json-response asmd)]])

(def token-success-response
  (fake-http/json-response {:access_token "at-2"
                            :refresh_token "rt-2"
                            :token_type "DPoP"
                            :scope "atproto"
                            :sub did
                            :expires_in 300}))

(defn- requests-to
  [requests url-fragment]
  (filter #(str/includes? (:url %) url-fragment) @requests))

(deftest refresh-end-to-end-test
  (let [client (test-client)
        _ (seed-session! client)
        {:keys [handler requests]} (fake-http/routed
                                    (conj resolution-routes
                                          ["/token" token-success-response]))]
    (with-redefs [http/handle-request handler]
      (let [session (deref (oauth/refresh client did) 5000 ::timeout)]
        (is (nil? (:error session)))
        (is (= "at-2" (get-in session [:tokens :access_token])))
        (is (= did (:did session)))
        (is (= pds (:pds session)))
        ;; satisfies the Session protocol
        (is (map? (xrpc-client/auth-interceptor session)))
        ;; the issuer was re-verified (identity resolved) before the token call
        (let [urls (mapv :url @requests)]
          (is (str/includes? (first urls) "plc.directory"))
          (is (str/includes? (last urls) "/token")))
        ;; the token request was a DPoP-signed refresh_token grant
        (let [token-request (first (requests-to requests "/token"))
              body (json/read-str (:body token-request))]
          (is (= "refresh_token" (:grant_type body)))
          (is (= "rt-1" (:refresh_token body)))
          (is (= 3 (count (str/split (get-in token-request [:headers :dpop]) #"\.")))))
        ;; the refreshed tokens were persisted with :iss and :expires-at
        (let [stored (store/get (:session-store client) did)]
          (is (= issuer (:iss stored)))
          (is (= "at-2" (get-in stored [:tokens :access_token])))
          (is (= "rt-2" (get-in stored [:tokens :refresh_token])))
          (is (int? (get-in stored [:tokens :expires-at])))
          (is (< (crypto/now) (get-in stored [:tokens :expires-at]))))))))

(deftest refresh-single-flight-test
  (let [client (test-client)
        _ (seed-session! client)
        release (promise)
        {:keys [handler requests]} (fake-http/routed
                                    (conj resolution-routes
                                          ["/token" (fn [_]
                                                      @release
                                                      token-success-response)]))]
    (with-redefs [http/handle-request handler]
      (let [p1 (future @(oauth/refresh client did))
            ;; wait for the first refresh to be in flight at the token endpoint
            _ (loop [n 0]
                (when (and (< n 100) (empty? (requests-to requests "/token")))
                  (Thread/sleep 50)
                  (recur (inc n))))
            p2 (oauth/refresh client did)]
        (deliver release true)
        (let [s1 (deref p1 5000 ::timeout)
              s2 (deref p2 5000 ::timeout)]
          (is (= "at-2" (get-in s1 [:tokens :access_token])))
          (is (= "at-2" (get-in s2 [:tokens :access_token])))
          (is (= 1 (count (requests-to requests "/token")))))))))

(deftest refresh-pins-sub-test
  ;; the token response's sub is not adopted: the stored sub stays pinned to
  ;; the session's DID (matching the reference, which ignores it on refresh)
  (let [client (test-client)
        _ (seed-session! client)
        {:keys [handler]} (fake-http/routed
                           (conj resolution-routes
                                 ["/token" (fake-http/json-response
                                            {:access_token "at-2"
                                             :refresh_token "rt-2"
                                             :token_type "DPoP"
                                             :scope "atproto"
                                             :sub "did:plc:zzzzzzzzzzzzzzzzzzzzzzzz"
                                             :expires_in 300})]))]
    (with-redefs [http/handle-request handler]
      (let [session (deref (oauth/refresh client did) 5000 ::timeout)]
        (is (nil? (:error session)))
        (is (= did (get-in session [:tokens :sub])))
        (is (= did (get-in (store/get (:session-store client) did) [:tokens :sub])))))))

(deftest issuer-metadata-cache-ttl-test
  ;; AS metadata is cached for issuer-metadata-ttl and re-fetched after that
  (let [client (test-client)
        {:keys [handler requests]} (fake-http/routed
                                    [["/.well-known/oauth-authorization-server"
                                      (fake-http/json-response asmd)]
                                     ["/revoke" (fake-http/json-response {})]])
        asmd-fetches #(count (requests-to requests "oauth-authorization-server"))]
    (with-redefs [http/handle-request handler]
      ;; first revoke fetches and caches the metadata, second hits the cache
      (seed-session! client)
      (is (= {:did did :revoked true} (deref (oauth/revoke client did) 5000 ::timeout)))
      (is (= 1 (asmd-fetches)))
      (seed-session! client)
      (is (= {:did did :revoked true} (deref (oauth/revoke client did) 5000 ::timeout)))
      (is (= 1 (asmd-fetches)))
      ;; past the TTL the metadata is fetched again
      (let [real-now crypto/now]
        (with-redefs [crypto/now #(+ (real-now) (* 2 oauth/issuer-metadata-ttl))]
          (seed-session! client)
          (is (= {:did did :revoked true} (deref (oauth/revoke client did) 5000 ::timeout)))
          (is (= 2 (asmd-fetches))))))))

(deftest invalid-grant-adopts-foreign-refresh-test
  (let [client (test-client)
        seeded (seed-session! client)
        {:keys [handler]} (fake-http/routed
                           (conj resolution-routes
                                 ["/token" (fake-http/json-response
                                            400 {:error "invalid_grant"
                                                 :error_description "refresh token replayed"})]))]
    (with-redefs [http/handle-request handler]
      (let [pending (oauth/refresh client did)]
        ;; another process refreshed concurrently and stored different tokens
        (store/set (:session-store client) did
                   (update seeded :tokens assoc
                           :access_token "at-foreign"
                           :refresh_token "rt-foreign"))
        (let [session (deref pending 5000 ::timeout)]
          (is (nil? (:error session)))
          (is (= "at-foreign" (get-in session [:tokens :access_token])))
          ;; the foreign session was adopted, not deleted
          (is (some? (store/get (:session-store client) did))))))))

(deftest invalid-grant-deletes-session-test
  (let [client (test-client)
        _ (seed-session! client)
        {:keys [handler]} (fake-http/routed
                           (conj resolution-routes
                                 ["/token" (fake-http/json-response
                                            400 {:error "invalid_grant"
                                                 :error_description "expired"})]))]
    (with-redefs [http/handle-request handler]
      (let [resp (deref (oauth/refresh client did) 5000 ::timeout)]
        (is (= "TokenRefreshError" (:error resp)))
        (is (= "expired" (:message resp)))
        ;; definitive failures are tagged so XRPC clients drop the session
        (is (true? (:atproto.xrpc.client/session-expired? resp)))
        (is (nil? (store/get (:session-store client) did)))))))

(deftest refresh-without-refresh-token-test
  (let [client (test-client)
        _ (seed-session! client {:tokens {:access_token "at-1"
                                          :token_type "DPoP"
                                          :sub did
                                          :aud pds
                                          :iss issuer}})
        {:keys [handler requests]} (fake-http/routed resolution-routes)]
    (with-redefs [http/handle-request handler]
      (let [resp (deref (oauth/refresh client did) 5000 ::timeout)]
        (is (= "TokenRefreshError" (:error resp)))
        (is (nil? (store/get (:session-store client) did)))
        (is (empty? @requests))))))

(deftest refresh-network-error-keeps-session-test
  (let [client (test-client)
        _ (seed-session! client)
        {:keys [handler]} (fake-http/routed
                           (conj resolution-routes
                                 ["/token" {:error "HTTPClientError"
                                            :message "connection refused"}]))]
    (with-redefs [http/handle-request handler]
      (let [resp (deref (oauth/refresh client did) 5000 ::timeout)]
        (is (= "HTTPClientError" (:error resp)))
        ;; transient failure: the stored session is kept
        (is (some? (store/get (:session-store client) did)))))))

(deftest refresh-unknown-did-test
  (let [client (test-client)]
    (let [resp (deref (oauth/refresh client did) 1000 ::timeout)]
      (is (= "SessionNotFound" (:error resp))))))

(deftest restore-test
  (let [client (test-client)
        _ (seed-session! client)
        {:keys [handler requests]} (fake-http/routed [])]
    (with-redefs [http/handle-request handler]
      (let [session (deref (oauth/restore client did) 1000 ::timeout)]
        (is (nil? (:error session)))
        (is (= did (:did session)))
        (is (= pds (:pds session)))
        (is (map? (xrpc-client/auth-interceptor session)))
        ;; fresh tokens are returned as-is, without any network traffic
        (is (empty? @requests))))))

(deftest restore-refreshes-stale-tokens-test
  ;; restore with the default :refresh :auto refreshes expired (or about to
  ;; expire) tokens before returning the session
  (let [client (test-client)
        _ (seed-session! client {:tokens (tokens {:expires-at (crypto/now)})})
        {:keys [handler requests]} (fake-http/routed
                                    (conj resolution-routes
                                          ["/token" token-success-response]))]
    (with-redefs [http/handle-request handler]
      (let [session (deref (oauth/restore client did) 5000 ::timeout)]
        (is (nil? (:error session)))
        (is (= "at-2" (get-in session [:tokens :access_token])))
        (is (= 1 (count (requests-to requests "/token"))))))))

(deftest restore-refresh-false-returns-stale-tokens-test
  (let [client (test-client)
        _ (seed-session! client {:tokens (tokens {:expires-at 0})})
        {:keys [handler requests]} (fake-http/routed [])]
    (with-redefs [http/handle-request handler]
      (let [session (deref (oauth/restore client did :refresh false) 1000 ::timeout)]
        (is (nil? (:error session)))
        (is (= "at-1" (get-in session [:tokens :access_token])))
        (is (empty? @requests))))))

(deftest restore-refresh-true-forces-refresh-test
  (let [client (test-client)
        _ (seed-session! client)
        {:keys [handler requests]} (fake-http/routed
                                    (conj resolution-routes
                                          ["/token" token-success-response]))]
    (with-redefs [http/handle-request handler]
      (let [session (deref (oauth/restore client did :refresh true) 5000 ::timeout)]
        (is (= "at-2" (get-in session [:tokens :access_token])))
        (is (= 1 (count (requests-to requests "/token"))))))))

(deftest restore-unknown-did-never-hangs-test
  (let [client (test-client)
        resp (deref (oauth/restore client "did:plc:cccccccccccccccccccccccc") 1000 ::timeout)]
    (is (= "SessionNotFound" (:error resp)))
    (is (= "did:plc:cccccccccccccccccccccccc" (:did resp)))))

(deftest restore-legacy-row-test
  ;; legacy rows have no :iss and no :expires-at
  (let [client (test-client)
        dpop-key (dpop/generate-key)]
    (store/set (:session-store client) did
               {:did did
                :did-doc did-doc
                :dpop-key dpop-key
                :tokens {:access_token "at-1"
                         :refresh_token "rt-1"
                         :token_type "DPoP"
                         :sub did}})
    (let [session (deref (oauth/restore client did) 1000 ::timeout)]
      (is (nil? (:error session)))
      (is (= "at-1" (get-in session [:tokens :access_token]))))))

(deftest revoke-test
  (let [client (test-client)
        _ (seed-session! client)
        {:keys [handler requests]} (fake-http/routed
                                    (conj resolution-routes
                                          ["/revoke" (fake-http/json-response {})]))]
    (with-redefs [http/handle-request handler]
      (let [resp (deref (oauth/revoke client did) 5000 ::timeout)]
        (is (= {:did did :revoked true} resp))
        ;; the access token was POSTed to the revocation endpoint
        (let [revocation-request (first (requests-to requests "/revoke"))]
          (is (some? revocation-request))
          (is (= "at-1" (:token (json/read-str (:body revocation-request))))))
        ;; the stored session is gone
        (is (nil? (store/get (:session-store client) did)))
        (is (= "SessionNotFound"
               (:error (deref (oauth/restore client did) 1000 ::timeout))))))))

(deftest revoke-server-error-still-deletes-test
  (let [client (test-client)
        _ (seed-session! client)
        {:keys [handler]} (fake-http/routed
                           (conj resolution-routes
                                 ["/revoke" (fake-http/json-response 500 {:error "server_error"})]))]
    (with-redefs [http/handle-request handler]
      (let [resp (deref (oauth/revoke client did) 5000 ::timeout)]
        (is (= {:did did :revoked true} resp))
        (is (nil? (store/get (:session-store client) did)))))))

(deftest revoke-without-revocation-endpoint-test
  (let [client (test-client)
        _ (seed-session! client)
        {:keys [handler requests]} (fake-http/routed
                                    [["/.well-known/oauth-authorization-server"
                                      (fake-http/json-response (dissoc asmd :revocation_endpoint))]])]
    (with-redefs [http/handle-request handler]
      (let [resp (deref (oauth/revoke client did) 5000 ::timeout)]
        (is (= {:did did :revoked true} resp))
        (is (empty? (requests-to requests "/revoke")))
        (is (nil? (store/get (:session-store client) did)))))))

(deftest revoke-unknown-did-test
  (let [client (test-client)
        resp (deref (oauth/revoke client did) 1000 ::timeout)]
    (is (= {:did did :revoked false} resp))))

(deftest callback-expired-state-test
  (let [client (test-client)
        state "state-123"]
    (store/set (:state-store client) state
               {:iss issuer
                :dpop-key (dpop/generate-key)
                :identity {:did did :did-doc did-doc}
                :verifier "verifier"
                :created-at (- (crypto/now) 7200)})
    (let [resp (deref (oauth/callback client {:state state :iss issuer :code "code"})
                      1000 ::timeout)]
      (is (= "StateExpired" (:error resp)))
      (is (nil? (store/get (:state-store client) state))))))

(deftest callback-unknown-state-test
  (let [client (test-client)
        resp (deref (oauth/callback client {:state "nope" :iss issuer :code "code"})
                    1000 ::timeout)]
    (is (= "Unknown state" (:error resp)))))

;; -----------------------------------------------------------------------------
;; Store
;; -----------------------------------------------------------------------------

(deftest memory-store-round-trip-test
  (let [ms (store/memory-store)
        session-data {:did did
                      :did-doc did-doc
                      :iss issuer
                      :dpop-key (dpop/generate-key)
                      :tokens {:access_token "at-1"
                               :refresh_token "rt-1"
                               :token_type "DPoP"
                               :scope "atproto"
                               :sub did
                               :aud pds
                               :expires-at 1760000000}}]
    (store/set ms did session-data)
    (is (= session-data (store/get ms did)))
    (store/del ms did)
    (is (nil? (store/get ms did)))))

(deftest memory-store-evicts-expired-entries-test
  (let [backing (atom {})
        ms (store/memory-store backing)]
    ;; an already-expired entry is not returned and is removed on read
    (store/set ms "expired" {:expires-at (- (crypto/now) 10) :a 1})
    (is (nil? (store/get ms "expired")))
    (is (not (contains? @backing "expired")))
    ;; a live entry with a future expiry is returned
    (store/set ms "live" {:expires-at (+ (crypto/now) 100) :a 2})
    (is (= 2 (:a (store/get ms "live"))))
    ;; expired entries are swept on writes, even if never read
    (store/set ms "abandoned" {:expires-at (- (crypto/now) 10) :a 3})
    (store/set ms "other" {:b 4})
    (is (not (contains? @backing "abandoned")))
    ;; entries without :expires-at are kept forever
    (is (= 4 (:b (store/get ms "other"))))))
