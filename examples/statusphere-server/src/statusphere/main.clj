(ns statusphere.main
  "Production entry point: read and validate config, start the system, block."
  (:gen-class)
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [com.stuartsierra.component :as component]
            [atproto.runtime.cast :as cast]
            [statusphere.system :as system])
  (:import [java.util Base64]))

(set! *warn-on-reflection* true)

(defn- cookie-secret?
  "Ring's cookie store needs exactly 16 key bytes."
  [s]
  (and (string? s)
       (= 16 (try (count (.decode (Base64/getDecoder) ^String s))
                  (catch Exception _ 0)))))

(s/def ::port pos-int?)
(s/def ::db-uri (s/and string? #(.startsWith ^String % "datomic:")))
(s/def ::cookie-secret cookie-secret?)
(s/def ::public-url string?)
(s/def ::jetstream-host string?)
(s/def ::config
  (s/keys :req-un [::port ::db-uri ::cookie-secret]
          :opt-un [::public-url ::jetstream-host]))

(defn load-config
  "Read and validate the EDN config file; throw with an actionable message."
  [path]
  (let [file (io/file path)]
    (when-not (.exists file)
      (throw (ex-info (str "Config file not found: " path
                           "\nCopy config-sample.edn to config.edn and fill it in.")
                      {:path path})))
    (let [config (edn/read-string (slurp file))]
      (when-not (s/valid? ::config config)
        (throw (ex-info (str "Invalid config at " path ":\n"
                             (s/explain-str ::config config)
                             (when-not (cookie-secret? (:cookie-secret config))
                               "\n(:cookie-secret must be 16 bytes, base64: openssl rand -base64 16)"))
                        {:path path})))
      config)))

(defn register-cast-handlers!
  "Log the SDK's (and this app's) observability events."
  []
  (cast/handle-uncaught-exceptions)
  (cast/register :alert cast/log)
  (cast/register :event cast/log))

(defn -main [& [config-path]]
  (register-cast-handlers!)
  (let [config (load-config (or config-path "config.edn"))
        system (component/start-system (system/new-system config))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(component/stop-system system)))
    (cast/event {:message (str "Statusphere running on port " (:port config))})
    @(promise)))
