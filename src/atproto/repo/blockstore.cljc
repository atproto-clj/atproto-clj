(ns atproto.repo.blockstore
  "Content-addressed block storage for atproto repositories.

  A \"block-map\" is a plain persistent map of CID -> byte-array; a
  \"cid-set\" is a plain persistent set of CIDs (multiformats CIDs
  implement equality/hashing).

  The protocols below are the storage seam durable implementations
  plug into; this namespace ships an in-memory implementation and a
  read-through overlay (port of packages/repo/src/storage in the
  TypeScript reference implementation)."
  (:require [clojure.spec.alpha :as s]
            [atproto.data :as data]
            [atproto.data.cbor :as cbor]))

#?(:clj (set! *warn-on-reflection* true))

(defprotocol ReadableBlockstore
  (get-bytes [bs cid]
    "Raw block bytes for cid, or nil if not present.")
  (has-block? [bs cid]
    "Whether a block exists for cid.")
  (get-blocks [bs cids]
    "Batch fetch. Returns {:blocks block-map, :missing [cid ...]}."))

(defprotocol WritableBlockstore
  "Mutable repo storage. In-memory impl here; durable impls are separate."
  (get-root [bs]
    "CID of the current root commit, or nil.")
  (put-block! [bs cid bytes])
  (put-blocks! [bs block-map])
  (update-root! [bs cid rev])
  (apply-commit! [bs commit-data]
    "Atomically delete :removed-cids, add :new-blocks, set root to :cid.
    See packages/repo/src/storage/memory-blockstore.ts:52-61."))

(defn memory-blockstore
  "In-memory blockstore (atom-backed). Optionally seeded with a block-map.
  Satisfies ReadableBlockstore and WritableBlockstore."
  ([] (memory-blockstore {}))
  ([block-map]
   (let [state (atom {:blocks (or block-map {}) :root nil :rev nil})]
     (reify
       ReadableBlockstore
       (get-bytes [_ cid] (get-in @state [:blocks cid]))
       (has-block? [_ cid] (contains? (:blocks @state) cid))
       (get-blocks [_ cids]
         (let [blocks (:blocks @state)]
           (reduce (fn [res cid]
                     (if-let [bytes (get blocks cid)]
                       (assoc-in res [:blocks cid] bytes)
                       (update res :missing conj cid)))
                   {:blocks {} :missing []}
                   cids)))
       WritableBlockstore
       (get-root [_] (:root @state))
       (put-block! [_ cid bytes] (swap! state assoc-in [:blocks cid] bytes) nil)
       (put-blocks! [_ block-map] (swap! state update :blocks merge block-map) nil)
       (update-root! [_ cid rev] (swap! state assoc :root cid :rev rev) nil)
       (apply-commit! [_ commit-data]
         (swap! state
                (fn [st]
                  (-> st
                      (assoc :root (:cid commit-data)
                             :rev (:rev commit-data))
                      (update :blocks #(merge (apply dissoc % (:removed-cids commit-data))
                                              (:new-blocks commit-data))))))
         nil)))))

(defn overlay
  "Read-only blockstore consulting `staged` then `saved` (port of SyncStorage)."
  [staged saved]
  (reify
    ReadableBlockstore
    (get-bytes [_ cid]
      (or (get-bytes staged cid)
          (get-bytes saved cid)))
    (has-block? [_ cid]
      (or (has-block? staged cid)
          (has-block? saved cid)))
    (get-blocks [_ cids]
      (let [from-staged (get-blocks staged cids)
            from-saved (get-blocks saved (:missing from-staged))]
        {:blocks (merge (:blocks from-staged) (:blocks from-saved))
         :missing (:missing from-saved)}))))

(defn read-block
  "Fetch the block at `cid` and DAG-CBOR-decode it. With `spec`, also
  validate the decoded data.

  Returns {:data x :bytes bytes} or
  {:error \"MissingBlock\" :cid cid} /
  {:error \"InvalidBlock\" :cid cid :message ...}."
  ([bs cid] (read-block bs cid nil))
  ([bs cid spec]
   (let [bytes (get-bytes bs cid)]
     (if (nil? bytes)
       {:error "MissingBlock"
        :message (str "Block not found: " (data/format-cid cid))
        :cid cid}
       (let [[ok data] (try
                         [true (cbor/decode bytes)]
                         (catch #?(:clj Exception :cljs :default) e
                           [false (ex-message e)]))]
         (cond
           (not ok)
           {:error "InvalidBlock"
            :message (str "Could not decode block "
                          (data/format-cid cid) ": " data)
            :cid cid}

           (and spec (not (s/valid? spec data)))
           {:error "InvalidBlock"
            :message (str "Block " (data/format-cid cid)
                          " does not match the expected shape.")
            :cid cid}

           :else {:data data :bytes bytes}))))))

(defn add-block
  "Encode `data` (DAG-CBOR), compute its CID, and assoc into block-map.
  Returns [cid block-map']."
  [block-map data]
  (let [bytes (cbor/encode data)
        cid (data/cid-link bytes)]
    [cid (assoc block-map cid bytes)]))
