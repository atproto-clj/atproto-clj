(ns atproto.runtime.varint-test
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            [multiformats.varint :as mf.varint]
            [atproto.runtime.bytes :as bytes]
            [atproto.runtime.varint :as varint]))

(deftest test-round-trip
  (doseq [n [0 1 127 128 255 300 16383 16384 65536
             ;; CAR-realistic block sizes
             1048576 16777216
             2147483647]]
    (testing (str "round-trip " n)
      (is (= n (varint/decode (varint/encode n)))))))

(deftest test-known-encodings
  (are [bs n] (bytes/eq? #?(:clj (byte-array bs)
                            :cljs (js/Int8Array.from (into-array bs)))
                         (varint/encode n))
    [0x00] 0
    [0x01] 1
    [0x7f] 127
    [-0x80 0x01] 128       ;; 0x80 0x01
    [-0x01 0x7f] 16383))   ;; 0xff 0x7f

(deftest test-matches-multiformats
  (doseq [n [0 1 127 128 16383 16384 123456789]]
    (is (bytes/eq? (mf.varint/encode n) (varint/encode n)))
    (is (= (mf.varint/decode (mf.varint/encode n))
           (varint/decode (varint/encode n))))))

(deftest test-read-bytes
  (testing "reads at an offset and returns [value bytes-read]"
    (let [b #?(:clj (byte-array [0x00 -0x54 0x02])  ;; 0x00, then varint 300 (0xac 0x02)
               :cljs (js/Int8Array.from #js [0x00 -0x54 0x02]))]
      (is (= [300 2] (varint/read-bytes b 1)))))

  (testing "decode ignores trailing bytes"
    (let [b #?(:clj (byte-array [0x05 0x42])
               :cljs (js/Int8Array.from #js [0x05 0x42]))]
      (is (= 5 (varint/decode b)))))

  (testing "throws on truncated varint"
    (let [b #?(:clj (byte-array [-0x80])  ;; lone continuation byte 0x80
               :cljs (js/Int8Array.from #js [-0x80]))]
      (is (thrown? #?(:clj Exception :cljs js/Error)
                   (varint/read-bytes b 0))))))
