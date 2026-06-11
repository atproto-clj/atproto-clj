(ns atproto.repo.sync-test
  "Sync v1.1 verification tests, ported from packages/repo/tests/sync.test.ts
  and proofs.test.ts in the TypeScript reference implementation.

  These use real ES256K keypairs, so they are JVM-only until crypto
  lands on ClojureScript."
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            [atproto.data :as data]
            [atproto.data.cbor :as cbor]
            #?(:clj [atproto.crypto :as crypto])
            [atproto.tid :as tid]
            [atproto.repo :as repo]
            [atproto.repo.blockstore :as blockstore]
            [atproto.repo.car :as car]
            [atproto.repo.sync :as sync]
            [atproto.repo.test-support.util :as util]))

(def repo-did "did:example:test")

#?(:clj
   (defn- test-env
     "{:storage .. :keypair .. :repo .. :data ..} with a filled repo."
     [items-per-collection]
     (let [storage (blockstore/memory-blockstore)
           keypair (util/ok! (util/result-of (crypto/generate "ES256K")))
           created (util/ok! (util/result-of (repo/create storage repo-did keypair)))
           {:keys [repo data]} (util/fill-repo created keypair items-per-collection)]
       {:storage storage :keypair keypair :repo repo :data data})))

#?(:clj
   (deftest test-sync-full-repo
     (let [{:keys [storage keypair repo data]} (test-env 20)
           car-bytes (util/ok! (sync/repo->car storage (:cid repo)))]

       (testing "verifies and rebuilds a full repo export"
         (let [verified (util/ok! (util/result-of
                                   (sync/verify-repo-car car-bytes
                                                         :did repo-did
                                                         :signing-key (crypto/did keypair))))
               sync-storage (blockstore/memory-blockstore)]
           (blockstore/apply-commit! sync-storage (:commit verified))
           (let [loaded (util/ok! (repo/load sync-storage (:cid repo)))]
             (is (= data (repo/contents loaded))))

           (testing "creates carry the record CIDs"
             (let [{:keys [block-map]} (car/read-car-with-root car-bytes)
                   contents-from-ops
                   (reduce (fn [acc {:keys [action collection rkey cid]}]
                             (is (= :create action))
                             (assoc-in acc [collection rkey]
                                       (cbor/decode (get block-map cid))))
                           {}
                           (:creates verified))]
               (is (= data contents-from-ops)))))))))

#?(:clj
   (deftest test-no-duplicate-blocks
     (let [{:keys [storage repo]} (test-env 10)
           car-bytes (util/ok! (sync/repo->car storage (:cid repo)))
           {:keys [blocks] :as parsed} (car/read-car car-bytes)]
       (is (nil? (:error parsed)))
       (is (= (count blocks) (count (set (map :cid blocks))))))))

#?(:clj
   (deftest test-sync-repo-that-is-behind
     (let [{:keys [storage keypair repo data]} (test-env 20)
           ;; add more to the provider's repo & have the consumer catch up
           {:keys [commit data]} (util/format-edit repo data keypair
                                                   {:adds 10 :updates 10 :deletes 10})
           verified (util/ok! (util/result-of
                               (sync/verify-diff repo
                                                 (:new-blocks commit)
                                                 (:cid commit)
                                                 :did repo-did
                                                 :signing-key (crypto/did keypair))))]
       (is (= (+ 20 20 20) (count (:writes verified))))
       (blockstore/apply-commit! storage (:commit verified))
       (let [updated (util/ok! (repo/load storage (:cid (:commit verified))))]
         (is (= data (repo/contents updated)))))))

#?(:clj
   (deftest test-rejects-bad-signature
     (let [{:keys [storage keypair repo]} (test-env 5)
           bad-repo (util/add-bad-commit repo keypair)
           car-bytes (util/ok! (sync/repo->car storage (:cid bad-repo)))
           res (util/result-of
                (sync/verify-repo-car car-bytes
                                      :did repo-did
                                      :signing-key (crypto/did keypair)))]
       (is (= "RepoVerification" (:error res))))))

#?(:clj
   (deftest test-rejects-wrong-did
     (let [{:keys [storage keypair repo]} (test-env 5)
           car-bytes (util/ok! (sync/repo->car storage (:cid repo)))
           res (util/result-of
                (sync/verify-repo-car car-bytes
                                      :did "did:example:someone-else"
                                      :signing-key (crypto/did keypair)))]
       (is (= "RepoVerification" (:error res))))))

;; -----------------------------------------------------------------------------
;; Record proofs (proofs.test.ts)
;; -----------------------------------------------------------------------------

#?(:clj
   (defn- contents->claims
     [contents]
     (vec (for [[coll records] contents
                [rkey value] records]
            {:collection coll :rkey rkey :cid (data/cid-for value)}))))

#?(:clj
   (deftest test-record-proofs
     (let [{:keys [storage keypair repo data]} (test-env 5)
           did-key (crypto/did keypair)
           get-proofs (fn [claims]
                        (util/ok! (sync/records->car
                                   storage (:cid repo)
                                   (map #(select-keys % [:collection :rkey]) claims))))
           do-verify (fn [proofs claims]
                       (util/result-of
                        (sync/verify-proofs proofs claims repo-did did-key)))]

       (testing "verifies valid records"
         (let [claims (contents->claims data)
               results (util/ok! (do-verify (get-proofs claims) claims))]
           (is (pos? (count (:verified results))))
           (is (= claims (:verified results)))
           (is (= [] (:unverified results)))))

       (testing "verifies record nonexistence"
         (let [claims [{:collection (first util/test-collections)
                        :rkey (tid/next-tid) ;; does not exist
                        :cid nil}]
               results (util/ok! (do-verify (get-proofs claims) claims))]
           (is (= claims (:verified results)))
           (is (= [] (:unverified results)))))

       (testing "does not verify a record that does not exist"
         (let [real-claims (contents->claims data)
               claims [(assoc (first real-claims) :rkey (tid/next-tid))]
               results (util/ok! (do-verify (get-proofs claims) claims))]
           (is (= [] (:verified results)))
           (is (= claims (:unverified results)))))

       (testing "does not verify an invalid record at a real path"
         (let [real-claims (contents->claims data)
               claims [(assoc (first real-claims) :cid (util/random-cid))]
               results (util/ok! (do-verify (get-proofs claims) claims))]
           (is (= [] (:verified results)))
           (is (= claims (:unverified results)))))

       (testing "does not verify a delete where the record does exist"
         (let [real-claims (contents->claims data)
               claims [(assoc (first real-claims) :cid nil)]
               results (util/ok! (do-verify (get-proofs claims) claims))]
           (is (= [] (:verified results)))
           (is (= claims (:unverified results)))))

       (testing "determines record proofs from a car file"
         (let [possible (contents->claims data)
               claims [(nth possible 0) (nth possible 4)
                       (nth possible 5) (nth possible 8)]
               records (util/ok! (util/result-of
                                  (sync/verify-records (get-proofs claims)
                                                       repo-did did-key)))]
           (is (pos? (count records)))
           (doseq [record records]
             (let [claim (some #(when (and (= (:collection %) (:collection record))
                                           (= (:rkey %) (:rkey record)))
                                  %)
                               claims)]
               (is (some? claim) "every returned record matches a claim")
               (is (= (:cid claim) (:cid record)))
               (is (= (get-in data [(:collection record) (:rkey record)])
                      (:value record)))))))

       (testing "verify-proofs rejects a bad signature"
         (let [bad-repo (util/add-bad-commit repo keypair)
               claims (contents->claims data)
               proofs (util/ok! (sync/records->car
                                 storage (:cid bad-repo)
                                 (map #(select-keys % [:collection :rkey]) claims)))
               res (util/result-of
                    (sync/verify-proofs proofs claims repo-did did-key))]
           (is (= "RepoVerification" (:error res))))))))
