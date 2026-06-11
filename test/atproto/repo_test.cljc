(ns atproto.repo-test
  "Repository layer tests, ported from packages/repo/tests/repo.test.ts
  and commit-data.test.ts in the TypeScript reference implementation.

  Repo behavior over generated contents/edits is property-based; error
  shapes and the deterministic commit-data proof scenario stay
  example-based. These use real ES256K keypairs, so they are JVM-only
  until crypto lands on ClojureScript."
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            [clojure.spec.alpha :as s]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            #?(:clj [atproto.crypto :as crypto])
            [atproto.data :as data]
            [atproto.runtime.bytes :as bytes]
            [atproto.tid :as tid]
            [atproto.repo :as repo]
            [atproto.repo.blockstore :as blockstore]
            [atproto.repo.car :as car]
            #?(:clj [atproto.repo.sync :as sync])
            [atproto.repo.test-support.gen :as tgen]
            [atproto.repo.test-support.util :as util]))

#?(:clj
   (def keypair
     "One keypair for the whole suite; key generation is not what these
     tests exercise."
     (delay (util/ok! (util/result-of (crypto/generate "ES256K"))))))

;; -----------------------------------------------------------------------------
;; Data keys & commit plumbing
;; -----------------------------------------------------------------------------

(defspec data-key-round-trips 100
  (prop/for-all [collection tgen/gen-key-segment
                 rkey tgen/gen-key-segment]
    (= {:collection collection :rkey rkey}
       (repo/parse-data-key (repo/data-key collection rkey)))))

(deftest test-parse-data-key-errors
  (is (= "InvalidDataKey" (:error (repo/parse-data-key "no-slash"))))
  (is (= "InvalidDataKey" (:error (repo/parse-data-key "a/b/c")))))

(deftest test-ensure-v3
  (let [cid (util/random-cid)
        sig (bytes/utf8-bytes "sig")
        v3 {:did "did:example:alice" :version 3 :data cid
            :rev "3jqfcqzm3fo2j" :prev nil :sig sig}
        v2 {:did "did:example:alice" :version 2 :data cid :prev nil :sig sig}]
    (is (= v3 (repo/ensure-v3 v3)))
    (let [upgraded (repo/ensure-v3 v2)]
      (is (= 3 (:version upgraded)))
      (is (string? (:rev upgraded))))
    (let [upgraded (repo/ensure-v3 (assoc v2 :rev "3jqfcqzm3fo2j"))]
      (is (= 3 (:version upgraded)))
      (is (= "3jqfcqzm3fo2j" (:rev upgraded))))))

(deftest test-write-op-spec
  (is (s/valid? ::repo/write-op {:action :create
                                 :collection "com.example.posts"
                                 :rkey "3jqfcqzm3fo2j"
                                 :value {:text "hi"}}))
  (is (s/valid? ::repo/write-op {:action :delete
                                 :collection "com.example.posts"
                                 :rkey "3jqfcqzm3fo2j"}))
  (are [op] (not (s/valid? ::repo/write-op op))
    {:action :create :collection "com.example.posts" :rkey "3jqfcqzm3fo2j"}
    {:action :delete :collection "com.example.posts" :rkey "3jqfcqzm3fo2j" :value {}}
    {:action :upsert :collection "com.example.posts" :rkey "k" :value {}}
    {:action :create :collection "notansid" :rkey "3jqfcqzm3fo2j" :value {}}))

;; -----------------------------------------------------------------------------
;; Repo lifecycle properties
;; -----------------------------------------------------------------------------

#?(:clj
   (def gen-contents-and-edit
     (gen/let [contents tgen/gen-contents
               edit (tgen/gen-edit contents)]
       [contents edit])))

#?(:clj
   (defspec repo-writes-match-the-content-model 15
     (prop/for-all [[contents {:keys [writes data]}] gen-contents-and-edit]
       (let [storage (blockstore/memory-blockstore)
             did (crypto/did @keypair)
             created (util/ok! (util/result-of (repo/create storage did @keypair)))
             filled (util/ok! (util/result-of
                               (repo/apply-writes
                                created
                                (tgen/contents->create-writes contents)
                                @keypair)))
             ;; read before the edit: applying a commit deletes the
             ;; removed blocks, so superseded handles become unreadable
             filled-contents (util/ok! (repo/contents filled))
             edited (util/ok! (util/result-of
                               (repo/apply-writes filled writes @keypair)))]
         (and ;; commit metadata
          (= did (:did (:commit created)))
          (= 3 (:version (:commit created)))
          (nil? (:prev (:commit created)))
          ;; contents match the model after creates and after the edit
          (= contents filled-contents)
          (= data (util/ok! (repo/contents edited)))
          ;; revs increase monotonically
          (pos? (compare (:rev (:commit filled)) (:rev (:commit created))))
          (pos? (compare (:rev (:commit edited)) (:rev (:commit filled))))
          ;; every record is individually readable (or absent once deleted)
          (every? (fn [[collection records]]
                    (every? (fn [[rkey record]]
                              (= record (:value (util/ok! (repo/get-record
                                                           edited collection rkey)))))
                            records))
                  data)
          (every? (fn [{:keys [action collection rkey]}]
                    (or (not= :delete action)
                        (nil? (util/ok! (repo/get-record edited collection rkey)))))
                  writes)
          ;; record-seq is ordered by data key and complete
          (let [records (vec (repo/record-seq edited))]
            (and (= (reduce + (map (comp count val) data)) (count records))
                 (= (map (juxt :collection :rkey) records)
                    (sort (map (juxt :collection :rkey) records)))
                 (every? (fn [{:keys [collection rkey value]}]
                           (= (get-in data [collection rkey]) value))
                         records)))
          ;; the commit signature verifies
          (true? (util/result-of
                  (repo/verify-commit-sig (:commit edited) (crypto/did @keypair))))
          ;; reloading from storage yields the same repo
          (let [reloaded (util/ok! (repo/load storage (:cid edited)))]
            (and (= data (util/ok! (repo/contents reloaded)))
                 (= (:commit edited) (update (:commit reloaded) :sig
                                             (constantly (:sig (:commit edited)))))
                 (bytes/eq? (:sig (:commit edited)) (:sig (:commit reloaded))))))))))

#?(:clj
   (defspec format-commit-reports-blocks-consistently 15
     (prop/for-all [[contents {:keys [writes]}] gen-contents-and-edit]
       (let [storage (blockstore/memory-blockstore)
             did (crypto/did @keypair)
             created (util/ok! (util/result-of (repo/create storage did @keypair)))
             filled (util/ok! (util/result-of
                               (repo/apply-writes
                                created
                                (tgen/contents->create-writes contents)
                                @keypair)))
             commit (util/ok! (util/result-of
                               (repo/format-commit filled writes @keypair)))]
         (and ;; relevant-blocks ⊇ new-blocks (frozen contract)
          (every? (fn [[cid bytes]]
                    (bytes/eq? bytes (get (:relevant-blocks commit) cid)))
                  (:new-blocks commit))
          ;; rev/since/prev bookkeeping
          (pos? (compare (:rev commit) (:rev (:commit filled))))
          (= (:rev (:commit filled)) (:since commit))
          (= (:cid filled) (:prev commit))
          ;; applying (remove :removed-cids, then add :new-blocks) yields
          ;; the commit's cid as root with every new block present, even
          ;; when a cid appears on both sides
          (let [applied (util/ok! (repo/apply-commit filled commit))]
            (and (= (:cid commit) (:cid applied))
                 (every? #(blockstore/has-block? storage %)
                         (keys (:new-blocks commit))))))))))

;; -----------------------------------------------------------------------------
;; Error shapes
;; -----------------------------------------------------------------------------

#?(:clj
   (deftest test-format-commit-errors
     (let [storage (blockstore/memory-blockstore)
           repo0 (util/ok! (util/result-of
                            (repo/create storage (crypto/did @keypair) @keypair)))
           coll "com.example.posts"
           rkey (tid/next-tid)
           repo1 (util/ok! (util/result-of
                            (repo/apply-writes repo0
                                               {:action :create
                                                :collection coll
                                                :rkey rkey
                                                :value {:a 1}}
                                               @keypair)))]
       (is (= "KeyAlreadyExists"
              (:error (util/result-of
                       (repo/format-commit repo1
                                           {:action :create
                                            :collection coll
                                            :rkey rkey
                                            :value {:a 2}}
                                           @keypair)))))
       (is (= "KeyNotFound"
              (:error (util/result-of
                       (repo/format-commit repo1
                                           {:action :update
                                            :collection coll
                                            :rkey (tid/next-tid)
                                            :value {:a 2}}
                                           @keypair)))))
       (is (= "InvalidWriteOp"
              (:error (util/result-of
                       (repo/format-commit repo1
                                           {:action :create
                                            :collection coll
                                            :rkey rkey}
                                           @keypair))))))))

;; Two records with identical content share one block. The repo layer's
;; :removed-cids is structural (TS parity): updating one of the two
;; lists the shared cid as removed even though the other record still
;; references it. Storage layers that index records must filter such
;; cids before deleting blocks, as the reference PDS does
;; (packages/pds/src/actor-store/repo/transactor.ts:163-170). This test
;; pins that contract so a behavior change is a conscious decision.
#?(:clj
   (deftest test-shared-record-blocks-are-structurally-removed
     (let [storage (blockstore/memory-blockstore)
           coll "com.example.posts"
           shared {:name "same content"}
           shared-cid (data/cid-for shared)
           repo0 (util/ok! (util/result-of
                            (repo/create storage "did:example:alice" @keypair)))
           filled (util/ok! (util/result-of
                             (repo/apply-writes
                              repo0
                              [{:action :create :collection coll :rkey "a" :value shared}
                               {:action :create :collection coll :rkey "b" :value shared}]
                              @keypair)))
           commit (util/ok! (util/result-of
                             (repo/format-commit
                              filled
                              {:action :update :collection coll :rkey "a"
                               :value {:name "different now"}}
                              @keypair)))]
       (is (= shared-cid (:cid (repo/get-record filled coll "b"))))
       (is (contains? (:removed-cids commit) shared-cid)
           "the shared block is listed as removed even though b still references it"))))

;; Port of commit-data.test.ts: when deleting the first key there is a
;; "rearranged block" that is necessary in the proof path but is NOT in
;; :new-blocks (it already existed in the repository); :relevant-blocks
;; must include it. This scenario needs the reference's specific fully
;; deterministic tree, so it stays example-based.
#?(:clj
   (deftest test-commit-data-includes-relevant-blocks
     (let [did "did:example:alice"
           coll "com.atproto.test"
           record {:test 123}
           storage (blockstore/memory-blockstore)
           keys (mapv #(str "key-" %) (range 50))
           repo0 (util/ok! (util/result-of (repo/create storage did @keypair)))
           ;; deterministic tree: one write per commit
           filled (reduce
                   (fn [r rkey]
                     (util/ok! (util/result-of
                                (repo/apply-writes r
                                                   {:action :create
                                                    :collection coll
                                                    :rkey rkey
                                                    :value record}
                                                   @keypair))))
                   repo0
                   keys)]

       (testing "new-blocks alone cannot prove the delete of the first key"
         (let [commit (util/ok! (util/result-of
                                 (repo/format-commit filled
                                                     {:action :delete
                                                      :collection coll
                                                      :rkey (first keys)}
                                                     @keypair)))
               car-bytes (car/write-car (:cid commit) (map (fn [[cid bytes]]
                                                             {:cid cid :bytes bytes})
                                                           (:new-blocks commit)))
               res (util/result-of
                    (sync/verify-proofs car-bytes
                                        [{:collection coll :rkey (first keys) :cid nil}]
                                        did
                                        (crypto/did @keypair)))]
           (is (= "MissingBlock" (:error res)))))

       (testing "relevant-blocks prove every delete"
         (reduce
          (fn [r rkey]
            (let [commit (util/ok! (util/result-of
                                    (repo/format-commit r
                                                        {:action :delete
                                                         :collection coll
                                                         :rkey rkey}
                                                        @keypair)))
                  car-bytes (car/write-car (:cid commit)
                                           (map (fn [[cid bytes]]
                                                  {:cid cid :bytes bytes})
                                                (:relevant-blocks commit)))
                  res (util/ok! (util/result-of
                                 (sync/verify-proofs car-bytes
                                                     [{:collection coll :rkey rkey :cid nil}]
                                                     did
                                                     (crypto/did @keypair))))]
              (is (= [] (:unverified res)) (str "unverified claim for " rkey))
              (is (= 1 (count (:verified res))))
              (util/ok! (repo/apply-commit r commit))))
          filled
          keys)))))
