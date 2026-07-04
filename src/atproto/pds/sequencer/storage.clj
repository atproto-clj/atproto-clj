(ns atproto.pds.sequencer.storage
  "Storage interface for the sequencer's durable, totally-ordered event
  log.

  atproto.pds.sequencer owns the library logic (event formatting,
  DAG-CBOR encoding, timestamps, the poll loop, subscriptions); a
  SequencerStorage owns persistence and ordering. Implementations:
  atproto.pds.sequencer.memory and atproto.pds.sequencer.sqlite.

  Rows are maps:
    {:seq          long        ;; assigned by the storage, total order
     :did          string
     :event-type   \"append\"|\"sync\"|\"identity\"|\"account\"
     :event        bytes       ;; opaque payload (DAG-CBOR, encoded by the library)
     :invalidated? boolean
     :sequenced-at string}     ;; ISO datetime, stamped by the library

  Implementations are synchronous and must be safe to call from any
  thread; they may throw (ex-info with {:error ...} data) on hard
  failures."
  (:refer-clojure :exclude [range]))

(defprotocol SequencerStorage
  (append-event! [storage {:keys [did event-type event sequenced-at]}]
    "Durably append an event. Assigns and returns the next seq (long).
    Appends must be atomic and observe a single total order: once a call
    returns seq n, every read issued afterwards (from any thread) sees
    all rows <= n that were ever assigned.")
  (current-seq [storage]
    "The latest assigned seq, or nil for an empty log.")
  (next-after [storage cursor]
    "The first row with seq > cursor (including invalidated rows), or nil.")
  (earliest-after-time [storage time]
    "The earliest row with sequenced-at >= time (ISO string compare), or nil.")
  (request-range [storage {:keys [earliest-seq latest-seq earliest-time limit]}]
    "Rows with seq in (earliest-seq, latest-seq], oldest first, skipping
    invalidated rows; optionally bounded by earliest-time and limit.")
  (invalidate! [storage seq]
    "Mark the row with this seq invalidated (excluded from request-range;
    still visible to next-after, reference parity).")
  (close! [storage]
    "Release any resources held by the storage."))
