(ns statusphere.system
  "The component system: every Lifecycle implementation lives here, so what
  starts and stops — and in what order — reads in one place. Components hold
  resources (a connection, a channel, a server); logic lives in plain
  functions in the other namespaces."
  (:require [com.stuartsierra.component :as component]
            [datomic.api :as d]
            [io.pedestal.connector :as pconn]
            [io.pedestal.http.jetty :as jetty]
            [atproto.lexicon :as lexicon]
            [atproto.runtime.cast :as cast]
            [statusphere.auth :as auth]
            [statusphere.db :as db]
            [statusphere.handles :as handles]
            [statusphere.routes :as routes]))

(set! *warn-on-reflection* true)

;; -----------------------------------------------------------------------------
;; Datomic
;; -----------------------------------------------------------------------------

(defrecord Datomic [uri conn]
  component/Lifecycle
  (start [this]
    (if conn
      this
      (do (cast/event {:message (str "Connecting to " uri)})
          (assoc this :conn (db/connect uri)))))
  (stop [this]
    (when conn (d/release conn))
    (assoc this :conn nil)))

;; -----------------------------------------------------------------------------
;; OAuth client
;; -----------------------------------------------------------------------------

(defrecord OauthClient [config datomic client]
  component/Lifecycle
  (start [this]
    (if client this (assoc this :client (auth/client config (:conn datomic)))))
  (stop [this]
    (assoc this :client nil)))

;; -----------------------------------------------------------------------------
;; Handle resolver
;; -----------------------------------------------------------------------------

(defrecord HandleResolver [resolver]
  component/Lifecycle
  (start [this]
    (if resolver this (assoc this :resolver (handles/resolver))))
  (stop [this]
    (assoc this :resolver nil)))

;; -----------------------------------------------------------------------------
;; Web server
;; -----------------------------------------------------------------------------

(defrecord WebServer [config datomic oauth-client handle-resolver connector]
  component/Lifecycle
  (start [this]
    (if connector
      this
      (let [app       {:conn         (:conn datomic)
                       :oauth-client (:client oauth-client)
                       :handles      (:resolver handle-resolver)
                       :config       config}
            connector (-> (routes/connector-map config app)
                          (jetty/create-connector nil)
                          (pconn/start!))]
        (cast/event {:message (str "Web server listening on port " (:port config))})
        (assoc this :connector connector))))
  (stop [this]
    (when connector (pconn/stop! connector))
    (assoc this :connector nil)))

;; -----------------------------------------------------------------------------
;; System
;; -----------------------------------------------------------------------------

(defn register-lexicons!
  "Register this app's Lexicon schemas (the status record and the bsky
  profile it validates). Global and idempotent — safe on every dev reset.

  The resource path is \"statusphere-lexicons\" rather than \"lexicons\"
  because the SDK bundles its own lexicons/ resource tree on the classpath,
  which would shadow this app's schemas."
  []
  (lexicon/register-specs! (lexicon/load-resources! "statusphere-lexicons")))

(defn new-system
  "Build the (unstarted) system from a validated config map."
  [config]
  (register-lexicons!)
  (component/system-map
   :datomic         (map->Datomic {:uri (:db-uri config)})
   :oauth-client    (component/using (map->OauthClient {:config config})
                                     [:datomic])
   :handle-resolver (map->HandleResolver {})
   :http            (component/using (map->WebServer {:config config})
                                     [:datomic :oauth-client :handle-resolver])))
