(ns atproto.repo-test
  "Repository layer tests, ported from packages/repo/tests/repo.test.ts
  and commit-data.test.ts in the TypeScript reference implementation.

  These use real ES256K keypairs, so they are JVM-only until crypto
  lands on ClojureScript."
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            [clojure.spec.alpha :as s]
            #?(:clj [atproto.crypto :as crypto])
            [atproto.runtime.bytes :as bytes]
            [atproto.tid :as tid]
            [atproto.repo :as repo]
            [atproto.repo.blockstore :as blockstore]
            [atproto.repo.car :as car]
            #?(:clj [atproto.repo.sync :as sync])
            [atproto.repo.test-support.util :as util]))

(deftest test-data-keys
  (is (= "com.example.posts/3jqfcqzm3fo2j"
         (repo/data-key "com.example.posts" "3jqfcqzm3fo2j")))
  (is (= {:collection "com.example.posts" :rkey "3jqfcqzm3fo2j"}
         (repo/parse-data-key "com.example.posts/3jqfcqzm3fo2j")))
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

#?(:clj
   (deftest test-repo-lifecycle
     (let [storage (blockstore/memory-blockstore)
           keypair (util/ok! (util/result-of (crypto/generate "ES256K")))
           coll "com.example.posts"
           repo0 (util/ok! (util/result-of
                            (repo/create storage (crypto/did keypair) keypair)))]

       (testing "creates repo with proper metadata"
         (is (= (crypto/did keypair) (:did (:commit repo0))))
         (is (= 3 (:version (:commit repo0))))
         (is (nil? (:prev (:commit repo0)))))

       (testing "does basic operations"
         (let [rkey (tid/next-tid)
               record (util/generate-object)
               created (util/ok!
                        (util/result-of
                         (repo/apply-writes repo0
                                            {:action :create
                                             :collection coll
                                             :rkey rkey
                                             :value record}
                                            keypair)))]
           (is (= record (:value (repo/get-record created coll rkey))))

           (let [updated-record (util/generate-object)
                 updated (util/ok!
                          (util/result-of
                           (repo/apply-writes created
                                              {:action :update
                                               :collection coll
                                               :rkey rkey
                                               :value updated-record}
                                              keypair)))]
             (is (= updated-record (:value (repo/get-record updated coll rkey))))

             (let [deleted (util/ok!
                            (util/result-of
                             (repo/apply-writes updated
                                                {:action :delete
                                                 :collection coll
                                                 :rkey rkey}
                                                keypair)))]
               (is (nil? (repo/get-record deleted coll rkey)))))))

       (testing "adds content collections, edits and deletes content"
         (let [{repo1 :repo data1 :data} (util/fill-repo repo0 keypair 100)]
           (is (= data1 (repo/contents repo1)))
           (let [{:keys [commit data]} (util/format-edit repo1 data1 keypair
                                                         {:adds 20 :updates 20 :deletes 20})
                 repo2 (util/ok! (repo/apply-commit repo1 commit))]
             (is (= data (repo/contents repo2)))

             (testing "has a valid signature to commit"
               (is (true? (util/result-of
                           (repo/verify-commit-sig (:commit repo2) (crypto/did keypair))))))

             (testing "rev increases monotonically"
               (is (pos? (compare (:rev (:commit repo2)) (:rev (:commit repo1))))))

             (testing "loads from blockstore"
               (let [reloaded (util/ok! (repo/load storage (:cid repo2)))]
                 (is (= data (repo/contents reloaded)))
                 (is (= (crypto/did keypair) (:did (:commit reloaded))))
                 (is (= 3 (:version (:commit reloaded))))))

             (testing "record-seq is ordered and complete"
               (let [records (vec (repo/record-seq repo2))]
                 (is (= (reduce + (map (comp count val) data))
                        (count records)))
                 (is (= (map (juxt :collection :rkey) records)
                        (sort (map (juxt :collection :rkey) records))))
                 (doseq [{:keys [collection rkey value]} (take 5 records)]
                   (is (= (get-in data [collection rkey]) value)))))))))))

#?(:clj
   (deftest test-format-commit-errors
     (let [storage (blockstore/memory-blockstore)
           keypair (util/ok! (util/result-of (crypto/generate "ES256K")))
           repo0 (util/ok! (util/result-of
                            (repo/create storage (crypto/did keypair) keypair)))
           coll "com.example.posts"
           rkey (tid/next-tid)
           repo1 (util/ok! (util/result-of
                            (repo/apply-writes repo0
                                               {:action :create
                                                :collection coll
                                                :rkey rkey
                                                :value {:a 1}}
                                               keypair)))]
       (is (= "KeyAlreadyExists"
              (:error (util/result-of
                       (repo/format-commit repo1
                                           {:action :create
                                            :collection coll
                                            :rkey rkey
                                            :value {:a 2}}
                                           keypair)))))
       (is (= "KeyNotFound"
              (:error (util/result-of
                       (repo/format-commit repo1
                                           {:action :update
                                            :collection coll
                                            :rkey (tid/next-tid)
                                            :value {:a 2}}
                                           keypair)))))
       (is (= "InvalidWriteOp"
              (:error (util/result-of
                       (repo/format-commit repo1
                                           {:action :create
                                            :collection coll
                                            :rkey rkey}
                                           keypair))))))))

;; Port of commit-data.test.ts: when deleting the first key there is a
;; "rearranged block" that is necessary in the proof path but is NOT in
;; :new-blocks (it already existed in the repository); :relevant-blocks
;; must include it.
#?(:clj
   (deftest test-commit-data-includes-relevant-blocks
     (let [did "did:example:alice"
           coll "com.atproto.test"
           record {:test 123}
           storage (blockstore/memory-blockstore)
           keypair (util/ok! (util/result-of (crypto/generate "ES256K")))
           keys (mapv #(str "key-" %) (range 50))
           repo0 (util/ok! (util/result-of (repo/create storage did keypair)))
           ;; deterministic tree: one write per commit
           filled (reduce
                   (fn [r rkey]
                     (util/ok! (util/result-of
                                (repo/apply-writes r
                                                   {:action :create
                                                    :collection coll
                                                    :rkey rkey
                                                    :value record}
                                                   keypair))))
                   repo0
                   keys)]

       (testing "new-blocks alone cannot prove the delete of the first key"
         (let [commit (util/ok! (util/result-of
                                 (repo/format-commit filled
                                                     {:action :delete
                                                      :collection coll
                                                      :rkey (first keys)}
                                                     keypair)))
               car-bytes (car/write-car (:cid commit) (map (fn [[cid bytes]]
                                                             {:cid cid :bytes bytes})
                                                           (:new-blocks commit)))
               res (util/result-of
                    (sync/verify-proofs car-bytes
                                        [{:collection coll :rkey (first keys) :cid nil}]
                                        did
                                        (crypto/did keypair)))]
           (is (= "MissingBlock" (:error res)))))

       (testing "relevant-blocks prove every delete"
         (reduce
          (fn [r rkey]
            (let [commit (util/ok! (util/result-of
                                    (repo/format-commit r
                                                        {:action :delete
                                                         :collection coll
                                                         :rkey rkey}
                                                        keypair)))
                  car-bytes (car/write-car (:cid commit)
                                           (map (fn [[cid bytes]]
                                                  {:cid cid :bytes bytes})
                                                (:relevant-blocks commit)))
                  res (util/ok! (util/result-of
                                 (sync/verify-proofs car-bytes
                                                     [{:collection coll :rkey rkey :cid nil}]
                                                     did
                                                     (crypto/did keypair))))]
              (is (= [] (:unverified res)) (str "unverified claim for " rkey))
              (is (= 1 (count (:verified res))))
              (util/ok! (repo/apply-commit r commit))))
          filled
          keys)))))
