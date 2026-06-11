(ns atproto.tid
  "A TID (timestamp identifier) is a compact string identifier based on an integer timestamp.

  A TID is always exactly 13 characters: 11 characters of sort32-encoded
  microsecond timestamp followed by 2 characters of sort32-encoded clock-id.

  See https://atproto.com/specs/tid"
  (:require [clojure.math :refer [floor random]]
            [clojure.string :as str]
            [atproto.lexicon.regex :as regex]
            [atproto.runtime.datetime :refer [current-time-millis]]))

(def ^:const tid-length 13)

(def s32-chars "234567abcdefghijklmnopqrstuvwxyz")

(defn s32-encode
  "Encode a non-negative integer as a sort32 string.

  With `pad-to`, left-pad the result with \\2 (sort32 zero) to that length."
  ([n]
   (loop [n (long n)
          s '()]
     (if (zero? n)
       (apply str (or (seq s) [\2]))
       (recur (quot n 32)
              (cons (nth s32-chars (rem n 32)) s)))))
  ([n pad-to]
   (let [s (s32-encode n)
         missing (- pad-to (count s))]
     (if (pos? missing)
       (str (apply str (repeat missing \2)) s)
       s))))

(defn s32-decode
  "Decode a sort32 string into a long. Return nil on invalid characters."
  [s]
  (reduce (fn [acc ch]
            (if-let [idx (str/index-of s32-chars ch)]
              (+ (* 32 acc) idx)
              (reduced nil)))
          0
          s))

(defn valid?
  "Whether `s` is a syntactically valid TID (exactly 13 sort32 characters)."
  [s]
  (boolean (and (string? s)
                (= tid-length (count s))
                (re-matches regex/tid s))))

(defn parse
  "Parse a TID string into {:timestamp <epoch microseconds> :clock-id <long>}.

  Return nil if the string is not a valid TID."
  [s]
  (when (valid? s)
    {:timestamp (s32-decode (subs s 0 11))
     :clock-id (s32-decode (subs s 11 tid-length))}))

(defn timestamp
  "The epoch microseconds of the TID (long), or nil if invalid."
  [tid]
  (:timestamp (parse tid)))

(defn clock-id
  "The clock-id of the TID (long), or nil if invalid."
  [tid]
  (:clock-id (parse tid)))

(defn from-time
  "Build a TID string from epoch microseconds and a clock-id.

  The timestamp segment is zero-padded to 11 characters and the clock-id
  to 2, so the result is always exactly 13 characters."
  [micros clock-id]
  (str (s32-encode micros 11)
       (s32-encode clock-id 2)))

(defn compare-tids
  "Lexicographic TID comparator (= chronological order).

  Negative if `a` is older than `b`, positive if newer, zero if equal."
  [a b]
  (compare a b))

(defn newer?
  "Whether TID `a` is strictly newer than TID `b`."
  [a b]
  (pos? (compare-tids a b)))

(defn older?
  "Whether TID `a` is strictly older than TID `b`."
  [a b]
  (neg? (compare-tids a b)))

(def ^:private last-timestamp-ms (atom 0))

(defn- monotime-ms
  "Monotonically increasing timestamps in milliseconds."
  []
  (swap! last-timestamp-ms
         (fn [last-ts]
           (let [ts (current-time-millis)]
             (if (< last-ts ts)
               ts
               (+ 1 last-ts))))))

;; 10 bits for the clock-id, fixed for this process
(def ^:private local-clock-id (long (floor (* 1024 (random)))))

(defn next-tid
  "The next monotonically increasing TID.

  With `prev` (a TID string), the result is guaranteed to be strictly newer
  than `prev`, falling back to prev's timestamp + 1 if the clock has not
  caught up yet."
  ([]
   (from-time (* 1000 (monotime-ms)) local-clock-id))
  ([prev]
   (let [tid (next-tid)]
     (if (or (nil? prev) (newer? tid prev))
       tid
       (from-time (inc (timestamp prev)) local-clock-id)))))
