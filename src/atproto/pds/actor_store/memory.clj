(ns atproto.pds.actor-store.memory
  "In-memory ActorStorage (atom-backed) for tests and development.

  Satisfies the same contracts as the SQLite implementation: the
  reader/transactor handles implement the WS-04 blockstore protocols and
  the record-index protocol, transactions are atomic (writes land only
  when the callback returns normally), and readers reject writes.
  Transactions are serialized on a single lock; nothing survives the
  process."
  (:require [atproto.data :as data]
            [atproto.data.cbor :as cbor]
            [atproto.at-uri :as at-uri]
            [atproto.pds.sql :as sql]
            [atproto.pds.actor-store.storage :as storage]
            [atproto.pds.actor-store.record :as record]
            [atproto.repo.blockstore :as blockstore]))

(set! *warn-on-reflection* true)

(def ^:private empty-actor-data
  {:blocks {}        ;; cid -> bytes
   :root nil
   :rev nil
   :records {}       ;; uri -> {:cid :collection :rkey :repo-rev :indexed-at :takedown-ref}
   :backlinks {}     ;; uri -> [{:path .. :link-to ..}]
   :record-blobs {}  ;; uri -> #{cid-str}
   :blobs {}})       ;; cid-str -> metadata

(defn- writable!
  [{:keys [read-only?]}]
  (when read-only?
    (throw (ex-info "Writes are not allowed through a reader."
                    {:error "ReadOnly"
                     :message "Writes are not allowed through a reader."}))))

(defn- join-record
  "Assemble the get-record shape from an index row + the block map."
  [blocks uri {:keys [cid indexed-at takedown-ref]}]
  {:uri uri
   :cid cid
   :value (some-> (get blocks cid) cbor/decode)
   :indexed-at indexed-at
   :takedown-ref takedown-ref})

;; `data` is a volatile holding the working snapshot: mutations are
;; local to the handle until the transaction commits them.
(defrecord MemRepoStore [data did blobstore read-only?]
  blockstore/ReadableBlockstore
  (get-bytes [_ cid] (get-in @data [:blocks cid]))
  (has-block? [_ cid] (contains? (:blocks @data) cid))
  (get-blocks [_ cids]
    (let [blocks (:blocks @data)]
      (reduce (fn [res cid]
                (if-let [bytes (get blocks cid)]
                  (assoc-in res [:blocks cid] bytes)
                  (update res :missing conj cid)))
              {:blocks {} :missing []}
              cids)))

  blockstore/WritableBlockstore
  (get-root [_] (:root @data))
  (put-block! [this cid bytes]
    (writable! this)
    (vswap! data assoc-in [:blocks cid] bytes)
    nil)
  (put-blocks! [this block-map]
    (writable! this)
    (vswap! data update :blocks merge block-map)
    nil)
  (update-root! [this cid rev]
    (writable! this)
    (vswap! data assoc :root cid :rev rev)
    nil)
  (apply-commit! [this commit-data]
    (writable! this)
    (vswap! data
            (fn [st]
              (-> st
                  (assoc :root (:cid commit-data)
                         :rev (:rev commit-data))
                  (update :blocks #(merge (apply dissoc % (:removed-cids commit-data))
                                          (:new-blocks commit-data))))))
    nil)

  record/RecordIndex
  (index-record! [this {:keys [uri cid record repo-rev]}]
    (writable! this)
    (let [{:keys [collection rkey]} (record/parse-record-uri! uri)
          blob-strs (set (map data/format-cid (record/blob-cids record)))]
      (vswap! data
              (fn [st]
                (-> st
                    (update-in [:records uri]
                               (fn [old]
                                 {:cid cid
                                  :collection collection
                                  :rkey rkey
                                  :repo-rev (or repo-rev "")
                                  :indexed-at (sql/now-iso)
                                  :takedown-ref (:takedown-ref old)}))
                    (assoc-in [:backlinks uri] (record/backlinks uri record))
                    (assoc-in [:record-blobs uri] blob-strs)))))
    nil)
  (delete-record! [this uri]
    (writable! this)
    (vswap! data
            (fn [st]
              (-> st
                  (update :records dissoc uri)
                  (update :backlinks dissoc uri)
                  (update :record-blobs dissoc uri))))
    nil)
  (get-record [this uri] (record/get-record this uri {}))
  (get-record [_ uri {:keys [include-takedowns?]}]
    (let [{:keys [records blocks]} @data]
      (when-let [row (get records uri)]
        (when (or include-takedowns? (nil? (:takedown-ref row)))
          (join-record blocks uri row)))))
  (list-records [_ {:keys [collection limit cursor reverse?]}]
    (let [{:keys [records blocks]} @data
          limit (or limit 50)
          cmp (if reverse? #(compare %2 %1) compare)
          in-page? (fn [rkey]
                     (or (nil? cursor)
                         (if reverse? (neg? (compare rkey cursor))
                             (pos? (compare rkey cursor)))))
          rows (->> records
                    (filter (fn [[_ row]]
                              (and (= collection (:collection row))
                                   (nil? (:takedown-ref row))
                                   (in-page? (:rkey row)))))
                    (sort-by (comp :rkey val) cmp)
                    (take limit)
                    (mapv (fn [[uri row]] (join-record blocks uri row))))]
      (cond-> {:records rows}
        (= (count rows) limit)
        (assoc :cursor (at-uri/rkey (:uri (last rows)))))))
  (list-collections [_]
    (->> (vals (:records @data))
         (map :collection)
         distinct
         sort
         vec))
  (backlink-uris [_ path link-to]
    (->> (:backlinks @data)
         (filter (fn [[_ links]]
                   (some #(and (= path (:path %)) (= link-to (:link-to %))) links)))
         (map key)
         sort
         vec))
  (register-blob! [this {:keys [cid mime-type size temp-key width height]}]
    (writable! this)
    (vswap! data update-in [:blobs (data/format-cid cid)]
            (fn [old]
              {:cid cid
               :mime-type mime-type
               :size size
               :temp-key temp-key
               :width width
               :height height
               :created-at (or (:created-at old) (sql/now-iso))
               :takedown-ref (:takedown-ref old)}))
    nil)
  (blob-metadata [_ cid]
    (some-> (get-in @data [:blobs (data/format-cid cid)])
            (select-keys [:cid :mime-type :size :temp-key :created-at :takedown-ref])))
  (record-uris-for-blob [_ cid]
    (let [cid-str (data/format-cid cid)]
      (->> (:record-blobs @data)
           (filter (fn [[_ cids]] (contains? cids cid-str)))
           (map key)
           sort
           vec))))

(defn- not-found!
  [did]
  (throw (ex-info (str "No actor store for " did)
                  {:error "ActorNotFound"
                   :message (str "No actor store for " did)
                   :did did})))

(defrecord MemoryActorStorage [state blobstore-factory lock]
  storage/ActorStorage
  (actor-exists? [_ did]
    (contains? @state did))
  (create-actor! [_ did key-info]
    (let [[old _] (swap-vals! state
                              (fn [m]
                                (cond-> m
                                  (not (contains? m did))
                                  (assoc did {:key-info key-info
                                              :data empty-actor-data}))))]
      (when (contains? old did)
        (throw (ex-info (str "Actor store already exists for " did)
                        {:error "ActorAlreadyExists"
                         :message (str "Actor store already exists for " did)
                         :did did}))))
    nil)
  (read-key-info [_ did]
    (get-in @state [did :key-info]))
  (destroy-actor! [_ did]
    (swap! state dissoc did)
    nil)
  (with-reader [_ did f]
    (let [entry (get @state did)]
      (when-not entry (not-found! did))
      (f (->MemRepoStore (volatile! (:data entry)) did
                         (when blobstore-factory (blobstore-factory did))
                         true))))
  (with-transaction [_ did f]
    (locking lock
      (let [entry (get @state did)]
        (when-not entry (not-found! did))
        (let [working (volatile! (:data entry))
              ret (f (->MemRepoStore working did
                                     (when blobstore-factory (blobstore-factory did))
                                     false))]
          ;; commit only on normal return; a throw discards the snapshot
          (swap! state assoc-in [did :data] @working)
          ret)))))

(defn storage
  "An in-memory ActorStorage.

  config (optional):
    :blobstore-factory  optional (fn [did] -> atproto.pds.blobstore/BlobStore)"
  ([] (storage {}))
  ([{:keys [blobstore-factory]}]
   (->MemoryActorStorage (atom {}) blobstore-factory (Object.))))
