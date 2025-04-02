(ns atproto.xrpc.server-test
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            [clojure.spec.alpha :as s]
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.http :as http]
            [atproto.runtime.json :as json]
            [atproto.lexicon :as lexicon]
            [atproto.xrpc.server :as server]))

;; TODO:
;; - subscription?

(def server
  (server/create
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
  [xrpc-request]
  (*impl* xrpc-request))

(defmethod server/handle "com.example.procedure"
  [xrpc-request]
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
      {:method :get  :url (url "com.example.unknown")}
      {:method :get  :url (url "com.example.query")}
      {:method :get  :url (url "com.example.query") :query-params {:filter "foo"}}
      {:method :post :url (url "com.example.procedure") :input {:encoding "text/plain" :body "test"}}))

  (testing "response validation"
    (binding [*impl* (constantly {:encoding "application/json"
                                  :body {:status "foo"}})]
      (is
       (invalid-response? (execute {:method :get
                                    :url (url "com.example.query")
                                    :query-params {:filter true}}))))))

(comment

  (require '[clojure.test :refer [run-tests]])
  (require 'atproto.lexicon
           'atproto.xrpc.server
           'atproto.xrpc.server-test
           :reload)

  (run-tests 'atproto.xrpc.server-test)

  )
