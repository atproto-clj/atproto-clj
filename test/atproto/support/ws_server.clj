(ns atproto.support.ws-server
  "WebSocket server helpers for streaming tests.

  Two servers are provided: an http-kit server for normal message/close
  scenarios (http-kit replies to pings automatically), and a minimal raw
  ServerSocket server that completes the WebSocket handshake but never
  answers pings, to exercise dead-peer heartbeat detection."
  (:require [clojure.string :as str]
            [org.httpkit.server :as httpkit])
  (:import [java.io BufferedReader InputStreamReader OutputStream]
           [java.net ServerSocket Socket]
           [java.nio.channels SelectionKey]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.util Base64]
           [org.httpkit.server AsyncChannel]))

(set! *warn-on-reflection* true)

(defn start!
  "Start an http-kit WebSocket server on an ephemeral (or given) port.

  Options:
    :port        default 0 (ephemeral)
    :on-open     (fn [ch]) http-kit channel; (httpkit/send! ch bytes) sends a
                 binary frame, (httpkit/send! ch string) a text frame.
    :on-receive  (fn [ch msg])
    :on-close    (fn [ch status])
    :ring-handler overrides the default as-channel handler entirely.

  Returns {:server s :port n :url \"ws://127.0.0.1:<port>/\"}."
  [& {:keys [port on-open on-receive on-close ring-handler] :or {port 0}}]
  (let [handler (or ring-handler
                    (fn [req]
                      (httpkit/as-channel
                       req
                       {:on-open (or on-open (fn [_]))
                        :on-receive (or on-receive (fn [_ _]))
                        :on-close (or on-close (fn [_ _]))})))
        server (httpkit/run-server handler {:port port
                                            :legacy-return-value? false})
        port (httpkit/server-port server)]
    {:server server :port port :url (str "ws://127.0.0.1:" port "/")}))

(defn stop!
  "Stop the server gracefully."
  [{:keys [server]}]
  (when server
    @(httpkit/server-stop! server)))

(defn kill!
  "Stop the server immediately, dropping open connections without a closing
  handshake (clients observe an abnormal close)."
  [{:keys [server]}]
  (when server
    @(httpkit/server-stop! server {:timeout 0})))

(let [key-field (doto (.getDeclaredField AsyncChannel "key")
                  (.setAccessible true))]
  (defn drop-connection!
    "Abruptly close the TCP socket under an http-kit websocket channel — no
    closing handshake, so the client observes an abnormal close."
    [ch]
    (let [^SelectionKey k (.get key-field ch)]
      (.close (.channel k)))))

;; Raw handshake-only server (never answers pings) -------------------------

(def ^:private ws-guid "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")

(defn- accept-key
  ^String [^String sec-websocket-key]
  (let [digest (.digest (MessageDigest/getInstance "SHA-1")
                        (.getBytes (str sec-websocket-key ws-guid)
                                   StandardCharsets/UTF_8))]
    (.encodeToString (Base64/getEncoder) digest)))

(defn- handshake!
  "Read the HTTP upgrade request from the socket and reply 101 without ever
  reading/writing WebSocket frames afterwards."
  [^Socket sock]
  (let [in (BufferedReader. (InputStreamReader. (.getInputStream sock)
                                                StandardCharsets/UTF_8))
        headers (loop [headers {}]
                  (let [line (.readLine in)]
                    (if (or (nil? line) (str/blank? line))
                      headers
                      (recur (if-let [[_ k v] (re-matches #"([^:]+):\s*(.*)" line)]
                               (assoc headers (str/lower-case k) v)
                               headers)))))
        key (get headers "sec-websocket-key")
        ^OutputStream out (.getOutputStream sock)]
    (.write out (.getBytes (str "HTTP/1.1 101 Switching Protocols\r\n"
                                "Upgrade: websocket\r\n"
                                "Connection: Upgrade\r\n"
                                "Sec-WebSocket-Accept: " (accept-key key) "\r\n"
                                "\r\n")
                           StandardCharsets/UTF_8))
    (.flush out)))

(defn start-silent!
  "Start a server that accepts WebSocket handshakes and then goes silent —
  it never sends frames and never answers pings. Returns
  {:server-socket ss :port n :url ...}; stop with stop-silent!."
  []
  (let [ss (ServerSocket. 0)
        port (.getLocalPort ss)
        runner (future
                 (try
                   (loop []
                     (let [sock (.accept ss)]
                       (future
                         (try (handshake! sock)
                              (catch Exception _))))
                     (recur))
                   (catch Exception _)))]
    {:server-socket ss
     :runner runner
     :port port
     :url (str "ws://127.0.0.1:" port "/")}))

(defn stop-silent!
  [{:keys [^ServerSocket server-socket]}]
  (when server-socket
    (.close server-socket)))
