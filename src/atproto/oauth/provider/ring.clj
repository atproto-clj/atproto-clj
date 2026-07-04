(ns atproto.oauth.provider.ring
  "Ring routes for the OAuth provider endpoints (clj only).

  Covers the machine-to-machine surface of the reference
  create-oauth-middleware.ts:
    GET  /.well-known/oauth-authorization-server
    GET  /.well-known/oauth-protected-resource   (when :resource is set)
    GET  /oauth/jwks
    POST /oauth/par
    POST /oauth/token
    POST /oauth/revoke
    GET  /oauth/authorize                         (delegates to hooks)
    POST /oauth/authorize/sign-in|accept|reject   (hook-driven)

  The login/consent UI is not served here: GET /oauth/authorize and the
  sign-in/accept/reject POSTs delegate to :hooks the host app supplies
  (see `routes`). DPoP-Nonce headers are attached to every PAR/token
  response, and errors use the OAuth JSON body shape."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :refer [keywordize-keys]]
            [atproto.runtime.http :as http]
            [atproto.runtime.json :as json]
            [atproto.oauth.provider :as provider])
  (:import [java.io ByteArrayOutputStream]))

(set! *warn-on-reflection* true)

(defn- body-string
  [body]
  (cond
    (nil? body) nil
    (string? body) body
    :else (let [os (ByteArrayOutputStream.)]
            (io/copy body os)
            (let [s (.toString os "UTF-8")]
              (when-not (str/blank? s) s)))))

(defn- parse-body
  "Parse a POST body as either JSON or form-urlencoded into a keyword map."
  [{:keys [headers body]}]
  (let [content-type (or (get headers "content-type") "")
        s (body-string body)]
    (cond
      (nil? s) {}
      (str/starts-with? content-type "application/json") (json/read-str s)
      :else (http/query-string->query-params s))))

(defn- dpop-header
  [{:keys [headers]}]
  (get headers "dpop"))

(defn- request-url
  [{:keys [scheme server-name server-port uri]}]
  (str (name (or scheme :https)) "://" server-name
       (when (and server-port (not (#{80 443} server-port))) (str ":" server-port))
       uri))

(defn- json-response
  ([status body] (json-response status body nil))
  ([status body extra-headers]
   {:status status
    :headers (merge {"content-type" "application/json"
                     "cache-control" "no-store"
                     "pragma" "no-cache"} extra-headers)
    :body (json/write-str body)}))

(defn- with-nonce
  "Move :dpop-nonce out of the OAuth response map into a DPoP-Nonce header."
  [result]
  (let [headers (when-let [nonce (:dpop-nonce result)] {"dpop-nonce" nonce})]
    [(dissoc result :dpop-nonce) headers]))

(defn- oauth-result-response
  "Map a provider PAR/token result to a Ring response."
  [result success-status]
  (let [[clean headers] (with-nonce result)]
    (if (:error clean)
      (json-response (or (:status clean) 400)
                     (cond-> {:error (:error clean)}
                       (:message clean) (assoc :error_description (:message clean)))
                     headers)
      (json-response success-status clean headers))))

;; -----------------------------------------------------------------------------
;; Endpoint handlers
;; -----------------------------------------------------------------------------

(defn- handle-par
  [provider ring-request]
  (let [result @(provider/pushed-authorization-request
                 provider
                 {:params (parse-body ring-request)
                  :dpop-proof (dpop-header ring-request)
                  :method :post
                  :url (request-url ring-request)})
        [clean headers] (with-nonce result)]
    (if (:error clean)
      (json-response (or (:status clean) 400)
                     (cond-> {:error (:error clean)}
                       (:message clean) (assoc :error_description (:message clean)))
                     headers)
      ;; PAR success uses the RFC 9126 wire field names
      (json-response 201
                     {:request_uri (:request-uri clean)
                      :expires_in (:expires-in clean)}
                     headers))))

(defn- handle-token
  [provider ring-request]
  (let [result @(provider/token
                 provider
                 {:params (parse-body ring-request)
                  :dpop-proof (dpop-header ring-request)
                  :method :post
                  :url (request-url ring-request)})
        ;; token responses use snake_case wire fields; :sub stays as-is
        [clean headers] (with-nonce result)]
    (if (:error clean)
      (json-response (or (:status clean) 400)
                     (cond-> {:error (:error clean)}
                       (:message clean) (assoc :error_description (:message clean)))
                     headers)
      (json-response 200 (dissoc clean :dpop-nonce) headers))))

(defn- handle-revoke
  [provider ring-request]
  @(provider/revoke provider {:params (parse-body ring-request)})
  (json-response 200 {}))

(defn- redirect-response
  [{:keys [uri params]}]
  {:status 302
   :headers {"location" (http/serialize-url
                         (-> (http/parse-url uri)
                             (update :query-params merge params)))}})

(defn- hook-result->response
  "Translate an authorize / sign-in / accept / reject result. Redirects
  become 302s; :prompt results are handed to the matching hook; errors
  become OAuth JSON."
  [hooks result]
  (cond
    (:redirect result)
    (redirect-response (:redirect result))

    (:error result)
    (json-response (or (:status result) 400)
                   (cond-> {:error (:error result)}
                     (:message result) (assoc :error_description (:message result))))

    (= :login (:prompt result))
    (if-let [hook (:render-login hooks)]
      (hook (:request result))
      (json-response 200 {:prompt "login" :request (:request result)}))

    (= :consent (:prompt result))
    (if-let [hook (:render-consent hooks)]
      (hook (:request result))
      (json-response 200 {:prompt "consent" :request (:request result)}))

    :else
    (json-response 200 result)))

(defn- device-id
  "The device-session id supplied by the host app (cookie/header)."
  [hooks ring-request]
  (if-let [f (:device-id hooks)]
    (f ring-request)
    (get-in ring-request [:headers "x-device-id"])))

(defn- handle-authorize
  [provider hooks ring-request]
  (let [params (:query-params ring-request)]
    (hook-result->response
     hooks
     @(provider/authorize provider
                          {:client-id (:client_id params)
                           :request-uri (:request_uri params)
                           :device-id (device-id hooks ring-request)}))))

(defn- handle-sign-in
  [provider hooks ring-request]
  (let [params (parse-body ring-request)]
    (hook-result->response
     hooks
     @(provider/complete-sign-in
       provider
       {:device-id (or (device-id hooks ring-request) (:device_id params))
        :request-uri (:request_uri params)
        :sub (:sub params)
        :credentials (when (:identifier params)
                       {:identifier (:identifier params)
                        :password (:password params)})
        :remember? (boolean (:remember params))}))))

(defn- handle-accept
  [provider hooks ring-request]
  (let [params (parse-body ring-request)]
    (hook-result->response
     hooks
     @(provider/accept provider
                       {:device-id (or (device-id hooks ring-request) (:device_id params))
                        :request-uri (:request_uri params)
                        :sub (:sub params)}))))

(defn- handle-reject
  [provider hooks ring-request]
  (hook-result->response
   hooks
   @(provider/reject provider {:request-uri (:request_uri (parse-body ring-request))})))

;; -----------------------------------------------------------------------------
;; Router
;; -----------------------------------------------------------------------------

(defn routes
  "A Ring handler for the provider endpoints.

  opts:
    :hooks     host-app callbacks:
               :render-login   (fn [request] ring-response) for GET
                               /oauth/authorize needing sign-in
               :render-consent (fn [request] ring-response) for consent
               :device-id      (fn [ring-request] device-id-string)
                               (defaults to the X-Device-Id header)
    :resource  when set, serves GET /.well-known/oauth-protected-resource
               advertising this issuer as the resource's auth server

  Returns nil for unmatched paths so it can be composed with other
  handlers."
  [provider & {:keys [hooks resource]}]
  (fn [ring-request]
    (let [ring-request (update ring-request :headers keywordize-keys)
          ;; normalize header keys back to lower-case strings for lookups
          ring-request (update ring-request :headers
                               (fn [h] (into {} (map (fn [[k v]] [(str/lower-case (name k)) v])) h)))
          ring-request (cond-> ring-request
                         (and (not (:query-params ring-request))
                              (not (str/blank? (:query-string ring-request))))
                         (assoc :query-params (http/query-string->query-params
                                               (:query-string ring-request))))
          {:keys [request-method uri]} ring-request
          method (or request-method (:method ring-request))]
      (case [method uri]
        [:get "/.well-known/oauth-authorization-server"]
        (json-response 200 (provider/metadata provider))

        [:get "/oauth/jwks"]
        (json-response 200 (provider/jwks provider))

        [:post "/oauth/par"]
        (handle-par provider ring-request)

        [:post "/oauth/token"]
        (handle-token provider ring-request)

        [:post "/oauth/revoke"]
        (handle-revoke provider ring-request)

        [:get "/oauth/authorize"]
        (handle-authorize provider hooks ring-request)

        [:post "/oauth/authorize/sign-in"]
        (handle-sign-in provider hooks ring-request)

        [:post "/oauth/authorize/accept"]
        (handle-accept provider hooks ring-request)

        [:post "/oauth/authorize/reject"]
        (handle-reject provider hooks ring-request)

        (when (and resource
                   (= [:get "/.well-known/oauth-protected-resource"] [method uri]))
          (json-response 200 {:resource resource
                              :authorization_servers [(:issuer provider)]}))))))
