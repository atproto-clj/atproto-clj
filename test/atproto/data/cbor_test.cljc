(ns atproto.data.cbor-test
  "Tests for the DAG-CBOR codec.

  Live verification (manual, not CI): fetch any public record and check
  CID parity against the PDS, e.g.

      GET https://bsky.social/xrpc/com.atproto.repo.getRecord?repo=<did>
            &collection=app.bsky.feed.post&rkey=<rkey>

  then check (= cid (data/format-cid (data/cid-for (json/decode value)))).
  Last performed 2026-06-11 against
  at://did:plc:z72i7hdynmk6r22z27h6tvur/app.bsky.feed.post/3mnslrkd6ok2g
  (cid bafyreigh4yjzzjiwjrlm4xe5yiu456ncqc6tfnf5ctup2sii7knhdineru): parity true."
  (:require #?@(:clj [[clojure.test :refer :all]
                      [clojure.java.io :as io]]
                :cljs [[cljs.test :refer :all]])
            [clojure.string :as str]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [atproto.runtime.bytes :as bytes]
            [atproto.runtime.crypto :as crypto]
            #?(:clj [atproto.runtime.json :as runtime.json])
            [atproto.data :as data]
            [atproto.data.json :as json]
            [atproto.data.cbor :as cbor])
  #?(:cljs (:require-macros [atproto.data.cbor-test :refer [data-model-fixtures]])))

;; -----------------------------------------------------------------------------
;; Helpers
;; -----------------------------------------------------------------------------

#?(:clj
   (defmacro data-model-fixtures
     "Inline the vendored data-model interop fixtures.

      Each fixture is {:json <value> :cbor_base64 <str> :cid <str>}.
      Source: bluesky-social/atproto packages/lex/lex-cbor/tests/data-model-fixtures.json"
     []
     (runtime.json/read-str
      (slurp (io/resource "interop-test-files/data-model/data-model-fixtures.json")))))

(defn hex->bytes
  [s]
  (let [ints (map (fn [[a b]]
                    #?(:clj (Integer/parseInt (str a b) 16)
                       :cljs (js/parseInt (str a b) 16)))
                  (partition 2 s))]
    #?(:clj (byte-array (map unchecked-byte ints))
       :cljs (js/Int8Array.from (into-array ints)))))

(defn bytes->hex
  [b]
  (let [n #?(:clj (alength ^bytes b) :cljs (.-length b))]
    (apply str
           (map (fn [i]
                  (let [x (bit-and #?(:clj (aget ^bytes b i) :cljs (aget b i)) 0xff)]
                    #?(:clj (format "%02x" x)
                       :cljs (.padStart (.toString x 16) 2 "0"))))
                (range n)))))

(defn concat-bytes
  [a b]
  #?(:clj (byte-array (concat (seq a) (seq b)))
     :cljs (js/Int8Array.from (concat (array-seq a) (array-seq b)))))

(defn- blen
  [b]
  #?(:clj (alength ^bytes b)
     :cljs (.-length b)))

(defn- slice-bytes
  [b start end]
  #?(:clj (byte-array (->> (seq b) (drop start) (take (- end start))))
     :cljs (.slice b start end)))

(defn- invalid-cbor?
  "Whether decoding the hex throws an InvalidCbor error."
  [decode-fn hex]
  (try
    (decode-fn (hex->bytes hex))
    false
    (catch #?(:clj Exception :cljs js/Error) e
      (= "InvalidCbor" (:error (ex-data e))))))

(defn- invalid-data-model?
  "Whether encoding the value throws an InvalidDataModel error."
  [v]
  (try
    (cbor/encode v)
    false
    (catch #?(:clj Exception :cljs js/Error) e
      (= "InvalidDataModel" (:error (ex-data e))))))

;; -----------------------------------------------------------------------------
;; Vendored interop fixtures
;; -----------------------------------------------------------------------------

(deftest test-interop-fixtures
  (doseq [{:keys [json cbor_base64 cid]} (data-model-fixtures)]
    (let [value (json/decode json)
          cbor-bytes (crypto/base64-decode cbor_base64)]
      (testing "encode is byte-exact"
        (is (= (bytes->hex cbor-bytes)
               (bytes->hex (cbor/encode value)))))
      (testing "cid-for matches the fixture CID"
        (is (= cid (data/format-cid (data/cid-for value)))))
      (testing "cid-link of the encoded bytes matches the fixture CID"
        (is (= cid (data/format-cid (data/cid-link cbor-bytes)))))
      (testing "decode-multi yields exactly one value that round-trips to JSON"
        (let [decoded (cbor/decode-multi cbor-bytes)]
          (is (= 1 (count decoded)))
          (is (= json (json/encode (first decoded))))))
      (testing "verify-cid against the original bytes"
        (is (true? (data/verify-cid (data/parse-cid cid) cbor-bytes)))))))

;; -----------------------------------------------------------------------------
;; Canonical form
;; -----------------------------------------------------------------------------

(deftest test-canonical-encoding
  (testing "simple map"
    (is (= "a16568656c6c6f65776f726c64"
           (bytes->hex (cbor/encode {:hello "world"})))))

  (testing "scalars"
    (are [hex v] (= hex (bytes->hex (cbor/encode v)))
      "f6" nil
      "f5" true
      "f4" false
      "60" ""
      "6161" "a"
      "40" #?(:clj (byte-array 0) :cljs (js/Int8Array. 0))
      "80" []
      "a0" {}))

  (testing "shortest-form integer heads across boundaries"
    (are [hex n] (= hex (bytes->hex (cbor/encode n)))
      "00" 0
      "17" 23
      "1818" 24
      "18ff" 255
      "190100" 256
      "19ffff" 65535
      "1a00010000" 65536
      "1affffffff" 4294967295
      "1b0000000100000000" 4294967296
      "1b001fffffffffffff" 9007199254740991
      "20" -1
      "37" -24
      "3818" -25
      "38ff" -256
      "390100" -257
      "3b001ffffffffffffe" -9007199254740991))

  (testing "map keys sorted by length first, then bytewise"
    ;; "b" and "c" (1 byte) sort before "aa" (2 bytes)
    (is (= "a361620161630362616102"
           (bytes->hex (cbor/encode {:aa 2 :b 1 :c 3}))))
    ;; multi-byte UTF-8 key: "zz" (7a7a) sorts before "é" (c3a9)
    (is (= "a2627a7a0262c3a901"
           (bytes->hex (cbor/encode {:é 1 :zz 2})))))

  (testing "string keys and keyword keys encode identically"
    (is (= (bytes->hex (cbor/encode {:hello "world"}))
           (bytes->hex (cbor/encode {"hello" "world"})))))

  (testing "tag 42 byte pattern appears once per link"
    (let [cid (data/parse-cid "bafyreidfayvfuwqa7qlnopdjiqrxzs6blmoeu4rujcjtnci5beludirz2a")
          hex (bytes->hex (cbor/encode {:link cid :links [cid cid]}))]
      (is (= 3 (count (re-seq #"d82a582500" hex)))))))

;; -----------------------------------------------------------------------------
;; Encode rejections
;; -----------------------------------------------------------------------------

(deftest test-encode-rejections
  (testing "floats, NaN, Infinity"
    (are [v] (invalid-data-model? v)
      3.14
      {:value 3.14}
      ##NaN
      ##Inf
      ##-Inf
      [1 -1 9007199254740991 ##NaN -9007199254740991]))

  (testing "integers out of the ±(2^53 - 1) range"
    (are [v] (invalid-data-model? v)
      9007199254740992
      -9007199254740992)
    (is (some? (cbor/encode 9007199254740991)))
    (is (some? (cbor/encode -9007199254740991))))

  (testing "invalid map keys"
    (are [v] (invalid-data-model? v)
      {:a/b 1}
      {1 2}
      {:a 1 "a" 2}))

  (testing "unsupported value types"
    (are [v] (invalid-data-model? v)
      :keyword-value
      #{1 2}
      'sym
      #?(:clj 1N)
      #?(:clj (java.util.Date.) :cljs (js/Date.))))

  (testing "ex-data carries the path into the data"
    (let [ex-data' (try
                     (cbor/encode {:a [{:b 3.14}]})
                     nil
                     (catch #?(:clj Exception :cljs js/Error) e
                       (ex-data e)))]
      (is (= "InvalidDataModel" (:error ex-data')))
      (is (= [:a 0 :b] (:path ex-data'))))))

;; -----------------------------------------------------------------------------
;; Decode strictness (vectors ported from lex-cbor's dag-cbor.test.ts)
;; -----------------------------------------------------------------------------

(deftest test-decode-rejections
  (testing "floats (incl. NaN and ±Infinity forms)"
    (are [hex] (invalid-cbor? cbor/decode hex)
      "f97e00"
      "f97ff8"
      "fa7ff80000"
      "fb7ff8000000000000"
      "a2616161616162fb7ff8000000000000"
      "f97c00"
      "fb7ff0000000000000"
      "a2616161616162fb7ff0000000000000"
      "f9fc00"
      "fbfff0000000000000"
      "a2616161616162fbfff0000000000000"
      ;; ordinary floats are rejected too (stricter than TS, per the
      ;; atproto data-model spec)
      "f93e00"
      "fa40490fdb"
      "fb40091eb851eb851f"))

  (testing "duplicate map keys"
    (are [hex] (invalid-cbor? cbor/decode hex)
      "a3636261720363666f6f0163666f6f02"))

  (testing "bad CID lead-in byte (expected 0x00)"
    (are [hex] (invalid-cbor? cbor/decode hex)
      "a1646c696e6bd82a582501017012207252523e6591fb8fe553d67ff55a86f84044b46a3e4176e10c58fa529a4aabd5"))

  (testing "unsupported tags and simple values"
    (are [hex] (invalid-cbor? cbor/decode hex)
      "c100"   ;; tag 1
      "d82a00" ;; tag 42 wrapping a non-byte-string
      "d82a40" ;; tag 42 wrapping an empty byte string
      "f0"     ;; simple value 16
      "f818"   ;; simple value 24
      "ff"))   ;; break code outside indefinite item

  (testing "indefinite lengths"
    (are [hex] (invalid-cbor? cbor/decode hex)
      "5fff"
      "7fff"
      "9fff"
      "9f0102ff"
      "bfff"))

  (testing "non-shortest-form encodings"
    (are [hex] (invalid-cbor? cbor/decode hex)
      "1817"               ;; 23 in one extra byte
      "1900ff"             ;; 255 in two bytes
      "1a0000ffff"         ;; 65535 in four bytes
      "1b00000000ffffffff" ;; 2^32-1 in eight bytes
      "5803010203"))       ;; byte-string length 3 in long form

  (testing "truncated input"
    (are [hex] (invalid-cbor? cbor/decode hex)
      ""
      "18"
      "a1"
      "5820ff"
      "61"
      "8101a1"))

  (testing "trailing bytes after a single decode"
    (is (invalid-cbor? cbor/decode "0000"))
    (is (= 0 (cbor/decode (hex->bytes "00")))))

  (testing "integers outside the platform range"
    #?(:clj (do (is (= 9223372036854775807 (cbor/decode (hex->bytes "1b7fffffffffffffff"))))
                (is (= -9223372036854775808 (cbor/decode (hex->bytes "3b7fffffffffffffff"))))
                (is (invalid-cbor? cbor/decode "1b8000000000000000"))
                (is (invalid-cbor? cbor/decode "1bffffffffffffffff"))
                (is (invalid-cbor? cbor/decode "3b8000000000000000")))
       :cljs (do (is (= 9007199254740991 (cbor/decode (hex->bytes "1b001fffffffffffff"))))
                 (is (invalid-cbor? cbor/decode "1b0020000000000000"))))))

(deftest test-decode-coercions
  (testing "0xf7 (undefined) is coerced to nil"
    (is (nil? (cbor/decode (hex->bytes "f7"))))
    (is (= {:foo "bar" :baz nil}
           (cbor/decode (hex->bytes "a26362617af763666f6f63626172"))))))

(deftest test-decode-values
  (testing "simple map"
    (is (= {:hello "world"}
           (cbor/decode (hex->bytes "a16568656c6c6f65776f726c64")))))

  (testing "tag 42 decodes to a CID"
    (let [cid (data/parse-cid "bafyreidfayvfuwqa7qlnopdjiqrxzs6blmoeu4rujcjtnci5beludirz2a")
          decoded (cbor/decode (cbor/encode {:link cid}))]
      (is (data/cid? (:link decoded)))
      (is (= cid (:link decoded)))))

  (testing "decode does not enforce sorted map keys"
    ;; {"aa" 2, "b" 1} in non-canonical key order
    (is (= {:aa 2 :b 1}
           (cbor/decode (hex->bytes "a262616102616201")))))

  (testing "slash as map key round-trips (TS parity; :/ is unqualified)"
    (let [v {(keyword "/") true}]
      (is (= v (cbor/decode (cbor/encode v)))))))

;; -----------------------------------------------------------------------------
;; decode-first / decode-multi
;; -----------------------------------------------------------------------------

(deftest test-decode-first-and-multi
  (let [one {:a 123
             :b (data/parse-cid "bafyreidfayvfuwqa7qlnopdjiqrxzs6blmoeu4rujcjtnci5beludirz2a")}
        two {:c (hex->bytes "010203")
             :d "hello"}
        b1 (cbor/encode one)
        b2 (cbor/encode two)
        both (concat-bytes b1 b2)]

    (testing "decode-first returns the first value and bytes consumed"
      (let [[v consumed] (cbor/decode-first both)]
        (is (data/eq? one v))
        (is (= #?(:clj (alength ^bytes b1) :cljs (.-length b1)) consumed))))

    (testing "decode-multi decodes concatenated items in order"
      (let [[v1 v2 :as vs] (cbor/decode-multi both)]
        (is (= 2 (count vs)))
        (is (data/eq? one v1))
        (is (data/eq? two v2))))

    (testing "decode rejects the concatenation"
      (is (thrown? #?(:clj Exception :cljs js/Error) (cbor/decode both))))

    (testing "truncated second item throws"
      (let [truncated (concat-bytes
                       b1
                       #?(:clj (byte-array (butlast (seq b2)))
                          :cljs (.slice b2 0 (dec (.-length b2)))))]
        (is (thrown? #?(:clj Exception :cljs js/Error)
                     (cbor/decode-multi truncated)))))

    (testing "decode-multi of empty input throws"
      (is (thrown? #?(:clj Exception :cljs js/Error)
                   (cbor/decode-multi (hex->bytes "")))))))

;; -----------------------------------------------------------------------------
;; Generative round-trip
;; -----------------------------------------------------------------------------

(def gen-bytes
  (gen/fmap #(#?(:clj byte-array :cljs js/Int8Array.from) %)
            (gen/vector (gen/choose -128 127))))

(def gen-cid
  (gen/fmap #(data/cid-link #?(:clj (byte-array %) :cljs (js/Int8Array.from %)))
            (gen/vector (gen/choose -128 127) 1 32)))

(def multibyte-fragments
  "UTF-8 torture fragments: 2/3/4-byte sequences, astral-plane characters,
  combining marks, and a multi-codepoint grapheme cluster (emoji ZWJ)."
  ["é" "ñ" "©" "⽘" "☎" "𓋓" "😀" "👨‍👩‍👧‍👧" "中文" "العربية" "𝒜" "ﬃ" "é"])

(def gen-unicode-string
  (gen/fmap str/join
            (gen/vector (gen/one-of [(gen/fmap str gen/char-ascii)
                                     (gen/elements multibyte-fragments)])
                        0 20)))

(def gen-scalar
  (gen/one-of [(gen/return nil)
               gen/boolean
               gen/int
               (gen/choose -9007199254740991 9007199254740991)
               gen/string
               gen-unicode-string
               gen-bytes
               gen-cid]))

(def gen-key
  (gen/fmap keyword (gen/not-empty gen/string-alphanumeric)))

(def gen-value
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of [(gen/vector inner)
                  (gen/map gen-key inner)]))
   gen-scalar))

(defspec round-trip-decode-of-encode 100
  (prop/for-all [v gen-value]
    (data/eq? v (cbor/decode (cbor/encode v)))))

(defspec round-trip-canonical-stability 100
  (prop/for-all [v gen-value]
    (let [encoded (cbor/encode v)]
      (= (bytes->hex encoded)
         (bytes->hex (cbor/encode (cbor/decode encoded)))))))

;; -----------------------------------------------------------------------------
;; Insertion-order insensitivity (canonical key sort)
;; -----------------------------------------------------------------------------

(defn- array-map-of
  "Build an array-map (which preserves insertion order) from [k v] entries."
  [entries]
  (apply array-map (mapcat identity entries)))

(def gen-map-with-permutation
  (gen/bind (gen/map gen-key gen-scalar)
            (fn [m]
              (gen/fmap (fn [perm] [(vec m) perm])
                        (gen/shuffle (vec m))))))

(defspec map-insertion-order-insensitivity 100
  (prop/for-all [[entries perm] gen-map-with-permutation]
    (= (bytes->hex (cbor/encode (array-map-of entries)))
       (bytes->hex (cbor/encode (array-map-of perm))))))

;; -----------------------------------------------------------------------------
;; CID determinism (the property MST/commit code depends on)
;; -----------------------------------------------------------------------------

(defspec cid-for-determinism 100
  (prop/for-all [v gen-value]
    (= (data/format-cid (data/cid-for v))
       (data/format-cid (data/cid-for (cbor/decode (cbor/encode v)))))))

;; -----------------------------------------------------------------------------
;; decode-first / decode-multi composition
;; -----------------------------------------------------------------------------

(defspec decode-multi-matches-folded-decode-first 50
  (prop/for-all [vs (gen/vector gen-value 1 5)]
    (let [encs (mapv cbor/encode vs)
          buf (reduce concat-bytes encs)
          multi (cbor/decode-multi buf)
          [firsts lens] (loop [b buf
                               firsts []
                               lens []]
                          (if (zero? (blen b))
                            [firsts lens]
                            (let [[v n] (cbor/decode-first b)]
                              (recur (slice-bytes b n (blen b))
                                     (conj firsts v)
                                     (conj lens n)))))]
      (and (= (count vs) (count multi) (count firsts))
           (every? true? (map data/eq? vs multi))
           (every? true? (map data/eq? vs firsts))
           ;; decode-first consumes exactly the per-item encoded lengths
           (= (mapv blen encs) lens)))))

;; -----------------------------------------------------------------------------
;; Every strict prefix of a valid item fails to decode
;; -----------------------------------------------------------------------------

(def gen-encoded-with-prefix-length
  (gen/bind gen-value
            (fn [v]
              (let [b (cbor/encode v)]
                (gen/fmap (fn [i] [b i])
                          (gen/choose 0 (dec (blen b))))))))

(defspec strict-prefix-always-throws 100
  (prop/for-all [[b i] gen-encoded-with-prefix-length]
    (try
      (cbor/decode (slice-bytes b 0 i))
      false
      (catch #?(:clj Exception :cljs js/Error) e
        (= "InvalidCbor" (:error (ex-data e)))))))

;; -----------------------------------------------------------------------------
;; Garbage fuzzing: decode is total — InvalidCbor or a normalizable value,
;; never an unexpected exception
;; -----------------------------------------------------------------------------

(def gen-garbage
  (gen/fmap #(#?(:clj byte-array :cljs js/Int8Array.from) %)
            (gen/vector (gen/choose -128 127) 1 100)))

(defspec garbage-decode-is-total 200
  (prop/for-all [b gen-garbage]
    (try
      (let [v (cbor/decode b)]
        ;; rare: random bytes happened to be valid CBOR; the value must
        ;; normalize cleanly...
        (try
          (true? (data/eq? v (cbor/decode (cbor/encode v))))
          (catch #?(:clj Exception :cljs js/Error) e
            ;; ...except that decode accepts the full int64 range on the
            ;; JVM while encode enforces the JS-safe range
            (= "InvalidDataModel" (:error (ex-data e))))))
      (catch #?(:clj Exception :cljs js/Error) e
        (= "InvalidCbor" (:error (ex-data e)))))))

;; -----------------------------------------------------------------------------
;; UTF-8 torture: multi-byte strings as values and as map keys, where the
;; byte-length-vs-char-length distinction matters for the canonical key sort
;; -----------------------------------------------------------------------------

(defspec unicode-string-round-trip 100
  (prop/for-all [s gen-unicode-string]
    (= s (cbor/decode (cbor/encode s)))))

(def gen-unicode-key
  ;; "/" is excluded: (keyword "a/b") is a *qualified* keyword, which is
  ;; outside the atproto data model (::data/key) and rejected by encode.
  (gen/fmap #(keyword (str/replace % "/" "_"))
            (gen/not-empty gen-unicode-string)))

(defspec unicode-map-keys-canonical-round-trip 100
  (prop/for-all [m (gen/map gen-unicode-key gen/int)]
    (let [encoded (cbor/encode m)]
      (and (true? (data/eq? m (cbor/decode encoded)))
           (= (bytes->hex encoded)
              (bytes->hex (cbor/encode (cbor/decode encoded))))))))
