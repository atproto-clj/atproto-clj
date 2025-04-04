(ns xyz.statusphere
  "AT Protocol \"Statusphere\" Example App in Clojure."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [cljs.build.api :refer [build]]
            [cljs.repl :as repl]
            [cljs.repl.browser :as browser]
            [atproto.runtime.cast :as cast]
            [xyz.statusphere.db :as db]
            [xyz.statusphere.ingester :as ingester]
            [xyz.statusphere.server :as server]
            [xyz.statusphere.api]))

(defmacro config-map
  []
  (let [m (-> (io/file "./config.edn")
              (io/reader)
              (java.io.PushbackReader.)
              (edn/read))]
    (merge m
           {:url (or (:public-url m)
                     (str "http://127.0.0.1:" (:port m)))})))

(def config (config-map))

(defn build-client
  "Compile the Clojurescript client."
  []
  (cast/event {:message "Building the client..."})
  (build "src"
         (merge {:main 'xyz.statusphere
                 :output-to "resources/public/js/main.js"
                 :output-dir "resources/public/js"
                 :asset-path "/js"}
                (if (= :prod (:env config))
                  {:optimizations :advanced}
                  {:optimizations :none})))
  (cast/event {:message "Client built."}))

(defn register-cast-handlers
  []
  (cast/handle-uncaught-exceptions)
  (cast/register :alert cast/log)
  (cast/register :event cast/log)
  (cast/register :dev   cast/log))

(declare stop)

(defn start
  []
  (register-cast-handlers)
  (build-client)
  (db/init)
  (let [service {:ingester (ingester/start config)
                 :server (server/start config)}]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread.
                       #(stop service)))
    service))

(defn stop
  [{:keys [server ingester]}]
  (ingester/stop ingester)
  (server/stop server))

(defn -main [& args]
  (start)
  @(promise))

;; development

(defonce service (atom nil))

(defn start-dev []
  (reset! service (start)))

(defn stop-dev []
  (when @service
    (stop @service)
    (reset! service nil)))

(defn restart-dev []
  (stop-dev)
  (start-dev))

(comment

  (require 'xyz.statusphere :reload-all)

  (xyz.statusphere/restart-dev)

  (xyz.statusphere/stop-dev)

  (clojure.java.browse/browse-url (:url xyz.statusphere/config))

  )
