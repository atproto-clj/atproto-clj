(ns atproto.pds.firehose
  "com.atproto.sync.subscribeRepos emission over WS-08's subscription
  transport.

  This namespace owns the outbox: the three-phase
  backfill -> cutover -> live delivery algorithm from
  packages/pds/src/sequencer/outbox.ts, and the cursor semantics from
  packages/pds/src/api/com/atproto/sync/subscribeRepos.ts:20-42. WS-08
  owns the websocket upgrade, frame encoding, per-route auth, and close
  codes; `handler` plugs into the
  atproto.xrpc.server/handle-subscription multimethod:

    (defmethod xrpc-server/handle-subscription \"com.atproto.sync.subscribeRepos\"
      [app-ctx request]
      (firehose/handler (:sequencer app-ctx) request))

  Live events are buffered per connection (bounded, default 500); when a
  consumer cannot keep up the buffer overflows, the stream ends with a
  {:frame/error \"ConsumerTooSlow\"} item, and the transport sends the
  error frame and closes with ws code 1008."
  (:require [clojure.core.async :as async]
            [atproto.runtime.cast :as cast]
            [atproto.pds.sql :as sql]
            [atproto.pds.sequencer :as sequencer])
  (:import [java.time Instant]))

(set! *warn-on-reflection* true)

(def nsid "com.atproto.sync.subscribeRepos")

(def default-max-buffer-size 500)
(def default-backfill-window-ms 86400000) ;; 1 day (reference repoBackfillLimitMs)
(def backfill-page-size 500)

(defn- wire-msg
  "The subscribeRepos message map for a decoded sequencer event; the
  frame layer lifts :$type into the frame header t."
  [{:keys [seq time type event]}]
  (assoc event
         :seq seq
         :time time
         :$type (str nsid "#" (name type))))

(defn- send!
  "Blocking put on messages, abandoned if the client disconnects.
  Returns true when the message was accepted."
  [messages close-ch msg]
  (let [[_ port] (async/alts!! [[messages msg] close-ch] :priority true)]
    (not= port close-ch)))

(defn- check-outdated-cursor
  "[intro-msg effective-cursor]: when the cursor's next event is older
  than the backfill window, an OutdatedCursor #info message and the
  cursor of the earliest in-window event."
  [seqr cursor current window-ms]
  (let [next-evt (sequencer/next-after seqr cursor)
        window-start (sql/iso (.minusMillis (Instant/now) (long window-ms)))]
    (if (and next-evt (neg? (compare (:time next-evt) window-start)))
      [{:$type (str nsid "#info")
        :name "OutdatedCursor"
        :message "Requested cursor exceeded limit. Possibly missing events"}
       (if-let [earliest (sequencer/earliest-after-time seqr window-start)]
         (dec (:seq earliest))
         current)]
      [nil cursor])))

(defn- run-backfill
  "Page through sequenced events after cursor. Returns the last seq
  forwarded (or the cursor when already caught up); nil on disconnect.
  Backfill ends within half a page of the head (outbox.ts getBackfill)."
  [seqr messages close-ch cursor]
  (loop [cursor cursor]
    (let [page (sequencer/request-range seqr {:earliest-seq cursor
                                              :limit backfill-page-size})
          sent (reduce (fn [_ evt]
                         (if (send! messages close-ch (wire-msg evt))
                           (:seq evt)
                           (reduced nil)))
                       cursor
                       page)]
      (cond
        (nil? sent) nil
        (< (count page) (quot backfill-page-size 2)) sent
        :else (recur (long sent))))))

(defn- run-live
  "Forward buffered live events with seq > last-seen until disconnect or
  overflow."
  [messages close-ch live-buf overflowed? last-seen]
  (loop [last-seen (long last-seen)]
    (let [[evt port] (async/alts!! [close-ch live-buf] :priority true)]
      (cond
        (= port close-ch) nil

        ;; live-buf closed: overflow (or shutdown)
        (nil? evt)
        (when @overflowed?
          (send! messages close-ch
                 {:frame/error "ConsumerTooSlow"
                  :frame/message "Stream consumer too slow"}))

        ;; dedupe events already delivered by backfill
        (<= (:seq evt) last-seen) (recur last-seen)

        (send! messages close-ch (wire-msg evt)) (recur (long (:seq evt)))

        :else nil))))

(defn handler
  "handle-subscription implementation for com.atproto.sync.subscribeRepos
  backed by an atproto.pds.sequencer.

  request: the WS-08 xrpc-request ({:params {:cursor n}} plus :close-ch,
  closed by the transport on client disconnect).

  opts:
    :max-buffer-size     bounded per-connection live buffer (default 500);
                         overflow emits {:frame/error \"ConsumerTooSlow\"}
    :backfill-window-ms  events older than this are not backfilled
                         (default 86400000 = 1 day)

  Cursor semantics:
    cursor > current seq       -> {:frame/error \"FutureCursor\"} only
    cursor older than window   -> #info OutdatedCursor first, then resume
                                  from the earliest in-window event
    no cursor                  -> live tail only

  Returns {:messages ch} per the WS-08 contract; every event between the
  cursor and disconnect is delivered exactly once, in seq order."
  [seqr {:keys [params close-ch]} & {:keys [max-buffer-size backfill-window-ms]
                                     :or {max-buffer-size default-max-buffer-size
                                          backfill-window-ms default-backfill-window-ms}}]
  (let [messages (async/chan)
        cursor (:cursor params)
        live-buf (async/chan max-buffer-size)
        overflowed? (atom false)
        listener (fn [batch]
                   (doseq [evt batch]
                     (when-not @overflowed?
                       (when-not (async/offer! live-buf evt)
                         (reset! overflowed? true)
                         (async/close! live-buf)))))
        unsub (atom nil)]
    (async/thread
      (try
        (let [current (or (sequencer/current-seq seqr) 0)]
          (if (and cursor (> cursor current))
            (send! messages close-ch
                   {:frame/error "FutureCursor"
                    :frame/message "Cursor in the future."})
            (let [[intro effective-cursor]
                  (if cursor
                    (check-outdated-cursor seqr cursor current backfill-window-ms)
                    [nil nil])]
              ;; subscribe before backfilling so no event falls between
              ;; the last backfill page and the first live delivery
              (reset! unsub (sequencer/subscribe seqr listener))
              (when (or (nil? intro) (send! messages close-ch intro))
                (let [last-seen (if effective-cursor
                                  (run-backfill seqr messages close-ch effective-cursor)
                                  current)]
                  (when last-seen
                    (run-live messages close-ch live-buf overflowed? last-seen)))))))
        (catch Throwable t
          (cast/alert {:message "subscribeRepos outbox failed" :ex t}))
        (finally
          (when-let [u @unsub] (u))
          (async/close! messages))))
    {:messages messages}))
