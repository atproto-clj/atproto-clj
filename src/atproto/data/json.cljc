(ns atproto.data.json
  "Textual JSON representation at atproto data.

  See https://atproto.com/specs/data-model"
  #?(:clj (:refer-clojure :exclude [bytes?]))
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [atproto.runtime.crypto :as crypto]
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.bytes :refer [bytes?]]
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
    (sequential? data) (mapv encode data)
    (map? data)        (into {} (map (fn [[k v]] [k (encode v)]) data))))

(defn decode
  "Take a valid JSON value and return the atproto data."
  [value]
  (cond
    (nil? value)        value
    (boolean? value)    value
    (int? value)        value
    (string? value)     value
    (sequential? value) (mapv decode value)
    (map? value)        (cond
                          (contains? value :$bytes) (crypto/base64-decode (:$bytes value))
                          (contains? value :$link)  (data/parse-cid (:$link value))
                          :else                     (into {} (map (fn [[k v]] [k (decode v)]) value)))
    ))

;; TODO: Assuming all JSON is atproto-flavored JSON may be problematic?
;;       See https://github.com/bluesky-social/atproto/discussions/3697
(defn atproto-flavored-json?
  [{:keys [headers]}]
  (when-let [ct (:content-type headers)]
    (str/starts-with? ct "application/json")))

(def client-interceptor
  "Encode/decode atproto data to the atproto-flavored json in HTTP request/response."
  {::i/name ::client-interceptor
   ::i/enter (fn [ctx]
               (update ctx
                       ::i/request
                       (fn [{:keys [body] :as request}]
                         (if (and body (atproto-flavored-json? request))
                           (update request :body encode)
                           request))))
   ::i/leave (fn leave-json [{:keys [::i/response] :as ctx}]
               (update ctx
                       ::i/response
                       (fn [{:keys [body] :as response}]
                         (if (and body (atproto-flavored-json? response))
                           (update response :body decode)
                           response))))})

(def server-interceptor
  "Decode/encode atproto data to the atproto-flavored json in HTTP request/response."
  {::i/name ::server-interceptor
   ::i/enter (fn [ctx]
               (update ctx
                       ::i/request
                       (fn [{:keys [body] :as request}]
                         (if (and body (atproto-flavored-json? request))
                           (update request :body decode)
                           request))))
   ::i/leave (fn [ctx]
               (update ctx
                       ::i/response
                       (fn [{:keys [body] :as response}]
                         (if (and body (atproto-flavored-json? response))
                           (update response :body encode)
                           response))))})
