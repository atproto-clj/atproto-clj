(ns atproto.credentials-test
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            [clojure.string :as str]
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.http :as http]
            [atproto.test-support.http :as fake-http]
            [atproto.xrpc.client :as xrpc-client]
            [atproto.credentials :as credentials]))

(def did "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa")

(def did-doc
  {:id did
   :alsoKnownAs ["at://alice.test"]
   :service [{:id "#atproto_pds"
              :type "AtprotoPersonalDataServer"
              :serviceEndpoint "https://pds.test"}]})

(def create-session-response
  (fake-http/json-response {:did did
                            :didDoc did-doc
                            :handle "alice.test"
                            :accessJwt "access-1"
                            :refreshJwt "refresh-1"}))

(defn- bearer
  [request]
  (get-in request [:headers :authorization]))

#?(:clj
   (deftest create-test
     (let [{:keys [handler]} (fake-http/routed
                              [["plc.directory" (fake-http/json-response did-doc)]
                               ["createSession" create-session-response]])]
       (with-redefs [http/handle-request handler]
         (let [session (deref (credentials/create {:identifier did :password "pw"})
                              1000 ::timeout)]
           (is (nil? (:error session)))
           (is (= {:accessJwt "access-1"
                   :refreshJwt "refresh-1"
                   :did did
                   :pds "https://pds.test"
                   :handle "alice.test"}
                  session))
           ;; satisfies the Session protocol via metadata
           (is (map? (xrpc-client/auth-interceptor session))))))))

#?(:clj
   (deftest refresh-end-to-end-test
     ;; an XRPC call rejected with ExpiredToken transparently refreshes via
     ;; com.atproto.server.refreshSession and the retried call succeeds
     (let [{:keys [handler requests]}
           (fake-http/routed
            [["plc.directory" (fake-http/json-response did-doc)]
             ["createSession" create-session-response]
             ["refreshSession" (fn [req]
                                 (if (= "Bearer refresh-1" (bearer req))
                                   ;; refreshSession output has no didDoc
                                   (fake-http/json-response {:did did
                                                             :handle "alice.test"
                                                             :accessJwt "access-2"
                                                             :refreshJwt "refresh-2"})
                                   (fake-http/json-response 401 {:error "InvalidToken"})))]
             [#(= "Bearer access-1" (bearer %))
              (fake-http/json-response 400 {:error "ExpiredToken"})]
             [#(= "Bearer access-2" (bearer %))
              (fake-http/json-response {:ok true})]])]
       (with-redefs [http/handle-request handler]
         (let [session (deref (credentials/create {:identifier did :password "pw"})
                              1000 ::timeout)
               xrpc (xrpc-client/init {:session session})
               resp (deref (xrpc-client/procedure xrpc {:nsid "com.example.ping"
                                                        :body {:n 1}})
                           2000 ::timeout)]
           (is (= {:ok true} resp))
           ;; the refresh request hit refreshSession with the refresh JWT
           (let [refresh-request (first (filter #(str/includes? (:url %) "refreshSession")
                                                @requests))]
             (is (some? refresh-request))
             (is (= :post (:method refresh-request)))
             (is (= "Bearer refresh-1" (bearer refresh-request))))
           ;; the rebuilt session has the new tokens and keeps :pds/:handle
           (let [new-session @(:session xrpc)]
             (is (= "access-2" (:accessJwt new-session)))
             (is (= "refresh-2" (:refreshJwt new-session)))
             (is (= "https://pds.test" (:pds new-session)))
             (is (= "alice.test" (:handle new-session)))
             (is (= did (:did new-session)))
             (is (map? (xrpc-client/auth-interceptor new-session)))))))))

#?(:clj
   (deftest refresh-did-change-rejected-test
     (let [{:keys [handler]}
           (fake-http/routed
            [["plc.directory" (fake-http/json-response did-doc)]
             ["createSession" create-session-response]
             ["refreshSession" (fake-http/json-response {:did "did:plc:bbbbbbbbbbbbbbbbbbbbbbbb"
                                                         :handle "alice.test"
                                                         :accessJwt "access-2"
                                                         :refreshJwt "refresh-2"})]
             ["" (fake-http/json-response 400 {:error "ExpiredToken"})]])]
       (with-redefs [http/handle-request handler]
         (let [session (deref (credentials/create {:identifier did :password "pw"})
                              1000 ::timeout)
               xrpc (xrpc-client/init {:session session})
               resp (deref (xrpc-client/procedure xrpc {:nsid "com.example.ping"
                                                        :body {:n 1}})
                           2000 ::timeout)]
           (is (= "InvalidDID" (:error resp))))))))

#?(:clj
   (deftest logout-test
     (let [{:keys [handler requests]}
           (fake-http/routed
            [["plc.directory" (fake-http/json-response did-doc)]
             ["createSession" create-session-response]
             ["deleteSession" (fake-http/json-response {})]])]
       (with-redefs [http/handle-request handler]
         (let [session (deref (credentials/create {:identifier did :password "pw"})
                              1000 ::timeout)
               resp (deref (credentials/logout session) 1000 ::timeout)]
           (is (= {} resp))
           (let [delete-request (first (filter #(str/includes? (:url %) "deleteSession")
                                               @requests))]
             (is (some? delete-request))
             (is (= "Bearer refresh-1" (bearer delete-request)))))))))

#?(:clj
   (deftest logout-server-error-still-resolves-test
     (let [{:keys [handler]}
           (fake-http/routed
            [["plc.directory" (fake-http/json-response did-doc)]
             ["createSession" create-session-response]
             ["deleteSession" (fake-http/json-response 400 {:error "InvalidRequest"})]])]
       (with-redefs [http/handle-request handler]
         (let [session (deref (credentials/create {:identifier did :password "pw"})
                              1000 ::timeout)
               resp (deref (credentials/logout session) 1000 ::timeout)]
           (is (not= ::timeout resp))
           (is (= "InvalidRequest" (:error resp))))))))
