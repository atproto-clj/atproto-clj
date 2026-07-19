(ns statusphere.ingester
  "Jetstream → local index.

  handle-event! applies one typed Jetstream event to the database; the
  :ingester component (see statusphere.system) wires it to a live consumer
  on a dedicated thread — a thread the app owns, so this namespace is plain
  blocking code and never touches the go-dispatch pool."
  (:require [clojure.spec.alpha :as s]
            [datomic.api :as d]
            [atproto.lexicon :as lexicon]
            [atproto.runtime.cast :as cast]
            [atproto.sync.cursor :as cursor]
            [statusphere.db :as db])
  (:import [java.time Instant]
           [java.util Date]))

(set! *warn-on-reflection* true)

(def collection "xyz.statusphere.status")

;; -----------------------------------------------------------------------------
;; Event handling
;; -----------------------------------------------------------------------------

(defn- parse-created-at
  "The record's createdAt is author-asserted, untrusted network data;
  fall back to now on anything unparseable."
  [s]
  (or (when (string? s)
        (try (Date/from (Instant/parse s))
             (catch Exception _ nil)))
      (Date.)))

(defn- valid-record? [record]
  (binding [lexicon/*schema-validate* true]
    (s/valid? ::lexicon/record record)))

(defn handle-event!
  "Apply one typed Jetstream event to the index. Safe to call with any
  event; everything that isn't a status commit is ignored."
  [conn {:keys [kind did rkey uri record] :as event}]
  (when (= collection (:collection event))
    (let [uri (or uri (str "at://" did "/" collection "/" rkey))]
      (case kind
        (:create :update)
        (if-not (valid-record? record)
          (cast/event {:message "Ingester: ignoring invalid status record"
                       :did did :uri uri})
          (do @(d/transact conn (db/upsert-status-tx
                                 {:uri        uri
                                  :author-did did
                                  :emoji      (:status record)
                                  :created-at (parse-created-at (:createdAt record))
                                  :indexed-at (Date.)}))
              (cast/event {:message "Ingester: indexed status"
                           :did did :uri uri :emoji (:status record)})))

        :delete
        (when-some [tx (db/retract-status-tx (d/db conn) uri)]
          @(d/transact conn tx)
          (cast/event {:message "Ingester: retracted status" :uri uri}))

        nil))))

;; -----------------------------------------------------------------------------
;; Cursor persistence
;; -----------------------------------------------------------------------------

(defn datomic-cursor-store
  "CursorStore over the :cursor/* attributes. Synchronous — always wrap it
  in throttled-cursor-store; the SDK writes the cursor for every event."
  [conn id]
  (reify cursor/CursorStore
    (get-cursor [_ cb]
      (let [db (d/db conn)]
        (cb {:cursor (when-let [e (d/entid db [:cursor/id id])]
                       (:cursor/time-us (d/pull db [:cursor/time-us] e)))})))
    (set-cursor [_ c cb]
      @(d/transact conn [{:cursor/id id :cursor/time-us c}])
      (cb {}))))

(defn throttled-cursor-store
  "Wrap a CursorStore so writes reach it at most once per interval-ms —
  Jetstream delivers network-wide identity/account events regardless of the
  collection filter, and an unthrottled store would turn each into a
  Datomic transaction. Returns {:store <CursorStore> :flush! <fn>}; call
  flush! on shutdown to persist the last pending cursor.

  now-fn is injectable for tests."
  [store interval-ms & {:keys [now-fn] :or {now-fn #(System/currentTimeMillis)}}]
  (let [state (atom {:last-write 0 :pending nil})
        write! (fn [c] (cursor/set-cursor store c (fn [_])))]
    {:store
     (reify cursor/CursorStore
       (get-cursor [_ cb] (cursor/get-cursor store cb))
       (set-cursor [_ c cb]
         (let [now (now-fn)
               [{:keys [last-write]} _]
               (swap-vals! state
                           (fn [{:keys [last-write] :as st}]
                             (if (>= (- now last-write) interval-ms)
                               {:last-write now :pending nil}
                               (assoc st :pending c))))]
           (when (>= (- now last-write) interval-ms)
             (write! c))
           (cb {}))))
     :flush!
     (fn []
       (let [[{:keys [pending]} _] (swap-vals! state assoc :pending nil)]
         (when pending (write! pending))))}))
