(ns atproto.repo.blockstore-test
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            [atproto.data :as data]
            [atproto.data.cbor :as cbor]
            [atproto.runtime.bytes :as bytes]
            [atproto.repo.blockstore :as blockstore]
            [atproto.repo.test-support.util :as util]))

(defn- block-of
  [value]
  (let [bytes (cbor/encode value)]
    [(data/cid-link bytes) bytes]))

(deftest test-memory-blockstore
  (let [bs (blockstore/memory-blockstore)
        [cid bytes] (block-of {:a 1})
        [cid2 bytes2] (block-of {:b 2})
        missing-cid (util/random-cid)]

    (testing "empty store"
      (is (nil? (blockstore/get-bytes bs cid)))
      (is (false? (blockstore/has-block? bs cid)))
      (is (nil? (blockstore/get-root bs)))
      (is (= {:blocks {} :missing [cid]}
             (blockstore/get-blocks bs [cid]))))

    (testing "put and get"
      (blockstore/put-block! bs cid bytes)
      (blockstore/put-blocks! bs {cid2 bytes2})
      (is (bytes/eq? bytes (blockstore/get-bytes bs cid)))
      (is (blockstore/has-block? bs cid2))
      (let [{:keys [blocks missing]} (blockstore/get-blocks bs [cid cid2 missing-cid])]
        (is (= [missing-cid] missing))
        (is (= #{cid cid2} (set (keys blocks))))))

    (testing "root"
      (blockstore/update-root! bs cid "3jqfcqzm3fo2j")
      (is (= cid (blockstore/get-root bs))))

    (testing "apply-commit!"
      (blockstore/apply-commit! bs {:cid cid2
                                    :rev "3jqfcqzm3fp2j"
                                    :new-blocks {cid2 bytes2}
                                    :removed-cids #{cid}})
      (is (= cid2 (blockstore/get-root bs)))
      (is (not (blockstore/has-block? bs cid)))
      (is (blockstore/has-block? bs cid2)))))

(deftest test-seeded-blockstore
  (let [[cid bytes] (block-of {:a 1})
        bs (blockstore/memory-blockstore {cid bytes})]
    (is (blockstore/has-block? bs cid))))

(deftest test-overlay
  (let [[cid1 bytes1] (block-of {:a 1})
        [cid2 bytes2] (block-of {:b 2})
        missing-cid (util/random-cid)
        staged (blockstore/memory-blockstore {cid1 bytes1})
        saved (blockstore/memory-blockstore {cid2 bytes2})
        bs (blockstore/overlay staged saved)]
    (is (bytes/eq? bytes1 (blockstore/get-bytes bs cid1)))
    (is (bytes/eq? bytes2 (blockstore/get-bytes bs cid2)))
    (is (nil? (blockstore/get-bytes bs missing-cid)))
    (is (blockstore/has-block? bs cid1))
    (is (blockstore/has-block? bs cid2))
    (is (not (blockstore/has-block? bs missing-cid)))
    (let [{:keys [blocks missing]} (blockstore/get-blocks bs [cid1 cid2 missing-cid])]
      (is (= #{cid1 cid2} (set (keys blocks))))
      (is (= [missing-cid] missing)))))

(deftest test-read-block
  (let [[cid bytes] (block-of {:a 1})
        bs (blockstore/memory-blockstore {cid bytes})]

    (testing "decodes a present block"
      (let [{:keys [data] :as res} (blockstore/read-block bs cid)]
        (is (nil? (:error res)))
        (is (= {:a 1} data))
        (is (bytes/eq? bytes (:bytes res)))))

    (testing "spec validation"
      (is (nil? (:error (blockstore/read-block bs cid map?))))
      (let [res (blockstore/read-block bs cid string?)]
        (is (= "InvalidBlock" (:error res)))
        (is (= cid (:cid res)))))

    (testing "missing block"
      (let [missing-cid (util/random-cid)
            res (blockstore/read-block bs missing-cid)]
        (is (= "MissingBlock" (:error res)))
        (is (= missing-cid (:cid res)))))

    (testing "undecodable block"
      ;; 0x7e announces a 30-byte text string but only 3 bytes follow
      (let [junk (bytes/utf8-bytes "~abc")
            junk-cid (data/cid-link junk)
            bs (blockstore/memory-blockstore {junk-cid junk})]
        (is (= "InvalidBlock" (:error (blockstore/read-block bs junk-cid))))))))

(deftest test-add-block
  (let [[cid block-map] (blockstore/add-block {} {:a 1})]
    (is (data/cid-link? cid))
    (is (contains? block-map cid))
    (is (= {:a 1} (cbor/decode (get block-map cid))))))
