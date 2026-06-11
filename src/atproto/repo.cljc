(ns atproto.repo
  "Signed atproto data repositories: commits, record CRUD, proofs.

  A repo handle is a map {:storage bs, :commit commit, :cid commit-cid,
  :tree mst}; commits are maps {:did did, :version 3, :data mst-root-cid,
  :rev tid, :prev nil, :sig sig-bytes}.

  Pure computation is synchronous; entry points that sign or verify
  commits (create, format-commit, apply-writes, resign, sign-commit,
  verify-commit-sig) are async per the SDK callback convention because
  the crypto key operations are async. Failures are
  {:error \"Name\" :message ...} maps, never thrown.

  Note: this namespace implements the repository *data structure*
  (parity with the TypeScript `@atproto/repo` package). It does not make
  HTTP calls to the `com.atproto.repo.*` XRPC endpoints.

  See https://atproto.com/specs/repository"
  (:refer-clojure :exclude [load])
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [atproto.data :as data]
            [atproto.data.cbor :as cbor]
            [atproto.crypto :as crypto]
            [atproto.tid :as tid]
            [atproto.lexicon :as lexicon]
            [atproto.runtime.bytes :as bytes]
            [atproto.runtime.interceptor :as i]
            [atproto.repo.blockstore :as blockstore]
            [atproto.repo.mst :as mst]))

#?(:clj (set! *warn-on-reflection* true))

;; -----------------------------------------------------------------------------
;; Error plumbing
;; -----------------------------------------------------------------------------

(defn- err!
  [error message & [extra]]
  (throw (ex-info message (assoc extra :error error :message message))))

(defn- ok!
  "Re-throw an SDK error map returned by a sync call; pass values through."
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

(defn- guarded
  "Wrap an async callback so a thrown SDK error map is delivered instead
  of escaping the calling stack."
  [cb f]
  (fn [x]
    (let [res (try
                [::ok (f x)]
                (catch #?(:clj Exception :cljs :default) e
                  (let [d (ex-data e)]
                    (if (and (map? d) (:error d))
                      [::err d]
                      (throw e)))))]
      (when (= ::err (first res))
        (cb (second res))))))

;; -----------------------------------------------------------------------------
;; Commits
;; -----------------------------------------------------------------------------

(s/def ::did string?)
(s/def ::version #{3})
(s/def ::data data/cid-link?)
(s/def ::rev string?)
(s/def ::prev (s/nilable data/cid?))
(s/def ::sig bytes/bytes?)

(s/def ::unsigned-commit
  (s/keys :req-un [::did ::version ::data ::rev ::prev]))

(s/def ::commit
  (s/keys :req-un [::did ::version ::data ::rev ::prev ::sig]))

;; Legacy v2 commits (optional rev) are accepted on read and normalized.
(s/def :atproto.repo.v2/version #{2})
(s/def :atproto.repo.v2/rev (s/nilable string?))
(s/def ::legacy-v2-commit
  (s/keys :req-un [::did :atproto.repo.v2/version ::data ::prev ::sig]
          :opt-un [:atproto.repo.v2/rev]))

(s/def ::versioned-commit
  (s/or :v3 ::commit :v2 ::legacy-v2-commit))

(defn ensure-v3
  "Normalize a (possibly legacy v2) commit to v3.
  Port of packages/repo/src/util.ts:115-125."
  [commit]
  (if (= 3 (:version commit))
    commit
    (-> commit
        (assoc :version 3)
        (update :rev #(or % (tid/next-tid))))))

;; -----------------------------------------------------------------------------
;; Data keys & write ops
;; -----------------------------------------------------------------------------

(defn data-key
  "The MST key for a record: \"collection/rkey\"."
  [collection rkey]
  (str collection "/" rkey))

(defn parse-data-key
  "{:collection nsid :rkey rkey} or {:error \"InvalidDataKey\"}."
  [k]
  (let [parts (str/split k #"/" -1)]
    (if (= 2 (count parts))
      {:collection (nth parts 0) :rkey (nth parts 1)}
      {:error "InvalidDataKey"
       :message (str "Invalid record data key: " k)})))

(s/def ::action #{:create :update :delete})
(s/def ::collection ::lexicon/nsid)
(s/def ::rkey ::lexicon/record-key)
(s/def ::value ::data/value)

(s/def ::write-op
  (s/and (s/keys :req-un [::action ::collection ::rkey] :opt-un [::value])
         #(if (= :delete (:action %))
            (not (contains? % :value))
            (contains? % :value))))

;; -----------------------------------------------------------------------------
;; Revs
;; -----------------------------------------------------------------------------

(def ^:private s32-chars "234567abcdefghijklmnopqrstuvwxyz")

(defn- s32-decode
  [s]
  (reduce (fn [n c] (+ (* n 32) (str/index-of s32-chars c))) 0 s))

(defn- s32-encode
  [n]
  (loop [n n, s '()]
    (if (zero? n)
      (apply str (or (seq s) [\2]))
      (recur (quot n 32) (cons (nth s32-chars (mod n 32)) s)))))

(defn- next-rev
  "A TID strictly greater than prev (TS TID.nextStr(prev) parity)."
  [prev]
  (let [t (tid/next-tid)]
    (if (or (nil? prev) (pos? (compare t prev)))
      t
      ;; prev is ahead of our clock: bump its timestamp by one microsecond
      (let [ts (s32-decode (subs prev 0 (- (count prev) 2)))]
        (str (s32-encode (inc ts)) (subs prev (- (count prev) 2)))))))

;; -----------------------------------------------------------------------------
;; Commit signing & verification (async, crypto contract)
;; -----------------------------------------------------------------------------

(defn sign-commit
  "Sign the DAG-CBOR encoding of the unsigned commit with signing-key (a
  Keypair). Async; yields the commit with :sig bytes, or {:error ...}.
  Port of packages/repo/src/util.ts:82-92."
  [unsigned-commit signing-key & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (try
      (let [encoded (cbor/encode unsigned-commit)]
        (crypto/sign signing-key encoded
                     :callback (fn [sig]
                                 (cb (if (and (map? sig) (:error sig))
                                       sig
                                       (assoc unsigned-commit :sig sig))))))
      (catch #?(:clj Exception :cljs :default) e
        (let [d (ex-data e)]
          (if (and (map? d) (:error d))
            (cb d)
            (throw e)))))
    val))

(defn verify-commit-sig
  "Verify the commit's :sig against did-key (\"did:key:z...\") over the
  DAG-CBOR of the commit minus :sig. Async; yields true/false, or
  {:error ...} for a malformed did:key.
  Port of packages/repo/src/util.ts:94-101."
  [commit did-key & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (try
      (let [encoded (cbor/encode (dissoc commit :sig))]
        (crypto/verify-did-sig did-key (:sig commit) encoded :callback cb))
      (catch #?(:clj Exception :cljs :default) e
        (let [d (ex-data e)]
          (if (and (map? d) (:error d))
            (cb d)
            (throw e)))))
    val))

;; -----------------------------------------------------------------------------
;; Loading & reading
;; -----------------------------------------------------------------------------

(defn load
  "Load a repo handle from storage at commit-cid (or the storage root).
  Returns the handle or {:error \"MissingBlock\"/\"InvalidCommit\"/...}."
  ([storage] (load storage nil))
  ([storage commit-cid]
   (attempt
    #(let [cid (or commit-cid (blockstore/get-root storage))]
       (when (nil? cid)
         (err! "InvalidCommit" "No commit CID provided and none in storage"))
       (let [{:keys [data]} (ok! (blockstore/read-block storage cid ::versioned-commit))
             commit (ensure-v3 data)]
         {:storage storage
          :commit commit
          :cid cid
          :tree (mst/load storage (:data commit))})))))

(defn get-record
  "{:cid cid :value data} for the record at collection/rkey, nil if
  absent, or {:error ...}."
  [repo collection rkey]
  (attempt
   #(when-let [cid (ok! (mst/get-value (:tree repo) (data-key collection rkey)))]
      (let [{:keys [data]} (ok! (blockstore/read-block (:storage repo) cid map?))]
        {:cid cid :value data}))))

(defn record-seq
  "Lazy ordered seq of {:collection c :rkey r :cid cid :value data}.
  May throw ex-info {:error \"MissingBlock\"} during realization.

  opts:
    :from  start data key (\"collection/rkey\"), inclusive"
  [repo & {:keys [from]}]
  (map (fn [{:keys [key value]}]
         (let [{:keys [collection rkey]} (ok! (parse-data-key key))]
           {:collection collection
            :rkey rkey
            :cid value
            :value (:data (ok! (blockstore/read-block (:storage repo) value map?)))}))
       (mst/leaf-seq (:tree repo) (or from ""))))

(defn contents
  "{collection {rkey value}} for the whole repo, or {:error ...}."
  [repo]
  (attempt
   #(reduce (fn [acc {:keys [collection rkey value]}]
              (assoc-in acc [collection rkey] value))
            {}
            (record-seq repo))))

;; -----------------------------------------------------------------------------
;; Writes & commits
;; -----------------------------------------------------------------------------

(defn- apply-ops
  "Apply write ops to the tree without committing.
  Returns {:tree tree' :leaves block-map-of-written-records}."
  [tree writes]
  (reduce
   (fn [{:keys [tree leaves]} {:keys [action collection rkey value]}]
     (let [k (data-key collection rkey)]
       (case action
         :create (let [[cid leaves'] (blockstore/add-block leaves value)]
                   {:tree (ok! (mst/add tree k cid)) :leaves leaves'})
         :update (let [[cid leaves'] (blockstore/add-block leaves value)]
                   {:tree (ok! (mst/update-value tree k cid)) :leaves leaves'})
         :delete {:tree (ok! (mst/delete tree k)) :leaves leaves})))
   {:tree tree :leaves {}}
   writes))

(defn- validate-writes!
  [writes]
  (doseq [w writes]
    (when-not (s/valid? ::write-op w)
      (err! "InvalidWriteOp" (str "Invalid write op: " (s/explain-str ::write-op w))))))

(defn- select-leaves
  "Pick the given cids out of the leaves block-map; errors if any are
  absent (a diff reported a new leaf we did not just write)."
  [leaves cids]
  (reduce (fn [acc cid]
            (if-let [bytes (get leaves cid)]
              (assoc acc cid bytes)
              (err! "MissingBlock"
                    (str "Missing leaf block: " (data/format-cid cid))
                    {:cid cid})))
          {}
          cids))

(defn format-commit
  "Apply write ops to the MST (without committing), diff against the
  current tree, collect covering proofs for every written key, and sign
  the new commit.

  writes: a write op {:action :create|:update|:delete, :collection nsid,
  :rkey rkey, :value data} (:value absent for :delete) or a vector of
  them.

  Async; yields commit-data:
  {:cid cid :rev tid :since tid-or-nil :prev cid-or-nil
   :new-blocks block-map        ;; new MST nodes + new leaf records + commit
   :relevant-blocks block-map   ;; new-blocks + covering-proof blocks
   :removed-cids cid-set}
  or {:error \"KeyAlreadyExists\"/\"KeyNotFound\"/\"InvalidWriteOp\" ...}.
  Port of packages/repo/src/repo.ts:118-191."
  [repo writes signing-key & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (try
      (let [writes (if (map? writes) [writes] (vec writes))
            _ (validate-writes! writes)
            {tree' :tree leaves :leaves} (apply-ops (:tree repo) writes)
            data-cid (ok! (mst/pointer tree'))
            d (ok! (mst/diff tree' (:tree repo)))
            proofs (reduce (fn [acc {:keys [collection rkey]}]
                             (merge acc (ok! (mst/covering-proof
                                              tree'
                                              (data-key collection rkey)))))
                           {}
                           writes)
            added-leaves (select-leaves leaves (:new-leaf-cids d))
            new-blocks (merge (:new-mst-blocks d) added-leaves)
            relevant-blocks (merge proofs added-leaves)
            rev (next-rev (:rev (:commit repo)))
            unsigned {:did (:did (:commit repo))
                      :version 3
                      :rev rev
                      ;; kept (as null) for backwards compatibility with v2
                      :prev nil
                      :data data-cid}]
        (sign-commit
         unsigned signing-key
         :callback
         (guarded cb
                  (fn [commit]
                    (ok! commit)
                    (let [commit-bytes (cbor/encode commit)
                          commit-cid (data/cid-link commit-bytes)
                          changed? (not= commit-cid (:cid repo))
                          new-blocks (cond-> new-blocks
                                       changed? (assoc commit-cid commit-bytes))
                          relevant-blocks (cond-> relevant-blocks
                                            changed? (assoc commit-cid commit-bytes))
                          removed-cids (cond-> (:removed-cids d)
                                         changed? (conj (:cid repo)))]
                      (cb {:cid commit-cid
                           :rev rev
                           :since (:rev (:commit repo))
                           :prev (:cid repo)
                           :new-blocks new-blocks
                           ;; relevant-blocks ⊇ new-blocks (frozen contract)
                           :relevant-blocks (merge relevant-blocks new-blocks)
                           :removed-cids removed-cids}))))))
      (catch #?(:clj Exception :cljs :default) e
        (let [d (ex-data e)]
          (if (and (map? d) (:error d))
            (cb d)
            (throw e)))))
    val))

(defn apply-commit
  "apply-commit! the commit-data to storage and reload.
  Returns the new repo handle or {:error ...}."
  [repo commit-data]
  (attempt
   #(do (blockstore/apply-commit! (:storage repo) commit-data)
        (ok! (load (:storage repo) (:cid commit-data))))))

(defn apply-writes
  "format-commit + apply-commit. Async; yields the new repo handle or
  {:error ...}."
  [repo writes signing-key & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (format-commit repo writes signing-key
                   :callback (fn [commit-data]
                               (cb (if (:error commit-data)
                                     commit-data
                                     (apply-commit repo commit-data)))))
    val))

(defn- format-init-commit
  "Sync part of repo creation up to signing; calls cb with commit-data.
  Port of packages/repo/src/repo.ts:37-77."
  [storage did signing-key initial-writes cb]
  (let [writes (mapv #(assoc % :action :create) (or initial-writes []))
        _ (validate-writes! writes)
        {tree :tree leaves :leaves} (apply-ops (mst/create storage) writes)
        data-cid (ok! (mst/pointer tree))
        d (ok! (mst/diff tree nil))
        new-blocks (merge leaves (:new-mst-blocks d))
        rev (tid/next-tid)
        unsigned {:did did
                  :version 3
                  :rev rev
                  :prev nil
                  :data data-cid}]
    (sign-commit
     unsigned signing-key
     :callback
     (guarded cb
              (fn [commit]
                (ok! commit)
                (let [[commit-cid new-blocks] (blockstore/add-block new-blocks commit)]
                  (cb {:cid commit-cid
                       :rev rev
                       :since nil
                       :prev nil
                       :new-blocks new-blocks
                       :relevant-blocks new-blocks
                       :removed-cids (:removed-cids d)})))))))

(defn create
  "Initialize a repo: empty MST (plus optional initial create ops), a
  signed init commit (version 3, rev = next TID, prev nil), applied to
  storage.

  opts:
    :initial-writes  vector of create ops ({:collection .. :rkey .. :value ..})

  Async; yields the repo handle or {:error ...}."
  [storage did signing-key & {:keys [initial-writes] :as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (try
      (format-init-commit
       storage did signing-key initial-writes
       (fn [commit-data]
         (cb (if (:error commit-data)
               commit-data
               (attempt
                #(do (blockstore/apply-commit! storage commit-data)
                     (ok! (load storage (:cid commit-data)))))))))
      (catch #?(:clj Exception :cljs :default) e
        (let [d (ex-data e)]
          (if (and (map? d) (:error d))
            (cb d)
            (throw e)))))
    val))

(defn resign
  "Re-sign the current commit with a (possibly new) signing key under a
  new rev, without changing repo contents. Async; yields the new repo
  handle or {:error ...}. Port of packages/repo/src/repo.ts:206-233."
  [repo rev signing-key & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (sign-commit
     {:did (:did (:commit repo))
      :version 3
      :rev rev
      :prev nil
      :data (:data (:commit repo))}
     signing-key
     :callback
     (guarded cb
              (fn [commit]
                (ok! commit)
                (let [[commit-cid new-blocks] (blockstore/add-block {} commit)]
                  (cb (apply-commit repo {:cid commit-cid
                                          :rev rev
                                          :since nil
                                          :prev nil
                                          :new-blocks new-blocks
                                          :relevant-blocks new-blocks
                                          :removed-cids #{(:cid repo)}}))))))
    val))
