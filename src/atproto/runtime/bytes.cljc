(ns atproto.runtime.bytes
  #?(:clj (:refer-clojure :exclude [bytes?]))
  #?(:clj (:import [java.util Arrays]
                   [java.nio ByteBuffer])
     :cljs (:require [goog.crypt])))

#?(:clj (set! *warn-on-reflection* true)
   :cljs (set! *warn-on-infer* true))

(defn bytes?
  [v]
  #?(:clj (= (class (byte-array [])) (class v))
     :cljs (= js/Int8Array (type v))))

(defn eq?
  [a b]
  #?(:clj (Arrays/equals ^bytes a ^bytes b)
     :cljs (and (= (.-length a) (.-length b))
                (loop [i 0]
                  (cond
                    (= i (.-length a)) true
                    (= (aget a i) (aget b i)) (recur (inc i))
                    :else false)))))

(defn ->utf8
  [bytes]
  #?(:clj (String. ^bytes bytes "UTF-8")
     :cljs (goog.crypt/utf8ByteArrayToString (js/Uint8Array. bytes))))

(defn utf8-bytes
  "UTF-8 encoding of a string as platform bytes."
  [s]
  #?(:clj (.getBytes ^String s "UTF-8")
     :cljs (js/Int8Array.from (goog.crypt/stringToUtf8ByteArray s))))

(defn length
  "Number of bytes in a platform byte array."
  [b]
  #?(:clj (alength ^bytes b)
     :cljs (.-length b)))

(defn slice
  "Copy of bytes from start (inclusive) to end (exclusive)."
  [b start end]
  #?(:clj (Arrays/copyOfRange ^bytes b (int start) (int end))
     :cljs (.slice b start end)))

(defn concat-bytes
  "Concatenate byte arrays into a single new byte array."
  [& arrays]
  (let [total (reduce + 0 (map length arrays))]
    #?(:clj (let [out (byte-array total)]
              (loop [offset 0
                     [^bytes a & more] arrays]
                (if a
                  (do (System/arraycopy a 0 out offset (alength a))
                      (recur (+ offset (alength a)) more))
                  out)))
       :cljs (let [out (js/Int8Array. total)]
               (loop [offset 0
                      [a & more] arrays]
                 (if a
                   (do (.set out a offset)
                       (recur (+ offset (.-length a)) more))
                   out))))))
