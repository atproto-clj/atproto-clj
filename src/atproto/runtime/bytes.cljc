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
  #?(:clj (Arrays/equals ^bytes a ^bytes b)))

(defn ->utf8
  [bytes]
  #?(:clj (String. ^bytes bytes "UTF-8")
     :cljs (goog.crypt/utf8ByteArrayToString (js/Uint8Array. bytes))))
