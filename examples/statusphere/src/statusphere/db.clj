(ns statusphere.db
  (:require [clojure.instant :refer [read-instant-date]]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql])
  (:import [java.time Instant]))

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

(defn row->status
  [{:keys [status/uri
           status/author_did
           status/status
           status/created_at
           status/indexed_at]}]
  {:uri uri
   :author-did author_did
   :status status
   :created-at (Instant/parse created_at)
   :indexed-at (Instant/parse indexed_at)})

(defn status->row
  [{:keys [uri author-did status created-at indexed-at]}]
  {:uri uri
   :author_did author-did
   :status status
   :created_at (str created-at)
   :indexed_at (str indexed-at)})

(defn insert-status!
  [status]
  (sql/insert! db :status (status->row status)))

(defn latest-statuses
  []
  (->> (sql/query db ["select * from status order by indexed_at desc limit 10"])
       (map row->status)))

(defn my-status
  [did]
  (some-> (sql/query db ["select * from status where author_did = ? order by indexed_at desc limit 1"
                         did])
          first
          row->status))
