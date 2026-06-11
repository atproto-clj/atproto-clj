(ns atproto.xrpc.rate-limit
  "Rate-limit hook points for the XRPC server (interface only).

  Ports the reference RateLimiterI interface and its combinators
  (packages/xrpc-server/src/rate-limiter.ts): a limiter consumes points
  for a key computed from the request context, and reports either nil
  (skipped / not applicable), a status map, or a RateLimitExceeded error.

  Status map shape:
  {:limit n             ;; points per window
   :duration secs       ;; window duration in seconds
   :remaining-points n
   :ms-before-next ms   ;; ms until the window resets
   :consumed-points n}

  Key/point computation:
  :calc-key    (fn [ctx]) -> string | nil   (nil skips the limiter)
  :calc-points (fn [ctx]) -> int            (< 1 skips the limiter)
  where ctx is the same map auth verifiers receive plus :auth. Wrapped
  limiters override these per call site via ::calc-key / ::calc-points
  on the ctx, which limiter implementations must honor.

  No production limiter is shipped; `memory` is for tests/dev only."
  (:refer-clojure :exclude [reset]))

#?(:clj (set! *warn-on-reflection* true))

(defprotocol RateLimiter
  (consume [limiter ctx cb]
    "Consume points for this request context.
     cb receives nil (skipped), a status map (not limited), or
     {:error \"RateLimitExceeded\" :status 429 :ratelimit-status <status>}.")
  (reset [limiter ctx cb]
    "Reset the counter for this request context's key. cb receives nil."))

(defn wrapped
  "Override the :calc-key / :calc-points of a limiter."
  [limiter {:keys [calc-key calc-points]}]
  (let [override #(cond-> %
                    calc-key (assoc ::calc-key calc-key)
                    calc-points (assoc ::calc-points calc-points))]
    (reify RateLimiter
      (consume [_ ctx cb] (consume limiter (override ctx) cb))
      (reset [_ ctx cb] (reset limiter (override ctx) cb)))))

(defn- tighter
  [a b]
  (cond
    (nil? a) b
    (nil? b) a
    (< (:remaining-points b) (:remaining-points a)) b
    :else a))

(defn combined
  "Combine limiters; all are consumed and the tightest (lowest remaining
  points) status wins. Any exceeded/error result short-circuits."
  [limiters]
  (reify RateLimiter
    (consume [_ ctx cb]
      (letfn [(step [remaining tightest]
                (if (empty? remaining)
                  (cb tightest)
                  (consume (first remaining) ctx
                           (fn [result]
                             (if (:error result)
                               (cb result)
                               (step (rest remaining) (tighter tightest result)))))))]
        (step (seq limiters) nil)))
    (reset [_ ctx cb]
      (letfn [(step [remaining]
                (if (empty? remaining)
                  (cb nil)
                  (reset (first remaining) ctx (fn [_] (step (rest remaining))))))]
        (step (seq limiters))))))

(defn- now-ms
  []
  #?(:clj (System/currentTimeMillis)
     :cljs (.now js/Date)))

(defn memory
  "Trivial in-memory fixed-window limiter (tests/dev only).

  config:
  :key-prefix   namespace for keys (limiters sharing an atom must differ).
  :duration-ms  window length in milliseconds.
  :points       max points per window.
  :calc-key     (fn [ctx]) -> string | nil; default: the request :ip.
  :calc-points  (fn [ctx]) -> int; default: 1."
  [{:keys [key-prefix duration-ms points calc-key calc-points]}]
  (let [state (atom {})
        default-calc-key (or calc-key (fn [ctx] (get-in ctx [:request :ip])))
        default-calc-points (or calc-points (constantly 1))
        entry-key (fn [ctx]
                    (when-let [k ((or (::calc-key ctx) default-calc-key) ctx)]
                      (str key-prefix ":" k)))]
    (reify RateLimiter
      (consume [_ ctx cb]
        (let [k (entry-key ctx)
              pts ((or (::calc-points ctx) default-calc-points) ctx)]
          (if (or (nil? k) (< pts 1))
            (cb nil)
            (let [now (now-ms)
                  [_ next-state]
                  (swap-vals! state
                              (fn [m]
                                (let [{:keys [consumed resets-at]} (get m k)]
                                  (if (or (nil? resets-at) (<= resets-at now))
                                    (assoc m k {:consumed pts
                                                :resets-at (+ now duration-ms)})
                                    (assoc m k {:consumed (+ consumed pts)
                                                :resets-at resets-at})))))
                  {:keys [consumed resets-at]} (get next-state k)
                  status {:limit points
                          :duration (quot duration-ms 1000)
                          :remaining-points (max 0 (- points consumed))
                          :ms-before-next (max 0 (- resets-at now))
                          :consumed-points consumed}]
              (if (< points consumed)
                (cb {:error "RateLimitExceeded"
                     :message "Rate Limit Exceeded"
                     :status 429
                     :ratelimit-status status})
                (cb status))))))
      (reset [_ ctx cb]
        (when-let [k (entry-key ctx)]
          (swap! state dissoc k))
        (cb nil)))))
