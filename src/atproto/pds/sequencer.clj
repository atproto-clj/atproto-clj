(ns atproto.pds.sequencer
  "Durable, totally-ordered event log backing com.atproto.sync.subscribeRepos.

  A single SQLite database with the repo_seq table
  (packages/pds/src/sequencer/db/schema.ts); the AUTOINCREMENT primary
  key is the stream's total order. Like the reference, this assumes a
  single sequencer process per database: multi-process deployments would
  need an external ordering authority.

  Event payloads are DAG-CBOR-encoded (WS-02) maps shaped like the
  subscribeRepos message bodies minus :seq/:time, which are added at
  emission from the row (packages/pds/src/sequencer/events.ts). Commit
  and sync payloads embed CAR slices produced with the WS-04 writer.

  Write fns and init/close! are async per the SDK convention; cursor
  queries are synchronous and cheap. A background poll loop delivers
  newly-committed batches (up to 1000 rows) to `subscribe` listeners,
  with exponential backoff capped at 1s when idle; writes poke the loop
  so delivery is prompt."
  (:require [clojure.core.async :as async]
            [atproto.data :as data]
            [atproto.data.cbor :as cbor]
            [atproto.repo.car :as car]
            [atproto.runtime.cast :as cast]
            [atproto.runtime.interceptor :as i]
            [atproto.pds.sql :as sql]))

(set! *warn-on-reflection* true)

(def migrations
  [["001-init"
    [(str "CREATE TABLE repo_seq ("
          "seq INTEGER PRIMARY KEY AUTOINCREMENT, "
          "did TEXT NOT NULL, "
          "eventType TEXT NOT NULL, "
          "event BLOB NOT NULL, "
          "invalidated INTEGER NOT NULL DEFAULT 0, "
          "sequencedAt TEXT NOT NULL)")
     "CREATE INDEX repo_seq_did_idx ON repo_seq (did)"
     "CREATE INDEX repo_seq_event_type_idx ON repo_seq (eventType)"
     "CREATE INDEX repo_seq_sequenced_at_idx ON repo_seq (sequencedAt)"]]])

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

;; The sequencer opens a short-lived connection per operation (WAL mode:
;; many readers, serialized writers), so writes may come from any thread.
(defrecord Sequencer [db-path state notify-ch poll-thread])

(defn- row->event
  [row]
  {:seq (:seq row)
   :did (:did row)
   :type (case (:eventType row)
           "append" :commit
           (keyword (:eventType row)))
   :event (cbor/decode (:event row))
   :time (:sequencedAt row)})

(defn current-seq
  "The latest sequenced seq, or nil for an empty log. Sync."
  [{:keys [db-path]}]
  (with-open [conn (sql/connect db-path)]
    (:seq (sql/execute-one!
           conn ["SELECT seq FROM repo_seq ORDER BY seq DESC LIMIT 1"]))))

(defn next-after
  "The first event row with seq > cursor, or nil. Sync."
  [{:keys [db-path]} cursor]
  (with-open [conn (sql/connect db-path)]
    (some-> (sql/execute-one!
             conn ["SELECT * FROM repo_seq WHERE seq > ? ORDER BY seq ASC LIMIT 1"
                   (long (or cursor 0))])
            row->event)))

(defn earliest-after-time
  "The earliest event row sequenced at or after the ISO datetime, or nil.
  Sync."
  [{:keys [db-path]} time]
  (with-open [conn (sql/connect db-path)]
    (some-> (sql/execute-one!
             conn [(str "SELECT * FROM repo_seq WHERE sequencedAt >= ? "
                        "ORDER BY sequencedAt ASC LIMIT 1")
                   time])
            row->event)))

(defn request-range
  "Decoded events with seq in (earliest-seq, latest-seq], oldest first,
  skipping invalidated rows.

  opts: :earliest-seq (default 0), :latest-seq, :earliest-time, :limit.
  Sync."
  [{:keys [db-path]} {:keys [earliest-seq latest-seq earliest-time limit]}]
  (with-open [conn (sql/connect db-path)]
    (mapv row->event
          (sql/execute!
           conn
           (cond-> [(str "SELECT * FROM repo_seq WHERE invalidated = 0 AND seq > ?"
                         (when latest-seq " AND seq <= ?")
                         (when earliest-time " AND sequencedAt >= ?")
                         " ORDER BY seq ASC"
                         (when limit " LIMIT ?"))
                    (long (or earliest-seq 0))]
             latest-seq (conj (long latest-seq))
             earliest-time (conj earliest-time)
             limit (conj (long limit)))))))

(defn- sequence-evt!
  [{:keys [db-path notify-ch] :as seqr} did event-type evt cb]
  (cb (attempt
       (fn []
         (let [seq (sql/retry-busy
                    (fn []
                      (with-open [conn (sql/connect db-path)]
                        (:seq (sql/execute-one!
                               conn
                               [(str "INSERT INTO repo_seq "
                                     "(did, eventType, event, invalidated, sequencedAt) "
                                     "VALUES (?, ?, ?, 0, ?) RETURNING seq")
                                did event-type (cbor/encode evt) (sql/now-iso)])))))]
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
  [{:keys [state notify-ch] :as seqr}]
  (loop [last-seen (or (current-seq seqr) 0)
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
  "Open (creating/migrating as needed) the sequencer database and start
  the poll loop.

  config: {:db-path path}
  Async; yields the sequencer or {:error ...}."
  [{:keys [db-path] :as config} & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (cb (attempt
         (fn []
           (sql/ensure-parent-dir! db-path)
           (with-open [conn (sql/connect db-path)]
             (sql/migrate! conn migrations))
           (let [state (atom {:listeners {} :closed? false})
                 notify-ch (async/chan (async/dropping-buffer 1))
                 seqr (->Sequencer db-path state notify-ch nil)
                 thread (doto (Thread. ^Runnable #(run-poll-loop seqr)
                                       "atproto-sequencer-poll")
                          (.setDaemon true)
                          (.start))]
             (assoc seqr :poll-thread thread)))))
    val))

(defn close!
  "Stop the poll loop. Async; yields {:closed true}."
  [{:keys [state notify-ch ^Thread poll-thread]} & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (swap! state assoc :closed? true)
    (async/offer! notify-ch :closed)
    (when poll-thread
      (.join poll-thread 5000))
    (cb {:closed true})
    val))
