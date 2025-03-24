(ns atproto.data-test
  (:require #?(:clj [clojure.test :refer :all])
            [clojure.spec.alpha :as s]
            [atproto.data :as data]))

(deftest test-validation

  (testing "Valid atproto data"
    (let [obj {:null nil
               :true true
               :false false
               :integer 1
               :string "foobar"
               :bytes (.getBytes "foobar")
               :link (data/cid-link (.getBytes "foobar"))
               :blob {:$type "blob"
                      :ref (data/blob-ref (.getBytes "foobar"))
                      :mimeType "text/plain"
                      :size 42}}]
      (is (s/valid? ::data/value (-> obj
                                     (assoc :array (vec (vals obj)))
                                     (assoc :object obj))))))

  (testing "Invalid atproto data"
    (are [d] (not (s/valid? ::data/value d))
      1N
      data/js-max-integer
      (dec (- data/js-max-integer))
      {:$type "blob"})))
