(ns atproto.xrpc.client-response-test
  "Client-side response validation (:validate-responses?) tests."
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            [atproto.runtime.http :as http]
            [atproto.test-support.http :as fake-http]
            [atproto.lexicon :as lexicon]
            [atproto.xrpc.client :as client]))

(def nsid "com.example.respQuery")

(lexicon/register-specs!
 (lexicon/lexicon
  [{:lexicon 1
    :id nsid
    :defs {:main {:type "query"
                  :output {:encoding "application/json"
                           :schema {:type "object"
                                    :required ["status"]
                                    :properties {:status {:type "string"}
                                                 :createdAt {:type "string"
                                                             :format "datetime"}}}}}}}]))

(defn- run-query
  [{:keys [config request responses]}]
  (let [{:keys [handler]} (fake-http/scripted responses)]
    (with-redefs [http/handle-request handler]
      #?(:clj (deref (client/query (client/init (merge {:service "https://pds.example.com"}
                                                       config))
                                   (or request {:nsid nsid}))
                     1000 {:error "Timeout"})
         :cljs nil))))

(deftest valid-response-test
  (let [resp (run-query {:config {:validate-responses? true}
                         :responses [(fake-http/json-response {:status "ok"})]})]
    (is (= {:status "ok"} resp))))

(deftest invalid-response-test
  (let [resp (run-query {:config {:validate-responses? true}
                         :responses [(fake-http/json-response {:other "thing"})]})]
    (is (= "InvalidResponse" (:error resp)))
    (is (string? (:message resp)))
    (is (some? (:explain-data resp)))))

(deftest lenient-validation-test
  ;; responses are validated leniently: an offset-less datetime is accepted
  (let [resp (run-query {:config {:validate-responses? true}
                         :responses [(fake-http/json-response
                                      {:status "ok"
                                       :createdAt "1985-04-12T23:20:50"})]})]
    (is (nil? (:error resp)))
    (is (= "ok" (:status resp))))
  ;; but a non-datetime value still fails
  (let [resp (run-query {:config {:validate-responses? true}
                         :responses [(fake-http/json-response
                                      {:status "ok"
                                       :createdAt "not-a-datetime"})]})]
    (is (= "InvalidResponse" (:error resp)))))

(deftest error-response-skips-validation-test
  (let [resp (run-query {:config {:validate-responses? true}
                         :responses [(fake-http/json-response
                                      400 {:error "SomeError"
                                           :message "it broke"})]})]
    (is (= "SomeError" (:error resp)))
    (is (= "it broke" (:message resp)))))

(deftest disabled-by-default-test
  ;; without :validate-responses?, a schema-invalid body passes through
  (let [resp (run-query {:responses [(fake-http/json-response {:other "thing"})]})]
    (is (= {:other "thing"} resp))))

(deftest unknown-nsid-passes-through-test
  (let [resp (run-query {:config {:validate-responses? true}
                         :request {:nsid "com.example.unknownQuery"}
                         :responses [(fake-http/json-response {:anything "goes"})]})]
    (is (= {:anything "goes"} resp))))
