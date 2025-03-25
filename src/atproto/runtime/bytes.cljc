(ns atproto.runtime.bytes
  #?(:clj (:import [java.util Arrays])))

(defn eq?
  [a b]
  #?(:clj (Arrays/equals a b)))
