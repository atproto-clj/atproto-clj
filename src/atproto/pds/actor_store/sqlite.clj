(ns atproto.pds.actor-store.sqlite
  "SQLite ActorStorage: a sharded directory of per-actor SQLite
  databases plus the actor's persisted key material.

  Layout (packages/pds/src/actor-store/actor-store.ts getLocation):
    {directory}/{sha256-hex(did)[0:2]}/{did}/store.sqlite
    {directory}/{sha256-hex(did)[0:2]}/{did}/key

  Databases are opened per operation and closed afterwards (reference
  transact/read semantics). The reader/transactor handle (SqlRepoStore)
  implements WS-04's blockstore protocols over the reference per-actor
  schema (packages/pds/src/actor-store/db/schema: repo_block, repo_root,
  record, blob, record_blob, backlink, account_pref — port of
  sql-repo-reader.ts / sql-repo-transactor.ts) and the record-index
  protocol (port of record/transactor.ts)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [atproto.data :as data]
            [atproto.data.cbor :as cbor]
            [atproto.at-uri :as at-uri]
            [atproto.runtime.crypto :as runtime.crypto]
            [atproto.runtime.json :as json]
            [atproto.pds.sql :as sql]
            [atproto.pds.actor-store.storage :as storage]
            [atproto.pds.actor-store.record :as record]
            [atproto.repo.blockstore :as blockstore])
  (:import [java.io File]
           [java.sql Connection]))

(set! *warn-on-reflection* true)

(def migrations
  "Ordered migrations for an actor store database."
  [["001-init"
    [(str "CREATE TABLE repo_root ("
          "did TEXT PRIMARY KEY, "
          "cid TEXT NOT NULL, "
          "rev TEXT NOT NULL, "
          "indexedAt TEXT NOT NULL)")
     (str "CREATE TABLE repo_block ("
          "cid TEXT PRIMARY KEY, "
          "repoRev TEXT NOT NULL, "
          "size INTEGER NOT NULL, "
          "content BLOB NOT NULL)")
     "CREATE INDEX repo_block_repo_rev_idx ON repo_block (repoRev, cid)"
     (str "CREATE TABLE record ("
          "uri TEXT PRIMARY KEY, "
          "cid TEXT NOT NULL, "
          "collection TEXT NOT NULL, "
          "rkey TEXT NOT NULL, "
          "repoRev TEXT NOT NULL, "
          "indexedAt TEXT NOT NULL, "
          "takedownRef TEXT)")
     "CREATE INDEX record_cid_idx ON record (cid)"
     "CREATE INDEX record_collection_idx ON record (collection)"
     "CREATE INDEX record_repo_rev_idx ON record (repoRev)"
     (str "CREATE TABLE blob ("
          "cid TEXT PRIMARY KEY, "
          "mimeType TEXT NOT NULL, "
          "size INTEGER NOT NULL, "
          "tempKey TEXT, "
          "width INTEGER, "
          "height INTEGER, "
          "createdAt TEXT NOT NULL, "
          "takedownRef TEXT)")
     "CREATE INDEX blob_tempkey_idx ON blob (tempKey)"
     (str "CREATE TABLE record_blob ("
          "blobCid TEXT NOT NULL, "
          "recordUri TEXT NOT NULL, "
          "PRIMARY KEY (blobCid, recordUri))")
     (str "CREATE TABLE backlink ("
          "uri TEXT NOT NULL, "
          "path TEXT NOT NULL, "
          "linkTo TEXT NOT NULL, "
          "PRIMARY KEY (uri, path))")
     "CREATE INDEX backlink_link_to_idx ON backlink (path, linkTo)"
     (str "CREATE TABLE account_pref ("
          "id INTEGER PRIMARY KEY AUTOINCREMENT, "
          "name TEXT NOT NULL, "
          "valueJson TEXT NOT NULL)")]]])

(defn- cid->str [cid] (data/format-cid cid))

(defn- put-block-row!
  [conn cid-str rev ^bytes bytes]
  (sql/execute!
   conn
   [(str "INSERT INTO repo_block (cid, repoRev, size, content) VALUES (?, ?, ?, ?) "
         "ON CONFLICT (cid) DO NOTHING")
    cid-str rev (alength bytes) bytes]))

;; SqlRepoStore satisfies the blockstore + record-index protocols; the
;; same value is handed out by with-reader (reads only; the connection
;; is opened with PRAGMA query_only) and with-transaction (inside a
;; write transaction). current-rev tracks the rev new blocks are indexed
;; under (the reference passes rev explicitly to putBlock/putMany; the
;; protocol carries no rev, so apply-commit!/update-root! set it here).
(defrecord SqlRepoStore [^Connection conn did blobstore current-rev]
  blockstore/ReadableBlockstore
  (get-bytes [_ cid]
    (:content (sql/execute-one!
               conn ["SELECT content FROM repo_block WHERE cid = ?" (cid->str cid)])))
  (has-block? [_ cid]
    (some? (sql/execute-one!
            conn ["SELECT cid FROM repo_block WHERE cid = ?" (cid->str cid)])))
  (get-blocks [_ cids]
    (let [cids (vec cids)
          found (into {}
                      (mapcat
                       (fn [chunk]
                         (let [marks (str/join ", " (repeat (count chunk) "?"))
                               rows (sql/execute!
                                     conn
                                     (into [(str "SELECT cid, content FROM repo_block "
                                                 "WHERE cid IN (" marks ")")]
                                           (map cid->str chunk)))]
                           (map (juxt :cid :content) rows))))
                      (partition-all 500 cids))]
      (reduce (fn [res cid]
                (if-let [bytes (get found (cid->str cid))]
                  (assoc-in res [:blocks cid] bytes)
                  (update res :missing conj cid)))
              {:blocks {} :missing []}
              cids)))

  blockstore/WritableBlockstore
  (get-root [_]
    (some-> (sql/execute-one! conn ["SELECT cid FROM repo_root WHERE did = ?" did])
            :cid
            data/parse-cid))
  (put-block! [_ cid bytes]
    (put-block-row! conn (cid->str cid) (or @current-rev "") bytes)
    nil)
  (put-blocks! [_ block-map]
    (let [rev (or @current-rev "")]
      (doseq [[cid bytes] block-map]
        (put-block-row! conn (cid->str cid) rev bytes)))
    nil)
  (update-root! [_ cid rev]
    (vreset! current-rev rev)
    (sql/execute!
     conn
     [(str "INSERT INTO repo_root (did, cid, rev, indexedAt) VALUES (?, ?, ?, ?) "
           "ON CONFLICT (did) DO UPDATE SET "
           "cid = excluded.cid, rev = excluded.rev, indexedAt = excluded.indexedAt")
      did (cid->str cid) rev (sql/now-iso)])
    nil)
  (apply-commit! [this commit-data]
    (let [{:keys [cid rev new-blocks removed-cids]} commit-data]
      (blockstore/update-root! this cid rev)
      (blockstore/put-blocks! this new-blocks)
      (doseq [chunk (partition-all 500 removed-cids)]
        (let [marks (str/join ", " (repeat (count chunk) "?"))]
          (sql/execute!
           conn
           (into [(str "DELETE FROM repo_block WHERE cid IN (" marks ")")]
                 (map cid->str chunk))))))
    nil))

;; -----------------------------------------------------------------------------
;; Record index
;; -----------------------------------------------------------------------------

(defn- row->record
  [row]
  {:uri (:uri row)
   :cid (data/parse-cid (:cid row))
   :value (some-> ^bytes (:content row) cbor/decode)
   :indexed-at (:indexedAt row)
   :takedown-ref (:takedownRef row)})

(extend-type SqlRepoStore
  record/RecordIndex
  (index-record! [{:keys [conn]} {:keys [uri cid record repo-rev]}]
    (let [{:keys [collection rkey]} (record/parse-record-uri! uri)]
      (sql/execute!
       conn
       [(str "INSERT INTO record (uri, cid, collection, rkey, repoRev, indexedAt) "
             "VALUES (?, ?, ?, ?, ?, ?) "
             "ON CONFLICT (uri) DO UPDATE SET "
             "cid = excluded.cid, repoRev = excluded.repoRev, indexedAt = excluded.indexedAt")
        uri (data/format-cid cid) collection rkey (or repo-rev "") (sql/now-iso)])
      (sql/execute! conn ["DELETE FROM backlink WHERE uri = ?" uri])
      (doseq [{:keys [path link-to]} (record/backlinks uri record)]
        (sql/execute!
         conn
         ["INSERT INTO backlink (uri, path, linkTo) VALUES (?, ?, ?)" uri path link-to]))
      (sql/execute! conn ["DELETE FROM record_blob WHERE recordUri = ?" uri])
      (doseq [blob-cid (record/blob-cids record)]
        (sql/execute!
         conn
         [(str "INSERT INTO record_blob (blobCid, recordUri) VALUES (?, ?) "
               "ON CONFLICT (blobCid, recordUri) DO NOTHING")
          (data/format-cid blob-cid) uri]))
      nil))
  (delete-record! [{:keys [conn]} uri]
    (sql/execute! conn ["DELETE FROM record WHERE uri = ?" uri])
    (sql/execute! conn ["DELETE FROM backlink WHERE uri = ?" uri])
    (sql/execute! conn ["DELETE FROM record_blob WHERE recordUri = ?" uri])
    nil)
  (get-record
    ([store uri] (record/get-record store uri {}))
    ([{:keys [conn]} uri {:keys [include-takedowns?]}]
     (when-let [row (sql/execute-one!
                     conn
                     [(str "SELECT r.uri, r.cid, r.indexedAt, r.takedownRef, b.content "
                           "FROM record r LEFT JOIN repo_block b ON b.cid = r.cid "
                           "WHERE r.uri = ?")
                      uri])]
       (when (or include-takedowns? (nil? (:takedownRef row)))
         (row->record row)))))
  (list-records [{:keys [conn]} {:keys [collection limit cursor reverse?]}]
    (let [limit (or limit 50)
          rows (sql/execute!
                conn
                (cond-> [(str "SELECT r.uri, r.cid, r.indexedAt, r.takedownRef, b.content "
                              "FROM record r LEFT JOIN repo_block b ON b.cid = r.cid "
                              "WHERE r.collection = ? AND r.takedownRef IS NULL "
                              (when cursor
                                (if reverse? "AND r.rkey < ? " "AND r.rkey > ? "))
                              "ORDER BY r.rkey " (if reverse? "DESC" "ASC")
                              " LIMIT ?")
                         collection]
                  cursor (conj cursor)
                  true (conj limit)))
          records (mapv row->record rows)]
      (cond-> {:records records}
        (= (count rows) limit)
        (assoc :cursor (at-uri/rkey (:uri (last records)))))))
  (list-collections [{:keys [conn]}]
    (mapv :collection
          (sql/execute! conn ["SELECT DISTINCT collection FROM record ORDER BY collection"])))
  (backlink-uris [{:keys [conn]} path link-to]
    (mapv :uri
          (sql/execute!
           conn
           ["SELECT uri FROM backlink WHERE path = ? AND linkTo = ? ORDER BY uri"
            path link-to])))
  (register-blob! [{:keys [conn]} {:keys [cid mime-type size temp-key width height]}]
    (sql/execute!
     conn
     [(str "INSERT INTO blob (cid, mimeType, size, tempKey, width, height, createdAt) "
           "VALUES (?, ?, ?, ?, ?, ?, ?) "
           "ON CONFLICT (cid) DO UPDATE SET "
           "mimeType = excluded.mimeType, size = excluded.size, tempKey = excluded.tempKey")
      (data/format-cid cid) mime-type size temp-key width height (sql/now-iso)])
    nil)
  (blob-metadata [{:keys [conn]} cid]
    (when-let [row (sql/execute-one!
                    conn
                    ["SELECT cid, mimeType, size, tempKey, createdAt, takedownRef FROM blob WHERE cid = ?"
                     (data/format-cid cid)])]
      {:cid (data/parse-cid (:cid row))
       :mime-type (:mimeType row)
       :size (:size row)
       :temp-key (:tempKey row)
       :created-at (:createdAt row)
       :takedown-ref (:takedownRef row)}))
  (record-uris-for-blob [{:keys [conn]} cid]
    (mapv :recordUri
          (sql/execute!
           conn
           ["SELECT recordUri FROM record_blob WHERE blobCid = ? ORDER BY recordUri"
            (data/format-cid cid)]))))

(defn root-rev
  "The rev stored alongside the repo root, or nil."
  [{:keys [conn did]}]
  (:rev (sql/execute-one! conn ["SELECT rev FROM repo_root WHERE did = ?" did])))

(defn repo-store
  "A SqlRepoStore over an open actor-database connection."
  [conn did blobstore]
  (let [store (->SqlRepoStore conn did blobstore (volatile! nil))]
    (vreset! (:current-rev store) (root-rev store))
    store))

;; -----------------------------------------------------------------------------
;; ActorStorage
;; -----------------------------------------------------------------------------

(defn actor-dir
  "The sharded directory for a DID."
  ^File [^File directory did]
  (io/file directory
           (subs (runtime.crypto/sha256-hex ^String did) 0 2)
           did))

(defn- db-file ^File [^File directory did] (io/file (actor-dir directory did) "store.sqlite"))
(defn- key-file ^File [^File directory did] (io/file (actor-dir directory did) "key"))

(defn- not-found!
  [did]
  (throw (ex-info (str "No actor store for " did)
                  {:error "ActorNotFound"
                   :message (str "No actor store for " did)
                   :did did})))

(defrecord SqliteActorStorage [^File directory blobstore-factory]
  storage/ActorStorage
  (actor-exists? [_ did]
    (.exists (db-file directory did)))
  (create-actor! [_ did key-info]
    (when (.exists (db-file directory did))
      (throw (ex-info (str "Actor store already exists for " did)
                      {:error "ActorAlreadyExists"
                       :message (str "Actor store already exists for " did)
                       :did did})))
    (.mkdirs (actor-dir directory did))
    (spit (key-file directory did) (json/write-str key-info))
    (with-open [conn (sql/connect (.getPath (db-file directory did)))]
      (sql/migrate! conn migrations))
    nil)
  (read-key-info [_ did]
    (when (.exists (key-file directory did))
      (json/read-str (slurp (key-file directory did)))))
  (destroy-actor! [_ did]
    (let [dir (actor-dir directory did)]
      (when (.exists dir)
        (run! io/delete-file (reverse (file-seq dir)))))
    nil)
  (with-reader [_ did f]
    (when-not (.exists (db-file directory did))
      (not-found! did))
    (with-open [conn (sql/connect (.getPath (db-file directory did))
                                  :pragmas ["PRAGMA query_only = ON"])]
      (f (repo-store conn did (when blobstore-factory (blobstore-factory did))))))
  (with-transaction [_ did f]
    (when-not (.exists (db-file directory did))
      (not-found! did))
    (with-open [conn (sql/connect (.getPath (db-file directory did)))]
      (let [txn (repo-store conn did (when blobstore-factory (blobstore-factory did)))]
        (sql/in-write-tx* conn (fn [_] (f txn)))))))

(defn storage
  "A SQLite ActorStorage.

  config:
    :directory          (required) root directory for actor data
    :blobstore-factory  optional (fn [did] -> atproto.pds.blobstore/BlobStore)"
  [{:keys [directory blobstore-factory]}]
  (->SqliteActorStorage (io/file directory) blobstore-factory))
