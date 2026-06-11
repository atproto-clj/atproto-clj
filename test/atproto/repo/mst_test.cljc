(ns atproto.repo.mst-test
  "MST unit + interop tests, ported from packages/repo/tests/mst.test.ts
  and commit-proofs.test.ts in the TypeScript reference implementation."
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            #?(:clj [clojure.java.io :as io])
            [atproto.data :as data]
            [atproto.runtime.json :as json]
            [atproto.repo.blockstore :as blockstore]
            [atproto.repo.mst :as mst]
            [atproto.repo.test-support.util :as util]))

(def cid1
  (data/parse-cid "bafyreie5cvv4h45feadgeuwhbcutmh6t2ceseocckahdoe6uat64zmz454"))

(defn- root-str
  [tree]
  (data/format-cid (util/ok! (mst/pointer tree))))

(defn- leaf-count
  [tree]
  (count (mst/leaf-seq tree)))

(defn- add!
  [tree key]
  (util/ok! (mst/add tree key cid1)))

;; -----------------------------------------------------------------------------
;; Utils
;; -----------------------------------------------------------------------------

(deftest test-count-prefix-len
  (are [expected a b] (= expected (mst/count-prefix-len a b))
    3 "abc" "abc"
    0 "" "abc"
    0 "abc" ""
    2 "ab" "abc"
    2 "abc" "ab"
    3 "abcde" "abc"
    3 "abc" "abcde"
    3 "abcde" "abc1"
    2 "abcde" "abb"
    0 "abcde" "qbb"
    0 "" "asdf"
    3 "abc" "abc\u0000"
    3 "abc\u0000" "abc"))

(deftest test-leading-zeros
  ;; layer assignments documented in mst.test.ts comments
  (are [layer key] (= layer (mst/leading-zeros key))
    0 "com.example.record/3jqfcqzm3fn2j"
    0 "com.example.record/3jqfcqzm3fo2j"
    0 "com.example.record/3jqfcqzm3fp2j"
    1 "com.example.record/3jqfcqzm3fs2j"
    0 "com.example.record/3jqfcqzm3ft2j"
    0 "com.example.record/3jqfcqzm3fu2j"
    0 "com.example.record/3jqfcqzm3fr2j"
    0 "com.example.record/3jqfcqzm3fz2j"
    0 "com.example.record/3jqfcqzm4fc2j"
    1 "com.example.record/3jqfcqzm4fd2j"
    0 "com.example.record/3jqfcqzm4ff2j"
    0 "com.example.record/3jqfcqzm4fg2j"
    0 "com.example.record/3jqfcqzm4fh2j"
    2 "com.example.record/3jqfcqzm3fx2j"))

;; -----------------------------------------------------------------------------
;; Allowable keys (mst.test.ts:176-254)
;; -----------------------------------------------------------------------------

(deftest test-key-validation
  (testing "rejected keys"
    (are [key] (and (not (mst/valid-key? key))
                    (= "InvalidMstKey"
                       (:error (mst/add (mst/create (blockstore/memory-blockstore))
                                        key cid1))))
      ""
      "asdf"
      "nested/collection/asdf"
      "coll/"
      "/rkey"
      "coll/jalapeñoA"
      "coll/coöperative"
      "coll/abc💩"
      "coll/key$"
      "coll/key%"
      "coll/key("
      "coll/key)"
      "coll/key+"
      "coll/key="
      "coll/@handle"
      "coll/any space"
      "coll/#extra"
      "coll/any+space"
      "coll/number[3]"
      "coll/number(3)"
      "coll/dHJ1ZQ=="
      "coll/\"quote\""
      ;; > 1024 chars
      (str "coll/" (apply str (repeat 1020 "a")))))

  (testing "allowed keys"
    (are [key] (and (mst/valid-key? key)
                    (not (:error (mst/add (mst/create (blockstore/memory-blockstore))
                                          key cid1))))
      "coll/3jui7kd54zh2y"
      "coll/self"
      "coll/example.com"
      "com.example/rkey"
      "coll/~1.2-3_"
      "coll/dHJ1ZQ"
      "coll/pre:fix"
      "coll/_")))

;; -----------------------------------------------------------------------------
;; Interop known maps (mst.test.ts:256-307)
;; -----------------------------------------------------------------------------

(deftest test-interop-known-maps
  (let [bs (blockstore/memory-blockstore)]
    (testing "empty tree root CID"
      (let [tree (mst/create bs)]
        (is (= 0 (leaf-count tree)))
        (is (= "bafyreie5737gdxlw5i64vzichcalba3z2v5n6icifvx5xytvske7mr3hpm"
               (root-str tree)))))

    (testing "trivial tree root CID"
      (let [tree (add! (mst/create bs) "com.example.record/3jqfcqzm3fo2j")]
        (is (= 1 (leaf-count tree)))
        (is (= "bafyreibj4lsc3aqnrvphp5xmrnfoorvru4wynt6lwidqbm2623a6tatzdu"
               (root-str tree)))))

    (testing "singlelayer2 tree root CID"
      (let [tree (add! (mst/create bs) "com.example.record/3jqfcqzm3fx2j")]
        (is (= 1 (leaf-count tree)))
        (is (= 2 (:layer tree)))
        (is (= "bafyreih7wfei65pxzhauoibu3ls7jgmkju4bspy4t2ha2qdjnzqvoy33ai"
               (root-str tree)))))

    (testing "simple tree root CID"
      (let [tree (reduce add!
                         (mst/create bs)
                         ["com.example.record/3jqfcqzm3fp2j"   ;; level 0
                          "com.example.record/3jqfcqzm3fr2j"   ;; level 0
                          "com.example.record/3jqfcqzm3fs2j"   ;; level 1
                          "com.example.record/3jqfcqzm3ft2j"   ;; level 0
                          "com.example.record/3jqfcqzm4fc2j"])] ;; level 0
        (is (= 5 (leaf-count tree)))
        (is (= "bafyreicmahysq4n6wfuxo522m6dpiy7z7qzym3dzs756t5n7nfdgccwq7m"
               (root-str tree)))))))

;; -----------------------------------------------------------------------------
;; Interop edge cases (mst.test.ts:309-449)
;; -----------------------------------------------------------------------------

(deftest test-trims-top-on-delete
  (let [bs (blockstore/memory-blockstore)
        l1root "bafyreifnqrwbk6ffmyaz5qtujqrzf5qmxf7cbxvgzktl4e3gabuxbtatv4"
        l0root "bafyreie4kjuxbwkhzg2i5dljaswcroeih4dgiqq6pazcmunwt2byd725vi"
        tree (reduce add!
                     (mst/create bs)
                     ["com.example.record/3jqfcqzm3fn2j"   ;; level 0
                      "com.example.record/3jqfcqzm3fo2j"   ;; level 0
                      "com.example.record/3jqfcqzm3fp2j"   ;; level 0
                      "com.example.record/3jqfcqzm3fs2j"   ;; level 1
                      "com.example.record/3jqfcqzm3ft2j"   ;; level 0
                      "com.example.record/3jqfcqzm3fu2j"])] ;; level 0
    (is (= 6 (leaf-count tree)))
    (is (= l1root (root-str tree)))
    (let [deleted (util/ok! (mst/delete tree "com.example.record/3jqfcqzm3fs2j"))]
      (is (= 5 (leaf-count deleted)))
      (is (= l0root (root-str deleted))))))

(deftest test-insertion-splits-two-layers-down
  (let [bs (blockstore/memory-blockstore)
        l1root "bafyreiettyludka6fpgp33stwxfuwhkzlur6chs4d2v4nkmq2j3ogpdjem"
        l2root "bafyreid2x5eqs4w4qxvc5jiwda4cien3gw2q6cshofxwnvv7iucrmfohpm"
        tree (reduce add!
                     (mst/create bs)
                     ["com.example.record/3jqfcqzm3fo2j"   ;; A; level 0
                      "com.example.record/3jqfcqzm3fp2j"   ;; B; level 0
                      "com.example.record/3jqfcqzm3fr2j"   ;; C; level 0
                      "com.example.record/3jqfcqzm3fs2j"   ;; D; level 1
                      "com.example.record/3jqfcqzm3ft2j"   ;; E; level 0
                      ;; GAP for F
                      "com.example.record/3jqfcqzm3fz2j"   ;; G; level 0
                      "com.example.record/3jqfcqzm4fc2j"   ;; H; level 0
                      "com.example.record/3jqfcqzm4fd2j"   ;; I; level 1
                      "com.example.record/3jqfcqzm4ff2j"   ;; J; level 0
                      "com.example.record/3jqfcqzm4fg2j"   ;; K; level 0
                      "com.example.record/3jqfcqzm4fh2j"])] ;; L; level 0
    (is (= 11 (leaf-count tree)))
    (is (= l1root (root-str tree)))
    ;; insert F (level 2): pushes E out of the node with G+H to a new node under D
    (let [with-f (add! tree "com.example.record/3jqfcqzm3fx2j")]
      (is (= 12 (leaf-count with-f)))
      (is (= l2root (root-str with-f)))
      ;; remove F: pushes E back over with G+H
      (let [without-f (util/ok! (mst/delete with-f "com.example.record/3jqfcqzm3fx2j"))]
        (is (= 11 (leaf-count without-f)))
        (is (= l1root (root-str without-f)))))))

(deftest test-new-layers-two-higher-than-existing
  (let [bs (blockstore/memory-blockstore)
        l0root "bafyreidfcktqnfmykz2ps3dbul35pepleq7kvv526g47xahuz3rqtptmky"
        l2root "bafyreiavxaxdz7o7rbvr3zg2liox2yww46t7g6hkehx4i4h3lwudly7dhy"
        l2root2 "bafyreig4jv3vuajbsybhyvb7gggvpwh2zszwfyttjrj6qwvcsp24h6popu"
        tree (reduce add!
                     (mst/create bs)
                     ["com.example.record/3jqfcqzm3ft2j"    ;; A; level 0
                      "com.example.record/3jqfcqzm3fz2j"])] ;; C; level 0
    (is (= 2 (leaf-count tree)))
    (is (= l0root (root-str tree)))
    ;; insert B (level 2)
    (let [with-b (add! tree "com.example.record/3jqfcqzm3fx2j")]
      (is (= 3 (leaf-count with-b)))
      (is (= l2root (root-str with-b)))
      ;; remove B
      (let [without-b (util/ok! (mst/delete with-b "com.example.record/3jqfcqzm3fx2j"))]
        (is (= 2 (leaf-count without-b)))
        (is (= l0root (root-str without-b))))
      ;; insert B (level 2) and D (level 1)
      (let [with-b-d (add! with-b "com.example.record/3jqfcqzm4fd2j")]
        (is (= 4 (leaf-count with-b-d)))
        (is (= l2root2 (root-str with-b-d)))
        ;; remove D
        (let [without-d (util/ok! (mst/delete with-b-d "com.example.record/3jqfcqzm4fd2j"))]
          (is (= 3 (leaf-count without-d)))
          (is (= l2root (root-str without-d))))))))

;; -----------------------------------------------------------------------------
;; Bulk operations (mst.test.ts:9-157)
;; -----------------------------------------------------------------------------

(deftest test-bulk-operations
  (let [bs (blockstore/memory-blockstore)
        mapping (util/generate-bulk-data-keys 1000 bs)
        shuffled (shuffle (vec mapping))
        tree (reduce (fn [t [k v]] (util/ok! (mst/add t k v)))
                     (mst/create bs)
                     shuffled)]

    (testing "adds records"
      (doseq [[k v] shuffled]
        (is (= v (util/ok! (mst/get-value tree k)))))
      (is (= 1000 (leaf-count tree))))

    (testing "edits records"
      (let [to-edit (take 100 shuffled)
            edited (mapv (fn [[k _]] [k (util/random-cid)]) to-edit)
            tree' (reduce (fn [t [k v]] (util/ok! (mst/update-value t k v)))
                          tree
                          edited)]
        (doseq [[k v] edited]
          (is (= v (util/ok! (mst/get-value tree' k)))))
        (is (= 1000 (leaf-count tree')))))

    (testing "deletes records"
      (let [to-delete (take 100 shuffled)
            the-rest (drop 100 shuffled)
            tree' (reduce (fn [t [k _]] (util/ok! (mst/delete t k)))
                          tree
                          to-delete)]
        (is (= 900 (leaf-count tree')))
        (doseq [[k _] to-delete]
          (is (nil? (util/ok! (mst/get-value tree' k)))))
        (doseq [[k v] the-rest]
          (is (= v (util/ok! (mst/get-value tree' k)))))))

    (testing "is order independent"
      (let [recreated (reduce (fn [t [k v]] (util/ok! (mst/add t k v)))
                              (mst/create bs)
                              (shuffle (vec mapping)))]
        (is (= (util/ok! (mst/pointer tree))
               (util/ok! (mst/pointer recreated))))))

    (testing "saves and loads from blockstore"
      (let [root (util/save-mst bs tree)
            loaded (mst/load bs root)]
        (is (= (util/ok! (mst/pointer tree))
               (util/ok! (mst/pointer loaded))))
        (is (= (mapv (juxt :key :value) (mst/leaf-seq tree))
               (mapv (juxt :key :value) (mst/leaf-seq loaded))))))

    (testing "diffs"
      (let [to-add (vec (util/generate-bulk-data-keys 100 bs))
            to-edit (->> shuffled (drop 500) (take 100))
            to-del (->> shuffled (drop 400) (take 100))
            expected-adds (into {} (map (fn [[k v]] [k {:key k :cid v}])) to-add)
            with-adds (reduce (fn [t [k v]] (util/ok! (mst/add t k v))) tree to-add)
            [with-edits expected-updates]
            (reduce (fn [[t expected] [k prev]]
                      (let [updated (util/random-cid)]
                        [(util/ok! (mst/update-value t k updated))
                         (assoc expected k {:key k :prev prev :cid updated})]))
                    [with-adds {}]
                    to-edit)
            to-diff (reduce (fn [t [k _]] (util/ok! (mst/delete t k))) with-edits to-del)
            expected-dels (into {} (map (fn [[k v]] [k {:key k :cid v}])) to-del)
            diff (util/ok! (mst/diff to-diff tree))]
        (is (= 100 (count (:adds diff))))
        (is (= 100 (count (:updates diff))))
        (is (= 100 (count (:deletes diff))))
        (is (= expected-adds (:adds diff)))
        (is (= expected-updates (:updates diff)))
        (is (= expected-dels (:deletes diff)))
        ;; ensure we correctly report all added CIDs
        (doseq [entry (mst/entry-seq to-diff)]
          (let [cid (if (mst/tree? entry)
                      (util/ok! (mst/pointer entry))
                      (:value entry))]
            (is (or (blockstore/has-block? bs cid)
                    (contains? (:new-mst-blocks diff) cid)
                    (contains? (:new-leaf-cids diff) cid)))))))))

;; -----------------------------------------------------------------------------
;; List operations
;; -----------------------------------------------------------------------------

(deftest test-list-operations
  (let [bs (blockstore/memory-blockstore)
        keys ["co.ll/key1" "co.ll/key3" "co.ll/key5" "other.coll/key2"]
        tree (reduce add! (mst/create bs) (shuffle keys))]
    (is (= (sort keys) (map :key (mst/leaf-seq tree))))
    (is (= ["co.ll/key3" "co.ll/key5" "other.coll/key2"]
           (map :key (util/ok! (mst/list-keys tree :after "co.ll/key1")))))
    (is (= ["co.ll/key1" "co.ll/key3"]
           (map :key (util/ok! (mst/list-keys tree :before "co.ll/key5")))))
    (is (= ["co.ll/key1"]
           (map :key (util/ok! (mst/list-keys tree :limit 1)))))
    (is (= ["co.ll/key1" "co.ll/key3" "co.ll/key5"]
           (map :key (util/ok! (mst/list-with-prefix tree "co.ll/")))))
    (is (= ["other.coll/key2"]
           (map :key (util/ok! (mst/list-with-prefix tree "other.coll/")))))))

;; -----------------------------------------------------------------------------
;; Commit proof fixtures (commit-proofs.test.ts)
;; -----------------------------------------------------------------------------

(defn- load-fixtures
  [name]
  #?(:clj (json/read-str (slurp (io/resource (str "interop-test-files/repo/" name))))
     :cljs (throw (ex-info "Fixture loading not implemented on cljs" {}))))

(defn- permutations
  [coll]
  (if (<= (count coll) 1)
    [(vec coll)]
    (mapcat (fn [i]
              (let [item (nth coll i)
                    rest- (into (vec (take i coll)) (drop (inc i) coll))]
                (map #(into [item] %) (permutations rest-))))
            (range (count coll)))))

#?(:clj
   (deftest test-commit-proof-fixtures
     (doseq [{:keys [comment leafValue keys adds dels
                     rootBeforeCommit rootAfterCommit blocksInProof]}
             (load-fixtures "commit-proof-fixtures.json")]
       (testing comment
         (let [leaf (data/parse-cid leafValue)
               storage (blockstore/memory-blockstore)
               tree (reduce (fn [t k] (util/ok! (mst/add t k leaf)))
                            (mst/create storage)
                            keys)
               root-before (util/ok! (mst/pointer tree))]
           (is (= rootBeforeCommit (data/format-cid root-before)))
           (let [tree (reduce (fn [t k] (util/ok! (mst/add t k leaf))) tree adds)
                 tree (reduce (fn [t k] (util/ok! (mst/delete t k))) tree dels)
                 root-after (util/ok! (mst/pointer tree))]
             (is (= rootAfterCommit (data/format-cid root-after)))
             (let [proof (reduce (fn [acc k]
                                   (merge acc (util/ok! (mst/covering-proof tree k))))
                                 {}
                                 (concat adds dels))]
               (doseq [cid-str blocksInProof]
                 (is (contains? proof (data/parse-cid cid-str))
                     (str "proof should contain " cid-str)))
               ;; proofs are invertible in every permutation of inverse ops
               (let [proof-storage (blockstore/memory-blockstore proof)
                     invert-ops (concat
                                 (map (fn [k] #(util/ok! (mst/delete % k))) adds)
                                 (map (fn [k] #(util/ok! (mst/add % k leaf))) dels))]
                 (doseq [order (permutations (vec invert-ops))]
                   (let [inverted (reduce (fn [t op] (op t))
                                          (mst/load proof-storage root-after)
                                          order)]
                     (is (= rootBeforeCommit
                            (data/format-cid (util/ok! (mst/pointer inverted)))))))))))))))
