(ns atproto.jetstream-test
  "Jetstream consumer tests: typed parsing over canned JSON, and the
  channel/cursor contract against an in-process WebSocket server."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :as a]
            [org.httpkit.server :as httpkit]
            [atproto.data :as data]
            [atproto.jetstream :as jet]
            [atproto.runtime.json :as json]
            [atproto.support.ws-server :as ws-server]
            [atproto.sync.cursor :as cursor]))

(set! *warn-on-reflection* true)

(def did "did:plc:44ybard66vv44zksje25o7dz")
(def record {:$type "xyz.statusphere.status"
             :status "🦋"
             :createdAt "2026-07-04T12:00:00.000Z"})
(def cid-str (data/format-cid (data/cid-for record)))

(defn- commit-event
  [& {:keys [time-us operation record cid]
      :or {time-us 1000000 operation "create"}}]
  (cond-> {:did did
           :time_us time-us
           :kind "commit"
           :commit (cond-> {:rev "3l3qo2vutsw2b"
                            :operation operation
                            :collection "xyz.statusphere.status"
                            :rkey "self"}
                     record (assoc :record record)
                     cid (assoc :cid cid))}))

;; -----------------------------------------------------------------------------
;; Typed parsing
;; -----------------------------------------------------------------------------

(deftest typed-event-test
  (testing "commit create"
    (let [event (jet/typed-event (commit-event :record record :cid cid-str))]
      (is (= :create (:kind event)))
      (is (= did (:did event)))
      (is (= 1000000 (:time-us event)))
      (is (= "xyz.statusphere.status" (:collection event)))
      (is (= "self" (:rkey event)))
      (is (= "3l3qo2vutsw2b" (:rev event)))
      (is (= (str "at://" did "/xyz.statusphere.status/self") (:uri event)))
      (is (= "🦋" (get-in event [:record :status])))
      (is (data/cid? (:cid event)))
      (is (= cid-str (data/format-cid (:cid event))))))
  (testing "record $link/$bytes forms decode to data-model values"
    (let [event (jet/typed-event
                 (commit-event :record {:subject {:$link cid-str}} :cid cid-str))]
      (is (data/cid? (get-in event [:record :subject])))))
  (testing "update and delete"
    (is (= :update (:kind (jet/typed-event
                           (commit-event :operation "update" :record record :cid cid-str)))))
    (let [event (jet/typed-event (commit-event :operation "delete"))]
      (is (= :delete (:kind event)))
      (is (not (contains? event :record)))
      (is (not (contains? event :cid)))))
  (testing "identity"
    (let [event (jet/typed-event {:did did
                                  :time_us 2000000
                                  :kind "identity"
                                  :identity {:did did
                                             :handle "alice.test"
                                             :seq 42
                                             :time "2026-07-04T12:00:00.000Z"}})]
      (is (= {:kind :identity :did did :time-us 2000000 :seq 42
              :time "2026-07-04T12:00:00.000Z" :handle "alice.test"}
             event))))
  (testing "account, including open statuses"
    (let [event (jet/typed-event {:did did
                                  :time_us 3000000
                                  :kind "account"
                                  :account {:did did :seq 43 :active false
                                            :status "desynchronized"
                                            :time "2026-07-04T12:00:00.000Z"}})]
      (is (= :account (:kind event)))
      (is (false? (:active event)))
      (is (= "desynchronized" (:status event)))))
  (testing "unknown kinds pass through raw"
    (let [raw {:did did :time_us 1 :kind "starfleet"}]
      (is (= {:kind :unknown :raw raw} (jet/typed-event raw))))
    (let [raw (assoc-in (commit-event) [:commit :operation] "explode")]
      (is (= {:kind :unknown :raw raw} (jet/typed-event raw))))))

;; -----------------------------------------------------------------------------
;; Channel contract against an in-process server
;; -----------------------------------------------------------------------------

(defn- jetstream-server
  "WS server replaying (events-fn conn-number) as JSON text frames; captures
  query strings and channels."
  [events-fn queries channels]
  (let [conns (atom 0)]
    (ws-server/start!
     :ring-handler
     (fn [req]
       (let [n (swap! conns inc)]
         (swap! queries conj (:query-string req))
         (httpkit/as-channel
          req
          {:on-open (fn [ch]
                      (swap! channels conj ch)
                      (doseq [event (events-fn n)]
                        (httpkit/send! ch (json/write-str event))))}))))))

(deftest consume-raw-mode-test
  (let [queries (atom []) channels (atom [])
        server (jetstream-server
                (fn [n] (when (= 1 n)
                          [(commit-event :time-us 100 :record record :cid cid-str)]))
                queries channels)
        ch (a/chan 16)
        control-ch (jet/consume ch :host (:url server) :cursor 555)]
    (try
      (let [event (a/alt!! ch ([v] v) (a/timeout 5000) ::timeout)]
        ;; raw JSON map with the shape the statusphere ingester destructures
        (let [{:keys [did commit]} event
              {:keys [operation collection rkey record]} commit]
          (is (= "did:plc:44ybard66vv44zksje25o7dz" did))
          (is (= "create" operation))
          (is (= "xyz.statusphere.status" collection))
          (is (= "self" rkey))
          (is (= "🦋" (:status record)))))
      ;; initial :cursor is used as-is
      (is (= "cursor=555" (first @queries)))
      (testing "closing the control channel halts processing and closes ch"
        (a/close! control-ch)
        (let [v (a/alt!! ch ([v] v) (a/timeout 5000) ::timeout)]
          (is (nil? v))))
      (finally
        (a/close! control-ch)
        (ws-server/stop! server)))))

(deftest consume-typed-and-cursor-store-test
  (let [queries (atom []) channels (atom [])
        server (jetstream-server
                (fn [n] (if (= 1 n)
                          [(commit-event :time-us 1000 :record record :cid cid-str)]
                          [(commit-event :time-us 2000 :operation "delete")]))
                queries channels)
        store (cursor/memory-store)
        ch (a/chan 16)
        control-ch (jet/consume ch
                                :host (:url server)
                                :typed? true
                                :cursor-store store
                                :ws-opts {:max-reconnect-ms 200})]
    (try
      (let [event (a/alt!! ch ([v] v) (a/timeout 5000) ::timeout)]
        (is (= :create (:kind event)))
        (is (= 1000 (:time-us event))))
      ;; the cursor store saw the event's time_us
      (loop [n 0]
        (let [c (promise)]
          (cursor/get-cursor store #(deliver c (:cursor %)))
          (when (and (not= 1000 (deref c 1000 nil)) (< n 100))
            (Thread/sleep 20)
            (recur (inc n)))))
      (let [c (promise)]
        (cursor/get-cursor store #(deliver c %))
        (is (= {:cursor 1000} (deref c 1000 ::timeout))))
      ;; force a reconnect: the new connection resumes from the stored
      ;; cursor rewound by 1µs
      (ws-server/drop-connection! (first @channels))
      (let [event (a/alt!! ch ([v] v) (a/timeout 10000) ::timeout)]
        (is (= :delete (:kind event)))
        (is (= 2000 (:time-us event))))
      (is (= "cursor=999" (second @queries)))
      (finally
        (a/close! control-ch)
        (ws-server/stop! server)))))

(deftest consume-compress-unsupported-test
  (let [result (jet/consume (a/chan) :compress? true)]
    (is (= "UnsupportedOption" (:error result)))))
