(ns atproto.repo.car-test
  "CAR v1 tests.

  The vendored fixtures stay example-based (they pin byte-exact interop
  with the reference implementation); round-trip, tamper-detection, and
  truncation behavior are property-based. Ported from
  packages/repo/tests/car.test.ts plus JVM streaming coverage."
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            #?(:clj [clojure.java.io :as io])
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [atproto.data :as data]
            [atproto.data.cbor :as cbor]
            [atproto.runtime.bytes :as bytes]
            [atproto.runtime.crypto :as runtime.crypto]
            [atproto.runtime.json :as json]
            [atproto.repo.car :as car]
            [atproto.repo.test-support.gen :as tgen])
  #?(:clj (:import [java.io ByteArrayInputStream ByteArrayOutputStream
                    PipedInputStream PipedOutputStream])))

(defn- blocks-match?
  "Same cids and bytes, in the same order."
  [expected actual]
  (and (= (count expected) (count actual))
       (every? true?
               (map (fn [e a]
                      (and (= (:cid e) (:cid a))
                           (bytes/eq? (:bytes e) (:bytes a))))
                    expected
                    actual))))

;; -----------------------------------------------------------------------------
;; Interop fixtures (byte-exact, car.test.ts:23-61)
;; -----------------------------------------------------------------------------

#?(:clj
   (defn- load-fixtures
     []
     (json/read-str (slurp (io/resource "interop-test-files/repo/car-file-fixtures.json")))))

#?(:clj
   (deftest test-car-fixtures
     (doseq [fixture (load-fixtures)]
       (let [root (data/parse-cid (:root fixture))
             blocks (map (fn [{:keys [cid bytes]}]
                           {:cid (data/parse-cid cid)
                            :bytes (runtime.crypto/base64-decode bytes)})
                         (:blocks fixture))]

         (testing "writes car files byte-exactly"
           (is (= (:car fixture)
                  (runtime.crypto/base64-encode (car/write-car root blocks)))))

         (testing "reads car files"
           (let [{:keys [roots] :as read} (car/read-car
                                           (runtime.crypto/base64-decode (:car fixture)))]
             (is (nil? (:error read)))
             (is (= [(:root fixture)] (map data/format-cid roots)))
             (is (blocks-match? blocks (:blocks read)))))))))

;; -----------------------------------------------------------------------------
;; Round-trip properties
;; -----------------------------------------------------------------------------

(defspec car-write-read-round-trips 100
  (prop/for-all [blocks tgen/gen-cbor-blocks
                 root? gen/boolean]
    (let [root (when root? (:cid (first blocks)))
          car-bytes (car/write-car root blocks)
          read (car/read-car car-bytes)
          with-root (car/read-car-with-root car-bytes)]
      (and (nil? (:error read))
           (= (if root [root] []) (:roots read))
           (blocks-match? blocks (:blocks read))
           ;; the block-map indexes the same content
           (every? (fn [{:keys [cid bytes]}]
                     (bytes/eq? bytes (get (:block-map read) cid)))
                   blocks)
           ;; read-car-with-root requires exactly one root
           (if root
             (and (nil? (:error with-root))
                  (= root (:root with-root)))
             (= "InvalidCar" (:error with-root)))))))

(defspec car-tampered-blocks-are-detected 100
  (prop/for-all [[blocks index replacement]
                 (gen/let [blocks tgen/gen-cbor-blocks
                           index (gen/choose 0 (dec (count blocks)))
                           replacement (gen/such-that
                                        #(not (bytes/eq? (:bytes %)
                                                         (:bytes (nth blocks index))))
                                        tgen/gen-cbor-block
                                        100)]
                   [blocks index replacement])]
    (let [tampered (assoc blocks index
                          {:cid (:cid (nth blocks index))
                           :bytes (:bytes replacement)})
          car-bytes (car/write-car (:cid (first blocks)) tampered)
          checked (car/read-car car-bytes)
          unchecked (car/read-car car-bytes :skip-cid-verification? true)]
      (and (= "InvalidCarBlock" (:error checked))
           (= (:cid (nth blocks index)) (:cid checked))
           (nil? (:error unchecked))
           (= (count blocks) (count (:blocks unchecked)))
           ;; the JVM streaming reader rejects the same tampered block
           #?(:clj (= "InvalidCarBlock"
                      (try
                        (into [] (:blocks (car/block-reader
                                           (ByteArrayInputStream. car-bytes))))
                        nil
                        (catch Exception e (:error (ex-data e)))))
              :cljs true)))))

(defspec car-truncation-never-yields-garbage 100
  (prop/for-all [[blocks cut]
                 (gen/let [blocks tgen/gen-cbor-blocks
                           car-len (gen/return (bytes/length
                                                (car/write-car (:cid (first blocks))
                                                               blocks)))
                           cut (gen/choose 0 (dec car-len))]
                   [blocks cut])]
    (let [car-bytes (car/write-car (:cid (first blocks)) blocks)
          read (car/read-car (bytes/slice car-bytes 0 cut))]
      (or
       ;; either a clean framing error...
       (= "InvalidCar" (:error read))
       ;; ...or the cut fell on a block boundary and we got a valid prefix
       (and (nil? (:error read))
            (blocks-match? (take (count (:blocks read)) blocks)
                           (:blocks read)))))))

(deftest test-framing-errors
  (testing "garbage header"
    (is (= "InvalidCar"
           (:error (car/read-car (bytes/utf8-bytes "junks")))))))

;; -----------------------------------------------------------------------------
;; JVM streaming
;; -----------------------------------------------------------------------------

#?(:clj
   (defspec car-streaming-matches-in-memory 50
     (prop/for-all [blocks tgen/gen-cbor-blocks]
       (let [root (:cid (first blocks))
             car-bytes (car/write-car root blocks)
             out (ByteArrayOutputStream.)
             _ (car/write-car-stream root blocks out)
             reader (car/block-reader (ByteArrayInputStream. car-bytes))]
         (and ;; the streaming writer produces identical bytes
          (bytes/eq? car-bytes (.toByteArray out))
          ;; the streaming reader agrees with the in-memory reader
          (= [root] (:roots reader))
          (blocks-match? blocks (into [] (:blocks reader))))))))

#?(:clj
   (deftest test-block-reader-streams-bounded
     ;; 50k blocks streamed from a pipe: the writer thread produces the CAR
     ;; incrementally and the reader consumes it without materializing it.
     (let [n 50000
           block-for (fn [i]
                       (let [bytes (cbor/encode {:block i})]
                         {:cid (data/cid-link bytes) :bytes bytes}))
           in (PipedInputStream. (* 64 1024))
           out (PipedOutputStream. in)
           writer (future
                    (try
                      (car/write-car-stream (:cid (block-for 0))
                                            (map block-for (range n))
                                            out)
                      (finally (.close out))))
           reader (car/block-reader in)
           counted (reduce (fn [acc _] (inc acc)) 0 (:blocks reader))]
       @writer
       (is (= n counted)))))
