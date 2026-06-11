(ns atproto.identity-test
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            #?(:clj [clojure.java.io :as io])
            [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.http :as http]
            [atproto.runtime.dns :as dns]
            [atproto.runtime.json :as json]
            [atproto.test-support.http :as fake-http]
            [atproto.identity :as identity]))

(deftest test-valid-atproto-did
  (are [did] (s/valid? ::identity/did did)
    "did:plc:l3rouwludahu3ui3bt66mfvj"
    "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa"

    "did:web:example.com"
    "did:web:sub.example.com"
    "did:web:localhost%3A8080"))

(deftest test-invalid-atproto-did
  (are [did] (not (s/valid? ::identity/did did))
    "did:plc:foo"                          ; too short
    "did:plc:toolongtoolongtoolongto"      ; too short
    "did:plc:toolongtoolongtoolongtool"    ; too long
    "did:plc:l3rouwludahu3ui3bt66mfv1"     ; non base-32 char
    "did:plc:l3rouwludahu3ui3bt66mfv8"     ; non base-32 char
    "did:plc:l3rouwludahu3ui3bt66mfvA"     ; non base-32 char
    "did:plc:l3rouwludahu3ui3bt66mfvZ"     ; non base-32 char

    "did:web:foo@example.com"              ; unallowed character
    "did:web::example.com"                 ; cannot start with colon
    "did:web:example.com:"                 ; cannot end with colon
    "did:web:example.com:path:to:resource" ; no path allowed
    "did:web:example.com%3A8080"           ; no port allowed outside localhost
    ))

(deftest web-did-url-mapping-test
  (let [web-did->url {"did:web:example.com"      "https://example.com/"
                      "did:web:sub.example.com"  "https://sub.example.com/"
                      "did:web:localhost%3A8080" "http://localhost:8080/"}]
    (doseq [[web-did url] web-did->url]
      (is (= url (identity/web-did->url web-did)) "The URL is correctly generated from the Web DID.")
      (is (= web-did (identity/url->web-did url)) "The Web DID is correctly generated from the URL."))))

;; -----------------------------------------------------------------------------
;; DID document validation & accessors
;; -----------------------------------------------------------------------------

#?(:clj
   (defn- fixture
     [path]
     (json/read-str (slurp (io/resource (str "atproto/identity/fixtures/" path))))))

(def fixture-did "did:plc:yk4dd2qkboz2yv6tpubpc6co")
(def fixture-did-key "did:key:zQ3shXjHeiBuRCKmM36cuYnm7YEMzhGnCmCyW92sRJ9pribSF")

#?(:clj
   (deftest validate-did-doc-test
     (testing "bad document"
       (is (= "PoorlyFormattedDidDocument"
              (:error (identity/validate-did-doc fixture-did (fixture "did-doc-bad.json"))))))
     (testing "mismatched id"
       (is (= "PoorlyFormattedDidDocument"
              (:error (identity/validate-did-doc "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa"
                                                 (fixture "did-doc-legacy-key.json"))))))
     (testing "valid documents pass through"
       (doseq [f ["did-doc-legacy-key.json" "did-doc-multikey.json"]]
         (let [doc (fixture f)]
           (is (= doc (identity/validate-did-doc fixture-did doc))))))))

#?(:clj
   (deftest did-doc-extraction-test
     ;; expectations from packages/identity/tests/did-document.test.ts
     (doseq [f ["did-doc-legacy-key.json" "did-doc-multikey.json"]]
       (let [doc (fixture f)]
         (testing f
           (is (= fixture-did (:id doc)))
           (is (= "dholms.xyz" (first (identity/did-doc-handles doc))))
           (is (= "https://bsky.social" (identity/did-doc-pds doc)))
           (is (= fixture-did-key (identity/did-doc-signing-did-key doc))))))
     (testing "signing key material"
       (is (= {:type "Multikey"
               :public-key-multibase "zQ3shXjHeiBuRCKmM36cuYnm7YEMzhGnCmCyW92sRJ9pribSF"}
              (identity/did-doc-signing-key (fixture "did-doc-multikey.json")))))))

(deftest did-doc-strict-id-matching-test
  (let [base {:id "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa"}
        service (fn [id] {:service [{:id id
                                     :type "AtprotoPersonalDataServer"
                                     :serviceEndpoint "https://pds.test"}]})]
    (testing "exact fragment or doc-id-prefixed ids match"
      (is (= "https://pds.test" (identity/did-doc-pds (merge base (service "#atproto_pds")))))
      (is (= "https://pds.test"
             (identity/did-doc-pds
              (merge base (service "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa#atproto_pds"))))))
    (testing "suffix matches on another DID's id are rejected"
      (is (nil? (identity/did-doc-pds
                 (merge base (service "did:plc:bbbbbbbbbbbbbbbbbbbbbbbb#atproto_pds"))))))
    (testing "non-http(s) endpoints are rejected"
      (is (nil? (identity/did-doc-pds
                 (merge base {:service [{:id "#atproto_pds"
                                         :type "AtprotoPersonalDataServer"
                                         :serviceEndpoint "ftp://pds.test"}]})))))))

;; -----------------------------------------------------------------------------
;; did:web resolution
;; -----------------------------------------------------------------------------

#?(:clj
   (deftest resolve-did-web-test
     (testing "fetches and validates /.well-known/did.json"
       (let [doc {:id "did:web:example.com"}
             {:keys [handler requests]} (fake-http/routed
                                         [["example.com/.well-known/did.json"
                                           (fake-http/json-response doc)]])]
         (with-redefs [http/handle-request handler]
           (is (= {:did-doc doc}
                  (deref (identity/resolve-did "did:web:example.com") 1000 ::timeout)))
           (is (= "https://example.com/.well-known/did.json"
                  (:url (first @requests)))))))
     (testing "localhost is fetched over http (the only host allowed a port)"
       (let [doc {:id "did:web:localhost%3A8080"}
             {:keys [handler requests]} (fake-http/routed
                                         [["did.json" (fake-http/json-response doc)]])]
         (with-redefs [http/handle-request handler]
           (deref (identity/resolve-did "did:web:localhost%3A8080") 1000 ::timeout)
           (is (= "http://localhost:8080/.well-known/did.json"
                  (:url (first @requests)))))))
     (testing "path-form did:web is unsupported"
       (is (= "UnsupportedDidWebPath"
              (:error (deref (identity/resolve-did "did:web:example.com:path:to:resource")
                             1000 ::timeout)))))
     (testing "non-success is positively not found"
       (let [{:keys [handler]} (fake-http/routed [["did.json" {:status 404 :body "nope"}]])]
         (with-redefs [http/handle-request handler]
           (is (= "DidNotFound"
                  (:error (deref (identity/resolve-did "did:web:example.com") 1000 ::timeout)))))))
     (testing "document for another DID is rejected"
       (let [{:keys [handler]} (fake-http/routed
                                [["did.json" (fake-http/json-response {:id "did:web:evil.com"})]])]
         (with-redefs [http/handle-request handler]
           (is (= "PoorlyFormattedDidDocument"
                  (:error (deref (identity/resolve-did "did:web:example.com") 1000 ::timeout)))))))))

#?(:clj
   (deftest resolve-did-plc-validates-test
     (testing "plc documents are validated against the requested DID"
       (let [{:keys [handler]} (fake-http/routed
                                [["plc.directory"
                                  (fake-http/json-response {:id "did:plc:bbbbbbbbbbbbbbbbbbbbbbbb"})]])]
         (with-redefs [http/handle-request handler]
           (is (= "PoorlyFormattedDidDocument"
                  (:error (deref (identity/resolve-did "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa")
                                 1000 ::timeout)))))))))

#?(:clj
   (deftest resolve-did-unsupported-method-test
     (testing "unsupported methods error through the async value (no throw)"
       (is (= "UnsupportedDidMethod"
              (:error (deref (identity/resolve-did "did:key:zQ3shXjHeiBuRCKmM36cuYnm7YEMzhGnCmCyW92sRJ9pribSF")
                             1000 ::timeout))))
       (is (= "UnsupportedDidMethod"
              (:error (deref (identity/resolve-did "not-even-a-did") 1000 ::timeout)))))))

;; -----------------------------------------------------------------------------
;; Handle resolution
;; -----------------------------------------------------------------------------

#?(:clj
   (defn- fake-dns
     "DNS interceptor stub: hostname -> seq of TXT values (or an error map)."
     [txt-by-hostname]
     {::i/name ::fake-dns
      ::i/enter (fn [{:keys [::i/request] :as ctx}]
                  (let [v (get txt-by-hostname (:hostname request))]
                    (assoc ctx ::i/response
                           (cond
                             (nil? v) {:error "DNS name not found"}
                             (map? v) v
                             :else {:values v}))))}))

;; TXT scenarios from packages/identity/tests/handle-resolver.test.ts:3-37
;; (chunked strings appear pre-joined, as JNDI returns them)
#?(:clj
   (def dns-fixtures
     {"_atproto.simple.test" ["did=did:example:simpleDid"]
      "_atproto.noisy.test" ["blah blah blah"
                             "did:example:fakeDid"
                             "atproto=did:example:fakeDid"
                             "did=did:example:noisyDid"
                             "chunk long domain aspdfoiuwerpoaisdfupasodfiuaspdfoiuasdpfoiausdfpaosidfuaspodifuaspdfoiuasdpfoiasudfpasodifuaspdofiuaspdfoiuasdapsodfiuweproiasudfpoasidfu"]
      "_atproto.bad.test" ["blah blah blah"
                           "did:example:fakeDid"
                           "atproto=did:example:fakeDid"]
      "_atproto.multi.test" ["did=did:example:firstDid"
                             "did=did:example:secondDid"]}))

#?(:clj
   (deftest resolve-handle-dns-test
     (let [no-https {:handler (fn [_ cb] (cb {:status 500}))}]
       (with-redefs [dns/interceptor (fake-dns dns-fixtures)
                     http/handle-request (:handler no-https)]
         (testing "simple"
           (is (= {:did "did:example:simpleDid"}
                  (deref (identity/resolve-handle "simple.test") 1000 ::timeout))))
         (testing "noisy"
           (is (= {:did "did:example:noisyDid"}
                  (deref (identity/resolve-handle "noisy.test") 1000 ::timeout))))
         (testing "bad (no did= record falls back to https, which fails)"
           (is (= "HandleNotFound"
                  (:error (deref (identity/resolve-handle "bad.test") 1000 ::timeout)))))
         (testing "multiple did= records is ambiguous"
           (is (= "HandleNotFound"
                  (:error (deref (identity/resolve-handle "multi.test") 1000 ::timeout)))))))))

#?(:clj
   (deftest resolve-handle-https-fallback-test
     (with-redefs [dns/interceptor (fake-dns {})]
       (testing "first line of the body, trimmed, must be a supported DID"
         (let [{:keys [handler]} (fake-http/routed
                                  [["/.well-known/atproto-did"
                                    {:status 200
                                     :body "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa  \nignored junk"}]])]
           (with-redefs [http/handle-request handler]
             (is (= {:did "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa"}
                    (deref (identity/resolve-handle "alice.test") 1000 ::timeout))))))
       (testing "junk body is HandleNotFound"
         (let [{:keys [handler]} (fake-http/routed
                                  [["/.well-known/atproto-did"
                                    {:status 200 :body "not a did at all"}]])]
           (with-redefs [http/handle-request handler]
             (is (= "HandleNotFound"
                    (:error (deref (identity/resolve-handle "alice.test") 1000 ::timeout)))))))
       (testing "http error is HandleNotFound"
         (let [{:keys [handler]} (fake-http/routed
                                  [["/.well-known/atproto-did" {:status 404 :body ""}]])]
           (with-redefs [http/handle-request handler]
             (is (= "HandleNotFound"
                    (:error (deref (identity/resolve-handle "alice.test") 1000 ::timeout))))))))))

;; -----------------------------------------------------------------------------
;; resolve-identity
;; -----------------------------------------------------------------------------

#?(:clj
   (deftest resolve-identity-invalid-identifier-test
     ;; regression: used to hang forever (no :else in the cond)
     (is (= "InvalidAtIdentifier"
            (:error (deref (identity/resolve-identity "!!! definitely not valid !!!")
                           1000 ::timeout))))))

#?(:clj
   (deftest resolve-identity-bidirectional-test
     (let [did "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa"
           doc {:id did
                :alsoKnownAs ["at://alice.test"]
                :service [{:id "#atproto_pds"
                           :type "AtprotoPersonalDataServer"
                           :serviceEndpoint "https://pds.test"}]}]
       (testing "handle input verifies against the DID document"
         (with-redefs [dns/interceptor (fake-dns {"_atproto.alice.test" [(str "did=" did)]
                                                  "_atproto.eve.test" [(str "did=" did)]})]
           (let [{:keys [handler]} (fake-http/routed
                                    [["plc.directory" (fake-http/json-response doc)]])]
             (with-redefs [http/handle-request handler]
               (is (= {:did did :did-doc doc :pds "https://pds.test" :handle "alice.test"}
                      (deref (identity/resolve-identity "alice.test") 1000 ::timeout)))
               ;; a handle not listed in the doc fails bidirectional verification
               (is (= "HandleNotFound"
                      (:error (deref (identity/resolve-identity "eve.test") 1000 ::timeout)))))))))))
