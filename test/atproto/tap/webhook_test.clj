(ns atproto.tap.webhook-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [atproto.runtime.json :as json]
            [atproto.tap.auth :as auth]
            [atproto.tap.webhook :as webhook])
  (:import [java.io ByteArrayInputStream]))

(set! *warn-on-reflection* true)

(def password "hunter2")
(def did "did:plc:ewvi7nxzyoun6zhxrhs64oiz")
(def cid-str "bafyreie5cvv4h45feadgeuwhbcutmh6t2ceseocckahdoe6uat64zmz454")

(def wire-event
  {:id 1
   :type "record"
   :record {:live true
            :did did
            :rev "3kao2c5nmvz2c"
            :collection "app.bsky.feed.post"
            :rkey "3kao2c5nmvv2c"
            :action "create"
            :cid cid-str
            :record {:text "hello"}}})

(defn- body-stream
  [^String s]
  (ByteArrayInputStream. (.getBytes s "UTF-8")))

(defn- request
  [& {:keys [method uri authorization body]
      :or {method :post
           uri "/tap"
           authorization (auth/format-admin-auth-header password)}}]
  (cond-> {:request-method method
           :uri uri
           :headers (if authorization {"authorization" authorization} {})}
    body (assoc :body (body-stream body))))

(defn- json-body [resp] (json/read-str (:body resp)))

(deftest off-path-test
  (let [handler (webhook/handler {:admin-password password :handler (fn [_])})]
    (testing "requests for other paths return nil (composable)"
      (is (nil? (handler (request :uri "/other"))))
      (is (nil? (handler (request :uri "/tap/sub")))))
    (testing "a custom :path is honored"
      (let [handler (webhook/handler {:admin-password password
                                      :handler (fn [_])
                                      :path "/hooks/tap"})]
        (is (nil? (handler (request :uri "/tap"))))
        (is (some? (handler (request :uri "/hooks/tap"
                                     :body (json/write-str wire-event)))))))))

(deftest method-not-allowed-test
  (let [handler (webhook/handler {:admin-password password :handler (fn [_])})
        resp (handler (request :method :get))]
    (is (= 405 (:status resp)))
    (is (= "application/json" (get-in resp [:headers "content-type"])))
    (is (= {:error "MethodNotAllowed"} (json-body resp)))))

(deftest auth-test
  (let [seen (atom [])
        handler (webhook/handler {:admin-password password
                                  :handler (fn [e] (swap! seen conj e))})]
    (testing "missing auth -> 401"
      (let [resp (handler (request :authorization nil
                                   :body (json/write-str wire-event)))]
        (is (= 401 (:status resp)))
        (is (= {:error "AuthRequired"} (json-body resp)))))
    (testing "wrong password -> 401"
      (let [resp (handler (request :authorization (auth/format-admin-auth-header "wrong")
                                   :body (json/write-str wire-event)))]
        (is (= 401 (:status resp)))
        (is (= {:error "AuthRequired"} (json-body resp)))))
    (is (empty? @seen))))

(deftest valid-event-test
  (let [seen (atom [])
        handler (webhook/handler {:admin-password password
                                  :handler (fn [e] (swap! seen conj e))})
        resp (handler (request :body (json/write-str wire-event)))]
    (is (= 200 (:status resp)))
    (is (= "application/json" (get-in resp [:headers "content-type"])))
    (is (= "{}" (:body resp)))
    (testing "the handler saw the flattened event"
      (is (= 1 (count @seen)))
      (let [event (first @seen)]
        (is (= [:record 1 :create did "3kao2c5nmvv2c" true]
               ((juxt :type :id :action :did :rkey :live) event)))
        (is (= {:text "hello"} (:record event)))
        (is (= cid-str (:cid event)))))))

(deftest malformed-json-test
  (let [handler (webhook/handler {:admin-password password :handler (fn [_])})
        resp (handler (request :body "{not json"))]
    (is (= 400 (:status resp)))
    (let [body (json-body resp)]
      (is (= "InvalidTapEvent" (:error body)))
      (is (string? (:message body))))))

(deftest invalid-event-test
  (let [handler (webhook/handler {:admin-password password :handler (fn [_])})
        resp (handler (request :body (json/write-str {:id 1 :type "bogus"})))]
    (is (= 400 (:status resp)))
    (is (= "InvalidTapEvent" (:error (json-body resp))))))

(deftest throwing-handler-test
  (let [handler (webhook/handler {:admin-password password
                                  :handler (fn [_]
                                             (throw (ex-info "super secret detail" {})))})
        resp (handler (request :body (json/write-str wire-event)))]
    (is (= 500 (:status resp)))
    (is (= {:error "InternalError"} (json-body resp)))
    (testing "the exception message is never leaked"
      (is (not (str/includes? (:body resp) "super secret detail"))))))
