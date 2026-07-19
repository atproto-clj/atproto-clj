(ns statusphere.ingester-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [atproto.sync.cursor :as cursor]
            [statusphere.db :as db]
            [statusphere.ingester :as ingester]
            [statusphere.system :as system]))

(defn fresh-conn []
  (system/register-lexicons!)
  (db/connect (str "datomic:mem://" (gensym "ingester-test"))))

(defn create-event [did rkey emoji & {:as record-overrides}]
  {:kind       :create
   :did        did
   :collection ingester/collection
   :rkey       rkey
   :uri        (str "at://" did "/" ingester/collection "/" rkey)
   :record     (merge {:$type     ingester/collection
                       :status    emoji
                       :createdAt "2026-07-19T12:00:00Z"}
                      record-overrides)})

(deftest creates-and-updates-index
  (let [conn (fresh-conn)]
    (ingester/handle-event! conn (create-event "did:plc:a" "3jzfcijpj2z2a" "🚀"))
    (let [s (db/current-status (d/db conn) "did:plc:a")]
      (is (= "🚀" (:status/emoji s)))
      (is (inst? (:status/created-at s))))
    (testing "replays are idempotent, updates overwrite"
      (ingester/handle-event! conn (create-event "did:plc:a" "3jzfcijpj2z2a" "🚀"))
      (ingester/handle-event! conn (-> (create-event "did:plc:a" "3jzfcijpj2z2a" "💀")
                                       (assoc :kind :update)))
      (let [statuses (db/recent-statuses (d/db conn) 10)]
        (is (= 1 (count statuses)))
        (is (= "💀" (:status/emoji (first statuses))))))))

(deftest invalid-records-are-skipped
  (let [conn (fresh-conn)]
    (ingester/handle-event! conn (create-event "did:plc:a" "r1" "🚀🚀"))   ;; two graphemes
    (ingester/handle-event! conn (create-event "did:plc:a" "r2" ""))       ;; too short
    (ingester/handle-event! conn (create-event "did:plc:a" "r3" nil))      ;; missing
    (is (empty? (db/recent-statuses (d/db conn) 10)))))

(deftest bad-created-at-falls-back-to-now
  (let [conn (fresh-conn)]
    (ingester/handle-event! conn (create-event "did:plc:a" "r1" "🚀"
                                               :createdAt "2026-07-19T12:00:00Z"))
    ;; createdAt passes the lexicon's datetime format check but defensive
    ;; parsing still applies on the way into the db
    (is (inst? (:status/created-at (db/current-status (d/db conn) "did:plc:a"))))))

(deftest deletes-retract
  (let [conn  (fresh-conn)
        event (create-event "did:plc:a" "r1" "🚀")]
    (ingester/handle-event! conn event)
    (testing "deleting an unknown uri is a no-op"
      (ingester/handle-event! conn {:kind :delete :did "did:plc:a"
                                    :collection ingester/collection
                                    :rkey "nope"
                                    :uri (str "at://did:plc:a/" ingester/collection "/nope")})
      (is (= 1 (count (db/recent-statuses (d/db conn) 10)))))
    (ingester/handle-event! conn (assoc event :kind :delete))
    (is (empty? (db/recent-statuses (d/db conn) 10)))))

(deftest irrelevant-events-are-ignored
  (let [conn (fresh-conn)]
    (ingester/handle-event! conn {:kind :identity :did "did:plc:a" :handle "a.test"})
    (ingester/handle-event! conn {:kind :account :did "did:plc:a" :active false})
    (ingester/handle-event! conn {:kind :unknown :raw {}})
    (ingester/handle-event! conn (assoc (create-event "did:plc:a" "r1" "🚀")
                                        :collection "app.bsky.feed.post"))
    (is (empty? (db/recent-statuses (d/db conn) 10)))))

(deftest datomic-cursor-store-round-trip
  (let [store (ingester/datomic-cursor-store (fresh-conn) "jetstream")
        got   (atom nil)]
    (cursor/get-cursor store #(reset! got %))
    (is (= {:cursor nil} @got))
    (cursor/set-cursor store 1721390000000000 #(reset! got %))
    (is (= {} @got))
    (cursor/get-cursor store #(reset! got %))
    (is (= {:cursor 1721390000000000} @got))))

(deftest throttled-cursor-store-coalesces
  (let [now     (atom 0)
        written (atom [])
        inner   (reify cursor/CursorStore
                  (get-cursor [_ cb] (cb {:cursor (peek @written)}))
                  (set-cursor [_ c cb] (swap! written conj c) (cb {})))
        {:keys [store flush!]} (ingester/throttled-cursor-store inner 5000 :now-fn #(deref now))]
    (reset! now 10000)
    (cursor/set-cursor store 1 (fn [_]))          ;; first write goes through
    (cursor/set-cursor store 2 (fn [_]))          ;; within interval: pending
    (cursor/set-cursor store 3 (fn [_]))
    (is (= [1] @written))
    (reset! now 16000)
    (cursor/set-cursor store 4 (fn [_]))          ;; interval elapsed: written
    (is (= [1 4] @written))
    (testing "flush! persists the pending tail exactly once"
      (cursor/set-cursor store 5 (fn [_]))
      (flush!)
      (is (= [1 4 5] @written))
      (flush!)
      (is (= [1 4 5] @written)))))
