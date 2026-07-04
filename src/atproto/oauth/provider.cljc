(ns atproto.oauth.provider
  "atproto OAuth authorization server (provider).

  Aggressively-subsetted port of the reference
  packages/oauth/oauth-provider (oauth-provider.ts): PAR-only
  authorization code flow with PKCE (S256), DPoP-bound access and
  refresh tokens, `none` and `private_key_jwt` client authentication,
  refresh-token rotation with reuse detection, and a resource-server
  verifier hook. Login/consent UI is delivered as prompts the host app
  renders (atproto.oauth.provider.ring wires hooks), never ported.

  Wire-facing entry points (pushed-authorization-request, token, revoke)
  return OAuth-shaped error maps ({:error \"invalid_grant\" :message ..
  :status .. (:dpop-nonce ..)}); verify-access-token returns SDK-shaped
  errors for WS-08's auth middleware. All entry points are async per the
  SDK convention."
  (:require [clojure.string :as str]
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.crypto :as crypto]
            [atproto.runtime.http :as http]
            [atproto.runtime.jwt :as jwt]
            [atproto.oauth.provider.store :as store]
            [atproto.oauth.provider.dpop :as dpop]
            [atproto.oauth.provider.client :as client]))

;; Lifetimes (seconds)
(def par-request-ttl 300)
(def default-access-token-ttl 3600)
(def default-refresh-token-ttl (* 90 24 3600))

(def request-uri-prefix "urn:ietf:params:oauth:request_uri:")

(defn- async-opts [opts] (select-keys opts [:channel :callback :promise]))

(defn- oauth-error
  ([code message] (oauth-error code message 400))
  ([code message status]
   {:error code :message message :status status}))

(defn- dpop-error->oauth
  "Map a dpop/check-proof error to the OAuth wire shape."
  [{:keys [error message dpop-nonce]}]
  (if (= "UseDpopNonce" error)
    (assoc (oauth-error "use_dpop_nonce" message) :dpop-nonce dpop-nonce)
    (oauth-error "invalid_dpop_proof" message)))

(defn- client-error->oauth
  [{:keys [error message status]}]
  (case error
    ("InvalidClientId" "InvalidClientMetadata")
    (oauth-error "invalid_client" message 400)
    "invalid_client"
    (oauth-error "invalid_client" message (or status 401))
    (oauth-error "invalid_request" message (or status 400))))

;; -----------------------------------------------------------------------------
;; Provider
;; -----------------------------------------------------------------------------

(defn- loopback-issuer?
  [{:keys [protocol host]}]
  (and (= :http protocol)
       (contains? #{"localhost" "127.0.0.1" "[::1]"} host)))

(defn create
  "Create a provider.

  config:
    :issuer      (required) https origin of this server (http allowed
                 for loopback hosts, for dev/tests)
    :keyset      (required) private JWKS used to sign access tokens;
                 must contain an ES256 key
    :stores      map of store implementations (atproto.oauth.provider.store):
                 :request-store :token-store :account-store :device-store
                 :replay-store, optional :client-store. Missing stores
                 default to in-memory implementations (:account-store has
                 no accounts unless provided).
    :access-token-mode  :stateless (default; JWT-only verification) or
                 :light (verify-access-token also requires the token row)
    :dpop        {:secret bytes :rotation-interval-ms n} (defaults used
                 when absent)
    :access-token-ttl / :refresh-token-ttl  lifetimes in seconds
    :metadata    extra entries merged into the AS metadata document
    :trusted-clients  set of client-ids that skip the consent prompt
                 (ClientStore-backed first-party clients always do)"
  [{:keys [issuer keyset stores access-token-mode metadata
           access-token-ttl refresh-token-ttl trusted-clients]
    :as config}]
  (let [parsed (when (string? issuer) (http/parse-url issuer))]
    (cond
      (nil? parsed)
      {:error "InvalidIssuer" :message "issuer must be a URL."}

      (not (or (= :https (:protocol parsed)) (loopback-issuer? parsed)))
      {:error "InvalidIssuer" :message "issuer must be https (or a loopback http URL)."}

      (or (:path parsed) (:query-params parsed) (:fragment parsed))
      {:error "InvalidIssuer" :message "issuer must be an origin without path or query."}

      :else
      (let [signing-jwk (first (jwt/query-jwks keyset {:alg "ES256"}))]
        (if (nil? signing-jwk)
          {:error "InvalidKeyset" :message "keyset must contain an ES256 signing key."}
          (let [stores (merge (store/memory-stores) stores)]
            {:issuer issuer
             :keyset keyset
             :signing-jwk signing-jwk
             :stores stores
             :registry (client/registry :client-store (:client-store stores))
             :dpop (dpop/create (assoc (:dpop config)
                                       :replay-store (:replay-store stores)))
             :access-token-mode (or access-token-mode :stateless)
             :access-token-ttl (or access-token-ttl default-access-token-ttl)
             :refresh-token-ttl (or refresh-token-ttl default-refresh-token-ttl)
             :trusted-clients (set trusted-clients)
             :extra-metadata metadata}))))))

(defn metadata
  "The OAuth authorization server metadata document. Sync."
  [{:keys [issuer extra-metadata]}]
  (merge
   {:issuer issuer
    :authorization_endpoint (str issuer "/oauth/authorize")
    :token_endpoint (str issuer "/oauth/token")
    :pushed_authorization_request_endpoint (str issuer "/oauth/par")
    :revocation_endpoint (str issuer "/oauth/revoke")
    :jwks_uri (str issuer "/oauth/jwks")
    :response_types_supported ["code"]
    :grant_types_supported ["authorization_code" "refresh_token"]
    :code_challenge_methods_supported ["S256"]
    :token_endpoint_auth_methods_supported ["none" "private_key_jwt"]
    :token_endpoint_auth_signing_alg_values_supported ["ES256"]
    :scopes_supported ["atproto" "transition:generic" "transition:chat.bsky"]
    :subject_types_supported ["public"]
    :authorization_response_iss_parameter_supported true
    :require_pushed_authorization_requests true
    :require_request_uri_registration true
    :client_id_metadata_document_supported true
    :dpop_signing_alg_values_supported ["ES256" "ES256K"]}
   extra-metadata))

(defn jwks
  "Public JWKS document. Sync."
  [{:keys [keyset]}]
  (jwt/public-jwks keyset))

(defn next-dpop-nonce
  "Current DPoP nonce for response headers. Sync."
  [provider]
  (dpop/next-nonce (:dpop provider)))

;; -----------------------------------------------------------------------------
;; Shared steps
;; -----------------------------------------------------------------------------

(defn- resolve-and-authenticate-client
  "get-client + authenticate-client from wire params; cb receives the
  client entry or an OAuth error map."
  [{:keys [registry issuer stores] :as provider} params cb]
  (let [client-id (:client_id params)]
    (if (not (string? client-id))
      (cb (oauth-error "invalid_request" "client_id is required."))
      (client/get-client
       registry client-id
       :callback
       (fn [{:keys [error] :as resolved}]
         (if error
           (cb (client-error->oauth resolved))
           (client/authenticate-client
            registry resolved
            {:client-assertion-type (:client_assertion_type params)
             :client-assertion (:client_assertion params)}
            :issuer issuer
            :replay-store (:replay-store stores)
            :callback
            (fn [{:keys [error] :as auth}]
              (if error
                (cb (client-error->oauth auth))
                (cb (assoc resolved :auth-method (:method auth))))))))))))

(defn- check-dpop
  "DPoP proof check for an AS endpoint (nonce required). Returns the
  proof result or an OAuth error map. Sync."
  [provider {:keys [dpop-proof method url access-token]}]
  (let [result (dpop/check-proof (:dpop provider)
                                 {:proof dpop-proof
                                  :method method
                                  :url url
                                  :access-token access-token
                                  :nonce-required? true})]
    (if (:error result)
      (dpop-error->oauth result)
      result)))

(defn- request-id->uri [request-id] (str request-uri-prefix request-id))

(defn- request-uri->id
  [request-uri]
  (when (and (string? request-uri)
             (str/starts-with? request-uri request-uri-prefix))
    (subs request-uri (count request-uri-prefix))))

(defn- scope-allowed?
  "Every requested scope is registered in the client metadata."
  [client-metadata requested]
  (let [registered (set (str/split (or (:scope client-metadata) "") #" +"))]
    (every? registered (str/split requested #" +"))))

;; -----------------------------------------------------------------------------
;; Pushed Authorization Request
;; -----------------------------------------------------------------------------

(defn- validate-par-params
  "The authorization-request data to store, or an OAuth error map."
  [{:keys [client-id metadata] :as resolved}
   {:keys [response_type redirect_uri code_challenge code_challenge_method
           scope state login_hint] :as params}
   dpop-jkt]
  (let [redirect-uri (or redirect_uri (first (:redirect_uris metadata)))
        scope (or scope "atproto")]
    (cond
      (not= "code" response_type)
      (oauth-error "invalid_request" "response_type must be \"code\".")

      (not (client/redirect-uri-allowed? resolved redirect-uri))
      (oauth-error "invalid_request" "redirect_uri is not registered for this client.")

      (not (string? code_challenge))
      (oauth-error "invalid_request" "code_challenge is required (PKCE).")

      (not= "S256" code_challenge_method)
      (oauth-error "invalid_request" "code_challenge_method must be S256.")

      (not (scope-allowed? metadata scope))
      (oauth-error "invalid_scope" "Requested scope exceeds the client's registered scope.")

      (not (str/includes? (str " " scope " ") " atproto "))
      (oauth-error "invalid_scope" "scope must include \"atproto\".")

      :else
      (let [now (crypto/now)]
        (cond-> {:client-id client-id
                 :redirect-uri redirect-uri
                 :scope scope
                 :code-challenge code_challenge
                 :code-challenge-method code_challenge_method
                 :dpop-jkt dpop-jkt
                 :created-at now
                 :expires-at (+ now par-request-ttl)}
          state (assoc :state state)
          login_hint (assoc :login-hint login_hint))))))

(defn pushed-authorization-request
  "Handle POST /oauth/par.

  request: {:params form/JSON params, :dpop-proof header value,
            :method :post, :url endpoint URL}
  Async. Success: {:request-uri \"urn:ietf:params:oauth:request_uri:..\"
                   :expires-in 300 :dpop-nonce next}
  Error: OAuth error map with :status (+ :dpop-nonce on use_dpop_nonce)."
  [{:keys [stores] :as provider} {:keys [params] :as request} & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (resolve-and-authenticate-client
     provider params
     (fn [{:keys [error] :as resolved}]
       (if error
         (cb resolved)
         (let [proof (check-dpop provider request)]
           (if (:error proof)
             (cb proof)
             (let [data (validate-par-params resolved params (:jkt proof))]
               (if (:error data)
                 (cb data)
                 (let [request-id (str "req-" (crypto/generate-nonce 24))]
                   (store/create-request! (:request-store stores) request-id data)
                   (cb {:request-uri (request-id->uri request-id)
                        :expires-in par-request-ttl
                        :dpop-nonce (next-dpop-nonce provider)})))))))))
    val))

;; -----------------------------------------------------------------------------
;; Authorize flow
;; -----------------------------------------------------------------------------

(defn- read-active-request
  "The stored authorization request for a request_uri, or an error map.
  Expired requests are deleted."
  [{:keys [stores]} request-uri]
  (let [request-store (:request-store stores)
        request-id (request-uri->id request-uri)
        data (when request-id (store/read-request request-store request-id))]
    (cond
      (nil? data)
      (oauth-error "invalid_request" "Unknown request_uri.")

      (< (:expires-at data) (crypto/now))
      (do (store/delete-request! request-store request-id)
          (oauth-error "invalid_request" "The authorization request expired."))

      :else
      {:request-id request-id :data data})))

(defn- issue-code!
  "Bind a code to the authorization request and build the redirect result."
  [{:keys [stores issuer]} request-id {:keys [redirect-uri state] :as data} sub]
  (let [code (str "cod-" (crypto/generate-nonce 24))]
    (store/update-request! (:request-store stores) request-id
                           {:code code :sub sub :authorized-at (crypto/now)})
    {:redirect {:uri redirect-uri
                :params (cond-> {:code code :iss issuer}
                          state (assoc :state state))}}))

(defn- deny-redirect
  [{:keys [issuer stores]} request-id {:keys [redirect-uri state]}]
  (store/delete-request! (:request-store stores) request-id)
  {:redirect {:uri redirect-uri
              :params (cond-> {:error "access_denied"
                               :error_description "The user denied the request."
                               :iss issuer}
                        state (assoc :state state))}})

(defn- matching-device-account
  "Pick the signed-in device account for this request: the login_hint
  match when hinted, else the single remembered account."
  [{:keys [stores]} device-id {:keys [login-hint]}]
  (when device-id
    (let [account-store (:account-store stores)
          accounts (filter :remember? (store/list-device-accounts account-store device-id))
          hinted (when login-hint
                   (filter (fn [{:keys [sub]}]
                             (let [account (store/get-account account-store sub)]
                               (or (= login-hint sub)
                                   (= login-hint (:handle account)))))
                           accounts))]
      (cond
        login-hint (first hinted)
        (= 1 (count accounts)) (first accounts)
        :else nil))))

(defn- consent-skippable?
  [{:keys [trusted-clients stores]} {:keys [client-id trusted?]} sub]
  (or trusted?
      (contains? trusted-clients client-id)
      (some? (store/get-authorized-client (:account-store stores) sub client-id))))

(defn- authorize-for-account
  "Redirect (consent already granted / trusted client) or consent prompt."
  [provider resolved request-id data sub]
  (if (consent-skippable? provider resolved sub)
    (issue-code! provider request-id data sub)
    {:prompt :consent
     :request {:request-uri (request-id->uri request-id)
               :client-id (:client-id data)
               :client-metadata (:metadata resolved)
               :scope (:scope data)
               :sub sub
               :account (store/get-account (get-in provider [:stores :account-store]) sub)}}))

(defn authorize
  "Drive GET /oauth/authorize?client_id=..&request_uri=.. for the
  current device session.

  Async. Yields one of:
    {:redirect {:uri .. :params {:code .. :state .. :iss ..}}}
    {:prompt :login   :request {..}}   ;; host app renders sign-in
    {:prompt :consent :request {..}}   ;; host app renders consent
    {:error ..}"
  [{:keys [registry] :as provider}
   {:keys [client-id request-uri device-id]} & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))
        {:keys [error request-id data] :as active} (read-active-request provider request-uri)]
    (cond
      error
      (cb active)

      (not= client-id (:client-id data))
      (cb (oauth-error "invalid_request" "client_id does not match the pushed request."))

      :else
      (client/get-client
       registry client-id
       :callback
       (fn [{:keys [error] :as resolved}]
         (if error
           (cb (client-error->oauth resolved))
           (if-let [{:keys [sub]} (matching-device-account provider device-id data)]
             (cb (authorize-for-account provider resolved request-id data sub))
             (cb {:prompt :login
                  :request {:request-uri request-uri
                            :client-id client-id
                            :client-metadata (:metadata resolved)
                            :scope (:scope data)
                            :login-hint (:login-hint data)}}))))))
    val))

(defn complete-sign-in
  "Called by the host app after (or with) user authentication.

  args: :device-id, :request-uri, :remember?, and either :sub (already
  authenticated by the host app) or :credentials {:identifier :password}
  (verified against the account store).

  Binds the account to the device session and re-enters the authorize
  flow. Async; yields the authorize result shapes or
  {:error \"invalid_credentials\" ...}."
  [{:keys [stores registry] :as provider}
   {:keys [device-id request-uri sub credentials remember?]} & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))
        account-store (:account-store stores)
        account (cond
                  sub (store/get-account account-store sub)
                  credentials (store/authenticate-account account-store credentials))
        {:keys [error request-id data] :as active} (read-active-request provider request-uri)]
    (cond
      (nil? account)
      (cb (oauth-error "invalid_credentials" "Invalid identifier or password." 401))

      error
      (cb active)

      (nil? device-id)
      (cb (oauth-error "invalid_request" "A device session is required."))

      :else
      (do
        (when (nil? (store/read-device (:device-store stores) device-id))
          (store/create-device! (:device-store stores) device-id {:created-at (crypto/now)}))
        (store/add-device-account! account-store device-id (:sub account) remember?)
        (client/get-client
         registry (:client-id data)
         :callback
         (fn [{:keys [error] :as resolved}]
           (if error
             (cb (client-error->oauth resolved))
             (cb (authorize-for-account provider resolved request-id data (:sub account))))))))
    val))

(defn accept
  "The user approved the consent prompt: record the client authorization
  and issue the code. Async; yields {:redirect ..} or {:error ..}."
  [{:keys [stores] :as provider} {:keys [device-id request-uri sub]} & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))
        account-store (:account-store stores)
        {:keys [error request-id data] :as active} (read-active-request provider request-uri)]
    (cond
      error
      (cb active)

      (nil? (and device-id (store/get-device-account account-store device-id sub)))
      (cb (oauth-error "invalid_request" "No signed-in session for this account." 401))

      :else
      (do (store/set-authorized-client! account-store sub (:client-id data)
                                        {:scope (:scope data)
                                         :authorized-at (crypto/now)})
          (cb (issue-code! provider request-id data sub))))
    val))

(defn reject
  "The user denied the consent prompt. Async; yields
  {:redirect {.. :params {:error \"access_denied\" ..}}} or {:error ..}."
  [provider {:keys [request-uri]} & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))
        {:keys [error request-id data] :as active} (read-active-request provider request-uri)]
    (if error
      (cb active)
      (cb (deny-redirect provider request-id data)))
    val))

;; -----------------------------------------------------------------------------
;; Token endpoint
;; -----------------------------------------------------------------------------

(defn- sign-access-token
  "Signed access-token JWT (typ at+jwt) for a token row."
  [{:keys [issuer signing-jwk access-token-ttl]} token-id {:keys [sub client-id scope dpop-jkt]}]
  (let [now (crypto/now)]
    (jwt/generate signing-jwk
                  {:alg "ES256"
                   :typ "at+jwt"
                   :kid (jwt/jwk-kid signing-jwk)}
                  {:iss issuer
                   :aud issuer
                   :sub sub
                   :client_id client-id
                   :scope scope
                   :jti token-id
                   :iat now
                   :exp (+ now access-token-ttl)
                   ;; string key so Nimbus serializes cnf as {"jkt": ..}
                   :cnf {"jkt" dpop-jkt}})))

(defn- token-response
  [{:keys [access-token-ttl] :as provider} token-id {:keys [sub scope] :as data} refresh-token]
  {:access_token (sign-access-token provider token-id data)
   :token_type "DPoP"
   :expires_in access-token-ttl
   :refresh_token refresh-token
   :scope scope
   :sub sub
   :dpop-nonce (next-dpop-nonce provider)})

(defn- handle-code-grant
  [{:keys [stores refresh-token-ttl] :as provider}
   {:keys [client-id] :as resolved}
   {:keys [code code_verifier redirect_uri] :as params}
   proof cb]
  (let [request-store (:request-store stores)
        consumed (store/consume-request-code! request-store code)
        {:keys [data]} consumed
        now (crypto/now)]
    (cond
      (not (string? code))
      (cb (oauth-error "invalid_request" "code is required."))

      (nil? consumed)
      (cb (oauth-error "invalid_grant" "Invalid or already-used code."))

      (< (:expires-at data) now)
      (cb (oauth-error "invalid_grant" "The authorization code expired."))

      (not= client-id (:client-id data))
      (cb (oauth-error "invalid_grant" "The code was issued to another client."))

      (and (:dpop-jkt data) (not= (:dpop-jkt data) (:jkt proof)))
      (cb (oauth-error "invalid_grant" "DPoP key mismatch with the pushed request."))

      (and redirect_uri (not= redirect_uri (:redirect-uri data)))
      (cb (oauth-error "invalid_grant" "redirect_uri does not match the pushed request."))

      (not (and (string? code_verifier)
                (= (:code-challenge data)
                   (crypto/base64url-encode (crypto/sha256 code_verifier)))))
      (cb (oauth-error "invalid_grant" "PKCE code_verifier check failed."))

      :else
      (let [token-id (str "tok-" (crypto/generate-nonce 24))
            refresh-token (str "ref-" (crypto/generate-nonce 32))
            token-data {:sub (:sub data)
                        :client-id client-id
                        :scope (:scope data)
                        :code code
                        :dpop-jkt (:jkt proof)
                        :created-at now
                        :refresh-expires-at (+ now refresh-token-ttl)}]
        (store/create-token! (:token-store stores) token-id token-data refresh-token)
        (cb (token-response provider token-id token-data refresh-token))))))

(defn- handle-refresh-grant
  [{:keys [stores refresh-token-ttl] :as provider}
   {:keys [client-id] :as resolved}
   {:keys [refresh_token] :as params}
   proof cb]
  (let [token-store (:token-store stores)
        {:keys [token-id data refresh-token] :as found}
        (when (string? refresh_token)
          (store/find-by-refresh-token token-store refresh_token))]
    (cond
      (not (string? refresh_token))
      (cb (oauth-error "invalid_request" "refresh_token is required."))

      (nil? found)
      (cb (oauth-error "invalid_grant" "Unknown refresh token."))

      ;; reuse detection: a rotated (used) refresh token kills the session
      (not= refresh_token refresh-token)
      (do (store/delete-token! token-store token-id)
          (cb (oauth-error "invalid_grant" "The refresh token was already used.")))

      (not= client-id (:client-id data))
      (cb (oauth-error "invalid_grant" "The token belongs to another client."))

      (not= (:dpop-jkt data) (:jkt proof))
      (cb (oauth-error "invalid_grant" "DPoP key mismatch."))

      (< (:refresh-expires-at data) (crypto/now))
      (do (store/delete-token! token-store token-id)
          (cb (oauth-error "invalid_grant" "The refresh token expired.")))

      :else
      (let [new-token-id (str "tok-" (crypto/generate-nonce 24))
            new-refresh-token (str "ref-" (crypto/generate-nonce 32))
            new-data (assoc data
                            :used-refresh-tokens
                            (vec (take-last 10 (conj (or (:used-refresh-tokens data) [])
                                                     refresh_token))))]
        (store/rotate-token! token-store token-id new-token-id new-refresh-token new-data)
        (cb (token-response provider new-token-id new-data new-refresh-token))))))

(defn token
  "Handle POST /oauth/token (authorization_code | refresh_token).

  request: {:params .. :dpop-proof .. :method :post :url ..}
  Async. Success: token response map (:access_token, :token_type \"DPoP\",
  :refresh_token, :expires_in, :scope, :sub, :dpop-nonce). Error: OAuth
  error map + :status."
  [provider {:keys [params] :as request} & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (resolve-and-authenticate-client
     provider params
     (fn [{:keys [error] :as resolved}]
       (if error
         (cb resolved)
         (let [proof (check-dpop provider request)]
           (if (:error proof)
             (cb proof)
             (case (:grant_type params)
               "authorization_code"
               (handle-code-grant provider resolved params proof cb)

               "refresh_token"
               (handle-refresh-grant provider resolved params proof cb)

               (cb (oauth-error "unsupported_grant_type"
                                (str "Unsupported grant_type: "
                                     (pr-str (:grant_type params)))))))))))
    val))

;; -----------------------------------------------------------------------------
;; Revocation
;; -----------------------------------------------------------------------------

(defn revoke
  "Handle POST /oauth/revoke. Accepts an access token (JWT) or a refresh
  token; deletes the token row when found. Async; always yields {} per
  RFC 7009 (unknown tokens are not an error)."
  [{:keys [stores keyset] :as provider} {:keys [params]} & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))
        token-store (:token-store stores)
        token-param (:token params)]
    (when (string? token-param)
      (if-let [{:keys [token-id]} (store/find-by-refresh-token token-store token-param)]
        (store/delete-token! token-store token-id)
        ;; try as an access-token JWT; revoke its token row by jti
        (let [{:keys [claims]} (jwt/parse token-param)]
          (when-let [token-id (:jti claims)]
            (store/delete-token! token-store token-id)))))
    (cb {})
    val))

;; -----------------------------------------------------------------------------
;; Resource-server verification (consumed by WS-08 auth middleware)
;; -----------------------------------------------------------------------------

(defn verify-access-token
  "Resource-server entry point: validate an access token and its DPoP
  binding.

  request: {:token .. :dpop-proof .. :method .. :url ..}
  Async. Success: {:sub did :scope .. :client-id .. :token-id .. :exp ..}
  Error: {:error \"InvalidToken\"|\"InvalidDpopProof\"|\"UseDpopNonce\"
          :message .. :status 401}."
  [{:keys [issuer keyset stores access-token-mode] :as provider}
   {:keys [token dpop-proof method url]} & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))
        finish
        (fn [{:keys [header claims] :as verified}]
          (let [{:keys [sub scope jti exp client_id cnf]} claims
                proof (dpop/check-proof (:dpop provider)
                                        {:proof dpop-proof
                                         :method method
                                         :url url
                                         :access-token token})]
            (cond
              (not= "at+jwt" (:typ header))
              (cb {:error "InvalidToken" :status 401
                   :message "Not an access token (typ must be at+jwt)."})

              (not= issuer (:iss claims))
              (cb {:error "InvalidToken" :status 401
                   :message "Unexpected access token issuer."})

              (:error proof)
              (cb (assoc proof :status 401))

              (not= (:jkt cnf) (:jkt proof))
              (cb {:error "InvalidDpopProof" :status 401
                   :message "DPoP proof key does not match the token's cnf.jkt."})

              (and (= :light access-token-mode)
                   (nil? (store/read-token (:token-store stores) jti)))
              (cb {:error "InvalidToken" :status 401
                   :message "The token was revoked."})

              :else
              (cb {:sub sub
                   :scope scope
                   :client-id client_id
                   :token-id jti
                   :exp exp}))))]
    #?(:clj
       (let [verified @(jwt/verify token {:jwks (jwt/public-jwks keyset)})]
         (if (:error verified)
           (cb {:error "InvalidToken" :status 401
                :message (or (:message verified) (:error verified))})
           (finish verified)))
       :cljs (cb {:error "NotImplemented"}))
    val))
