(ns atproto.runtime.retry-test
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            [atproto.runtime.retry :as retry]))

(deftest backoff-ms-test
  ;; ~100, ~200, ~400, ~800, ~1000, ~1000, ... each ±15%
  (dotimes [_ 50]
    (let [ms (retry/backoff-ms 0)]
      (is (<= 85.0 ms 115.0)))
    (let [ms (retry/backoff-ms 1)]
      (is (<= 170.0 ms 230.0)))
    (let [ms (retry/backoff-ms 2)]
      (is (<= 340.0 ms 460.0)))
    ;; capped at 1000
    (let [ms (retry/backoff-ms 10)]
      (is (<= 850.0 ms 1150.0)))
    ;; custom multiplier/max
    (let [ms (retry/backoff-ms 5 :multiplier 1 :max 10)]
      (is (<= 8.5 ms 11.5)))))

(deftest abort-signal-test
  (let [signal (retry/abort-signal)]
    (is (false? (retry/aborted? signal)))
    (retry/abort! signal)
    (is (true? (retry/aborted? signal)))
    ;; idempotent
    (retry/abort! signal)
    (is (true? (retry/aborted? signal))))
  ;; nil signals are never aborted (and abort!/on-abort! tolerate them)
  (is (false? (retry/aborted? nil)))
  (is (nil? (retry/abort! nil)))
  (is (nil? (retry/on-abort! nil #(throw (ex-info "boom" {}))))))

(deftest on-abort-test
  ;; listeners registered before abort! fire exactly once on abort!
  (let [signal (retry/abort-signal)
        calls (atom 0)]
    (retry/on-abort! signal #(swap! calls inc))
    (is (zero? @calls))
    (retry/abort! signal)
    (is (= 1 @calls))
    (retry/abort! signal)
    (is (= 1 @calls))
    ;; listeners registered after abort! fire immediately
    (retry/on-abort! signal #(swap! calls inc))
    (is (= 2 @calls))))

;; The with-retry tests use immediate callbacks, so the only asynchrony is
;; `schedule`'s backoff sleep; a promise bridges it on the JVM.

#?(:clj
   (defn- run-retry
     [f opts]
     (let [result (promise)]
       (retry/with-retry f opts #(deliver result %))
       (deref result 5000 ::timeout))))

#?(:clj
   (deftest with-retry-success-first-try-test
     (let [calls (atom 0)
           f (fn [cb] (swap! calls inc) (cb {:ok true}))]
       (is (= {:ok true} (run-retry f {:max-retries 3})))
       (is (= 1 @calls)))))

#?(:clj
   (deftest with-retry-retryable-then-success-test
     (let [calls (atom 0)
           f (fn [cb]
               (if (= 1 (swap! calls inc))
                 (cb {:error "Timeout" :retryable? true})
                 (cb {:ok true})))]
       (is (= {:ok true} (run-retry f {:max-retries 3
                                       :backoff-ms (constantly 1)})))
       (is (= 2 @calls)))))

#?(:clj
   (deftest with-retry-respects-max-retries-test
     (let [calls (atom 0)
           f (fn [cb] (swap! calls inc) (cb {:error "Timeout" :retryable? true}))]
       (is (= "Timeout" (:error (run-retry f {:max-retries 2
                                              :backoff-ms (constantly 1)}))))
       ;; initial attempt + 2 retries
       (is (= 3 @calls)))))

#?(:clj
   (deftest with-retry-non-retryable-test
     (let [calls (atom 0)
           f (fn [cb] (swap! calls inc) (cb {:error "InvalidRequest" :retryable? false}))]
       (is (= "InvalidRequest" (:error (run-retry f {:max-retries 3}))))
       (is (= 1 @calls)))))

#?(:clj
   (deftest with-retry-custom-predicate-test
     (let [calls (atom 0)
           f (fn [cb]
               (if (= 1 (swap! calls inc))
                 (cb {:error "Weird"})
                 (cb {:ok true})))]
       (is (= {:ok true} (run-retry f {:max-retries 3
                                       :retryable? #(= "Weird" (:error %))
                                       :backoff-ms (constantly 1)})))
       (is (= 2 @calls)))))

#?(:clj
   (deftest with-retry-aborted-before-start-test
     (let [signal (retry/abort-signal)
           calls (atom 0)
           f (fn [cb] (swap! calls inc) (cb {:ok true}))]
       (retry/abort! signal)
       (is (= "Aborted" (:error (run-retry f {:max-retries 3 :signal signal}))))
       (is (zero? @calls)))))

#?(:clj
   (deftest with-retry-aborted-between-attempts-test
     (let [signal (retry/abort-signal)
           calls (atom 0)
           f (fn [cb]
               (swap! calls inc)
               (retry/abort! signal)
               (cb {:error "Timeout" :retryable? true}))]
       (is (= "Aborted" (:error (run-retry f {:max-retries 3 :signal signal}))))
       (is (= 1 @calls)))))
