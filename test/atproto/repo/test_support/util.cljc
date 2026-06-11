(ns atproto.repo.test-support.util
  "Helpers for the repo tests (async deref, error re-throw, blockstore
  persistence, and the bad-commit scenario from
  packages/repo/tests/_util.ts)."
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

(defn save-mst
  "Persist a tree's unstored blocks; returns the root CID."
  [storage tree]
  (let [{:keys [root blocks]} (ok! (mst/unstored-blocks tree))]
    (blockstore/put-blocks! storage blocks)
    root))

#?(:clj
   (defn random-bytes
     [n]
     (let [b (byte-array n)]
       (.nextBytes (java.security.SecureRandom.) b)
       b)))

#?(:clj
   (defn add-bad-commit
     "Apply a commit whose signature is over random bytes instead of the
     commit. Returns the new (bad) repo handle."
     [repo keypair]
     (let [[cid new-blocks] (blockstore/add-block {} {:name (random-str 100)})
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
