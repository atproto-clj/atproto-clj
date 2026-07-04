(ns atproto.sync.runner
  "Partitioned in-order event processing with consecutive-seq cursor commit.
  Port of @atproto/sync MemoryRunner + ConsecutiveList."
  (:require [atproto.runtime.cast :as cast]
            [atproto.sync.cursor :as cursor]))

#?(:clj (set! *warn-on-reflection* true))

;;; ConsecutiveList
;;;
;;; Pure persistent data structure. Values are pushed in order; each push
;;; returns an item id. Items may complete in any order; completing an item
;;; returns the values of the fully-completed prefix (removed from the list),
;;; or [] when the head of the list is still incomplete. Item ids are
;;; monotonically increasing and stay valid as earlier items are removed.

(defn consecutive-list
  "An empty ConsecutiveList."
  []
  {:next-id 0
   :items []})

(defn push
  "Append v to the list. Returns [clist' item-id]."
  [clist v]
  (let [id (:next-id clist)]
    [(-> clist
         (update :items conj {:id id :value v :done? false})
         (update :next-id inc))
     id]))

(defn complete
  "Mark the item with item-id done. Returns [clist' completed-values] where
  completed-values are the values of the fully-completed prefix (in push
  order, removed from the list), or [] if the head is still incomplete."
  [clist item-id]
  (let [items (mapv #(if (= item-id (:id %)) (assoc % :done? true) %)
                    (:items clist))
        done (into [] (take-while :done?) items)
        n (count done)]
    [(assoc clist :items (subvec items n))
     (mapv :value done)]))

;;; Runner internals
;;;
;;; The runner is an atom-based state machine. All state transitions are pure
;;; functions from state to [state' actions]; `transact!` applies them with
;;; compare-and-set! and runs the returned actions only after the swap
;;; succeeds, so no side effect ever runs inside a swap and no callback runs
;;; while state is being updated.
;;;
;;; State:
;;;   :clist      ConsecutiveList of tracked seqs
;;;   :queues     did -> vector of queued tasks {:idx :item-id :handler}
;;;   :running    set of dids currently executing a handler
;;;   :active     count of partitions actively processing
;;;   :next-idx   global arrival counter (dispatch picks lowest head :idx)
;;;   :pending    tracked-but-not-completed event count (for drain!)
;;;   :cursor     latest consecutive completed seq (or :start-cursor)
;;;   :writing?   a cursor-store write is in flight
;;;   :write-latest  newest cursor queued for the store
;;;   :drain-cbs  callbacks waiting for quiescence
;;;   :destroyed? no new work accepted

(defn- transact!
  "Atomically apply the pure step fn (state -> [state' actions]) to the
  state atom, then run each action with run-action! outside the swap."
  [state step run-action!]
  (let [actions (loop []
                  (let [old @state
                        [new actions] (step old)]
                    (if (compare-and-set! state old new)
                      actions
                      (recur))))]
    (doseq [action actions]
      (run-action! action))
    nil))

(defn- check-drain
  "If the runner is quiescent, release any waiting drain callbacks."
  [st]
  (if (and (zero? (:pending st))
           (not (:writing? st))
           (seq (:drain-cbs st)))
    [(assoc st :drain-cbs [])
     (mapv (fn [cb] {:action :drain :cb cb}) (:drain-cbs st))]
    [st []]))

(defn- dispatch
  "Start as many queued tasks as :concurrency allows. A partition (did) is
  eligible when it has queued work and is not already running; among eligible
  partitions the one whose head task arrived first is started, so
  :concurrency 1 degenerates to global arrival order."
  [st concurrency]
  (loop [st st
         actions []]
    (if (and concurrency (>= (:active st) concurrency))
      [st actions]
      (let [candidates (for [[did q] (:queues st)
                             :when (not (contains? (:running st) did))]
                         [did (nth q 0)])]
        (if (empty? candidates)
          [st actions]
          (let [[did task] (apply min-key (fn [[_ t]] (:idx t)) candidates)
                q' (subvec (get (:queues st) did) 1)
                st (-> st
                       (assoc :queues (if (seq q')
                                        (assoc (:queues st) did q')
                                        (dissoc (:queues st) did)))
                       (update :running conj did)
                       (update :active inc))]
            (recur st (conj actions {:action :run :did did :task task}))))))))

(defn- advance-cursor
  "Record the new consecutive cursor and, when a store is configured, queue
  a serialized write. At most one store write is ever in flight; newer values
  arriving mid-write coalesce into :write-latest, so the store never sees an
  older cursor after a newer one."
  [st new-cursor store?]
  (let [st (assoc st :cursor new-cursor)]
    (cond
      (not store?) [st []]
      (:writing? st) [(assoc st :write-latest new-cursor) []]
      :else [(assoc st :writing? true :write-latest new-cursor)
             [{:action :write-cursor :cursor new-cursor}]])))

(defn- complete-step
  "Pure step run when the handler for item-id (on partition did) finishes."
  [st did item-id concurrency store?]
  (let [[clist completed] (complete (:clist st) item-id)
        st (-> st
               (assoc :clist clist)
               (update :running disj did)
               (update :active dec)
               (update :pending dec))
        [st cursor-actions] (if (seq completed)
                              (advance-cursor st (peek completed) store?)
                              [st []])
        [st run-actions] (dispatch st concurrency)
        [st drain-actions] (check-drain st)]
    [st (-> cursor-actions (into run-actions) (into drain-actions))]))

(defn- write-done-step
  "Pure step run when a cursor-store write for `written` completes. Starts
  the next write if a newer cursor was queued meanwhile."
  [st written]
  (let [latest (:write-latest st)]
    (if (and latest (> latest written))
      [st [{:action :write-cursor :cursor latest}]]
      (check-drain (assoc st :writing? false)))))

(declare ^:private run-action!)

(defn- run-task!
  "Invoke the handler for task on partition did, guarding against handlers
  that throw or call their callback more than once. Errors (thrown or
  delivered as {:error ...}) are reported via cast/alert but still count as
  completion for cursor purposes."
  [{:keys [state concurrency store] :as runner} did {:keys [item-id handler]}]
  (let [completed? (atom false)
        finish! (fn []
                  (when (compare-and-set! completed? false true)
                    (transact! state
                               #(complete-step % did item-id concurrency
                                               (some? store))
                               #(run-action! runner %))))
        cb (fn [res]
             (when (and (map? res) (:error res))
               (cast/alert {:message "Sync runner event handler returned an error."
                            :did did
                            :error res}))
             (finish!))]
    (try
      (handler cb)
      (catch #?(:clj Throwable :cljs :default) t
        (cast/alert {:message "Sync runner event handler threw."
                     :did did
                     :ex t})
        (finish!)))))

(defn- run-action!
  "Execute one action produced by a pure step, outside any swap."
  [{:keys [state store] :as runner} {:keys [action] :as a}]
  (case action
    :run (let [{:keys [did task]} a]
           #?(:clj (future (run-task! runner did task))
              :cljs (run-task! runner did task)))
    :write-cursor (let [c (:cursor a)]
                    (cursor/set-cursor store c
                                       (fn [_]
                                         (transact! state
                                                    #(write-done-step % c)
                                                    #(run-action! runner %)))))
    :drain ((:cb a) {})))

;;; Public API

(defn memory-runner
  "Partitioned event runner. Events for the same did run serially in arrival
  order; events for different dids run concurrently. Options:
    :concurrency   max partitions processed at once (default nil = unbounded)
    :cursor-store  atproto.sync.cursor/CursorStore updated with the latest
                   *consecutive* completed seq
    :start-cursor  initial cursor"
  [& {:keys [concurrency cursor-store start-cursor]}]
  {:concurrency concurrency
   :store cursor-store
   :state (atom {:clist (consecutive-list)
                 :queues {}
                 :running #{}
                 :active 0
                 :next-idx 0
                 :pending 0
                 :cursor start-cursor
                 :writing? false
                 :write-latest nil
                 :drain-cbs []
                 :destroyed? false})})

(defn track-event
  "Schedule handler-fn — a (fn [cb]) that calls cb when the event is durably
  processed — on the partition for did, recording seq at scheduling time so
  cursor order matches arrival order. No-op after destroy!."
  [{:keys [state concurrency] :as runner} did seq handler-fn]
  (transact! state
             (fn [st]
               (if (:destroyed? st)
                 [st []]
                 (let [[clist item-id] (push (:clist st) seq)
                       task {:idx (:next-idx st)
                             :item-id item-id
                             :handler handler-fn}
                       st (-> st
                              (assoc :clist clist)
                              (update :next-idx inc)
                              (update :pending inc)
                              (update-in [:queues did] (fnil conj []) task))]
                   (dispatch st concurrency))))
             #(run-action! runner %)))

(defn get-cursor
  "Yield {:cursor n} to cb: :start-cursor until the first advance, then the
  latest consecutive completed seq (nil when neither exists)."
  [{:keys [state]} cb]
  (cb {:cursor (:cursor @state)}))

(defn drain!
  "Call cb with {} once all queued work (and any in-flight cursor-store
  write) has completed. Immediate if already quiescent."
  [{:keys [state] :as runner} cb]
  (transact! state
             (fn [st]
               (if (and (zero? (:pending st)) (not (:writing? st)))
                 [st [{:action :drain :cb cb}]]
                 [(update st :drain-cbs conj cb) []]))
             #(run-action! runner %)))

(defn destroy!
  "Stop accepting work and drop queued-but-unstarted work. In-flight
  handlers run to completion; subsequent track-event calls are ignored."
  [{:keys [state] :as runner}]
  (transact! state
             (fn [st]
               (let [dropped (transduce (map count) + (vals (:queues st)))
                     st (-> st
                            (assoc :destroyed? true :queues {})
                            (update :pending - dropped))]
                 (check-drain st)))
             #(run-action! runner %)))
