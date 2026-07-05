(ns atproto.runtime.ws-test
  "Tests for the reconnecting WebSocket consumer.

  All tests run against in-process servers (see atproto.support.ws-server);
  results are collected through promises with deref timeouts, matching the
  repo's async test conventions."
  (:require [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as httpkit]
            [atproto.runtime.ws :as ws]
            [atproto.support.ws-server :as ws-server]))

(set! *warn-on-reflection* true)

(deftest backoff-ms-test
  (testing "bounded by max-ms and never negative"
    (doseq [attempt (range 12)]
      (let [ms (ws/backoff-ms attempt 5000)]
        (is (<= 0 ms 5000)))))
  (testing "grows exponentially before the cap"
    ;; attempt 3 => 8s +- 0.5s, un-capped
    (let [ms (ws/backoff-ms 3 64000)]
      (is (<= 7500 ms 8500)))))

(deftest receive-and-close-test
  (let [received (atom [])
        got-both (promise)
        closed (promise)
        server (ws-server/start!
                :on-open (fn [ch]
                           (httpkit/send! ch (byte-array [1 2 3]))
                           (httpkit/send! ch "hello")))
        handle (ws/connect {:url-fn (fn [cb] (cb (:url server)))
                            :on-message (fn [msg]
                                          (when (= 2 (count (swap! received conj msg)))
                                            (deliver got-both true)))
                            :on-close (fn [info] (deliver closed info))})]
    (try
      (is (true? (deref got-both 5000 ::timeout)))
      (let [[binary text] @received]
        (is (bytes? binary))
        (is (= [1 2 3] (vec binary)))
        (is (= "hello" text)))
      (ws/close! handle)
      (let [info (deref closed 5000 ::timeout)]
        (is (= 1000 (:code info))))
      (is (not (ws/connected? handle)))
      ;; close! is idempotent
      (ws/close! handle)
      (finally
        (ws/close! handle)
        (ws-server/stop! server)))))

(deftest clean-server-close-test
  (let [calls (atom [])
        closed (promise)
        server (ws-server/start!
                :on-open (fn [ch]
                           (httpkit/send! ch "bye")
                           (httpkit/close ch)))
        handle (ws/connect {:url-fn (fn [cb]
                                      (swap! calls conj :called)
                                      (cb (:url server)))
                            :on-message (fn [_])
                            :on-close (fn [info] (deliver closed info))
                            :max-reconnect-ms 100})]
    (try
      (let [info (deref closed 5000 ::timeout)]
        (is (map? info))
        (is (contains? info :code)))
      ;; A clean close is terminal: no reconnect happens afterwards.
      (Thread/sleep 300)
      (is (= 1 (count @calls)))
      (finally
        (ws/close! handle)
        (ws-server/stop! server)))))

(deftest reconnect-after-abnormal-close-test
  (let [conns (atom 0)
        queries (atom [])
        first-ch (promise)
        server (ws-server/start!
                :ring-handler
                (fn [req]
                  (let [n (swap! conns inc)]
                    (swap! queries conj (:query-string req))
                    (httpkit/as-channel
                     req
                     {:on-open (fn [ch]
                                 (if (= 1 n)
                                   (do (deliver first-ch ch)
                                       (httpkit/send! ch "one"))
                                   (httpkit/send! ch "two")))}))))
        url-calls (atom 0)
        messages (atom [])
        first-msg (promise)
        second-msg (promise)
        reconnected (promise)
        errors (atom [])
        handle (ws/connect {:url-fn (fn [cb]
                                      (let [n (swap! url-calls inc)]
                                        (cb (str (:url server) "?cursor=" n))))
                            :on-message (fn [msg]
                                          (swap! messages conj msg)
                                          (case (count @messages)
                                            1 (deliver first-msg msg)
                                            2 (deliver second-msg msg)
                                            nil))
                            :on-error (fn [err] (swap! errors conj err))
                            :on-reconnect (fn [] (deliver reconnected true))
                            :max-reconnect-ms 200
                            :heartbeat-interval-ms 60000})]
    (try
      (is (= "one" (deref first-msg 5000 ::timeout)))
      ;; Drop the TCP connection without a closing handshake.
      (ws-server/drop-connection! (deref first-ch 1000 nil))
      (is (= "two" (deref second-msg 10000 ::timeout)))
      (is (true? (deref reconnected 1000 ::timeout)))
      (is (<= 2 @url-calls))
      (is (every? #(false? (:fatal %)) @errors))
      (is (seq @errors))
      ;; The reconnect used a freshly resolved URL.
      (is (= (str "cursor=" @url-calls) (last @queries)))
      (finally
        (ws/close! handle)
        (ws-server/stop! server)))))

(deftest heartbeat-detects-dead-peer-test
  (let [server (ws-server/start-silent!)
        url-calls (atom 0)
        errors (atom [])
        dead (promise)
        handle (ws/connect {:url-fn (fn [cb]
                                      (swap! url-calls inc)
                                      (cb (:url server)))
                            :on-message (fn [_])
                            :on-error (fn [err]
                                        (swap! errors conj err)
                                        (when (= "WSHeartbeatTimeout" (:error err))
                                          (deliver dead err)))
                            :max-reconnect-ms 100
                            :heartbeat-interval-ms 100})]
    (try
      (let [err (deref dead 5000 ::timeout)]
        (is (= "WSHeartbeatTimeout" (:error err)))
        (is (false? (:fatal err))))
      ;; The dead connection triggers a reconnect attempt.
      (let [n @url-calls]
        (Thread/sleep 500)
        (is (< n @url-calls)))
      (finally
        (ws/close! handle)
        (ws-server/stop-silent! server)))))

(deftest handshake-rejection-is-fatal-test
  (let [server (ws-server/start!
                :ring-handler (fn [_req] {:status 400 :body "nope"}))
        errors (atom [])
        fatal (promise)
        handle (ws/connect {:url-fn (fn [cb] (cb (:url server)))
                            :on-message (fn [_])
                            :on-error (fn [err]
                                        (swap! errors conj err)
                                        (when (:fatal err) (deliver fatal err)))
                            :max-reconnect-ms 100})]
    (try
      (let [err (deref fatal 5000 ::timeout)]
        (is (= "WSHandshakeFailed" (:error err)))
        (is (true? (:fatal err))))
      (is (not (ws/connected? handle)))
      (finally
        (ws/close! handle)
        (ws-server/stop! server)))))

(deftest url-fn-error-is-fatal-test
  (let [fatal (promise)
        handle (ws/connect {:url-fn (fn [cb] (cb {:error "CursorStoreError"
                                                  :message "boom"}))
                            :on-message (fn [_])
                            :on-error (fn [err]
                                        (when (:fatal err) (deliver fatal err)))})]
    (try
      (let [err (deref fatal 5000 ::timeout)]
        (is (= "CursorStoreError" (:error err)))
        (is (true? (:fatal err))))
      (finally
        (ws/close! handle)))))

(deftest send-test
  (let [received (promise)
        server (ws-server/start!
                :on-receive (fn [_ch msg] (deliver received msg)))
        connected (promise)
        handle (ws/connect {:url-fn (fn [cb] (cb (:url server)))
                            :on-message (fn [_])})]
    (try
      ;; Wait for the connection to establish before sending.
      (loop [n 0]
        (when (and (not (ws/connected? handle)) (< n 100))
          (Thread/sleep 20)
          (recur (inc n))))
      (is (ws/connected? handle))
      (let [sent (promise)]
        (ws/send! handle "ack-1" #(deliver sent %))
        (is (= {} (deref sent 5000 ::timeout))))
      (is (= "ack-1" (deref received 5000 ::timeout)))
      (finally
        (ws/close! handle)
        (ws-server/stop! server)))))

(deftest send-when-disconnected-test
  (let [handle (ws/connect {:url-fn (fn [cb] (cb "ws://127.0.0.1:1/"))
                            :on-message (fn [_])
                            :max-reconnect-ms 50})
        result (promise)]
    (try
      (ws/send! handle "nope" #(deliver result %))
      (is (= "NotConnected" (:error (deref result 5000 ::timeout))))
      (finally
        (ws/close! handle)))))
