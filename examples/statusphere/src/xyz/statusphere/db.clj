(ns xyz.statusphere.db
  (:require [clojure.instant :refer [read-instant-date]]
            [next.jdbc :as jdbc]))

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
