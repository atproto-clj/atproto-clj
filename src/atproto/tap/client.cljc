(ns atproto.tap.client
  "Client for a Tap instance (bluesky-social/indigo cmd/tap).

  Port of the reference @atproto/tap Tap + TapChannel (commit b9ef557):
  admin HTTP endpoints under the Tap base URL (Basic auth, username
  \"admin\"), and a /channel WebSocket delivering JSON text events with an
  explicit ack protocol. Delivery is at-least-once: an event is redelivered
  until its {\"type\":\"ack\",\"id\":n} is received, so handlers must ack
  only after durable processing and a throwing handler is never acked.

  The channel is JVM-only for now (it rides on atproto.runtime.ws)."
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.http :as http]
            [atproto.runtime.json :as json]
            [atproto.runtime.cast :as cast]
            [atproto.runtime.ws :as ws]
            [atproto.tap.auth :as auth]
            [atproto.tap.events :as events]
            #?(:clj [clojure.core.async :as a])))

#?(:clj (set! *warn-on-reflection* true))

;; -----------------------------------------------------------------------------
;; Client
;; -----------------------------------------------------------------------------

(s/def ::url (s/and string? #(re-matches #"https?://.+" %)))
(s/def ::admin-password string?)
(s/def ::config (s/keys :req-un [::url ::admin-password]))

(defn create
  "Create a Tap client.

  Config: {:url \"http://localhost:2480\" :admin-password \"secret\"}.
  Throws on an invalid config."
  [{:keys [url admin-password] :as config}]
  (when-not (s/valid? ::config config)
    (throw (ex-info "Invalid Tap client config."
                    {:error "InvalidTapConfig"
                     :message (s/explain-str ::config config)})))
  {:url (str/replace url #"/+$" "")
   :admin-password admin-password})

;; -----------------------------------------------------------------------------
;; Admin HTTP endpoints
;; -----------------------------------------------------------------------------

(defn- admin-execute
  "Execute an admin HTTP request with the Basic admin auth header. Delivers
  (response->result response) for HTTP responses; transport errors
  ({:error \"HTTPClientError\"/\"Timeout\"}) pass through as-is."
  [{:keys [admin-password]} request response->result opts]
  (let [[cb val] (i/platform-async (select-keys opts [:channel :callback :promise]))]
    (i/execute {::i/request (update request :headers
                                    #(merge {:authorization (auth/format-admin-auth-header
                                                             admin-password)}
                                            %))
                ::i/queue [json/client-interceptor
                           http/client-interceptor]}
               :callback
               (fn [{:keys [error] :as resp}]
                 (cb (if error resp (response->result resp)))))
    val))

(defn- body-or-empty
  [{:keys [body]}]
  (if (or (nil? body) (and (string? body) (str/blank? body)))
    {}
    body))

(defn- success-body
  "Parsed body of a 2xx response ({} when empty); error map otherwise."
  [{:keys [status] :as resp}]
  (if (http/success? status)
    (body-or-empty resp)
    (http/error-map resp)))

(defn add-repos!
  "Ask Tap to track the given DIDs (POST {url}/repos/add)."
  [client dids & {:as opts}]
  (admin-execute client
                 {:method :post
                  :url (str (:url client) "/repos/add")
                  :headers {:content-type "application/json"}
                  :body {:dids (vec dids)}}
                 success-body
                 opts))

(defn remove-repos!
  "Ask Tap to stop tracking the given DIDs (POST {url}/repos/remove)."
  [client dids & {:as opts}]
  (admin-execute client
                 {:method :post
                  :url (str (:url client) "/repos/remove")
                  :headers {:content-type "application/json"}
                  :body {:dids (vec dids)}}
                 success-body
                 opts))

(defn resolve-did
  "Resolve a DID document through Tap (GET {url}/resolve/{did}).
  Delivers {:did-doc <parsed json>} or {:error \"DidNotFound\"} on 404."
  [client did & {:as opts}]
  (admin-execute client
                 {:method :get
                  :url (str (:url client) "/resolve/" did)}
                 (fn [{:keys [status] :as resp}]
                   (cond
                     (http/success? status) {:did-doc (body-or-empty resp)}
                     (= 404 status)         {:error "DidNotFound" :did did}
                     :else                  (http/error-map resp)))
                 opts))

(defn repo-info
  "Tap's info for a tracked repo (GET {url}/info/{did})."
  [client did & {:as opts}]
  (admin-execute client
                 {:method :get
                  :url (str (:url client) "/info/" did)}
                 success-body
                 opts))

;; -----------------------------------------------------------------------------
;; Channel (WebSocket + ack protocol)
;; -----------------------------------------------------------------------------

;; Acks are JSON text {"type":"ack","id":n}, sent strictly in order — one
;; in-flight send at a time, the next only after the previous send's
;; callback — buffered while disconnected and flushed in order after
;; reconnect (port of tap/src/channel.ts:62-107). The queue lives in an
;; atom; a single-flight "pump" (guarded by :sending?) drains it, so ack!
;; is safe to call from any thread (including the ws listener thread) and
;; never blocks.

#?(:clj (declare ^:private flush-acks!))

#?(:clj
   (defn- send-next-ack!
     "Send the ack at the head of the queue; pop and continue on success.
     Only the pump owner (the thread that flipped :sending?) runs this."
     [state]
     (let [{:keys [acks ws]} @state
           id (peek acks)]
       (if (nil? id)
         (do (swap! state assoc :sending? false)
             ;; Re-check: an ack may have been enqueued since the peek.
             (flush-acks! state))
         (ws/send! ws (json/write-str {:type "ack" :id id})
                   (fn [{:keys [error]}]
                     (if error
                       (do
                         ;; Keep the failed ack at the head of the queue; it
                         ;; is resent when the flush is kicked again (by
                         ;; :on-reconnect, the next ack!, or the retry timer
                         ;; below, which also covers a message handled before
                         ;; the connection is observed as open).
                         (swap! state assoc :sending? false)
                         (when (not= "NotConnected" error)
                           (cast/event {:message "Tap ack send failed; buffered for retry."
                                        :error error}))
                         (when-not (:closed? @state)
                           (a/go (a/<! (a/timeout 100))
                                 (flush-acks! state))))
                       (do (swap! state update :acks pop)
                           (send-next-ack! state)))))))))

#?(:clj
   (defn- flush-acks!
     "Kick the single-flight ack pump if it is idle and work is queued."
     [state]
     (let [[old new] (swap-vals! state
                                 (fn [{:keys [sending? closed? ws acks] :as st}]
                                   (if (or sending? closed? (nil? ws) (empty? acks))
                                     st
                                     (assoc st :sending? true))))]
       (when (and (not (:sending? old)) (:sending? new))
         (send-next-ack! state)))))

#?(:clj
   (defn- enqueue-ack!
     [state id]
     (swap! state update :acks conj id)
     (flush-acks! state)
     nil))

#?(:clj
   (defn- handle-message
     "Parse one TEXT message and dispatch it to the channel handler.
     Parse failures and handler throws go to on-error and are NOT acked
     (Tap redelivers: at-least-once)."
     [state {:keys [handler on-error]} msg]
     (if-not (string? msg)
       (on-error {:error "InvalidTapEvent"
                  :message "Expected a text message on the Tap channel."})
       (let [parsed (try {:data (json/read-str msg)}
                         (catch Exception e {:exception e}))]
         (if-let [e (:exception parsed)]
           (on-error {:error "InvalidTapEvent"
                      :message (str "Tap channel message is not valid JSON: "
                                    (ex-message e))})
           (let [event (events/parse-tap-event (:data parsed))]
             (if (:error event)
               (on-error event)
               (let [ack! (fn ack! [] (enqueue-ack! state (:id event)))]
                 (try
                   (handler event ack!)
                   (catch Throwable t
                     (cast/alert {:message "Tap channel handler threw; event not acked."
                                  :ex t})
                     (on-error {:error "TapHandlerError"
                                :message (str "Tap event handler threw: " (ex-message t))
                                :exception t
                                :event event})))))))))))

(defn channel
  "Open the Tap /channel WebSocket (ws[s] derived from the client :url)
  with the admin auth header.

  Config:
    :handler  (fn [event ack!]) — event from atproto.tap.events; call (ack!)
              after durable processing. ack! is async-safe and non-blocking;
              acks are sent in order, buffered while disconnected and flushed
              in order on reconnect. If the handler throws, NO ack is sent.
    :on-error (fn [{:keys [error message exception]}]) parse/handler errors
              (and forwarded WebSocket errors). Defaults to cast/alert.
    :ws-opts  extra options for atproto.runtime.ws/connect (e.g.
              :max-reconnect-ms, :heartbeat-interval-ms, :on-close).

  Returns a handle for stop!."
  [client {:keys [handler on-error ws-opts] :as config}]
  #?(:clj
     (do
       (when-not (fn? handler)
         (throw (ex-info "A :handler fn is required."
                         {:error "InvalidTapConfig" :config config})))
       (let [on-error (or on-error (fn [err] (cast/alert err)))
             config (assoc config :on-error on-error)
             ws-url (str (str/replace-first (:url client) #"^http" "ws")
                         "/channel")
             state (atom {:acks clojure.lang.PersistentQueue/EMPTY
                          :sending? false
                          :ws nil
                          :closed? false})
             ws-handle (ws/connect
                        (merge ws-opts
                               {:url-fn (fn [cb] (cb ws-url))
                                :headers (merge (:headers ws-opts)
                                                {:authorization
                                                 (auth/format-admin-auth-header
                                                  (:admin-password client))})
                                :on-message (fn [msg]
                                              (handle-message state config msg))
                                :on-error (fn [err]
                                            (when-let [f (:on-error ws-opts)] (f err))
                                            (on-error err))
                                :on-reconnect (fn []
                                                (when-let [f (:on-reconnect ws-opts)] (f))
                                                (flush-acks! state))}))]
         (swap! state assoc :ws ws-handle)
         ;; Acks enqueued before the ws handle was recorded need a kick.
         (flush-acks! state)
         {:ws ws-handle :state state}))
     :cljs {:error "NotImplemented"
            :message "The Tap channel is not yet implemented on ClojureScript."}))

(defn stop!
  "Close the channel WebSocket and stop the ack pump. Idempotent."
  [handle]
  #?(:clj (do (swap! (:state handle) assoc :closed? true)
              (ws/close! (:ws handle))
              nil)
     :cljs nil))
