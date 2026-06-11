(ns atproto.repo.blockstore-test
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [atproto.data :as data]
            [atproto.data.cbor :as cbor]
            [atproto.runtime.bytes :as bytes]
            [atproto.repo.blockstore :as blockstore]
            [atproto.repo.test-support.gen :as tgen]))

(def gen-block-map
  (gen/map tgen/gen-cid tgen/gen-bytes {:max-elements 15}))

(def gen-cids
  (gen/vector tgen/gen-cid 0 10))

(defn- lookups-match?
  "get-bytes/has-block?/get-blocks against bs agree with the model map
  for every queried cid."
  [bs model queried]
  (let [{:keys [blocks missing]} (blockstore/get-blocks bs queried)]
    (and (every? (fn [cid]
                   (if-let [expected (get model cid)]
                     (and (blockstore/has-block? bs cid)
                          (bytes/eq? expected (blockstore/get-bytes bs cid))
                          (bytes/eq? expected (get blocks cid)))
                     (and (not (blockstore/has-block? bs cid))
                          (nil? (blockstore/get-bytes bs cid))
                          (not (contains? blocks cid)))))
                 queried)
         ;; get-blocks partitions the query exactly by membership
         (= (set (filter #(contains? model %) queried)) (set (keys blocks)))
         (= (vec (remove #(contains? model %) queried)) missing))))

(defspec memory-blockstore-matches-model 100
  (prop/for-all [seeded gen-block-map
                 added gen-block-map
                 extra gen-cids]
    (let [bs (blockstore/memory-blockstore seeded)]
      (doseq [[cid bytes] added]
        (blockstore/put-block! bs cid bytes))
      (let [model (merge seeded added)]
        (lookups-match? bs model (concat (keys model) extra))))))

(defspec overlay-prefers-staged-then-saved 100
  (prop/for-all [staged-map gen-block-map
                 saved-map gen-block-map
                 extra gen-cids]
    (let [bs (blockstore/overlay (blockstore/memory-blockstore staged-map)
                                 (blockstore/memory-blockstore saved-map))
          model (merge saved-map staged-map)]
      (lookups-match? bs model (concat (keys model) extra)))))

(defspec apply-commit-removes-then-adds-and-sets-root 100
  (prop/for-all [[initial new-blocks removed root rev]
                 (gen/let [initial gen-block-map
                           new-blocks gen-block-map
                           n-rm (gen/choose 0 (count initial))
                           rm-order (gen/shuffle (keys initial))
                           root tgen/gen-cid]
                   [initial new-blocks (set (take n-rm rm-order)) root "3jqfcqzm3fo2j"])]
    (let [bs (blockstore/memory-blockstore initial)]
      (blockstore/apply-commit! bs {:cid root
                                    :rev rev
                                    :new-blocks new-blocks
                                    :removed-cids removed})
      (let [model (merge (apply dissoc initial removed) new-blocks)]
        (and (= root (blockstore/get-root bs))
             (lookups-match? bs model (concat (keys initial) (keys new-blocks))))))))

(defspec put-blocks-equals-individual-puts 100
  (prop/for-all [block-map gen-block-map]
    (let [batch (blockstore/memory-blockstore)
          single (blockstore/memory-blockstore)]
      (blockstore/put-blocks! batch block-map)
      (doseq [[cid bytes] block-map]
        (blockstore/put-block! single cid bytes))
      (and (lookups-match? batch block-map (keys block-map))
           (lookups-match? single block-map (keys block-map))))))

(defspec add-block-read-block-round-trips 100
  (prop/for-all [value (gen/map (gen/fmap keyword gen/string-alphanumeric)
                                (gen/one-of [tgen/gen-data-int
                                             gen/string
                                             gen/boolean])
                                {:max-elements 5})]
    (let [[cid block-map] (blockstore/add-block {} value)
          bs (blockstore/memory-blockstore block-map)
          {:keys [data bytes] :as res} (blockstore/read-block bs cid map?)]
      (and (data/cid-link? cid)
           (nil? (:error res))
           (= value data)
           (true? (data/verify-cid cid bytes))))))

;; -----------------------------------------------------------------------------
;; read-block error shapes
;; -----------------------------------------------------------------------------

(deftest test-read-block-errors
  (let [bytes (cbor/encode {:a 1})
        cid (data/cid-link bytes)
        bs (blockstore/memory-blockstore {cid bytes})]

    (testing "spec validation failure"
      (let [res (blockstore/read-block bs cid string?)]
        (is (= "InvalidBlock" (:error res)))
        (is (= cid (:cid res)))))

    (testing "missing block"
      (let [missing-cid (data/cid-link (bytes/utf8-bytes "elsewhere"))
            res (blockstore/read-block bs missing-cid)]
        (is (= "MissingBlock" (:error res)))
        (is (= missing-cid (:cid res)))))

    (testing "undecodable block"
      ;; 0x7e announces a 30-byte text string but only 3 bytes follow
      (let [junk (bytes/utf8-bytes "~abc")
            junk-cid (data/cid-link junk)
            bs (blockstore/memory-blockstore {junk-cid junk})]
        (is (= "InvalidBlock" (:error (blockstore/read-block bs junk-cid))))))))
