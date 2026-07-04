(ns atproto.pds.sql-test
  (:require [clojure.test :refer :all]
            [atproto.pds.sql :as sql]))

(deftest connect-and-pragmas-test
  (with-open [conn (sql/connect :memory)]
    (testing "foreign keys are enforced"
      (is (= 1 (:foreign_keys (sql/execute-one! conn ["PRAGMA foreign_keys"])))))
    (testing "extra pragmas are applied"))
  (with-open [conn (sql/connect :memory :pragmas ["PRAGMA query_only = ON"])]
    (is (thrown? Exception (sql/execute! conn ["CREATE TABLE t (a TEXT)"])))))

(deftest migrate-test
  (with-open [conn (sql/connect :memory)]
    (let [v1 [["001-init" ["CREATE TABLE t (a TEXT PRIMARY KEY, b INTEGER)"]]]
          v2 (conj v1 ["002-index" ["CREATE INDEX t_b ON t (b)"]])]
      (sql/migrate! conn v1)
      (testing "already-applied migrations are skipped"
        (sql/migrate! conn v1)
        (sql/migrate! conn v2)
        (is (= ["001-init" "002-index"]
               (map :name (sql/execute! conn ["SELECT name FROM migrations ORDER BY name"])))))
      (testing "fn migrations receive the connection"
        (sql/migrate! conn (conj v2 ["003-seed"
                                     (fn [conn]
                                       (sql/execute! conn ["INSERT INTO t (a, b) VALUES ('x', 1)"]))]))
        (is (= {:a "x" :b 1} (sql/execute-one! conn ["SELECT a, b FROM t"])))))))

(deftest write-tx-test
  (with-open [conn (sql/connect :memory)]
    (sql/migrate! conn [["001-init" ["CREATE TABLE t (a TEXT PRIMARY KEY)"]]])
    (testing "commits on normal return"
      (is (= :done (sql/with-write-tx [c conn]
                     (sql/execute! c ["INSERT INTO t (a) VALUES ('kept')"])
                     :done)))
      (is (some? (sql/execute-one! conn ["SELECT a FROM t WHERE a = 'kept'"]))))
    (testing "rolls back on throw"
      (is (thrown? Exception
                   (sql/with-write-tx [c conn]
                     (sql/execute! c ["INSERT INTO t (a) VALUES ('dropped')"])
                     (throw (ex-info "boom" {})))))
      (is (nil? (sql/execute-one! conn ["SELECT a FROM t WHERE a = 'dropped'"]))))))

(deftest retry-busy-test
  (testing "passes values through"
    (is (= 42 (sql/retry-busy (fn [] 42)))))
  (testing "non-busy exceptions propagate immediately"
    (let [calls (atom 0)]
      (is (thrown? Exception (sql/retry-busy (fn [] (swap! calls inc) (throw (ex-info "nope" {}))))))
      (is (= 1 @calls)))))

(deftest now-iso-test
  (testing "fixed-width millisecond ISO timestamps compare lexicographically"
    (let [t (sql/now-iso)]
      (is (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z" t))
      (is (neg? (compare "2020-01-01T00:00:00.000Z" t))))))
