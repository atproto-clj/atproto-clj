(ns atproto.pds.sql
  "Shared next.jdbc/SQLite helpers for the PDS service pieces.

  Conventions (mirroring packages/pds/src/db in the TypeScript reference
  implementation): one SQLite file per logical store, WAL journal mode,
  camelCase column names matching the reference schemas, raw SQL strings,
  BEGIN IMMEDIATE write transactions, and busy retries around short
  write transactions (reference retrySqlite).

  Used by the OAuth provider SQLite stores (11A), the actor store (11B),
  and the sequencer (11C)."
  (:require [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [atproto.runtime.cast :as cast])
  (:import [java.sql Connection]
           [java.time Instant]
           [org.sqlite SQLiteErrorCode SQLiteException]))

(set! *warn-on-reflection* true)

(defn now-iso
  "The current time as an ISO-8601 UTC datetime string (reference
  ISO timestamp convention for indexedAt/sequencedAt columns)."
  []
  (str (Instant/now)))

(defn connect
  "Open a SQLite connection to db-path with the SDK pragmas applied:
  WAL journal mode, NORMAL synchronous, foreign keys on, 5s busy timeout.

  db-path may be a path string or :memory (a private in-memory database,
  for tests)."
  ^Connection [db-path & {:keys [pragmas]}]
  (let [conn (jdbc/get-connection
              {:jdbcUrl (if (= :memory db-path)
                          "jdbc:sqlite::memory:"
                          (str "jdbc:sqlite:" db-path))})]
    (doseq [pragma (concat ["PRAGMA journal_mode = WAL"
                            "PRAGMA synchronous = NORMAL"
                            "PRAGMA foreign_keys = ON"
                            "PRAGMA busy_timeout = 5000"]
                           pragmas)]
      (jdbc/execute! conn [pragma]))
    conn))

(def ^:private query-opts
  {:builder-fn rs/as-unqualified-maps})

(defn execute!
  "Run a [sql & params] vector; rows as plain unqualified maps."
  [conn sql-params]
  (jdbc/execute! conn sql-params query-opts))

(defn execute-one!
  "Run a [sql & params] vector; the first row as a plain unqualified map."
  [conn sql-params]
  (jdbc/execute-one! conn sql-params query-opts))

(defn- busy?
  [^Throwable t]
  (and (instance? SQLiteException t)
       (contains? #{SQLiteErrorCode/SQLITE_BUSY SQLiteErrorCode/SQLITE_LOCKED}
                  (.getResultCode ^SQLiteException t))))

(defn retry-busy
  "Run (f), retrying on SQLITE_BUSY/SQLITE_LOCKED with linear backoff
  (reference retrySqlite: up to 5 attempts). Other exceptions propagate."
  [f]
  (loop [attempt 1]
    (let [result (try
                   [::ok (f)]
                   (catch Exception e
                     (if (and (busy? e) (< attempt 5))
                       [::retry e]
                       (throw e))))]
      (if (= ::ok (first result))
        (second result)
        (do (cast/dev {:message "SQLite busy; retrying" :attempt attempt})
            (Thread/sleep (long (* 10 attempt)))
            (recur (inc attempt)))))))

(defn in-write-tx*
  "Run (f conn) inside a BEGIN IMMEDIATE transaction, committing on
  success and rolling back on any throw. Retries the whole transaction
  on SQLITE_BUSY."
  [^Connection conn f]
  (retry-busy
   (fn []
     (jdbc/execute! conn ["BEGIN IMMEDIATE"])
     (try
       (let [ret (f conn)]
         (jdbc/execute! conn ["COMMIT"])
         ret)
       (catch Throwable t
         (try
           (jdbc/execute! conn ["ROLLBACK"])
           (catch Exception rollback-error
             (cast/alert {:message "SQLite rollback failed"
                          :ex rollback-error})))
         (throw t))))))

(defmacro with-write-tx
  "Evaluate body inside a BEGIN IMMEDIATE transaction on conn."
  [[binding conn] & body]
  `(in-write-tx* ~conn (fn [~binding] ~@body)))

;; -----------------------------------------------------------------------------
;; Migrations
;; -----------------------------------------------------------------------------

(defn migrate!
  "Apply ordered migrations to conn.

  migrations is an ordered seq of [name up] pairs where name is a unique
  string and up is either a seq of SQL statement strings or a (fn [conn]).
  Applied migrations are recorded in a `migrations` table and skipped on
  subsequent runs; each pending migration runs in its own write
  transaction (tiny mirror of the reference kysely migrator)."
  [conn migrations]
  (jdbc/execute! conn [(str "CREATE TABLE IF NOT EXISTS migrations ("
                            "name TEXT PRIMARY KEY, "
                            "appliedAt TEXT NOT NULL)")])
  (let [applied (->> (execute! conn ["SELECT name FROM migrations"])
                     (map :name)
                     set)]
    (doseq [[mig-name up] migrations
            :when (not (applied mig-name))]
      (in-write-tx*
       conn
       (fn [conn]
         (if (fn? up)
           (up conn)
           (doseq [stmt up]
             (jdbc/execute! conn [stmt])))
         (jdbc/execute! conn ["INSERT INTO migrations (name, appliedAt) VALUES (?, ?)"
                              mig-name (now-iso)]))))))

;; -----------------------------------------------------------------------------
;; Filesystem helpers
;; -----------------------------------------------------------------------------

(defn ensure-parent-dir!
  "Create the parent directory of path if needed."
  [path]
  (when-let [parent (.getParentFile (io/file path))]
    (.mkdirs parent))
  path)
