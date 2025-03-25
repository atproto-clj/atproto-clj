(ns atproto.oauth.client.store
  (:refer-clojure :exclude [get set])
  (:require [atproto.runtime.json :as json]))

;; todo: should we make this async?
(defprotocol Store
  "Interface for stores needed by the OAuth 2 client."
  (get* [_ key] "The value for key, or nil.")
  (set* [_ key val] "Set the value for key.")
  (del* [_ key] "Delete the value for key."))

(defn memory-store
  ([] (memory-store (atom {})))
  ([store-atom]
   (reify Store
     (get* [_ key]
       (@store-atom key))
     (set* [_ key val]
       (swap! store-atom assoc key val)
       nil)
     (del* [_ key]
       (swap! store-atom dissoc key)
       nil))))

(defn get
  [store key]
  (when-let [val (get* store key)]
    (json/read-str val)))

(defn set
  [store key val]
  (set* store key (json/write-str val)))

(defn del
  [store key]
  (del* store key))
