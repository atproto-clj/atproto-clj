(ns atproto.runtime.cast
  #?(:clj (:require [clojure.tools.logging :as log])
     :cljs (:require-macros [atproto.runtime.cast :refer [dev-enabled?]])))

(def type->level
  {:alert  :error
   :event  :info
   :dev    :debug})

(defn log
  "runtime-specific logging"
  [{:keys [::type ex] :as evt}]
  #?(:clj (let [level (get type->level type :info)]
            (if ex
              (log/log level ex evt)
              (log/log level evt)))
     :cljs (.log js/console (clj->js evt))))

#?(:clj
   (defmacro dev-enabled?
     "Whether we should handle dev messages."
     []
     (or (= (System/getProperty "atproto.runtime.cast.dev-enabled") "true")
         (= (System/getenv "ATPROTO_RUNTIME_CAST_DEV_ENABLED") "true"))))

(def ^:private handlers
  (atom {:alert #{}
         :event #{}
         :metric #{}
         :dev #{}}))

(defn- dispatch
  "Dispatch the message to the appropriate handler.

  Remove any handler that throws."
  [{:keys [::type] :as evt}]
  (let [failed-handlers (reduce (fn [failed handle]
                                  (try
                                    (handle (dissoc evt ::type))
                                    (catch #?(:clj Throwable
                                              :cljs js/Error) e
                                      (log {:message "Cast handler failed"
                                            :ex e})
                                      (conj failed handle))))
                                []
                                (get @handlers type))]
    (apply swap! handlers update type disj failed-handlers)))

(defonce ^:private tap
  (add-tap dispatch))

(defn- enqueue*
  [type evt]
  (tap> (assoc evt ::type type)))

;; API

(defn register
  [type handler]
  (swap! handlers update type conj handler))

(defn alert
  [evt]
  (enqueue* :alert evt))

(defn event
  [evt]
  (enqueue* :event evt))

(defn metric
  [evt]
  (enqueue* :metric evt))

(defn dev
  [evt]
  (when (dev-enabled?)
    (enqueue* :dev evt)))

(defn handle-uncaught-exceptions
  "Set the uncaught exception handler of the current thread to alert."
  []
  #?(:clj (Thread/setDefaultUncaughtExceptionHandler
           (reify Thread$UncaughtExceptionHandler
             (uncaughtException [_ t e]
               (alert {:message "Uncaught exception"
                       :ex e}))))))
