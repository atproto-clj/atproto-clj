(ns atproto.sync.cursor-test
  (:require [clojure.test :refer [deftest is testing]]
            [atproto.sync.cursor :as cursor]))

(defn- take-cb
  "Run f with a callback; return what the callback was called with."
  [f]
  (let [result (atom ::not-called)]
    (f #(reset! result %))
    @result))

(deftest memory-store-test
  (testing "empty store yields {:cursor nil}"
    (let [store (cursor/memory-store)]
      (is (= {:cursor nil} (take-cb #(cursor/get-cursor store %))))))
  (testing "seeded store yields the seed"
    (let [store (cursor/memory-store {:cursor 42})]
      (is (= {:cursor 42} (take-cb #(cursor/get-cursor store %))))))
  (testing "set then get round-trips"
    (let [store (cursor/memory-store)]
      (is (= {} (take-cb #(cursor/set-cursor store 7 %))))
      (is (= {:cursor 7} (take-cb #(cursor/get-cursor store %))))
      (is (= {} (take-cb #(cursor/set-cursor store 8 %))))
      (is (= {:cursor 8} (take-cb #(cursor/get-cursor store %)))))))

(deftest metadata-extension-test
  (testing "CursorStore extends via metadata"
    (let [saved (atom nil)
          store (with-meta {}
                  {`cursor/get-cursor (fn [_ cb] (cb {:cursor @saved}))
                   `cursor/set-cursor (fn [_ c cb] (reset! saved c) (cb {}))})]
      (is (cursor/cursor-store? store))
      (is (= {} (take-cb #(cursor/set-cursor store 99 %))))
      (is (= {:cursor 99} (take-cb #(cursor/get-cursor store %)))))))

(deftest cursor-store-pred-test
  (is (cursor/cursor-store? (cursor/memory-store)))
  (is (not (cursor/cursor-store? {})))
  (is (not (cursor/cursor-store? nil))))
