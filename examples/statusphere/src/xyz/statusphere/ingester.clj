(ns xyz.statusphere.ingester
  (:require [clojure.spec.alpha :as s]
            [clojure.core.async :as a]
            [atproto.runtime.cast :as cast]
            [atproto.lexicon :as lexicon]
            [atproto.jetstream :as jet]
            [xyz.statusphere.db :as db]))

(defn start
  [config]
  (cast/event {:message "Starting the ingester..."})
  (let [status-collection "xyz.statusphere.status"
        events-ch (a/chan)
        control-ch (jet/consume events-ch
                                :wanted-collections [status-collection])]
    (a/go-loop []
      (when-let [{:keys [did commit]} (a/<! events-ch)]
        (when-let [{:keys [operation collection rkey record]} commit]
          (when (and (= status-collection collection)
                     (= "create" operation))
            (if (not (s/valid? ::lexicon/record record))
              (cast/event {:message "Invalid status."
                           :record record
                           :explain-data (s/explain-data ::lexicon/record record)})
              (let [status #:status{:uri (str "at://" did "/" status-collection "/" rkey)
                                    :author_did did
                                    :status (:status record)
                                    :created_at (:createdAt record)
                                    :indexed_at (str (java.time.Instant/now))}]
                (db/upsert-status! status)
                (cast/event {:message "Ingester: status received"
                             :status status}))))))
      (recur))
    (cast/event {:message "Ingester started."})
    {:control-ch control-ch}))

(defn stop
  [{:keys [control-ch]}]
  (cast/event {:message "Stopping the ingester..."})
  (a/close! control-ch)
  (cast/event {:message "Ingester stopped."}))
