(ns atproto.runtime.datetime-test
  (:require #?@(:clj [[clojure.test :refer :all]
                      [clojure.java.io :as io]]
                :cljs [[cljs.test :refer :all]])
            [clojure.string :as str]
            [atproto.lexicon.regex :as regex]
            [atproto.runtime.datetime :as datetime])
  #?(:cljs (:require-macros [atproto.runtime.datetime-test :refer [interop-test-cases]])))

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

;; The :cljs bodies are owned by WS-10; these tests are CLJ-only until then.

#?(:clj
   (deftest normalize-interop-fixtures-test
     (doseq [s (interop-test-cases "syntax/datetime_syntax_valid.txt")]
       (let [normalized (datetime/normalize s)]
         (is (string? normalized) (str "expected to normalize: " s " got " (pr-str normalized)))
         (when (string? normalized)
           (is (re-matches regex/rfc3339 normalized))
           ;; canonical form: UTC millisecond precision with trailing Z
           (is (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z" normalized)))))
     ;; syntactically plausible but semantically invalid datetimes must error
     (doseq [s (interop-test-cases "syntax/datetime_parse_invalid.txt")]
       (is (= "InvalidDatetime" (:error (datetime/normalize s)))
           (str "expected normalize to fail: " s)))))

#?(:clj
   (deftest normalize-test
     ;; canonical inputs round-trip
     (is (= "1985-04-12T23:20:50.123Z" (datetime/normalize "1985-04-12T23:20:50.123Z")))
     ;; offsets are normalized to UTC
     (is (= "1985-04-13T06:20:50.123Z" (datetime/normalize "1985-04-12T23:20:50.123-07:00")))
     ;; fractional seconds are truncated to millisecond precision
     (is (= "1985-04-12T23:20:50.123Z" (datetime/normalize "1985-04-12T23:20:50.123456789Z")))
     (is (= "1985-04-12T23:20:50.000Z" (datetime/normalize "1985-04-12T23:20:50Z")))
     ;; missing timezone is interpreted as UTC
     (is (= "1985-04-12T23:20:50.000Z" (datetime/normalize "1985-04-12T23:20:50")))
     (is (= "1985-04-12T23:20:50.123Z" (datetime/normalize "1985-04-12T23:20:50.123")))
     ;; explicit "UTC" timezone abbreviation
     (is (= "1985-04-12T23:20:50.000Z" (datetime/normalize "1985-04-12T23:20:50 UTC")))
     ;; RFC-1123
     (is (= "2008-06-03T11:05:30.000Z" (datetime/normalize "Tue, 3 Jun 2008 11:05:30 GMT")))
     ;; unparseable input
     (is (= "InvalidDatetime" (:error (datetime/normalize "blah"))))
     (is (= "InvalidDatetime" (:error (datetime/normalize ""))))
     (is (= "InvalidDatetime" (:error (datetime/normalize nil))))
     ;; year bounds (0000-9999, evaluated in UTC)
     (is (= "InvalidDatetime" (:error (datetime/normalize "+10000-01-01T00:00:00Z"))))
     (is (= "InvalidDatetime" (:error (datetime/normalize "9999-12-31T23:30:00-05:00"))))
     (is (= "0000-01-01T00:00:00.000Z" (datetime/normalize "0000-01-01T00:00:00Z")))))

#?(:clj
   (deftest normalize-or-epoch-test
     (is (= "1985-04-12T23:20:50.123Z" (datetime/normalize-or-epoch "1985-04-12T23:20:50.123Z")))
     (is (= "1970-01-01T00:00:00.000Z" (datetime/normalize-or-epoch "blah")))
     (is (= "1970-01-01T00:00:00.000Z" (datetime/normalize-or-epoch nil)))))

#?(:clj
   (deftest current-datetime-test
     (let [now (datetime/current-datetime)]
       (is (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z" now))
       (is (re-matches regex/rfc3339 now))
       ;; normalize-stable
       (is (= now (datetime/normalize now))))))
