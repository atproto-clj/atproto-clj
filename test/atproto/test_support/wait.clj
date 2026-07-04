(ns atproto.test-support.wait
  "Polling helpers for asynchronous test assertions.

  Never assert after a bare sleep: poll for the observable condition
  with a timeout generous enough that hitting it means a real failure,
  not a slow scheduler. For negative assertions (\"X never happens\"),
  don't wait and hope — wait for a positive signal that is ordered
  after X would have happened, then assert.")

(defn wait-until
  "Poll (pred) every interval-ms (default 10) until it returns truthy or
  timeout-ms (default 5000) elapses. Returns pred's truthy value, or
  false on timeout."
  ([pred] (wait-until pred 5000))
  ([pred timeout-ms] (wait-until pred timeout-ms 10))
  ([pred timeout-ms interval-ms]
   (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
     (loop []
       (let [v (pred)]
         (cond
           v v
           (< (System/currentTimeMillis) deadline)
           (do (Thread/sleep (long interval-ms)) (recur))
           :else false))))))
