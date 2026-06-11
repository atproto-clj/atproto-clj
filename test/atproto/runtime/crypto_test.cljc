(ns atproto.runtime.crypto-test
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            [clojure.string :as str]
            [atproto.runtime.bytes :as bytes]
            [atproto.runtime.crypto :as crypto]))

;; sha256 of "abc", see https://www.di-mgt.com.au/sha_testvectors.html
(def sha256-abc-hex
  "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")

#?(:clj
   (deftest sha256-test
     (testing "accepts strings and byte arrays equivalently"
       (is (bytes/eq? (crypto/sha256 "abc")
                      (crypto/sha256 (.getBytes "abc" "UTF-8")))))
     (testing "known test vector"
       (is (= sha256-abc-hex (crypto/hex-encode (crypto/sha256 "abc"))))
       (is (= sha256-abc-hex (crypto/sha256-hex "abc")))
       (is (= sha256-abc-hex (crypto/sha256-hex (.getBytes "abc" "UTF-8")))))))

#?(:clj
   (deftest base64-test
     (testing "round-trips byte arrays"
       (dotimes [_ 20]
         (let [b (crypto/random-bytes (inc (rand-int 64)))]
           (is (bytes/eq? b (crypto/base64-decode (crypto/base64-encode b)))))))
     (testing "encodes without padding"
       (is (= "QQ" (crypto/base64-encode (byte-array [(int \A)]))))
       (is (not (str/includes? (crypto/base64-encode (crypto/random-bytes 10)) "="))))
     (testing "decodes padded and unpadded input"
       (is (bytes/eq? (byte-array [(int \A)]) (crypto/base64-decode "QQ")))
       (is (bytes/eq? (byte-array [(int \A)]) (crypto/base64-decode "QQ=="))))
     (testing "returns nil on invalid input"
       (is (nil? (crypto/base64-decode "&&&&"))))))

#?(:clj
   (deftest base64url-test
     (testing "round-trips byte arrays"
       (dotimes [_ 20]
         (let [b (crypto/random-bytes (inc (rand-int 64)))]
           (is (bytes/eq? b (crypto/base64url-decode (crypto/base64url-encode b)))))))
     (testing "decodes padded and unpadded input"
       (is (bytes/eq? (byte-array [(unchecked-byte 0xfb)]) (crypto/base64url-decode "-w")))
       (is (bytes/eq? (byte-array [(unchecked-byte 0xfb)]) (crypto/base64url-decode "-w=="))))
     (testing "returns nil on invalid input"
       (is (nil? (crypto/base64url-decode "+/+/"))))))

#?(:clj
   (deftest hex-test
     (testing "round-trips byte arrays"
       (dotimes [_ 20]
         (let [b (crypto/random-bytes (inc (rand-int 64)))]
           (is (bytes/eq? b (crypto/hex-decode (crypto/hex-encode b)))))))
     (testing "encodes lowercase"
       (is (= "00ff10" (crypto/hex-encode (byte-array [0x00 (unchecked-byte 0xff) 0x10])))))
     (testing "decodes uppercase and lowercase"
       (is (bytes/eq? (byte-array [(unchecked-byte 0xab)]) (crypto/hex-decode "AB")))
       (is (bytes/eq? (byte-array [(unchecked-byte 0xab)]) (crypto/hex-decode "ab"))))
     (testing "returns nil on invalid input"
       (is (nil? (crypto/hex-decode "zz")))
       (is (nil? (crypto/hex-decode "abc"))))))
