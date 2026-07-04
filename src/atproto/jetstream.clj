(ns atproto.jetstream
  "Jetstream consumer (JSON firehose; see bluesky-social/jetstream).

  Connection management is built on atproto.runtime.ws (exponential backoff
  with jitter, heartbeat dead-connection detection, per-connect URL
  re-resolution), so cursors survive reconnects and a silently dead TCP
  connection no longer hangs the consumer.

  Events are placed on a caller-supplied channel as parsed JSON maps with
  keyword keys (:time_us, :did, :kind, :commit, ...). With :typed? true
  they are instead parsed into :kind-keyed maps matching
  atproto.sync.firehose where applicable:

    {:kind :create | :update | :delete
     :did \"did:...\" :time-us n :collection \"...\" :rkey \"...\" :rev \"...\"
     :uri \"at://did/coll/rkey\"
     :record {...} :cid <cid>}     ;; :create/:update only
    {:kind :identity :did :time-us :seq :time :handle}
    {:kind :account  :did :time-us :seq :time :active :status}
    {:kind :unknown  :raw <original map>}  ;; forward-compatible passthrough

  Zstd-compressed mode (Jetstream's compress=true) is not implemented:
  it requires the custom zstd dictionary from the external
  bluesky-social/jetstream repository; :compress? true returns
  {:error \"UnsupportedOption\"} (WS-05 planning doc, risk 3)."
  (:require [charred.api :as json]
            [clojure.string :as str]
            [clojure.core.async :as a]
            [atproto.data :as data]
            [atproto.data.json :as data-json]
            [atproto.runtime.cast :as cast]
            [atproto.runtime.ws :as ws]
            [atproto.sync.cursor :as cursor])
  (:import [java.time Instant]))

(set! *warn-on-reflection* true)

(def default-host "jetstream1.us-east.bsky.network")

(defn- base-url
  "Jetstream endpoint for a :host option; bare hostnames get wss://, and a
  full ws://.../wss:// URL is used as-is (useful for tests)."
  [host]
  (if (str/includes? host "://")
    (str/replace host #"/+$" "")
    (str "wss://" host)))

(defn- subscribe-url
  [{:keys [host wanted-collections]} cursor]
  (let [params (cond-> []
                 cursor (conj (str "cursor=" cursor))
                 (seq wanted-collections)
                 (into (map #(str "wantedCollections=" %) wanted-collections)))]
    (str (base-url host) "/subscribe"
         (when (seq params)
           (str "?" (str/join "&" params))))))

(def ^:private parse-json
  (json/parse-json-fn {:key-fn keyword :async? false :bufsize 8192}))

(defn current-time-us
  "Helper function to return the current time in microseconds"
  []
  (* (System/currentTimeMillis) 1000))

(defn us-str
  "Helper function to render a microsecond value as a human-readable date"
  [us]
  (let [seconds (long (/ us 1e6))
        nanoseconds (* 1000 (- us (* seconds (long 1e6))))]
    (str (Instant/ofEpochSecond seconds nanoseconds))))

(defn typed-event
  "Parse a raw Jetstream JSON event map into a :kind-keyed typed event
  (shapes in the ns docstring). Unknown kinds pass through as
  {:kind :unknown :raw event}."
  [{:keys [did time_us kind commit identity account] :as event}]
  (case kind
    "commit"
    (let [{:keys [operation collection rkey rev record cid]} commit
          op-kind (case operation
                    "create" :create
                    "update" :update
                    "delete" :delete
                    nil)]
      (if-not op-kind
        {:kind :unknown :raw event}
        (cond-> {:kind op-kind
                 :did did
                 :time-us time_us
                 :collection collection
                 :rkey rkey
                 :rev rev
                 :uri (str "at://" did "/" collection "/" rkey)}
          (some? record) (assoc :record (data-json/decode record))
          cid (assoc :cid (or (data/parse-cid cid) cid)))))

    "identity"
    (cond-> {:kind :identity
             :did did
             :time-us time_us
             :seq (:seq identity)
             :time (:time identity)}
      (:handle identity) (assoc :handle (:handle identity)))

    "account"
    (cond-> {:kind :account
             :did did
             :time-us time_us
             :seq (:seq account)
             :time (:time account)
             :active (:active account)}
      (:status account) (assoc :status (:status account)))

    {:kind :unknown :raw event}))

(defn- cursor-url-fn
  "url-fn re-invoked on every (re)connect. A cursor derived from processed
  events (the store, or the in-memory last event) is rewound by 1µs so no
  event is missed at the boundary (at-least-once); the caller's initial
  :cursor is used as-is."
  [opts cursor-store initial-cursor last-event-cursor]
  (fn [cb]
    (let [finish (fn [stored]
                   (let [processed (or stored @last-event-cursor)]
                     (cb (subscribe-url opts (if processed
                                               (dec (long processed))
                                               initial-cursor)))))]
      (if cursor-store
        (cursor/get-cursor cursor-store
                           (fn [{:keys [error cursor] :as resp}]
                             (if error (cb resp) (finish cursor))))
        (finish nil)))))

(defn consume
  "Place messages from the jetstream on the supplied channel. Reconnects
   automatically if the socket closes unexpectedly.

   Returns a control channel. Closing the control channel halts processing.
   (With :compress? true, returns {:error \"UnsupportedOption\"} instead —
   see the namespace docstring.)

   Options:

   - host (default: jetstream1.us-east.bsky.network)
   - cursor (value in μs) (default: none)
   - wanted-collections (coll of collection ids) (default: nil (i.e. everything))
   - cursor-store (atproto.sync.cursor/CursorStore) — read on every
     (re)connect, written with :time_us as events are placed on ch;
     lets consumption resume across restarts (default: none)
   - typed? — parse events with `typed-event` before placing them on ch
     (default: false, raw JSON maps)
   - compress? — Jetstream zstd mode; not implemented, returns
     {:error \"UnsupportedOption\"}
   - close? (default: true) - close the ch upon disconnection?
   - max-retries (deprecated, ignored) — reconnection now uses capped
     exponential backoff with jitter and retries until the control channel
     is closed
   - ws-opts — extra options for atproto.runtime.ws/connect
     (:max-reconnect-ms, :heartbeat-interval-ms, ...)"
  [ch & {:keys [host cursor control-ch max-retries wanted-collections close?
                cursor-store typed? compress? ws-opts]
         :or {host default-host
              control-ch (a/chan)
              close? true}
         :as opts}]
  (if compress?
    {:error "UnsupportedOption"
     :message "Jetstream zstd mode is not implemented; consume plain JSON instead."}
    (let [opts {:host host :wanted-collections wanted-collections}
          last-event-cursor (volatile! nil)
          state (atom {:stopped? false :ws nil})
          on-message
          (fn [msg]
            (when (string? msg)
              (let [event (try
                            (parse-json msg)
                            (catch Exception e
                              (cast/alert {:message "Undecodable Jetstream event."
                                           :ex e})
                              nil))]
                (when (map? event)
                  ;; Blocking put: backpressures the socket, which requests
                  ;; one message at a time.
                  (when (a/>!! ch (if typed? (typed-event event) event))
                    (when-let [us (:time_us event)]
                      (vreset! last-event-cursor us)
                      (when cursor-store
                        (cursor/set-cursor
                         cursor-store us
                         (fn [{:keys [error] :as resp}]
                           (when error
                             (cast/alert (assoc resp :message "Jetstream cursor write failed."))))))))))))
          connect!
          (fn connect! []
            (when-not (:stopped? @state)
              (let [reconnect (fn [info]
                                (when-not (:stopped? @state)
                                  (cast/event {:message "Jetstream connection ended; re-subscribing."
                                               :info info})
                                  (future
                                    (Thread/sleep 3000)
                                    (connect!))))
                    handle (ws/connect
                            (merge {:max-reconnect-ms 64000}
                                   ws-opts
                                   {:url-fn (cursor-url-fn opts cursor-store
                                                           cursor last-event-cursor)
                                    :on-message on-message
                                    :on-error (fn [{:keys [fatal] :as err}]
                                                (cast/event {:message "Jetstream websocket error."
                                                             :error (:error err)})
                                                (when fatal (reconnect err)))
                                    :on-close (fn [info]
                                                (when-not (:stopped? @state)
                                                  (reconnect info)))}))]
                (swap! state assoc :ws handle))))]
      (connect!)
      (a/go-loop []
        (let [cmd (a/<! control-ch)]
          (if (some? cmd)
            (do (cast/event {:message "Unknown Jetstream command." :command cmd})
                (recur))
            ;; control channel closed -> shutdown
            (let [[{:keys [ws]} _] (swap-vals! state assoc :stopped? true)]
              (when ws (ws/close! ws))
              (when close? (a/close! ch))))))
      control-ch)))

(comment

  ;; Define a channel to recieve events
  (def events-ch (a/chan))

  ;; Subscribe to the jetstream
  (def control-ch (consume events-ch :wanted-collections ["app.bsky.feed.post"]))

  ;; Typed events with a persistent cursor:
  ;; (require '[atproto.sync.cursor :as cursor])
  ;; (def store (cursor/memory-store))
  ;; (def control-ch (consume events-ch :typed? true :cursor-store store))

  ;; Consume events
  (a/go-loop [count 0]
    (if-let [event (a/<! events-ch)]
      (do
        (when (zero? (rem count 100)) (println (format "Got %s posts" count)))
        (recur (inc count)))
      (println "event channel closed")))

  ;; Stop processing
  (a/close! control-ch)

  )
