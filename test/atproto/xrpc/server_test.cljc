(ns atproto.xrpc.server-test
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            [clojure.spec.alpha :as s]
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.http :as http]
            [atproto.runtime.json :as json]
            [atproto.lexicon :as lexicon]
            [atproto.crypto :as crypto]
            [atproto.service-auth :as service-auth]
            [atproto.xrpc.rate-limit :as rate-limit]
            [atproto.xrpc.server :as server]))

;; TODO:
;; - subscription?

(def server
  (server/init
   {:validate-response? true
    :lexicon (lexicon/lexicon
              [{:lexicon 1
                :id "com.example.query"
                :defs {:main {:type "query"
                              :parameters {:type "params"
                                           :required ["filter"]
                                           :properties {:filter {:type "boolean"}}}
                              :output {:encoding "application/json"
                                       :schema {:type "object"
                                                :properties {:status {:type "string"
                                                                      :const "ok"}}}}}}}
               {:lexicon 1
                :id "com.example.procedure"
                :defs {:main {:type "procedure"
                              :parameters {:type "params"
                                           :properties {:flag {:type "boolean"}}}
                              :input {:encoding "image/*"}
                              :output {:encoding "application/json"
                                       :schema {:type "object"
                                                :properties {:isDone {:type "boolean"}}}}}}}])}))

(def ^:dynamic *impl* identity)

(defmethod server/handle "com.example.query"
  [ctx xrpc-request]
  (*impl* xrpc-request))

(defmethod server/handle "com.example.procedure"
  [ctx xrpc-request]
  (*impl* xrpc-request))

(defn- url
  [nsid]
  (str "http://example.com/xrpc/" nsid))

(defn error?
  [code {:keys [headers body]}]
  (and (= "application/json" (:content-type headers))
       (= code (:error body))))

(defn invalid-request?
  [resp]
  (and (= 400 (:status resp))
       (error? "InvalidRequest" resp)))

(defn invalid-response?
  [resp]
  (and (= 500 (:status resp))
       (error? "InvalidResponse" resp)))

(defn internal-server-error?
  [resp]
  (and (= 500 (:status resp))
       (error? "InternalServerError" resp)))

(defn method-not-implemented?
  [resp]
  (and (= 501 (:status resp))
       (error? "MethodNotImplemented" resp)))

(defn valid-response?
  [{:keys [encoding body]} resp]
  (and (= 200 (:status resp))
       (= (or encoding "application/json") (:content-type (:headers resp)))
       (= body (:body resp))))

(defn execute
  [http-request]
  @(i/execute {::i/request http-request
               ::i/queue [(server/interceptor server)]}))

(deftest test-xrpc-server

  (testing "delegate request to query method"
    (binding [*impl* (fn [{:keys [nsid params]}]
                       (is (= nsid "com.example.query"))
                       (is (true? (:filter params)))
                       {:encoding "application/json"
                        :body {:status "ok"}})]
      (let [{:keys [status headers body]} (execute {:method :get
                                                    :url (url "com.example.query")
                                                    :query-params {:filter true}})]
        (is (= 200 status))
        (is (= "application/json" (:content-type headers)))
        (is (= {:status "ok"} body)))))

  (testing "delegate request to procedure method"
    (binding [*impl* (fn [{:keys [nsid params encoding body]}]
                       (is (= nsid "com.example.procedure"))
                       (is (false? (:flag params)))
                       {:encoding "application/json"
                        :body {:isDone true}})]
      (let [{:keys [status headers body]} (execute {:method :post
                                                    :url (url "com.example.procedure")
                                                    :query-params {:flag false}
                                                    :headers {:content-type "image/png"}
                                                    :body (byte-array [0])})]
        (is (= 200 status))
        (is (= "application/json" (:content-type headers)))
        (is (= {:isDone true} body)))))

  (testing "request validation"
    (are [http-request] (invalid-request? (execute http-request))
      {:method :get  :url (url "com.example")}
      {:method :post :url (url "com.example.query")}
      {:method :get  :url (url "com.example.query")}
      {:method :get  :url (url "com.example.query") :query-params {:filter "foo"}}
      {:method :post :url (url "com.example.procedure") :input {:encoding "text/plain" :body "test"}}))

  (testing "valid-but-unknown NSID is 501, malformed XRPC path is 400 (reference catchall)"
    (is (method-not-implemented? (execute {:method :get :url (url "com.example.unknown")})))
    (is (invalid-request? (execute {:method :get :url "http://example.com/xrpc/"})))
    (is (invalid-request? (execute {:method :get :url "http://example.com/other/com.example.query"})))
    (is (invalid-request? (execute {:method :get :url (url "not a nsid")}))))

  (testing "response validation"
    (binding [*impl* (constantly {:encoding "application/json"
                                  :body {:status "foo"}})]
      (is
       (invalid-response? (execute {:method :get
                                    :url (url "com.example.query")
                                    :query-params {:filter true}}))))))

;; -----------------------------------------------------------------------------
;; Auth
;; -----------------------------------------------------------------------------

(defn- token-auth-verifier
  "Auth verifier accepting exactly `Bearer <expected>`."
  [expected]
  (fn [{:keys [request]} cb]
    (if (= (get-in request [:headers :authorization]) (str "Bearer " expected))
      (cb {:credentials {:did "did:example:alice"}})
      (cb {:error "AuthenticationRequired" :message "bad token" :status 401}))))

(defn- execute-with
  [config http-request]
  @(i/execute {::i/request http-request
               ::i/queue [(server/interceptor (merge server config))]}))

(deftest auth-pipeline-test
  (let [config {:auth {"com.example.query" (token-auth-verifier "sesame")
                       "com.example.procedure" (token-auth-verifier "sesame")}}]

    (testing "auth success exposes :auth to handle"
      (binding [*impl* (fn [{:keys [auth]}]
                         (is (= {:credentials {:did "did:example:alice"}} auth))
                         {:encoding "application/json"
                          :body {:status "ok"}})]
        (let [resp (execute-with config
                                 {:method :get
                                  :url (url "com.example.query")
                                  :query-params {:filter true}
                                  :headers {:authorization "Bearer sesame"}})]
          (is (= 200 (:status resp))))))

    (testing "auth failure returns the verifier's status and error body"
      (binding [*impl* (fn [_] (throw (ex-info "handler must not run" {})))]
        (let [resp (execute-with config
                                 {:method :get
                                  :url (url "com.example.query")
                                  :query-params {:filter true}
                                  :headers {:authorization "Bearer wrong"}})]
          (is (= 401 (:status resp)))
          (is (= {:error "AuthenticationRequired" :message "bad token"}
                 (:body resp))))))

    (testing "routes without a verifier stay unauthenticated"
      (binding [*impl* (fn [{:keys [auth]}]
                         (is (nil? auth))
                         {:encoding "application/json"
                          :body {:status "ok"}})]
        (is (= 200 (:status (execute-with {:auth {"com.example.procedure"
                                                  (token-auth-verifier "sesame")}}
                                          {:method :get
                                           :url (url "com.example.query")
                                           :query-params {:filter true}}))))))

    (testing ":default verifier applies to routes without a specific entry"
      (let [resp (execute-with {:auth {:default (token-auth-verifier "sesame")}}
                               {:method :get
                                :url (url "com.example.query")
                                :query-params {:filter true}})]
        (is (= 401 (:status resp)))))

    (testing "bad auth fails before an invalid request payload"
      (binding [*impl* (fn [_] (throw (ex-info "handler must not run" {})))]
        (let [resp (execute-with config
                                 {:method :post
                                  :url (url "com.example.procedure")
                                  :headers {:content-type "text/plain"
                                            :authorization "Bearer wrong"}
                                  :body (byte-array [0])})]
          (is (= 401 (:status resp)))
          (is (= "AuthenticationRequired" (get-in resp [:body :error]))))))

    (testing "good auth still fails on the invalid payload"
      (binding [*impl* (fn [_] (throw (ex-info "handler must not run" {})))]
        (let [resp (execute-with config
                                 {:method :post
                                  :url (url "com.example.procedure")
                                  :headers {:content-type "text/plain"
                                            :authorization "Bearer sesame"}
                                  :body (byte-array [0])})]
          (is (invalid-request? resp)))))

    (testing "invalid params fail before auth"
      (let [verifier-calls (atom 0)
            resp (execute-with {:auth {"com.example.query"
                                       (fn [_ctx cb]
                                         (swap! verifier-calls inc)
                                         (cb {:credentials {}}))}}
                               {:method :get
                                :url (url "com.example.query")
                                :query-params {:filter "foo"}})]
        (is (invalid-request? resp))
        (is (zero? @verifier-calls))))))

#?(:clj
   (deftest service-auth-verifier-test
     (let [kp @(crypto/generate "ES256K")
           own-did "did:example:bob"
           verifier (server/service-auth-verifier
                     {:own-did own-did
                      :get-signing-key (fn [_iss _force-refresh? cb]
                                         (cb {:key (crypto/did kp)}))})
           config {:auth {"com.example.query" verifier}}
           token (fn [lxm]
                   (:token @(service-auth/create-jwt {:iss "did:example:alice"
                                                      :aud own-did
                                                      :keypair kp
                                                      :lxm lxm})))]

       (testing "valid service JWT: 200 and :service credentials"
         (binding [*impl* (fn [{:keys [auth]}]
                            (is (= :service (get-in auth [:credentials :type])))
                            (is (= "did:example:alice" (get-in auth [:credentials :did])))
                            {:encoding "application/json"
                             :body {:status "ok"}})]
           (let [resp (execute-with config
                                    {:method :get
                                     :url (url "com.example.query")
                                     :query-params {:filter true}
                                     :headers {:authorization (str "Bearer " (token "com.example.query"))}})]
             (is (= 200 (:status resp))))))

       (testing "missing Authorization: 401 MissingJwt"
         (let [resp (execute-with config
                                  {:method :get
                                   :url (url "com.example.query")
                                   :query-params {:filter true}})]
           (is (= 401 (:status resp)))
           (is (= "MissingJwt" (get-in resp [:body :error])))))

       (testing "lxm bound to a different nsid: 401 BadJwtLexiconMethod"
         (let [resp (execute-with config
                                  {:method :get
                                   :url (url "com.example.query")
                                   :query-params {:filter true}
                                   :headers {:authorization (str "Bearer " (token "com.example.other"))}})]
           (is (= 401 (:status resp)))
           (is (= "BadJwtLexiconMethod" (get-in resp [:body :error]))))))))

;; -----------------------------------------------------------------------------
;; Error mapping
;; -----------------------------------------------------------------------------

(deftest error-mapping-test
  (testing "custom lexicon error names pass through with their status"
    (binding [*impl* (constantly {:error "MyCustomError"
                                  :status 400
                                  :message "custom detail"})]
      (let [resp (execute {:method :get
                           :url (url "com.example.query")
                           :query-params {:filter true}})]
        (is (= 400 (:status resp)))
        (is (= {:error "MyCustomError" :message "custom detail"} (:body resp))))))

  (testing "an :error without :status maps to a generic 500"
    (binding [*impl* (constantly {:error "Whoops"})]
      (is (internal-server-error? (execute {:method :get
                                            :url (url "com.example.query")
                                            :query-params {:filter true}})))))

  (testing "5xx messages are sanitized, error names preserved"
    (binding [*impl* (constantly {:error "UpstreamFailure"
                                  :status 502
                                  :message "secret internal detail"})]
      (let [resp (execute {:method :get
                           :url (url "com.example.query")
                           :query-params {:filter true}})]
        (is (= 502 (:status resp)))
        (is (= {:error "UpstreamFailure" :message "Upstream Failure"} (:body resp))))))

  (testing "InvalidResponse spec explanations never leak"
    (binding [*impl* (constantly {:encoding "application/json"
                                  :body {:status "not-ok"}})]
      (let [resp (execute {:method :get
                           :url (url "com.example.query")
                           :query-params {:filter true}})]
        (is (= 500 (:status resp)))
        (is (= {:error "InvalidResponse" :message "Internal Server Error"}
               (:body resp))))))

  (testing "a throwing handler maps to a generic 500"
    (binding [*impl* (fn [_] (throw (ex-info "secret" {})))]
      (is (internal-server-error? (execute {:method :get
                                            :url (url "com.example.query")
                                            :query-params {:filter true}})))))

  (testing "error helper status table"
    (are [status error helper] (= {:status status :error error}
                                  (select-keys helper [:status :error]))
      400 "InvalidRequest"         (server/invalid-request "m")
      401 "AuthenticationRequired" (server/auth-required)
      403 "Forbidden"              (server/forbidden)
      404 "XRPCNotSupported"       (server/xrpc-not-supported)
      406 "NotAcceptable"          (server/not-acceptable)
      413 "PayloadTooLarge"        (server/payload-too-large)
      415 "UnsupportedMediaType"   (server/unsupported-media-type)
      429 "RateLimitExceeded"      (server/rate-limit-exceeded)
      500 "InternalServerError"    (server/internal-server-error)
      501 "MethodNotImplemented"   (server/method-not-implemented)
      502 "UpstreamFailure"        (server/upstream-failure)
      503 "NotEnoughResources"     (server/not-enough-resources)
      504 "UpstreamTimeout"        (server/upstream-timeout))))

;; -----------------------------------------------------------------------------
;; Rate limiting
;; -----------------------------------------------------------------------------

(deftest rate-limit-test
  (let [request {:method :get
                 :url (url "com.example.query")
                 :query-params {:filter true}}]
    (binding [*impl* (constantly {:encoding "application/json"
                                  :body {:status "ok"}})]

      (testing "requests beyond the limit get a 429 with RateLimit-* headers"
        (let [limiter (rate-limit/memory {:key-prefix "test"
                                          :duration-ms 60000
                                          :points 2
                                          :calc-key (constantly "k")})
              config {:rate-limit {:routes {"com.example.query" [limiter]}}}
              ok1 (execute-with config request)
              ok2 (execute-with config request)
              limited (execute-with config request)]
          (is (= 200 (:status ok1)))
          (is (= "2" (get-in ok1 [:headers :ratelimit-limit])))
          (is (= "1" (get-in ok1 [:headers :ratelimit-remaining])))
          (is (= "0" (get-in ok2 [:headers :ratelimit-remaining])))
          (is (= 429 (:status limited)))
          (is (= "RateLimitExceeded" (get-in limited [:body :error])))
          (is (= "0" (get-in limited [:headers :ratelimit-remaining])))))

      (testing "global limiters apply to every route"
        (let [limiter (rate-limit/memory {:key-prefix "global"
                                          :duration-ms 60000
                                          :points 1
                                          :calc-key (constantly "k")})
              config {:rate-limit {:global [limiter]}}]
          (is (= 200 (:status (execute-with config request))))
          (is (= 429 (:status (execute-with config request))))))

      (testing "a nil calc-key skips the limiter"
        (let [limiter (rate-limit/memory {:key-prefix "skip"
                                          :duration-ms 60000
                                          :points 1
                                          :calc-key (constantly nil)})
              config {:rate-limit {:global [limiter]}}]
          (is (= 200 (:status (execute-with config request))))
          (is (= 200 (:status (execute-with config request)))))))))

(comment

  (require '[clojure.test :refer [run-tests]])
  (require 'atproto.lexicon
           'atproto.xrpc.server
           'atproto.xrpc.server-test
           :reload)

  (run-tests 'atproto.xrpc.server-test)

  )
