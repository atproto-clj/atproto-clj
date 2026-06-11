(ns atproto.tid-test
  (:require #?@(:clj [[clojure.test :refer :all]
                      [clojure.java.io :as io]]
                :cljs [[cljs.test :refer :all]])
            [clojure.string :as str]
            [atproto.tid :as tid])
  #?(:cljs (:require-macros [atproto.tid-test :refer [interop-test-cases]])))

#?(:clj
   (defmacro interop-test-cases
     "Return the test cases from the file at `path` in the interop test file directory.

      Can be called from ClojureScript to inline the test cases."
     [path]
     (->> (slurp (io/resource (str "interop-test-files/" path)))
          (str/split-lines)
          (remove #(or (str/blank? %)
                       (str/starts-with? % "#")))
          (into []))))

(deftest interop-fixtures-test
  (doseq [s (interop-test-cases "syntax/tid_syntax_valid.txt")]
    (is (tid/valid? s) (str "expected valid: " s))
    (is (some? (tid/parse s)) (str "expected parseable: " s)))
  (doseq [s (interop-test-cases "syntax/tid_syntax_invalid.txt")]
    (is (not (tid/valid? s)) (str "expected invalid: " s))
    (is (nil? (tid/parse s)) (str "expected unparseable: " s))))

(deftest s32-round-trip-test
  (doseq [n [0 1 31 32 1024 123456789 1700000000000000]]
    (is (= n (tid/s32-decode (tid/s32-encode n)))))
  (is (= "2" (tid/s32-encode 0)))
  (is (= "22222" (tid/s32-encode 0 5)))
  (is (= 0 (tid/s32-decode "2")))
  (is (nil? (tid/s32-decode "01"))))

(deftest parse-round-trip-test
  (doseq [[micros clock-id] [[0 0]
                             [1 0]
                             [1 1023]
                             [1700000000000000 42]
                             [3 16]]]
    (let [s (tid/from-time micros clock-id)]
      (is (= tid/tid-length (count s)))
      (is (tid/valid? s) (str "expected valid: " s))
      (is (= {:timestamp micros :clock-id clock-id} (tid/parse s)))
      (is (= micros (tid/timestamp s)))
      (is (= clock-id (tid/clock-id s))))))

(deftest padding-regression-test
  ;; timestamps < 32^10 microseconds used to produce TIDs shorter than 13 chars
  (let [s (tid/from-time 1 0)]
    (is (= 13 (count s)))
    (is (tid/valid? s))))

(deftest next-tid-test
  (let [tids (repeatedly 64 tid/next-tid)]
    (doseq [s tids]
      (is (= 13 (count s)))
      (is (tid/valid? s)))
    ;; strictly increasing
    (is (= tids (sort tid/compare-tids (shuffle tids))))
    (is (every? (fn [[a b]] (tid/newer? b a)) (partition 2 1 tids)))))

(deftest next-tid-with-prev-test
  ;; a prev in the past has no effect
  (let [prev (tid/from-time 1 0)
        tid (tid/next-tid prev)]
    (is (tid/newer? tid prev)))
  ;; a prev in the future forces prev's timestamp + 1
  (let [future-micros (* 1000 1000 60 60 24 365 100) ;; ~year 2070... from epoch micros
        prev (tid/from-time (+ (tid/timestamp (tid/next-tid)) future-micros) 0)
        tid (tid/next-tid prev)]
    (is (tid/newer? tid prev))
    (is (= (inc (tid/timestamp prev)) (tid/timestamp tid))))
  ;; nil prev behaves like the 0-arity
  (is (tid/valid? (tid/next-tid nil))))

(deftest compare-tids-test
  (let [older (tid/from-time 1000 0)
        newer (tid/from-time 2000 0)]
    (is (neg? (tid/compare-tids older newer)))
    (is (pos? (tid/compare-tids newer older)))
    (is (zero? (tid/compare-tids older older)))
    (is (tid/newer? newer older))
    (is (not (tid/newer? older newer)))
    (is (tid/older? older newer))
    (is (not (tid/older? newer older))))
  ;; lexicographic order = chronological order once padded
  (let [tids (map #(tid/from-time % 0) [1 31 32 1000 32768 1700000000000000])]
    (is (= tids (sort tids)))))
