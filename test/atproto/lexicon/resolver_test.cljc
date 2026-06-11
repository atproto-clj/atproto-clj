(ns atproto.lexicon.resolver-test
  "Resolver tests against stubbed DNS and HTTP layers.

  No live network: DNS is stubbed by redefining atproto.runtime.dns/interceptor
  (mirroring the reference implementation's mocked DNS layer in
  packages/lexicon-resolver/tests/lexicon.test.ts) and HTTP by redefining
  atproto.runtime.http/handle-request with the routed fake."
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            [clojure.string :as str]
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.http :as http]
            [atproto.runtime.dns :as dns]
            [atproto.test-support.http :as fake-http]
            [atproto.lexicon.resolver :as resolver]))

(def nsid "com.example.test.record")
(def authority-did "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa")
(def pds-url "https://pds.example.com")

(def lexicon-doc
  {:lexicon 1
   :id nsid
   :defs {:main {:type "record"
                 :key "tid"
                 :record {:type "object"
                          :properties {:text {:type "string"}}}}}})

(defn stub-dns
  "A DNS interceptor stub returning canned responses by hostname."
  [responses-by-hostname]
  {::i/name ::stub-dns
   ::i/enter (fn [{:keys [::i/request] :as ctx}]
               (assoc ctx
                      ::i/response
                      (get responses-by-hostname
                           (:hostname request)
                           {:error "DNS name not found"})))})

(def did-doc
  {:id authority-did
   :service [{:id "#atproto_pds"
              :type "AtprotoPersonalDataServer"
              :serviceEndpoint pds-url}]})

(defn get-record-response
  [value]
  (fake-http/json-response
   {:uri (str "at://" authority-did "/" resolver/lexicon-record-collection "/" nsid)
    :cid "bafyreidfayvfuwqa7qlnopdjiqrxzs6blmoeu4rujcjtnci5beludirz2a"
    :value value}))

(defn routes
  [& {:keys [record-response]}]
  [["plc.directory" (fake-http/json-response did-doc)]
   ["/xrpc/com.atproto.repo.getRecord"
    (or record-response (get-record-response lexicon-doc))]])

(defn resolve-with-stubs
  [{:keys [dns-responses http-routes opts]}]
  (let [{:keys [handler requests]} (fake-http/routed
                                    (or http-routes (routes)))]
    (with-redefs [dns/interceptor (stub-dns
                                   (or dns-responses
                                       {"_lexicon.test.example.com"
                                        {:values [(str "did=" authority-did)]}}))
                  http/handle-request handler]
      (let [result #?(:clj (deref (apply resolver/resolve-nsid nsid
                                         (mapcat identity opts))
                                  1000 {:error "Timeout"})
                      :cljs nil)]
        {:result result
         :requests @requests}))))

(deftest happy-path-test
  (let [{:keys [result requests]} (resolve-with-stubs {})]
    (is (nil? (:error result)))
    (is (= nsid (:nsid result)))
    (is (= authority-did (:did result)))
    (is (= (str "at://" authority-did "/" resolver/lexicon-record-collection "/" nsid)
           (:uri result)))
    (is (string? (:cid result)))
    (is (= lexicon-doc (:lexicon result)))
    ;; one DID doc fetch + one getRecord against the declared PDS
    (is (= 2 (count requests)))
    (let [get-record (last requests)]
      (is (str/starts-with? (:url get-record) pds-url))
      (is (= {:repo authority-did
              :collection resolver/lexicon-record-collection
              :rkey nsid}
             (:query-params get-record))))))

(deftest chunked-txt-record-test
  (let [{:keys [result]} (resolve-with-stubs
                          {:dns-responses
                           {"_lexicon.test.example.com"
                            {:values [(str "\"did=did:plc:aaaaaaaaaa\" \"aaaaaaaaaaaaaa\"")]}}})]
    (is (nil? (:error result)))
    (is (= authority-did (:did result)))))

(deftest invalid-nsid-test
  (let [result #?(:clj @(resolver/resolve-nsid "not-an-nsid") :cljs nil)]
    (is (= "InvalidNsid" (:error result)))))

(deftest no-did-record-test
  (let [{:keys [result]} (resolve-with-stubs
                          {:dns-responses
                           {"_lexicon.test.example.com" {:values ["foo=bar"]}}})]
    (is (= "LexiconAuthorityNotFound" (:error result)))
    (is (= nsid (:nsid result)))))

(deftest dns-failure-test
  (let [{:keys [result]} (resolve-with-stubs {:dns-responses {}})]
    (is (= "LexiconAuthorityNotFound" (:error result)))))

(deftest multiple-did-records-test
  (let [{:keys [result]} (resolve-with-stubs
                          {:dns-responses
                           {"_lexicon.test.example.com"
                            {:values [(str "did=" authority-did)
                                      "did=did:plc:bbbbbbbbbbbbbbbbbbbbbbbb"]}}})]
    (is (= "LexiconAuthorityNotFound" (:error result)))))

(deftest invalid-did-in-txt-test
  (let [{:keys [result]} (resolve-with-stubs
                          {:dns-responses
                           {"_lexicon.test.example.com"
                            {:values ["did=not-a-did"]}}})]
    (is (= "LexiconAuthorityNotFound" (:error result)))))

(deftest did-doc-missing-pds-test
  (let [{:keys [result]} (resolve-with-stubs
                          {:http-routes
                           [["plc.directory" (fake-http/json-response
                                              {:id authority-did :service []})]]})]
    (is (= "LexiconResolutionError" (:error result)))))

(deftest did-resolution-failure-test
  (let [{:keys [result]} (resolve-with-stubs
                          {:http-routes
                           [["plc.directory" (fake-http/json-response 404 {})]]})]
    (is (= "LexiconResolutionError" (:error result)))))

(deftest record-fetch-failure-test
  (let [{:keys [result]} (resolve-with-stubs
                          {:http-routes
                           (routes :record-response
                                   (fake-http/json-response
                                    400 {:error "RecordNotFound"
                                         :message "Could not locate record"}))})]
    (is (= "LexiconResolutionError" (:error result)))))

(deftest invalid-document-test
  (let [{:keys [result]} (resolve-with-stubs
                          {:http-routes
                           (routes :record-response
                                   (get-record-response {:id nsid}))})]
    (is (= "InvalidLexiconDocument" (:error result)))
    (is (some? (:explain-data result)))))

(deftest wrong-record-type-test
  (let [{:keys [result]} (resolve-with-stubs
                          {:http-routes
                           (routes :record-response
                                   (get-record-response
                                    (assoc lexicon-doc :$type "com.example.other")))})]
    (is (= "InvalidLexiconDocument" (:error result)))))

(deftest nsid-mismatch-test
  (let [{:keys [result]} (resolve-with-stubs
                          {:http-routes
                           (routes :record-response
                                   (get-record-response
                                    (assoc lexicon-doc :id "com.example.test.other")))})]
    (is (= "LexiconNsidMismatch" (:error result)))
    (is (= "com.example.test.other" (:id result)))))

(deftest did-authority-override-test
  (let [{:keys [result requests]} (resolve-with-stubs
                                   {:dns-responses {}
                                    :opts {:did-authority authority-did}})]
    (is (nil? (:error result)))
    (is (= lexicon-doc (:lexicon result)))
    ;; DNS skipped: only DID doc fetch + getRecord
    (is (= 2 (count requests)))))

(deftest cache-test
  (let [cache (resolver/memory-cache)
        first-pass (resolve-with-stubs {:opts {:cache cache}})]
    (is (nil? (:error (:result first-pass))))
    (is (= 2 (count (:requests first-pass))))
    ;; cache hit: no network at all
    (let [second-pass (resolve-with-stubs {:dns-responses {}
                                           :http-routes []
                                           :opts {:cache cache}})]
      (is (= (:result first-pass) (:result second-pass)))
      (is (= 0 (count (:requests second-pass)))))
    ;; :force-refresh bypasses the cache read and repopulates
    (let [third-pass (resolve-with-stubs {:opts {:cache cache
                                                 :force-refresh true}})]
      (is (nil? (:error (:result third-pass))))
      (is (= 2 (count (:requests third-pass)))))))

(deftest expired-cache-test
  (let [cache (resolver/memory-cache :ttl-ms 0)
        first-pass (resolve-with-stubs {:opts {:cache cache}})]
    (is (nil? (:error (:result first-pass))))
    ;; entry expired immediately: resolves over the network again
    (let [second-pass (resolve-with-stubs {:opts {:cache cache}})]
      (is (nil? (:error (:result second-pass))))
      (is (= 2 (count (:requests second-pass)))))))

(deftest errors-not-cached-test
  (let [cache (resolver/memory-cache)
        first-pass (resolve-with-stubs {:dns-responses {}
                                        :opts {:cache cache}})]
    (is (= "LexiconAuthorityNotFound" (:error (:result first-pass))))
    ;; the failure was not cached; a later attempt resolves normally
    (let [second-pass (resolve-with-stubs {:opts {:cache cache}})]
      (is (nil? (:error (:result second-pass)))))))
