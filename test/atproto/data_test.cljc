(ns atproto.data-test
  (:require #?@(:clj [[clojure.test :refer :all]]
                :cljs [[cljs.test :refer :all]
                       [goog.crypt]])
            [clojure.spec.alpha :as s]
            [multiformats.cid :as cid]
            [multiformats.hash :as mhash]
            [atproto.data :as data]))

(defn- hex->bytes
  [s]
  (let [ints (map (fn [[a b]]
                    #?(:clj (Integer/parseInt (str a b) 16)
                       :cljs (js/parseInt (str a b) 16)))
                  (partition 2 s))]
    #?(:clj (byte-array (map unchecked-byte ints))
       :cljs (js/Int8Array.from (into-array ints)))))

(defn- utf8-bytes
  [s]
  #?(:clj (.getBytes ^String s "UTF-8")
     :cljs (js/Int8Array.from (goog.crypt/stringToUtf8ByteArray s))))

;; sha256("hello world"), independently computed
(def hello-world-sha256
  "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9")

(deftest test-validation

  (testing "Valid atproto data"
    (let [obj {:null nil
               :true true
               :false false
               :integer 1
               :string "foobar"
               :bytes (utf8-bytes "foobar")
               :link (data/cid-link (utf8-bytes "foobar"))
               :blob {:$type "blob"
                      :ref (data/blob-ref (utf8-bytes "foobar"))
                      :mimeType "text/plain"
                      :size 42}}]
      (is (s/valid? ::data/value (-> obj
                                     (assoc :array (vec (vals obj)))
                                     (assoc :object obj))))))

  (testing "Invalid atproto data"
    (are [d] (not (s/valid? ::data/value d))
      1N
      data/js-max-integer
      (dec (- data/js-max-integer))
      {:$type "blob"})))

(deftest test-cid-known-answers
  ;; These known-answer tests pin the fix for the mhash/create bug:
  ;; cid-link/blob-ref must *hash* the input, not wrap it as a digest.
  (let [content (utf8-bytes "hello world")
        digest (hex->bytes hello-world-sha256)]

    (testing "blob-ref hashes the content (raw 0x55, sha2-256)"
      (let [c (data/blob-ref content)]
        (is (= (cid/create :raw (mhash/create :sha2-256 digest)) c))
        (is (data/blob-ref? c))))

    (testing "cid-link hashes the bytes (dag-cbor 0x71, sha2-256)"
      (let [c (data/cid-link content)]
        (is (= (cid/create :ipld-cbor (mhash/create :sha2-256 digest)) c))
        (is (data/cid-link? c))))))

(deftest test-verify-cid
  (let [content (utf8-bytes "hello world")]

    (testing "sha2-256 match"
      (is (true? (data/verify-cid (data/blob-ref content) content)))
      (is (true? (data/verify-cid (data/cid-link content) content))))

    (testing "sha2-512 match"
      (is (true? (data/verify-cid (cid/create :raw (mhash/sha2-512 content))
                                  content))))

    (testing "mismatch"
      (let [other (utf8-bytes "something else")
            res (data/verify-cid (data/blob-ref content) other)]
        (is (= "CidMismatch" (:error res)))
        (is (= (data/format-cid (data/blob-ref content)) (:expected res)))
        (is (= (data/format-cid (data/blob-ref other)) (:actual res)))))

    (testing "unsupported hash algorithm"
      (let [res (data/verify-cid (cid/create :raw (mhash/md5 content)) content)]
        (is (= "UnsupportedHashAlgorithm" (:error res)))))))

(deftest test-legacy-blob
  (let [valid {:cid "bafkreiccldh766hwcnuxnf2wh6jgzepf2nlu2lvcllt63eww5p6chi4ity"
               :mimeType "image/jpeg"}]

    (testing "legacy-blob? accepts the legacy untyped shape"
      (is (data/legacy-blob? valid))
      (is (s/valid? ::data/legacy-blob valid)))

    (testing "legacy-blob? rejections"
      (are [v] (not (data/legacy-blob? v))
        nil
        "string"
        {}
        (assoc valid :extra "key")
        (assoc valid :cid "not-a-cid")
        (assoc valid :cid 42)
        (assoc valid :mimeType "")
        (dissoc valid :mimeType)
        (dissoc valid :cid)))

    (testing "upgrade-legacy-blob produces the typed form with :size -1"
      (let [{:keys [$type ref mimeType size] :as upgraded} (data/upgrade-legacy-blob valid)]
        (is (= "blob" $type))
        (is (= (data/parse-cid (:cid valid)) ref))
        (is (= "image/jpeg" mimeType))
        (is (= -1 size))
        (is (= #{:$type :ref :mimeType :size} (set (keys upgraded))))))

    (testing "upgrade-legacy-blob returns nil on invalid input"
      (is (nil? (data/upgrade-legacy-blob {:cid "not-a-cid" :mimeType "image/jpeg"})))
      (is (nil? (data/upgrade-legacy-blob nil))))))
