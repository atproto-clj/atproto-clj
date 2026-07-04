(ns atproto.tap.auth-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [atproto.tap.auth :as auth])
  #?(:clj (:import [java.nio.charset StandardCharsets]
                   [java.util Base64])))

#?(:clj (set! *warn-on-reflection* true))

(defn- b64
  [s]
  #?(:clj (.encodeToString (Base64/getEncoder)
                           (.getBytes ^String s StandardCharsets/UTF_8))
     :cljs (js/btoa s)))

(deftest format-admin-auth-header-test
  (testing "standard base64 WITH padding, like HTTP Basic"
    (is (= (str "Basic " (b64 "admin:hunter2"))
           (auth/format-admin-auth-header "hunter2")))
    (is (= "Basic YWRtaW46aHVudGVyMg=="
           (auth/format-admin-auth-header "hunter2")))))

(deftest round-trip-test
  (is (= "hunter2"
         (auth/parse-admin-auth-header (auth/format-admin-auth-header "hunter2"))))
  (testing "passwords containing colons survive the round trip"
    (is (= "a:b:c"
           (auth/parse-admin-auth-header (auth/format-admin-auth-header "a:b:c")))))
  (testing "empty password"
    (is (= "" (auth/parse-admin-auth-header (auth/format-admin-auth-header ""))))))

(deftest parse-admin-auth-header-errors-test
  (testing "wrong username"
    (is (= "InvalidAuthHeader"
           (:error (auth/parse-admin-auth-header (str "Basic " (b64 "root:pw")))))))
  (testing "non-Basic header"
    (is (= "InvalidAuthHeader"
           (:error (auth/parse-admin-auth-header "Bearer abc123")))))
  (testing "nil header"
    (is (= "InvalidAuthHeader" (:error (auth/parse-admin-auth-header nil)))))
  (testing "garbage base64"
    (is (= "InvalidAuthHeader"
           (:error (auth/parse-admin-auth-header "Basic $$$not-base64$$$")))))
  (testing "base64 of the wrong shape (no colon)"
    (is (= "InvalidAuthHeader"
           (:error (auth/parse-admin-auth-header (str "Basic " (b64 "admin"))))))))

(deftest admin-auth-valid?-test
  (let [header (auth/format-admin-auth-header "hunter2")]
    (testing "correct password"
      (is (true? (auth/admin-auth-valid? "hunter2" header))))
    (testing "wrong password"
      (is (false? (auth/admin-auth-valid? "letmein" header))))
    (testing "wrong username"
      (is (false? (auth/admin-auth-valid? "hunter2"
                                          (str "Basic " (b64 "root:hunter2"))))))
    (testing "nil header"
      (is (false? (auth/admin-auth-valid? "hunter2" nil))))
    (testing "malformed header"
      (is (false? (auth/admin-auth-valid? "hunter2" "Basic %%%%"))))))

(deftest no-password-leakage-test
  (testing "error messages never contain the credentials"
    (doseq [header [(str "Basic " (b64 "root:sup3rs3cret"))
                    (str "Bearer " "sup3rs3cret")
                    (str "Basic " (b64 "sup3rs3cret"))]]
      (let [{:keys [error message]} (auth/parse-admin-auth-header header)]
        (is (= "InvalidAuthHeader" error))
        (is (not (str/includes? (or message "") "sup3rs3cret")))))))
