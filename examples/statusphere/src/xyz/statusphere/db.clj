(ns xyz.statusphere.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [atproto.runtime.cast :as cast]))

(def db
  {:dbtype "sqlite"
   :dbname "statusphere.db"})

(def create-tables
  [
   "create table if not exists status (
      uri varchar primary key,
      author_did varchar,
      status varchar,
      created_at varchar,
      indexed_at varchar)"

   "create table if not exists auth_session (
      key varchar primary key,
      session varchar)"

   "create table if not exists auth_state (
      key varchar primary key,
      state varchar)"])

(defn up!
  []
  (mapv #(jdbc/execute-one! db [%])
        create-tables))

(def drop-tables
  ["drop table status"
   "drop table auth_session"
   "drop table auth_state"])

(defn down!
  []
  (mapv #(jdbc/execute-one! db [%])
        drop-tables))

(defn init
  []
  (cast/event {:message "Initializing the database..."})
  (up!)
  (cast/event {:message "Database initialized."}))

(defn statuses
  [{:keys [limit]}]
  (sql/query db ["select * from status order by indexed_at desc limit ?" limit]))

(defn user-status
  [{:keys [did]}]
  (first
   (sql/query db
              ["select * from status where author_did = ? order by indexed_at desc limit 1"
               did])))

(defn upsert-status!
  [{:keys [status/uri
           status/author_did
           status/status
           status/created_at
           status/indexed_at]}]
  (jdbc/execute-one! db
                     [(str "insert into status"
                           " (uri, author_did, status, created_at, indexed_at) values (?, ?, ?, ?, ?)"
                           " on conflict(uri) do update set status=?, indexed_at=?")
                      uri author_did status created_at indexed_at status indexed_at]))
