(ns atproto.repo.car-test
  "CAR v1 tests, ported from packages/repo/tests/car.test.ts plus
  framing-error and JVM streaming coverage."
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            #?(:clj [clojure.java.io :as io])
            [atproto.data :as data]
            [atproto.data.cbor :as cbor]
            [atproto.runtime.bytes :as bytes]
            [atproto.runtime.crypto :as runtime.crypto]
            [atproto.runtime.json :as json]
            [atproto.repo.car :as car])
  #?(:clj (:import [java.io ByteArrayInputStream ByteArrayOutputStream
                    PipedInputStream PipedOutputStream])))

(defn- cbor-block
  [value]
  (let [bytes (cbor/encode value)]
    {:cid (data/cid-link bytes) :bytes bytes}))

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
             (is (= (count (:blocks fixture)) (count (:blocks read))))
             (doseq [[expected actual] (map vector (:blocks fixture) (:blocks read))]
               (is (= (:cid expected) (data/format-cid (:cid actual))))
               (is (bytes/eq? (runtime.crypto/base64-decode (:bytes expected))
                              (:bytes actual))))))))))

(deftest test-read-car-with-root
  (let [block (cbor-block {:test 1})
        car-bytes (car/write-car (:cid block) [block])
        {:keys [root block-map] :as read} (car/read-car-with-root car-bytes)]
    (is (nil? (:error read)))
    (is (= (:cid block) root))
    (is (bytes/eq? (:bytes block) (get block-map (:cid block))))
    ;; zero roots
    (is (= "InvalidCar"
           (:error (car/read-car-with-root (car/write-car nil [block])))))))

(deftest test-cid-verification
  (let [blocks (mapv #(cbor-block {:block %}) (range 4))
        bad-block (cbor-block {:block "bad"})
        ;; last block claims block3's CID but carries bad-block's bytes
        tampered (conj (pop blocks)
                       {:cid (:cid (peek blocks)) :bytes (:bytes bad-block)})
        car-bytes (car/write-car (:cid (first blocks)) tampered)]
    (testing "verifies CIDs by default"
      (let [res (car/read-car car-bytes)]
        (is (= "InvalidCarBlock" (:error res)))
        (is (= (:cid (peek blocks)) (:cid res)))))
    (testing "skips CID verification"
      (let [res (car/read-car car-bytes :skip-cid-verification? true)]
        (is (nil? (:error res)))
        (is (= 4 (count (:blocks res))))))))

(deftest test-framing-errors
  (let [block (cbor-block {:test 1})
        car-bytes (car/write-car (:cid block) [block])
        len (bytes/length car-bytes)]
    (testing "empty input"
      (is (= "InvalidCar" (:error (car/read-car (bytes/slice car-bytes 0 0))))))
    (testing "truncated header"
      (is (= "InvalidCar" (:error (car/read-car (bytes/slice car-bytes 0 3))))))
    (testing "truncated block"
      (is (= "InvalidCar" (:error (car/read-car (bytes/slice car-bytes 0 (dec len)))))))
    (testing "garbage header"
      (is (= "InvalidCar"
             (:error (car/read-car (bytes/utf8-bytes "junks"))))))))

;; -----------------------------------------------------------------------------
;; JVM streaming
;; -----------------------------------------------------------------------------

#?(:clj
   (deftest test-write-car-stream
     (let [blocks (mapv #(cbor-block {:block %}) (range 10))
           root (:cid (first blocks))
           out (ByteArrayOutputStream.)]
       (car/write-car-stream root blocks out)
       (is (bytes/eq? (car/write-car root blocks) (.toByteArray out))))))

#?(:clj
   (deftest test-block-reader
     (let [blocks (mapv #(cbor-block {:block %}) (range 100))
           root (:cid (first blocks))
           car-bytes (car/write-car root blocks)
           {:keys [roots] :as reader} (car/block-reader (ByteArrayInputStream. car-bytes))]
       (is (= [root] roots))
       (let [read (into [] (:blocks reader))]
         (is (= (map :cid blocks) (map :cid read)))
         (is (every? true? (map #(bytes/eq? (:bytes %1) (:bytes %2)) blocks read)))))))

#?(:clj
   (deftest test-block-reader-detects-corruption
     (let [good (cbor-block {:block 1})
           bad {:cid (:cid (cbor-block {:block 2})) :bytes (:bytes good)}
           car-bytes (car/write-car (:cid good) [good bad])
           reader (car/block-reader (ByteArrayInputStream. car-bytes))]
       (is (= "InvalidCarBlock"
              (try
                (into [] (:blocks reader))
                nil
                (catch Exception e (:error (ex-data e)))))))))

#?(:clj
   (deftest test-block-reader-streams-bounded
     ;; 50k blocks streamed from a pipe: the writer thread produces the CAR
     ;; incrementally and the reader consumes it without materializing it.
     (let [n 50000
           block-for (fn [i] (cbor-block {:block i}))
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
