(ns atproto.pds.sequencer.sqlite
  "SQLite SequencerStorage: a single repo_seq table whose AUTOINCREMENT
  primary key is the stream's total order
  (packages/pds/src/sequencer/db/schema.ts).

  Like the reference, this assumes a single sequencer process per
  database file; multi-process deployments would need an external
  ordering authority. Connections are opened per operation (WAL mode:
  many readers, serialized writers), so calls may come from any thread."
  (:require [atproto.pds.sql :as sql]
            [atproto.pds.sequencer.storage :as storage]))

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

(defn- row->map
  [row]
  (when row
    {:seq (:seq row)
     :did (:did row)
     :event-type (:eventType row)
     :event (:event row)
     :invalidated? (pos? (:invalidated row))
     :sequenced-at (:sequencedAt row)}))

(defrecord SqliteSequencerStorage [db-path]
  storage/SequencerStorage
  (append-event! [_ {:keys [did event-type event sequenced-at]}]
    (sql/retry-busy
     (fn []
       (with-open [conn (sql/connect db-path)]
         (:seq (sql/execute-one!
                conn
                [(str "INSERT INTO repo_seq "
                      "(did, eventType, event, invalidated, sequencedAt) "
                      "VALUES (?, ?, ?, 0, ?) RETURNING seq")
                 did event-type event sequenced-at]))))))
  (current-seq [_]
    (with-open [conn (sql/connect db-path)]
      (:seq (sql/execute-one!
             conn ["SELECT seq FROM repo_seq ORDER BY seq DESC LIMIT 1"]))))
  (next-after [_ cursor]
    (with-open [conn (sql/connect db-path)]
      (row->map (sql/execute-one!
                 conn ["SELECT * FROM repo_seq WHERE seq > ? ORDER BY seq ASC LIMIT 1"
                       (long (or cursor 0))]))))
  (earliest-after-time [_ time]
    (with-open [conn (sql/connect db-path)]
      (row->map (sql/execute-one!
                 conn [(str "SELECT * FROM repo_seq WHERE sequencedAt >= ? "
                            "ORDER BY sequencedAt ASC LIMIT 1")
                       time]))))
  (request-range [_ {:keys [earliest-seq latest-seq earliest-time limit]}]
    (with-open [conn (sql/connect db-path)]
      (mapv row->map
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
  (invalidate! [_ seq]
    (with-open [conn (sql/connect db-path)]
      (sql/execute! conn ["UPDATE repo_seq SET invalidated = 1 WHERE seq = ?" (long seq)]))
    nil)
  (close! [_] nil))

(defn open
  "Open (creating/migrating as needed) a SQLite sequencer storage.

  config: {:db-path path}"
  [{:keys [db-path]}]
  (sql/ensure-parent-dir! db-path)
  (with-open [conn (sql/connect db-path)]
    (sql/migrate! conn migrations))
  (->SqliteSequencerStorage db-path))
