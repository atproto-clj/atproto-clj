(ns atproto.repo.test-support.util
  "Bulk key/record generators and repo-mutation helpers for the repo
  tests. Port of packages/repo/tests/_util.ts."
  (:require [atproto.data :as data]
            [atproto.data.cbor :as cbor]
            [atproto.tid :as tid]
            #?(:clj [atproto.crypto :as crypto])
            [atproto.repo :as repo]
            [atproto.repo.blockstore :as blockstore]
            [atproto.repo.mst :as mst]))

(defn result-of
  "Deref an async result (JVM promise) with a timeout."
  [async-val]
  #?(:clj (let [res (deref async-val 30000 ::timeout)]
            (if (= ::timeout res)
              (throw (ex-info "Timed out waiting for async result" {}))
              res))
     :cljs (throw (ex-info "Synchronous deref not supported on cljs" {}))))

(defn ok!
  "Throw if x is an SDK error map; otherwise return it."
  [x]
  (if (and (map? x) (:error x))
    (throw (ex-info (or (:message x) (:error x)) x))
    x))

(def ^:private rand-chars
  "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz")

(defn random-str
  [len]
  (apply str (repeatedly len #(rand-nth (vec rand-chars)))))

(defn random-cid
  "CID of a random small record; optionally stored in storage."
  ([] (random-cid nil))
  ([storage]
   (let [bytes (cbor/encode {:test (random-str 50)})
         cid (data/cid-link bytes)]
     (when storage
       (blockstore/put-block! storage cid bytes))
     cid)))

(defn generate-bulk-data-keys
  "{data-key cid} map of `count` random record keys."
  ([count] (generate-bulk-data-keys count nil))
  ([count storage]
   (into {}
         (map (fn [_]
                [(str "com.example.record/" (tid/next-tid))
                 (random-cid storage)]))
         (range count))))

(defn generate-object
  []
  {:name (random-str 100)})

(defn save-mst
  "Persist a tree's unstored blocks; returns the root CID."
  [storage tree]
  (let [{:keys [root blocks]} (ok! (mst/unstored-blocks tree))]
    (blockstore/put-blocks! storage blocks)
    root))

(def test-collections
  ["com.example.posts" "com.example.likes"])

#?(:clj
   (defn random-bytes
     [n]
     (let [b (byte-array n)]
       (.nextBytes (java.security.SecureRandom.) b)
       b)))

#?(:clj
   (defn fill-repo
     "Add items-per-collection random records to each test collection.
     Returns {:repo repo' :data repo-contents}."
     [repo keypair items-per-collection]
     (let [{:keys [writes data]}
           (reduce
            (fn [acc coll]
              (reduce
               (fn [{:keys [writes data]} _]
                 (let [rkey (tid/next-tid)
                       record (generate-object)]
                   {:writes (conj writes {:action :create
                                          :collection coll
                                          :rkey rkey
                                          :value record})
                    :data (assoc-in data [coll rkey] record)}))
               acc
               (range items-per-collection)))
            {:writes [] :data {}}
            test-collections)]
       {:repo (ok! (result-of (repo/apply-writes repo writes keypair)))
        :data data})))

#?(:clj
   (defn format-edit
     "Format (without applying) a commit with random adds/updates/deletes
     in each test collection. Returns {:commit commit-data :data contents}."
     [repo prev-data keypair {:keys [adds updates deletes]
                              :or {adds 0 updates 0 deletes 0}}]
     (let [{:keys [writes data]}
           (reduce
            (fn [acc coll]
              (let [shuffled (shuffle (vec (get prev-data coll {})))
                    to-update (subvec shuffled 0 updates)
                    to-delete (subvec shuffled updates (min (count shuffled)
                                                            (+ updates deletes)))]
                (as-> acc acc
                  ;; adds
                  (reduce (fn [{:keys [writes data]} _]
                            (let [rkey (tid/next-tid)
                                  record (generate-object)]
                              {:writes (conj writes {:action :create
                                                     :collection coll
                                                     :rkey rkey
                                                     :value record})
                               :data (assoc-in data [coll rkey] record)}))
                          acc
                          (range adds))
                  ;; updates
                  (reduce (fn [{:keys [writes data]} [rkey _]]
                            (let [record (generate-object)]
                              {:writes (conj writes {:action :update
                                                     :collection coll
                                                     :rkey rkey
                                                     :value record})
                               :data (assoc-in data [coll rkey] record)}))
                          acc
                          to-update)
                  ;; deletes
                  (reduce (fn [{:keys [writes data]} [rkey _]]
                            {:writes (conj writes {:action :delete
                                                   :collection coll
                                                   :rkey rkey})
                             :data (update data coll dissoc rkey)})
                          acc
                          to-delete))))
            {:writes [] :data prev-data}
            test-collections)]
       {:commit (ok! (result-of (repo/format-commit repo writes keypair)))
        :data data})))

#?(:clj
   (defn add-bad-commit
     "Apply a commit whose signature is over random bytes instead of the
     commit. Returns the new (bad) repo handle."
     [repo keypair]
     (let [[cid new-blocks] (blockstore/add-block {} (generate-object))
           tree (ok! (mst/add (:tree repo)
                              (str "com.example.test/" (tid/next-tid))
                              cid))
           data-cid (ok! (mst/pointer tree))
           d (ok! (mst/diff tree (:tree repo)))
           new-blocks (merge new-blocks (:new-mst-blocks d))
           rev (tid/next-tid)
           sig (ok! (result-of (crypto/sign keypair (random-bytes 256))))
           commit (assoc (:commit repo) :rev rev :data data-cid :sig sig)
           [commit-cid new-blocks] (blockstore/add-block new-blocks commit)]
       (blockstore/apply-commit! (:storage repo)
                                 {:cid commit-cid
                                  :rev rev
                                  :prev (:cid repo)
                                  :since (:rev (:commit repo))
                                  :new-blocks new-blocks
                                  :relevant-blocks new-blocks
                                  :removed-cids (:removed-cids d)})
       (ok! (repo/load (:storage repo) commit-cid)))))
