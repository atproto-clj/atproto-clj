(ns statusphere.db
  "Datomic schema, queries, and transaction builders.

  Every function takes a Datomic connection or database value; nothing here
  knows about components, HTTP, or the network. The database is a disposable
  local index over the firehose — the user's PDS is the source of truth."
  (:require [datomic.api :as d]))

(set! *warn-on-reflection* true)

(def schema
  [;; One entity per xyz.statusphere.status record, keyed by AT-URI.
   {:db/ident       :status/uri
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "at:// URI of the record; unique identity, so transacting upserts."}
   {:db/ident       :status/author-did
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :status/emoji
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "The record's `status` field."}
   {:db/ident       :status/created-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Author-asserted creation time; untrusted network data."}
   {:db/ident       :status/indexed-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "When this app indexed the status; orders the feed."}

   ;; OAuth client stores. Values are JSON strings, opaque to this app.
   {:db/ident       :auth-session/key
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}
   {:db/ident       :auth-session/value
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/noHistory   true} ;; rotated tokens shouldn't accrete in history
   {:db/ident       :auth-state/key
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}
   {:db/ident       :auth-state/value
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/noHistory   true}

   ;; Jetstream cursor (µs timestamp).
   {:db/ident       :cursor/id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}
   {:db/ident       :cursor/time-us
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/noHistory   true}])

(defn connect
  "Ensure the database at uri exists, connect, and ensure the schema.
  Both steps are idempotent, so this is safe on every boot."
  [uri]
  (d/create-database uri)
  (let [conn (d/connect uri)]
    @(d/transact conn schema)
    conn))

;; -----------------------------------------------------------------------------
;; Statuses
;; -----------------------------------------------------------------------------

(def status-pull
  [:status/uri :status/author-did :status/emoji :status/created-at :status/indexed-at])

(defn recent-statuses
  "The n most recently indexed statuses, newest first.
  Walks the :status/indexed-at AVET index backwards — no scan, no sort."
  [db n]
  (->> (d/index-pull db {:index    :avet
                         :selector status-pull
                         :start    [:status/indexed-at]
                         :reverse  true})
       (take n)
       (vec)))

(defn current-status
  "The most recently indexed status for this author DID, or nil.
  Scans one author's statuses — fine at example scale."
  [db did]
  (when did
    (->> (d/q '[:find [(pull ?s pattern) ...]
                :in $ ?did pattern
                :where [?s :status/author-did ?did]]
              db did status-pull)
         (sort-by :status/indexed-at #(compare %2 %1))
         (first))))

(defn upsert-status-tx
  "Transaction data for a status map with keys
  :uri :author-did :emoji :created-at :indexed-at (instants as java.util.Date)."
  [{:keys [uri author-did emoji created-at indexed-at]}]
  [{:status/uri        uri
    :status/author-did author-did
    :status/emoji      emoji
    :status/created-at created-at
    :status/indexed-at indexed-at}])

(defn retract-status-tx
  "Retraction for uri, or nil if we never indexed it. The existence check
  guards the lookup-ref throw on transact; the single-writer ingester makes
  it race-free."
  [db uri]
  (when (d/entid db [:status/uri uri])
    [[:db/retractEntity [:status/uri uri]]]))
