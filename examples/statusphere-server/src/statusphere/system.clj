(ns statusphere.system
  "The component system: every Lifecycle implementation lives here, so what
  starts and stops — and in what order — reads in one place. Components hold
  resources (a connection, a channel, a server); logic lives in plain
  functions in the other namespaces."
  (:require [com.stuartsierra.component :as component]
            [datomic.api :as d]
            [atproto.lexicon :as lexicon]
            [atproto.runtime.cast :as cast]
            [statusphere.db :as db]))

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
   :datomic (map->Datomic {:uri (:db-uri config)})))
