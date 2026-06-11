(ns atproto.data.json-test
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            [clojure.string :as str]
            [atproto.runtime.bytes :as bytes]
            [atproto.runtime.crypto :as crypto]
            [atproto.data :as data]
            [atproto.data.json :as json]))

;; $bytes value from interop fixture 2 (32 bytes, unpadded base64)
(def fixture-bytes-b64 "nFERjvLLiw9qm45JrqH9QTzyC2Lu1Xb4ne6+sBrCzI0")

(deftest test-encoding-decoding

  (let [bytes (byte-array [(byte 0x43) (byte 0x6c) (byte 0x6f) (byte 0x6a)
                           (byte 0x75) (byte 0x72) (byte 0x65) (byte 0x21)])]
    (testing "JSON encoding & decoding of at proto data."
      (are [v] (data/eq? v (json/decode (json/encode v)))
        nil
        true
        false
        42
        "foobar"
        bytes
        (data/cid-link bytes)
        {:$type "blob"
         :ref (data/blob-ref bytes)
         :mimeType "text/plain"
         :size 42}))))

(deftest test-bytes-base64
  (let [b (crypto/base64-decode fixture-bytes-b64)]

    (testing "decodes to 32 bytes"
      (is (= 32 #?(:clj (alength ^bytes b) :cljs (.-length b)))))

    (testing "re-encodes without padding to the identical string"
      (is (= fixture-bytes-b64 (crypto/base64-encode b))))

    (testing "padded input also decodes"
      (is (bytes/eq? b (crypto/base64-decode (str fixture-bytes-b64 "=")))))

    (testing "invalid input decodes to nil"
      (are [s] (nil? (crypto/base64-decode s))
        "!!!"
        "a"     ;; impossible length
        "ab=c"  ;; padding in the middle
        nil))

    (testing "$bytes JSON form is unpadded"
      (let [{:keys [$bytes]} (json/encode b)]
        (is (= fixture-bytes-b64 $bytes))
        (is (not (str/ends-with? $bytes "=")))))))

(deftest test-legacy-blob-passthrough
  ;; Legacy untyped blob refs are deliberately NOT special-cased by the
  ;; JSON codec (TS parity): they round-trip as plain maps.
  (let [legacy {:cid "bafkreiccldh766hwcnuxnf2wh6jgzepf2nlu2lvcllt63eww5p6chi4ity"
                :mimeType "image/jpeg"}]
    (is (= legacy (json/encode legacy)))
    (is (= legacy (json/decode legacy)))
    (is (= {:record legacy} (json/decode (json/encode {:record legacy}))))
    (is (data/legacy-blob? (json/decode legacy)))))
