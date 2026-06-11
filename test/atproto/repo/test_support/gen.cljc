(ns atproto.repo.test-support.gen
  "test.check generators for MST keys, CIDs, blocks, and repo contents,
  shared by the repo property tests."
  (:require [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [atproto.data :as data]
            [atproto.data.cbor :as cbor]))

(defn ->bytes
  [byte-vec]
  #?(:clj (byte-array byte-vec)
     :cljs (js/Int8Array.from (into-array byte-vec))))

;; -----------------------------------------------------------------------------
;; MST keys
;; -----------------------------------------------------------------------------

(def key-chars
  (vec "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_~-:."))

(def gen-key-segment
  "One non-empty [a-zA-Z0-9_~\\-:.]+ segment of an MST key."
  (gen/fmap str/join (gen/vector (gen/elements key-chars) 1 12)))

(def gen-mst-key
  "A valid \"collection/rkey\" MST key."
  (gen/fmap (fn [[collection rkey]] (str collection "/" rkey))
            (gen/tuple gen-key-segment gen-key-segment)))

(def gen-invalid-mst-key
  "Strings that violate the MST key grammar in one specific way."
  (gen/one-of
   [;; no slash at all
    gen-key-segment
    ;; empty segment on either side
    (gen/fmap #(str % "/") gen-key-segment)
    (gen/fmap #(str "/" %) gen-key-segment)
    ;; more than two segments
    (gen/fmap (fn [[a b c]] (str a "/" b "/" c))
              (gen/tuple gen-key-segment gen-key-segment gen-key-segment))
    ;; a character outside the allowed charset
    (gen/fmap (fn [[k bad-char i]]
                (let [i (mod i (count k))]
                  (str (subs k 0 i) bad-char (subs k i))))
              (gen/tuple gen-mst-key
                         (gen/elements [" " "$" "%" "(" ")" "+" "=" "@" "#" "\"" "é" "💩"])
                         gen/nat))
    ;; longer than 1024 chars
    (gen/fmap (fn [[a b]] (str a "/" b (str/join (repeat 1024 "x"))))
              (gen/tuple gen-key-segment gen-key-segment))]))

;; -----------------------------------------------------------------------------
;; CIDs, bytes & blocks
;; -----------------------------------------------------------------------------

(def gen-bytes
  (gen/fmap ->bytes (gen/vector (gen/choose -128 127) 0 64)))

(def gen-cid
  "A dag-cbor CID of random content."
  (gen/fmap #(data/cid-link (->bytes %))
            (gen/vector (gen/choose -128 127) 4 32)))

(def gen-key-cid-map
  "{mst-key cid} of distinct keys, the input shape for building trees."
  (gen/map gen-mst-key gen-cid {:min-elements 1 :max-elements 40}))

(def gen-data-int
  "Integers within the atproto data model's ±(2^53 - 1) bounds."
  (gen/choose -9007199254740991 9007199254740991))

(def gen-cbor-block
  "{:cid c :bytes b} where bytes are a valid DAG-CBOR record and the CID
  matches the bytes."
  (gen/fmap (fn [[n s]]
              (let [bytes (cbor/encode {:i n :payload s})]
                {:cid (data/cid-link bytes) :bytes bytes}))
            (gen/tuple gen-data-int gen/string-alphanumeric)))

(def gen-cbor-blocks
  (gen/vector gen-cbor-block 1 20))

;; -----------------------------------------------------------------------------
;; Repo contents & edits
;; -----------------------------------------------------------------------------

(def collections
  ["com.example.posts" "com.example.likes" "org.example.profile"])

(def gen-rkey
  (gen/such-that #(not (#{"." ".."} %)) gen-key-segment 100))

(def gen-record
  (gen/fmap (fn [s] {:name s}) gen/string-alphanumeric))

(defn- embed-paths
  "Make every record unique by embedding its own path. Identical records
  share a CID, and the repo layer's :removed-cids is structural (TS
  parity): deleting/updating one of two identical records removes the
  block both reference, which storage layers above the repo are
  responsible for filtering. The round-trip properties model repos
  without shared record blocks; the shared-block behavior has its own
  deliberate test."
  [contents]
  (reduce-kv
   (fn [acc collection records]
     (assoc acc collection
            (reduce-kv (fn [rs rkey record]
                         (assoc rs rkey (assoc record :path (str collection "/" rkey))))
                       {}
                       records)))
   {}
   contents))

(def gen-contents
  "Repo contents: {collection {rkey record}} with at least one record
  per generated collection; records are unique per path."
  (gen/fmap embed-paths
            (gen/map (gen/elements collections)
                     (gen/map gen-rkey gen-record {:min-elements 1 :max-elements 8})
                     {:min-elements 1 :max-elements 2})))

(defn contents->create-writes
  [contents]
  (vec (for [[collection records] contents
             [rkey record] records]
         {:action :create :collection collection :rkey rkey :value record})))

(defn apply-writes-to-contents
  "The model: what repo contents look like after the writes."
  [contents writes]
  (reduce (fn [data {:keys [action collection rkey value]}]
            (case action
              (:create :update) (assoc-in data [collection rkey] value)
              :delete (let [data (update data collection dissoc rkey)]
                        (if (empty? (get data collection))
                          (dissoc data collection)
                          data))))
          contents
          writes))

(defn expected-write-descripts
  "The write descripts a correct MST diff between old and new contents
  must report, as a set of [action collection rkey]. Updates writing an
  identical record are invisible to a content-addressed diff."
  [old new]
  (let [paths (fn [contents] (set (for [[coll records] contents
                                        [rkey _] records]
                                    [coll rkey])))
        old-paths (paths old)
        new-paths (paths new)]
    (-> #{}
        (into (map #(into [:create] %)) (remove old-paths new-paths))
        (into (map #(into [:delete] %)) (remove new-paths old-paths))
        (into (comp (filter (fn [[coll rkey]]
                              (not= (get-in old [coll rkey])
                                    (get-in new [coll rkey]))))
                    (map #(into [:update] %)))
              (filter new-paths old-paths)))))

(defn gen-edit
  "Generator of {:writes [...] :data new-contents} for a random batch of
  creates/updates/deletes against the given base contents."
  [contents]
  (let [pairs (vec (for [[collection records] contents
                         [rkey _] records]
                     [collection rkey]))]
    (gen/let [shuffled (gen/shuffle pairs)
              n-upd (gen/choose 0 (count pairs))
              n-del (gen/choose 0 (- (count pairs) n-upd))
              upd-records (gen/vector gen-record n-upd)
              additions gen-contents]
      (let [existing? (set pairs)
            creates (for [[collection records] additions
                          [rkey record] records
                          :when (not (existing? [collection rkey]))]
                      {:action :create :collection collection
                       :rkey rkey :value record})
            updates (map (fn [[collection rkey] record]
                           {:action :update :collection collection
                            :rkey rkey
                            :value (assoc record :path (str collection "/" rkey))})
                         (take n-upd shuffled)
                         upd-records)
            deletes (map (fn [[collection rkey]]
                           {:action :delete :collection collection :rkey rkey})
                         (take n-del (drop n-upd shuffled)))
            writes (vec (concat creates updates deletes))]
        {:writes writes
         :data (apply-writes-to-contents contents writes)}))))
