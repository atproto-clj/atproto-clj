(ns atproto.identity-test
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            [clojure.spec.alpha :as s]
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
