(ns atproto.sync.cursor
  "Cursor persistence for streaming consumers.

  Firehose cursors are relay sequence numbers; Jetstream cursors are
  microsecond timestamps — the same protocol serves both. Runners (see
  atproto.sync.runner) persist the highest consecutively completed cursor
  through a CursorStore so consumption resumes where it left off."
  (:require [clojure.spec.alpha :as s]))

#?(:clj (set! *warn-on-reflection* true))

(defprotocol CursorStore
  :extend-via-metadata true
  (get-cursor [store cb]
    "Read the persisted cursor. Calls cb with {:cursor n} ({:cursor nil}
    when none has been stored) or {:error ...}.")
  (set-cursor [store cursor cb]
    "Persist the cursor. Calls cb with {} or {:error ...}."))

(s/def ::cursor (s/nilable int?))

(defn cursor-store?
  [v]
  (or (satisfies? CursorStore v)
      (let [m (meta v)]
        (boolean (and (get m `get-cursor) (get m `set-cursor))))))

(defn memory-store
  "In-memory CursorStore, optionally seeded:
  (memory-store) or (memory-store {:cursor 123})."
  [& {:keys [cursor]}]
  (let [state (atom cursor)]
    (reify CursorStore
      (get-cursor [_ cb] (cb {:cursor @state}))
      (set-cursor [_ cursor cb]
        (reset! state cursor)
        (cb {})))))
