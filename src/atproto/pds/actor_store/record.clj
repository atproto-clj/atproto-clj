(ns atproto.pds.actor-store.record
  "Record, backlink, and blob indexes inside an actor store.

  Synchronous; every fn takes the open SqlRepoStore handed to the
  read-actor / transact-actor! callback. Port of
  packages/pds/src/actor-store/record/transactor.ts (+reader)."
  (:require [clojure.spec.alpha :as s]
            [atproto.data :as data]
            [atproto.data.cbor :as cbor]
            [atproto.at-uri :as at-uri]
            [atproto.lexicon :as lexicon]
            [atproto.pds.sql :as sql]))

(set! *warn-on-reflection* true)

(defn backlinks
  "Backlink rows for a record value.

  The reference indexes `subject` backlinks for the bsky graph/feed
  collections (record/util.ts getBacklinks); this SDK carries no
  bsky-specific tables, so the same shapes are indexed for any
  collection: a top-level :subject that is a DID (path \"subject\") or a
  map with an at-uri :uri (path \"subject.uri\")."
  [uri record]
  (let [subject (when (map? record) (:subject record))]
    (cond
      (s/valid? ::lexicon/did subject)
      [{:uri uri :path "subject" :link-to subject}]

      (and (map? subject) (at-uri/valid? (:uri subject)))
      [{:uri uri :path "subject.uri" :link-to (:uri subject)}]

      :else [])))

(defn blob-cids
  "The distinct CIDs of every blob ref appearing in a record value."
  [record]
  (into []
        (comp (filter #(s/valid? ::data/blob %))
              (map :ref)
              (distinct))
        (tree-seq coll? #(if (map? %) (vals %) (seq %)) record)))

(defn index-record!
  "Insert or update the index row for a record, refreshing its backlinks
  and record_blob associations.

  {:uri at-uri-string :cid cid :record decoded-value :repo-rev rev}"
  [{:keys [conn]} {:keys [uri cid record repo-rev]}]
  (let [{:keys [collection rkey]} (at-uri/parse uri)]
    (when-not (and collection rkey)
      (throw (ex-info (str "Not a record at-uri: " uri)
                      {:error "InvalidRecordUri"
                       :message (str "Not a record at-uri: " uri)})))
    (sql/execute!
     conn
     [(str "INSERT INTO record (uri, cid, collection, rkey, repoRev, indexedAt) "
           "VALUES (?, ?, ?, ?, ?, ?) "
           "ON CONFLICT (uri) DO UPDATE SET "
           "cid = excluded.cid, repoRev = excluded.repoRev, indexedAt = excluded.indexedAt")
      uri (data/format-cid cid) collection rkey (or repo-rev "") (sql/now-iso)])
    (sql/execute! conn ["DELETE FROM backlink WHERE uri = ?" uri])
    (doseq [{:keys [path link-to]} (backlinks uri record)]
      (sql/execute!
       conn
       ["INSERT INTO backlink (uri, path, linkTo) VALUES (?, ?, ?)" uri path link-to]))
    (sql/execute! conn ["DELETE FROM record_blob WHERE recordUri = ?" uri])
    (doseq [blob-cid (blob-cids record)]
      (sql/execute!
       conn
       [(str "INSERT INTO record_blob (blobCid, recordUri) VALUES (?, ?) "
             "ON CONFLICT (blobCid, recordUri) DO NOTHING")
        (data/format-cid blob-cid) uri]))
    nil))

(defn delete-record!
  "Remove a record's index row, backlinks, and blob associations."
  [{:keys [conn]} uri]
  (sql/execute! conn ["DELETE FROM record WHERE uri = ?" uri])
  (sql/execute! conn ["DELETE FROM backlink WHERE uri = ?" uri])
  (sql/execute! conn ["DELETE FROM record_blob WHERE recordUri = ?" uri])
  nil)

(defn- row->record
  [row]
  {:uri (:uri row)
   :cid (data/parse-cid (:cid row))
   :value (some-> ^bytes (:content row) cbor/decode)
   :indexed-at (:indexedAt row)
   :takedown-ref (:takedownRef row)})

(defn get-record
  "The indexed record for uri:
  {:uri .. :cid .. :value .. :indexed-at .. :takedown-ref ..} or nil.
  Taken-down records return nil unless :include-takedowns? is set."
  [{:keys [conn]} uri & {:keys [include-takedowns?]}]
  (when-let [row (sql/execute-one!
                  conn
                  [(str "SELECT r.uri, r.cid, r.indexedAt, r.takedownRef, b.content "
                        "FROM record r LEFT JOIN repo_block b ON b.cid = r.cid "
                        "WHERE r.uri = ?")
                   uri])]
    (when (or include-takedowns? (nil? (:takedownRef row)))
      (row->record row))))

(defn list-records
  "Records in a collection ordered by rkey.

  opts: :collection (required), :limit (default 50), :cursor (rkey to
  resume after), :reverse? (descending rkey order when true).
  Returns {:records [...] :cursor rkey-or-nil} (cursor present only when
  the page is full)."
  [{:keys [conn]} {:keys [collection limit cursor reverse?]}]
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

(defn list-collections
  "Sorted collection NSIDs present in the record index."
  [{:keys [conn]}]
  (mapv :collection
        (sql/execute! conn ["SELECT DISTINCT collection FROM record ORDER BY collection"])))

(defn backlink-uris
  "URIs of records whose backlink at path points to link-to."
  [{:keys [conn]} path link-to]
  (mapv :uri
        (sql/execute!
         conn
         ["SELECT uri FROM backlink WHERE path = ? AND linkTo = ? ORDER BY uri"
          path link-to])))

;; -----------------------------------------------------------------------------
;; Blob metadata
;; -----------------------------------------------------------------------------

(defn register-blob!
  "Insert (or refresh) the metadata row for an uploaded blob."
  [{:keys [conn]} {:keys [cid mime-type size temp-key width height]}]
  (sql/execute!
   conn
   [(str "INSERT INTO blob (cid, mimeType, size, tempKey, width, height, createdAt) "
         "VALUES (?, ?, ?, ?, ?, ?, ?) "
         "ON CONFLICT (cid) DO UPDATE SET "
         "mimeType = excluded.mimeType, size = excluded.size, tempKey = excluded.tempKey")
    (data/format-cid cid) mime-type size temp-key width height (sql/now-iso)])
  nil)

(defn blob-metadata
  "The blob metadata row for cid, or nil."
  [{:keys [conn]} cid]
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

(defn record-uris-for-blob
  "URIs of indexed records referencing the blob cid."
  [{:keys [conn]} cid]
  (mapv :recordUri
        (sql/execute!
         conn
         ["SELECT recordUri FROM record_blob WHERE blobCid = ? ORDER BY recordUri"
          (data/format-cid cid)])))
