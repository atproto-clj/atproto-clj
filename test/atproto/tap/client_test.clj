(ns atproto.tap.client-test
  "Tap admin endpoints against the stub HTTP handler; the Tap channel
  against the in-process http-kit WebSocket server (ack protocol scenarios
  ported from @atproto/tap tests/channel.test.ts)."
  (:require [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as httpkit]
            [atproto.runtime.http :as http]
            [atproto.runtime.json :as json]
            [atproto.runtime.ws :as ws]
            [atproto.support.ws-server :as ws-server]
            [atproto.test-support.http :as stub]
            [atproto.tap.auth :as auth]
            [atproto.tap.client :as tap]))

(set! *warn-on-reflection* true)

(def password "hunter2")
(def auth-header (auth/format-admin-auth-header password))
(def did "did:plc:ewvi7nxzyoun6zhxrhs64oiz")
(def cid-str "bafyreie5cvv4h45feadgeuwhbcutmh6t2ceseocckahdoe6uat64zmz454")

(defn- run [v] (deref v 5000 ::timeout))

;; -----------------------------------------------------------------------------
;; Admin HTTP endpoints
;; -----------------------------------------------------------------------------

(deftest create-test
  (testing "trailing slashes are trimmed"
    (is (= "http://tap.example"
           (:url (tap/create {:url "http://tap.example/" :admin-password password})))))
  (testing "invalid url"
    (is (thrown? Exception (tap/create {:url "not-a-url" :admin-password password})))
    (is (thrown? Exception (tap/create {:url "ftp://tap.example" :admin-password password})))))

(deftest add-repos-test
  (let [{:keys [handler requests]} (stub/scripted [(stub/json-response 200 {})])
        client (tap/create {:url "http://tap.example" :admin-password password})]
    (with-redefs [http/handle-request handler]
      (is (= {} (run (tap/add-repos! client ["did:plc:a" "did:plc:b"])))))
    (let [req (first @requests)]
      (is (= :post (:method req)))
      (is (= "http://tap.example/repos/add" (:url req)))
      (is (= auth-header (get-in req [:headers :authorization])))
      (is (= "application/json" (get-in req [:headers :content-type])))
      (is (= {:dids ["did:plc:a" "did:plc:b"]} (json/read-str (:body req)))))))

(deftest remove-repos-test
  (let [{:keys [handler requests]} (stub/scripted [(stub/json-response 200 {})])
        client (tap/create {:url "http://tap.example" :admin-password password})]
    (with-redefs [http/handle-request handler]
      (is (= {} (run (tap/remove-repos! client ["did:plc:a"])))))
    (let [req (first @requests)]
      (is (= :post (:method req)))
      (is (= "http://tap.example/repos/remove" (:url req)))
      (is (= auth-header (get-in req [:headers :authorization])))
      (is (= {:dids ["did:plc:a"]} (json/read-str (:body req)))))))

(deftest resolve-did-test
  (testing "success delivers {:did-doc <parsed json>}"
    (let [did-doc {:id did :alsoKnownAs ["at://alice.test"]}
          {:keys [handler requests]} (stub/scripted [(stub/json-response 200 did-doc)])
          client (tap/create {:url "http://tap.example" :admin-password password})]
      (with-redefs [http/handle-request handler]
        (is (= {:did-doc did-doc} (run (tap/resolve-did client did)))))
      (let [req (first @requests)]
        (is (= :get (:method req)))
        (is (= (str "http://tap.example/resolve/" did) (:url req)))
        (is (= auth-header (get-in req [:headers :authorization]))))))
  (testing "404 -> DidNotFound"
    (let [{:keys [handler]} (stub/scripted [(stub/json-response 404 {:error "NotFound"})])
          client (tap/create {:url "http://tap.example" :admin-password password})]
      (with-redefs [http/handle-request handler]
        (is (= "DidNotFound" (:error (run (tap/resolve-did client did))))))))
  (testing "other HTTP errors -> error-map"
    (let [{:keys [handler]} (stub/scripted [(stub/json-response 500 {:error "Boom"})])
          client (tap/create {:url "http://tap.example" :admin-password password})]
      (with-redefs [http/handle-request handler]
        (is (= "HTTP_500" (:error (run (tap/resolve-did client did)))))))))

(deftest repo-info-test
  (let [info {:did did :rev "3kao2c5nmvz2c" :state "active"}
        {:keys [handler requests]} (stub/scripted [(stub/json-response 200 info)])
        client (tap/create {:url "http://tap.example" :admin-password password})]
    (with-redefs [http/handle-request handler]
      (is (= info (run (tap/repo-info client did)))))
    (let [req (first @requests)]
      (is (= :get (:method req)))
      (is (= (str "http://tap.example/info/" did) (:url req)))
      (is (= auth-header (get-in req [:headers :authorization]))))))

(deftest transport-error-passthrough-test
  (let [{:keys [handler]} (stub/scripted [{:error "HTTPClientError" :message "conn refused"}])
        client (tap/create {:url "http://tap.example" :admin-password password})]
    (with-redefs [http/handle-request handler]
      (is (= "HTTPClientError" (:error (run (tap/add-repos! client ["did:plc:a"]))))))))

;; -----------------------------------------------------------------------------
;; Channel
;; -----------------------------------------------------------------------------

(defn- wire-record-event
  [id rkey]
  {:id id
   :type "record"
   :record {:live true
            :did did
            :rev (str "rev-" id)
            :collection "app.bsky.feed.post"
            :rkey rkey
            :action "create"
            :cid cid-str
            :record {:text (str "post " id)}}})

(defn- send-event! [ch event] (httpkit/send! ch (json/write-str event)))

(deftest channel-delivery-and-acks-test
  (let [received (atom [])
        acks (atom [])
        upgrade-headers (atom nil)
        acks-done (promise)
        server (ws-server/start!
                :ring-handler
                (fn [req]
                  (reset! upgrade-headers (:headers req))
                  (httpkit/as-channel
                   req
                   {:on-open (fn [ch]
                               (send-event! ch (wire-record-event 1 "a"))
                               (send-event! ch (wire-record-event 2 "b")))
                    :on-receive (fn [_ msg]
                                  (let [acks' (swap! acks conj (json/read-str msg))]
                                    (when (= 2 (count acks'))
                                      (deliver acks-done true))))})))
        client (tap/create {:url (str "http://127.0.0.1:" (:port server))
                            :admin-password password})
        handle (tap/channel client {:handler (fn [event ack!]
                                               (swap! received conj event)
                                               (ack!))})]
    (try
      (is (true? (deref acks-done 5000 ::timeout)))
      (testing "handler sees flattened events, in order"
        (is (= [[:record 1 :create "a" true]
                [:record 2 :create "b" true]]
               (mapv (juxt :type :id :action :rkey :live) @received)))
        (is (= {:text "post 1"} (:record (first @received))))
        (is (= cid-str (:cid (first @received)))))
      (testing "acks are {\"type\":\"ack\",\"id\":n} texts, in order"
        (is (= [{:type "ack" :id 1} {:type "ack" :id 2}] @acks)))
      (testing "the ws upgrade carries the admin auth header"
        (is (= auth-header (get @upgrade-headers "authorization"))))
      (finally
        (tap/stop! handle)
        (ws-server/stop! server)))))

(deftest channel-handler-error-test
  (let [errors (atom [])
        acks (atom [])
        ack-done (promise)
        server (ws-server/start!
                :on-open (fn [ch]
                           (httpkit/send! ch "{not json")
                           (send-event! ch (wire-record-event 1 "a"))
                           (send-event! ch (wire-record-event 2 "b")))
                :on-receive (fn [_ msg]
                              (swap! acks conj (json/read-str msg))
                              (deliver ack-done true)))
        client (tap/create {:url (str "http://127.0.0.1:" (:port server))
                            :admin-password password})
        handle (tap/channel client
                            {:handler (fn [event ack!]
                                        (if (= 1 (:id event))
                                          (throw (ex-info "boom" {}))
                                          (ack!)))
                             :on-error (fn [err] (swap! errors conj err))})]
    (try
      (is (true? (deref ack-done 5000 ::timeout)))
      ;; allow any stray (incorrect) acks to arrive before asserting
      (Thread/sleep 200)
      (testing "the throwing handler's event is NOT acked; later events are"
        (is (= [{:type "ack" :id 2}] @acks)))
      (testing "handler throw and parse failure both reach :on-error"
        (is (some #(= "TapHandlerError" (:error %)) @errors))
        (is (some #(= "InvalidTapEvent" (:error %)) @errors)))
      (finally
        (tap/stop! handle)
        (ws-server/stop! server)))))

(deftest channel-buffered-acks-test
  (let [conn-count (atom 0)
        server-ch (atom nil)
        acks (atom [])
        stashed-ack (atom nil)
        first-event (promise)
        second-event (promise)
        all-acks (promise)
        server (ws-server/start!
                :on-open (fn [ch]
                           (reset! server-ch ch)
                           (if (= 1 (swap! conn-count inc))
                             (send-event! ch (wire-record-event 1 "a"))
                             (send-event! ch (wire-record-event 2 "b"))))
                :on-receive (fn [_ msg]
                              (let [acks' (swap! acks conj (json/read-str msg))]
                                (when (= 2 (count acks'))
                                  (deliver all-acks true)))))
        client (tap/create {:url (str "http://127.0.0.1:" (:port server))
                            :admin-password password})
        handle (tap/channel client
                            {:handler (fn [event ack!]
                                        (if (= 1 (:id event))
                                          (do (reset! stashed-ack ack!)
                                              (deliver first-event true))
                                          (do (deliver second-event true)
                                              (ack!))))
                             :ws-opts {:max-reconnect-ms 200}})]
    (try
      (is (true? (deref first-event 5000 ::timeout)))
      ;; Abruptly kill the TCP connection (no closing handshake), then ack
      ;; while the client is down: the ack must be buffered.
      (ws-server/drop-connection! @server-ch)
      (Thread/sleep 100)
      (@stashed-ack)
      ;; The client reconnects (url-fn re-resolves; the server keeps
      ;; accepting) and the second connection delivers event 2.
      (is (true? (deref second-event 10000 ::timeout)))
      (is (true? (deref all-acks 5000 ::timeout)))
      (testing "the buffered ack is flushed in order, before newer acks"
        (is (= [{:type "ack" :id 1} {:type "ack" :id 2}] @acks)))
      (is (= 2 @conn-count))
      (finally
        (tap/stop! handle)
        (ws-server/stop! server)))))

(deftest channel-stop-test
  (let [closed (promise)
        server (ws-server/start!)
        client (tap/create {:url (str "http://127.0.0.1:" (:port server))
                            :admin-password password})
        handle (tap/channel client
                            {:handler (fn [_ _])
                             :ws-opts {:on-close (fn [c] (deliver closed c))}})]
    (try
      (loop [n 0]
        (when (and (< n 250) (not (ws/connected? (:ws handle))))
          (Thread/sleep 20)
          (recur (inc n))))
      (is (true? (ws/connected? (:ws handle))))
      (tap/stop! handle)
      (testing "stop! closes the websocket cleanly"
        (is (= 1000 (:code (deref closed 5000 ::timeout))))
        (is (false? (ws/connected? (:ws handle)))))
      (finally
        (ws-server/stop! server)))))
