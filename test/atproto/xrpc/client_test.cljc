(ns atproto.xrpc.client-test
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.http :as http]
            [atproto.runtime.json :as json]
            [atproto.test-support.http :as fake-http]
            [atproto.xrpc.client :as client]))

(deftest invalid-token-response?-test
  (are [expected response] (= expected (client/invalid-token-response? response))
    true  {:status 400
           :headers {:content-type "application/json"}
           :body {:error "ExpiredToken"}}
    ;; content-type prefix match (charset suffix)
    true  {:status 400
           :headers {:content-type "application/json; charset=utf-8"}
           :body {:error "ExpiredToken"}}
    ;; 401 with no WWW-Authenticate header (bearer-auth convention)
    true  {:status 401 :headers {}}
    ;; RFC 6750/9449 invalid_token challenges
    true  {:status 401
           :headers {:www-authenticate "DPoP error=\"invalid_token\", error_description=\"x\""}}
    true  {:status 401
           :headers {:www-authenticate "Bearer error=\"invalid_token\""}}
    ;; the DPoP interceptor owns the nonce dance
    false {:status 401
           :headers {:www-authenticate "DPoP error=\"use_dpop_nonce\""}}
    false {:status 200
           :headers {:content-type "application/json"}
           :body {:error "ExpiredToken"}}
    false {:status 400
           :headers {:content-type "application/json"}
           :body {:error "InvalidRequest"}}
    false {:status 400
           :headers {:content-type "text/plain"}
           :body "ExpiredToken"}
    false {:error "HTTPClientError" :status 401 :headers {}}))

(defn- stub-session
  "A Session implemented via metadata: authenticates with `Bearer <token>`
  and delegates refresh-token to refresh-fn."
  [token refresh-fn]
  (with-meta
    {:token token :pds "https://pds.test"}
    {`client/auth-interceptor
     (fn [session]
       {::i/name ::stub-auth
        ::i/enter #(assoc-in % [::i/request :headers :authorization]
                             (str "Bearer " (:token session)))})
     `client/refresh-token
     (fn [session cb] (refresh-fn session cb))}))

(def ^:private expired-response
  (fake-http/json-response 400 {:error "ExpiredToken"}))

(defn- bearer
  [request]
  (get-in request [:headers :authorization]))

#?(:clj
   (deftest refresh-and-retry-test
     (let [refresh-calls (atom 0)
           session (stub-session "old"
                                 (fn [_ cb]
                                   (swap! refresh-calls inc)
                                   (cb (stub-session "new" (fn [_ cb] (cb {:error "TokenRefreshError"}))))))
           {:keys [handler requests]} (fake-http/routed
                                       [[#(= "Bearer old" (bearer %)) expired-response]
                                        [#(= "Bearer new" (bearer %)) (fake-http/json-response {:result "ok"})]])
           xrpc (client/init {:session session})]
       (with-redefs [http/handle-request handler]
         (let [resp (deref (client/procedure xrpc {:nsid "com.example.proc"
                                                   :body {:hello "world"}})
                           1000 ::timeout)]
           (is (= {:result "ok"} resp))
           (is (= 1 @refresh-calls))
           (is (= 2 (count @requests)))
           (let [[r1 r2] @requests]
             (is (= "Bearer old" (bearer r1)))
             ;; the retried request carried the NEW token...
             (is (= "Bearer new" (bearer r2)))
             ;; ...and a singly-encoded JSON body, byte-identical to the original
             (is (= {:hello "world"} (json/read-str (:body r1))))
             (is (= (:body r1) (:body r2)))))
         ;; the client's session atom holds the new session
         (is (= "new" (:token @(:session xrpc))))))))

#?(:clj
   (deftest refresh-failure-delivers-full-error-map-test
     (let [session (stub-session "old"
                                 (fn [_ cb] (cb {:error "TokenRefreshError" :message "x"})))
           {:keys [handler]} (fake-http/scripted [expired-response])
           xrpc (client/init {:session session})]
       (with-redefs [http/handle-request handler]
         (let [resp (deref (client/procedure xrpc {:nsid "com.example.proc" :body {:a 1}})
                           1000 ::timeout)]
           (is (= {:error "TokenRefreshError" :message "x"} resp))
           ;; a transient refresh failure keeps the session in place
           (is (= "old" (:token @(:session xrpc)))))))))

#?(:clj
   (deftest definitive-refresh-failure-drops-session-test
     ;; an error tagged ::client/session-expired? drops the session: the
     ;; error is delivered and subsequent requests go out unauthenticated
     (let [session (stub-session "old"
                                 (fn [_ cb] (cb {:error "TokenRefreshError"
                                                 ::client/session-expired? true})))
           {:keys [handler requests]} (fake-http/scripted
                                       [expired-response
                                        (fake-http/json-response {:anon true})])
           xrpc (client/init {:session session})]
       (with-redefs [http/handle-request handler]
         (let [resp (deref (client/procedure xrpc {:nsid "com.example.proc" :body {:a 1}})
                           1000 ::timeout)]
           (is (= "TokenRefreshError" (:error resp)))
           (is (nil? @(:session xrpc)))
           (let [resp2 (deref (client/procedure xrpc {:nsid "com.example.proc" :body {:a 1}})
                              1000 ::timeout)]
             (is (= {:anon true} resp2))
             (is (nil? (bearer (second @requests))))))))))

#?(:clj
   (deftest refresh-token-throwing-delivers-error-test
     (let [session (stub-session "old"
                                 (fn [_ _] (throw (ex-info "boom" {}))))
           {:keys [handler]} (fake-http/scripted [expired-response])
           xrpc (client/init {:session session})]
       (with-redefs [http/handle-request handler]
         (let [resp (deref (client/procedure xrpc {:nsid "com.example.proc" :body {:a 1}})
                           1000 ::timeout)]
           (is (= "TokenRefreshError" (:error resp))))))))

#?(:clj
   (deftest retry-at-most-once-test
     (let [refresh-calls (atom 0)
           make-session (fn make-session [token]
                          (stub-session token
                                        (fn [_ cb]
                                          (swap! refresh-calls inc)
                                          (cb (make-session "refreshed")))))
           {:keys [handler requests]} (fake-http/scripted (repeat 5 expired-response))
           xrpc (client/init {:session (make-session "initial")})]
       (with-redefs [http/handle-request handler]
         (let [resp (deref (client/procedure xrpc {:nsid "com.example.proc" :body {:a 1}})
                           1000 ::timeout)]
           ;; the second invalid-token response is returned as-is
           (is (= "ExpiredToken" (:error resp)))
           (is (= 1 @refresh-calls))
           (is (= 2 (count @requests))))))))

#?(:clj
   (deftest refresh-session-not-recursed-test
     ;; a session marked :refresh? never triggers another refresh
     (let [refresh-calls (atom 0)
           session (assoc (stub-session "r" (fn [_ cb]
                                              (swap! refresh-calls inc)
                                              (cb {:error "TokenRefreshError"})))
                          :refresh? true)
           {:keys [handler]} (fake-http/scripted [expired-response])
           xrpc (client/init {:session session})]
       (with-redefs [http/handle-request handler]
         (let [resp (deref (client/procedure xrpc {:nsid "com.example.proc" :body {:a 1}})
                           1000 ::timeout)]
           (is (= "ExpiredToken" (:error resp)))
           (is (= 0 @refresh-calls)))))))

#?(:clj
   (deftest error-taxonomy-test
     ;; a JSON error body is preserved verbatim and enriched
     (let [{:keys [handler]} (fake-http/scripted
                              [(fake-http/json-response 400 {:error "InvalidSwap"
                                                             :message "Commit was at bafy..."})])
           xrpc (client/init {:service "https://pds.test"})]
       (with-redefs [http/handle-request handler]
         (let [resp (deref (client/procedure xrpc {:nsid "com.example.proc" :body {:a 1}})
                           1000 ::timeout)]
           (is (= "InvalidSwap" (:error resp)))
           (is (= "Commit was at bafy..." (:message resp)))
           (is (= 400 (:status resp)))
           (is (false? (:retryable? resp)))
           (is (map? (:headers resp)))
           (is (some? (:http-response resp))))))
     ;; non-JSON error bodies derive the name from the status; no HTTP_<status>
     (let [{:keys [handler]} (fake-http/scripted
                              [{:status 502
                                :headers {:content-type "text/html"}
                                :body "<html>Bad Gateway</html>"}])
           xrpc (client/init {:service "https://pds.test"})]
       (with-redefs [http/handle-request handler]
         (let [resp (deref (client/query xrpc {:nsid "com.example.query"})
                           1000 ::timeout)]
           (is (= "UpstreamFailure" (:error resp)))
           (is (= 502 (:status resp)))
           (is (true? (:retryable? resp))))))
     ;; transport failures become retryable network errors
     (let [{:keys [handler]} (fake-http/scripted
                              [{:error "HTTPClientError" :message "connection refused"}])
           xrpc (client/init {:service "https://pds.test"})]
       (with-redefs [http/handle-request handler]
         (let [resp (deref (client/query xrpc {:nsid "com.example.query"})
                           1000 ::timeout)]
           (is (= "HTTPClientError" (:error resp)))
           (is (true? (:retryable? resp)))
           (is (nil? (:status resp))))))))

#?(:clj
   (deftest success-headers-metadata-test
     (let [{:keys [handler]} (fake-http/scripted
                              [(fake-http/json-response 200
                                                        {:ratelimit-remaining "10"}
                                                        {:result "ok"})])
           xrpc (client/init {:service "https://pds.test"})]
       (with-redefs [http/handle-request handler]
         (let [resp (deref (client/query xrpc {:nsid "com.example.query"})
                           1000 ::timeout)]
           (is (= {:result "ok"} resp))
           (is (= "10" (:ratelimit-remaining (client/response-headers resp))))
           (is (= "application/json" (:content-type (client/response-headers resp)))))))))

#?(:clj
   (deftest request-validation-error-map-test
     ;; client-side validation failures return an error map instead of throwing
     (let [{:keys [handler requests]} (fake-http/scripted [])
           xrpc (client/init {:service "https://pds.test"})]
       (with-redefs [http/handle-request handler]
         (let [resp (deref (client/query xrpc {:nsid "com.example.query"
                                               :params "not-a-map"})
                           1000 ::timeout)]
           (is (= "InvalidRequest" (:error resp)))
           (is (false? (:retryable? resp)))
           (is (some? (:explain-data resp)))
           ;; the request never reached the wire
           (is (empty? @requests)))))))

#?(:clj
   (deftest header-merge-test
     ;; precedence: client defaults < per-request < computed content-type
     (let [{:keys [handler requests]} (fake-http/scripted
                                       [(fake-http/json-response {:ok true})])
           xrpc (client/init {:service "https://pds.test"
                              :headers {:x-default "client"
                                        :x-shared "client"}})]
       (with-redefs [http/handle-request handler]
         (deref (client/procedure xrpc {:nsid "com.example.proc"
                                        :body {:a 1}
                                        :headers {:x-shared "request"
                                                  :x-request "request"}})
                1000 ::timeout)
         (let [headers (:headers (first @requests))]
           (is (= "client" (:x-default headers)))
           (is (= "request" (:x-shared headers)))
           (is (= "request" (:x-request headers)))
           (is (= "application/json" (:content-type headers))))))
     ;; supplying :content-type alongside a body is an error
     (let [{:keys [handler requests]} (fake-http/scripted [])
           xrpc (client/init {:service "https://pds.test"})]
       (with-redefs [http/handle-request handler]
         (let [resp (deref (client/procedure xrpc {:nsid "com.example.proc"
                                                   :body {:a 1}
                                                   :headers {:content-type "text/plain"}})
                           1000 ::timeout)]
           (is (= "InvalidRequest" (:error resp)))
           (is (empty? @requests)))))))

#?(:clj
   (deftest service-proxy-test
     ;; client-level :service-proxy emits atproto-proxy
     (let [{:keys [handler requests]} (fake-http/scripted
                                       (repeat 2 (fake-http/json-response {:ok true})))
           xrpc (client/init {:service "https://pds.test"
                              :service-proxy "did:web:api.bsky.app#bsky_appview"})]
       (with-redefs [http/handle-request handler]
         (deref (client/query xrpc {:nsid "com.example.query"}) 1000 ::timeout)
         (is (= "did:web:api.bsky.app#bsky_appview"
                (:atproto-proxy (:headers (first @requests)))))
         ;; a per-request atproto-proxy header wins
         (deref (client/query xrpc {:nsid "com.example.query"
                                    :headers {:atproto-proxy "did:web:other.test#other"}})
                1000 ::timeout)
         (is (= "did:web:other.test#other"
                (:atproto-proxy (:headers (second @requests)))))))
     ;; with-service-proxy returns a routed copy of the client
     (let [{:keys [handler requests]} (fake-http/scripted
                                       [(fake-http/json-response {:ok true})])
           xrpc (-> (client/init {:service "https://pds.test"})
                    (client/with-service-proxy "did:web:api.bsky.app#bsky_appview"))]
       (with-redefs [http/handle-request handler]
         (deref (client/query xrpc {:nsid "com.example.query"}) 1000 ::timeout)
         (is (= "did:web:api.bsky.app#bsky_appview"
                (:atproto-proxy (:headers (first @requests)))))))
     ;; malformed proxy strings are rejected
     (is (thrown? #?(:clj Exception :cljs js/Error)
                  (client/init {:service "https://pds.test"
                                :service-proxy "not-a-proxy"})))
     (is (thrown? #?(:clj Exception :cljs js/Error)
                  (client/with-service-proxy (client/init {:service "https://pds.test"})
                    "did:web:api.bsky.app")))))

#?(:clj
   (deftest labelers-test
     (let [{:keys [handler requests]} (fake-http/scripted
                                       (repeat 2 (fake-http/json-response {:ok true})))
           xrpc (-> (client/init {:service "https://pds.test"})
                    (client/with-labelers [{:did "did:plc:label1" :redact? true}
                                           "did:plc:label2"]))]
       (with-redefs [http/handle-request handler]
         (deref (client/query xrpc {:nsid "com.example.query"}) 1000 ::timeout)
         (is (= "did:plc:label1;redact, did:plc:label2"
                (:atproto-accept-labelers (:headers (first @requests)))))
         ;; a per-request value is merged after the client's labelers
         (deref (client/query xrpc {:nsid "com.example.query"
                                    :headers {:atproto-accept-labelers "did:plc:label3"}})
                1000 ::timeout)
         (is (= "did:plc:label1;redact, did:plc:label2, did:plc:label3"
                (:atproto-accept-labelers (:headers (second @requests)))))))))

#?(:clj
   (deftest single-flight-refresh-test
     (let [refresh-calls (atom 0)
           release (promise)
           session (stub-session "old"
                                 (fn [_ cb]
                                   (swap! refresh-calls inc)
                                   (future
                                     @release
                                     (cb (stub-session "new" (fn [_ cb] (cb {:error "TokenRefreshError"})))))))
           {:keys [handler]} (fake-http/routed
                              [[#(= "Bearer old" (bearer %)) expired-response]
                               [#(= "Bearer new" (bearer %)) (fake-http/json-response {:ok true})]])
           xrpc (client/init {:session session})]
       (with-redefs [http/handle-request handler]
         ;; all 32 requests hit the expired response and queue on one refresh
         (let [results (doall (repeatedly 32 #(client/procedure xrpc {:nsid "com.example.proc"
                                                                      :body {:n 1}})))]
           (deliver release true)
           (doseq [result results]
             (is (= {:ok true} (deref result 2000 ::timeout))))
           (is (= 1 @refresh-calls)))))))
