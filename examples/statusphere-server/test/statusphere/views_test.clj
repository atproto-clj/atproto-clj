(ns statusphere.views-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [statusphere.views :as views])
  (:import [java.util Date]))

(defn- status [uri did emoji]
  {:status/uri uri :status/author-did did :status/emoji emoji
   :status/created-at (Date. 0) :status/indexed-at (Date. 0)})

(deftest home-logged-out
  (let [html (views/home {:statuses   [(status "at://did:a/s/1" "did:a" "🚀")]
                          :handle-for {"did:a" "alice.test"}
                          :csrf-token "tok"})]
    (is (str/includes? html "Statusphere"))
    (is (str/includes? html "login-form"))
    (is (str/includes? html "@alice.test"))
    (is (str/includes? html "🚀"))
    (is (not (str/includes? html "status-options")) "no picker when signed out")))

(deftest home-logged-in
  (let [html (views/home {:viewer     {:did "did:a" :handle "alice.test" :display-name "Alice"}
                          :my-status  "🚀"
                          :statuses   []
                          :handle-for {}
                          :csrf-token "tok"})]
    (is (str/includes? html "Hi, <strong>Alice</strong>"))
    (is (str/includes? html "status-options"))
    (is (str/includes? html "selected") "current emoji is marked")
    (is (str/includes? html "name=\"__anti-forgery-token\""))))

(deftest untrusted-strings-are-escaped
  (testing "a hostile emoji field cannot inject markup"
    (let [html (views/home {:statuses   [(status "at://did:evil/s/1" "did:evil"
                                                 "<script>alert(1)</script>")]
                            :handle-for {"did:evil" "<img src=x onerror=alert(1)>"}
                            :csrf-token "tok"})]
      (is (not (str/includes? html "<script>alert(1)</script>")))
      (is (not (str/includes? html "<img src=x")))
      (is (str/includes? html "&lt;script&gt;")))))

(deftest error-codes-map-to-fixed-messages
  (let [html (views/login {:error "oauth" :csrf-token "tok"})]
    (is (str/includes? html "Could not sign you in")))
  (testing "unknown codes render nothing"
    (let [html (views/login {:error "<b>injected</b>" :csrf-token "tok"})]
      (is (not (str/includes? html "injected")))
      (is (not (str/includes? html "error visible"))))))
