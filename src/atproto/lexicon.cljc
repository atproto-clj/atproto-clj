(ns atproto.lexicon
  "Lexicon is a schema definition language used to describe atproto records,
  HTTP endpoints (XRPC), and event stream messages.

  See https://atproto.com/specs/lexicon"
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as s]
            #?(:clj [clojure.java.io :as io])
            [atproto.data :as data]
            [atproto.lexicon.regex :as regex]
            [atproto.runtime.string :refer [utf8-length grapheme-length]]
            [atproto.runtime.datetime :as datetime]
            [atproto.runtime.http :as http]
            [atproto.runtime.json :as json]
            [atproto.runtime.cast :as cast]
            ;; Aliases for spec keys
            [atproto.lexicon.schema                   :as-alias schema]
            [atproto.lexicon.schema.file              :as-alias file]
            [atproto.lexicon.schema.type-def          :as-alias type-def]
            [atproto.lexicon.schema.error             :as-alias error]
            [atproto.lexicon.schema.body              :as-alias body]
            [atproto.lexicon.schema.message           :as-alias message]
            [atproto.lexicon.schema.type.null         :as-alias null]
            [atproto.lexicon.schema.type.boolean      :as-alias boolean]
            [atproto.lexicon.schema.type.integer      :as-alias integer]
            [atproto.lexicon.schema.type.string       :as-alias string]
            [atproto.lexicon.schema.type.bytes        :as-alias bytes]
            [atproto.lexicon.schema.type.cid-link     :as-alias cid-link]
            [atproto.lexicon.schema.type.array        :as-alias array]
            [atproto.lexicon.schema.type.object       :as-alias object]
            [atproto.lexicon.schema.type.blob         :as-alias blob]
            [atproto.lexicon.schema.type.params       :as-alias params]
            [atproto.lexicon.schema.type.token        :as-alias token]
            [atproto.lexicon.schema.type.ref          :as-alias ref]
            [atproto.lexicon.schema.type.union        :as-alias union]
            [atproto.lexicon.schema.type.unknown      :as-alias unkown]
            [atproto.lexicon.schema.type.record       :as-alias record]
            [atproto.lexicon.schema.type.query        :as-alias query]
            [atproto.lexicon.schema.type.procedure    :as-alias procedure]
            [atproto.lexicon.schema.type.subscription :as-alias subscription]))

;; todo:
;; - validate that the refs pointed to by `union` in the schema have `type=object`.

;; -----------------------------------------------------------------------------
;; String Formats: https://atproto.com/specs/lexicon#string-formats
;; -----------------------------------------------------------------------------

(s/def ::nsid
  (s/and string?
         #(<= (count %) 317)
         (s/conformer #(or (when-let [[_ authority name] (re-matches regex/nsid %)]
                             (str (str/lower-case authority) "." name))
                           ::s/invalid))))

;; datetime supports nanosecond precision: "1985-04-12T23:20:50.123456789"
;; but not below: "1985-04-12T23:20:50.12345678912345"

;; trim second fraction digits below nanosecond
(s/def ::datetime
  (s/and #(<= (count %) 64)
         #(not (str/starts-with? % "000"))
         #(not (str/starts-with? % "-"))
         #(not (str/ends-with? % "-00:00"))
         #(re-matches regex/rfc3339 %)
         datetime/parse))

(s/def ::did
  (s/and string?
         #(<= (count %) 2048)
         #(re-matches regex/did %)))

(s/def ::handle
  (s/and string?
         #(<= (count %) 253)
         #(re-matches regex/handle %)
         (s/conformer str/lower-case)))

(s/def ::at-identifier
  (s/or :handle ::handle
        :did    ::did))

(s/def ::at-uri
  (s/and string?
         #(< (count %) (* 8 1024))
         (s/conformer #(or (when-let [[_ authority collection rkey _] (re-matches regex/at-uri %)]
                             (when (and (s/valid? ::at-identifier authority)
                                        (or (not collection) (s/valid? ::nsid collection))
                                        (or (not rkey) (s/valid? ::record-key rkey)))
                               (cond-> {:authority (second (s/conform ::at-identifier authority))}
                                 collection (assoc :collection (s/conform ::nsid collection))
                                 rkey       (assoc :rkey rkey))))
                           ::s/invalid))))

(s/def ::cid
  (s/and string?
         data/parse-cid))

(s/def ::record-key
  (s/and string?
         #(<= 1 (count %) 512)
         #(re-matches regex/record-key %)
         (complement #{"." ".."})))

(s/def ::tid
  (s/and string?
         #(= 13 (count %))
         #(re-matches regex/tid %)))

(s/def ::uri
  ::http/uri)

(s/def ::language
  (s/and string?
         #(re-matches regex/bcp47 %)))

;; -----------------------------------------------------------------------------
;; MIME Types
;; -----------------------------------------------------------------------------

(s/def ::mime-type
  (s/and string?
         #(re-matches regex/mime-type %)
         (s/conformer str/lower-case)))

(defn parse-mime-type
  "Return a map with :type, :subtype, and :parameter.

  All values are strings."
  [s]
  (let [[_ type subtype parameter] (re-matches regex/mime-type
                                               (str/lower-case s))]
    (cond-> {:type type
             :subtype subtype}
      (not (str/blank? parameter)) (assoc :parameter parameter))))

(s/def ::mime-type-pattern
  (s/and string?
         (s/conformer
          #(or (if-let [[_ type] (re-matches #"([^/]+)/\*" %)]
                 {:type type
                  :subtype "*"}
                 (when-let [[_ type subtype _] (re-matches regex/mime-type %)]
                   {:type type
                    :subtype subtype}))
               ::s/invalid))))

(defn mime-type-pattern-match?
  "Whether the valid MIME Type string matches the (conformed) pattern."
  [pattern s]
  (when (s/valid? ::mime-type s)
    (let [{:keys [type subtype]} (parse-mime-type s)]
      (and (#{"*" type} (:type pattern))
           (#{"*" subtype} (:subtype pattern))))))

(defn accept-mime-type?
  "Whether the mime-type matches at least one of the conformed patterns in `accept`."
  [accept s]
  (some #(mime-type-pattern-match? % s)
        accept))

;; -----------------------------------------------------------------------------
;; Schema: Validation
;; -----------------------------------------------------------------------------

;; Shared types

;; The name of the type (e.g. 'null', 'boolean'...)
(s/def ::schema/type string?)

;; Short description string of the container (schema, type...)
(s/def ::schema/description string?)

;; Errors returned by procedure, query, and subscription
(s/def ::schema/error
  (s/keys :req-un [::error/name]
          :opt-un [::description]))

(s/def ::error/name
  (s/and string?
         #(not (re-matches #"\s" %))))

;; Schema of the HTTP bodies for procedure and query
(s/def ::schema/body
  (s/keys :req-un [::body/encoding]
          :opt-un [::body/schema
                   ::description]))

(s/def ::body/encoding ::mime-type-pattern)

(s/def ::body/schema
  (s/and ::schema/field-type
         #(contains? #{"object" "ref" "union"} (:type %))))

;; File

(s/def ::schema/file
  (s/keys :req-un [::file/lexicon ::file/id ::file/defs]
          :opt-un [::schema/description]))

(s/def ::file/lexicon #{1})

(s/def ::file/id ::nsid)

(s/def ::file/defs
  (s/and
   (s/map-of ::data/key
             (s/or :field   ::schema/field-type
                   :primary ::schema/primary-type))
   ;; Primary types should always have the name `main`.
   ;; It is possible for main to describe a non-primary type.
   #(every? (fn [[name [type def]]] (or (= :main name) (= :field type))) %)))

(defmulti field-type-spec :type)

(s/def ::schema/field-type
  (s/and (s/keys :req-un [::schema/type]
                 :opt-un [::schema/description])
         (s/multi-spec field-type-spec :type)))

(defmethod field-type-spec "null" [_]
  any?)

(defmethod field-type-spec "boolean" [_]
  (s/keys :opt-un [::boolean/default
                   ::boolean/const]))

(s/def ::boolean/default boolean?)
(s/def ::boolean/const boolean?)

(defmethod field-type-spec "integer" [_]
  (s/keys :opt-un [::integer/minimum
                   ::integer/maximum
                   ::integer/enum
                   ::integer/default
                   ::integer/const]))

(s/def ::integer/minimum int?)
(s/def ::integer/maximum int?)
(s/def ::integer/enum (s/coll-of int?))
(s/def ::integer/default int?)
(s/def ::integer/const int?)

(defmethod field-type-spec "string" [_]
  (s/keys :opt-un [::string/format
                   ::string/maxLength
                   ::string/minLength
                   ::string/maxGraphemes
                   ::string/minGraphemes
                   ::string/knownValues
                   ::string/enum
                   ::string/default
                   ::string/const]))

(s/def ::string/format #{"at-identifier"
                         "at-uri"
                         "cid"
                         "datetime"
                         "did"
                         "handle"
                         "nsid"
                         "tid"
                         "record-key"
                         "uri"
                         "language"})
(s/def ::string/maxLength int?)
(s/def ::string/minLength int?)
(s/def ::string/maxGraphemes int?)
(s/def ::string/minGraphemes int?)
(s/def ::string/knownValues (s/coll-of string?))
(s/def ::string/enum (s/coll-of string?))
(s/def ::string/default string?)
(s/def ::string/const string?)

(defmethod field-type-spec "bytes" [_]
  (s/keys :opt-un [::bytes/minLength
                   ::bytes/maxLength]))

(s/def ::bytes/minLength int?)
(s/def ::bytes/maxLength int?)

(defmethod field-type-spec "cid-link" [_]
  any?)

(defmethod field-type-spec "blob" [_]
  (s/keys :opt-un [::blob/accept
                   ::blob/maxSize]))

(s/def ::blob/accept (s/coll-of ::mime-type-pattern))
(s/def ::blob/maxSize int?)

(defmethod field-type-spec "array" [_]
  (s/keys :req-un [::array/items]
          :opt-un [::array/minLength
                   ::array/maxLength]))

(s/def ::array/items ::schema/field-type)
(s/def ::array/minLength int?)
(s/def ::array/maxLength int?)

(defmethod field-type-spec "object" [_]
  (s/keys :req-un [::object/properties]
          :opt-un [::object/required
                   ::object/nullable]))

(s/def ::object/properties (s/map-of ::data/key ::schema/field-type))
(s/def ::object/required (s/coll-of string?))
(s/def ::object/nullable (s/coll-of string?))

(defmethod field-type-spec "params" [_]
  (s/keys :req-un [::params/properties]
          :opt-un [::params/required]))

(s/def ::params/properties
  (s/map-of ::data/key
            (s/and ::schema/field-type
                   #(contains? #{"boolean" "integer" "string" "unknown" "array"}
                               (:type %)))))

(s/def ::params/required
  (s/coll-of string?))

(defmethod field-type-spec "token" [_]
  any?)

(defmethod field-type-spec "ref" [_]
  (s/keys :req-un [::ref/ref]))

(s/def ::ref/ref
  (s/or :local-ref (s/conformer
                    (fn [s]
                      (if (str/starts-with? s "#")
                        (subs s 1)
                        ::s/invalid)))
        :global-ref (s/conformer
                     (fn [s]
                       (let [[nsid name] (str/split s #"#")]
                         (if (s/valid? ::nsid nsid)
                           s
                           ::s/invalid))))))

(defmethod field-type-spec "union" [_]
  (s/and
   (s/keys :opt-un [::union/refs
                    ::union/closed])
   ;; refs is only optional if closed=false
   #(not (and (empty? (:refs %))
              (:closed %)))))

(s/def ::union/refs (s/coll-of ::ref/ref))
(s/def ::union/closed boolean?)

(defmethod field-type-spec "unknown" [_]
  any?)

;; Primary Types

(defmulti primary-type-spec :type)

(s/def ::schema/primary-type
  (s/and (s/keys :req-un [::type]
                 :opt-un [::description])
         (s/multi-spec primary-type-spec :type)))

(defmethod primary-type-spec "record" [_]
  (s/keys :req-un [::record/key
                   ::record/record]))

(s/def ::record/record (s/and ::schema/field-type
                              #(= "object" (:type %))))

(s/def ::record/key
  (s/or :tid     #{"tid"}
        :nsid    #{"nsid"}
        :literal #(let [[_ value] (re-matches #"literal:(.+)$" %)]
                    (or value ::s/invalid))
        :any     #{"any"}))

(defmethod primary-type-spec "query" [_]
  (s/keys :opt-un [::query/parameters
                   ::query/output
                   ::query/errors]))

(s/def ::query/parameters (s/and ::schema/field-type #(= "params" (:type %))))
(s/def ::query/output ::schema/body)
(s/def ::query/errors (s/coll-of ::schema/error))

(defmethod primary-type-spec "procedure" [_]
  (s/keys :opt-un [::procedure/parameters
                   ::procedure/output
                   ::procedure/input
                   ::procedure/errors]))

(s/def ::procedure/parameters (s/and ::schema/field-type #(= "params" (:type %))))
(s/def ::procedure/output ::schema/body)
(s/def ::procedure/input ::schema/body)
(s/def ::procedure/errors (s/coll-of ::schema/error))

(defmethod primary-type-spec "subscription" [_]
  (s/keys :opt-un [::subscription/parameters
                   ::subscription/message
                   ::subscription/errors]))

(s/def ::subscription/parameters (s/and ::schema/field-type #(= "params" (:type %))))
(s/def ::subscription/message (s/keys :req-un [::message/schema]
                                      :opt-un [::description]))
(s/def ::message/schema (s/and ::schema/field-type #(= "union" (:type %))))

;; -----------------------------------------------------------------------------
;; Translator (schema -> clojure spec)
;; -----------------------------------------------------------------------------

(defn- context
  "The context passed to translator functions.

  :nsid  id of the schema, used to resolve local references
  :ns    current namespace to generate spec keys
  :defs  volatile holding the seq of s/def forms generated by the translator functions"
  [nsid]
  {:nsid nsid
   :ns nsid
   :defs (volatile! [])})

(defn- spec-key
  "The spec key in the current context."
  [{:keys [ns]}]
  (let [idx (str/last-index-of ns \.)]
    (keyword (subs ns 0 idx)
             (subs ns (inc idx)))))

(defn- nest
  "Add the name to the context's namespace."
  [ctx name]
  (update ctx :ns str "." name))

(defn- add-spec
  "Add the spec definition to the context and return the key."
  [ctx spec]
  (let [spec-key (spec-key ctx)]
    (vswap! (:defs ctx) conj `(s/def ~spec-key ~spec))
    spec-key))

(defmulti ^:private field-type-def->spec
  "Translate the field type definition into a spec form and return it.

  Implementations can add 'sub specs' in the context as needed."
  (fn [ctx type-def] (:type type-def)))

(defmulti ^:private translate-primary-type-def
  "Translate the Lexicon Primary Type Definition into spec forms and add them to the context."
  (fn [ctx primary-type-def] (:type primary-type-def)))

(defn translate
  "Translate the Lexicon file into Clojure spec forms and return them as a seq."
  [{:keys [id] :as file}]
  (let [ctx (context id)]
    (doseq [[kwd [type def]] (:defs (s/conform ::schema/file file))]
      (let [ctx (if (= :main kwd) ctx (nest ctx (name kwd)))]
        (case type
          :primary (translate-primary-type-def ctx def)
          :field   (add-spec ctx (field-type-def->spec ctx def)))))
    @(:defs ctx)))

(defn- rkey-type->spec
  "The spec form for this record key type."
  [[rkey-type rkey-value]]
  (case rkey-type
    :tid     ::tid
    :nsid    ::nsid
    :literal `#{~rkey-value}
    :any     ::record-key))

(defmethod translate-primary-type-def "record"
  [ctx {:keys [key record]}]
  ;; ignore the record key in the validation (for now)
  (add-spec ctx (field-type-def->spec ctx record)))

(defn- add-params-spec
  [ctx parameters]
  (add-spec ctx (field-type-def->spec ctx parameters)))

(defn- add-body-specs
  [ctx {:keys [encoding schema]}]
  (cond-> [(add-spec (nest ctx "encoding")
                     `#(mime-type-pattern-match? ~encoding %))]
    schema
    (conj (add-spec (nest ctx "body")
                    (field-type-def->spec ctx schema)))))

(defn- add-request-spec
  [ctx {:keys [parameters input]}]
  (add-spec ctx (let [spec-keys (cond-> []
                                  parameters
                                  (conj (add-params-spec (nest ctx "params") parameters))

                                  input
                                  (concat (add-body-specs ctx input)))]
                  (if (seq spec-keys)
                    ;; we don't know if any of the parameters are required so
                    ;; we conform :params to an empty map and validate against the spec
                    `(s/and (s/conformer #(update % :params (fnil identity {})))
                            (s/keys :req-un ~spec-keys))
                    'any?))))

(defn- add-response-spec
  [ctx {:keys [output]}]
  (add-spec ctx (if output
                  `(s/keys :req-un ~(add-body-specs ctx output))
                  'any?)))

(defmethod translate-primary-type-def "query"
  [ctx def]
  (add-request-spec (nest ctx "request") def)
  (add-response-spec (nest ctx "response") def))

(defmethod translate-primary-type-def "procedure"
  [ctx def]
  (add-request-spec (nest ctx "request") def)
  (add-response-spec (nest ctx "response") def))

(defmethod translate-primary-type-def "subscription"
  [ctx {:keys [parameters message] :as def}]
  (add-request-spec (nest ctx "request") def)
  (let [ctx (nest ctx "message")]
    (add-spec ctx (if message
                    (field-type-def->spec ctx (:schema message))
                    'any?))))

;; Field Type Definitions

(defmethod field-type-def->spec "null"
  [ctx def]
  `nil?)

(defmethod field-type-def->spec "boolean"
  [ctx {:keys [default const]}]
  (let [specs (cond-> [`::data/boolean]
                (false? const) (conj `false?)
                (true? const)  (conj `true?))]
    `(s/and ~@specs)))

(defmethod field-type-def->spec "integer"
  [ctx {:keys [minimum maximum enum default const]}]
  (let [specs (cond-> [`::data/integer]
                minimum  (conj `#(<= ~minimum %))
                maximum  (conj `#(<= % ~maximum))
                enum     (conj `#{~@enum})
                const    (conj `#{~const}))]
    `(s/and ~@specs)))

(defmethod field-type-def->spec "string"
  [ctx {:keys [format maxLength minLength maxGraphemes minGraphemes
               knownValues enum default const]}]
  (let [specs (cond-> [`::data/string]
                format       (conj (keyword "atproto.lexicon" format))
                maxLength    (conj `#(<= (utf8-length %) ~maxLength))
                minLength    (conj `#(<= ~minLength (utf8-length %)))
                maxGraphemes (conj `#(<= (grapheme-length %) ~maxGraphemes))
                minGraphemes (conj `#(<= ~minGraphemes (grapheme-length %)))
                enum         (conj `#{~@enum})
                const        (conj `#{~const}))]
    `(s/and ~@specs)))

(defmethod field-type-def->spec "bytes"
  [ctx {:keys [minLength maxLength]}]
  (let [specs (cond-> [`::data/bytes]
                minLength (conj `#(<= ~minLength (count %)))
                maxLength (conj `#(<= (count %) ~maxLength)))]
    `(s/and ~@specs)))

(defmethod field-type-def->spec "cid-link"
  [ctx def]
  `::data/link)

(defmethod field-type-def->spec "blob"
  [ctx {:keys [accept maxSize]}]
  (let [specs (cond-> [`::data/blob]
                accept  (conj `#(accept-mime-type? ~accept (:mimeType %)))
                maxSize (conj `#(<= (:size %) ~maxSize)))]
    `(s/and ~@specs)))

(defmethod field-type-def->spec "array"
  [ctx {:keys [items minLength maxLength]}]
  (let [items-spec (field-type-def->spec ctx items)
        opts (cond-> {}
               minLength (assoc :min-count minLength)
               maxLength (assoc :max-count maxLength))]
    `(s/and ::data/array
            (s/coll-of ~items-spec ~opts))))

(defmethod field-type-def->spec "object"
  [ctx {:keys [properties required nullable]}]
  (let [required? (set (map keyword required))
        nullable? (set (map keyword nullable))
        keys (reduce (fn [keys [prop-key prop-type]]
                       (let [ctx (nest ctx (name prop-key))
                             spec (let [spec (field-type-def->spec ctx prop-type)]
                                    (if (nullable? prop-key)
                                      `(s/nilable ~spec)
                                      spec))]
                         (update keys
                                 (if (required? prop-key) :required :optional)
                                 conj
                                 (add-spec ctx spec))))
                     {:required []
                      :optional []}
                     properties)]
    `(s/and ::data/object
            (s/keys :req-un ~(:required keys)
                    :opt-un ~(:optional keys)))))

(defmethod field-type-def->spec "params"
  [ctx {:keys [required properties]}]
  (let [required? (set (map keyword required))
        keys (reduce (fn [keys [prop-key prop-type]]
                       (let [ctx (nest ctx (name prop-key))
                             spec (field-type-def->spec ctx prop-type)]
                         (update keys
                                 (if (required? prop-key) :required :optional)
                                 conj
                                 (add-spec ctx spec))))
                     {:required []
                      :optional []}
                     properties)]
    `(s/keys :req-un ~(:required keys)
             :opt-un ~(:optional keys))))

(defmethod field-type-def->spec "token"
  [ctx _]
  `(constantly false))

(defn resolve-ref
  "Resolve the Lexicon reference."
  [{:keys [nsid]} [ref-type ref]]
  (case ref-type
    :local-ref  (str nsid "#" ref)
    :global-ref ref))

(defn lex-uri->spec-key
  "The Spec key corresponding to this Lexicon schema URI."
  [s]
  (let [[nsid type-name] (str/split s #"#")]
    (spec-key (cond-> {:ns nsid}
                type-name (nest type-name)))))

(defmethod field-type-def->spec "ref"
  [ctx {:keys [ref]}]
  ;; We could simply return the spec key here but that would
  ;; require toposorting the specs before defining them.
  ;; I'm not even sure there can't be cycles in the Lexicon schemas.
  ;; Downside: we only detect missing specs at validation time.
  (let [spec-key (lex-uri->spec-key (resolve-ref ctx ref))]
    `(s/or ~spec-key ~spec-key)))

(defmulti object-spec (fn [object] (if (:$type object) :typed :untyped)))

(defmethod object-spec :typed [object]
  (if-let [spec (s/get-spec (lex-uri->spec-key (:$type object)))]
    (s/and ::data/object
           spec)
    ::data/object))

(defmethod object-spec :untyped [object]
  ::data/object)

(defmethod field-type-def->spec "union"
  [ctx {:keys [refs closed]}]
  (let [specs (cond-> [`(s/multi-spec object-spec identity)
                       `#(contains? % :$type)]
                closed (conj `#(contains? ~(set refs) (:$type %))))]
    `(s/and ~@specs)))

(defmethod field-type-def->spec "unknown"
  [ctx _]
  `(s/multi-spec object-spec identity))

;; -----------------------------------------------------------------------------
;; Loading
;; -----------------------------------------------------------------------------

(defn lexicon
  "Create a new Lexicon with the given schemas.

  A lexicon is a map: nsid -> schema."
  [schemas]
  (reduce (fn [lexicon schema]
            (if (s/valid? ::schema/file schema)
              (do
                (cast/event {:message (str "Adding schema to Lexicon: " (:id schema))})
                (assoc lexicon (:id schema) schema))
              (throw (ex-info (s/explain-str ::schema/file schema)
                              (s/explain-data ::schema/file schema)))))
          {}
          schemas))

#?(:clj
   (defn load-resources!
     "Load the Lexicon schemas at the resource path and return a Lexicon."
     [resource-path]
     (->> (io/resource resource-path)
          (io/file)
          (file-seq)
          (filter #(str/ends-with? (.getName %) ".json"))
          (map #(json/read-str (slurp %)))
          lexicon)))

(defn register-specs!
  "Register the specs from this lexicon."
  [lexicon]
  (doseq [schema (vals lexicon)]
    (eval
     `(do ~@(translate schema)))))

(defn type-def
  "The type definition for this Lexicon URL."
  [lexicon lex-uri]
  (let [[nsid type-name] (str/split lex-uri #"#")]
    (get-in lexicon [nsid :defs (keyword (or type-name "main"))])))

;; -----------------------------------------------------------------------------
;; Validation
;; -----------------------------------------------------------------------------

(defmulti record-spec (constantly nil))

(defmethod record-spec :default
  [{:keys [$type] :as record}]
  (lex-uri->spec-key $type))

(s/def ::record
  (s/multi-spec record-spec identity))

(defn request-spec-key
  [nsid]
  (spec-key (nest {:ns nsid} "request")))

(defn response-spec-key
  [nsid]
  (spec-key (nest {:ns nsid} "response")))

(defn message-spec-key
  [nsid]
  (spec-key (nest {:ns nsid} "message")))
