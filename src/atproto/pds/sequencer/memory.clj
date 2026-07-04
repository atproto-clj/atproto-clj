(ns atproto.pds.sequencer.memory
  "In-memory SequencerStorage (atom-backed) for tests and development.
  Satisfies the same total-order contract as the SQLite implementation;
  nothing survives the process."
  (:require [atproto.pds.sequencer.storage :as storage]))

(set! *warn-on-reflection* true)

(defrecord MemorySequencerStorage [state]
  storage/SequencerStorage
  (append-event! [_ {:keys [did event-type event sequenced-at]}]
    (let [[old _] (swap-vals!
                   state
                   (fn [{:keys [next-seq] :as st}]
                     (-> st
                         (update :rows assoc next-seq
                                 {:seq next-seq
                                  :did did
                                  :event-type event-type
                                  :event event
                                  :invalidated? false
                                  :sequenced-at sequenced-at})
                         (update :next-seq inc))))]
      (:next-seq old)))
  (current-seq [_]
    (when-let [entry (last (:rows @state))]
      (key entry)))
  (next-after [_ cursor]
    (some-> (subseq (:rows @state) > (long (or cursor 0)))
            first
            val))
  (earliest-after-time [_ time]
    (->> (vals (:rows @state))
         (filter #(<= 0 (compare (:sequenced-at %) time)))
         (sort-by :sequenced-at)
         first))
  (request-range [_ {:keys [earliest-seq latest-seq earliest-time limit]}]
    (into []
          (comp (map val)
                (remove :invalidated?)
                (filter #(or (nil? latest-seq) (<= (:seq %) latest-seq)))
                (filter #(or (nil? earliest-time)
                             (<= 0 (compare (:sequenced-at %) earliest-time))))
                (take (or limit Long/MAX_VALUE)))
          (subseq (:rows @state) > (long (or earliest-seq 0)))))
  (invalidate! [_ seq]
    (swap! state (fn [st]
                   (cond-> st
                     (get-in st [:rows (long seq)])
                     (assoc-in [:rows (long seq) :invalidated?] true))))
    nil)
  (close! [_] nil))

(defn open
  "A fresh in-memory sequencer storage."
  []
  (->MemorySequencerStorage (atom {:next-seq 1 :rows (sorted-map)})))
