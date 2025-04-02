(ns xyz.statusphere
  "AT Protocol \"Statusphere\" Example App in Clojure."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [cljs.build.api :refer [build]]
            [cljs.repl :as repl]
            [cljs.repl.browser :as browser]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.cookie :refer [cookie-store]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.util.response :as r]
            [compojure.core :refer [GET POST routes]]
            [compojure.route :as route]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [atproto.client :as client]
            [atproto.tid :as tid]
            [atproto.identity]
            [atproto.oauth.client :as oauth-client]
            [atproto.xrpc.server :as xrpc-server]
            [atproto.xrpc.server.ring :as ring-adapter]
            [atproto.lexicon :as lexicon]
            [atproto.runtime.cast :as cast]
            [atproto.runtime.json :as json]
            [xyz.statusphere.auth :as auth]
            [xyz.statusphere.db :as db])
  (:import [java.time Instant]
           [java.util Base64]))

;; DID resolver

(defonce did-handle-store (atom {}))

(defn did->handle
  "Resolve the did into a handle or return the did itself."
  [did]
  (or (get @did-handle-store did)
      (let [{:keys [error handle] :as resp} @(atproto.identity/resolve-identity did)]
        (if error
          (cast/alert resp)
          (let [res (or handle did)]
            (swap! did-handle-store assoc did res)
            res)))))

(defn dids->handles
  "A map of the dids to their handles."
  [dids]
  (reduce (fn [m did]
            (conj m [did (did->handle did)]))
          {}
          dids))

;; Inject the clients in the request's :app-ctx

(defn wrap-oauth-client
  "Inject the oauth client in the request."
  [handler client]
  (fn [req]
    (handler (assoc-in req [:app-ctx :oauth-client] client))))

(defn wrap-atproto-client
  "If the user is authenticated, restore the oauth session and inject the atproto client in the request."
  [handler]
  (fn [{:keys [app-ctx session] :as request}]
    (let [{:keys [oauth-client]} app-ctx]
      (or (when-let [did (:did session)]
            (let [{:keys [error] :as oauth-session} @(oauth-client/restore oauth-client did)]
              (if error
                (cast/alert oauth-session)
                (handler (assoc-in request [:app-ctx :atproto-client] @(client/create {:session oauth-session}))))))
          (handler request)))))

;; XRPC API implementation

(defn profile->profile-view
  [did {:keys [avatar displayName createdAt]}]
  (cond-> {:$type "app.bsky.actor.defs#profileView"
           :did did
           :handle (did->handle did)
           :displayName displayName
           :createdAt createdAt}
    avatar (assoc :avatar (format "https://atproto.pictures/img/%s/%s" did (:ref avatar)))))

(defn status->status-view
  [{:keys [status/uri status/status status/author_did status/created_at]}]
  {:uri uri
   :status status
   :createdAt created_at
   :profile {:did author_did
             :handle (did->handle author_did)}})

(defmethod xrpc-server/handle "xyz.statusphere.getStatuses"
  [{:keys [app-ctx params] :as request}]
  (let [{:keys [atproto-client]} app-ctx
        statuses (sql/query db/db ["select * from status order by indexed_at desc limit ?" (:limit params)])]
    {:encoding "application/json"
     :body {:statuses (map status->status-view statuses)}}))

(defmethod xrpc-server/handle "xyz.statusphere.getUser"
  [{:keys [app-ctx] :as request}]
  (let [{:keys [atproto-client]} app-ctx]
    (if (not atproto-client)
      (xrpc-server/auth-required)
      (let [did (client/did atproto-client)
            {:keys [error] :as resp} @(client/query atproto-client
                                                    {:nsid "com.atproto.repo.getRecord"
                                                     :params {:repo did
                                                              :collection "app.bsky.actor.profile"
                                                              :rkey "self"}})]
        (if error
          resp
          (let [profile (:value resp)]
            (if (not (s/valid? ::lexicon/record profile))
              {:error "InvalidLexiconProfile"
               :profile profile
               :explain-data (s/explain-data ::lexicon/record profile)}
              (let [status (first
                            (sql/query db/db
                                       ["select * from status where author_did = ? order by indexed_at desc limit 1"
                                        did]))]
                {:encoding "application/json"
                 :body (cond-> {:profile (profile->profile-view did profile)}
                         status (assoc :status (status->status-view status)))}))))))))

(defmethod xrpc-server/handle "xyz.statusphere.sendStatus"
  [{:keys [body app-ctx] :as request}]
  (let [{:keys [atproto-client]} app-ctx]
    (if (not atproto-client)
      (xrpc-server/auth-required)
      (let [did (client/did atproto-client)
            status {:$type "xyz.statusphere.status"
                    :status (:status body)
                    :createdAt (str (Instant/now))}]
        (if (not (s/valid? ::lexicon/record status))
          {:error "InvalidStatus"
           :message (s/explain-str ::lexicon/record status)}
          (let [{:keys [error] :as resp} @(client/procedure atproto-client
                                                            {:nsid "com.atproto.repo.putRecord"
                                                             :body {:repo did
                                                                    :collection "xyz.statusphere.status"
                                                                    :rkey (tid/next-tid)
                                                                    :record status
                                                                    :validate false}})]
            (if error
              resp
              (let [optimistic-status {:status/uri (:uri resp)
                                       :status/author_did did
                                       :status/status (:status status)
                                       :status/created_at (:createdAt status)
                                       :status/indexed_at (:createdAt status)}]
                (sql/insert! db/db :status optimistic-status)
                {:encoding "application/json"
                 :body {:status (status->status-view optimistic-status)}}))))))))

;; -----------------------------------------------------------------------------
;; Routes
;; -----------------------------------------------------------------------------

(defn app
  [env xrpc-server]
  (routes

   (ring-adapter/handler xrpc-server)

   (GET "/client-metadata.json" req
     (r/content-type
      (r/response (json/write-str (:client-metadata (:oauth-client (:app-ctx req)))))
      "application/json"))

   (POST "/login" req
     (let [{:keys [error authorization-url] :as resp} @(oauth-client/authorize (:oauth-client (:app-ctx req))
                                                                               (get-in req [:params :handle]))]
       (when error
         (cast/alert resp))
       (r/redirect authorization-url)))

   (GET "/oauth/callback" req
     (let [{:keys [error session] :as resp} @(oauth-client/callback (:oauth-client (:app-ctx req))
                                                                    (:params req))]
       (when error
         (cast/alert resp))
       (assoc (r/redirect "/")
              :session session)))

   (POST "/logout" req
     ;; todo: destroy oauth session
     (assoc (r/redirect "/")
            :session nil))

   (GET "/favicon.ico" _
     {:status 404})

   (GET "*" _
     (r/resource-response "public/index.html"))))

(defn handler
  [env]
  (let [oauth-client (auth/client env db/db)
        lexicon (lexicon/load-resources! "lexicons")
        xrpc-server (xrpc-server/create {:lexicon lexicon
                                         :validate-response? true})]
    (-> (app env xrpc-server)
        (wrap-atproto-client)
        (wrap-oauth-client oauth-client)
        (wrap-resource "public")
        (wrap-session {:cookie-name "sid"
                       :store (cookie-store {:key (:cookie-secret env)})})
        (wrap-keyword-params)
        (wrap-params))))

(defn read-env
  [config-edn-path]
  (let [{:keys [port cookie-secret]} (-> (io/file config-edn-path)
                                         (io/reader)
                                         (java.io.PushbackReader.)
                                         (edn/read))]
    {:port port
     :cookie-secret (.decode (Base64/getDecoder) cookie-secret)}))

(defn register-cast-handlers
  [env]
  (cast/handle-uncaught-exceptions)
  (cast/register :alert cast/log)
  (cast/register :event cast/log)
  (cast/register :dev   cast/log))

(defn build-client
  [{:keys [prod?] :as env}]
  (build "src"
         (merge {:main 'xyz.statusphere
                 :output-to "resources/public/js/main.js"
                 :output-dir "resources/public/js"
                 :asset-path "/js"}
                (if prod?
                  {:optimizations :advanced}
                  {:optimizations :none}))))

(defn start
  [{:keys [prod? port] :as env}]
  (build-client env)
  (register-cast-handlers env)
  (db/up!)
  (run-jetty (handler env)
             {:port port
              :join? (if prod? true false)}))

(defn -main [& args]
  (start (assoc (read-env (first args))
                :prod? true)))

;; -----------------------------------------------------------------------------
;; Development
;; -----------------------------------------------------------------------------

(defonce server (atom nil))

(defn start-dev []
  (start (assoc (read-env "./config.edn")
                :prod? false)))

(defn stop-dev []
  (when @server
    (.stop @server)))

(defn restart-dev []
  (stop-dev)
  (start-dev))

(comment

  (require 'xyz.statusphere :reload-all)

  (xyz.statusphere/restart-dev)

  (xyz.statusphere/build-client)

  (clojure.java.browse/browse-url "http://127.0.0.1:8080")

  )
