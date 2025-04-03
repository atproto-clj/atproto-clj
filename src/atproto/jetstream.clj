(ns atproto.jetstream
  (:require [charred.api :as json]
            [clojure.string :as str]
            [clojure.core.async :as a]
            [clojure.tools.logging :as log])
  (:import [java.net.http HttpClient WebSocket WebSocket$Listener]
           [java.net URI]
           [java.time Duration Instant]))

(defrecord JetstreamListener [^StringBuilder sb ch start-ch]
  WebSocket$Listener
  (onOpen [this websocket]
    (log/info "websocket listener opened")
    (a/go (a/<! start-ch) (.request websocket 1)))

  (onClose [this websocket status reason]
    (a/close! ch)
    (log/info "websocket listener closed" {:status status :reason reason}))

  (onError [this websocket error]
    (a/close! ch)
    (log/error "websocket listener error" error))

  (onText [this websocket chars last]
    (try
      (.append sb chars)
      (when last
        (a/>!! ch (str sb))
        (.setLength sb 0))
      (.request websocket 1))))

(defn- ^WebSocket connect
  [uri ch retries max-retries]
  (log/info "connecting to" uri)
  (try
    (let [start-ch (a/promise-chan)
          listener (->JetstreamListener (StringBuilder.) ch start-ch)
          socket-promise (-> (HttpClient/newHttpClient)
                           (.newWebSocketBuilder)
                           (.connectTimeout (Duration/ofSeconds 30))
                           (.buildAsync uri listener))
          socket @socket-promise]
      (log/info "connected to" (str uri))
      (a/>!! start-ch :ok)
      socket)
    (catch Exception e
      (if (< retries max-retries)
        (let [wait-time (int (Math/pow 3 retries))]
          (log/warn "Connection failed: retrying in" wait-time "seconds" e)
          (Thread/sleep (int (* 1000 wait-time)))
          (connect uri ch (inc retries) max-retries))
        (do
          (log/error "Connection failed" (str uri) e)
          (a/close! ch)
          (throw e))))))

(defn- uri
  [& {:keys [host wanted-collections cursor]}]
  (let [params
        (cond-> []
          cursor
          (conj ["cursor" (str cursor)])
          (seq wanted-collections)
          (concat (map #(vector "wantedCollections" %) wanted-collections)))]
    (URI.
      (str "wss://" host "/subscribe"
        (when-not (empty? params)
          (str "?" (str/join "&"
                     (map (fn [[k v]] (str k "=" v))
                       params))))))))

(def ^:private parse-json-xf
  (let [parse-fn (json/parse-json-fn
                   {:key-fn keyword
                    :async? false
                    :bufsize 8192})]
    (map parse-fn)))

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

(defn consume
  "Place messages from the jetstream on the supplied channel. Reconnects
   automatically if the socket closes unexpectedly.

   Returns a control channel. Closing the control channel halts processing.

   Options:

   - host (default: jetstream1.us-east.bsky.network)
   - cursor (value in μs) (default: none)
   - wanted-collections (coll of collection ids) (default: nil (i.e. everything))
   - max-retries (default: 4)
   - close? (default: true) - close the ch upon disconnection?"
  [ch & {:keys [host cursor control-ch max-retries wanted-collections close?]
         :or {host "jetstream1.us-east.bsky.network"
              control-ch (a/chan)
              max-retries 4
              close? true}}]
  (let [opts {:host host
              :cursor cursor
              :control-ch control-ch
              :max-retries max-retries
              :wanted-collections wanted-collections}
        listener-ch (a/chan 1 parse-json-xf identity)
        socket (connect (uri opts) listener-ch 0 max-retries)
        last-cursor (volatile! (or cursor 0))]
    (a/go-loop []
      (a/alt!
        control-ch ([cmd] (if cmd
                            (do (log/warn "Unknown command:" cmd)  (recur))
                            (do
                              (log/info "Shutdown command recieved")
                              (a/close! listener-ch)
                              (.sendClose socket WebSocket/NORMAL_CLOSURE "complete")
                              (.abort socket)
                              (when close? (a/close! ch)))))
        listener-ch ([data]
                     (if-not data
                       (do
                         (log/info "Lost connection at" (us-str @last-cursor))
                         (consume ch (assoc opts :cursor (- @last-cursor 1))))
                       (do
                         (when-let [us (:time_us data)]
                           (vreset! last-cursor us))
                         (a/>! ch data)
                         (recur))))
        :priority true))
    control-ch))

(comment

  ;; Define a channel to recieve events
  (def events-ch (a/chan))

  ;; Subscribe to the jetstream
  (def control-ch (consume events-ch :wanted-collections ["app.bsky.feed.post"]))

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
