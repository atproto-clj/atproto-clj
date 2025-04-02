(ns atproto.data
  "Records and messages in atproto are stored, transmitted, encoded, and authenticated in a consistent way.

  The core \"data model\" supports both binary (CBOR) and textual (JSON) representations.

  See https://atproto.com/specs/data-model"
  #?(:clj (:refer-clojure :exclude [format]))
  (:require [clojure.math :as math]
            [clojure.spec.alpha :as s]
            [multiformats.cid :as cid]
            [multiformats.hash :as mhash]
            [atproto.runtime.bytes :as bytes]
            [atproto.lexicon.regex :as regex]
            [atproto.data.blob :as-alias blob]))

;; -----------------------------------------------------------------------------
;; CID: https://atproto.com/specs/data-model#link-and-cid-formats
;; -----------------------------------------------------------------------------

(defn cid?
  "Whether the input is a CID."
  [input]
  (cid/cid? input))

(defn cid-link
  "Create a CID to be used as a `link`.

  multicodec: `dag-cbor` (0x71)
  multihash: `sha-256` with 256 bits."
  [bytes]
  (cid/create :ipld-cbor
              (mhash/create :sha2-256 bytes)))

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
              (mhash/create :sha2-256 bytes)))

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
;; Equality
;; -----------------------------------------------------------------------------

;; todo: consider introducing an immutables bytes type to not have to do that
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
