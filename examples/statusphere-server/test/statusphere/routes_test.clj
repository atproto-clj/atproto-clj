(ns statusphere.routes-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datomic.api :as d]
            [io.pedestal.connector.test :as pt]
            [io.pedestal.http.jetty :as jetty]
            [statusphere.db :as db]
            [statusphere.routes :as routes]
            [statusphere.system :as system])
  (:import [java.util Date]))

(use-fixtures :once pt/disable-routing-table-output-fixture)

;; 16 zero bytes, base64 — a valid (test-only) cookie secret.
(def test-secret "AAAAAAAAAAAAAAAAAAAAAA==")

(def test-config {:port 0 :cookie-secret test-secret})

(defn test-connector
  "An unstarted connector: response-for executes the interceptor chain
  in-process, so no server socket is ever opened."
  [app]
  (-> (routes/connector-map test-config app)
      (jetty/create-connector nil)))

(defn fresh-app []
  (system/register-lexicons!)
  {:conn   (db/connect (str "datomic:mem://" (gensym "routes-test")))
   :config test-config})

(deftest home-renders
  (let [{:keys [conn] :as app} (fresh-app)]
    @(d/transact conn (db/upsert-status-tx {:uri        "at://did:a/xyz.statusphere.status/1"
                                            :author-did "did:a"
                                            :emoji      "🚀"
                                            :created-at (Date.)
                                            :indexed-at (Date.)}))
    (let [{:keys [status body]} (pt/response-for (test-connector app) :get "/")]
      (is (= 200 status))
      (is (str/includes? body "Statusphere"))
      (is (str/includes? body "🚀"))
      (is (str/includes? body "login-form") "logged-out home shows the login form"))))

(deftest login-page-renders
  (let [{:keys [status body]} (pt/response-for (test-connector (fresh-app)) :get "/login")]
    (is (= 200 status))
    (is (str/includes? body "login-form"))))

(deftest static-css-is-served
  (let [{:keys [status headers]} (pt/response-for (test-connector (fresh-app)) :get "/style.css")]
    (is (= 200 status))
    (is (str/starts-with? (get headers "Content-Type" "") "text/css"))))

(deftest unknown-path-is-404
  (is (= 404 (:status (pt/response-for (test-connector (fresh-app)) :get "/nope")))))

(deftest hostile-status-is-escaped
  (let [{:keys [conn] :as app} (fresh-app)]
    @(d/transact conn (db/upsert-status-tx {:uri        "at://did:evil/xyz.statusphere.status/1"
                                            :author-did "did:evil"
                                            :emoji      "<script>alert(1)</script>"
                                            :created-at (Date.)
                                            :indexed-at (Date.)}))
    (let [{:keys [body]} (pt/response-for (test-connector app) :get "/")]
      (is (not (str/includes? body "<script>alert(1)")))
      (is (str/includes? body "&lt;script&gt;")))))
