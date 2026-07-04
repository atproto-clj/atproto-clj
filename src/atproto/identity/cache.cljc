(ns atproto.identity.cache
  "Pluggable cache for identity resolution results.

  Mirrors the DID cache of the TypeScript reference implementation
  (packages/identity/src/did/memory-cache.ts): entries carry an
  :updated-at timestamp and the SDK computes staleness/expiry against a
  policy, so implementations stay dumb key/value stores.

  Policy semantics:
    :stale-ttl     entry older than this is served but revalidated
                   in the background (stale-while-revalidate)
    :max-ttl       entry older than this is never served
    :negative-ttl  lifetime of cached not-found markers; nil (the
                   default) disables negative caching entirely, which
                   matches the reference implementation")

#?(:clj (set! *warn-on-reflection* true))

(defprotocol Cache
  "Storage interface. Entries are maps with at least :val and :updated-at
  (epoch ms). Implementations need no TTL logic; policy is computed by
  `check`."
  (get* [cache k] "The entry map for k, or nil.")
  (set* [cache k entry] "Store the entry map under k. Returns nil.")
  (del* [cache k] "Remove k. Returns nil.")
  (clear* [cache] "Remove all entries. Returns nil."))

(defn memory-cache
  "Default in-memory Cache backed by an atom. Unbounded; pass your own
  implementation (e.g. LRU, redis) for production multi-node setups."
  ([] (memory-cache (atom {})))
  ([entries-atom]
   (reify Cache
     (get* [_ k] (get @entries-atom k))
     (set* [_ k entry] (swap! entries-atom assoc k entry) nil)
     (del* [_ k] (swap! entries-atom dissoc k) nil)
     (clear* [_] (reset! entries-atom {}) nil))))

(def default-policy
  "Reference defaults: staleTTL 1 hour, maxTTL 1 day
  (packages/identity/src/did/memory-cache.ts:12-15); negative caching
  disabled (the reference evicts on not-found rather than caching it)."
  {:stale-ttl (* 1000 60 60)
   :max-ttl (* 1000 60 60 24)
   :negative-ttl nil})

(defn now-ms
  "Milliseconds since epoch."
  []
  #?(:clj (System/currentTimeMillis)
     :cljs (.now js/Date)))

(defn check
  "Look up k and classify the entry against the policy. Returns nil on
  miss, else
    {:val <cached value>
     :updated-at <ms>
     :stale? <bool>      ; updated-at + stale-ttl < now
     :expired? <bool>    ; updated-at + max-ttl < now
     :negative? <bool>}  ; entry is a cached not-found marker

  Negative entries expire (and are considered stale) after
  :negative-ttl; with a nil :negative-ttl they are always expired."
  [cache {:keys [stale-ttl max-ttl negative-ttl]} k]
  (when-let [{:keys [val updated-at negative?]} (get* cache k)]
    (let [now (now-ms)
          age (- now updated-at)
          expired? (boolean
                    (if negative?
                      (or (nil? negative-ttl) (< negative-ttl age))
                      (< max-ttl age)))
          stale? (boolean
                  (if negative?
                    expired?
                    (< stale-ttl age)))]
      {:val val
       :updated-at updated-at
       :stale? stale?
       :expired? expired?
       :negative? (boolean negative?)})))

(defn store
  "Cache a successful value under k (sets :updated-at to now)."
  [cache k val]
  (set* cache k {:val val :updated-at (now-ms)}))

(defn store-negative
  "Cache a not-found marker under k, but only if the policy enables
  negative caching (:negative-ttl non-nil); otherwise evict k."
  [cache policy k]
  (if (:negative-ttl policy)
    (set* cache k {:val nil :negative? true :updated-at (now-ms)})
    (del* cache k)))

(defn evict
  "Remove k from the cache."
  [cache k]
  (del* cache k))
