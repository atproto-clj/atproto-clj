(ns atproto.pds.actor-store.record
  "Record, backlink, and blob indexes inside an actor store.

  RecordIndex is part of the storage interface: every actor-storage
  reader/transactor (atproto.pds.actor-store.storage) satisfies it, and
  each implementation maintains its own index structures. This
  namespace also holds the *indexing semantics* shared by all
  implementations — which backlinks and blob refs a record value
  produces — as pure functions, so backends index identically.

  Port of packages/pds/src/actor-store/record/transactor.ts (+reader)."
  (:require [clojure.spec.alpha :as s]
            [atproto.data :as data]
            [atproto.at-uri :as at-uri]
            [atproto.lexicon :as lexicon]))

(set! *warn-on-reflection* true)

(defprotocol RecordIndex
  "Record/backlink/blob indexing operations on an actor-storage handle.
  Reads work on readers and transactors; writes only inside a
  transaction."
  (index-record! [store {:keys [uri cid record repo-rev]}]
    "Insert or update the index row for a record, refreshing its
    backlinks and record<->blob associations (derived via `backlinks`
    and `blob-cids`).")
  (delete-record! [store uri]
    "Remove a record's index row, backlinks, and blob associations.")
  (get-record [store uri] [store uri opts]
    "The indexed record joined with its block content:
    {:uri .. :cid .. :value .. :indexed-at .. :takedown-ref ..} or nil.
    Taken-down records return nil unless opts {:include-takedowns? true}.")
  (list-records [store {:keys [collection limit cursor reverse?]}]
    "Records in a collection ordered by rkey (default limit 50,
    :cursor resumes after an rkey, :reverse? descends). Returns
    {:records [...] :cursor rkey-or-nil} (cursor only on a full page).")
  (list-collections [store]
    "Sorted collection NSIDs present in the record index.")
  (backlink-uris [store path link-to]
    "URIs of records whose backlink at path points to link-to, sorted.")
  (register-blob! [store {:keys [cid mime-type size temp-key width height]}]
    "Insert (or refresh) the metadata row for an uploaded blob.")
  (blob-metadata [store cid]
    "The blob metadata row for cid
    ({:cid :mime-type :size :temp-key :created-at :takedown-ref}), or nil.")
  (record-uris-for-blob [store cid]
    "URIs of indexed records referencing the blob cid, sorted."))

;; -----------------------------------------------------------------------------
;; Indexing semantics (pure; shared by every storage implementation)
;; -----------------------------------------------------------------------------

(defn parse-record-uri!
  "{:collection .. :rkey ..} for a record at-uri; throws
  {:error \"InvalidRecordUri\"} for anything else."
  [uri]
  (let [{:keys [collection rkey] :as parsed} (at-uri/parse uri)]
    (if (and collection rkey)
      parsed
      (throw (ex-info (str "Not a record at-uri: " uri)
                      {:error "InvalidRecordUri"
                       :message (str "Not a record at-uri: " uri)})))))

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
