(ns statusphere.auth
  "OAuth client construction and its Datomic-backed stores.

  The Store protocol is synchronous by contract (see
  atproto.oauth.client.store) — this is one of the app's two deliberate
  sync islands. Its callers are SDK internals on their own threads, never
  the go-dispatch pool, and the operations are cheap peer-local reads and
  small transacts."
  (:require [datomic.api :as d]
            [atproto.oauth.client :as oauth-client]
            [atproto.oauth.client.store :as store]
            [atproto.runtime.json :as json])
  (:import [java.net URLEncoder]
           [java.nio.charset StandardCharsets]))

(set! *warn-on-reflection* true)

;; -----------------------------------------------------------------------------
;; Stores
;; -----------------------------------------------------------------------------

(defn- entry-expired?
  "True when this JSON value declares an :expires-at (epoch seconds) in the
  past. Garbage values are treated as expired."
  [value now-secs]
  (try
    (let [expires-at (:expires-at (json/read-str value))]
      (and (int? expires-at) (<= expires-at now-secs)))
    (catch Exception _ true)))

(defn- sweep-expired!
  "Delete entries whose JSON value has expired — keeps abandoned
  authorization flows from accumulating (see the Store docstring)."
  [conn key-attr value-attr]
  (let [now     (quot (System/currentTimeMillis) 1000)
        expired (->> (d/q '[:find ?e ?v
                            :in $ ?ka ?va
                            :where [?e ?ka] [?e ?va ?v]]
                          (d/db conn) key-attr value-attr)
                     (keep (fn [[e v]] (when (entry-expired? v now) e))))]
    (when (seq expired)
      @(d/transact conn (mapv (fn [e] [:db/retractEntity e]) expired)))))

(defn- datomic-store
  "A Store persisting JSON string values under key-attr/value-attr.
  Expired entries read as absent (matching the SDK's memory-store);
  with :sweep? true, writes also sweep them out of the database."
  [conn key-attr value-attr & {:keys [sweep?]}]
  (reify store/Store
    (get* [_ k]
      (let [db (d/db conn)]
        (when-let [e (d/entid db [key-attr k])]
          (when-let [v (get (d/pull db [value-attr] e) value-attr)]
            (when-not (entry-expired? v (quot (System/currentTimeMillis) 1000))
              v)))))
    (set* [_ k v]
      (when sweep? (sweep-expired! conn key-attr value-attr))
      @(d/transact conn [{key-attr k, value-attr v}]))
    (del* [_ k]
      (when (d/entid (d/db conn) [key-attr k])
        @(d/transact conn [[:db/retractEntity [key-attr k]]])))))

(defn state-store [conn]
  (datomic-store conn :auth-state/key :auth-state/value :sweep? true))

(defn session-store [conn]
  (datomic-store conn :auth-session/key :auth-session/value))

;; -----------------------------------------------------------------------------
;; Client
;; -----------------------------------------------------------------------------

(def scope "atproto transition:generic")

(defn base-url
  "The app's own URL: the public one, or the OAuth loopback address.
  The spec requires 127.0.0.1 (not localhost) in loopback redirect URIs."
  [{:keys [public-url port]}]
  (or public-url (str "http://127.0.0.1:" port)))

(defn client-metadata
  [config]
  (let [url          (base-url config)
        redirect-uri (str url "/oauth/callback")
        client-id    (if (:public-url config)
                       ;; Production: metadata served at /client-metadata.json.
                       (str (:public-url config) "/client-metadata.json")
                       ;; Development: the spec's loopback exception — the
                       ;; authorization server synthesizes a virtual metadata
                       ;; document from this client_id.
                       (format "http://localhost?redirect_uri=%s&scope=%s"
                               (URLEncoder/encode redirect-uri StandardCharsets/UTF_8)
                               (URLEncoder/encode ^String scope StandardCharsets/UTF_8)))]
    {:client_name                "Statusphere (Clojure, server-side)"
     :client_id                  client-id
     :client_uri                 url
     :redirect_uris              [redirect-uri]
     :scope                      scope
     :grant_types                ["authorization_code" "refresh_token"]
     :response_types             ["code"]
     :application_type           "web"
     :token_endpoint_auth_method "none"
     :dpop_bound_access_tokens   true}))

(defn client
  "The SDK OAuth client, with state and sessions persisted in Datomic."
  [config conn]
  (oauth-client/create
   {:client-metadata (client-metadata config)
    :state-store     (state-store conn)
    :session-store   (session-store conn)}))
