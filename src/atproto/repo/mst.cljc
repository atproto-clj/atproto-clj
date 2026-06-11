(ns atproto.repo.mst
  "Merkle Search Tree: an ordered, insertion-order-independent,
  deterministic tree.

  Each key is hashed (SHA-256) and its leading zero bits are counted,
  2 bits per layer (~4-way fanout), to determine which layer the key
  lives on. Every subtree is referred to by the CID of its DAG-CBOR
  node encoding, so identical contents always produce identical roots.

  A node is encoded as {:l left-subtree-cid-or-nil
                        :e [{:p shared-prefix-len
                             :k key-suffix-bytes
                             :v value-cid
                             :t right-subtree-cid-or-nil} ...]}
  with key prefix compression relative to the preceding key.

  A tree value is an opaque map; use the API in this namespace. Trees
  are immutable values: every mutation returns a new tree. Entries are
  loaded lazily from the blockstore and cached; root CIDs are computed
  on demand and cached.

  Public functions return {:error \"Name\" :message ...} maps, never
  throw. Lazy sequences (leaf-seq, car-block-seq) are the exception:
  they may throw ex-info carrying the same map during realization.

  See https://atproto.com/specs/repository and
  packages/repo/src/mst/ in the TypeScript reference implementation."
  (:refer-clojure :exclude [load])
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [multiformats.hash :as mhash]
            [atproto.data :as data]
            [atproto.data.cbor :as cbor]
            [atproto.runtime.bytes :as bytes]
            [atproto.repo.blockstore :as blockstore]))

#?(:clj (set! *warn-on-reflection* true))

;; -----------------------------------------------------------------------------
;; Errors
;; -----------------------------------------------------------------------------

(defn- err!
  [error message & [extra]]
  (throw (ex-info message (assoc extra :error error :message message))))

(defn- attempt
  "Call f, converting thrown SDK error maps into returned error maps."
  [f]
  (try
    (f)
    (catch #?(:clj Exception :cljs :default) e
      (let [d (ex-data e)]
        (if (and (map? d) (:error d))
          d
          (throw e))))))

(defn- missing-block-ex?
  [e]
  (= "MissingBlock" (:error (ex-data e))))

;; -----------------------------------------------------------------------------
;; Keys & layers
;; -----------------------------------------------------------------------------

(def ^:private valid-chars-regex #"[a-zA-Z0-9_~\-:.]+")

(defn valid-key?
  "Whether s is a valid MST data key: \"collection/rkey\" with two
  non-empty [a-zA-Z0-9_~\\-:.]+ segments, at most 1024 chars total.
  Port of packages/repo/src/mst/util.ts:132-148."
  [s]
  (boolean
   (and (string? s)
        (<= (count s) 1024)
        (let [parts (str/split s #"/" -1)]
          (and (= 2 (count parts))
               (every? #(re-matches valid-chars-regex %) parts))))))

(s/def ::key valid-key?)

(defn leading-zeros
  "Count of leading zero bits in sha256(key). Determines the MST layer
  (2 bits per layer). Accepts a string or bytes.
  Port of packages/repo/src/mst/util.ts:23-38."
  [key]
  (let [k (if (bytes/bytes? key) key (bytes/utf8-bytes key))
        ;; multihash bytes are 0x12 0x20 ++ digest; slice off the header
        digest (bytes/slice (mhash/encode (mhash/sha2-256 k)) 2 34)
        len (bytes/length digest)]
    (loop [i 0
           zeros 0]
      (if (>= i len)
        zeros
        (let [b (bit-and 0xff #?(:clj (aget ^bytes digest i)
                                 :cljs (aget digest i)))]
          (if (zero? b)
            (recur (inc i) (+ zeros 4))
            (+ zeros (cond (< b 4) 3
                           (< b 16) 2
                           (< b 64) 1
                           :else 0))))))))

(defn count-prefix-len
  "Length of the common prefix of strings a and b."
  [a b]
  (let [n (min (count a) (count b))]
    (loop [i 0]
      (if (and (< i n) (= (nth a i) (nth b i)))
        (recur (inc i))
        i))))

;; -----------------------------------------------------------------------------
;; Node data shape
;; -----------------------------------------------------------------------------

(s/def ::p (s/and int? #(<= 0 %)))
(s/def ::k bytes/bytes?)
(s/def ::v data/cid?)
(s/def ::t (s/nilable data/cid?))
(s/def ::l (s/nilable data/cid?))
(s/def ::entry (s/keys :req-un [::p ::k ::v ::t]))
(s/def ::e (s/coll-of ::entry :kind vector?))
(s/def ::node-data (s/keys :req-un [::l ::e]))

;; -----------------------------------------------------------------------------
;; Tree values
;;
;; Tree: {::tree true, :blockstore bs, :pointer cid-or-nil,
;;        :entries vec-or-nil, :layer int-or-nil, :outdated? bool,
;;        ::cache atom}
;; Leaf: {:key string, :value cid}
;;
;; The cache atom holds {:entries ..., :pointer ..., :layer ...} computed
;; on demand; it is a pure function of the (immutable) tree value, so
;; caching keeps trees value-like while avoiding repeated hashing/IO
;; (the reference implementation mutates the same fields in place).
;; -----------------------------------------------------------------------------

(defn- make-tree
  [bs pointer entries layer outdated?]
  {::tree true
   :blockstore bs
   :pointer pointer
   :entries entries
   :layer layer
   :outdated? outdated?
   ::cache (atom {})})

(defn tree?
  "Whether x is an MST tree value (as opposed to a {:key :value} leaf)."
  [x]
  (boolean (and (map? x) (::tree x))))

(defn- leaf-entry? [x] (and (map? x) (not (::tree x))))

(defn create
  "New empty tree over blockstore bs."
  [bs]
  (make-tree bs nil [] nil true))

(defn load
  "Lazy handle on an existing tree by root CID. Does not touch storage."
  [bs root-cid]
  (make-tree bs root-cid nil nil false))

(declare get-pointer)

(defn- read-node
  "Read and validate the MST node at cid. Throws on missing/invalid."
  [bs cid]
  (let [res (blockstore/read-block bs cid ::node-data)]
    (if (:error res)
      (throw (ex-info (:message res) res))
      (:data res))))

(defn- deserialize-node-data
  "Node data -> entries vector of subtree handles and leaves.
  Port of packages/repo/src/mst/util.ts:48-78."
  [bs node layer]
  (let [child-layer (when (and layer (pos? layer)) (dec layer))]
    (loop [items (seq (:e node))
           last-key ""
           entries (if (:l node)
                     [(make-tree bs (:l node) nil child-layer false)]
                     [])]
      (if (nil? items)
        entries
        (let [{:keys [p k t v]} (first items)
              key (str (subs last-key 0 p) (bytes/->utf8 k))]
          (when-not (valid-key? key)
            (err! "InvalidMstKey" (str "Not a valid MST key: " key) {:key key}))
          (recur (next items)
                 key
                 (cond-> (conj entries {:key key :value v})
                   t (conj (make-tree bs t nil child-layer false)))))))))

(defn- serialize-node-data
  "Entries vector -> node data, with key prefix compression.
  Port of packages/repo/src/mst/util.ts:80-115."
  [entries]
  (let [[l start] (if (tree? (first entries))
                    [(get-pointer (first entries)) 1]
                    [nil 0])
        n (count entries)]
    (loop [i start
           last-key ""
           e []]
      (if (>= i n)
        {:l l :e e}
        (let [leaf (nth entries i)]
          (when-not (leaf-entry? leaf)
            (err! "InvalidTree" "Not a valid node: two subtrees next to each other"))
          (when-not (valid-key? (:key leaf))
            (err! "InvalidMstKey" (str "Not a valid MST key: " (:key leaf))
                  {:key (:key leaf)}))
          (let [nxt (nth entries (inc i) nil)
                [t i'] (if (tree? nxt)
                         [(get-pointer nxt) (+ i 2)]
                         [nil (inc i)])
                p (count-prefix-len last-key (:key leaf))]
            (recur (long i')
                   (:key leaf)
                   (conj e {:p p
                            :k (bytes/utf8-bytes (subs (:key leaf) p))
                            :v (:value leaf)
                            :t t}))))))))

(defn- get-entries
  "The entries vector of the node, loading lazily from storage.
  Throws {:error \"MissingBlock\"} if the node block is absent."
  [tree]
  (or (:entries tree)
      (:entries @(::cache tree))
      (let [{:keys [blockstore pointer]} tree]
        (when (nil? pointer)
          (err! "InvalidTree" "No entries or CID provided"))
        (let [node (read-node blockstore pointer)
              ;; the node's layer, from its first (p=0, uncompressed) key
              layer (when-let [fe (first (:e node))]
                      (leading-zeros (:k fe)))
              entries (deserialize-node-data blockstore node layer)]
          (swap! (::cache tree) assoc :entries entries)
          entries))))

(defn- serialize*
  [tree]
  (let [node (serialize-node-data (get-entries tree))
        bytes (cbor/encode node)]
    {:cid (data/cid-link bytes)
     :bytes bytes
     :data node}))

(defn- get-pointer
  [tree]
  (if-not (:outdated? tree)
    (:pointer tree)
    (or (:pointer @(::cache tree))
        (let [{:keys [cid]} (serialize* tree)]
          (swap! (::cache tree) assoc :pointer cid)
          cid))))

(defn pointer
  "Root CID of the tree, (re)serializing dirty nodes as needed.
  Returns a CID or {:error ...}."
  [tree]
  (attempt #(get-pointer tree)))

(defn serialize
  "Serialize this node: {:cid cid :bytes bytes :data node-data} or {:error ...}."
  [tree]
  (attempt #(serialize* tree)))

;; -----------------------------------------------------------------------------
;; Layers
;; -----------------------------------------------------------------------------

(defn- known-layer
  [tree]
  (or (:layer tree) (:layer @(::cache tree))))

(defn- layer-for-entries
  [entries]
  (when-let [first-leaf (some #(when (leaf-entry? %) %) entries)]
    (leading-zeros (:key first-leaf))))

(defn- attempt-get-layer
  [tree]
  (or (known-layer tree)
      (let [entries (get-entries tree)
            layer (or (layer-for-entries entries)
                      (some (fn [e]
                              (when (tree? e)
                                (some-> (attempt-get-layer e) inc)))
                            entries))]
        (when layer
          (swap! (::cache tree) assoc :layer layer))
        layer)))

(defn- get-layer
  [tree]
  (or (attempt-get-layer tree) 0))

;; -----------------------------------------------------------------------------
;; Simple entry operations
;; -----------------------------------------------------------------------------

(defn- new-tree
  [tree entries]
  (make-tree (:blockstore tree) (:pointer tree) (vec entries) (known-layer tree) true))

(defn- at-index
  [entries i]
  (when (and (<= 0 i) (< i (count entries)))
    (nth entries i)))

(defn- find-gt-or-equal-leaf-index
  "Index of the first leaf with key >= target, or (count entries)."
  [entries key]
  (or (first (keep-indexed
              (fn [i e]
                (when (and (leaf-entry? e)
                           (<= 0 (compare (:key e) key)))
                  i))
              entries))
      (count entries)))

(defn- update-entry
  [tree entries index entry]
  (new-tree tree (assoc entries index entry)))

(defn- remove-entry
  [tree entries index]
  (new-tree tree (into (subvec entries 0 index) (subvec entries (inc index)))))

(defn- splice-in
  [tree entries entry index]
  (new-tree tree (-> (subvec entries 0 index)
                     (conj entry)
                     (into (subvec entries index)))))

(defn- replace-with-split
  [tree entries index left leaf right]
  (new-tree tree (-> (subvec entries 0 index)
                     (cond-> left (conj left))
                     (conj leaf)
                     (cond-> right (conj right))
                     (into (subvec entries (inc index))))))

;; -----------------------------------------------------------------------------
;; Splits & merges
;; -----------------------------------------------------------------------------

(defn- create-child
  [tree]
  (make-tree (:blockstore tree) nil [] (dec (get-layer tree)) true))

(defn- create-parent
  [tree]
  (make-tree (:blockstore tree) nil [tree] (inc (get-layer tree)) true))

(defn- split-around
  "Recursively split the tree around key. Returns [left right], either
  possibly nil. Port of packages/repo/src/mst/mst.ts:464-490."
  [tree key]
  (let [entries (get-entries tree)
        index (find-gt-or-equal-leaf-index entries key)
        left-data (subvec entries 0 index)
        right-data (subvec entries index)
        last-in-left (peek left-data)]
    (if (tree? last-in-left)
      ;; the far right of the left side is a subtree: split it on key too
      (let [[sub-left sub-right] (split-around last-in-left key)
            left-entries (cond-> (subvec entries 0 (dec index))
                           sub-left (conj sub-left))
            right-entries (if sub-right
                            (into [sub-right] right-data)
                            right-data)]
        [(when (seq left-entries) (new-tree tree left-entries))
         (when (seq right-entries) (new-tree tree right-entries))])
      [(when (seq left-data) (new-tree tree left-data))
       (when (seq right-data) (new-tree tree right-data))])))

(defn- append-merge
  "Merge two trees where every key in to-merge is greater than every key
  in tree (used for deletes). Port of mst.ts:494-514."
  [tree to-merge]
  (when (not= (get-layer tree) (get-layer to-merge))
    (err! "InvalidTree" "Trying to merge two nodes from different layers of the MST"))
  (let [es (get-entries tree)
        ms (get-entries to-merge)
        last-in-left (peek es)
        first-in-right (first ms)]
    (if (and (tree? last-in-left) (tree? first-in-right))
      (let [merged (append-merge last-in-left first-in-right)]
        (new-tree tree (-> (subvec es 0 (dec (count es)))
                           (conj merged)
                           (into (rest ms)))))
      (new-tree tree (into es ms)))))

;; -----------------------------------------------------------------------------
;; Core operations
;; -----------------------------------------------------------------------------

(defn- add*
  [tree key value known-zeros]
  (when-not (valid-key? key)
    (err! "InvalidMstKey" (str "Not a valid MST key: " key) {:key key}))
  (let [key-zeros (or known-zeros (leading-zeros key))
        layer (get-layer tree)
        new-leaf {:key key :value value}]
    (cond
      ;; belongs in this layer
      (= key-zeros layer)
      (let [entries (get-entries tree)
            index (find-gt-or-equal-leaf-index entries key)
            found (at-index entries index)]
        (when (and (leaf-entry? found) (= (:key found) key))
          (err! "KeyAlreadyExists" (str "There is already a value at key: " key)
                {:key key}))
        (let [prev (at-index entries (dec index))]
          (if (or (nil? prev) (leaf-entry? prev))
            ;; if entry before is a leaf (or we're on the far left), splice in
            (splice-in tree entries new-leaf index)
            ;; otherwise split the preceding subtree around the key
            (let [[split-left split-right] (split-around prev key)]
              (replace-with-split tree entries (dec index)
                                  split-left new-leaf split-right)))))

      ;; belongs on a lower layer
      (< key-zeros layer)
      (let [entries (get-entries tree)
            index (find-gt-or-equal-leaf-index entries key)
            prev (at-index entries (dec index))]
        (if (tree? prev)
          (update-entry tree entries (dec index) (add* prev key value key-zeros))
          (let [subtree (add* (create-child tree) key value key-zeros)]
            (splice-in tree entries subtree index))))

      ;; belongs on a higher layer: push the rest of the tree down
      :else
      (let [[left right] (split-around tree key)
            extra-layers (- key-zeros layer)
            ;; starting at 1 since the first layer is handled by the split
            [left right] (loop [i 1, left left, right right]
                           (if (< i extra-layers)
                             (recur (inc i)
                                    (some-> left create-parent)
                                    (some-> right create-parent))
                             [left right]))
            entries (cond-> []
                      left (conj left)
                      true (conj new-leaf)
                      right (conj right))]
        (make-tree (:blockstore tree) nil entries key-zeros true)))))

(defn- get-value*
  [tree key]
  (let [entries (get-entries tree)
        index (find-gt-or-equal-leaf-index entries key)
        found (at-index entries index)]
    (if (and (leaf-entry? found) (= (:key found) key))
      (:value found)
      (let [prev (at-index entries (dec index))]
        (when (tree? prev)
          (get-value* prev key))))))

(defn- update*
  [tree key value]
  (when-not (valid-key? key)
    (err! "InvalidMstKey" (str "Not a valid MST key: " key) {:key key}))
  (let [entries (get-entries tree)
        index (find-gt-or-equal-leaf-index entries key)
        found (at-index entries index)]
    (if (and (leaf-entry? found) (= (:key found) key))
      (update-entry tree entries index {:key key :value value})
      (let [prev (at-index entries (dec index))]
        (if (tree? prev)
          (update-entry tree entries (dec index) (update* prev key value))
          (err! "KeyNotFound" (str "Could not find a record with key: " key)
                {:key key}))))))

(defn- delete-recurse
  [tree key]
  (let [entries (get-entries tree)
        index (find-gt-or-equal-leaf-index entries key)
        found (at-index entries index)]
    (if (and (leaf-entry? found) (= (:key found) key))
      ;; found on this level: remove it, merging neighbor subtrees
      (let [prev (at-index entries (dec index))
            nxt (at-index entries (inc index))]
        (if (and (tree? prev) (tree? nxt))
          (new-tree tree (-> (subvec entries 0 (dec index))
                             (conj (append-merge prev nxt))
                             (into (subvec entries (+ index 2)))))
          (remove-entry tree entries index)))
      ;; otherwise recurse down to find it
      (let [prev (at-index entries (dec index))]
        (if (tree? prev)
          (let [subtree (delete-recurse prev key)]
            (if (zero? (count (get-entries subtree)))
              (remove-entry tree entries (dec index))
              (update-entry tree entries (dec index) subtree)))
          (err! "KeyNotFound" (str "Could not find a record with key: " key)
                {:key key}))))))

(defn- trim-top
  "If the top node only points to another tree, trim it and return the
  subtree."
  [tree]
  (let [entries (try
                  (get-entries tree)
                  (catch #?(:clj Exception :cljs :default) e
                    (if (missing-block-ex? e) ::missing (throw e))))]
    (cond
      (= ::missing entries) tree
      (and (= 1 (count entries)) (tree? (first entries))) (recur (first entries))
      :else tree)))

(defn add
  "Add leaf key -> value-cid. Returns tree' or
  {:error \"InvalidMstKey\"} / {:error \"KeyAlreadyExists\" :key key}
  / {:error \"MissingBlock\" :cid cid} (partial tree)."
  [tree key value-cid]
  (attempt #(add* tree key value-cid nil)))

(defn update-value
  "Replace value at existing key. {:error \"KeyNotFound\"} if absent."
  [tree key value-cid]
  (attempt #(update* tree key value-cid)))

(defn delete
  "Remove key, merging neighbor subtrees and trimming the top.
  {:error \"KeyNotFound\"} if absent."
  [tree key]
  (attempt #(trim-top (delete-recurse tree key))))

(defn get-value
  "Value CID at key, nil if absent, or {:error \"MissingBlock\" ...} on a
  partial tree."
  [tree key]
  (attempt #(get-value* tree key)))

;; -----------------------------------------------------------------------------
;; Traversal
;; -----------------------------------------------------------------------------

(defn entry-seq
  "Lazy depth-first seq of all entries — subtree handles (see tree?) and
  {:key k :value cid} leaves — starting with the tree itself. May throw
  ex-info {:error \"MissingBlock\"} during realization.
  Port of mst.ts walk (:621-633)."
  [tree]
  (cons tree
        (lazy-seq
         (mapcat #(if (tree? %) (entry-seq %) [%])
                 (get-entries tree)))))

(defn- walk-from
  "Lazy seq of entries on the path to key and everything after it.
  Port of mst.ts walkFrom (:554-580), except an exact-match leaf at the
  split index is yielded only once (the reference yields it twice; its
  `list` masks the duplicate by skipping `key === after`, but
  `listWithPrefix` would surface it)."
  [tree key]
  (cons tree
        (lazy-seq
         (let [entries (get-entries tree)
               index (find-gt-or-equal-leaf-index entries key)
               found (at-index entries index)
               ;; an exact match at index is covered by the tail below
               head (when-not (and (leaf-entry? found) (= (:key found) key))
                      (let [prev (at-index entries (dec index))]
                        (cond
                          (and (leaf-entry? prev) (= (:key prev) key)) [prev]
                          (tree? prev) (walk-from prev key)
                          :else nil)))]
           (concat head
                   (mapcat #(if (tree? %) (walk-from % key) [%])
                           (subvec entries index)))))))

(defn leaf-seq
  "Lazy ordered seq of {:key k :value cid} leaves, optionally starting at
  key `from`. May throw ex-info {:error \"MissingBlock\"} during
  realization on a partial tree."
  ([tree] (leaf-seq tree ""))
  ([tree from] (filter leaf-entry? (walk-from tree (or from "")))))

(defn list-keys
  "Ordered leaves as a vector of {:key k :value cid}.

  opts:
    :after   exclusive start key
    :before  exclusive end key
    :limit   max number of leaves"
  [tree & {:keys [after before limit]}]
  (attempt
   #(cond->> (leaf-seq tree (or after ""))
      after (remove (fn [leaf] (= (:key leaf) after)))
      before (take-while (fn [leaf] (neg? (compare (:key leaf) before))))
      limit (take limit)
      true vec)))

(defn list-with-prefix
  "Ordered leaves whose key starts with prefix, as a vector.

  opts:
    :limit  max number of leaves"
  [tree prefix & {:keys [limit]}]
  (attempt
   #(cond->> (leaf-seq tree prefix)
      true (take-while (fn [leaf] (str/starts-with? (:key leaf) prefix)))
      limit (take limit)
      true vec)))

(defn reachable-leaf-seq
  "Like leaf-seq but skips subtrees whose blocks are missing (for proof
  CARs). Returns a vector. Port of mst.ts walkReachable (:695-723)."
  [tree]
  (attempt
   #(let [acc (volatile! (transient []))]
      (letfn [(walk [t]
                (doseq [e (get-entries t)]
                  (if (tree? e)
                    (try
                      (walk e)
                      (catch #?(:clj Exception :cljs :default) ex
                        (when-not (missing-block-ex? ex) (throw ex))))
                    (vswap! acc conj! e))))]
        (walk tree))
      (persistent! @acc))))

;; -----------------------------------------------------------------------------
;; Blocks for storage & sync
;; -----------------------------------------------------------------------------

(defn unstored-blocks
  "{:root cid :blocks block-map} of tree nodes not yet in the blockstore.
  Port of mst.ts getUnstoredBlocks (:209-224)."
  [tree]
  (attempt
   #(letfn [(go [t blocks]
              (let [ptr (get-pointer t)]
                (if (blockstore/has-block? (:blockstore t) ptr)
                  blocks
                  (let [{:keys [cid bytes]} (serialize* t)]
                    (reduce (fn [blocks e]
                              (if (tree? e) (go e blocks) blocks))
                            (assoc blocks cid bytes)
                            (get-entries t))))))]
      {:root (get-pointer tree)
       :blocks (go tree {})})))

(defn cids-for-path
  "Vector of CIDs of nodes along the path to key, plus the leaf value cid
  if the key is present. Port of mst.ts cidsForPath (:766-778)."
  [tree key]
  (attempt
   #(letfn [(go [t]
              (let [own (get-pointer t)
                    entries (get-entries t)
                    index (find-gt-or-equal-leaf-index entries key)
                    found (at-index entries index)]
                (if (and (leaf-entry? found) (= (:key found) key))
                  [own (:value found)]
                  (let [prev (at-index entries (dec index))]
                    (if (tree? prev)
                      (into [own] (go prev))
                      [own])))))]
      (go tree))))

(defn car-block-seq
  "Lazy seq of {:cid c :bytes b} for all stored tree nodes (BFS order)
  followed by all leaf record blocks; for full-repo CAR export. Throws
  ex-info {:error \"MissingBlock\"} during realization if blocks are
  absent. Port of mst.ts carBlockStream (:727-764)."
  [tree]
  (let [bs (:blockstore tree)
        root (get-pointer tree)
        fetch! (fn [cids what]
                 (let [{:keys [blocks missing]} (blockstore/get-blocks bs cids)]
                   (when (seq missing)
                     (err! "MissingBlock"
                           (str "Missing blocks: " what)
                           {:cids missing}))
                   blocks))
        ;; ordered de-duplicated conj: [order-vector seen-set]
        ord-conj (fn [[order seen :as acc] cid]
                   (if (contains? seen cid)
                     acc
                     [(conj order cid) (conj seen cid)]))
        decode-node (fn [cid bytes]
                      (let [node (cbor/decode bytes)]
                        (when-not (s/valid? ::node-data node)
                          (err! "InvalidBlock"
                                (str "Block " (data/format-cid cid)
                                     " is not a valid MST node.")
                                {:cid cid}))
                        node))]
    ;; BFS over node layers, then leaf blocks
    (letfn [(node-layers [to-fetch leaves]
              (lazy-seq
               (if (empty? to-fetch)
                 (let [leaf-cids (first leaves)
                       blocks (fetch! leaf-cids "mst leaf")]
                   (map (fn [cid] {:cid cid :bytes (get blocks cid)}) leaf-cids))
                 (let [blocks (fetch! to-fetch "mst node")
                       step (reduce
                             (fn [{:keys [out next-layer leaves]} cid]
                               (let [bytes (get blocks cid)
                                     node (decode-node cid bytes)
                                     entries (deserialize-node-data bs node nil)]
                                 {:out (conj out {:cid cid :bytes bytes})
                                  :next-layer (reduce
                                               (fn [nl e]
                                                 (if (tree? e)
                                                   (ord-conj nl (get-pointer e))
                                                   nl))
                                               next-layer entries)
                                  :leaves (reduce
                                           (fn [lv e]
                                             (if (leaf-entry? e)
                                               (ord-conj lv (:value e))
                                               lv))
                                           leaves entries)}))
                             {:out [] :next-layer [[] #{}] :leaves leaves}
                             to-fetch)]
                   (concat (:out step)
                           (node-layers (first (:next-layer step))
                                        (:leaves step)))))))]
      (node-layers [root] [[] #{}]))))

;; -----------------------------------------------------------------------------
;; Diff (twin-walker)
;; -----------------------------------------------------------------------------

;; Walker state: {:stack [status ...]
;;                :status {:done? bool :curr entry :walking tree-or-nil :index int}}
;; Port of packages/repo/src/mst/walker.ts.

(defn- make-walker
  [root]
  {:stack []
   :status {:done? false :curr root :walking nil :index 0}})

(defn- walker-done? [w] (:done? (:status w)))
(defn- walker-curr [w] (:curr (:status w)))

(defn- walker-layer
  [{:keys [status]}]
  (cond
    (:done? status) (err! "InvalidTreeWalk" "Walk is done")
    (:walking status) (or (known-layer (:walking status)) 0)
    ;; if curr is the root of the tree, add 1
    (tree? (:curr status)) (inc (or (known-layer (:curr status)) 0))
    :else (err! "InvalidTreeWalk" "Could not identify layer of walk")))

(defn- walker-step-over
  "Move to the next node in the subtree, skipping over the current one."
  [{:keys [stack status] :as w}]
  (cond
    (:done? status) w
    ;; stepping over the root means we're done
    (nil? (:walking status)) (assoc w :status {:done? true})
    :else
    (let [entries (get-entries (:walking status))
          index (inc (:index status))
          nxt (at-index entries index)]
      (if nxt
        (assoc w :status (assoc status :index index :curr nxt))
        (if-let [popped (peek stack)]
          (recur (assoc w :stack (pop stack) :status popped))
          (assoc w :status {:done? true}))))))

(defn- walker-step-into
  "Step into the subtree at the pointer; errors if pointed at a leaf."
  [{:keys [stack status] :as w}]
  (cond
    (:done? status) w

    ;; edge case for the very start of the walk
    (nil? (:walking status))
    (let [curr (:curr status)]
      (when-not (tree? curr)
        (err! "InvalidTreeWalk" "The root of the tree cannot be a leaf"))
      (let [nxt (at-index (get-entries curr) 0)]
        (if (nil? nxt)
          (assoc w :status {:done? true})
          (assoc w :status {:done? false :walking curr :curr nxt :index 0}))))

    :else
    (let [curr (:curr status)]
      (when-not (tree? curr)
        (err! "InvalidTreeWalk" "No tree at pointer, cannot step into"))
      (let [nxt (at-index (get-entries curr) 0)]
        (when (nil? nxt)
          (err! "InvalidTreeWalk" "Tried to step into a node with 0 entries"))
        (-> w
            (update :stack conj status)
            (assoc :status {:done? false :walking curr :curr nxt :index 0}))))))

(defn- walker-advance
  [w]
  (cond
    (walker-done? w) w
    (leaf-entry? (walker-curr w)) (walker-step-over w)
    :else (walker-step-into w)))

;; Diff accumulator, port of packages/repo/src/data-diff.ts.

(def ^:private empty-diff
  {:adds {}
   :updates {}
   :deletes {}
   :new-mst-blocks {}
   :new-leaf-cids #{}
   :removed-cids #{}})

(defn- diff-leaf-add
  [d key cid]
  (let [d (assoc-in d [:adds key] {:key key :cid cid})]
    (if (contains? (:removed-cids d) cid)
      (update d :removed-cids disj cid)
      (update d :new-leaf-cids conj cid))))

(defn- diff-leaf-update
  [d key prev cid]
  (if (= prev cid)
    d
    (-> d
        (assoc-in [:updates key] {:key key :prev prev :cid cid})
        (update :removed-cids conj prev)
        (update :new-leaf-cids conj cid))))

(defn- diff-leaf-delete
  [d key cid]
  (let [d (assoc-in d [:deletes key] {:key key :cid cid})]
    (if (contains? (:new-leaf-cids d) cid)
      (update d :new-leaf-cids disj cid)
      (update d :removed-cids conj cid))))

(defn- diff-tree-add
  [d cid bytes]
  (if (contains? (:removed-cids d) cid)
    (update d :removed-cids disj cid)
    (assoc-in d [:new-mst-blocks cid] bytes)))

(defn- diff-tree-delete
  [d cid]
  (if (contains? (:new-mst-blocks d) cid)
    (update d :new-mst-blocks dissoc cid)
    (update d :removed-cids conj cid)))

(defn- diff-node-add
  [d node]
  (if (leaf-entry? node)
    (diff-leaf-add d (:key node) (:value node))
    (let [{:keys [cid bytes]} (serialize* node)]
      (diff-tree-add d cid bytes))))

(defn- diff-node-delete
  [d node]
  (if (leaf-entry? node)
    (-> d
        (assoc-in [:deletes (:key node)] {:key (:key node) :cid (:value node)})
        (update :removed-cids conj (:value node)))
    (diff-tree-delete d (get-pointer node))))

(defn- null-diff
  [tree]
  (reduce diff-node-add empty-diff (entry-seq tree)))

(defn- mst-diff
  [curr prev]
  (get-pointer curr)
  (if (nil? prev)
    (null-diff curr)
    (do
      (get-pointer prev)
      (loop [lw (make-walker prev)
             rw (make-walker curr)
             d empty-diff]
        (cond
          (and (walker-done? lw) (walker-done? rw)) d

          ;; one walker finished: log all remaining nodes of the other
          (walker-done? lw)
          (recur lw (walker-advance rw) (diff-node-add d (walker-curr rw)))

          (walker-done? rw)
          (recur (walker-advance lw) rw (diff-node-delete d (walker-curr lw)))

          :else
          (let [left (walker-curr lw)
                right (walker-curr rw)]
            (cond
              (or (nil? left) (nil? right)) d

              ;; both leaves: update or advance the lowest key
              (and (leaf-entry? left) (leaf-entry? right))
              (cond
                (= (:key left) (:key right))
                (recur (walker-advance lw) (walker-advance rw)
                       (if (= (:value left) (:value right))
                         d
                         (diff-leaf-update d (:key left) (:value left) (:value right))))

                (neg? (compare (:key left) (:key right)))
                (recur (walker-advance lw) rw
                       (diff-leaf-delete d (:key left) (:value left)))

                :else
                (recur lw (walker-advance rw)
                       (diff-leaf-add d (:key right) (:value right))))

              ;; walkers on different layers: catch the higher one up
              (> (walker-layer lw) (walker-layer rw))
              (if (leaf-entry? left)
                (recur lw (walker-advance rw) (diff-node-add d right))
                (recur (walker-step-into lw) rw (diff-node-delete d left)))

              (< (walker-layer lw) (walker-layer rw))
              (if (leaf-entry? right)
                (recur (walker-advance lw) rw (diff-node-delete d left))
                (recur lw (walker-step-into rw) (diff-node-add d right)))

              ;; both trees on the same layer: step over if identical,
              ;; into if different
              (and (tree? left) (tree? right))
              (if (= (get-pointer left) (get-pointer right))
                (recur (walker-step-over lw) (walker-step-over rw) d)
                (recur (walker-step-into lw) (walker-step-into rw)
                       (-> d (diff-node-add right) (diff-node-delete left))))

              ;; one tree, one leaf: step into the tree
              (and (leaf-entry? left) (tree? right))
              (recur lw (walker-step-into rw) (diff-node-add d right))

              (and (tree? left) (leaf-entry? right))
              (recur (walker-step-into lw) rw (diff-node-delete d left))

              :else
              (err! "InvalidTreeWalk" "Unidentifiable case in diff walk"))))))))

(defn diff
  "Difference between curr and prev (prev may be nil = everything added).
  Returns {:adds {key {:key k :cid c}}
           :updates {key {:key k :prev c :cid c}}
           :deletes {key {:key k :cid c}}
           :new-mst-blocks block-map
           :new-leaf-cids cid-set
           :removed-cids cid-set}
  or {:error ...}. Port of packages/repo/src/mst/diff.ts + data-diff.ts."
  [curr prev]
  (attempt #(mst-diff curr prev)))

;; -----------------------------------------------------------------------------
;; Proofs
;; -----------------------------------------------------------------------------

(defn- proof-for-key
  [tree key]
  (let [entries (get-entries tree)
        index (find-gt-or-equal-leaf-index entries key)
        found (at-index entries index)
        blocks (if (and (leaf-entry? found) (= (:key found) key))
                 {}
                 (let [prev (at-index entries (dec index))]
                   (if (or (nil? prev) (leaf-entry? prev))
                     ::abort
                     (proof-for-key prev key))))]
    (if (= ::abort blocks)
      {}
      (let [{:keys [cid bytes]} (serialize* tree)]
        (assoc blocks cid bytes)))))

(defn- proof-for-left-sib
  [tree key]
  (let [entries (get-entries tree)
        index (find-gt-or-equal-leaf-index entries key)
        prev (at-index entries (dec index))
        blocks (if (or (nil? prev) (leaf-entry? prev))
                 {}
                 (proof-for-left-sib prev key))
        {:keys [cid bytes]} (serialize* tree)]
    (assoc blocks cid bytes)))

(defn- proof-for-right-sib
  [tree key]
  (let [entries (get-entries tree)
        index (find-gt-or-equal-leaf-index entries key)
        found (or (at-index entries index)
                  (at-index entries (dec index)))
        blocks (cond
                 (nil? found) {}
                 (tree? found) (proof-for-right-sib found key)
                 :else
                 (let [node (if (= (:key found) key)
                              (at-index entries (inc index))
                              (at-index entries (dec index)))]
                   (if (or (nil? node) (leaf-entry? node))
                     {}
                     (proof-for-right-sib node key))))
        {:keys [cid bytes]} (serialize* tree)]
    (assoc blocks cid bytes)))

(defn covering-proof
  "Block-map of all MST nodes needed to prove the value of the leaf at
  key and its immediate left and right siblings.
  {:error \"MissingBlock\"} on partial trees.
  Port of mst.ts getCoveringProof (:784-850)."
  [tree key]
  (attempt
   #(merge (proof-for-key tree key)
           (proof-for-left-sib tree key)
           (proof-for-right-sib tree key))))
