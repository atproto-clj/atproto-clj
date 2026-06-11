(ns atproto.at-uri-test
  (:require #?@(:clj [[clojure.test :refer :all]
                      [clojure.java.io :as io]]
                :cljs [[cljs.test :refer :all]])
            [clojure.string :as str]
            [atproto.at-uri :as at-uri])
  #?(:cljs (:require-macros [atproto.at-uri-test :refer [interop-test-cases]])))

#?(:clj
   (defmacro interop-test-cases
     "Return the test cases from the file at `path` in the interop test file directory.

      Can be called from ClojureScript to inline the test cases."
     [path]
     (->> (slurp (io/resource (str "interop-test-files/" path)))
          (str/split-lines)
          (remove #(or (str/blank? %)
                       (str/starts-with? % "#")))
          (into []))))

(deftest interop-fixtures-test
  (doseq [s (interop-test-cases "syntax/aturi_syntax_valid.txt")]
    (is (at-uri/valid? s) (str "expected valid: " s))
    (let [parsed (at-uri/parse s)]
      (is (some? parsed) (str "expected parseable: " s))
      ;; fixture URIs are canonical: make round-trips them exactly
      (is (= s (at-uri/make parsed)) (str "expected round-trip: " s))))
  (doseq [s (interop-test-cases "syntax/aturi_syntax_invalid.txt")]
    (is (not (at-uri/valid? s)) (str "expected invalid: " s))
    (is (nil? (at-uri/parse s)) (str "expected unparseable: " s))))

(deftest parse-test
  (is (= {:authority "did:plc:asdf123"}
         (at-uri/parse "at://did:plc:asdf123")))
  (is (= {:authority "user.bsky.social"}
         (at-uri/parse "at://user.bsky.social")))
  (is (= {:authority "did:plc:asdf123"
          :collection "app.bsky.feed.post"}
         (at-uri/parse "at://did:plc:asdf123/app.bsky.feed.post")))
  (is (= {:authority "did:plc:asdf123"
          :collection "app.bsky.feed.post"
          :rkey "3jzfcijpj2z2a"}
         (at-uri/parse "at://did:plc:asdf123/app.bsky.feed.post/3jzfcijpj2z2a")))
  ;; not a string / garbage
  (is (nil? (at-uri/parse nil)))
  (is (nil? (at-uri/parse 42)))
  (is (nil? (at-uri/parse "")))
  (is (nil? (at-uri/parse "https://example.com")))
  ;; invalid segments are rejected even when the shape matches
  (is (nil? (at-uri/parse "at://name")))
  (is (nil? (at-uri/parse "at://did:plc:asdf123/not-an-nsid/rkey")))
  (is (nil? (at-uri/parse "at://did:plc:asdf123/app.bsky.feed.post/..")))
  ;; 8KB length cap
  (is (nil? (at-uri/parse (str "at://did:plc:asdf123/app.bsky.feed.post/"
                               (apply str (repeat (* 8 1024) "o")))))))

(deftest accessors-test
  (let [uri "at://did:plc:asdf123/app.bsky.feed.post/3jzfcijpj2z2a"]
    (is (= "did:plc:asdf123" (at-uri/authority uri)))
    (is (= "app.bsky.feed.post" (at-uri/collection uri)))
    (is (= "3jzfcijpj2z2a" (at-uri/rkey uri)))
    (is (nil? (at-uri/fragment uri)))
    ;; accessors also accept a parsed map
    (let [parsed (at-uri/parse uri)]
      (is (= "did:plc:asdf123" (at-uri/authority parsed)))
      (is (= "app.bsky.feed.post" (at-uri/collection parsed)))
      (is (= "3jzfcijpj2z2a" (at-uri/rkey parsed)))))
  (is (nil? (at-uri/collection "at://did:plc:asdf123")))
  (is (nil? (at-uri/rkey "at://did:plc:asdf123/app.bsky.feed.post")))
  (is (nil? (at-uri/authority "not-a-uri"))))

(deftest make-test
  (is (= "at://did:plc:asdf123"
         (at-uri/make "did:plc:asdf123")))
  (is (= "at://did:plc:asdf123/app.bsky.feed.post"
         (at-uri/make "did:plc:asdf123" "app.bsky.feed.post")))
  (is (= "at://did:plc:asdf123/app.bsky.feed.post/3jzfcijpj2z2a"
         (at-uri/make "did:plc:asdf123" "app.bsky.feed.post" "3jzfcijpj2z2a")))
  (is (= "at://did:plc:asdf123/app.bsky.feed.post/3jzfcijpj2z2a"
         (at-uri/make {:authority "did:plc:asdf123"
                       :collection "app.bsky.feed.post"
                       :rkey "3jzfcijpj2z2a"})))
  ;; bad inputs return error maps
  (is (= "InvalidAtUri" (:error (at-uri/make "name"))))
  (is (= "InvalidAtUri" (:error (at-uri/make nil))))
  (is (= "InvalidAtUri" (:error (at-uri/make "did:plc:asdf123" "not-an-nsid"))))
  (is (= "InvalidAtUri" (:error (at-uri/make "did:plc:asdf123" "app.bsky.feed.post" ".."))))
  (is (= "InvalidAtUri" (:error (at-uri/make {:authority "did:plc:asdf123"
                                              :rkey "3jzfcijpj2z2a"})))))
