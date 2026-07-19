(ns user
  "Reloaded workflow: (reset) stops the system, reloads changed namespaces,
  and starts a fresh system. Also: (start), (stop), and `system`."
  (:require [com.stuartsierra.component.repl :refer [reset set-init start stop system]]
            [statusphere.main :as main]
            [statusphere.system :as sys]))

(main/register-cast-handlers!)

(set-init
 (fn [_]
   (sys/new-system (main/load-config "config.edn"))))

(comment
  (start)
  (reset)
  (stop)
  system)
