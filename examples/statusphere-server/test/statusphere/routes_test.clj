(ns statusphere.routes-test
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datomic.api :as d]
            [io.pedestal.connector.test :as pt]
            [io.pedestal.http.jetty :as jetty]
            [atproto.client :as at]
            [atproto.identity :as identity]
            [atproto.oauth.client :as oauth-client]
            [atproto.runtime.json :as json]
            [statusphere.auth :as auth]
            [statusphere.db :as db]
            [statusphere.handles :as handles]
            [statusphere.routes :as routes]
            [statusphere.system :as system])
  (:import [java.net URLEncoder]
           [java.util Date]))

(use-fixtures :once pt/disable-routing-table-output-fixture)

;; 16 zero bytes, base64 — a valid (test-only) cookie secret.
(def test-secret "AAAAAAAAAAAAAAAAAAAAAA==")

(def test-config {:port 8080 :cookie-secret test-secret})

(defn test-connector
  "An unstarted connector: response-for executes the interceptor chain
  in-process, so no server socket is ever opened."
  [app]
  (-> (routes/connector-map test-config app)
      (jetty/create-connector nil)))

(defn fresh-app []
  (system/register-lexicons!)
  (let [conn (db/connect (str "datomic:mem://" (gensym "routes-test")))]
    {:conn         conn
     :oauth-client (auth/client test-config conn)
     :handles      (handles/resolver)
     :config       test-config}))

;; -----------------------------------------------------------------------------
;; Session/CSRF round-trip helpers
;; -----------------------------------------------------------------------------

(defn- session-cookie [{:keys [headers]}]
  (when-let [set-cookie (get headers "Set-Cookie")]
    (-> (if (coll? set-cookie) (first set-cookie) set-cookie)
        (str/split #";")
        (first))))

(defn- page-token [{:keys [body]}]
  (second (re-find #"name=\"__anti-forgery-token\"[^>]*value=\"([^\"]*)\"" body)))

(defn browse
  "GET path; return {:cookie .. :token ..} for making authenticated POSTs."
  [connector path & {:keys [cookie]}]
  (let [response (pt/response-for connector :get path
                                  :headers (if cookie {"cookie" cookie} {}))]
    {:response response
     :cookie   (or (session-cookie response) cookie)
     :token    (page-token response)}))

(defn form-body [m]
  (str/join "&" (for [[k v] m]
                  (str (name k) "=" (URLEncoder/encode (str v) "UTF-8")))))

(defn post
  "POST a form with the session cookie and CSRF token."
  [connector path {:keys [cookie token]} params]
  (pt/response-for connector :post path
                   :headers {"cookie"       cookie
                             "content-type" "application/x-www-form-urlencoded"}
                   :body (form-body (assoc params :__anti-forgery-token token))))

;; Stubs for the SDK boundary: deliver on the caller-provided :channel,
;; exactly like the real fns.
(defn- stub-async [result]
  (fn [& args]
    (let [ch (second (drop-while #(not= :channel %) args))]
      (a/put! ch result)
      ch)))

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

(deftest post-without-csrf-token-is-rejected
  (let [connector (test-connector (fresh-app))
        {:keys [cookie]} (browse connector "/login")]
    (is (= 403 (:status (pt/response-for connector :post "/login"
                                         :headers {"cookie" cookie
                                                   "content-type" "application/x-www-form-urlencoded"}
                                         :body "handle=alice.test"))))))

(deftest login-submit-redirects-to-authorization-server
  (let [connector (test-connector (fresh-app))
        session   (browse connector "/login")]
    (with-redefs [oauth-client/authorize
                  (stub-async {:authorization-url "https://pds.example/oauth/authorize?request_uri=x"})]
      (let [{:keys [status headers]} (post connector "/login" session {:handle "alice.test"})]
        (is (= 303 status))
        (is (= "https://pds.example/oauth/authorize?request_uri=x"
               (get headers "Location")))))
    (testing "authorize errors bounce back to /login with a fixed code"
      (with-redefs [oauth-client/authorize (stub-async {:error "UnknownHandle"})]
        (let [{:keys [status headers]} (post connector "/login" session {:handle "nope"})]
          (is (= 303 status))
          (is (= "/login?error=oauth" (get headers "Location"))))))))

(deftest oauth-callback-establishes-session
  (let [connector (test-connector (fresh-app))
        {:keys [cookie]} (browse connector "/")]
    (with-redefs [oauth-client/callback (stub-async {:session {:did "did:plc:alice"}})]
      (let [response (pt/response-for connector :get "/oauth/callback?code=c&state=s&iss=i"
                                      :headers {"cookie" cookie})
            cookie'  (session-cookie response)]
        (is (= 303 (:status response)))
        (is (= "/" (get-in response [:headers "Location"])))
        (is (some? cookie') "session cookie is re-issued with the DID (and a rotated CSRF token)")
        (testing "the session survives, and home renders signed-in"
          (with-redefs [oauth-client/restore (stub-async {:did "did:plc:alice" :handle "alice.test"})
                        at/init              (stub-async {:stub-client true})
                        at/query             (stub-async {:error "RecordNotFound"})]
            (let [{:keys [body]} (:response (browse connector "/" :cookie cookie'))]
              (is (str/includes? body "Log out"))
              (is (str/includes? body "alice.test"))
              (is (str/includes? body "status-options")))))))))

(deftest failed-restore-drops-the-stale-session
  (let [connector (test-connector (fresh-app))
        {:keys [cookie]} (browse connector "/")]
    (with-redefs [oauth-client/callback (stub-async {:session {:did "did:plc:alice"}})]
      (let [cookie' (session-cookie (pt/response-for connector :get "/oauth/callback?code=c&state=s&iss=i"
                                                     :headers {"cookie" cookie}))]
        (with-redefs [oauth-client/restore (stub-async {:error "SessionNotFound"})]
          (let [response (pt/response-for connector :get "/" :headers {"cookie" cookie'})
                cookie'' (session-cookie response)]
            (is (= 303 (:status response)))
            (testing "the re-issued cookie is signed out for good"
              (let [{:keys [body status]} (:response (browse connector "/" :cookie cookie''))]
                (is (= 200 status))
                (is (str/includes? body "login-form"))))))))))

(deftest logout-clears-the-session
  (let [connector (test-connector (fresh-app))
        {:keys [cookie]} (browse connector "/")]
    (with-redefs [oauth-client/callback (stub-async {:session {:did "did:plc:alice"}})]
      (let [cookie' (session-cookie (pt/response-for connector :get "/oauth/callback?code=c&state=s&iss=i"
                                                     :headers {"cookie" cookie}))]
        (with-redefs [oauth-client/restore (stub-async {:did "did:plc:alice" :handle "alice.test"})
                      at/init              (stub-async {:stub-client true})
                      oauth-client/revoke  (stub-async {:did "did:plc:alice" :revoked true})]
          (let [session  (browse connector "/" :cookie cookie')
                response (post connector "/logout" session {})
                cookie'' (or (session-cookie response) (:cookie session))]
            (is (= 303 (:status response)))
            (let [{:keys [body]} (:response (browse connector "/" :cookie cookie''))]
              (is (str/includes? body "login-form") "signed out again"))))))))

(defn- signed-in-cookie
  "Full cookie dance: establish a signed-in browser session against stubs."
  [connector]
  (with-redefs [oauth-client/callback (stub-async {:session {:did "did:plc:alice"}})]
    (let [{:keys [cookie]} (browse connector "/")]
      (session-cookie (pt/response-for connector :get "/oauth/callback?code=c&state=s&iss=i"
                                       :headers {"cookie" cookie})))))

(def viewer-stubs
  {#'oauth-client/restore (stub-async {:did "did:plc:alice" :handle "alice.test"})
   #'at/init              (stub-async {:stub-client true})
   #'at/query             (stub-async {:error "RecordNotFound"})})

(deftest send-status-requires-login
  (let [connector (test-connector (fresh-app))
        session   (browse connector "/")]
    (let [{:keys [status headers]} (post connector "/status" session {:status "🚀"})]
      (is (= 303 status))
      (is (= "/login" (get headers "Location"))))))

(deftest send-status-writes-pds-then-index
  (let [{:keys [conn] :as app} (fresh-app)
        connector (test-connector app)
        cookie    (signed-in-cookie connector)
        put-args  (atom nil)]
    (with-redefs-fn (assoc viewer-stubs
                           #'at/procedure
                           (fn [_client req & args]
                             (reset! put-args req)
                             (let [ch (second (drop-while #(not= :channel %) args))]
                               (a/put! ch {:uri (str "at://did:plc:alice/xyz.statusphere.status/"
                                                     (get-in req [:body :rkey]))})
                               ch))
                           #'identity/resolve-identity
                           (stub-async {:did "did:plc:alice" :handle "alice.test"}))
      (fn []
        (let [session  (browse connector "/" :cookie cookie)
              response (post connector "/status" session {:status "🚀"})]
          (is (= 303 (:status response)))
          (is (= "/" (get-in response [:headers "Location"])))
          (testing "the PDS write is a validated putRecord with a TID rkey"
            (is (= "com.atproto.repo.putRecord" (:nsid @put-args)))
            (is (= false (get-in @put-args [:body :validate])))
            (is (= 13 (count (get-in @put-args [:body :rkey]))))
            (is (= "🚀" (get-in @put-args [:body :record :status]))))
          (testing "the optimistic upsert landed in the local index"
            (is (= "🚀" (:status/emoji (db/current-status (d/db conn) "did:plc:alice")))))
          (testing "home shows it, with the resolved handle and marked picker"
            (let [{:keys [body]} (:response (browse connector "/" :cookie cookie))]
              (is (str/includes? body "@alice.test"))
              (is (str/includes? body "status-option selected")))))))))

(deftest send-status-rejects-invalid-statuses
  (let [connector (test-connector (fresh-app))
        cookie    (signed-in-cookie connector)]
    (with-redefs-fn viewer-stubs
      (fn []
        (let [session (browse connector "/" :cookie cookie)]
          (doseq [bad ["totally not an emoji" "🚀🚀" ""]]
            (let [{:keys [status headers]} (post connector "/status" session {:status bad})]
              (is (= 303 status))
              (is (= "/?error=invalid-status" (get headers "Location"))
                  (pr-str bad)))))))))

(deftest send-status-surfaces-pds-failure
  (let [connector (test-connector (fresh-app))
        cookie    (signed-in-cookie connector)]
    (with-redefs-fn (assoc viewer-stubs
                           #'at/procedure (stub-async {:error "InternalServerError"}))
      (fn []
        (let [session (browse connector "/" :cookie cookie)
              {:keys [status headers]} (post connector "/status" session {:status "🚀"})]
          (is (= 303 status))
          (is (= "/?error=pds" (get headers "Location"))))))))

(deftest home-shows-profile-display-name
  (let [connector (test-connector (fresh-app))
        cookie    (signed-in-cookie connector)]
    (with-redefs-fn (assoc viewer-stubs
                           #'at/query
                           (stub-async {:uri   "at://did:plc:alice/app.bsky.actor.profile/self"
                                        :value {:$type       "app.bsky.actor.profile"
                                                :displayName "Alice"}}))
      (fn []
        (let [{:keys [body]} (:response (browse connector "/" :cookie cookie))]
          (is (str/includes? body "Hi, <strong>Alice</strong>")))))))

(deftest client-metadata-is-served
  (let [{:keys [status body headers]} (pt/response-for (test-connector (fresh-app))
                                                       :get "/client-metadata.json")
        metadata (json/read-str body)]
    (is (= 200 status))
    (is (str/starts-with? (get headers "Content-Type") "application/json"))
    (is (= ["http://127.0.0.1:8080/oauth/callback"] (:redirect_uris metadata)))
    (is (str/starts-with? (:client_id metadata) "http://localhost?redirect_uri="))))

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
