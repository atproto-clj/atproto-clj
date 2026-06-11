(ns atproto.data.cbor
  "DAG-CBOR codec for the atproto data model subset.

  Canonical form: definite lengths only, shortest-form integers,
  map keys sorted length-first then bytewise; CID links as tag 42
  with 0x00 identity-multibase prefix; no floats.

  Invalid input throws ex-info whose ex-data is an SDK-style error map
  ({:error \"InvalidDataModel\"} on encode, {:error \"InvalidCbor\"} on
  decode). Errors are thrown rather than returned because decoded data
  is arbitrary and {:error ...} is itself a valid atproto data value.

  Note: decode does not enforce sorted map keys (matching the TS
  reference), so decode->encode of non-canonical third-party bytes may
  not be byte-identical. Verification code must always hash the
  original bytes, never re-encoded ones.

  See https://atproto.com/specs/data-model and
  https://ipld.io/specs/codecs/dag-cbor/spec/"
  (:require [multiformats.cid :as cid]
            [atproto.runtime.bytes :as bytes]
            #?@(:cljs [[goog.crypt]]))
  #?(:clj (:import [java.io ByteArrayOutputStream]
                   [java.util Arrays]
                   [java.nio.charset StandardCharsets])))

#?(:clj (set! *warn-on-reflection* true))

;; Integers must stay within the JS safe range: ±(2^53 - 1).
(def ^:private max-safe-int 9007199254740991)

(def ^:private cid-cbor-tag 42)

;; -----------------------------------------------------------------------------
;; Errors
;; -----------------------------------------------------------------------------

(defn- encode-error!
  [message path]
  (throw (ex-info message {:error "InvalidDataModel"
                           :message message
                           :path path})))

(defn- decode-error!
  [message offset]
  (throw (ex-info message {:error "InvalidCbor"
                           :message message
                           :offset offset})))

;; -----------------------------------------------------------------------------
;; Byte helpers
;; -----------------------------------------------------------------------------

(defn- blength
  [b]
  #?(:clj (alength ^bytes b)
     :cljs (.-length b)))

(defn- ubyte
  "Unsigned byte at index i."
  [b i]
  (bit-and #?(:clj (aget ^bytes b (int i))
              :cljs (aget b i))
           0xff))

(defn- bslice
  [b start end]
  #?(:clj (Arrays/copyOfRange ^bytes b (int start) (int end))
     :cljs (.slice b start end)))

(defn- utf8-bytes
  [s]
  #?(:clj (.getBytes ^String s StandardCharsets/UTF_8)
     :cljs (goog.crypt/stringToUtf8ByteArray s)))

;; -----------------------------------------------------------------------------
;; Encoder
;; -----------------------------------------------------------------------------

(defn- new-writer
  []
  #?(:clj (ByteArrayOutputStream.)
     :cljs #js []))

(defn- write-byte!
  [w b]
  #?(:clj (.write ^ByteArrayOutputStream w (unchecked-int b))
     :cljs (.push w (bit-and b 0xff))))

(defn- write-bytes!
  [w bs]
  #?(:clj (.write ^ByteArrayOutputStream w ^bytes bs 0 (alength ^bytes bs))
     :cljs (dotimes [i (.-length bs)]
             (.push w (bit-and (aget bs i) 0xff)))))

(defn- writer->bytes
  [w]
  #?(:clj (.toByteArray ^ByteArrayOutputStream w)
     :cljs (js/Int8Array.from w)))

(defn- write-u16!
  [w n]
  (write-byte! w (bit-and (unsigned-bit-shift-right n 8) 0xff))
  (write-byte! w (bit-and n 0xff)))

(defn- write-u32!
  [w n]
  (write-byte! w (bit-and (unsigned-bit-shift-right n 24) 0xff))
  (write-byte! w (bit-and (unsigned-bit-shift-right n 16) 0xff))
  (write-byte! w (bit-and (unsigned-bit-shift-right n 8) 0xff))
  (write-byte! w (bit-and n 0xff)))

(defn- write-head!
  "Write an item head: major type + shortest-form argument."
  [w major n]
  (let [mt (bit-shift-left major 5)]
    (cond
      (< n 24)
      (write-byte! w (bit-or mt n))

      (< n 0x100)
      (do (write-byte! w (bit-or mt 24))
          (write-byte! w n))

      (< n 0x10000)
      (do (write-byte! w (bit-or mt 25))
          (write-u16! w n))

      (< n 0x100000000)
      (do (write-byte! w (bit-or mt 26))
          (write-u32! w n))

      :else
      (do (write-byte! w (bit-or mt 27))
          ;; quot/rem instead of 64-bit shifts so this works on cljs doubles
          (write-u32! w (quot n 0x100000000))
          (write-u32! w (rem n 0x100000000))))))

(defn- compare-key-bytes
  "DAG-CBOR canonical map key order: byte length first, then bytewise
  (unsigned) comparison."
  [a b]
  (let [la (blength a)
        lb (blength b)]
    (if (not= la lb)
      (compare la lb)
      (loop [i 0]
        (if (= i la)
          0
          (let [ba (ubyte a i)
                bb (ubyte b i)]
            (if (= ba bb)
              (recur (inc i))
              (compare ba bb))))))))

(declare encode-value!)

(defn- encode-int!
  [w n path]
  (when-not (<= (- max-safe-int) n max-safe-int)
    (encode-error! (str "Integer out of range, must be within ±(2^53 - 1): " n) path))
  (if (neg? n)
    (write-head! w 1 (- -1 n))
    (write-head! w 0 n)))

(defn- encode-string!
  [w s]
  (let [b (utf8-bytes s)]
    (write-head! w 3 (blength b))
    (write-bytes! w b)))

(defn- encode-cid!
  [w c]
  (let [cb (cid/encode c)]
    (write-head! w 6 cid-cbor-tag)
    ;; 0x00 identity-multibase prefix, for historical reasons
    (write-head! w 2 (inc (blength cb)))
    (write-byte! w 0x00)
    (write-bytes! w cb)))

(defn- map-key->string
  [k path]
  (cond
    (string? k) k
    (and (keyword? k) (nil? (namespace k))) (name k)
    :else (encode-error! (str "Map keys must be strings or unqualified keywords: " (pr-str k))
                         path)))

(defn- encode-map!
  [w m path]
  (let [entries (mapv (fn [[k v]]
                        (let [ks (map-key->string k path)]
                          [(utf8-bytes ks) ks k v]))
                      m)]
    (when (not= (count entries)
                (count (into #{} (map second) entries)))
      (encode-error! "Duplicate map keys after string conversion" path))
    (write-head! w 5 (count entries))
    (doseq [[kb _ k v] (sort-by first compare-key-bytes entries)]
      (write-head! w 3 (blength kb))
      (write-bytes! w kb)
      (encode-value! w v (conj path k)))))

(defn- encode-array!
  [w v path]
  (write-head! w 4 (count v))
  (loop [i 0
         items (seq v)]
    (when items
      (encode-value! w (first items) (conj path i))
      (recur (inc i) (next items)))))

(defn- encode-value!
  [w v path]
  (cond
    (nil? v)         (write-byte! w 0xf6)
    (true? v)        (write-byte! w 0xf5)
    (false? v)       (write-byte! w 0xf4)
    (int? v)         (encode-int! w v path)
    (string? v)      (encode-string! w v)
    (bytes/bytes? v) (do (write-head! w 2 (blength v))
                         (write-bytes! w v))
    (cid/cid? v)     (encode-cid! w v)
    (map? v)         (encode-map! w v path)
    (sequential? v)  (encode-array! w v path)
    (number? v)      (encode-error! (str "Non-integer numbers are not supported: " v) path)
    :else            (encode-error! (str "Unsupported value type: " (pr-str (type v))) path)))

(defn encode
  "Encode atproto data to canonical DAG-CBOR bytes.

  Accepts: nil, booleans, integers in [-(2^53 - 1), 2^53 - 1], strings,
  platform bytes, CIDs (multiformats.cid), vectors/sequentials, and
  maps with unqualified-keyword (or string) keys.

  Returns platform bytes (clj: byte[]).
  Throws ex-info with ex-data {:error \"InvalidDataModel\"
                               :message <human-readable>
                               :path <vector path into data>}
  on floats, out-of-range ints, qualified/non-string keys, or any
  unsupported value type."
  #?(:clj ^bytes [data] :cljs [data])
  (let [w (new-writer)]
    (encode-value! w data [])
    (writer->bytes w)))

;; -----------------------------------------------------------------------------
;; Decoder
;; -----------------------------------------------------------------------------

(defn- check-len!
  "Ensure `need` bytes are available starting at `off`."
  [b off need]
  (when (> (+ off need) (blength b))
    (decode-error! "Unexpected end of input" off)))

(defn- read-arg
  "Read the argument of an item head (additional info `info` at offset
  `off`, head byte already consumed). Returns [arg new-off]. Enforces
  shortest-form encoding and platform integer range."
  [b off info]
  (cond
    (< info 24)
    [info off]

    (= info 24)
    (do (check-len! b off 1)
        (let [v (ubyte b off)]
          (when (< v 24)
            (decode-error! "Non-shortest-form argument encoding" (dec off)))
          [v (inc off)]))

    (= info 25)
    (do (check-len! b off 2)
        (let [v (+ (* (ubyte b off) 0x100)
                   (ubyte b (inc off)))]
          (when (< v 0x100)
            (decode-error! "Non-shortest-form argument encoding" (dec off)))
          [v (+ off 2)]))

    (= info 26)
    (do (check-len! b off 4)
        (let [v (+ (* (ubyte b off) 0x1000000)
                   (* (ubyte b (inc off)) 0x10000)
                   (* (ubyte b (+ off 2)) 0x100)
                   (ubyte b (+ off 3)))]
          (when (< v 0x10000)
            (decode-error! "Non-shortest-form argument encoding" (dec off)))
          [v (+ off 4)]))

    (= info 27)
    (do (check-len! b off 8)
        (let [hi (+ (* (ubyte b off) 0x1000000)
                    (* (ubyte b (inc off)) 0x10000)
                    (* (ubyte b (+ off 2)) 0x100)
                    (ubyte b (+ off 3)))
              lo (+ (* (ubyte b (+ off 4)) 0x1000000)
                    (* (ubyte b (+ off 5)) 0x10000)
                    (* (ubyte b (+ off 6)) 0x100)
                    (ubyte b (+ off 7)))]
          #?(:clj (when (<= 0x80000000 hi)
                    ;; would exceed the signed 64-bit range
                    (decode-error! "Integer out of range" (dec off)))
             :cljs (when (<= 0x200000 hi)
                     ;; would exceed the JS safe-integer range
                     (decode-error! "Integer out of range" (dec off))))
          (let [v (+ (* hi 0x100000000) lo)]
            #?(:cljs (when (> v max-safe-int)
                       (decode-error! "Integer out of range" (dec off))))
            (when (< v 0x100000000)
              (decode-error! "Non-shortest-form argument encoding" (dec off)))
            [v (+ off 8)])))

    (= info 31)
    (decode-error! "Indefinite lengths are not supported" (dec off))

    :else
    (decode-error! (str "Reserved additional info: " info) (dec off))))

(declare decode-at)

(defn- read-byte-string
  [b off len]
  (check-len! b off len)
  [(bslice b off (+ off len)) (+ off len)])

(defn- read-text
  [b off len]
  (check-len! b off len)
  [#?(:clj (String. ^bytes b (int off) (int len) StandardCharsets/UTF_8)
      :cljs (bytes/->utf8 (.slice b off (+ off len))))
   (+ off len)])

(defn- read-array
  [b off n]
  (loop [i 0
         off off
         acc (transient [])]
    (if (= i n)
      [(persistent! acc) off]
      (let [[v off'] (decode-at b off)]
        (recur (inc i) off' (conj! acc v))))))

(defn- read-map-key
  "Map keys must be text strings. Returns [string new-off]."
  [b off]
  (check-len! b off 1)
  (let [ib (ubyte b off)
        major (bit-shift-right ib 5)
        info (bit-and ib 0x1f)]
    (when (not= 3 major)
      (decode-error! "Map keys must be strings" off))
    (let [[len off'] (read-arg b (inc off) info)]
      (read-text b off' len))))

(defn- read-map
  [b off n]
  (loop [i 0
         off off
         acc (transient {})]
    (if (= i n)
      [(persistent! acc) off]
      (let [key-off off
            [ks off'] (read-map-key b off)
            k (keyword ks)]
        (when (contains? acc k)
          (decode-error! (str "Duplicate map key: " (pr-str ks)) key-off))
        (let [[v off''] (decode-at b off')]
          (recur (inc i) off'' (assoc! acc k v)))))))

(defn- read-tag
  [b off tag]
  (when (not= cid-cbor-tag tag)
    (decode-error! (str "Unsupported CBOR tag: " tag) off))
  (check-len! b off 1)
  (let [ib (ubyte b off)
        major (bit-shift-right ib 5)
        info (bit-and ib 0x1f)]
    (when (not= 2 major)
      (decode-error! "CBOR tag 42 must contain a byte string" off))
    (let [[len off'] (read-arg b (inc off) info)
          [payload off''] (read-byte-string b off' len)]
      (when (or (zero? len)
                (not (zero? (ubyte payload 0))))
        (decode-error! "Invalid CID for CBOR tag 42; expected leading 0x00" off))
      (let [cid (try
                  (cid/decode (bslice payload 1 len))
                  (catch #?(:clj Exception :cljs js/Error) e
                    (decode-error! (str "Invalid CID in CBOR tag 42: "
                                        #?(:clj (.getMessage e)
                                           :cljs (.-message e)))
                                   off)))]
        [cid off'']))))

(defn- read-simple
  "Major type 7: simple values and floats."
  [b off info]
  (case (long info)
    20 [false (inc off)]
    21 [true (inc off)]
    22 [nil (inc off)]
    23 [nil (inc off)] ;; undefined (0xf7) is coerced to null
    (25 26 27) (decode-error! "Floats are not supported" off)
    31 (decode-error! "Unexpected break code" off)
    (decode-error! (str "Unsupported simple value: " info) off)))

(defn- decode-at
  "Decode the item starting at `off`. Returns [value new-off]."
  [b off]
  (check-len! b off 1)
  (let [ib (ubyte b off)
        major (bit-shift-right ib 5)
        info (bit-and ib 0x1f)]
    (if (= 7 major)
      (read-simple b off info)
      (let [[arg off'] (read-arg b (inc off) info)]
        (case major
          0 [arg off']
          1 [(- -1 arg) off']
          2 (read-byte-string b off' arg)
          3 (read-text b off' arg)
          4 (read-array b off' arg)
          5 (read-map b off' arg)
          6 (read-tag b off' arg))))))

(defn decode
  "Decode a single DAG-CBOR item; rejects trailing bytes.

  Map keys become unqualified keywords; tag 42 becomes a CID;
  byte strings become platform bytes; 0xf7 (undefined) becomes nil.

  Throws ex-info with ex-data {:error \"InvalidCbor\"
                               :message <...> :offset <byte offset>}
  on floats, indefinite lengths, non-shortest-form encodings,
  duplicate map keys, unknown tags/simple values, bad tag-42 payloads
  (missing 0x00 prefix or invalid CID), truncation, or trailing bytes."
  [bytes]
  (let [[v off] (decode-at bytes 0)]
    (when (< off (blength bytes))
      (decode-error! "Unexpected trailing bytes after decoded item" off))
    v))

(defn decode-first
  "Decode the first DAG-CBOR item in bytes.

  Returns [value bytes-consumed]. Same strictness/errors as decode,
  except trailing bytes are expected and left for the caller."
  [bytes]
  (decode-at bytes 0))

(defn decode-multi
  "Decode a buffer of concatenated DAG-CBOR items (e.g. a firehose
  frame: header item followed by body item).

  Returns a vector of decoded values. Throws (as decode) if any item
  is invalid or the final item is truncated."
  [bytes]
  (loop [off 0
         acc []]
    (let [[v off'] (decode-at bytes off)
          acc (conj acc v)]
      (if (< off' (blength bytes))
        (recur (long off') acc)
        acc))))
