(ns atproto.pds.sequencer
  "Durable, totally-ordered event log backing com.atproto.sync.subscribeRepos.

  This namespace owns the library logic: event formatting per the
  reference (packages/pds/src/sequencer/events.ts), DAG-CBOR encoding
  (WS-02) and CAR slices (WS-04), timestamping, cursor queries, and a
  background poll loop delivering freshly-sequenced batches (up to 1000
  rows) to `subscribe` listeners with exponential backoff capped at 1s
  when idle (writes poke the loop so delivery is prompt).

  Persistence and ordering live behind the
  atproto.pds.sequencer.storage/SequencerStorage protocol; pass an
  implementation to `init` (atproto.pds.sequencer.memory or
  atproto.pds.sequencer.sqlite ship with the SDK).

  Event payloads are maps shaped like the subscribeRepos message bodies
  minus :seq/:time, which are added at emission from the row. Write fns
  and init/close! are async per the SDK convention; cursor queries are
  synchronous and cheap."
  (:require [clojure.core.async :as async]
            [atproto.data :as data]
            [atproto.data.cbor :as cbor]
            [atproto.repo.car :as car]
            [atproto.runtime.cast :as cast]
            [atproto.runtime.interceptor :as i]
            [atproto.pds.sql :as sql]
            [atproto.pds.sequencer.storage :as storage]))

(set! *warn-on-reflection* true)

(defn- async-opts [opts] (select-keys opts [:channel :callback :promise]))

(defn- attempt
  [f]
  (try
    (f)
    (catch Exception e
      (let [d (ex-data e)]
        (if (and (map? d) (:error d))
          (select-keys d [:error :message])
          {:error "SequencerError" :message (ex-message e)})))))

;; -----------------------------------------------------------------------------
;; Event formatting (packages/pds/src/sequencer/events.ts)
;; -----------------------------------------------------------------------------

(defn- car-slice
  "CAR bytes rooted at root-cid. blocks is a block-map (or already-encoded
  CAR bytes, passed through). The root block, when present in the map, is
  written first (the reference always includes the commit block)."
  [root-cid blocks]
  (if (bytes? blocks)
    blocks
    (let [root-bytes (get blocks root-cid)
          rest-blocks (sort-by (comp data/format-cid key)
                               (dissoc blocks root-cid))]
      (car/write-car
       root-cid
       (concat (when root-bytes [{:cid root-cid :bytes root-bytes}])
               (map (fn [[cid bytes]] {:cid cid :bytes bytes}) rest-blocks))))))

(defn format-commit-evt
  "#commit message body (minus seq/time) for a WS-04 CommitData
  (events.ts formatSeqCommit). commit-data may carry :ops
  ([{:action \"create\"|\"update\"|\"delete\" :path .. :cid ..(:prev ..)}]),
  :blobs, and :prev-data in addition to the WS-04 keys; the CAR slice is
  built from :relevant-blocks (which include the new blocks and commit,
  per the WS-04 contract)."
  [did {:keys [cid rev since relevant-blocks new-blocks ops blobs prev-data] :as commit-data}]
  (cond-> {:rebase false
           :tooBig false
           :repo did
           :commit cid
           :rev rev
           :since since
           :blocks (car-slice cid (or relevant-blocks new-blocks))
           :ops (vec (or ops []))
           :blobs (vec (or blobs []))}
    prev-data (assoc :prevData prev-data)))

(defn format-sync-evt
  "#sync message body for {:cid commit-cid :rev rev :blocks blocks}
  (events.ts formatSeqSyncEvt); :blocks is a block-map holding at least
  the commit block, or pre-built CAR bytes."
  [did {:keys [cid rev blocks]}]
  {:did did
   :blocks (car-slice cid blocks)
   :rev rev})

(defn format-identity-evt
  "#identity message body (events.ts formatSeqIdentityEvt)."
  [did handle]
  (cond-> {:did did}
    handle (assoc :handle handle)))

(defn format-account-evt
  "#account message body (events.ts formatSeqAccountEvt)."
  [did {:keys [active status]}]
  (cond-> {:did did :active (boolean active)}
    status (assoc :status status)))

;; -----------------------------------------------------------------------------
;; Sequencer
;; -----------------------------------------------------------------------------

(defrecord Sequencer [storage state notify-ch poll-thread])

(defn- row->event
  "Decode a storage row into {:seq :did :type :event :time}."
  [row]
  {:seq (:seq row)
   :did (:did row)
   :type (case (:event-type row)
           "append" :commit
           (keyword (:event-type row)))
   :event (cbor/decode (:event row))
   :time (:sequenced-at row)})

(defn current-seq
  "The latest sequenced seq, or nil for an empty log. Sync."
  [{:keys [storage]}]
  (storage/current-seq storage))

(defn next-after
  "The first (decoded) event with seq > cursor, or nil. Sync."
  [{:keys [storage]} cursor]
  (some-> (storage/next-after storage cursor) row->event))

(defn earliest-after-time
  "The earliest (decoded) event sequenced at or after the ISO datetime,
  or nil. Sync."
  [{:keys [storage]} time]
  (some-> (storage/earliest-after-time storage time) row->event))

(defn request-range
  "Decoded events with seq in (earliest-seq, latest-seq], oldest first,
  skipping invalidated rows.

  opts: :earliest-seq (default 0), :latest-seq, :earliest-time, :limit.
  Sync."
  [{:keys [storage]} opts]
  (mapv row->event (storage/request-range storage opts)))

(defn invalidate!
  "Mark a sequenced event invalidated (excluded from request-range and
  the firehose; reference invalidation semantics). Sync."
  [{:keys [storage]} seq]
  (storage/invalidate! storage seq))

(defn- sequence-evt!
  [{:keys [storage notify-ch]} did event-type evt cb]
  (cb (attempt
       (fn []
         (let [seq (storage/append-event! storage
                                          {:did did
                                           :event-type event-type
                                           :event (cbor/encode evt)
                                           :sequenced-at (sql/now-iso)})]
           (async/offer! notify-ch :new-events)
           {:seq seq})))))

(defn sequence-commit!
  "Sequence a #commit event for a WS-04 CommitData (see format-commit-evt).
  Async; yields {:seq n}."
  [seqr did commit-data & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (sequence-evt! seqr did "append" (format-commit-evt did commit-data) cb)
    val))

(defn sequence-sync!
  "Sequence a #sync event. Async; yields {:seq n}."
  [seqr did sync-data & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (sequence-evt! seqr did "sync" (format-sync-evt did sync-data) cb)
    val))

(defn sequence-identity!
  "Sequence an #identity event. Async; yields {:seq n}."
  [seqr did handle & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (sequence-evt! seqr did "identity" (format-identity-evt did handle) cb)
    val))

(defn sequence-account!
  "Sequence an #account event for {:active bool :status str?}.
  Async; yields {:seq n}."
  [seqr did status & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (sequence-evt! seqr did "account" (format-account-evt did status) cb)
    val))

(defn subscribe
  "Register a live listener called with each freshly-sequenced batch
  (a vector of decoded events, oldest first). Returns an unsubscribe
  thunk. Listener callbacks run on the poll thread and must not block."
  [{:keys [state]} callback]
  (let [id (Object.)]
    (swap! state assoc-in [:listeners id] callback)
    (fn [] (swap! state update :listeners dissoc id))))

;; poll loop backoff: 50ms doubling to 1s when idle (sequencer.ts pollDb)
(def ^:private min-poll-wait-ms 50)
(def ^:private max-poll-wait-ms 1000)

(defn- run-poll-loop
  "initial-seq is captured synchronously by init before the thread
  starts: initializing it here would race with events sequenced between
  init returning and the thread being scheduled, silently skipping them."
  [{:keys [state notify-ch] :as seqr} initial-seq]
  (loop [last-seen (long initial-seq)
         wait-ms min-poll-wait-ms]
    (when-not (:closed? @state)
      (let [batch (try
                    (request-range seqr {:earliest-seq last-seen :limit 1000})
                    (catch Exception e
                      (cast/alert {:message "Sequencer poll failed" :ex e})
                      []))]
        (if (seq batch)
          (do
            (doseq [[_ listener] (:listeners @state)]
              (try
                (listener batch)
                (catch Exception e
                  (cast/alert {:message "Sequencer listener failed" :ex e}))))
            (recur (long (:seq (peek batch))) min-poll-wait-ms))
          (do
            (async/alt!! notify-ch ([_])
                         (async/timeout wait-ms) ([_])
                         :priority true)
            (recur last-seen (min max-poll-wait-ms (* 2 wait-ms)))))))))

(defn init
  "Start a sequencer over a SequencerStorage implementation.

  config: {:storage <atproto.pds.sequencer.storage/SequencerStorage>}
  (see atproto.pds.sequencer.memory/open and
  atproto.pds.sequencer.sqlite/open)

  Async; yields the sequencer or {:error ...}."
  [{:keys [storage] :as config} & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (cb (attempt
         (fn []
           (when-not (satisfies? storage/SequencerStorage storage)
             (throw (ex-info "config requires a :storage implementing SequencerStorage."
                             {:error "InvalidStorage"
                              :message "config requires a :storage implementing SequencerStorage."})))
           (let [state (atom {:listeners {} :closed? false})
                 notify-ch (async/chan (async/dropping-buffer 1))
                 seqr (->Sequencer storage state notify-ch nil)
                 ;; capture the starting position before init returns, so
                 ;; every event sequenced afterwards reaches subscribers
                 initial-seq (long (or (storage/current-seq storage) 0))
                 thread (doto (Thread. ^Runnable #(run-poll-loop seqr initial-seq)
                                       "atproto-sequencer-poll")
                          (.setDaemon true)
                          (.start))]
             (assoc seqr :poll-thread thread)))))
    val))

(defn close!
  "Stop the poll loop and close the storage. Async; yields {:closed true}."
  [{:keys [storage state notify-ch ^Thread poll-thread]} & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (swap! state assoc :closed? true)
    (async/offer! notify-ch :closed)
    (when poll-thread
      (.join poll-thread 5000))
    (storage/close! storage)
    (cb {:closed true})
    val))
