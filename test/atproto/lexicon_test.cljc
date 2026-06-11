(ns atproto.lexicon-test
  (:require #?@(:clj [[clojure.test :refer :all]
                      [clojure.java.io :as io]]
                :cljs [[cljs.test :refer :all]])
            [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [atproto.data :as data]
            [atproto.lexicon :as lexicon])
  #?(:cljs (:require-macros [atproto.lexicon-test :refer [interop-test-cases]])))

;; -----------------------------------------------------------------------------
;; Translator
;; -----------------------------------------------------------------------------

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

(deftest parse-nsid-test
  (testing "authority is returned in domain order"
    (is (= {:authority "feed.bsky.app" :name "post"}
           (lexicon/parse-nsid "app.bsky.feed.post")))
    (is (= {:authority "example.com" :name "fooBar"}
           (lexicon/parse-nsid "com.example.fooBar"))))
  (testing "valid NSIDs parse"
    (doseq [nsid (interop-test-cases "syntax/nsid_syntax_valid.txt")]
      (let [{:keys [authority name]} (lexicon/parse-nsid nsid)]
        (is (string? authority) (str nsid " has an authority"))
        (is (string? name) (str nsid " has a name")))))
  (testing "invalid NSIDs return nil"
    (doseq [nsid (interop-test-cases "syntax/nsid_syntax_invalid.txt")]
      (is (nil? (lexicon/parse-nsid nsid)) (str nsid " does not parse")))
    (is (nil? (lexicon/parse-nsid nil)))
    (is (nil? (lexicon/parse-nsid 42)))))

(defn- blob-ref
  "Helper to create test blob refs."
  [mime-type size]
  {:$type "blob"
   :ref (data/blob-ref (byte-array [1]))
   :mimeType mime-type
   :size size})

;; The schema embeds the test cases in the `::valid` and `::invalid` keys on each definition.
(def schema
  {:lexicon 1
   :id "com.example.schema"
   :defs
   {
    ;; null -----------------------------------------------------------------------------

    :null
    {:type "null"
     ::valid [nil]
     ::invalid [0 false "nil" "null"]}

    ;; boolean -----------------------------------------------------------------------------

    :boolean
    {:type "boolean"
     ::valid [true false]
     ::invalid [nil 1 "true" "false"]}

    :boolean-const-true
    {:type "boolean"
     :const true
     ::valid [true]
     ::invalid [nil 1 "true" false]}

    :boolean-const-false
    {:type "boolean"
     :const false
     ::valid [false]
     ::invalid [nil 1 "false" true]}

    ;; integer -----------------------------------------------------------------------------

    :integer
    {:type "integer"
     ::valid [1 (long 1) (dec data/js-max-integer) (- data/js-max-integer)]
     ::invalid [nil data/js-max-integer (dec (- data/js-max-integer))]}

    :integer_minmax
    {:type "integer"
     :minimum 5
     :maximum 10
     ::valid [5 (long 7) 10]
     ::invalid [nil 1 (long 2) 4 11]}

    :integer_enum
    {:type "integer"
     :enum [1 2 3 5 8 13]
     ::valid [1 2 3 5 8 13]
     ::invalid [nil -1 0 4 6 7 9 14]}

    :integer_const
    {:type "integer"
     :const 5
     ::valid [5 (long 5)]
     ::invalid [nil 0 1 4 6]}

    ;; string -----------------------------------------------------------------------------

    :string
    {:type "string"
     ::valid ["" "foo" "foo💩" "a~öñ©⽘☎𓋓😀👨‍👩‍👧‍👧‍"]
     ::invalid [nil 1]}

    :string-format-at-identifier
    {:type "string"
     :format "at-identifier"
     ::valid (interop-test-cases "syntax/atidentifier_syntax_valid.txt")
     ::invalid (interop-test-cases "syntax/atidentifier_syntax_invalid.txt")}

    :string-format-at-uri
    {:type "string"
     :format "at-uri"
     ::valid (interop-test-cases "syntax/aturi_syntax_valid.txt")
     ::invalid (interop-test-cases "syntax/aturi_syntax_invalid.txt")}

    :string-format-cid
    {:type "string"
     :format "cid"
     ::valid ["bafyreidfayvfuwqa7qlnopdjiqrxzs6blmoeu4rujcjtnci5beludirz2a"]
     ::invalid [nil "" "foobar"]}

    :string-format-datetime
    {:type "string"
     :format "datetime"
     ::valid (interop-test-cases "syntax/datetime_syntax_valid.txt")
     ::invalid (interop-test-cases "syntax/datetime_syntax_invalid.txt")}

    :string-format-did
    {:type "string"
     :format "did"
     ::valid (interop-test-cases "syntax/did_syntax_valid.txt")
     ::invalid (interop-test-cases "syntax/did_syntax_invalid.txt")}

    :string-format-handle
    {:type "string"
     :format "handle"
     ::valid (interop-test-cases "syntax/handle_syntax_valid.txt")
     ::invalid (interop-test-cases "syntax/handle_syntax_invalid.txt")}

    :string-format-nsid
    {:type "string"
     :format "nsid"
     ::valid (interop-test-cases "syntax/nsid_syntax_valid.txt")
     ::invalid (interop-test-cases "syntax/nsid_syntax_invalid.txt")}

    :string-format-tid
    {:type "string"
     :format "tid"
     ::valid (interop-test-cases "syntax/tid_syntax_valid.txt")
     ::invalid (interop-test-cases "syntax/tid_syntax_invalid.txt")}

    :string-format-record-key
    {:type "string"
     :format "record-key"
     ::valid (interop-test-cases "syntax/recordkey_syntax_valid.txt")
     ::invalid (interop-test-cases "syntax/recordkey_syntax_invalid.txt")}

    :string-format-uri
    {:type "string"
     :format "uri"
     ::valid ["http://example.com"]
     ::invalid [nil ""]}

    :string-format-language
    {:type "string"
     :format "language"
     ::valid ["de"
              "de-CH"
              "de-DE-1901"
              "es-419"
              "sl-IT-nedis"
              "mn-Cyrl-MN"
              "x-fr-CH"
              "en-GB-boont-r-extended-sequence-x-private"
              "sr-Cyrl"
              "hy-Latn-IT-arevela"
              "i-klingon"]
     ::invalid [nil
                ""
                "x"
                "de-CH-"
                "i-bad-grandfathered"]}

    :string-length
    {:type "string"
     :minLength 1
     :maxLength 5
     ::valid ["a" "ñ" ]
     ::invalid [nil "" "👨‍👩‍👧‍👧"]}

    :string-graphemes
    {:type "string"
     :minGraphemes 1
     :maxGraphemes 5
     ::valid ["a" "⽘" "👨‍👩‍👧‍👧"]
     ::invalid [nil "" "a~öñ©⽘☎𓋓😀👨‍👩‍👧‍👧‍"]}

    :string-enum
    {:type "string"
     :enum ["a" "⽘" "👨‍👩‍👧‍👧"]
     ::valid ["a" "⽘" "👨‍👩‍👧‍👧"]
     ::invalid [nil "" "b"]}

    :string-const
    {:type "string"
     :const "👨‍👩‍👧‍👧"
     ::valid ["👨‍👩‍👧‍👧"]
     ::invalid [nil "" "a"]}

    ;; bytes -----------------------------------------------------------------------------

    :bytes
    {:type "bytes"
     ::valid [(byte-array [1 2 3 4]) (byte-array [])]
     ::invalid [nil "" 1]}

    :bytes-length
    {:type "bytes"
     :minLength 1
     :maxLength 5
     ::valid [(byte-array [1]) (byte-array [1 2 3 4 5])]
     ::invalid [nil "" 1 (byte-array []) (byte-array [1 2 3 4 5 6])]}

    ;; cid-link -----------------------------------------------------------------------------

    :cid-link
    {:type "cid-link"
     ::valid [(data/cid-link (byte-array [1 2 3]))]
     ::invalid [nil "" (data/blob-ref (byte-array [1 2 3]))]}

    ;; blob -----------------------------------------------------------------------------

    :blob
    {:type "blob"
     ::valid [(blob-ref "text/plain" 5)]
     ::invalid [nil
                ""
                {}
                {:$type "blob"}]}

    :blob_accept_any
    {:type "blob"
     :accept ["*/*"]
     ::valid [(blob-ref "text/plain" 5)]
     ::invalid [{:$type "blob"}]}

    :blob_accept_image
    {:type "blob"
     :accept ["image/*"]
     ::valid [(blob-ref "image/png" 5)]
     ::invalid [{:$type "blob"}
                (blob-ref "text/plain" 5)]}

    :blob_accept_image_png
    {:type "blob"
     :accept ["image/png"]
     ::valid [(blob-ref "image/png" 5)
              (blob-ref "image/png;key=value" 5)]
     ::invalid [{:$type "blob"}
                (blob-ref "text/plain" 5)
                (blob-ref "image/jpg" 5)]}

    :blob-max-size
    {:type "blob"
     :maxSize 4
     ::valid [(blob-ref "text/plain" 1)
              (blob-ref "image/png" 4)]
     ::invalid [{:$type "blob"}
                (blob-ref "image/png" 5)]}

    ;; array -----------------------------------------------------------------------------

    :array
    {:type "array"
     :items {:type "integer"}
     ::valid [[] [1] [1 2 3]]
     ::invalid [nil [nil] ["a"]]}

    :array_length
    {:type "array"
     :items {:type "integer"}
     :minLength 1
     :maxLength 5
     ::valid [[1] [1 2 3 4 5]]
     ::invalid [nil [nil] ["a"] [] [1 2 3 4 5 6]]}

    ;; object -----------------------------------------------------------------------------

    :object
    {:type "object"
     :properties {:int {:type "integer"}
                  :string {:type "string"}}
     :required ["int"]
     :nullable ["string"]
     ::valid [{:int 0}
              {:int 0 :string "foobar"}
              {:int 0 :string nil}]
     ::invalid [{}
                {:int nil}
                {:int "foobar"}
                {:int 0 :string false}
                {:int 0 :string 1}]}

    ;; params -----------------------------------------------------------------------------

    :params
    {:type "params"
     :properties {:a {:type "boolean"}
                  :b {:type "string"}}
     :required ["a"]
     ::valid [{:a true}
              {:a false :b "foobar"}]
     ::invalid [{}
                {:a nil}
                {:a "foobar"}
                {:a true :b nil}
                {:a true :b false}
                {:a true :b 1}]}

    ;; token -----------------------------------------------------------------------------

    :token
    {:type "token"
     ::valid []
     ::invalid [nil "" 1 [] {}]}

    ;; ref -----------------------------------------------------------------------------

    :ref_local
    {:type "ref"
     :ref "#integer"
     ::valid [1 (long 1)]
     ::invalid [nil "a"]}

    :ref_global
    {:type "ref"
     :ref "com.example.schema#integer"
     ::valid [1 (long 1)]
     ::invalid [nil "a"]}

    ;; union -----------------------------------------------------------------------------

    :union
    {:type "union"
     :refs ["#object"]
     ::valid [{:$type "com.example.schema#object" :int 0}
              {:$type "com.example.schema#object" :int 0 :string "foobar"}
              ;; other object types are allowed if the union is open
              {:$type "com.example.schema#params" :a true :b "foobar"}
              ;; the $type does not even have to be known
              {:$type "com.example.schema#random" :foo "bar"}]
     ::invalid [nil
                1
                []
                ;; missing :$type
                {:int 0}
                ;; other object types are validated
                {:$type "com.example.schema#params" :a 1}]}

    :unknown
    {:type "unknown"
     ::valid [;; known type
              {:$type "com.example.schema#object" :int 0}
              ;; unknown type
              {:$type "com.example.schema#random" :a 1 :b 2}
              ;; no $type
              {:a 1 :b 2}]
     ::invalid [nil
                1
                ;; if we know the type, it must be valid
                {:$type "com.example.schema#object" :int "a"}
                ]}

    }})

(defn register-test-specs!
  [schema]
  (let [schema-valid? (s/valid? :atproto.lexicon.schema/file schema)]
    (when (is schema-valid? "The test schema is valid.")
      (lexicon/register-specs! {(:id schema) schema})
      true)))

(defn spec-for
  "The registered validator for this spec key, or the key itself."
  [spec-key]
  (or (lexicon/registered-validator spec-key) spec-key))

(defn- record-schema
  [id key]
  {:lexicon 1
   :id id
   :defs {:main {:type "record"
                 :key key
                 :record {:type "object"
                          :properties {:text {:type "string"}}}}}})

(deftest test-record-key-validation
  (lexicon/register-specs!
   (lexicon/lexicon [(record-schema "com.example.tidKeyed" "tid")
                     (record-schema "com.example.nsidKeyed" "nsid")
                     (record-schema "com.example.selfKeyed" "literal:self")
                     (record-schema "com.example.anyKeyed" "any")
                     {:lexicon 1
                      :id "com.example.notARecord"
                      :defs {:main {:type "query"}}}]))
  (testing "tid keys"
    (doseq [rkey (interop-test-cases "syntax/tid_syntax_valid.txt")]
      (is (true? (lexicon/valid-record-key? "com.example.tidKeyed" rkey))
          (str rkey " is a valid tid record key")))
    (doseq [rkey (interop-test-cases "syntax/tid_syntax_invalid.txt")]
      (is (false? (lexicon/valid-record-key? "com.example.tidKeyed" rkey))
          (str rkey " is not a valid tid record key"))))
  (testing "nsid keys"
    (doseq [rkey (interop-test-cases "syntax/nsid_syntax_valid.txt")]
      (is (true? (lexicon/valid-record-key? "com.example.nsidKeyed" rkey))
          (str rkey " is a valid nsid record key")))
    (doseq [rkey (interop-test-cases "syntax/nsid_syntax_invalid.txt")]
      (is (false? (lexicon/valid-record-key? "com.example.nsidKeyed" rkey))
          (str rkey " is not a valid nsid record key"))))
  (testing "literal keys"
    (is (true? (lexicon/valid-record-key? "com.example.selfKeyed" "self")))
    (is (false? (lexicon/valid-record-key? "com.example.selfKeyed" "other")))
    (is (false? (lexicon/valid-record-key? "com.example.selfKeyed" "literal:self"))))
  (testing "any keys"
    (doseq [rkey (interop-test-cases "syntax/recordkey_syntax_valid.txt")]
      (is (true? (lexicon/valid-record-key? "com.example.anyKeyed" rkey))
          (str rkey " is a valid record key")))
    (doseq [rkey (interop-test-cases "syntax/recordkey_syntax_invalid.txt")]
      (is (false? (lexicon/valid-record-key? "com.example.anyKeyed" rkey))
          (str rkey " is not a valid record key"))))
  (testing "unknown or non-record collections"
    (is (= "UnknownCollection"
           (:error (lexicon/valid-record-key? "com.example.unregistered" "abc"))))
    (is (= "UnknownCollection"
           (:error (lexicon/valid-record-key? "com.example.notARecord" "abc"))))
    (is (nil? (lexicon/record-key-spec "com.example.unregistered")))
    (is (nil? (lexicon/record-key-spec "com.example.notARecord")))))

(deftest test-union-ref-check
  (testing "a union ref targeting a non-object def in the set throws"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                 (lexicon/lexicon
                  [{:lexicon 1
                    :id "com.example.unionBad"
                    :defs {:name {:type "string"}
                           :main {:type "object"
                                  :properties {:value {:type "union"
                                                       :refs ["#name"]}}}}}]))))
  (testing "a union ref targeting a non-object def in another schema of the set throws"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                 (lexicon/lexicon
                  [{:lexicon 1
                    :id "com.example.unionBad"
                    :defs {:main {:type "object"
                                  :properties {:value {:type "union"
                                                       :refs ["com.example.other#name"]}}}}}
                   {:lexicon 1
                    :id "com.example.other"
                    :defs {:name {:type "string"}}}]))))
  (testing "union refs to object defs and to absent schemas are fine"
    (is (map? (lexicon/lexicon
               [{:lexicon 1
                 :id "com.example.unionGood"
                 :defs {:thing {:type "object" :properties {}}
                        :main {:type "object"
                               :properties {:value {:type "union"
                                                    :refs ["#thing"
                                                           "com.example.absent#thing"]}}}}}])))))

(def modes-schema
  {:lexicon 1
   :id "com.example.modes"
   :defs {:datetime {:type "string" :format "datetime"}
          :atUri    {:type "string" :format "at-uri"}
          :blob     {:type "blob" :accept ["image/*"] :maxSize 1000}}})

(deftest test-strict-lenient-modes
  (when (register-test-specs! modes-schema)
    (let [datetime (spec-for :com.example.modes/datetime)
          at-uri (spec-for :com.example.modes/atUri)
          blob (spec-for :com.example.modes/blob)
          lenient-valid? (fn [spec x]
                           (binding [lexicon/*strict* false]
                             (s/valid? spec x)))]
      (testing "datetime: strict-valid inputs behave identically in both modes"
        (doseq [s (interop-test-cases "syntax/datetime_syntax_valid.txt")]
          (is (s/valid? datetime s) (str s " is valid in strict mode"))
          (is (lenient-valid? datetime s) (str s " is valid in lenient mode"))))
      (testing "datetime: offset-less ISO datetimes pass only in lenient mode"
        (doseq [s ["1985-04-12T23:20:50"
                   "1985-04-12T23:20:50.123"]]
          (is (not (s/valid? datetime s)) (str s " is invalid in strict mode"))
          (is (lenient-valid? datetime s) (str s " is valid in lenient mode"))))
      (testing "datetime: junk fails in both modes"
        (doseq [s ["" "foo" "1985-04-12" nil 42]]
          (is (not (s/valid? datetime s)))
          (is (not (lenient-valid? datetime s)))))
      (testing "at-uri: rkey record-key validity only enforced in strict mode"
        (let [valid "at://user.bsky.social/app.bsky.feed.post/3jzfcijpj2z2a"
              bad-rkey "at://user.bsky.social/app.bsky.feed.post/key!"]
          (is (s/valid? at-uri valid))
          (is (lenient-valid? at-uri valid))
          (is (not (s/valid? at-uri bad-rkey)))
          (is (lenient-valid? at-uri bad-rkey))
          (is (not (s/valid? at-uri "at://")))
          (is (not (lenient-valid? at-uri "at://")))))
      (testing "blob: legacy untyped refs pass only in lenient mode"
        (let [cid-str (data/format-cid (data/blob-ref (byte-array [1])))
              legacy {:cid cid-str :mimeType "image/png"}]
          (is (not (s/valid? blob legacy)))
          (is (lenient-valid? blob legacy))
          ;; junk that is not a legacy ref fails in both modes
          (is (not (lenient-valid? blob {:cid "not-a-cid" :mimeType "image/png"})))
          (is (not (lenient-valid? blob {:cid cid-str})))))
      (testing "blob: accept/maxSize checks are skipped in lenient mode"
        (let [good (blob-ref "image/png" 5)
              oversized (blob-ref "image/png" 2000)
              wrong-mime (blob-ref "text/plain" 5)]
          (is (s/valid? blob good))
          (is (lenient-valid? blob good))
          (is (not (s/valid? blob oversized)))
          (is (lenient-valid? blob oversized))
          (is (not (s/valid? blob wrong-mime)))
          (is (lenient-valid? blob wrong-mime)))))))

(deftest test-validator-compiler
  (when (register-test-specs! schema)
    (doseq [[k def] (:defs schema)]
      (let [spec-key (keyword (:id schema) (name k))]
        (doseq [valid (::valid def)]
          (is (s/valid? (spec-for spec-key) valid)
              (str "\"" valid "\" is a valid " spec-key)))
        (doseq [invalid (::invalid def)]
          (is (not (s/valid? (spec-for spec-key) invalid))
              (str "[" invalid "] is not a valid " spec-key)))))))
