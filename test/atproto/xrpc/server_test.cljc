(ns atproto.xrpc.server-test
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            [clojure.spec.alpha :as s]
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.http :as http]
            [atproto.runtime.json :as json]
            [atproto.lexicon :as lexicon]
            [atproto.xrpc.server :as server]))

(def interceptor
  (server/interceptor
   {:lexicon (lexicon/load-schemas
              [{:lexicon 1
                :id "com.example.query"
                :defs {:main {:type "query"
                              :parameters {:type "params"
                                           :required [:filter]
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
                                                :properties {:isDone {:type "boolean"}}}}}}}

               {:lexicon 1
                :id "com.example.subscription"
                :defs {:event {:type "object"
                               :properties {:type {:type "string"}
                                            :instant {:type "string"
                                                      :format "datetime"}}}
                       :main {:type "subscription"
                              :parameters {:type "params"
                                           :properties {:flag {:type "boolean"}}}
                              :message {:schema {:type "union"
                                                 :refs ["com.example.subcription#event"]}}}}}])}))

(defn execute
  [request]
  @(i/execute {::i/request request
               ::i/queue [interceptor]
               :validate-response? true}))

(def ^:dynamic *impl* identity)

(defmethod server/handle :com.example.query
  [xrpc-request]
  (*impl* xrpc-request))

(defmethod server/handle :com.example.procedure
  [xrpc-request]
  (*impl* xrpc-request))

(defmethod server/handle :com.example.subscription
  [xrpc-request]
  (*impl* xrpc-request))

(defn- url
  [nsid]
  (str "http://example.com/xrpc/" nsid))

(defn invalid-request?
  [{:keys [status headers body] :as resp}]
  (and (http/client-error? status)
       (= "application/json" (:content-type headers))
       (= "InvalidRequest" (:error body))))

(defn invalid-response?
  [{:keys [status headers body]}]
  (and (= status 500)
       (= "application/json" (:content-type headers))
       (= "InvalidResponse" (:error body))))

(deftest test-xrpc-server

  (testing "request validation"
    (are [http-request] (invalid-request? (execute http-request))
      {:method :get  :url "http://example.com/foo/xrpc/com.example.query"}
      {:method :get  :url (url "com.example")}
      {:method :post :url (url "com.example.query")}
      {:method :get  :url (url "com.example.unknown")}
      {:method :get  :url (url "com.example.query")}
      {:method :get  :url (url "com.example.query") :query-params {:filter "foo"}}
      {:method :post :url (url "com.example.procedure") :input {:encoding "text/plain" :body "test"}}))

  (testing "xrpc request building"
    (binding [*impl* (fn [{:keys [op params]}]
                       (is (= op :com.example.query))
                       (is (true? (:filter params)))
                       (server/json-output {:status "ok"}))]
      (execute {:method :get
                :url (url "com.example.query")
                :query-params {:filter true}})))

  (testing "response validation"
    (binding [*impl* (constantly (server/json-output {:status "foo"}))]
      (is
       (invalid-response?
        (execute {:method :get
                  :url (url "com.example.query")
                  :query-params {:filter true}}))))

    (binding [*impl* (constantly (server/json-output {:status "ok"}))]
      (let [{:keys [status headers body] :as resp} (execute {:method :get
                                                             :url (url "com.example.query")
                                                             :query-params {:filter true}})]
        (tap> resp)
        (is (= status 200))
        (is (= "ok" (:status body)))))))

(comment

  (require '[clojure.test :refer [run-tests]])
  (require 'atproto.lexicon
           'atproto.xrpc.server
           'atproto.xrpc.server-test
           :reload)

  (run-tests 'atproto.xrpc.server-test)

  )
