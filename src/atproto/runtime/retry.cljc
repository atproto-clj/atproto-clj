(ns atproto.runtime.retry
  "Callback-based retry with exponential backoff (ports common-web/retry.ts),
  plus the cooperative abort signal used for request cancellation."
  (:require [atproto.xrpc.error :as xrpc-error]))

;; Abort signals
;;
;; A signal is an opaque value created by `abort-signal` and tripped (at most
;; once) by `abort!`. Consumers either poll with `aborted?` or register a
;; zero-arg listener with `on-abort!`; every listener is invoked exactly once,
;; immediately if the signal is already aborted.

(defn abort-signal
  "Create a cooperative cancellation signal."
  []
  (atom {:aborted? false :listeners []}))

(defn aborted?
  "Whether the signal has been aborted. nil signals are never aborted."
  [signal]
  (boolean (some-> signal deref :aborted?)))

(defn on-abort!
  "Register a zero-arg listener invoked exactly once when the signal is
  aborted (immediately, if it already is)."
  [signal f]
  (when signal
    (let [[prev _] (swap-vals! signal (fn [{:keys [aborted?] :as state}]
                                        (if aborted?
                                          state
                                          (update state :listeners conj f))))]
      (when (:aborted? prev)
        (f))
      nil)))

(defn abort!
  "Trip the signal, invoking pending listeners. Idempotent."
  [signal]
  (when signal
    (let [[prev _] (swap-vals! signal #(assoc % :aborted? true :listeners []))]
      (when-not (:aborted? prev)
        (run! #(%) (:listeners prev)))
      nil)))

;; Retry

(defn backoff-ms
  "Exponential backoff with jitter: min(2^n * multiplier, max) ± 15%.

  Defaults multiplier=100, max=1000: ~100, ~200, ~400, ~800, ~1000, ~1000, ..."
  [n & {:keys [multiplier max] :or {multiplier 100 max 1000}}]
  (let [ms (min (* (Math/pow 2 n) multiplier) max)
        delta (* ms 0.15)]
    (+ ms (- (rand (* 2 delta)) delta))))

(defn schedule
  "Platform-appropriate delayed invocation of zero-arg `f` after `ms`."
  [ms f]
  #?(:clj (future (Thread/sleep (long ms)) (f))
     :cljs (js/setTimeout f ms))
  nil)

(defn with-retry
  "Run async op `f`, a fn of one arg (a callback receiving a result map).
  If the result is retryable and attempts remain, re-run after backoff;
  otherwise deliver the result to `cb` (which is called exactly once).

  opts: :max-retries (default 3)
        :retryable?  (fn [result] boolean; default atproto.xrpc.error/retryable?)
        :backoff-ms  (fn [attempt] ms; default backoff-ms)
        :signal      optional abort signal; checked before each attempt,
                     delivering {:error \"Aborted\"} if set"
  [f {:keys [max-retries signal] :as opts} cb]
  (let [max-retries (or max-retries 3)
        result-retryable? (or (:retryable? opts) xrpc-error/retryable?)
        backoff (or (:backoff-ms opts) backoff-ms)
        aborted-result {:error "Aborted" :message "Request aborted."}
        attempt! (fn attempt! [n]
                   (if (aborted? signal)
                     (cb aborted-result)
                     (f (fn [result]
                          (cond
                            (not (and (< n max-retries)
                                      (result-retryable? result)))
                            (cb result)

                            (aborted? signal)
                            (cb aborted-result)

                            :else
                            (schedule (backoff n) #(attempt! (inc n))))))))]
    (attempt! 0)
    nil))
