(ns atproto.oauth.client
  "OAuth 2 client for atproto profile."
  (:refer-clojure :exclude [resolve])
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.json :as json]
            [atproto.runtime.http :as http]
            [atproto.runtime.crypto :as crypto]
            [atproto.runtime.jwt :as jwt]
            [atproto.runtime.cast :as cast]
            [atproto.identity :as identity]
            [atproto.xrpc.client :as xrpc-client]
            [atproto.oauth.client.dpop :as dpop]
            [atproto.oauth.client.store :as store]))

;; -----------------------------------------------------------------------------
;; Persisted session shape
;; -----------------------------------------------------------------------------

;; The session-store value (JSON, key = DID). Readers must tolerate legacy
;; rows missing :iss and :tokens/:expires-at.

(s/def ::did string?)
(s/def ::handle string?)
(s/def ::did-doc map?)
(s/def ::dpop-key map?)
(s/def ::iss ::http/url)
(s/def ::access_token string?)
(s/def ::refresh_token string?)
(s/def ::token_type string?)
(s/def ::scope string?)
(s/def ::sub string?)
(s/def ::aud string?)
(s/def ::expires-at int?)
(s/def ::tokens
  (s/keys :req-un [::access_token ::token_type]
          :opt-un [::refresh_token ::scope ::sub ::aud ::iss ::expires-at]))
(s/def ::session-data
  (s/keys :req-un [::did ::tokens ::dpop-key]
          :opt-un [::iss ::handle ::did-doc]))

;; -----------------------------------------------------------------------------
;; OAuth session for the XRPC client
;; -----------------------------------------------------------------------------

(declare refresh-session auth-interceptor)

(defn- oauth-session
  "Create a OAuth session that can be used with `xrpc.client`."
  [client {:keys [did handle did-doc tokens dpop-key] :as data}]
  (with-meta
    (cond-> {:pds (identity/did-doc-pds did-doc)
             :did did
             :tokens tokens
             :dpop-key dpop-key}
      handle (assoc :handle handle))
    {`xrpc-client/auth-interceptor #(auth-interceptor client %)
     `xrpc-client/refresh-token #(refresh-session client %1 %2)}))

(defn auth-interceptor
  [client {:keys [tokens dpop-key]}]
  (let [{:keys [client_id]} (:client-metadata client)
        {:keys [token_type access_token]} tokens]
    {::i/name ::auth-interceptor
     ::i/enter (fn [ctx]
                 (-> ctx
                     (assoc-in [::i/request :headers :authorization]
                               (str token_type " " access_token))
                     (update ::i/queue #(cons (dpop/interceptor {:iss client_id
                                                                 :dpop-key dpop-key})
                                              %))))}))

;; -----------------------------------------------------------------------------
;; OAuth client
;; -----------------------------------------------------------------------------

;; atproto clients and servers must support ES256
(def default-alg "ES256")

;; Maximum age of a pending-authorization state entry, in seconds.
(def state-max-age (* 60 60))

(defn create
  "Create a new OAuth2 client."
  [{:keys [client-metadata keys state-store session-store] :as opts}]
  (let [jwks {"keys" (->> keys
                          (filter string?)
                          (map json/read-str))}]
    {:client-metadata (assoc client-metadata
                             ;; only add the public keys to the client metadata
                             :jwks (jwt/public-jwks jwks))
     :jwks jwks
     :issuers (atom {})
     ;; per-DID single-flight token refreshes
     ::refresh-inflight (atom {})
     :state-store (or state-store (store/memory-store))
     :session-store (or session-store (store/memory-store))}))

;; How long fetched authorization-server metadata may be reused, in seconds.
(def issuer-metadata-ttl 60)

(defn- set-issuer!
  [client iss metadata]
  (swap! (:issuers client) assoc iss {:metadata metadata
                                      :expires-at (+ (crypto/now) issuer-metadata-ttl)}))

(defn- get-issuer
  "The cached authorization-server metadata for `iss`, if still fresh."
  [client iss]
  (when-let [{:keys [metadata expires-at]} (get @(:issuers client) iss)]
    (when (< (crypto/now) expires-at)
      metadata)))

(defn- after-delay
  "Invoke f after ms milliseconds, off the calling thread."
  [ms f]
  #?(:clj (future (Thread/sleep (long ms)) (f))
     :cljs (js/setTimeout f ms))
  nil)

;; Resolve identity

(defn- fetch-rsmd
  "Fetch the resource server metadata."
  [server cb]
  (i/execute {::i/request {:method :get
                           :url (str server "/.well-known/oauth-protected-resource")}
              ::i/queue [json/client-interceptor
                         http/client-interceptor]}
             :callback (fn [{:keys [error body] :as resp}]
                         (cb (if error resp body)))))

(defn- fetch-asmd
  "Fetch the authorization server metadata."
  [server cb]
  (i/execute {::i/request {:method :get
                           :url (str server "/.well-known/oauth-authorization-server")}
              ::i/queue [json/client-interceptor
                         http/client-interceptor]}
             :callback (fn [{:keys [error body] :as resp}]
                         (cb (if error resp body)))))

(defn- issuer-metadata
  "The authorization-server metadata for `iss`.

  Returns the cached entry (fresh for `issuer-metadata-ttl`), or fetches it
  from <iss>/.well-known/oauth-authorization-server, validates that the
  metadata's issuer matches, and caches it. Makes refresh/revoke/callback
  work after a process restart, when the in-memory issuer cache is empty."
  [client iss cb]
  (if-let [metadata (get-issuer client iss)]
    (cb metadata)
    (fetch-asmd iss
                (fn [{:keys [error] :as resp}]
                  (cond
                    error
                    (cb resp)

                    (not (= iss (:issuer resp)))
                    (cb {:error "IssuerMismatch"
                         :message "Authorization server metadata does not match the issuer URL."
                         :iss iss})

                    :else
                    (do (set-issuer! client iss resp)
                        (cb resp)))))))

(defn- handle-asmd
  [client {:keys [rsmd asmd identity] :as ctx} cb]
  (let [{:keys [protected_resources issuer]} asmd]
    (if (and (seq protected_resources)
             (not (contains? (set protected_resources) (:resource rsmd))))
      (cb (assoc ctx :error "PDS not protected by issuer."))
      (do
        (set-issuer! client issuer asmd)
        (cb {:identity identity
             :iss issuer})))))

(defn- handle-rsmd
  [client {:keys [rsmd] :as ctx} cb]
  (let [{:keys [authorization_servers]} rsmd]
    (cond
      (= 0 (count authorization_servers)) (cb (assoc ctx :error "No authorization server found."))
      (< 1 (count authorization_servers)) (cb (assoc ctx :error "Too many authorization servers found."))
      :else  (fetch-asmd (first authorization_servers)
                         (fn [{:keys [error] :as resp}]
                           (if error
                             (cb (merge ctx resp))
                             (handle-asmd client (assoc ctx :asmd resp) cb)))))))

(defn- handle-identity
  [client {:keys [identity] :as ctx} cb]
  (fetch-rsmd (identity/did-doc-pds (:did-doc identity))
              (fn [{:keys [error] :as resp}]
                (if error
                  (cb (merge ctx resp))
                  (handle-rsmd client (assoc ctx :rsmd resp) cb)))))

;; todo: options map with :no-cache? :allow-stale?
(defn- resolve
  "Resolve the identity and return it with the issuer's URL.

  Return a map with:
  :identity  The verified identity
  :iss       The issuer's URL."
  [client input cb]
  (identity/resolve-identity input
                             :callback
                             (fn [{:keys [error] :as resp}]
                               (if error
                                 (cb resp)
                                 (handle-identity client {:identity resp} cb)))))

;; Authorization server requests

(defmulti client-auth
  "The authentication payload for this client and issuer."
  (fn [client issuer]
    (let [client-method (get-in client [:client-metadata :token_endpoint_auth_method] "none")]
      (when (contains? (set (:token_endpoint_auth_methods_supported issuer))
                       client-method)
        client-method))))

(defmethod client-auth "none"
  [client _]
  {:client_id (:client_id (:client-metadata client))})

(defmethod client-auth "private_key_jwt"
  [{:keys [jwks] :as client} issuer]
  (let [jwk (first (jwt/query-jwks jwks {:alg default-alg}))
        {:keys [client_id]} (:client-metadata client)
        now (crypto/now)]
    {:client_id client_id
     :client_assertion_type "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
     :client_assertion (jwt/generate jwk
                                     {:alg default-alg
                                      :kid (jwt/jwk-kid jwk)}
                                     {:iss client_id
                                      :sub client_id
                                      :aud (:issuer issuer)
                                      :jti (crypto/generate-nonce 16)
                                      :iat now
                                      ;; exp is REQUIRED for JWT client
                                      ;; assertions (RFC 7523 §3); jti is
                                      ;; single-use so keep it short
                                      :exp (+ now 60)})}))

(defn- as-request
  "POST `params`, merged with this client's authentication payload, to one of
  the issuer's endpoints (e.g. :token_endpoint), DPoP-signed with `dpop-key`.

  cb receives the parsed response body or an {:error ...} map; OAuth error
  bodies pass through as e.g. {:error \"invalid_grant\" ...}."
  [client {:keys [issuer dpop-key]} endpoint params cb]
  (let [{:keys [client_id]} (:client-metadata client)]
    (if-let [url (get issuer endpoint)]
      (i/execute {::i/request {:method :post
                               :url url
                               :headers {:content-type "application/json"}
                               :body (merge params (client-auth client issuer))}
                  ::i/queue [(dpop/interceptor {:iss client_id
                                                :dpop-key dpop-key})
                             json/client-interceptor
                             http/client-interceptor]}
                 :callback
                 (fn [{:keys [error status body] :as http-response}]
                   (cond
                     error                        (cb http-response)
                     (:error body)                (cb body)
                     (not (http/success? status)) (cb (http/error-map http-response))
                     :else                        (cb body))))
      (cb {:error "MissingEndpoint"
           :message (str "No " (name endpoint) " in the authorization server metadata.")
           :endpoint endpoint}))))

(defn- token-request
  "POST `params` to the issuer's token endpoint. See `as-request`."
  [client server params cb]
  (as-request client server :token_endpoint params cb))

(defn- tokens+expiry
  "Normalize a token response into the token map persisted in the session
  store: keep access_token/refresh_token/token_type/scope/sub, add :iss and
  :aud, compute :expires-at (epoch seconds) from :expires_in."
  [iss aud {:keys [expires_in] :as token-response}]
  (cond-> (-> token-response
              (select-keys [:access_token :refresh_token :token_type :scope :sub])
              (assoc :iss iss :aud aud))
    expires_in (assoc :expires-at (+ (crypto/now) expires_in))))

(defn- revoke-tokens
  "Best-effort revocation of this session's access token at the authorization
  server. All failures (missing issuer metadata, no revocation endpoint,
  network or server errors) are swallowed; cb is always called with nil."
  [client {:keys [iss dpop-key tokens]} cb]
  (if-not iss
    (cb nil)
    (issuer-metadata client iss
                     (fn [{:keys [error revocation_endpoint] :as issuer}]
                       (if (or error (not revocation_endpoint))
                         (cb nil)
                         (as-request client
                                     {:issuer issuer :dpop-key dpop-key}
                                     :revocation_endpoint
                                     {:token (:access_token tokens)}
                                     (fn [_] (cb nil))))))))

;; Pushed Authorization Request

(defn- par-interceptor
  "Interceptor for this client to send the push authorization request (PAR) to this issuer."
  [{:keys [client-metadata] :as client}
   {:keys [issuer dpop-key] :as server}]
  (let [{:keys [client_id redirect_uris scope]} client-metadata
        {:keys [pushed_authorization_request_endpoint authorization_endpoint]} issuer]
    {::i/name ::par
     ::i/enter (fn [{:keys [::i/request] :as ctx}]
                 (update ctx
                         ::i/request
                         (fn [{:keys [opts identity input]}]
                           (let [pkce (crypto/generate-pkce 63)
                                 state (crypto/generate-nonce 16)
                                 now (crypto/now)
                                 oauth-params (cond-> {:client_id client_id
                                                       :redirect_uri (or (:redirect-uri opts)
                                                                         (first redirect_uris))
                                                       :code_challenge (:challenge pkce)
                                                       :code_challenge_method (:method pkce)
                                                       :state state
                                                       :response_mode "query"
                                                       :response_type "code"
                                                       :scope (or (:scope opts) scope)}
                                                identity (assoc :login_hint input))]
                             (store/set (:state-store client)
                                        state
                                        {:iss (:issuer issuer)
                                         :dpop-key dpop-key
                                         :identity identity
                                         :verifier (:verifier pkce)
                                         :app-state (:state opts)
                                         :created-at now
                                         :expires-at (+ now state-max-age)})
                             {:method :post
                              :url pushed_authorization_request_endpoint
                              :headers {:content-type "application/json"}
                              :body (merge oauth-params
                                           (client-auth client issuer))}))))

     ::i/leave (fn [ctx]
                 (update ctx
                         ::i/response
                         (fn [{:keys [error status body] :as response}]
                           (cond
                             error
                             response

                             (not (http/success? status))
                             (http/error-map response)

                             :else
                             {:authorization-url (-> authorization_endpoint
                                                     http/parse-url
                                                     (update :query-params
                                                             merge
                                                             {:client_id client_id
                                                              :request_uri (:request_uri body)})
                                                     http/serialize-url)}))))}))

(defn authorize
  "Generate the end-user authorization URL.

  Accept the following options:
  :scope          OAuth scope
  :redirect-uri   OAuth callback redirect URI
  :state          app-specific state that will be returned in `callback`."
  [client input & {:as opts}]
  (let [[cb val] (i/platform-async opts)
        {:keys [client_id]} (:client-metadata client)]
    (resolve client
             input
             (fn [{:keys [error identity iss] :as resp}]
               (if error
                 (cb resp)
                 (let [server {:issuer (get-issuer client iss)
                               :dpop-key (dpop/generate-key)}]
                   (if (not (:require_pushed_authorization_requests (:issuer server)))
                     (cb {:error "Server does not support PAR."})
                     (i/execute {::i/request {:opts opts
                                              :identity identity
                                              :input input
                                              :issuer iss}
                                 ::i/queue [(par-interceptor client server)
                                            (dpop/interceptor {:iss client_id
                                                               :dpop-key (:dpop-key server)})
                                            json/client-interceptor
                                            http/client-interceptor]}
                                :callback cb))))))
    val))

(defn- exchange-code
  "Exchange an authorization code for a set of tokens."
  [client server {:keys [code verifier]} cb]
  (let [{:keys [redirect_uris]} (:client-metadata client)
        {:keys [issuer]} server]
    (token-request client server
                   {:grant_type "authorization_code"
                    :redirect_uri (first redirect_uris)
                    :code code
                    :code_verifier verifier}
                   (fn [{:keys [error] :as body}]
                     (if error
                       (cb body)
                       ;; The token response must be valid before the 'sub' it contains can be trusted
                       (resolve client
                                (:sub body)
                                (fn [{:keys [error iss] :as resp}]
                                  (cb (cond
                                        error resp
                                        (not (= iss (:issuer issuer))) {:error "Issuer mismatch."}
                                        :else (assoc body
                                                     :aud
                                                     (-> resp
                                                         :identity
                                                         :did-doc
                                                         identity/did-doc-pds)))))))))))

(defn- validate-callback-params
  "Validate the callback params.

  Return a map with:
  :state    The local state stored for this session.
  :error    In case of an error."
  [client {:keys [response iss state error code] :as params}]
  (let [saved-state (store/get (:state-store client) state)]
    (if (not saved-state)
      {:error "Unknown state" :state state}
      (do
        (store/del (:state-store client) state)
        (cond error
              {:error error
               :params params
               :state saved-state}

              (and (:created-at saved-state)
                   (< state-max-age (- (crypto/now) (:created-at saved-state))))
              {:error "StateExpired"
               :params params
               :state saved-state}

              (not (= iss (:iss saved-state)))
              {:error "Issuer mismatch."
               :params params
               :state saved-state}

              :else
              {:state saved-state})))))

(defn callback
  "Exchange the authorization code for OAuth tokens and return a OAuth session.

  Stores :iss and :tokens (with :expires-at) in the session store, revoking
  any pre-existing session for the same DID before storing the new one.
  State entries older than `state-max-age` are rejected as
  {:error \"StateExpired\"} and deleted.

  Return a map with:
  :session  The OAuth session.
  :state    The app state passed in authorize, if any."
  [client params & {:as opts}]
  (let [[cb val] (i/platform-async opts)
        {:keys [error state] :as resp} (validate-callback-params client params)]
    (if error
      (cb resp)
      (let [{:keys [verifier dpop-key app-state identity]} state]
        (issuer-metadata
         client (:iss state)
         (fn [{:keys [error] :as issuer}]
           (if error
             (cb issuer)
             (let [server {:issuer issuer
                           :dpop-key dpop-key}]
               (exchange-code client server {:code (:code params)
                                             :verifier verifier}
                              (fn [{:keys [error] :as resp}]
                                (if error
                                  (cb resp)
                                  (let [did (:sub resp)
                                        iss (:issuer issuer)
                                        session-data (merge identity
                                                            {:did did
                                                             :iss iss
                                                             :tokens (tokens+expiry iss (:aud resp) resp)
                                                             :dpop-key dpop-key})
                                        store-session! (fn []
                                                         (store/set (:session-store client) did session-data)
                                                         (cb (cond-> {:session (oauth-session client session-data)}
                                                               app-state (assoc :state app-state))))]
                                    ;; revoke any pre-existing session for the same DID
                                    (if-let [existing (store/get (:session-store client) did)]
                                      (revoke-tokens client existing (fn [_] (store-session!)))
                                      (store-session!))))))))))))
    val))

;; Tokens are considered stale this many seconds before :expires-at, plus a
;; random jitter to spread refreshes across concurrent instances.
(def token-expiry-leeway 10)
(def token-expiry-jitter 30)

(defn- stale-tokens?
  "True when these tokens are expired or about to expire. Tokens without
  :expires-at (legacy rows) are never considered stale."
  [{:keys [expires-at]}]
  (boolean
   (and expires-at
        (< expires-at (+ (crypto/now)
                         token-expiry-leeway
                         (rand-int token-expiry-jitter))))))

(defn restore
  "The OAuth session for this did.

  Resolves to the session or {:error \"SessionNotFound\" :did did}.

  The :refresh option controls token freshness:
  :auto (default)  refresh first when the stored tokens are expired or about
                   to expire (see `stale-tokens?`)
  true             always refresh first
  false            return the stored tokens as-is, even if expired

  Tolerates legacy stored sessions without :iss/:expires-at (never refreshed
  proactively, only on an invalid-token response)."
  [client did & {:keys [refresh] :or {refresh :auto} :as opts}]
  (let [[cb val] (i/platform-async (dissoc opts :refresh))]
    (if-let [session-data (store/get (:session-store client) did)]
      (if (case refresh
            true true
            false false
            (stale-tokens? (:tokens session-data)))
        (refresh-session client {:did did} cb)
        (cb (oauth-session client session-data)))
      (cb {:error "SessionNotFound" :did did}))
    val))

;; -----------------------------------------------------------------------------
;; Refresh
;; -----------------------------------------------------------------------------

(defn- recover-invalid-grant
  "Concurrency recovery for a refresh_token grant rejected with invalid_grant.

  Another process may have used (and rotated) the refresh token first: wait a
  moment, re-read the store, and adopt the stored session if it now holds
  different tokens. Otherwise the grant is dead: delete the session and report
  a TokenRefreshError."
  [client did used-tokens {:keys [error_description] :as token-resp} cb]
  (after-delay
   1000
   (fn []
     (let [session-store (:session-store client)
           stored (store/get session-store did)]
       (if (and stored
                (not= (select-keys (:tokens stored) [:access_token :refresh_token])
                      (select-keys used-tokens [:access_token :refresh_token])))
         (cb (oauth-session client stored))
         (do (when stored
               (store/del session-store did))
             (cb {:error "TokenRefreshError"
                  :message (or error_description "The refresh token grant was rejected.")
                  :did did
                  ::xrpc-client/session-expired? true})))))))

(defn- run-refresh
  "Perform the refresh_token grant for the stored session of this DID.

  cb receives a new session (satisfying xrpc-client/Session) or {:error ...}.
  Definitive failures delete the stored session and are tagged with
  :atproto.xrpc.client/session-expired? so XRPC clients drop the dead session;
  transient failures (network, 5xx) leave it in place. The refreshed tokens
  are persisted via store/set before the new session is delivered."
  [client did cb]
  (let [session-store (:session-store client)
        stored (store/get session-store did)]
    (cond
      (not stored)
      (cb {:error "TokenRefreshError"
           :message "Session deleted."
           :did did
           ::xrpc-client/session-expired? true})

      (not (get-in stored [:tokens :refresh_token]))
      (do (store/del session-store did)
          (cb {:error "TokenRefreshError"
               :message "No refresh token available."
               :did did
               ::xrpc-client/session-expired? true}))

      :else
      ;; Re-verify the issuer for this DID before using the refresh token.
      (resolve client did
               (fn [{:keys [error identity iss] :as resp}]
                 (cond
                   error
                   (cb resp)

                   ;; legacy rows have no :iss; adopt the resolved issuer
                   (and (:iss stored) (not (= iss (:iss stored))))
                   (do (store/del session-store did)
                       (cb {:error "TokenRefreshError"
                            :message "Issuer mismatch."
                            :did did
                            ::xrpc-client/session-expired? true}))

                   :else
                   (let [iss (or (:iss stored) iss)
                         aud (identity/did-doc-pds (:did-doc identity))]
                     (issuer-metadata
                      client iss
                      (fn [{:keys [error] :as issuer}]
                        (if error
                          (cb issuer)
                          (token-request
                           client
                           {:issuer issuer :dpop-key (:dpop-key stored)}
                           {:grant_type "refresh_token"
                            :refresh_token (get-in stored [:tokens :refresh_token])}
                           (fn [{:keys [error] :as token-resp}]
                             (cond
                               (not error)
                               ;; pin :sub to the session's DID; the token
                               ;; response's sub is not re-validated here
                               (let [session-data (merge stored
                                                         {:iss iss
                                                          :did-doc (:did-doc identity)
                                                          :tokens (tokens+expiry iss aud (assoc token-resp :sub did))}
                                                         (when (:handle identity)
                                                           {:handle (:handle identity)}))]
                                 (store/set session-store did session-data)
                                 (cb (oauth-session client session-data)))

                               (= "invalid_grant" error)
                               (recover-invalid-grant client did (:tokens stored) token-resp cb)

                               :else
                               (cb token-resp))))))))))))))

(defn- refresh-session
  "xrpc-client/Session refresh for OAuth sessions (wired in oauth-session
  metadata).

  Single-flighted per DID: concurrent refreshes for one DID across XRPC
  clients sharing this OAuth client result in a single token request, with
  every caller receiving the same result."
  [client {:keys [did]} cb]
  (let [inflight (::refresh-inflight client)
        [prev _] (swap-vals! inflight
                             (fn [m]
                               (if (contains? m did)
                                 (update m did conj cb)
                                 (assoc m did [cb]))))]
    (when (not (contains? prev did))
      (run-refresh client did
                   (fn [result]
                     (let [[m _] (swap-vals! inflight dissoc did)]
                       (run! #(% result) (get m did))))))))

(defn refresh
  "Force-refresh the stored OAuth session for this DID.

  Async; resolves to the refreshed session or an {:error ...} map."
  [client did & {:as opts}]
  (let [[cb val] (i/platform-async opts)]
    (if (store/get (:session-store client) did)
      (refresh-session client {:did did} cb)
      (cb {:error "SessionNotFound" :did did}))
    val))

;; -----------------------------------------------------------------------------
;; Revocation
;; -----------------------------------------------------------------------------

(defn revoke
  "Sign out: best-effort token revocation at the authorization server, then
  delete the stored session. The local session is deleted even when the
  server-side revocation fails.

  Async; resolves to {:did did :revoked true} when a stored session was
  deleted, {:did did :revoked false} when there was none."
  [client did & {:as opts}]
  (let [[cb val] (i/platform-async opts)
        session-store (:session-store client)]
    (if-let [session-data (store/get session-store did)]
      (revoke-tokens client session-data
                     (fn [_]
                       (store/del session-store did)
                       (cb {:did did :revoked true})))
      (cb {:did did :revoked false}))
    val))
