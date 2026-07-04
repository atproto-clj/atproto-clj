(ns atproto.pds.actor-store.sqlite
  "SQLite-backed repo storage for one actor.

  Implements WS-04's blockstore protocols
  (atproto.repo.blockstore Readable/WritableBlockstore) over the
  reference per-actor schema (packages/pds/src/actor-store/db/schema):
  repo_block, repo_root, record, blob, record_blob, backlink,
  account_pref. Port of sql-repo-reader.ts / sql-repo-transactor.ts."
  (:require [clojure.string :as str]
            [atproto.data :as data]
            [atproto.pds.sql :as sql]
            [atproto.repo.blockstore :as blockstore])
  (:import [java.sql Connection]))

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

;; SqlRepoStore satisfies both blockstore protocols; the same value is
;; handed out by read-actor (reads only) and transact-actor! (inside a
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
