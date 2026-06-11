(ns atproto.repo.sync
  "Sync v1.1 verification of repository CARs, plus the provider-side
  CAR builders used to test them (and useful for PDS work).

  The verifiers are async (they verify commit signatures through the
  async crypto contract); the providers are synchronous. Failures are
  {:error \"Name\" :message ...} maps, never thrown.

  Port of packages/repo/src/sync/consumer.ts and provider.ts."
  (:require [atproto.data :as data]
            [atproto.runtime.interceptor :as i]
            [atproto.repo :as repo]
            [atproto.repo.blockstore :as blockstore]
            [atproto.repo.car :as car]
            [atproto.repo.mst :as mst]))

#?(:clj (set! *warn-on-reflection* true))

(defn- err!
  [error message & [extra]]
  (throw (ex-info message (assoc extra :error error :message message))))

(defn- ok!
  [x]
  (if (and (map? x) (:error x))
    (throw (ex-info (or (:message x) (:error x)) x))
    x))

(defn- attempt
  [f]
  (try
    (f)
    (catch #?(:clj Exception :cljs :default) e
      (let [d (ex-data e)]
        (if (and (map? d) (:error d))
          d
          (throw e))))))

(defn- async-opts
  [opts]
  (select-keys opts [:channel :callback :promise]))

(defn- deliver-attempt
  "Deliver (f) to cb, converting thrown SDK error maps."
  [cb f]
  (cb (attempt f)))

;; -----------------------------------------------------------------------------
;; Consumers
;; -----------------------------------------------------------------------------

(defn- check-sig-then
  "Verify the commit sig against did-key when one is given, then call
  continue (or deliver the error to cb)."
  [commit did-key cid cb continue]
  (if (nil? did-key)
    (continue)
    (repo/verify-commit-sig
     commit did-key
     :callback (fn [res]
                 (cond
                   (and (map? res) (:error res)) (cb res)
                   res (continue)
                   :else (cb {:error "RepoVerification"
                              :message (str "Invalid signature on commit: "
                                            (data/format-cid cid))}))))))

(defn- diff-writes
  "Write descripts from an MST diff.
  Port of packages/repo/src/util.ts:19-52."
  [d]
  (-> []
      (into (map (fn [{:keys [key cid]}]
                   (let [{:keys [collection rkey]} (ok! (repo/parse-data-key key))]
                     {:action :create :collection collection :rkey rkey :cid cid})))
            (vals (:adds d)))
      (into (map (fn [{:keys [key prev cid]}]
                   (let [{:keys [collection rkey]} (ok! (repo/parse-data-key key))]
                     {:action :update :collection collection :rkey rkey
                      :cid cid :prev prev})))
            (vals (:updates d)))
      (into (map (fn [{:keys [key cid]}]
                   (let [{:keys [collection rkey]} (ok! (repo/parse-data-key key))]
                     {:action :delete :collection collection :rkey rkey :cid cid})))
            (vals (:deletes d)))))

(defn- finish-diff
  "Sync tail of verify-diff once the root commit has been verified.
  Port of packages/repo/src/sync/consumer.ts:56-105."
  [known-repo updated block-map ensure-leaves?]
  (let [d (ok! (mst/diff (:tree updated) (:tree known-repo)))
        writes (diff-writes d)
        leaves (reduce (fn [acc cid]
                         (if-let [bytes (get block-map cid)]
                           (assoc acc cid bytes)
                           (if ensure-leaves?
                             (err! "MissingBlock"
                                   (str "Missing leaf block: " (data/format-cid cid))
                                   {:cid cid})
                             acc)))
                       {}
                       (:new-leaf-cids d))
        new-blocks (merge (:new-mst-blocks d) leaves)
        [commit-cid new-blocks] (blockstore/add-block new-blocks (:commit updated))
        same-commit? (and known-repo (= commit-cid (:cid known-repo)))
        new-blocks (cond-> new-blocks
                     same-commit? (dissoc commit-cid))
        removed-cids (cond-> (:removed-cids d)
                       (and known-repo (not same-commit?)) (conj (:cid known-repo)))]
    {:writes writes
     :commit {:cid (:cid updated)
              :rev (:rev (:commit updated))
              :prev (:cid known-repo)
              :since (:rev (:commit known-repo))
              :new-blocks new-blocks
              :relevant-blocks new-blocks
              :removed-cids removed-cids}}))

(defn- verify-diff*
  [known-repo block-map root did signing-key ensure-leaves? cb]
  (try
    (let [staged (blockstore/memory-blockstore block-map)
          storage (if known-repo
                    (blockstore/overlay staged (:storage known-repo))
                    staged)
          updated (repo/load storage root)]
      (cond
        (:error updated)
        (cb (if (= "InvalidBlock" (:error updated))
              {:error "RepoVerification"
               :message (:message updated)}
              updated))

        (and did (not= did (:did (:commit updated))))
        (cb {:error "RepoVerification"
             :message (str "Invalid repo did: " (:did (:commit updated)))})

        :else
        (check-sig-then
         (:commit updated) signing-key root cb
         (fn []
           (deliver-attempt
            cb #(finish-diff known-repo updated block-map ensure-leaves?))))))
    (catch #?(:clj Exception :cljs :default) e
      (let [d (ex-data e)]
        (if (and (map? d) (:error d))
          (cb d)
          (throw e))))))

(defn verify-diff
  "Verify an update CAR's blocks against a known repo handle (which may
  be nil): staged blocks overlay the repo storage, the new root commit
  is checked (optional :did match and :signing-key signature), and the
  new MST is diffed against the old.

  Async; yields {:writes [write-descript ...] :commit commit-data} or
  {:error \"RepoVerification\"/\"MissingBlock\"/... ...}.
  write-descript = {:action :create|:update|:delete :collection c
                    :rkey r :cid cid (& :prev cid on :update)}."
  [known-repo block-map root & {:keys [did signing-key ensure-leaves?]
                                :or {ensure-leaves? true}
                                :as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (verify-diff* known-repo block-map root did signing-key ensure-leaves? cb)
    val))

(defn verify-diff-car
  "read-car-with-root + verify-diff. Async."
  [known-repo car-bytes & {:as opts}]
  (let [{:keys [root block-map] :as parsed} (car/read-car-with-root car-bytes)]
    (if (:error parsed)
      (let [[cb val] (i/platform-async (async-opts opts))]
        (cb parsed)
        val)
      (verify-diff known-repo block-map root opts))))

(defn verify-repo
  "Verify a full repo export (block-map + commit root). Checks the
  commit shape, optional :did match, optional :signing-key (did:key)
  signature, then walks commit -> MST -> records, requiring every write
  to be a create (and all leaves present unless :ensure-leaves? false).

  Async; yields
  {:creates [{:action :create :collection c :rkey r :cid cid} ...]
   :commit commit-data}
  or {:error \"RepoVerification\" ...} / {:error \"MissingBlock\" ...}."
  [block-map root & {:keys [did signing-key ensure-leaves?]
                     :or {ensure-leaves? true}
                     :as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (verify-diff*
     nil block-map root did signing-key ensure-leaves?
     (fn [res]
       (cb (if (:error res)
             res
             (if-let [bad (some #(when (not= :create (:action %)) %)
                                (:writes res))]
               {:error "RepoVerification"
                :message (str "Unexpected action in full repo: "
                              (name (:action bad)))}
               {:creates (:writes res)
                :commit (:commit res)})))))
    val))

(defn verify-repo-car
  "read-car-with-root + verify-repo. Async."
  [car-bytes & {:as opts}]
  (let [{:keys [root block-map] :as parsed} (car/read-car-with-root car-bytes)]
    (if (:error parsed)
      (let [[cb val] (i/platform-async (async-opts opts))]
        (cb parsed)
        val)
      (verify-repo block-map root opts))))

(defn verify-proofs
  "Verify com.atproto.sync.getRecord-style proof CARs.

  claims: [{:collection c :rkey r :cid cid-or-nil} ...]
  (nil cid = claim of absence).

  Async; yields {:verified [claim ...] :unverified [claim ...]} or
  {:error \"RepoVerification\"/\"MissingBlock\"/\"InvalidCar\" ...}.
  Port of packages/repo/src/sync/consumer.ts:129-170."
  [car-bytes claims did did-key & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (try
      (let [{:keys [root block-map] :as parsed} (car/read-car-with-root car-bytes)]
        (if (:error parsed)
          (cb parsed)
          (let [bs (blockstore/memory-blockstore block-map)
                commit (:data (ok! (blockstore/read-block bs root ::repo/commit)))]
            (if (not= did (:did commit))
              (cb {:error "RepoVerification"
                   :message (str "Invalid repo did: " (:did commit))})
              (check-sig-then
               commit did-key root cb
               (fn []
                 (deliver-attempt
                  cb
                  #(let [tree (mst/load bs (:data commit))]
                     (reduce
                      (fn [res {:keys [collection rkey cid] :as claim}]
                        (let [found (ok! (mst/get-value
                                          tree (repo/data-key collection rkey)))
                              record (when found
                                       (:data (ok! (blockstore/read-block
                                                    bs found map?))))
                              verified? (if (nil? cid)
                                          (nil? record)
                                          (= found cid))]
                          (update res (if verified? :verified :unverified)
                                  conj claim)))
                      {:verified [] :unverified []}
                      claims)))))))))
      (catch #?(:clj Exception :cljs :default) e
        (let [d (ex-data e)]
          (if (and (map? d) (:error d))
            (cb d)
            (throw e)))))
    val))

(defn verify-records
  "All records reachable in a proof CAR, after did + signature checks.

  Async; yields [{:collection c :rkey r :cid cid :value data} ...] or
  {:error ...}. Port of packages/repo/src/sync/consumer.ts:172-205."
  [car-bytes did did-key & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (try
      (let [{:keys [root block-map] :as parsed} (car/read-car-with-root car-bytes)]
        (if (:error parsed)
          (cb parsed)
          (let [bs (blockstore/memory-blockstore block-map)
                commit (:data (ok! (blockstore/read-block bs root ::repo/commit)))]
            (if (not= did (:did commit))
              (cb {:error "RepoVerification"
                   :message (str "Invalid repo did: " (:did commit))})
              (check-sig-then
               commit did-key root cb
               (fn []
                 (deliver-attempt
                  cb
                  #(let [tree (mst/load bs (:data commit))]
                     (into []
                           (keep (fn [{:keys [key value]}]
                                   (let [{:keys [collection rkey]}
                                         (ok! (repo/parse-data-key key))
                                         block (blockstore/read-block bs value map?)]
                                     (when-not (:error block)
                                       {:collection collection
                                        :rkey rkey
                                        :cid value
                                        :value (:data block)}))))
                           (ok! (mst/reachable-leaf-seq tree)))))))))))
      (catch #?(:clj Exception :cljs :default) e
        (let [d (ex-data e)]
          (if (and (map? d) (:error d))
            (cb d)
            (throw e)))))
    val))

;; -----------------------------------------------------------------------------
;; Providers
;; -----------------------------------------------------------------------------

(defn- full-repo-blocks
  "Commit block followed by the MST block stream (nodes BFS, then leaves)."
  [storage commit-cid]
  (let [{:keys [data bytes]} (ok! (blockstore/read-block
                                   storage commit-cid ::repo/versioned-commit))]
    (cons {:cid commit-cid :bytes bytes}
          (mst/car-block-seq (mst/load storage (:data data))))))

(defn repo->car
  "Full repo export CAR bytes (commit block + MST nodes + leaf records),
  with the commit CID as the single root. Returns bytes or {:error ...}.
  The JVM 3-arity streams to an OutputStream instead.
  Port of packages/repo/src/sync/provider.ts:13-30."
  ([storage commit-cid]
   (attempt
    #(car/write-car commit-cid (full-repo-blocks storage commit-cid))))
  #?(:clj
     ([storage commit-cid ^java.io.OutputStream out]
      (attempt
       #(car/write-car-stream commit-cid (full-repo-blocks storage commit-cid) out)))))

(defn records->car
  "Proof CAR bytes for specific record paths
  [{:collection c :rkey r} ...]: the commit block plus every MST node on
  the path to each record (and the record blocks themselves). Returns
  bytes or {:error ...}.
  Port of packages/repo/src/sync/provider.ts:35-67."
  [storage commit-cid paths]
  (attempt
   #(let [{:keys [data bytes]} (ok! (blockstore/read-block
                                     storage commit-cid ::repo/versioned-commit))
          tree (mst/load storage (:data data))
          ;; ordered de-duplicated cids across all paths
          cids (first
                (reduce (fn [[order seen :as acc] cid]
                          (if (contains? seen cid)
                            acc
                            [(conj order cid) (conj seen cid)]))
                        [[] #{}]
                        (mapcat (fn [{:keys [collection rkey]}]
                                  (ok! (mst/cids-for-path
                                        tree (repo/data-key collection rkey))))
                                paths)))
          {:keys [blocks missing]} (blockstore/get-blocks storage cids)]
      (when (seq missing)
        (err! "MissingBlock" "Missing blocks for record proofs" {:cids missing}))
      (car/write-car commit-cid
                     (cons {:cid commit-cid :bytes bytes}
                           (map (fn [cid] {:cid cid :bytes (get blocks cid)})
                                cids))))))
