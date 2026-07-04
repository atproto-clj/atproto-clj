(ns atproto.sync.runner-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [atproto.sync.cursor :as cursor]
            [atproto.sync.runner :as runner]))

(defn- take-cb
  "Run f with a callback; return what the callback was called with."
  [f]
  (let [result (atom ::not-called)]
    (f #(reset! result %))
    @result))

;;; ConsecutiveList

(deftest consecutive-list-test
  (testing "docstring example: complete out of order, prefix flushes"
    (let [cl (runner/consecutive-list)
          [cl i1] (runner/push cl 1)
          [cl i2] (runner/push cl 2)
          [cl i3] (runner/push cl 3)
          [cl r2] (runner/complete cl i2)
          [cl r1] (runner/complete cl i1)
          [_ r3] (runner/complete cl i3)]
      (is (= [] r2))
      (is (= [1 2] r1))
      (is (= [3] r3))))
  (testing "completing the only item returns it"
    (let [[cl i1] (runner/push (runner/consecutive-list) :a)
          [_ r1] (runner/complete cl i1)]
      (is (= [:a] r1))))
  (testing "completing out of order returns [] until the prefix completes"
    (let [cl (runner/consecutive-list)
          [cl i1] (runner/push cl 1)
          [cl i2] (runner/push cl 2)
          [cl i3] (runner/push cl 3)
          [cl i4] (runner/push cl 4)
          [cl r4] (runner/complete cl i4)
          [cl r3] (runner/complete cl i3)
          [cl r2] (runner/complete cl i2)
          [_ r1] (runner/complete cl i1)]
      (is (= [] r4))
      (is (= [] r3))
      (is (= [] r2))
      (is (= [1 2 3 4] r1))))
  (testing "ids remain valid after earlier items are removed"
    (let [cl (runner/consecutive-list)
          [cl i1] (runner/push cl 1)
          [cl i2] (runner/push cl 2)
          [cl r1] (runner/complete cl i1)
          [cl i3] (runner/push cl 3)
          [cl r3] (runner/complete cl i3)
          [_ r2] (runner/complete cl i2)]
      (is (= [1] r1))
      (is (= [] r3))
      (is (= [2 3] r2)))))

(defspec consecutive-list-random-completion-order 50
  (prop/for-all [[n perm] (gen/bind (gen/choose 1 30)
                                    (fn [n]
                                      (gen/tuple (gen/return n)
                                                 (gen/shuffle (range n)))))]
    (let [[clist ids] (reduce (fn [[cl ids] v]
                                (let [[cl id] (runner/push cl v)]
                                  [cl (conj ids id)]))
                              [(runner/consecutive-list) []]
                              (range 1 (inc n)))]
      (loop [cl clist
             perm perm
             completed #{}
             returned []]
        (if (empty? perm)
          ;; everything returned exactly once, in order
          (= returned (range 1 (inc n)))
          (let [i (first perm)
                [cl vals] (runner/complete cl (nth ids i))
                completed (conj completed (inc i))
                ;; naive model: highest fully-consecutive completed seq
                highest (loop [k 0]
                          (if (contains? completed (inc k)) (recur (inc k)) k))
                returned (into returned vals)]
            (and (= returned (range 1 (inc (count returned))))
                 (or (empty? returned) (<= (peek returned) highest))
                 (recur cl (rest perm) completed returned))))))))

;;; Runner (JVM-only: uses promises and Thread/sleep for async bridging)

#?(:clj
   (deftest same-did-serial-test
     (let [r (runner/memory-runner)
           order (atom [])
           done (promise)]
       (dotimes [i 5]
         (runner/track-event r "did:plc:a" (inc i)
                             (fn [cb]
                               (swap! order conj [:start i])
                               (Thread/sleep 5)
                               (swap! order conj [:end i])
                               (cb {}))))
       (runner/drain! r (fn [_] (deliver done ::done)))
       (is (= ::done (deref done 5000 ::timeout)))
       (is (= (mapcat (fn [i] [[:start i] [:end i]]) (range 5)) @order)
           "same-did events run serially, in arrival order"))))

#?(:clj
   (deftest different-dids-interleave-test
     ;; Each handler waits for the other to start: only completes promptly if
     ;; the two partitions genuinely run concurrently.
     (let [r (runner/memory-runner :concurrency 2)
           a-started (promise)
           b-started (promise)
           a-saw (atom nil)
           b-saw (atom nil)
           done (promise)]
       (runner/track-event r "did:a" 1
                           (fn [cb]
                             (deliver a-started true)
                             (reset! a-saw (deref b-started 2000 ::timeout))
                             (cb {})))
       (runner/track-event r "did:b" 2
                           (fn [cb]
                             (deliver b-started true)
                             (reset! b-saw (deref a-started 2000 ::timeout))
                             (cb {})))
       (runner/drain! r (fn [_] (deliver done ::done)))
       (is (= ::done (deref done 5000 ::timeout)))
       (is (true? @a-saw) "did:b started while did:a was still running")
       (is (true? @b-saw) "did:a started while did:b was still running"))))

#?(:clj
   (deftest cursor-commit-test
     (testing "get-cursor returns :start-cursor before any completion"
       (let [r (runner/memory-runner :start-cursor 41)]
         (is (= {:cursor 41} (take-cb #(runner/get-cursor r %))))))
     (testing "store only ever sees consecutive completed cursors"
       (let [writes (atom [])
             mem (cursor/memory-store)
             store (with-meta {}
                     {`cursor/get-cursor (fn [_ cb] (cursor/get-cursor mem cb))
                      `cursor/set-cursor (fn [_ c cb]
                                           (swap! writes conj c)
                                           (cursor/set-cursor mem c cb))})
             r (runner/memory-runner :cursor-store store)
             release-1 (promise)
             h2-done (promise)
             h3-done (promise)
             done (promise)]
         ;; seq=1 held open; seq=2 and seq=3 (different dids) finish first
         (runner/track-event r "did:a" 1
                             (fn [cb]
                               (deref release-1 2000 ::timeout)
                               (cb {})))
         (runner/track-event r "did:b" 2
                             (fn [cb] (cb {}) (deliver h2-done true)))
         (runner/track-event r "did:c" 3
                             (fn [cb] (cb {}) (deliver h3-done true)))
         (is (true? (deref h2-done 2000 ::timeout)))
         (is (true? (deref h3-done 2000 ::timeout)))
         ;; 2 and 3 completed but 1 has not: nothing consecutive yet
         (is (= [] @writes) "store not written past an incomplete seq")
         (is (= {:cursor nil} (take-cb #(runner/get-cursor r %))))
         (deliver release-1 true)
         (runner/drain! r (fn [_] (deliver done ::done)))
         (is (= ::done (deref done 5000 ::timeout)))
         (is (every? #(<= % 3) @writes))
         (is (apply <= 0 @writes) "cursor writes never go backwards")
         (is (= 3 (peek @writes)) "store ends at the max seq")
         (is (= {:cursor 3} (take-cb #(cursor/get-cursor mem %))))
         (is (= {:cursor 3} (take-cb #(runner/get-cursor r %))))))))

#?(:clj
   (deftest concurrency-one-serial-test
     (let [r (runner/memory-runner :concurrency 1)
           active (atom 0)
           max-active (atom 0)
           order (atom [])
           done (promise)]
       (doseq [[i did] (map-indexed vector ["did:a" "did:b" "did:a" "did:c"])]
         (runner/track-event r did (inc i)
                             (fn [cb]
                               (swap! max-active max (swap! active inc))
                               (Thread/sleep 5)
                               (swap! order conj (inc i))
                               (swap! active dec)
                               (cb {}))))
       (runner/drain! r (fn [_] (deliver done ::done)))
       (is (= ::done (deref done 5000 ::timeout)))
       (is (= 1 @max-active) ":concurrency 1 never overlaps handlers")
       (is (= [1 2 3 4] @order) "global arrival order across dids"))))

#?(:clj
   (deftest drain-and-destroy-test
     (testing "drain! resolves only after all handlers ran"
       (let [r (runner/memory-runner)
             completed (atom 0)
             n 6
             seen-at-drain (promise)]
         (dotimes [i n]
           (runner/track-event r (str "did:" (mod i 3)) (inc i)
                               (fn [cb]
                                 (Thread/sleep 5)
                                 (swap! completed inc)
                                 (cb {}))))
         (runner/drain! r (fn [_] (deliver seen-at-drain @completed)))
         (is (= n (deref seen-at-drain 5000 ::timeout)))))
     (testing "track-event after destroy! is ignored"
       (let [r (runner/memory-runner)
             ran? (atom false)
             done (promise)]
         (runner/destroy! r)
         (runner/track-event r "did:a" 1 (fn [cb] (reset! ran? true) (cb {})))
         (runner/drain! r (fn [_] (deliver done ::done)))
         (is (= ::done (deref done 1000 ::timeout)))
         (is (false? @ran?))))
     (testing "destroy! drops queued work but lets in-flight handlers finish"
       (let [r (runner/memory-runner :concurrency 1)
             release (promise)
             first-started (promise)
             first-finished (atom false)
             second-ran (atom false)
             done (promise)]
         (runner/track-event r "did:a" 1
                             (fn [cb]
                               (deliver first-started true)
                               (deref release 2000 ::timeout)
                               (reset! first-finished true)
                               (cb {})))
         (runner/track-event r "did:b" 2
                             (fn [cb] (reset! second-ran true) (cb {})))
         (is (true? (deref first-started 2000 ::timeout)))
         (runner/destroy! r)
         (deliver release true)
         (runner/drain! r (fn [_] (deliver done ::done)))
         (is (= ::done (deref done 5000 ::timeout)))
         (is (true? @first-finished))
         (is (false? @second-ran))))))

#?(:clj
   (defspec runner-random-delays 50
     (prop/for-all [[n did-idxs delays]
                    (gen/bind (gen/tuple (gen/choose 1 20) (gen/choose 1 4))
                              (fn [[n k]]
                                (gen/tuple (gen/return n)
                                           (gen/vector (gen/choose 0 (dec k)) n)
                                           (gen/vector (gen/choose 0 3) n))))]
       (let [store (cursor/memory-store)
             r (runner/memory-runner :cursor-store store)
             recorded (atom {})
             done (promise)]
         (dotimes [i n]
           (let [did (str "did:" (nth did-idxs i))
                 delay-ms (nth delays i)]
             (runner/track-event r did (inc i)
                                 (fn [cb]
                                   (when (pos? delay-ms)
                                     (Thread/sleep (long delay-ms)))
                                   (swap! recorded update did (fnil conj [])
                                          (inc i))
                                   (cb {})))))
         (runner/drain! r (fn [_] (deliver done ::done)))
         (let [expected (reduce (fn [m i]
                                  (update m (str "did:" (nth did-idxs i))
                                          (fnil conj []) (inc i)))
                                {}
                                (range n))]
           (and (= ::done (deref done 10000 ::timeout))
                ;; per-did ordering preserved
                (= expected @recorded)
                ;; final cursor reached the max seq, in runner and store
                (= {:cursor n} (take-cb #(runner/get-cursor r %)))
                (= {:cursor n} (take-cb #(cursor/get-cursor store %)))))))))
