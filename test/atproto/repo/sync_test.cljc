(ns atproto.repo.sync-test
  "Sync v1.1 verification tests, ported from packages/repo/tests/sync.test.ts
  and proofs.test.ts in the TypeScript reference implementation.

  Verification over generated contents/edits/claims is property-based;
  the bad-signature and wrong-did rejections stay example-based. These
  use real ES256K keypairs, so they are JVM-only until crypto lands on
  ClojureScript."
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [atproto.data :as data]
            [atproto.data.cbor :as cbor]
            #?(:clj [atproto.crypto :as crypto])
            [atproto.repo :as repo]
            [atproto.repo.blockstore :as blockstore]
            [atproto.repo.car :as car]
            [atproto.repo.sync :as sync]
            [atproto.repo.test-support.gen :as tgen]
            [atproto.repo.test-support.util :as util]))

(def repo-did "did:example:test")

#?(:clj
   (def keypair
     (delay (util/ok! (util/result-of (crypto/generate "ES256K"))))))

#?(:clj
   (defn- make-repo
     "{:storage .. :repo ..} with the given contents committed."
     [contents]
     (let [storage (blockstore/memory-blockstore)
           created (util/ok! (util/result-of
                              (repo/create storage repo-did @keypair)))
           filled (util/ok! (util/result-of
                             (repo/apply-writes
                              created
                              (tgen/contents->create-writes contents)
                              @keypair)))]
       {:storage storage :repo filled})))

;; -----------------------------------------------------------------------------
;; Full repo sync
;; -----------------------------------------------------------------------------

#?(:clj
   (defspec full-repo-export-verifies-and-rebuilds 15
     (prop/for-all [contents tgen/gen-contents]
       (let [{:keys [storage repo]} (make-repo contents)
             car-bytes (util/ok! (sync/repo->car storage (:cid repo)))
             verified (util/ok! (util/result-of
                                 (sync/verify-repo-car
                                  car-bytes
                                  :did repo-did
                                  :signing-key (crypto/did @keypair))))
             {:keys [blocks block-map]} (car/read-car-with-root car-bytes)
             sync-storage (blockstore/memory-blockstore)]
         (blockstore/apply-commit! sync-storage (:commit verified))
         (and ;; the export contains no duplicate blocks
          (= (count blocks) (count (set (map :cid blocks))))
          ;; rebuilding from the verified commit yields the same contents
          (= contents (util/ok! (repo/contents
                                 (util/ok! (repo/load sync-storage (:cid repo))))))
          ;; every record is reported as a create whose cid resolves to it
          (= (count (tgen/contents->create-writes contents))
             (count (:creates verified)))
          (every? (fn [{:keys [action collection rkey cid]}]
                    (and (= :create action)
                         (= (get-in contents [collection rkey])
                            (cbor/decode (get block-map cid)))))
                  (:creates verified)))))))

;; -----------------------------------------------------------------------------
;; Diff sync
;; -----------------------------------------------------------------------------

#?(:clj
   (defspec diff-sync-catches-a-repo-up 15
     (prop/for-all [[contents {:keys [writes data]}]
                    (gen/let [contents tgen/gen-contents
                              edit (tgen/gen-edit contents)]
                      [contents edit])]
       (let [{:keys [storage repo]} (make-repo contents)
             commit (util/ok! (util/result-of
                               (repo/format-commit repo writes @keypair)))
             verified (util/ok! (util/result-of
                                 (sync/verify-diff repo
                                                   (:new-blocks commit)
                                                   (:cid commit)
                                                   :did repo-did
                                                   :signing-key (crypto/did @keypair))))]
         (blockstore/apply-commit! storage (:commit verified))
         (and ;; the verified writes are exactly the content-level diff
          (= (tgen/expected-write-descripts contents data)
             (set (map (juxt :action :collection :rkey) (:writes verified))))
          ;; create/update descripts carry the cid of the new record
          (every? (fn [{:keys [action collection rkey cid]}]
                    (or (= :delete action)
                        (= (data/cid-for (get-in data [collection rkey])) cid)))
                  (:writes verified))
          ;; commit-data bookkeeping points back at the known repo
          (= (:cid repo) (:prev (:commit verified)))
          (= (:rev (:commit repo)) (:since (:commit verified)))
          ;; applying the verified commit catches the storage up
          (= data (util/ok! (repo/contents
                             (util/ok! (repo/load storage
                                                  (:cid (:commit verified))))))))))))

;; -----------------------------------------------------------------------------
;; Record proofs
;; -----------------------------------------------------------------------------

#?(:clj
   (def gen-contents-and-claims
     "[contents claims tampered-claim absent-claim]: claims are a subset of
     true record claims; tampered-claim has a wrong cid; absent-claim
     claims absence of a record that does not exist."
     (gen/let [contents tgen/gen-contents
               all-paths (gen/return
                          (vec (for [[collection records] contents
                                     [rkey record] records]
                                 {:collection collection
                                  :rkey rkey
                                  :cid (data/cid-for record)})))
               claims (gen/not-empty (gen/vector (gen/elements all-paths) 1 6))
               wrong-cid tgen/gen-cid
               absent-rkey (gen/such-that
                            (fn [rk]
                              (not-any? #(= rk (:rkey %)) all-paths))
                            tgen/gen-rkey
                            100)]
       [contents
        (vec (distinct claims))
        (assoc (first claims) :cid wrong-cid)
        {:collection (:collection (first claims)) :rkey absent-rkey :cid nil}])))

#?(:clj
   (defspec record-proofs-verify-true-claims-and-reject-false-ones 15
     (prop/for-all [[contents claims tampered absent] gen-contents-and-claims]
       (let [{:keys [storage repo]} (make-repo contents)
             did-key (crypto/did @keypair)
             proofs-for (fn [claims]
                          (util/ok! (sync/records->car
                                     storage (:cid repo)
                                     (map #(select-keys % [:collection :rkey]) claims))))
             verify (fn [proofs claims]
                      (util/ok! (util/result-of
                                 (sync/verify-proofs proofs claims repo-did did-key))))]
         (and ;; true claims all verify
          (= {:verified claims :unverified []}
             (verify (proofs-for claims) claims))
          ;; a claim with the wrong cid does not verify
          (= {:verified [] :unverified [tampered]}
             (verify (proofs-for [tampered]) [tampered]))
          ;; absence of a record that does not exist verifies
          (= {:verified [absent] :unverified []}
             (verify (proofs-for [absent]) [absent]))
          ;; claiming absence of a record that DOES exist does not verify
          (let [absence-of-real (assoc (first claims) :cid nil)]
            (= {:verified [] :unverified [absence-of-real]}
               (verify (proofs-for [absence-of-real]) [absence-of-real])))
          ;; verify-records returns exactly the records reachable in the car
          (let [records (util/ok! (util/result-of
                                   (sync/verify-records (proofs-for claims)
                                                        repo-did did-key)))]
            (every? (fn [{:keys [collection rkey cid value]}]
                      (and (= (get-in contents [collection rkey]) value)
                           (= (data/cid-for value) cid)))
                    records)))))))

;; -----------------------------------------------------------------------------
;; Rejections (example-based: specific failure modes)
;; -----------------------------------------------------------------------------

#?(:clj
   (defn- small-contents
     []
     {"com.example.posts"
      (into {} (map (fn [i] [(str "rkey-" i) {:name (str "record " i)}]))
            (range 4))}))

#?(:clj
   (deftest test-rejects-bad-signature
     (let [{:keys [storage repo]} (make-repo (small-contents))
           bad-repo (util/add-bad-commit repo @keypair)
           car-bytes (util/ok! (sync/repo->car storage (:cid bad-repo)))]
       (is (= "RepoVerification"
              (:error (util/result-of
                       (sync/verify-repo-car car-bytes
                                             :did repo-did
                                             :signing-key (crypto/did @keypair))))))
       (testing "verify-proofs also rejects it"
         (let [proofs (util/ok! (sync/records->car
                                 storage (:cid bad-repo)
                                 [{:collection "com.example.posts" :rkey "rkey-0"}]))]
           (is (= "RepoVerification"
                  (:error (util/result-of
                           (sync/verify-proofs proofs
                                               [{:collection "com.example.posts"
                                                 :rkey "rkey-0"
                                                 :cid nil}]
                                               repo-did
                                               (crypto/did @keypair)))))))))))

#?(:clj
   (deftest test-rejects-wrong-did
     (let [{:keys [storage repo]} (make-repo (small-contents))
           car-bytes (util/ok! (sync/repo->car storage (:cid repo)))]
       (is (= "RepoVerification"
              (:error (util/result-of
                       (sync/verify-repo-car car-bytes
                                             :did "did:example:someone-else"
                                             :signing-key (crypto/did @keypair)))))))))
