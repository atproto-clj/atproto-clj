(ns atproto.oauth.provider.client
  "OAuth client registry for the provider: client_id resolution
  (metadata document fetch or loopback synthesis), atproto-profile
  validation, caching, and client authentication.

  Subset port of oauth-provider/src/client/client-manager.ts and
  oauth-types/src/atproto-loopback-client-metadata.ts."
  (:require [clojure.string :as str]
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.http :as http]
            [atproto.runtime.json :as json]
            [atproto.runtime.crypto :as crypto]
            [atproto.runtime.jwt :as jwt]
            [atproto.oauth.provider.store :as store]))

(def client-assertion-jwt-bearer
  "urn:ietf:params:oauth:client-assertion-type:jwt-bearer")

;; How long fetched client metadata (and JWKS) may be reused, in seconds.
(def metadata-ttl 300)

(defn- async-opts [opts] (select-keys opts [:channel :callback :promise]))

(defn- invalid-metadata
  [message]
  {:error "InvalidClientMetadata" :message message})

(defn- invalid-client-id
  [message]
  {:error "InvalidClientId" :message message})

;; -----------------------------------------------------------------------------
;; Loopback clients
;; -----------------------------------------------------------------------------

(defn loopback-client-id?
  "Whether client-id is an atproto loopback client id
  (http://localhost[/][?...])."
  [client-id]
  (and (string? client-id)
       (or (= client-id "http://localhost")
           (str/starts-with? client-id "http://localhost/")
           (str/starts-with? client-id "http://localhost?"))))

(defn- loopback-redirect-uri?
  [uri]
  (when-let [{:keys [protocol host]} (when (string? uri) (http/parse-url uri))]
    (and (= :http protocol)
         (contains? #{"127.0.0.1" "[::1]"} host))))

(defn loopback-client-metadata
  "Synthesized metadata for a loopback client id
  (atproto-loopback-client-metadata.ts), or {:error ...}."
  [client-id]
  (let [{:keys [protocol host path query-params]} (http/parse-url client-id)
        redirect-uris (let [r (:redirect_uri query-params)]
                        (cond
                          (nil? r) ["http://127.0.0.1/" "http://[::1]/"]
                          (coll? r) (vec r)
                          :else [r]))
        scope (or (:scope query-params) "atproto")]
    (cond
      (or (not= :http protocol)
          (not= "localhost" host)
          (not (contains? #{nil "/"} path)))
      (invalid-client-id "Loopback client_id must be http://localhost with no path.")

      (not (every? loopback-redirect-uri? redirect-uris))
      (invalid-metadata "Loopback redirect_uris must be http://127.0.0.1 or http://[::1] URLs.")

      :else
      {:client_id client-id
       :client_name "Loopback client"
       :response_types ["code"]
       :grant_types ["authorization_code" "refresh_token"]
       :scope scope
       :redirect_uris redirect-uris
       :token_endpoint_auth_method "none"
       :application_type "native"
       :dpop_bound_access_tokens true})))

;; -----------------------------------------------------------------------------
;; Metadata validation (atproto profile)
;; -----------------------------------------------------------------------------

(defn- custom-scheme-redirect-uri?
  "Private-use (reversed domain) scheme, e.g. com.example.app:/callback."
  [uri]
  (when (string? uri)
    (when-let [[_ scheme] (re-matches #"([a-zA-Z][a-zA-Z0-9+.-]*):.*" uri)]
      (and (str/includes? scheme ".")
           (not (contains? #{"http" "https"} (str/lower-case scheme)))))))

(defn- validate-redirect-uris
  [application-type redirect-uris]
  (cond
    (not (and (vector? redirect-uris) (seq redirect-uris)))
    (invalid-metadata "redirect_uris is required.")

    (= "native" application-type)
    (when-not (every? #(or (custom-scheme-redirect-uri? %)
                           (loopback-redirect-uri? %))
                      redirect-uris)
      (invalid-metadata (str "Native client redirect_uris must use a private-use"
                             " scheme or a loopback (http://127.0.0.1, http://[::1]) address.")))

    :else
    (when-not (every? #(and (string? %) (str/starts-with? % "https://"))
                      redirect-uris)
      (invalid-metadata "Web client redirect_uris must be https:// URLs."))))

(defn validate-client-metadata
  "Validate a client metadata document against the atproto profile.
  Returns the metadata (normalized) or {:error \"InvalidClientMetadata\"}."
  [client-id {:keys [redirect_uris grant_types response_types scope
                     application_type token_endpoint_auth_method
                     token_endpoint_auth_signing_alg
                     dpop_bound_access_tokens jwks jwks_uri]
              :as metadata}]
  (let [application-type (or application_type "web")
        grant-types (set (or grant_types ["authorization_code"]))
        response-types (set (or response_types ["code"]))
        auth-method (or token_endpoint_auth_method "none")
        scopes (set (when (string? scope) (str/split scope #" +")))]
    (or
     (when (not= client-id (:client_id metadata))
       (invalid-metadata "client_id does not match the metadata document."))
     (when-not (contains? #{"web" "native"} application-type)
       (invalid-metadata (str "Unsupported application_type: " (pr-str application_type))))
     (validate-redirect-uris application-type redirect_uris)
     (when-not (contains? grant-types "authorization_code")
       (invalid-metadata "grant_types must include authorization_code."))
     (when-let [unsupported (seq (remove #{"authorization_code" "refresh_token"} grant-types))]
       (invalid-metadata (str "Unsupported grant_types: " (str/join ", " unsupported))))
     (when (not= #{"code"} response-types)
       (invalid-metadata "response_types must be [\"code\"]."))
     (when-not (contains? scopes "atproto")
       (invalid-metadata "scope must include \"atproto\"."))
     (when (not= true dpop_bound_access_tokens)
       (invalid-metadata "dpop_bound_access_tokens must be true."))
     (when-not (contains? #{"none" "private_key_jwt"} auth-method)
       (invalid-metadata (str "Unsupported token_endpoint_auth_method: "
                              (pr-str token_endpoint_auth_method))))
     (when (and (= "private_key_jwt" auth-method)
                (not (or (map? jwks) (string? jwks_uri))))
       (invalid-metadata "private_key_jwt clients must provide jwks or jwks_uri."))
     ;; spec: "Either this field or the jwks_uri field must be provided
     ;; for confidential clients, but not both."
     (when (and (map? jwks) (string? jwks_uri))
       (invalid-metadata "jwks and jwks_uri are mutually exclusive."))
     (when (and (= "private_key_jwt" auth-method)
                (some? token_endpoint_auth_signing_alg)
                (not= "ES256" token_endpoint_auth_signing_alg))
       (invalid-metadata (str "token_endpoint_auth_signing_alg must be ES256, got "
                              (pr-str token_endpoint_auth_signing_alg) ".")))
     (assoc metadata
            :application_type application-type
            :token_endpoint_auth_method auth-method))))

(defn redirect-uri-allowed?
  "Whether uri is acceptable for this client: an exact registered match,
  or (native clients) a registered loopback redirect_uri with any port."
  [{:keys [metadata]} uri]
  (let [{:keys [redirect_uris application_type]} metadata]
    (boolean
     (or (some #{uri} redirect_uris)
         (when (and (= "native" application_type) (loopback-redirect-uri? uri))
           (let [portless (fn [u]
                            (let [{:keys [protocol host path]} (http/parse-url u)]
                              [protocol host (or path "/")]))]
             (some #(and (loopback-redirect-uri? %) (= (portless %) (portless uri)))
                   redirect_uris)))))))

;; -----------------------------------------------------------------------------
;; Registry (fetch + cache)
;; -----------------------------------------------------------------------------

(defn registry
  "Create a client registry.

  opts:
    :client-store  optional atproto.oauth.provider.store/ClientStore
                   consulted before fetching over HTTP (first-party
                   clients; marked :trusted? true)
    :ttl           metadata cache TTL in seconds (default 300)"
  [& {:keys [client-store ttl]}]
  {:client-store client-store
   :ttl (or ttl metadata-ttl)
   :cache (atom {})})

(defn- fetch-json
  [url cb]
  (i/execute {::i/request {:method :get :url url}
              ::i/queue [json/client-interceptor
                         http/client-interceptor]}
             :callback
             (fn [{:keys [error status body] :as resp}]
               (cb (cond
                     error resp
                     (not (http/success? status)) (http/error-map resp)
                     (not (map? body)) (invalid-metadata "Expected a JSON document.")
                     :else body)))))

(defn- cache-get
  [{:keys [cache]} client-id]
  (when-let [{:keys [client expires-at]} (get @cache client-id)]
    (when (< (crypto/now) expires-at)
      client)))

(defn- cache-put!
  [{:keys [cache ttl]} client-id client]
  (swap! cache assoc client-id {:client client
                                :expires-at (+ (crypto/now) ttl)})
  client)

(defn- with-client-jwks
  "Attach :jwks to the client entry, fetching jwks_uri if needed."
  [{:keys [metadata] :as client} cb]
  (cond
    (map? (:jwks metadata))
    (cb (assoc client :jwks (:jwks metadata)))

    (string? (:jwks_uri metadata))
    (fetch-json (:jwks_uri metadata)
                (fn [{:keys [error] :as resp}]
                  (cb (if error
                        (invalid-metadata (str "Could not fetch client jwks: "
                                               (or (:message resp) (:error resp))))
                        (assoc client :jwks resp)))))

    :else (cb client)))

(defn get-client
  "Resolve client-id to validated client metadata.

  Async; yields {:client-id .. :metadata .. (:jwks ..) (:trusted? true)
  (:loopback? true)} or {:error \"InvalidClientId\"|\"InvalidClientMetadata\"}."
  [{:keys [client-store] :as registry} client-id & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))
        finish (fn [{:keys [error] :as client}]
                 (cb (if error client (cache-put! registry client-id client))))
        stored (when client-store
                 (store/get-client-metadata client-store client-id))
        cached (when-not stored (cache-get registry client-id))]
    (cond
      (loopback-client-id? client-id)
      (let [metadata (loopback-client-metadata client-id)]
        (if (:error metadata)
          (cb metadata)
          (cb {:client-id client-id :metadata metadata :loopback? true})))

      stored
      (let [metadata (validate-client-metadata client-id stored)]
        (if (:error metadata)
          (cb metadata)
          (with-client-jwks {:client-id client-id :metadata metadata :trusted? true}
            cb)))

      cached
      (cb cached)

      (not (and (string? client-id) (str/starts-with? client-id "https://")))
      (cb (invalid-client-id "client_id must be an https:// URL (or a loopback client)."))

      :else
      (fetch-json client-id
                  (fn [{:keys [error] :as resp}]
                    (if error
                      (cb (invalid-metadata (str "Could not fetch client metadata: "
                                                 (or (:message resp) (:error resp)))))
                      (let [metadata (validate-client-metadata client-id resp)]
                        (if (:error metadata)
                          (cb metadata)
                          (with-client-jwks {:client-id client-id :metadata metadata}
                            finish)))))))
    val))

;; -----------------------------------------------------------------------------
;; Client authentication
;; -----------------------------------------------------------------------------

(defn- invalid-client
  [message]
  {:error "invalid_client" :message message :status 401})

;; Assertions must be rejected past this lifetime even if their exp is
;; further out, and jti replay records never need to outlive it.
(def max-assertion-lifetime-s 3600)

(defn- verify-assertion-claims
  [{:keys [client-id]} {:keys [iss sub aud jti exp]} issuer replay-store]
  (cond
    (not= client-id iss)
    (invalid-client "client_assertion iss must be the client_id.")

    (not= client-id sub)
    (invalid-client "client_assertion sub must be the client_id.")

    (not (or (= issuer aud)
             (and (coll? aud) (some #{issuer} aud))))
    (invalid-client "client_assertion aud must be the issuer.")

    (not (number? exp))
    (invalid-client "client_assertion requires an exp claim.")

    (not (and (string? jti) (seq jti)))
    (invalid-client "client_assertion requires a jti claim.")

    ;; spec: "Authorization Servers must ensure uniqueness of jti values
    ;; over the full token validity time period" — track the jti until
    ;; the assertion itself expires (bounded by the max lifetime)
    (not (store/unique? replay-store "client-assertion" jti
                        (min (long exp)
                             (+ (crypto/now) max-assertion-lifetime-s))))
    (invalid-client "client_assertion jti was already used.")

    :else nil))

(defn authenticate-client
  "Verify the request's client authentication for a resolved client.

  credentials: {:client-assertion-type .. :client-assertion ..} (both
  nil for public clients).
  opts: :issuer (this AS issuer URL), :replay-store (jti replay).

  Async; yields {:method \"none\"|\"private_key_jwt\"} or
  {:error \"invalid_client\" :message .. :status 401}."
  [registry {:keys [client-id metadata jwks] :as client} credentials
   & {:keys [issuer replay-store] :as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))
        {:keys [client-assertion-type client-assertion]} credentials
        method (:token_endpoint_auth_method metadata "none")]
    (case method
      "none"
      (cb {:method "none"})

      "private_key_jwt"
      (cond
        (not= client-assertion-jwt-bearer client-assertion-type)
        (cb (invalid-client (str "client_assertion_type must be "
                                 client-assertion-jwt-bearer ".")))

        (not (string? client-assertion))
        (cb (invalid-client "client_assertion is required."))

        (not (map? jwks))
        (cb (invalid-client "No JWKS available for this client."))

        :else
        #?(:clj
           ;; leeway: the spec says assertions "generated less than a
           ;; minute ago" must not be rejected, so tolerate clock skew
           (let [verified @(jwt/verify client-assertion {:jwks jwks} :leeway 30)]
             (cond
               (:error verified)
               (cb (invalid-client (str "Invalid client_assertion: "
                                        (or (:message verified) (:error verified)))))

               ;; atproto profile: ES256 is the required signing system
               (not= "ES256" (get-in verified [:header :alg]))
               (cb (invalid-client (str "client_assertion alg must be ES256, got "
                                        (pr-str (get-in verified [:header :alg])) ".")))

               :else
               (if-let [err (verify-assertion-claims client (:claims verified)
                                                     issuer replay-store)]
                 (cb err)
                 (cb {:method "private_key_jwt"}))))
           :cljs (cb {:error "NotImplemented"})))

      (cb (invalid-client (str "Unsupported token_endpoint_auth_method: " method))))
    val))
