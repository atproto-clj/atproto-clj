(ns statusphere.handles
  "DID → handle display names, resolved through the SDK's
  stale-while-revalidate identity cache. A page render costs at most one
  live resolution per never-seen DID; repeats are cache hits."
  (:require [clojure.core.async :as a]
            [atproto.identity :as identity]
            [atproto.identity.cache :as cache]))

(set! *warn-on-reflection* true)

(defn resolver
  "Held by the :handle-resolver component."
  []
  {:cache (cache/memory-cache)})

(defn <did->handle
  "Channel of the handle for this DID, falling back to the DID itself on
  any resolution failure."
  [{:keys [cache]} did]
  (a/go
    (let [{:keys [error handle]}
          (a/<! (identity/resolve-identity did :cache cache
                                           :channel (a/promise-chan)))]
      (if (or error (nil? handle)) did handle))))

(defn <resolve-all
  "Channel of {did handle} for the distinct DIDs in dids. Sequential —
  the cache makes repeats free, and a page has few distinct authors."
  [resolver dids]
  (a/go-loop [m {}
              [did & more] (distinct (remove nil? dids))]
    (if did
      (recur (assoc m did (a/<! (<did->handle resolver did))) more)
      m)))
