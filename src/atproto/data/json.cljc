(ns atproto.data.json
  "Textual JSON representation at atproto data.

  See https://atproto.com/specs/data-model"
  (:require [clojure.spec.alpha :as s]
            [atproto.runtime.crypto :as crypto]
            [atproto.data :as data]
            ;; aliases for spec
            [atproto.data.json.bytes :as-alias bytes]
            [atproto.data.json.link :as-alias link]
            [atproto.data.json.blob :as-alias blob]))

;; -----------------------------------------------------------------------------
;; Validation
;; -----------------------------------------------------------------------------

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

(s/def ::null    ::data/null)
(s/def ::boolean ::data/boolean)
(s/def ::integer ::data/integer)
(s/def ::string  ::data/string)

(s/def ::bytes  (s/keys :req-un [::bytes/$bytes]))
(s/def ::bytes/$bytes #(crypto/base64-decode %))

(s/def ::link  (s/keys :req-un [::link/$link]))
(s/def ::link/$link #(data/cid-link? (data/parse-cid %)))

(s/def ::blob
  (s/keys :req-un [::blob/$type
                   ::blob/ref
                   ::blob/mimeType
                   ::blob/size]))
(s/def ::blob/$type #{"blob"})
(s/def ::blob/ref #(data/blob-ref? (data/parse-cid %)))
(s/def ::blob/size pos-int?)
(s/def ::blob/mimeType ::data/mime-type)

(s/def ::array (s/coll-of ::value))
(s/def ::object (s/map-of ::data/key ::value))

;; -----------------------------------------------------------------------------
;; Encoding & Decoding
;; -----------------------------------------------------------------------------

(defn encode
  "Take valid atproto data and return the JSON value."
  [data]
  (cond
    (nil? data)        data
    (boolean? data)    data
    (int? data)        data
    (string? data)     data
    (bytes? data)      {:$bytes (crypto/base64-encode data)}
    (data/cid? data)   {:$link (data/format-cid data)}
    (vector? data)     (mapv encode data)
    (map? data)        (into {} (map (fn [[k v]] [k (encode v)]) data))))

(defn decode
  "Take a valid JSON value and return the atproto data."
  [value]
  (cond
    (nil? value)     value
    (boolean? value) value
    (int? value)     value
    (string? value)  value
    (vector? value)  (mapv decode value)
    (map? value)     (cond
                       (contains? value :$bytes) (crypto/base64-decode (:$bytes value))
                       (contains? value :$link)  (data/parse-cid (:$link value))
                       :else                     (into {} (map (fn [[k v]] [k (decode v)]) value)))))
