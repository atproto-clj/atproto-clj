(ns atproto.runtime.ws
  "Cross-platform reconnecting WebSocket consumer.

  Port of the reference @atproto/ws-client WebSocketKeepAlive: the URL is
  re-resolved before every (re)connect, reconnects use exponential backoff
  with jitter capped at :max-reconnect-ms, and a ping/pong heartbeat
  terminates silently-dead connections. The JVM implementation uses
  java.net.http.WebSocket; ClojureScript is not yet implemented (connect
  returns {:error \"NotImplemented\"}).

  Terminal semantics: a clean close (initiated by either side) invokes
  :on-close exactly once and stops reconnecting; a fatal error (bad
  handshake, protocol failure) invokes :on-error with :fatal true exactly
  once and stops reconnecting. Reconnectable failures (IO errors, abnormal
  closes, dead heartbeats) invoke :on-error with :fatal false and
  reconnect."
  (:require [clojure.spec.alpha :as s]
            [atproto.runtime.cast :as cast]
            #?(:clj [clojure.core.async :as a]))
  #?(:clj (:import [java.io ByteArrayOutputStream]
                   [java.net URI ProtocolException]
                   [java.net.http HttpClient WebSocket WebSocket$Builder
                    WebSocket$Listener WebSocketHandshakeException]
                   [java.nio ByteBuffer]
                   [java.time Duration]
                   [java.util.concurrent CompletableFuture CompletionException
                    CompletionStage]
                   [java.util.function BiFunction Function])))

#?(:clj (set! *warn-on-reflection* true))

(s/def ::url-fn fn?)
(s/def ::headers (s/map-of keyword? string?))
(s/def ::on-message fn?)
(s/def ::on-error fn?)
(s/def ::on-reconnect fn?)
(s/def ::on-close fn?)
(s/def ::max-reconnect-ms pos-int?)
(s/def ::heartbeat-interval-ms pos-int?)
(s/def ::config
  (s/keys :req-un [::url-fn ::on-message]
          :opt-un [::headers ::on-error ::on-reconnect ::on-close
                   ::max-reconnect-ms ::heartbeat-interval-ms]))

(def default-max-reconnect-ms 64000)
(def default-heartbeat-interval-ms 10000)

(defn backoff-ms
  "Reconnect delay for the nth consecutive failed attempt: 2^n seconds with
  +-0.5s jitter, capped at max-ms (reference ws-client backoffMs)."
  [attempt max-ms]
  (let [base-sec #?(:clj (Math/pow 2 attempt) :cljs (js/Math.pow 2 attempt))
        rand-sec (- (rand) 0.5)]
    (long (max 0 (min (* 1000 (+ base-sec rand-sec)) max-ms)))))

#?(:clj
   (defn- buffer->bytes
     ^bytes [^ByteBuffer buf]
     (let [arr (byte-array (.remaining buf))]
       (.get buf arr)
       arr)))

#?(:clj
   (defn- deliver-message
     "Invoke the user's :on-message on the socket listener thread; a throwing
     handler must not take down the connection."
     [on-message msg]
     (try
       (on-message msg)
       (catch Throwable t
         (cast/alert {:message "WebSocket :on-message handler threw."
                      :ex t})))))

#?(:clj
   (defn- connection-listener
     "WebSocket$Listener for a single connection attempt. Lifecycle events go
     to conn-ch; complete messages are handed to on-message directly on the
     listener thread (one message of demand at a time, requested only after
     the handler returns, for natural backpressure)."
     [conn-ch on-message activity]
     (let [sb (StringBuilder.)
           bos (ByteArrayOutputStream.)
           now #?(:clj #(System/currentTimeMillis) :cljs #(js/Date.now))]
       (reify WebSocket$Listener
         (onOpen [_ ws]
           (vreset! activity (now))
           (a/put! conn-ch [:open ws])
           (.request ws 1))
         (onText [_ ws chars last?]
           (vreset! activity (now))
           (.append sb chars)
           (when last?
             (let [msg (str sb)]
               (.setLength sb 0)
               (deliver-message on-message msg)))
           (.request ws 1)
           nil)
         (onBinary [_ ws buf last?]
           (vreset! activity (now))
           (let [^bytes chunk (buffer->bytes buf)]
             (.write bos chunk 0 (alength chunk)))
           (when last?
             (let [msg (.toByteArray bos)]
               (.reset bos)
               (deliver-message on-message msg)))
           (.request ws 1)
           nil)
         (onPong [_ ws _]
           (vreset! activity (now))
           (a/put! conn-ch [:pong])
           (.request ws 1)
           nil)
         (onClose [_ _ code reason]
           (a/put! conn-ch [:close {:code code :reason reason}])
           nil)
         (onError [_ _ err]
           (a/put! conn-ch [:error err])
           nil)))))

#?(:clj
   (defn- unwrap-completion
     ^Throwable [^Throwable t]
     (if (and (instance? CompletionException t) (.getCause t))
       (.getCause t)
       t)))

#?(:clj
   (defn- connect-error
     "Classify a connection-attempt failure. Handshake rejections (the server
     spoke HTTP and refused the upgrade) are fatal; IO-level failures are
     reconnectable."
     [t]
     (let [cause (unwrap-completion t)]
       (if (instance? WebSocketHandshakeException cause)
         {:error "WSHandshakeFailed" :fatal true
          :message (str "WebSocket handshake failed: " (ex-message cause))
          :exception cause}
         {:error "WSConnectionFailed" :fatal false
          :message (str "WebSocket connection failed: " (ex-message cause))
          :exception cause}))))

#?(:clj
   (defn- socket-error
     "Classify an error on an established socket. Protocol violations are
     fatal; IO errors are reconnectable (reference isReconnectable)."
     [t]
     (let [cause (unwrap-completion t)]
       (if (instance? ProtocolException cause)
         {:error "WSProtocolError" :fatal true
          :message (str "WebSocket protocol error: " (ex-message cause))
          :exception cause}
         {:error "WSConnectionError" :fatal false
          :message (str "WebSocket connection error: " (ex-message cause))
          :exception cause}))))

#?(:clj
   (defn- open-socket!
     "Start a connection attempt; lifecycle events arrive on conn-ch."
     [^HttpClient client url headers conn-ch on-message activity]
     (let [builder (reduce-kv (fn [^WebSocket$Builder b k v]
                                (.header b (name k) (str v)))
                              (-> (.newWebSocketBuilder client)
                                  (.connectTimeout (Duration/ofSeconds 30)))
                              (or headers {}))
           fut (.buildAsync ^WebSocket$Builder builder
                            (URI/create url)
                            (connection-listener conn-ch on-message activity))]
       (.handle ^CompletableFuture fut
                (reify BiFunction
                  (apply [_ _ err]
                    (when err
                      (a/put! conn-ch [:connect-error err]))
                    nil))))))

#?(:clj
   (defn- ping!
     [ws]
     (try (.sendPing ^WebSocket ws (ByteBuffer/allocate 0))
          (catch Exception _))))

#?(:clj
   (defn- abort!
     [ws]
     (try (.abort ^WebSocket ws) (catch Exception _))))

#?(:clj
   (defn- terminate!
     "Deliver the terminal callback exactly once."
     [{:keys [terminated config state]} kind payload]
     (when (compare-and-set! terminated false true)
       (swap! state assoc :connected? false :closed? true)
       (case kind
         :close (when-let [on-close (:on-close config)]
                  (on-close payload))
         :error (when-let [on-error (:on-error config)]
                  (on-error (assoc payload :fatal true)))))))

#?(:clj
   (defn- babysit-connection
     "Wait on one connection until it ends. Yields (as the go block's value)
     {:opened? bool :outcome :retry|:close|:fatal|:shutdown, ...}."
     [{:keys [state config]} conn-ch activity connected-before?]
     (let [{:keys [on-error on-reconnect heartbeat-interval-ms]
            :or {heartbeat-interval-ms default-heartbeat-interval-ms}} config]
       (a/go-loop [ws nil
                   alive? true
                   opened? false]
         (let [hb (when ws (a/timeout heartbeat-interval-ms))
               [v port] (a/alts! (if hb [conn-ch hb] [conn-ch]))]
           (if (= port hb)
             ;; Heartbeat tick: dead only when the previous ping got no pong
             ;; AND nothing at all arrived in the meantime (inbound traffic
             ;; proves liveness even while a slow consumer delays pong
             ;; delivery, since listener callbacks are serialized).
             (if (or alive?
                     (< (- (System/currentTimeMillis) @activity)
                        heartbeat-interval-ms))
               (do (ping! ws)
                   (recur ws false opened?))
               (do (abort! ws)
                   {:opened? opened?
                    :outcome :retry
                    :error {:error "WSHeartbeatTimeout" :fatal false
                            :message "No pong received; terminating dead connection."}}))
             (let [[tag payload] v]
               (case tag
                 :open (do (swap! state assoc :socket payload :connected? true)
                           (when (and connected-before? on-reconnect)
                             (on-reconnect))
                           (recur payload alive? true))
                 :pong (recur ws true opened?)
                 ;; 1006 is the reserved "abnormal closure" code: the
                 ;; connection died without a closing handshake. Reconnect,
                 ;; like the reference's AbnormalCloseError (ws-client
                 ;; index.ts:50-57).
                 :close (if (= 1006 (:code payload))
                          {:opened? opened?
                           :outcome :retry
                           :error {:error "WSAbnormalClose" :fatal false
                                   :message (str "Abnormal WebSocket close: "
                                                 (:reason payload))}}
                          {:opened? opened? :outcome :close :close payload})
                 :error {:opened? opened?
                         :outcome (if (:fatal (socket-error payload)) :fatal :retry)
                         :error (socket-error payload)}
                 :connect-error {:opened? opened?
                                 :outcome (if (:fatal (connect-error payload)) :fatal :retry)
                                 :error (connect-error payload)}
                 :shutdown {:opened? opened? :outcome :shutdown}))))))))

#?(:clj
   (defn- run-loop
     "Drive connect / babysit / backoff cycles until a terminal event."
     [{:keys [state config client] :as handle}]
     (let [{:keys [url-fn headers on-message on-error max-reconnect-ms]
            :or {max-reconnect-ms default-max-reconnect-ms}} config]
       (a/go-loop [attempts nil          ;; consecutive failures; nil = first ever
                   connected-before? false]
         (when-not (:closed? @state)
           (when attempts
             (a/<! (a/timeout (if connected-before?
                                (backoff-ms attempts max-reconnect-ms)
                                ;; Never yet connected: constant short retry
                                ;; (reference initialSetup branch).
                                (min 1000 max-reconnect-ms)))))
           (when-not (:closed? @state)
             (let [url-ch (a/promise-chan)
                   _ (url-fn #(a/put! url-ch %))
                   url (a/<! url-ch)]
               (if (or (map? url) (not (string? url)))
                 (terminate! handle :error
                             (if (and (map? url) (:error url))
                               url
                               {:error "WSInvalidUrl"
                                :message (str "url-fn returned " (pr-str url))}))
                 (let [conn-ch (a/chan (a/sliding-buffer 16))
                       activity (volatile! 0)]
                   (swap! state assoc :conn-ch conn-ch)
                   (open-socket! client url headers conn-ch on-message activity)
                   (let [{:keys [opened? outcome error close]}
                         (a/<! (babysit-connection handle conn-ch activity connected-before?))]
                     (swap! state assoc :connected? false :socket nil)
                     (case outcome
                       :shutdown (terminate! handle :close
                                             {:code 1000 :reason "Closed by client."})
                       :close (do (cast/event {:message "WebSocket closed cleanly."
                                               :code (:code close)})
                                  (terminate! handle :close close))
                       :fatal (terminate! handle :error error)
                       :retry (do (when-not (:closed? @state)
                                    ;; A stale socket error racing close! is
                                    ;; not worth reporting.
                                    (when on-error
                                      (on-error (assoc error :fatal false)))
                                    (cast/event {:message "WebSocket reconnecting."
                                                 :error (:error error)}))
                                  (recur (if opened? 0 (inc (or attempts -1)))
                                         (or connected-before? opened?))))))))))))))

(defn connect
  "Open a self-healing WebSocket subscription.

  Config map:
    :url-fn          (fn [cb]) -> calls cb with the URL string (or {:error ...}).
                     Re-invoked before every (re)connect so cursor params stay
                     fresh.
    :headers         map of extra headers (e.g. Tap admin auth).
    :on-message      (fn [msg]) msg is platform bytes for binary frames, a
                     string for text. Called on the socket listener thread with
                     one message of demand at a time; must not block long.
    :on-error        (fn [{:keys [error message exception fatal]}]) non-fatal
                     (reconnectable, :fatal false) and fatal (:fatal true,
                     terminal) errors.
    :on-reconnect    (fn []) called after a successful reconnect (not the
                     first connect).
    :on-close        (fn [{:keys [code reason]}]) terminal: no further
                     reconnects. Called for clean server closes and close!.
    :max-reconnect-ms        cap for backoff (default 64000).
    :heartbeat-interval-ms   ping interval; the connection is terminated and
                             reopened when a ping gets no pong (default 10000).

  Reconnect policy (port of @atproto/ws-client): exponential backoff 2^n
  seconds with +-0.5s jitter, capped; IO errors, abnormal closes, and dead
  heartbeats reconnect; a clean close (either side) ends the subscription via
  :on-close; handshake rejections and protocol failures end it via :on-error
  with :fatal true.

  Returns a handle for use with `send!`, `close!` and `connected?`."
  [config]
  #?(:clj
     (do (when-not (s/valid? ::config config)
           (throw (ex-info "Invalid WebSocket config."
                           {:error "InvalidWSConfig"
                            :message (s/explain-str ::config config)})))
         (let [handle {:config config
                       :state (atom {:closed? false :connected? false
                                     :socket nil :conn-ch nil})
                       :terminated (atom false)
                       :send-chain (volatile! (CompletableFuture/completedFuture nil))
                       :client (HttpClient/newHttpClient)}]
           (run-loop handle)
           handle))
     :cljs {:error "NotImplemented"
            :message "The WebSocket client is not yet implemented on ClojureScript."}))

(defn send!
  "Send a text (string) or binary (bytes) message. Calls cb with {} or
  {:error ...}. Sends are serialized; used by the Tap channel for acks."
  [handle msg cb]
  #?(:clj
     (let [{:keys [state send-chain]} handle
           task (reify Function
                  (apply [_ _]
                    (let [{:keys [^WebSocket socket connected?]} @state]
                      (if-not (and socket connected?)
                        (do (cb {:error "NotConnected"
                                 :message "The WebSocket is not connected."})
                            (CompletableFuture/completedFuture nil))
                        (try
                          (-> (if (string? msg)
                                (.sendText socket ^CharSequence msg true)
                                (.sendBinary socket (ByteBuffer/wrap ^bytes msg) true))
                              (.handle (reify BiFunction
                                         (apply [_ _ err]
                                           (cb (if err
                                                 {:error "WSSendFailed"
                                                  :message (ex-message (unwrap-completion err))}
                                                 {}))
                                           nil))))
                          (catch Exception e
                            (cb {:error "WSSendFailed" :message (ex-message e)})
                            (CompletableFuture/completedFuture nil)))))))]
       (locking send-chain
         (vswap! send-chain
                 (fn [^CompletableFuture prev]
                   (-> prev
                       (.handle (reify BiFunction (apply [_ _ _] nil)))
                       (.thenCompose ^Function task)))))
       nil)
     :cljs (cb {:error "NotImplemented"
                :message "The WebSocket client is not yet implemented on ClojureScript."})))

(defn close!
  "Cleanly close the socket and stop reconnecting. Idempotent."
  [handle]
  #?(:clj
     (let [{:keys [state]} handle
           [{:keys [closed? ^WebSocket socket conn-ch]} _]
           (swap-vals! state assoc :closed? true)]
       (when-not closed?
         (when conn-ch
           (a/put! conn-ch [:shutdown]))
         (when socket
           (try (.sendClose socket WebSocket/NORMAL_CLOSURE "")
                (catch Exception _))
           (try (.abort socket) (catch Exception _)))
         ;; If the run loop is between connections it can't observe the
         ;; shutdown event; deliver the terminal callback here (exactly-once
         ;; is guaranteed by the :terminated flag).
         (terminate! handle :close {:code 1000 :reason "Closed by client."})))
     :cljs nil)
  nil)

(defn connected?
  "True while an open WebSocket connection is established."
  [handle]
  #?(:clj (boolean (:connected? @(:state handle)))
     :cljs false))
