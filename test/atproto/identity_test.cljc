(ns atproto.identity-test
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            #?(:clj [clojure.java.io :as io])
            [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.http :as http]
            [atproto.runtime.dns :as dns]
            [atproto.runtime.json :as json]
            [atproto.test-support.http :as fake-http]
            [atproto.test-support.gen :as tsg]
            [atproto.identity :as identity]))

;; -----------------------------------------------------------------------------
;; DID syntax
;; -----------------------------------------------------------------------------

(defspec did-plc-spec-spec 100
  ;; valid did:plc = exactly 24 lowercase base32 chars; any length change
  ;; or out-of-alphabet character invalidates it
  (prop/for-all [msid tsg/plc-msid
                 bad-char (gen/elements (seq "0189ABCXYZ!_"))
                 idx (gen/choose 0 23)]
    (and (s/valid? ::identity/did (str "did:plc:" msid))
         (not (s/valid? ::identity/did (str "did:plc:" (subs msid 1))))
         (not (s/valid? ::identity/did (str "did:plc:" msid "a")))
         (not (s/valid? ::identity/did
                        (str "did:plc:"
                             (subs msid 0 idx) bad-char (subs msid (inc idx))))))))

(defspec did-web-spec-spec 100
  ;; bare hostnames are valid; localhost is the only host allowed a port;
  ;; path components are never allowed
  (prop/for-all [host (gen/such-that #(not (str/starts-with? % "localhost"))
                                     tsg/hostname)
                 port (gen/choose 1 65535)]
    (and (s/valid? ::identity/did (str "did:web:" host))
         (s/valid? ::identity/did (str "did:web:localhost%3A" port))
         (not (s/valid? ::identity/did (str "did:web:" host "%3A" port)))
         (not (s/valid? ::identity/did (str "did:web:" host ":path"))))))

(deftest test-invalid-atproto-did
  ;; irregular syntax cases kept from the reference test tables
  (are [did] (not (s/valid? ::identity/did did))
    "did:web:foo@example.com"              ; unallowed character
    "did:web::example.com"                 ; cannot start with colon
    "did:web:example.com:"                 ; cannot end with colon
    "did:web:example.com:path:to:resource" ; no path allowed
    "did:web:example.com%3A8080"           ; no port allowed outside localhost
    ))

(defspec web-did-url-round-trip-spec 100
  (prop/for-all [host tsg/hostname
                 port (gen/one-of [(gen/return nil) (gen/choose 1 65535)])]
    (let [did (if port (str "did:web:localhost%3A" port) (str "did:web:" host))
          url (if port (str "http://localhost:" port "/") (str "https://" host "/"))]
      (and (= url (identity/web-did->url did))
           (= did (identity/url->web-did url))))))

(deftest web-did-url-mapping-test
  ;; reference vectors (packages/did/tests/methods/web.test.ts)
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

(defspec did-doc-strict-id-matching-spec 100
  ;; service/verificationMethod ids match iff they are exactly
  ;; "#<fragment>" or "<doc-id>#<fragment>"; an id qualified with another
  ;; DID (a suffix match) or another fragment never matches
  (prop/for-all [doc-did tsg/did-plc
                 other-did tsg/did-plc
                 style (gen/elements [:fragment :qualified :other-did :wrong-fragment])]
    (let [id (case style
               :fragment "#atproto_pds"
               :qualified (str doc-did "#atproto_pds")
               :other-did (str other-did "#atproto_pds")
               :wrong-fragment "#other_pds")
          vm-id (case style
                  :fragment "#atproto"
                  :qualified (str doc-did "#atproto")
                  :other-did (str other-did "#atproto")
                  :wrong-fragment "#elsewhere")
          doc {:id doc-did
               :service [{:id id
                          :type "AtprotoPersonalDataServer"
                          :serviceEndpoint "https://pds.test"}]
               :verificationMethod [{:id vm-id
                                     :type "Multikey"
                                     :controller doc-did
                                     :publicKeyMultibase "zQ3shXjHeiBuRCKmM36cuYnm7YEMzhGnCmCyW92sRJ9pribSF"}]}
          match? (or (#{:fragment :qualified} style)
                     (and (= style :other-did) (= other-did doc-did)))]
      (and (= (when match? "https://pds.test")
              (identity/did-doc-pds doc))
           (= (when match?
                {:type "Multikey"
                 :public-key-multibase "zQ3shXjHeiBuRCKmM36cuYnm7YEMzhGnCmCyW92sRJ9pribSF"})
              (identity/did-doc-signing-key doc))))))

(defspec did-doc-pds-rejects-non-http-endpoints-spec 50
  (prop/for-all [scheme (gen/elements ["ftp" "gopher" "file" "ws"])
                 host tsg/hostname]
    (nil? (identity/did-doc-pds
           {:id "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa"
            :service [{:id "#atproto_pds"
                       :type "AtprotoPersonalDataServer"
                       :serviceEndpoint (str scheme "://" host)}]}))))

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

#?(:clj
   (def failing-https
     "An HTTP handler that always fails, so resolution cannot succeed
     through the HTTPS fallback."
     (fn [_ cb] (cb {:status 500}))))

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
     (with-redefs [dns/interceptor (fake-dns dns-fixtures)
                   http/handle-request failing-https]
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
                (:error (deref (identity/resolve-handle "multi.test") 1000 ::timeout))))))))

#?(:clj
   (def gen-txt-case
     "TXT record sets with 0-3 did= records shuffled among noise lines."
     (gen/let [dids (gen/vector tsg/did-plc 0 3)
               noise (gen/vector (gen/such-that #(not (str/starts-with? % "did="))
                                                gen/string-alphanumeric)
                                 0 4)
               values (gen/shuffle (concat (map #(str "did=" %) dids) noise))]
       {:dids dids :values values})))

#?(:clj
   (defspec resolve-handle-dns-exactly-one-spec 50
     ;; DNS resolution succeeds iff there is exactly one did= TXT record
     (prop/for-all [{:keys [dids values]} gen-txt-case]
       (with-redefs [dns/interceptor (fake-dns {"_atproto.prop.test" values})
                     http/handle-request failing-https]
         (let [res (deref (identity/resolve-handle "prop.test") 1000 ::timeout)]
           (if (= 1 (count dids))
             (= {:did (first dids)} res)
             (= "HandleNotFound" (:error res))))))))

#?(:clj
   (defspec resolve-handle-https-fallback-spec 50
     ;; the HTTPS fallback takes the first line of the body, trims it, and
     ;; requires a supported DID; anything else is HandleNotFound
     (prop/for-all [did tsg/did-plc
                    valid? gen/boolean
                    pad (gen/elements ["" " " "  \t"])
                    junk-lines (gen/vector tsg/label 0 3)]
       (with-redefs [dns/interceptor (fake-dns {})]
         (let [first-line (if valid? (str pad did pad) (str "garbage-" did))
               body (str/join "\n" (cons first-line junk-lines))
               {:keys [handler]} (fake-http/routed
                                  [["/.well-known/atproto-did" {:status 200 :body body}]])]
           (with-redefs [http/handle-request handler]
             (let [res (deref (identity/resolve-handle "alice.test") 1000 ::timeout)]
               (if valid?
                 (= {:did did} res)
                 (= "HandleNotFound" (:error res))))))))))

#?(:clj
   (deftest resolve-handle-https-error-test
     (with-redefs [dns/interceptor (fake-dns {})]
       (let [{:keys [handler]} (fake-http/routed
                                [["/.well-known/atproto-did" {:status 404 :body ""}]])]
         (with-redefs [http/handle-request handler]
           (is (= "HandleNotFound"
                  (:error (deref (identity/resolve-handle "alice.test") 1000 ::timeout)))))))))

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
