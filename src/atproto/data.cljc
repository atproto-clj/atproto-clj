(ns atproto.data
  "Records and messages in atproto are stored, transmitted, encoded, and authenticated in a consistent way.

  The core \"data model\" supports both binary (CBOR) and textual (JSON) representations.

  See https://atproto.com/specs/data-model"
  (:require [clojure.math :as math]
            [clojure.spec.alpha :as s]
            [multiformats.cid :as cid]
            [multiformats.hash :as mhash]
            [atproto.runtime.bytes :as bytes]
            [atproto.data.cbor :as cbor]
            [atproto.lexicon.regex :as regex]
            [atproto.data.blob :as-alias blob]
            [atproto.data.legacy-blob :as-alias legacy-blob]))

;; -----------------------------------------------------------------------------
;; CID: https://atproto.com/specs/data-model#link-and-cid-formats
;; -----------------------------------------------------------------------------

(defn cid?
  "Whether the input is a CID."
  [input]
  (cid/cid? input))

(defn cid-link
  "CID for already-encoded DAG-CBOR bytes. For un-encoded data, use cid-for.

  multicodec: `dag-cbor` (0x71)
  multihash: `sha-256` with 256 bits."
  [cbor-bytes]
  (cid/create :ipld-cbor
              (mhash/sha2-256 cbor-bytes)))

(defn cid-link?
  "Whether the cid is a valid CID for atproto links."
  [cid]
  (and (= 1 (:version cid))
       (= :ipld-cbor (:codec cid))
       (= :sha2-256 (:algorithm (:hash cid)))))

(defn blob-ref
  "Create a CID to be used as a reference in a `blob`.

  multicodec: `raw` (0x55)
  multihash: `sha-256` with 256 bits."
  [bytes]
  (cid/create :raw
              (mhash/sha2-256 bytes)))

(defn blob-ref?
  "Whether the cid is a valid CID to atproto blob references."
  [cid]
  (and (= 1 (:version cid))
       (= :raw (:codec cid))
       (= :sha2-256 (:algorithm (:hash cid)))))

(defn encode-cid
  "cid -> bytes"
  [cid]
  (cid/encode cid))

(defn decode-cid
  "bytes -> cid"
  [bytes]
  (cid/decode bytes))

(defn format-cid
  "cid -> string (base32)"
  [cid]
  (cid/format :base32 cid))

(defn parse-cid
  "string -> cid

  Return nil if the CID can't be parsed."
  [s]
  (try
    (cid/parse s)
    (catch #?(:clj Exception
              :cljs js/Error) _)))

(defn cid-for
  "Compute the CID of atproto data: DAG-CBOR encode, sha2-256,
  CIDv1 with the dag-cbor (0x71) multicodec.

  Throws (as atproto.data.cbor/encode) if data is not valid
  atproto data."
  [data]
  (cid/create :ipld-cbor (mhash/sha2-256 (cbor/encode data))))

(defn verify-cid
  "Verify that cid matches the given bytes.

  Hashes bytes with the cid's multihash algorithm (sha2-256 or
  sha2-512) and compares digests.

  Returns true on match, otherwise an error map:
  {:error \"CidMismatch\" :message <...> :expected <cid-str> :actual <cid-str>}
  {:error \"UnsupportedHashAlgorithm\" :message <...>}"
  [cid bytes]
  (let [alg (:algorithm (:hash cid))]
    (if-let [hash-fn (case alg
                       :sha2-256 mhash/sha2-256
                       :sha2-512 mhash/sha2-512
                       nil)]
      (let [computed (hash-fn bytes)]
        (or (= computed (:hash cid))
            {:error "CidMismatch"
             :message "CID does not match the bytes."
             :expected (cid/format cid)
             :actual (cid/format (cid/create (:codec cid) computed))}))
      {:error "UnsupportedHashAlgorithm"
       :message (str "Unsupported CID multihash algorithm: " (pr-str alg))})))

;; -----------------------------------------------------------------------------
;; Validation
;; -----------------------------------------------------------------------------

;; Limit integers because JS has only 53-bit precision
(def js-max-integer (long (math/pow 2 53)))

(defn js-safe-int? [i]
  (<= (- js-max-integer) i (dec js-max-integer)))

(s/def ::mime-type
  (s/and string?
         #(re-matches regex/mime-type %)))

(s/def ::null nil?)
(s/def ::boolean boolean?)
(s/def ::integer (s/and int?
                        js-safe-int?))
(s/def ::string string?)
(s/def ::bytes bytes/bytes?)
(s/def ::link cid-link?)

(s/def ::blob
  (s/keys :req-un [::blob/$type
                   ::blob/ref
                   ::blob/mimeType
                   ::blob/size]))

(s/def ::blob/$type #{"blob"})
(s/def ::blob/ref blob-ref?)
(s/def ::blob/mimeType ::mime-type)
(s/def ::blob/size pos-int?)

(s/def ::array
  (s/coll-of ::value))

(s/def ::key
  (s/and keyword?
         #(not (qualified-keyword? %))))

(def reserved-type? #{"blob"})

(s/def ::object
  (s/and map?
         #(not (reserved-type? (:$type %)))
         (s/map-of ::key ::value)))

(s/def ::value
  (s/nonconforming
   (s/or :null    ::null
         :boolean ::boolean
         :integer ::integer
         :string  ::string
         :bytes   ::bytes
         :link    ::link
         :blob    ::blob
         :array   ::array
         :object  ::object)))

;; -----------------------------------------------------------------------------
;; Legacy untyped blob refs
;;
;; Older atproto records reference blobs as {:cid <cid-string> :mimeType <str>}
;; instead of the typed form. The JSON and CBOR codecs deliberately pass these
;; through as plain maps (TS parity); validation/upgrade is the application's
;; (or the lexicon lenient mode's) concern.
;; -----------------------------------------------------------------------------

(s/def ::legacy-blob/cid
  (s/and string? parse-cid))

(s/def ::legacy-blob/mimeType
  (s/and string? seq))

(s/def ::legacy-blob
  (s/and (s/keys :req-un [::legacy-blob/cid
                          ::legacy-blob/mimeType])
         #(= #{:cid :mimeType} (set (keys %)))))

(defn legacy-blob?
  "Whether the value is a legacy untyped blob ref."
  [v]
  (s/valid? ::legacy-blob v))

(defn upgrade-legacy-blob
  "Convert a legacy untyped blob ref to the typed form.

  Returns {:$type \"blob\" :ref <parsed CID> :mimeType <mimeType> :size -1},
  or nil if input is not a valid legacy blob ref."
  [m]
  (when (legacy-blob? m)
    {:$type "blob"
     :ref (parse-cid (:cid m))
     :mimeType (:mimeType m)
     :size -1}))

;; -----------------------------------------------------------------------------
;; Equality
;; -----------------------------------------------------------------------------

;; Decision (WS-02): the data model keeps raw platform byte arrays as its
;; bytes representation — no immutable wrapper type. A wrapper would ripple
;; through every spec, codec, and platform boundary (hashing, HTTP bodies,
;; websockets), and the TS SDK uses raw Uint8Array the same way. The cost is
;; that platform arrays use identity equality, so values containing bytes
;; don't compare with `=`; this `eq?` is the structural-equality entry point
;; for atproto data values.
(defn eq?
  [a b]
  (or (= a b)
      (and (bytes/bytes? a)
           (bytes/bytes? b)
           (bytes/eq? a b))
      (and (vector? a)
           (vector? b)
           (every? #(apply eq? %) (map vector a b)))
      (and (map? a)
           (map? b)
           (= (set (keys a))
              (set (keys b)))
           (every? #(eq? (a %) (b %)) (keys a)))))
