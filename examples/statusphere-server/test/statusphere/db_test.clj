(ns statusphere.db-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [statusphere.db :as db])
  (:import [java.util Date]))

(defn fresh-conn []
  (db/connect (str "datomic:mem://" (gensym "db-test"))))

(defn status
  "A status map for upsert-status-tx, with instants derived from `t` millis."
  [uri did emoji t]
  {:uri        uri
   :author-did did
   :emoji      emoji
   :created-at (Date. (long t))
   :indexed-at (Date. (long t))})

(deftest connect-is-idempotent
  (let [uri (str "datomic:mem://" (gensym "db-test"))]
    (db/connect uri)
    (is (some? (db/connect uri)) "second connect (re-transacting schema) succeeds")))

(deftest upsert-and-query
  (let [conn (fresh-conn)]
    @(d/transact conn (db/upsert-status-tx (status "at://did:a/s/1" "did:a" "🚀" 1000)))
    @(d/transact conn (db/upsert-status-tx (status "at://did:b/s/1" "did:b" "🥹" 2000)))

    (testing "recent-statuses is newest-first and limited"
      (let [statuses (db/recent-statuses (d/db conn) 10)]
        (is (= ["at://did:b/s/1" "at://did:a/s/1"] (mapv :status/uri statuses))))
      (is (= 1 (count (db/recent-statuses (d/db conn) 1)))))

    (testing "same tx twice is a no-op (idempotent firehose replay)"
      @(d/transact conn (db/upsert-status-tx (status "at://did:a/s/1" "did:a" "🚀" 1000)))
      (is (= 2 (count (db/recent-statuses (d/db conn) 10)))))

    (testing "update by uri overwrites in place"
      @(d/transact conn (db/upsert-status-tx (status "at://did:a/s/1" "did:a" "💀" 3000)))
      (let [statuses (db/recent-statuses (d/db conn) 10)]
        (is (= 2 (count statuses)))
        (is (= "💀" (:status/emoji (first statuses))))
        (is (= "at://did:a/s/1" (:status/uri (first statuses))))))))

(deftest current-status-picks-latest-indexed
  (let [conn (fresh-conn)]
    (is (nil? (db/current-status (d/db conn) "did:a")))
    @(d/transact conn (db/upsert-status-tx (status "at://did:a/s/1" "did:a" "👍" 1000)))
    @(d/transact conn (db/upsert-status-tx (status "at://did:a/s/2" "did:a" "👀" 2000)))
    @(d/transact conn (db/upsert-status-tx (status "at://did:b/s/1" "did:b" "🥷" 3000)))
    (is (= "👀" (:status/emoji (db/current-status (d/db conn) "did:a"))))
    (is (= "🥷" (:status/emoji (db/current-status (d/db conn) "did:b"))))))

(deftest retraction
  (let [conn (fresh-conn)]
    (testing "unknown uri yields nil (no lookup-ref throw)"
      (is (nil? (db/retract-status-tx (d/db conn) "at://did:a/s/1"))))
    @(d/transact conn (db/upsert-status-tx (status "at://did:a/s/1" "did:a" "👍" 1000)))
    (let [tx (db/retract-status-tx (d/db conn) "at://did:a/s/1")]
      (is (some? tx))
      @(d/transact conn tx)
      (is (empty? (db/recent-statuses (d/db conn) 10))))))
