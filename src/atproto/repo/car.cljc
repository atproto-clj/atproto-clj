(ns atproto.repo.car
  "CAR v1 (Content ARchive) reading and writing.

  Layout: varint(len(header)) ++ header, where header is the DAG-CBOR
  encoding of {:version 1 :roots [cid ...]}; then for each block
  varint(len(cid-bytes) + len(block-bytes)) ++ cid-bytes ++ block-bytes.

  Block CIDs are parsed structurally (version varint + codec varint +
  multihash), not as a fixed 36-byte prefix.

  See https://ipld.io/specs/transport/car/carv1/ and
  packages/repo/src/car.ts in the TypeScript reference implementation."
  (:require [clojure.spec.alpha :as s]
            [atproto.data :as data]
            [atproto.data.cbor :as cbor]
            [atproto.runtime.bytes :as bytes]
            [atproto.runtime.varint :as varint])
  #?(:clj (:import [java.io InputStream OutputStream])))

#?(:clj (set! *warn-on-reflection* true))

(defn- err!
  [error message & [extra]]
  (throw (ex-info message (assoc extra :error error :message message))))

(s/def ::version #{1})
(s/def ::roots (s/coll-of data/cid? :kind vector?))
(s/def ::header (s/keys :req-un [::version ::roots]))

;; -----------------------------------------------------------------------------
;; Writing
;; -----------------------------------------------------------------------------

(defn- header-bytes
  [root]
  (let [header (cbor/encode {:version 1 :roots (if root [root] [])})]
    (bytes/concat-bytes (varint/encode (bytes/length header)) header)))

(defn- block-bytes
  [{:keys [cid bytes]}]
  (let [cid-bytes (data/encode-cid cid)]
    (bytes/concat-bytes (varint/encode (+ (bytes/length cid-bytes)
                                          (bytes/length bytes)))
                        cid-bytes
                        bytes)))

(defn write-car
  "CAR v1 bytes for a root CID (may be nil = empty roots) and a seqable
  of {:cid c :bytes b} blocks (order preserved)."
  [root blocks]
  (apply bytes/concat-bytes
         (header-bytes root)
         (map block-bytes blocks)))

#?(:clj
   (defn write-car-stream
     "Stream a CAR v1 to an OutputStream from a (possibly lazy) seq of
     {:cid c :bytes b} blocks."
     [root blocks ^OutputStream out]
     (.write out ^bytes (header-bytes root))
     (doseq [block blocks]
       (.write out ^bytes (block-bytes block)))
     (.flush out)))

;; -----------------------------------------------------------------------------
;; Reading
;; -----------------------------------------------------------------------------

(defn- read-varint-at
  "[value bytes-read] at offset, or throws {:error \"InvalidCar\"}."
  [b offset]
  (try
    (varint/read-bytes b offset)
    (catch #?(:clj Exception :cljs :default) e
      (err! "InvalidCar" (str "Could not parse varint: " (ex-message e))
            {:offset offset}))))

(defn- read-cid-at
  "Structurally parse the CID at offset within bytes b (bounded by end).
  Returns [cid bytes-consumed]. Throws {:error \"InvalidCar\"} on
  non-v1 CIDs or malformed/truncated prefixes."
  [b offset end]
  (let [[version n1] (read-varint-at b offset)]
    (when (not= 1 version)
      (err! "InvalidCar" (str "Unsupported CID version: " version)))
    (let [[_codec n2] (read-varint-at b (+ offset n1))
          [_hash-fn n3] (read-varint-at b (+ offset n1 n2))
          [hash-len n4] (read-varint-at b (+ offset n1 n2 n3))
          total (+ n1 n2 n3 n4 hash-len)]
      (when (> (+ offset total) end)
        (err! "InvalidCar" "Truncated CID in CAR block"))
      (let [cid (try
                  (data/decode-cid (bytes/slice b offset (+ offset total)))
                  (catch #?(:clj Exception :cljs :default) e
                    (err! "InvalidCar" (str "Invalid CID: " (ex-message e)))))]
        [cid total]))))

(defn- parse-block
  "Parse and (optionally) verify one CID ++ bytes section."
  [section-bytes skip-cid-verification?]
  (let [end (bytes/length section-bytes)
        [cid cid-len] (read-cid-at section-bytes 0 end)
        block (bytes/slice section-bytes cid-len end)]
    (when-not skip-cid-verification?
      (let [res (data/verify-cid cid block)]
        (when (map? res)
          (err! "InvalidCarBlock"
                (str "Not a valid CID for bytes: " (data/format-cid cid))
                {:cid cid}))))
    {:cid cid :bytes block}))

(defn- parse-header
  "[roots offset-after-header]"
  [b]
  (let [len (bytes/length b)
        [header-len n] (read-varint-at b 0)]
    (when (> (+ n header-len) len)
      (err! "InvalidCar" "Truncated CAR header"))
    (let [header (try
                   (cbor/decode (bytes/slice b n (+ n header-len)))
                   (catch #?(:clj Exception :cljs :default) e
                     (err! "InvalidCar"
                           (str "Could not parse CAR header: " (ex-message e)))))]
      (when-not (s/valid? ::header header)
        (err! "InvalidCar" "Could not parse CAR header"))
      [(:roots header) (+ n header-len)])))

(defn- attempt-car
  "Run f, converting thrown errors into maps; anything not already
  SDK-error-shaped becomes {:error \"InvalidCar\"}."
  [f]
  (try
    (f)
    (catch #?(:clj Exception :cljs :default) e
      (let [d (ex-data e)]
        (if (and (map? d) (:error d))
          d
          {:error "InvalidCar" :message (ex-message e)})))))

(defn read-car
  "Parse CAR v1 bytes.

  Returns {:roots [cid ...]
           :blocks [{:cid c :bytes b} ...]   ;; in file order
           :block-map block-map}
  or {:error \"InvalidCar\" :message ...}.

  Verifies that each block's bytes hash to its CID unless
  :skip-cid-verification? is true; a mismatch yields
  {:error \"InvalidCarBlock\" :cid cid}."
  [car-bytes & {:keys [skip-cid-verification?]}]
  (attempt-car
   #(let [len (bytes/length car-bytes)
          [roots offset] (parse-header car-bytes)]
      (loop [offset offset
             blocks []
             block-map {}]
        (if (>= offset len)
          {:roots roots :blocks blocks :block-map block-map}
          (let [[section-len n] (read-varint-at car-bytes offset)
                start (+ offset n)
                end (+ start section-len)]
            (when (> end len)
              (err! "InvalidCar" "Truncated CAR block"))
            (let [{:keys [cid bytes] :as block}
                  (parse-block (bytes/slice car-bytes start end)
                               skip-cid-verification?)]
              (recur end
                     (conj blocks block)
                     (assoc block-map cid bytes)))))))))

(defn read-car-with-root
  "Like read-car but requires exactly one root.

  Returns {:root cid :blocks [...] :block-map block-map} or
  {:error \"InvalidCar\" ...}."
  [car-bytes & {:as opts}]
  (let [res (read-car car-bytes opts)]
    (cond
      (:error res) res
      (not= 1 (count (:roots res)))
      {:error "InvalidCar"
       :message (str "Expected one root, got " (count (:roots res)))}
      :else (-> res
                (dissoc :roots)
                (assoc :root (first (:roots res)))))))

;; -----------------------------------------------------------------------------
;; JVM streaming
;; -----------------------------------------------------------------------------

#?(:clj
   (defn- read-fully
     "Read exactly n bytes from in; throws on EOF."
     ^bytes [^InputStream in n]
     (let [out (byte-array n)]
       (loop [offset 0]
         (if (= offset n)
           out
           (let [read (.read in out offset (- n offset))]
             (when (neg? read)
               (err! "InvalidCar" "Unexpected end of CAR stream"))
             (recur (+ offset read))))))))

#?(:clj
   (defn- read-varint-stream
     "Read a varint from the stream, or nil at a clean EOF."
     [^InputStream in]
     (loop [acc []]
       (let [b (.read in)]
         (cond
           (neg? b) (if (empty? acc)
                      nil
                      (err! "InvalidCar" "Truncated varint in CAR stream"))
           (< b 128) (let [bs (byte-array (map unchecked-byte (conj acc b)))]
                       (first (varint/read-bytes bs 0)))
           :else (recur (conj acc b)))))))

#?(:clj
   (defn block-reader
     "Streaming CAR reader over an InputStream.

     Returns {:roots [cid ...] :blocks <reducible of {:cid c :bytes b}>}
     or {:error \"InvalidCar\" ...}. Blocks are verified during reduction
     (opt out with :skip-cid-verification?); reduction throws ex-info
     {:error \"InvalidCarBlock\"} on a bad block and
     {:error \"InvalidCar\"} on framing errors. Does not load the whole
     CAR into memory; suitable for multi-GB getRepo exports."
     [^InputStream in & {:keys [skip-cid-verification?]}]
     (attempt-car
      #(let [header-len (or (read-varint-stream in)
                            (err! "InvalidCar" "Could not parse CAR header"))
             header (try
                      (cbor/decode (read-fully in header-len))
                      (catch Exception e
                        (err! "InvalidCar"
                              (str "Could not parse CAR header: " (ex-message e)))))]
         (when-not (s/valid? ::header header)
           (err! "InvalidCar" "Could not parse CAR header"))
         {:roots (:roots header)
          :blocks (reify clojure.lang.IReduceInit
                    (reduce [_ f init]
                      (loop [acc init]
                        (if-let [section-len (read-varint-stream in)]
                          (let [section (read-fully in section-len)
                                block (parse-block section skip-cid-verification?)
                                ret (f acc block)]
                            (if (reduced? ret)
                              @ret
                              (recur ret)))
                          acc))))}))))
