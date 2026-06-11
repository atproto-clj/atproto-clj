(ns atproto.xrpc.rate-limit-test
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            [atproto.xrpc.rate-limit :as rate-limit]))

(defn- consume!
  [limiter ctx]
  (let [result (atom ::none)]
    (rate-limit/consume limiter ctx #(reset! result %))
    @result))

(deftest memory-limiter-test
  (let [limiter (rate-limit/memory {:key-prefix "t"
                                    :duration-ms 60000
                                    :points 2
                                    :calc-key (constantly "k")})]
    (testing "status map shape while under the limit"
      (let [status (consume! limiter {})]
        (is (= {:limit 2 :duration 60 :remaining-points 1 :consumed-points 1}
               (select-keys status [:limit :duration :remaining-points :consumed-points])))
        (is (pos? (:ms-before-next status)))))
    (testing "exceeding the limit"
      (consume! limiter {})
      (let [result (consume! limiter {})]
        (is (= "RateLimitExceeded" (:error result)))
        (is (= 429 (:status result)))
        (is (= 0 (:remaining-points (:ratelimit-status result))))))
    (testing "reset clears the counter"
      (rate-limit/reset limiter {} identity)
      (is (= 1 (:consumed-points (consume! limiter {})))))
    (testing "calc-points below 1 skips"
      (is (nil? (consume! limiter {::rate-limit/calc-points (constantly 0)}))))))

(deftest wrapped-limiter-test
  (let [limiter (rate-limit/memory {:key-prefix "w"
                                    :duration-ms 60000
                                    :points 10
                                    :calc-key (constantly "default")})
        per-user (rate-limit/wrapped limiter
                                     {:calc-key (fn [ctx] (get-in ctx [:auth :did]))
                                      :calc-points (constantly 5)})]
    (testing "overridden key and points"
      (is (= 5 (:consumed-points (consume! per-user {:auth {:did "did:example:a"}}))))
      (is (= 9 (:remaining-points (consume! limiter {})))
          "the wrapped limiter consumed under a separate key")
      (is (= 0 (:remaining-points (consume! per-user {:auth {:did "did:example:a"}}))))
      (is (= "RateLimitExceeded"
             (:error (consume! per-user {:auth {:did "did:example:a"}})))))
    (testing "nil key from the override skips"
      (is (nil? (consume! per-user {}))))))

(deftest combined-limiter-test
  (let [loose (rate-limit/memory {:key-prefix "loose"
                                  :duration-ms 60000
                                  :points 100
                                  :calc-key (constantly "k")})
        tight (rate-limit/memory {:key-prefix "tight"
                                  :duration-ms 60000
                                  :points 2
                                  :calc-key (constantly "k")})
        both (rate-limit/combined [loose tight])]
    (testing "tightest status wins"
      (is (= 1 (:remaining-points (consume! both {}))))
      (is (= 0 (:remaining-points (consume! both {})))))
    (testing "any exceeded result short-circuits"
      (is (= "RateLimitExceeded" (:error (consume! both {})))))))
