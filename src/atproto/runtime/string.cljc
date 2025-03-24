(ns atproto.runtime.string
  (:require [clojure.string :as str])
  #?(:clj (:import [java.nio.charset StandardCharsets]
                   [java.text BreakIterator])))

#?(:clj (set! *warn-on-reflection* true))

(defn utf8-length
  [s]
  #?(:clj (alength (.getBytes ^String s StandardCharsets/UTF_8))))

(defn grapheme-length
  [s]
  #?(:clj (let [iter (doto (BreakIterator/getCharacterInstance)
                       (.setText ^String s))]
            (loop [cnt 0]
              (if (= BreakIterator/DONE (.next iter))
                cnt
                (recur (inc cnt)))))))
