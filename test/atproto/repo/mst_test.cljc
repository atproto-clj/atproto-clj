(ns atproto.repo.mst-test
  "MST tests.

  Interop vectors (known root CIDs, layer assignments, the reference's
  key accept/reject lists, and the vendored commit-proof fixtures) are
  example-based because they pin cross-implementation byte
  compatibility; everything behavioral is property-based.

  Ported from packages/repo/tests/mst.test.ts and commit-proofs.test.ts
  in the TypeScript reference implementation."
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            #?(:clj [clojure.java.io :as io])
            [clojure.string :as str]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [atproto.data :as data]
            [atproto.runtime.json :as json]
            [atproto.repo.blockstore :as blockstore]
            [atproto.repo.mst :as mst]
            [atproto.repo.test-support.gen :as tgen]
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

(defn- build-tree
  "Tree over a fresh memory blockstore from [key cid] pairs, in order."
  ([pairs] (build-tree (blockstore/memory-blockstore) pairs))
  ([bs pairs]
   (reduce (fn [t [k v]] (util/ok! (mst/add t k v)))
           (mst/create bs)
           pairs)))

;; -----------------------------------------------------------------------------
;; Utils
;; -----------------------------------------------------------------------------

(def gen-prefix-pair
  "[prefix a b] where a and b do not extend the common prefix."
  (gen/such-that (fn [[_ a b]]
                   (or (empty? a) (empty? b) (not= (first a) (first b))))
                 (gen/tuple gen/string-alphanumeric
                            gen/string-alphanumeric
                            gen/string-alphanumeric)
                 100))

(defspec count-prefix-len-finds-the-common-prefix 200
  (prop/for-all [[p a b] gen-prefix-pair]
    (= (count p) (mst/count-prefix-len (str p a) (str p b)))))

(deftest test-leading-zeros
  ;; interop vectors: layer assignments documented in mst.test.ts comments
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
;; Key validation
;; -----------------------------------------------------------------------------

(defspec generated-valid-keys-are-accepted 200
  (prop/for-all [k tgen/gen-mst-key]
    (and (mst/valid-key? k)
         (not (:error (mst/add (mst/create (blockstore/memory-blockstore))
                               k cid1))))))

(defspec generated-invalid-keys-are-rejected 200
  (prop/for-all [k tgen/gen-invalid-mst-key]
    (and (not (mst/valid-key? k))
         (= "InvalidMstKey"
            (:error (mst/add (mst/create (blockstore/memory-blockstore))
                             k cid1))))))

(deftest test-key-validation-interop
  ;; the reference's accept/reject lists, kept verbatim (mst.test.ts:176-254)
  (testing "rejected keys"
    (are [key] (not (mst/valid-key? key))
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
    (are [key] (mst/valid-key? key)
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
;; Model-based properties
;; -----------------------------------------------------------------------------

(defspec tree-matches-model-map 50
  (prop/for-all [[m order absent-key]
                 (gen/let [m tgen/gen-key-cid-map
                           order (gen/shuffle (vec m))
                           absent-key (gen/such-that #(not (contains? m %))
                                                     tgen/gen-mst-key
                                                     100)]
                   [m order absent-key])]
    (let [tree (build-tree order)]
      (and (= (sort (keys m)) (map :key (mst/leaf-seq tree)))
           (every? (fn [[k v]] (= v (util/ok! (mst/get-value tree k)))) m)
           (nil? (util/ok! (mst/get-value tree absent-key)))
           (= "KeyAlreadyExists"
              (:error (mst/add tree (key (first m)) cid1)))
           (= "KeyNotFound"
              (:error (mst/update-value tree absent-key cid1)))
           (= "KeyNotFound"
              (:error (mst/delete tree absent-key)))))))

(defspec roots-are-insertion-order-independent 50
  (prop/for-all [[order1 order2]
                 (gen/let [m tgen/gen-key-cid-map
                           order1 (gen/shuffle (vec m))
                           order2 (gen/shuffle (vec m))]
                   [order1 order2])]
    (= (util/ok! (mst/pointer (build-tree order1)))
       (util/ok! (mst/pointer (build-tree order2))))))

(defspec updates-and-deletes-match-model 50
  (prop/for-all [[m order n-upd n-del upd-cids]
                 (gen/let [m tgen/gen-key-cid-map
                           order (gen/shuffle (vec m))
                           n-upd (gen/choose 0 (count m))
                           n-del (gen/choose 0 (- (count m) n-upd))
                           upd-cids (gen/vector tgen/gen-cid n-upd)]
                   [m order n-upd n-del upd-cids])]
    (let [tree (build-tree (vec m))
          to-update (map vector (map first (take n-upd order)) upd-cids)
          to-delete (map first (take n-del (drop n-upd order)))
          updated (reduce (fn [t [k v]] (util/ok! (mst/update-value t k v)))
                          tree
                          to-update)
          final (reduce (fn [t k] (util/ok! (mst/delete t k)))
                        updated
                        to-delete)
          model (reduce dissoc (into m to-update) to-delete)]
      (and (= (sort (keys model)) (map :key (mst/leaf-seq final)))
           (every? (fn [[k v]] (= v (util/ok! (mst/get-value final k)))) model)
           (every? (fn [k] (nil? (util/ok! (mst/get-value final k)))) to-delete)))))

(defspec save-and-load-round-trips 50
  (prop/for-all [m tgen/gen-key-cid-map]
    (let [bs (blockstore/memory-blockstore)
          tree (build-tree bs (vec m))
          root (util/save-mst bs tree)
          loaded (mst/load bs root)]
      (and (= (util/ok! (mst/pointer tree)) (util/ok! (mst/pointer loaded)))
           (= (mapv (juxt :key :value) (mst/leaf-seq tree))
              (mapv (juxt :key :value) (mst/leaf-seq loaded)))))))

(defspec list-operations-match-model 50
  (prop/for-all [[m after before limit prefix]
                 (gen/let [m tgen/gen-key-cid-map
                           after (gen/one-of [(gen/return nil)
                                              (gen/elements (keys m))
                                              tgen/gen-mst-key])
                           before (gen/one-of [(gen/return nil)
                                               (gen/elements (keys m))
                                               tgen/gen-mst-key])
                           limit (gen/one-of [(gen/return nil)
                                              (gen/choose 0 (inc (count m)))])
                           prefix (gen/one-of [(gen/elements (map #(subs % 0 (min 4 (count %)))
                                                                  (keys m)))
                                               tgen/gen-key-segment])]
                   [m after before limit prefix])]
    (let [tree (build-tree (vec m))
          sorted-keys (sort (keys m))
          model (cond->> sorted-keys
                  after (filter #(pos? (compare % after)))
                  before (filter #(neg? (compare % before)))
                  limit (take limit))]
      (and (= (vec model)
              (mapv :key (util/ok! (mst/list-keys tree
                                                  :after after
                                                  :before before
                                                  :limit limit))))
           (= (vec (filter #(str/starts-with? % prefix) sorted-keys))
              (mapv :key (util/ok! (mst/list-with-prefix tree prefix))))))))

;; -----------------------------------------------------------------------------
;; Diff properties
;; -----------------------------------------------------------------------------

(def gen-tree-and-edit
  "[base-map adds updates deletes]: adds are disjoint from base, updates
  and deletes are disjoint subsets of base."
  (gen/let [m tgen/gen-key-cid-map
            raw-adds tgen/gen-key-cid-map
            order (gen/shuffle (vec m))
            n-upd (gen/choose 0 (count m))
            n-del (gen/choose 0 (- (count m) n-upd))
            upd-cids (gen/vector tgen/gen-cid n-upd)]
    [m
     (vec (apply dissoc raw-adds (keys m)))
     (mapv vector (map first (take n-upd order)) upd-cids)
     (mapv first (take n-del (drop n-upd order)))]))

(defspec diff-reports-exactly-the-applied-ops 50
  (prop/for-all [[m adds updates deletes] gen-tree-and-edit]
    (let [bs (blockstore/memory-blockstore)
          base (build-tree bs (vec m))
          with-adds (reduce (fn [t [k v]] (util/ok! (mst/add t k v))) base adds)
          with-upds (reduce (fn [t [k v]] (util/ok! (mst/update-value t k v)))
                            with-adds
                            updates)
          final (reduce (fn [t k] (util/ok! (mst/delete t k))) with-upds deletes)
          diff (util/ok! (mst/diff final base))
          ;; updates to the same cid are invisible to a content-addressed diff
          visible-updates (remove (fn [[k v]] (= v (get m k))) updates)]
      (and (= (into {} (map (fn [[k v]] [k {:key k :cid v}])) adds)
              (:adds diff))
           (= (into {} (map (fn [[k v]] [k {:key k :prev (get m k) :cid v}]))
                    visible-updates)
              (:updates diff))
           (= (into {} (map (fn [k] [k {:key k :cid (get m k)}])) deletes)
              (:deletes diff))))))

(defspec diff-against-nil-reports-all-blocks 50
  (prop/for-all [m tgen/gen-key-cid-map]
    (let [tree (build-tree (vec m))
          diff (util/ok! (mst/diff tree nil))]
      (and (= (into {} (map (fn [[k v]] [k {:key k :cid v}])) m)
              (:adds diff))
           (empty? (:updates diff))
           (empty? (:deletes diff))
           (empty? (:removed-cids diff))
           ;; every node of the tree is reported as a new block or leaf
           (every? (fn [entry]
                     (if (mst/tree? entry)
                       (contains? (:new-mst-blocks diff)
                                  (util/ok! (mst/pointer entry)))
                       (contains? (:new-leaf-cids diff) (:value entry))))
                   (mst/entry-seq tree))))))

(defspec diff-reported-blocks-cover-the-new-tree 50
  (prop/for-all [[m adds updates deletes] gen-tree-and-edit]
    (let [bs (blockstore/memory-blockstore)
          base (build-tree bs (vec m))
          _ (util/save-mst bs base)
          final (as-> base t
                  (reduce (fn [t [k v]] (util/ok! (mst/add t k v))) t adds)
                  (reduce (fn [t [k v]] (util/ok! (mst/update-value t k v))) t updates)
                  (reduce (fn [t k] (util/ok! (mst/delete t k))) t deletes))
          diff (util/ok! (mst/diff final base))
          ;; leaf record blocks live outside the MST; the old tree's
          ;; leaf cids stand in for "already known to the consumer"
          base-leaf-cids (set (vals m))]
      ;; every reachable cid of the new tree is either part of the old
      ;; tree (stored node or known leaf) or reported by the diff
      (every? (fn [entry]
                (if (mst/tree? entry)
                  (let [cid (util/ok! (mst/pointer entry))]
                    (or (blockstore/has-block? bs cid)
                        (contains? (:new-mst-blocks diff) cid)))
                  (or (contains? base-leaf-cids (:value entry))
                      (contains? (:new-leaf-cids diff) (:value entry)))))
              (mst/entry-seq final)))))

;; -----------------------------------------------------------------------------
;; Covering-proof properties
;; -----------------------------------------------------------------------------

(defspec covering-proofs-make-edits-invertible 30
  (prop/for-all [[m adds deletes invert-order]
                 (gen/let [[m adds _ deletes] gen-tree-and-edit
                           invert-order (gen/shuffle
                                         (concat (map (fn [[k _]] [:del k]) adds)
                                                 (map (fn [k] [:add k]) deletes)))]
                   [m adds deletes invert-order])]
    (let [base (build-tree (vec m))
          root-before (util/ok! (mst/pointer base))
          final (as-> base t
                  (reduce (fn [t [k v]] (util/ok! (mst/add t k v))) t adds)
                  (reduce (fn [t k] (util/ok! (mst/delete t k))) t deletes))
          changed-keys (concat (map first adds) deletes)
          proof (reduce (fn [acc k]
                          (merge acc (util/ok! (mst/covering-proof final k))))
                        {}
                        changed-keys)
          ;; invert the edit over a blockstore holding ONLY the proof blocks
          proof-bs (blockstore/memory-blockstore proof)
          add-cids (into {} adds)
          del-cids (select-keys m deletes)
          inverted (reduce (fn [t [op k]]
                             (case op
                               :del (util/ok! (mst/delete t k))
                               :add (util/ok! (mst/add t k (get del-cids k)))))
                           (mst/load proof-bs (util/ok! (mst/pointer final)))
                           invert-order)]
      (= root-before (util/ok! (mst/pointer inverted))))))

;; -----------------------------------------------------------------------------
;; Commit proof fixtures (interop, commit-proofs.test.ts)
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
