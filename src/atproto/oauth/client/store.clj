(ns atproto.oauth.client.store
  (:refer-clojure :exclude [get set])
  (:require [atproto.runtime.json :as json]
            [atproto.runtime.crypto :as crypto]))

(defprotocol Store
  "Interface for the key/value stores needed by the OAuth 2 client.

  Implementations are synchronous; values are JSON strings.

  Pending-authorization state entries are short-lived: the client stamps
  :created-at/:expires-at (epoch seconds) into state values and rejects
  entries older than one hour, so implementations do not need to expire rows
  for correctness — but they should delete state rows older than an hour to
  avoid unbounded growth from abandoned authorization flows."
  (get* [_ key] "The value for key, or nil.")
  (set* [_ key val] "Set the value for key.")
  (del* [_ key] "Delete the value for key."))

(defn- entry-expires-at
  "The expiry (epoch seconds) declared by this JSON value, if any."
  [val]
  (try
    (let [expires-at (:expires-at (json/read-str val))]
      (when (int? expires-at)
        expires-at))
    (catch Exception _)))

(defn memory-store
  "In-memory store.

  Values written with a top-level :expires-at field (epoch seconds) are
  treated as expired after that time: they are lazily evicted on reads and
  writes and never returned. Values without :expires-at (e.g. sessions) are
  kept forever."
  ([] (memory-store (atom {})))
  ([store-atom]
   (letfn [(expired? [entry now]
             (when-let [expires-at (:expires-at entry)]
               (<= expires-at now)))]
     (reify Store
       (get* [_ key]
         (when-let [entry (clojure.core/get @store-atom key)]
           (if (expired? entry (crypto/now))
             (do (swap! store-atom dissoc key)
                 nil)
             (:val entry))))
       (set* [_ key val]
         (let [now (crypto/now)
               entry {:val val :expires-at (entry-expires-at val)}]
           (swap! store-atom
                  (fn [m]
                    (-> (into {} (remove (fn [[_ e]] (expired? e now))) m)
                        (assoc key entry))))
           nil))
       (del* [_ key]
         (swap! store-atom dissoc key)
         nil)))))

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
