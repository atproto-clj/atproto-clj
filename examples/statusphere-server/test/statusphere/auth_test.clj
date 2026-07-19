(ns statusphere.auth-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [atproto.oauth.client.store :as store]
            [atproto.runtime.json :as json]
            [statusphere.auth :as auth]
            [statusphere.db :as db]))

(defn fresh-conn []
  (db/connect (str "datomic:mem://" (gensym "auth-test"))))

(deftest session-store-round-trip
  (let [s (auth/session-store (fresh-conn))]
    (is (nil? (store/get* s "did:a")))
    (store/set* s "did:a" (json/write-str {:tokens 1}))
    (is (= {:tokens 1} (store/get s "did:a")))
    (testing "overwrite"
      (store/set* s "did:a" (json/write-str {:tokens 2}))
      (is (= {:tokens 2} (store/get s "did:a"))))
    (testing "delete, and deleting an absent key"
      (store/del* s "did:a")
      (is (nil? (store/get* s "did:a")))
      (store/del* s "did:a")
      (is (nil? (store/get* s "did:a"))))))

(deftest state-store-expiry
  (let [now  (quot (System/currentTimeMillis) 1000)
        s    (auth/state-store (fresh-conn))]
    (store/set s "expired" {:verifier "v" :expires-at (- now 10)})
    (store/set s "fresh"   {:verifier "v" :expires-at (+ now 3600)})
    (testing "expired entries read as absent"
      (is (nil? (store/get s "expired")))
      (is (some? (store/get s "fresh"))))
    (testing "writes sweep expired rows out of the database"
      (store/set s "another" {:verifier "v" :expires-at (+ now 3600)})
      ;; get* bypasses the read-side filter only through the swept db,
      ;; so absence here proves the row is gone, not just filtered.
      (is (nil? (store/get* s "expired"))))))

(deftest client-metadata-loopback-mode
  (let [md (auth/client-metadata {:port 8080})]
    (is (str/starts-with? (:client_id md) "http://localhost?redirect_uri="))
    (is (str/includes? (:client_id md) "127.0.0.1%3A8080")
        "redirect_uri inside client_id uses 127.0.0.1, not localhost")
    (is (= ["http://127.0.0.1:8080/oauth/callback"] (:redirect_uris md)))
    (is (= "none" (:token_endpoint_auth_method md)))
    (is (true? (:dpop_bound_access_tokens md)))))

(deftest client-metadata-production-mode
  (let [md (auth/client-metadata {:port 8080 :public-url "https://statusphere.example.com"})]
    (is (= "https://statusphere.example.com/client-metadata.json" (:client_id md)))
    (is (= ["https://statusphere.example.com/oauth/callback"] (:redirect_uris md)))))
