(ns statusphere.routes
  "Pedestal routes, interceptors, and handlers.

  Handlers are functions of request -> channel-of-response, written as go
  blocks; async-handler adapts one into an interceptor whose :enter returns
  a channel delivering the updated context (Pedestal's async contract).
  After the first async interceptor the chain runs on the core.async
  dispatch pool, so nothing on a request path may block: SDK calls are
  awaited via :channel (a/promise-chan), Datomic writes via
  (a/<! (a/io-thread @(d/transact ...)))."
  (:require [clojure.core.async :as a]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [datomic.api :as d]
            [io.pedestal.connector :as pconn]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.csrf :as csrf]
            [io.pedestal.http.ring-middlewares :as ring-middlewares]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.secure-headers :as secure-headers]
            [io.pedestal.service.interceptors :as interceptors]
            [io.pedestal.service.resources :as resources]
            [ring.middleware.session.cookie :as cookie]
            [atproto.client :as at]
            [atproto.lexicon :as lexicon]
            [atproto.oauth.client :as oauth-client]
            [atproto.runtime.cast :as cast]
            [atproto.runtime.json :as json]
            [atproto.tid :as tid]
            [statusphere.db :as db]
            [statusphere.handles :as handles]
            [statusphere.views :as views])
  (:import [java.time Instant]
           [java.util Base64 Date]))

(set! *warn-on-reflection* true)

;; -----------------------------------------------------------------------------
;; Response helpers
;; -----------------------------------------------------------------------------

(defn- html [body]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body body})

(defn- redirect [location]
  {:status 303
   :headers {"Location" location}})

;; -----------------------------------------------------------------------------
;; Interceptors
;; -----------------------------------------------------------------------------

(defn- async-handler
  "Adapt a (request -> channel-of-response) fn into a Pedestal interceptor."
  [name f]
  {:name  name
   :enter (fn [context]
            (a/go (assoc context :response (a/<! (f (:request context))))))})

(defn- inject-app
  "assoc the app context (Datomic conn, oauth client, handle resolver,
  config) into the request as :app — handlers and tests both see it."
  [app]
  {:name  ::inject-app
   :enter (fn [context] (update context :request assoc :app app))})

(def restore-viewer
  "When the browser session carries a :did, restore the OAuth session
  (refreshing stale tokens transparently) and attach
  :viewer {:did .. :client <atproto client>} to the request.

  On failure — revoked grant, wiped database — the stale cookie is
  dropped and the request short-circuits to a logged-out redirect."
  {:name  ::restore-viewer
   :enter (fn [{:keys [request] :as context}]
            (if-let [did (get-in request [:session :did])]
              (a/go
                (let [{:keys [error] :as oauth-session}
                      (a/<! (oauth-client/restore (:oauth-client (:app request)) did
                                                  :channel (a/promise-chan)))]
                  (if error
                    (do (cast/event {:message "Dropping stale browser session"
                                     :did did :error error})
                        (assoc context :response
                               (-> (redirect "/")
                                   (assoc :session (dissoc (:session request) :did)))))
                    (let [client (a/<! (at/init {:session oauth-session}
                                                :channel (a/promise-chan)))]
                      (update context :request assoc
                              :viewer {:did    did
                                       :handle (:handle oauth-session)
                                       :client client})))))
              context))})

;; -----------------------------------------------------------------------------
;; Handlers
;; -----------------------------------------------------------------------------

(defn- csrf-token [request]
  (get request csrf/anti-forgery-token))

(defn- valid-record?
  "Validate against the registered Lexicon schema (not just data-shape):
  *schema-validate* defaults to false, so bind it around the check."
  [record]
  (binding [lexicon/*schema-validate* true]
    (s/valid? ::lexicon/record record)))

(defn- <display-name
  "Channel of the viewer's bsky profile displayName (best-effort: nil on
  any error, or when the record doesn't validate against the bundled
  app.bsky.actor.profile lexicon)."
  [{:keys [client did]}]
  (a/go
    (let [{:keys [error value]}
          (a/<! (at/query client
                          {:nsid   "com.atproto.repo.getRecord"
                           :params {:repo       did
                                    :collection "app.bsky.actor.profile"
                                    :rkey       "self"}}
                          :channel (a/promise-chan)))]
      (when (and (not error) (valid-record? value))
        (:displayName value)))))

(defn home
  [{:keys [app query-params viewer] :as request}]
  (a/go
    (let [dbv        (d/db (:conn app))
          statuses   (db/recent-statuses dbv 50)
          handle-for (a/<! (handles/<resolve-all (:handles app)
                                                 (map :status/author-did statuses)))
          viewer     (when viewer
                       (assoc viewer :display-name (a/<! (<display-name viewer))))]
      (html (views/home {:error      (:error query-params)
                         :viewer     viewer
                         :my-status  (when viewer
                                       (:status/emoji (db/current-status dbv (:did viewer))))
                         :statuses   statuses
                         :handle-for handle-for
                         :csrf-token (csrf-token request)})))))

(defn send-status
  "Write the status record to the viewer's PDS (the source of truth), then
  upsert it into the local index optimistically — Jetstream will echo it
  back as a no-op."
  [{:keys [app viewer form-params] :as request}]
  (a/go
    (if-not viewer
      (redirect "/login")
      (let [now    (Instant/now)
            emoji  (:status form-params)
            record {:$type     "xyz.statusphere.status"
                    :status    emoji
                    :createdAt (str now)}]
        (if-not (valid-record? record)
          (redirect "/?error=invalid-status")
          (let [{:keys [error uri] :as resp}
                (a/<! (at/procedure (:client viewer)
                                    {:nsid "com.atproto.repo.putRecord"
                                     :body {:repo       (:did viewer)
                                            :collection "xyz.statusphere.status"
                                            :rkey       (tid/next-tid)
                                            :record     record
                                            ;; the PDS doesn't know our lexicon
                                            :validate   false}}
                                    :channel (a/promise-chan)))]
            (if error
              (do (cast/event {:message "putRecord failed" :error error
                               :description (:message resp)})
                  (redirect "/?error=pds"))
              (do (a/<! (a/io-thread
                         @(d/transact (:conn app)
                                      (db/upsert-status-tx
                                       {:uri        uri
                                        :author-did (:did viewer)
                                        :emoji      emoji
                                        :created-at (Date/from now)
                                        :indexed-at (Date/from now)}))))
                  (redirect "/")))))))))

(defn login-page
  [{:keys [query-params] :as request}]
  (a/go
    (html (views/login {:error      (:error query-params)
                        :csrf-token (csrf-token request)}))))

(defn login-submit
  "Resolve the submitted handle and send the browser to its authorization
  server (the SDK runs the PAR request first)."
  [{:keys [app form-params] :as request}]
  (a/go
    (let [handle (some-> (:handle form-params) str/trim)
          {:keys [error authorization-url] :as resp}
          (a/<! (oauth-client/authorize (:oauth-client app) handle
                                        :channel (a/promise-chan)))]
      (if error
        (do (cast/event {:message "OAuth authorize failed" :handle handle :error error
                         :description (:message resp)})
            (redirect "/login?error=oauth"))
        (redirect authorization-url)))))

(defn oauth-callback
  "Exchange the authorization code for tokens; remember only the DID in the
  browser session. Merge into the existing session (it carries the CSRF
  token) and rotate the token on this privilege change."
  [{:keys [app query-params session] :as request}]
  (a/go
    (let [{:keys [error] :as resp}
          (a/<! (oauth-client/callback (:oauth-client app) query-params
                                       :channel (a/promise-chan)))]
      (if error
        (do (cast/event {:message "OAuth callback failed" :error error
                         :description (:message resp)})
            (redirect "/login?error=oauth"))
        (-> (redirect "/")
            (assoc :session (assoc session :did (get-in resp [:session :did])))
            (csrf/rotate-token))))))

(defn logout
  [{:keys [app session] :as request}]
  (a/go
    (when-let [did (:did session)]
      (a/<! (oauth-client/revoke (:oauth-client app) did :channel (a/promise-chan))))
    (-> (redirect "/")
        (assoc :session (dissoc session :did)))))

(defn client-metadata
  "The OAuth client metadata document (production client discovery)."
  [{:keys [app]}]
  {:status  200
   :headers {"Content-Type" "application/json"}
   :body    (json/write-str (:client-metadata (:oauth-client app)))})

;; -----------------------------------------------------------------------------
;; Routes & connector
;; -----------------------------------------------------------------------------

(defn routes [app]
  (let [common [(inject-app app)]
        viewer (conj common restore-viewer)]
    #{["/"                     :get  (conj viewer (async-handler ::home home))]
      ["/status"               :post (conj viewer (async-handler ::send-status send-status))]
      ["/login"                :get  (conj common (async-handler ::login-page login-page))]
      ["/login"                :post (conj common (async-handler ::login-submit login-submit))]
      ["/oauth/callback"       :get  (conj common (async-handler ::oauth-callback oauth-callback))]
      ["/logout"               :post (conj common (async-handler ::logout logout))]
      ["/client-metadata.json" :get  (conj common client-metadata) :route-name ::client-metadata]}))

(defn connector-map
  "The Pedestal connector map: interceptor stack + routes. `app` is the
  request context map injected by inject-app.

  Interceptor order matters: session before anti-forgery (the token lives
  in the session), body-params before anti-forgery (the token arrives as a
  form field)."
  [{:keys [port cookie-secret public-url]} app]
  (let [host        (if public-url "0.0.0.0" "localhost")
        session-key (.decode (Base64/getDecoder) ^String cookie-secret)]
    (-> (pconn/default-connector-map host port)
        (pconn/with-interceptors
          [interceptors/log-request
           interceptors/not-found
           (ring-middlewares/content-type) ;; :leave — MIME type from file extension
           (ring-middlewares/session {:cookie-name  "sid"
                                      :store        (cookie/cookie-store {:key session-key})
                                      :cookie-attrs {:http-only true
                                                     ;; Lax is load-bearing: the OAuth
                                                     ;; callback is a top-level GET and
                                                     ;; must carry the session cookie.
                                                     :same-site :lax}})
           route/query-params
           (body-params/body-params)
           (csrf/anti-forgery)
           (secure-headers/secure-headers)])
        (pconn/with-routes
          (routes app)
          (resources/resource-routes {:resource-root "public"})))))
