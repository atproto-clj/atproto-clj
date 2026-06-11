(ns atproto.xrpc.subscription-test
  "JVM integration tests for the websocket subscription transport:
  http-kit server + ring adapter on one side, java.net.http.WebSocket as a
  vanilla binary websocket client on the other (pattern from
  atproto.jetstream)."
  (:require [clojure.test :refer :all]
            [clojure.core.async :as async]
            [org.httpkit.server :as httpkit]
            [atproto.lexicon :as lexicon]
            [atproto.crypto :as crypto]
            [atproto.service-auth :as service-auth]
            [atproto.xrpc.client :as xrpc-client]
            [atproto.xrpc.frames :as frames]
            [atproto.xrpc.server :as xrpc-server]
            [atproto.xrpc.server.ring :as ring])
  (:import [java.io ByteArrayOutputStream]
           [java.net URI]
           [java.net.http HttpClient WebSocket WebSocket$Listener]
           [java.nio ByteBuffer]
           [java.time Duration]
           [java.util.concurrent CompletableFuture]))

(set! *warn-on-reflection* true)

;; Mirrors the countdown subscription of the reference
;; packages/xrpc-server/tests/subscriptions.test.ts LEXICONS.
(def test-lexicon
  (lexicon/lexicon
   [{:lexicon 1
     :id "io.example.stream"
     :defs {:main {:type "subscription"
                   :parameters {:type "params"
                                :required ["countdown"]
                                :properties {:countdown {:type "integer"
                                                         :minimum 0}}}
                   :message {:schema {:type "union"
                                      :refs ["#message"]}}}
            :message {:type "object"
                      :required ["num"]
                      :properties {:num {:type "integer"}}}}}
    {:lexicon 1
     :id "io.example.streamBoom"
     :defs {:main {:type "subscription"}}}
    {:lexicon 1
     :id "io.example.streamAuth"
     :defs {:main {:type "subscription"}}}
    {:lexicon 1
     :id "io.example.streamForever"
     :defs {:main {:type "subscription"}}}
    {:lexicon 1
     :id "io.example.query"
     :defs {:main {:type "query"
                   :output {:encoding "application/json"
                            :schema {:type "object"
                                     :properties {:ok {:type "boolean"}}}}}}}
    {:lexicon 1
     :id "io.example.authQuery"
     :defs {:main {:type "query"
                   :output {:encoding "application/json"
                            :schema {:type "object"
                                     :properties {:did {:type "string"
                                                        :format "did"}}}}}}}]))

(defmethod xrpc-server/handle-subscription "io.example.stream"
  [_app-ctx {:keys [params]}]
  (let [messages (async/chan 8)]
    (async/go
      (loop [n (:countdown params)]
        (if (neg? n)
          (async/close! messages)
          (do (async/>! messages {:$type "io.example.stream#message" :num n})
              (recur (dec n))))))
    {:messages messages}))

(defmethod xrpc-server/handle-subscription "io.example.streamBoom"
  [_app-ctx _request]
  (let [messages (async/chan 8)]
    (async/go
      (async/>! messages {:$type "io.example.streamBoom#message" :num 1})
      (async/>! messages {:frame/error "BoomError" :frame/message "It blew up"}))
    {:messages messages}))

(defmethod xrpc-server/handle-subscription "io.example.streamAuth"
  [_app-ctx {:keys [auth]}]
  (let [messages (async/chan 1)]
    (async/go
      (async/>! messages {:did (get-in auth [:credentials :did])})
      (async/close! messages))
    {:messages messages}))

(def forever-closed (atom nil))

(defmethod xrpc-server/handle-subscription "io.example.streamForever"
  [_app-ctx {:keys [close-ch]}]
  (let [messages (async/chan)
        closed (promise)]
    (reset! forever-closed closed)
    (async/go-loop [n 0]
      (let [[_ port] (async/alts! [close-ch (async/timeout 20)])]
        (if (= port close-ch)
          (do (async/close! messages)
              (deliver closed true))
          (do (async/>! messages {:num n})
              (recur (inc n))))))
    {:messages messages}))

(defmethod xrpc-server/handle "io.example.query"
  [_app-ctx _request]
  {:encoding "application/json"
   :body {:ok true}})

(def service-keypair @(crypto/generate "ES256K"))
(def server-did "did:web:server.example.com")

(defmethod xrpc-server/handle "io.example.authQuery"
  [_app-ctx {:keys [auth]}]
  {:encoding "application/json"
   :body {:did (get-in auth [:credentials :did])}})

(def server
  (xrpc-server/init
   {:lexicon test-lexicon
    :validate-response? true
    :auth {"io.example.streamAuth"
           (fn [{:keys [request]} cb]
             (if (= "Bearer sesame" (get-in request [:headers :authorization]))
               (cb {:credentials {:did "did:example:alice"}})
               (cb {:error "AuthenticationRequired" :message "bad token" :status 401})))
           "io.example.authQuery"
           (xrpc-server/service-auth-verifier
            {:own-did server-did
             :get-signing-key (fn [_iss _force-refresh? cb]
                                (cb {:key (crypto/did service-keypair)}))})}}))

(def ^:dynamic *port* nil)

(defn- with-http-server
  [f]
  (let [s (httpkit/run-server (ring/handler server)
                              {:port 0 :legacy-return-value? false})]
    (try
      (binding [*port* (httpkit/server-port s)]
        (f))
      (finally
        (httpkit/server-stop! s)))))

(use-fixtures :once with-http-server)

(defn- ws-url
  [nsid query]
  (str "ws://127.0.0.1:" *port* "/xrpc/" nsid (when query (str "?" query))))

(defn- ws-collect
  "Connect to the url and collect binary frames until the server closes.

  Returns {:frames [decoded-frame ...] :close {:code n :reason s}} or
  {:error throwable} if the upgrade fails. With :abort-after n, aborts the
  socket client-side after n frames."
  [url & {:keys [headers abort-after]}]
  (let [frames (atom [])
        result (promise)
        buf (ByteArrayOutputStream.)
        maybe-abort (fn [^WebSocket ws]
                      (when (and abort-after (<= abort-after (count @frames)))
                        (.abort ws)
                        (deliver result {:frames @frames :close :aborted})))
        listener (reify WebSocket$Listener
                   (onOpen [_ ws]
                     (.request ws 1))
                   (onBinary [_ ws data last?]
                     (let [^ByteBuffer data data
                           bytes (byte-array (.remaining data))]
                       (.get data bytes)
                       (.write buf bytes 0 (alength bytes)))
                     (when last?
                       (swap! frames conj (frames/decode (.toByteArray buf)))
                       (.reset buf))
                     (maybe-abort ws)
                     (.request ws 1)
                     nil)
                   (onClose [_ _ws code reason]
                     (deliver result {:frames @frames
                                      :close {:code code :reason reason}})
                     nil)
                   (onError [_ _ws err]
                     (deliver result {:frames @frames :error err})
                     nil))
        builder (-> (HttpClient/newHttpClient)
                    (.newWebSocketBuilder)
                    (.connectTimeout (Duration/ofSeconds 5)))
        builder (reduce (fn [^java.net.http.WebSocket$Builder b [k v]]
                          (.header b (name k) v))
                        builder
                        headers)]
    (try
      (.join ^CompletableFuture
             (.buildAsync ^java.net.http.WebSocket$Builder builder (URI/create url) listener))
      (catch Exception e
        (deliver result {:error e})))
    (deref result 5000 {:error :timeout})))

(deftest countdown-stream-test
  (testing "N message frames with lifted $type headers, then close 1000"
    (let [{:keys [frames close error]} (ws-collect (ws-url "io.example.stream" "countdown=3"))]
      (is (nil? error))
      (is (= [{:op 1 :t "#message" :body {:num 3}}
              {:op 1 :t "#message" :body {:num 2}}
              {:op 1 :t "#message" :body {:num 1}}
              {:op 1 :t "#message" :body {:num 0}}]
             frames))
      (is (= 1000 (:code close))))))

(deftest bad-params-test
  (testing "invalid params: no messages, error frame InvalidRequest, close 1008"
    (let [{:keys [frames close error]} (ws-collect (ws-url "io.example.stream" "countdown=banana"))]
      (is (nil? error))
      (is (= 1 (count frames)))
      (let [frame (first frames)]
        (is (= -1 (:op frame)))
        (is (= "InvalidRequest" (get-in frame [:body :error]))))
      ;; the error name travels in the frame body; http-kit cannot attach
      ;; a close reason (see atproto.xrpc.server.ring docstring)
      (is (= 1008 (:code close)))))
  (testing "missing required param"
    (let [{:keys [frames close]} (ws-collect (ws-url "io.example.stream" nil))]
      (is (= "InvalidRequest" (get-in (first frames) [:body :error])))
      (is (= 1008 (:code close))))))

(deftest unknown-subscription-test
  (testing "a subscription NSID without a handler maps to MethodNotImplemented"
    (is (= {:status 501 :error "MethodNotImplemented" :message "Method Not Implemented"}
           (xrpc-server/handle-subscription nil {:nsid "io.example.streamNobody"})))))

(deftest auth-test
  (testing "auth verifier rejection: error frame + close 1008"
    (let [{:keys [frames close error]} (ws-collect (ws-url "io.example.streamAuth" nil))]
      (is (nil? error))
      (is (= -1 (:op (first frames))))
      (is (= "AuthenticationRequired" (get-in (first frames) [:body :error])))
      (is (= 1008 (:code close)))))
  (testing "authenticated subscription sees :auth credentials"
    (let [{:keys [frames close error]} (ws-collect (ws-url "io.example.streamAuth" nil)
                                                   :headers {:authorization "Bearer sesame"})]
      (is (nil? error))
      (is (= [{:op 1 :body {:did "did:example:alice"}}] frames))
      (is (= 1000 (:code close))))))

(deftest mid-stream-error-test
  (testing "{:frame/error ...} mid-stream: error frame then close 1008 with the error name"
    (let [{:keys [frames close error]} (ws-collect (ws-url "io.example.streamBoom" nil))]
      (is (nil? error))
      (is (= [{:op 1 :t "#message" :body {:num 1}}
              {:op -1 :body {:error "BoomError" :message "It blew up"}}]
             frames))
      (is (= 1008 (:code close))))))

(deftest client-disconnect-test
  (testing "producer sees :close-ch close on client disconnect"
    (reset! forever-closed nil)
    (let [{:keys [close]} (ws-collect (ws-url "io.example.streamForever" nil)
                                      :abort-after 2)]
      (is (= :aborted close))
      (let [closed @forever-closed]
        (is (some? closed))
        (is (true? (deref closed 5000 :timeout)))))))

(deftest plain-http-on-subscription-test
  (testing "a non-websocket request to a subscription endpoint gets an XRPC error"
    (let [resp @(org.httpkit.client/get (str "http://127.0.0.1:" *port*
                                             "/xrpc/io.example.stream?countdown=3"))]
      (is (= 400 (:status resp)))))
  (testing "queries on the same server still work over plain HTTP"
    (let [resp @(org.httpkit.client/get (str "http://127.0.0.1:" *port*
                                             "/xrpc/io.example.query"))]
      (is (= 200 (:status resp))))))

(deftest service-auth-round-trip-test
  (testing "a service-auth session round-trips against the SDK's own server"
    (let [session (service-auth/session
                   {:iss "did:example:alice"
                    :aud server-did
                    :keypair service-keypair
                    :service (str "http://127.0.0.1:" *port*)})
          client (xrpc-client/init {:session session})
          resp (deref (xrpc-client/query client {:nsid "io.example.authQuery"})
                      5000 ::timeout)]
      (is (= {:did "did:example:alice"} resp))))
  (testing "an unauthenticated client gets 401 MissingJwt"
    (let [client (xrpc-client/init {:service (str "http://127.0.0.1:" *port*)})
          resp (deref (xrpc-client/query client {:nsid "io.example.authQuery"})
                      5000 ::timeout)]
      (is (= "MissingJwt" (:error resp)))))
  (testing "a session for the wrong audience gets 401 BadJwtAudience"
    (let [session (service-auth/session
                   {:iss "did:example:alice"
                    :aud "did:web:other.example.com"
                    :keypair service-keypair
                    :service (str "http://127.0.0.1:" *port*)})
          client (xrpc-client/init {:session session})
          resp (deref (xrpc-client/query client {:nsid "io.example.authQuery"})
                      5000 ::timeout)]
      (is (= "BadJwtAudience" (:error resp))))))
