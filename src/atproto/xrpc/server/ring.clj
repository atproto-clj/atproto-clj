(ns atproto.xrpc.server.ring
  "Ring adapter for the XRPC server, including the http-kit websocket
  transport for lexicon `subscription` endpoints.

  Subscription semantics (reference packages/xrpc-server/src/stream/server.ts):
  outgoing messages are sent as binary frames; an error (subscription
  rejection or a {:frame/error ...} item) is sent as an error frame and the
  socket is closed with ws code 1008 (Policy); normal completion closes
  with 1000. Two http-kit transport limitations (the error name still
  reaches clients in the error frame body, which is the atproto spec's
  mechanism):
  - the close frame carries no reason string: AsyncChannel.serverClose
    encodes only the 2-byte status code, unlike the reference which sets
    the error name as the close reason;
  - backpressure is best-effort (no per-send completion callback): use a
    bounded :messages channel for producer-side backpressure."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :refer [keywordize-keys stringify-keys]]
            [clojure.core.async :as async]
            [org.httpkit.server :as httpkit]
            [atproto.runtime.http :as http]
            [atproto.runtime.json :as json]
            [atproto.runtime.cast :as cast]
            [atproto.lexicon :as lexicon]
            [atproto.xrpc.frames :as frames]
            [atproto.xrpc.server :as xrpc-server])
  (:import [java.io ByteArrayOutputStream]
           [org.httpkit.server AsyncChannel]))

(set! *warn-on-reflection* true)

(defn input-stream->bytes ^bytes [is]
  (when is
    (let [os (ByteArrayOutputStream.)]
      (io/copy is os)
      (let [ba (.toByteArray os)]
        (when (not (zero? (count ba)))
          ba)))))

(defn ring-request->http-request
  [{:keys [request-method scheme server-name server-port uri query-string headers body app-ctx]}]
  (let [query-params (when (not-empty query-string)
                       (http/query-string->query-params query-string))]
    (cond-> {:method request-method
             :url (str (name scheme) "://"
                       server-name
                       (when server-port (str ":" server-port))
                       uri)
             :headers (keywordize-keys headers)
             :body (input-stream->bytes body)}
      app-ctx (assoc :app-ctx app-ctx)
      query-params (assoc :query-params query-params))))

(defn http-response->ring-response
  [http-response]
  (update http-response :headers stringify-keys))

;; -----------------------------------------------------------------------------
;; Websocket subscription transport
;; -----------------------------------------------------------------------------

(def ^:private close-normal 1000)
(def ^:private close-policy 1008)

(defn- ws-close!
  "Close the websocket with the given close code (http-kit cannot attach a
  close reason; see the namespace docstring)."
  [ch code]
  (.serverClose ^AsyncChannel ch (int code)))

(defn- send-error-frame!
  "Send an error frame, then close with 1008/Policy."
  [ch {:keys [error message]}]
  (httpkit/send! ch (frames/encode (frames/error-frame error message)) false)
  (ws-close! ch close-policy))

(defn- run-send-loop
  "Drive the frame send loop for an accepted subscription."
  [ch nsid messages]
  (async/go-loop []
    (if-some [msg (async/<! messages)]
      (if (:frame/error msg)
        (send-error-frame! ch {:error (:frame/error msg)
                               :message (:frame/message msg)})
        (if (httpkit/send! ch (frames/encode (frames/message-frame msg :nsid nsid)) false)
          (recur)
          ;; client went away; the :close-ch signal lets the producer stop
          nil))
      (ws-close! ch close-normal))))

(defn- xrpc-error-ring-response
  "A plain HTTP (non-upgraded) JSON response for an XRPC error map."
  [{:keys [status error message]}]
  {:status (or status 500)
   :headers {"content-type" "application/json"}
   :body (json/write-str (cond-> {:error error}
                           (some? message) (assoc :message message)))})

(defn- subscription-ring-response
  "Handle a request for a lexicon subscription NSID.

  Validates the request and runs the route's auth verifier
  (xrpc-server/subscription-request), then invokes handle-subscription and
  drives the frame send loop over the websocket. Errors after the upgrade
  are reported as an error frame followed by close 1008 (reference
  behavior); non-websocket requests get the mapped XRPC error JSON."
  [server {:keys [app-ctx websocket?] :as ring-request}]
  (let [http-request (ring-request->http-request ring-request)
        result @(xrpc-server/subscription-request server http-request)
        close-ch (async/chan)
        sub (when (not (:error result))
              (xrpc-server/handle-subscription app-ctx (assoc result :close-ch close-ch)))
        error (or (when (:error result) result)
                  (when (:error sub) sub))]
    (if (not websocket?)
      (xrpc-error-ring-response (or error (xrpc-server/invalid-request
                                           "Expected a WebSocket upgrade request.")))
      (httpkit/as-channel
       ring-request
       {:on-open (fn [ch]
                   (cast/event {:message "XRPC subscription opened"
                                :nsid (:nsid result)
                                :error (:error error)})
                   (if error
                     (send-error-frame! ch error)
                     (run-send-loop ch (:nsid result) (:messages sub))))
        :on-close (fn [_ch status]
                    (cast/event {:message "XRPC subscription closed"
                                 :nsid (:nsid result)
                                 :status status})
                    (async/close! close-ch))}))))

(defn- subscription-nsid
  "The NSID of the lexicon subscription this request targets, if any."
  [{:keys [lexicon]} uri]
  (let [nsid (subs uri (count "/xrpc/"))]
    (when (= "subscription" (:type (lexicon/type-def lexicon nsid)))
      nsid)))

(defn handler
  [server]
  (fn [ring-request]
    (when (str/starts-with? (:uri ring-request) "/xrpc/")
      (if (subscription-nsid server (:uri ring-request))
        (subscription-ring-response server ring-request)
        (http-response->ring-response
         @(xrpc-server/handle-http-request server
                                           (ring-request->http-request ring-request)))))))
