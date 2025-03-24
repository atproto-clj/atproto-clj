(ns atproto.data.json-test
  (:require #?(:clj [clojure.test :refer :all])
            [clojure.spec.alpha :as s]
            [atproto.data :as data]
            [atproto.data.json :as json]))

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
