(ns atproto.identity
  "atproto uses two interrelated forms of identifiers: handles and DIDs.

  Handles are DNS names while DIDs are a W3C standard with multiple implementations which provide secure & stable IDs. AT Protocol supports the DID PLC and DID Web variants.

  See https://atproto.com/guides/identity"
  (:refer-clojure :exclude [resolve])
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.http :as http]
            [atproto.runtime.json :as json]
            [atproto.runtime.dns :as dns]
            [atproto.crypto :as crypto]
            [atproto.identity.cache :as cache]
            [atproto.identity.did-doc :as-alias did-doc]
            [atproto.identity.did-doc.verification-method :as-alias verification-method]
            [atproto.identity.did-doc.service :as-alias service]
            [atproto.lexicon :as lexicon]))

;; todo:
;; - parallelize handle resolution
;; - "productionize" resolution (error handling, timeout, retry...)

(def default-plc-url "https://plc.directory")

(defn- async-opts
  "The async-adapter keys of an options map (see i/platform-async).
  Other keys must be stripped before requesting the platform default."
  [opts]
  (select-keys opts [:channel :callback :promise]))

;; -----------------------------------------------------------------------------
;; DID
;; -----------------------------------------------------------------------------

(defn parse-did
  "Parse a valid DID and return a map with :method, :msid."
  [did]
  (when (string? did)
    (when-let [[_ method msid] (re-matches #"^did:([^:]+):(.+)$" did)]
      {:method method
       :msid msid})))

(defmulti ^:private did-method-spec
  "Method-specific validation of the DID."
  :method)

(s/def ::did
  (s/and ::lexicon/did
         (s/conformer #(or (parse-did %)
                           ::s/invalid))
         (s/multi-spec did-method-spec identity)
         (s/conformer #(str "did:" (:method %) ":" (:msid %)))))

;; -----------------------------------------------------------------------------
;; DID document spec & validation
;;
;; Ported from the reference didDocument zod schema
;; (packages/common-web/src/did-doc.ts:188-200).
;; -----------------------------------------------------------------------------

(s/def ::did-doc/id string?)
(s/def ::did-doc/alsoKnownAs (s/coll-of string?))

(s/def ::verification-method/id string?)
(s/def ::verification-method/type string?)
(s/def ::verification-method/controller string?)
(s/def ::verification-method/publicKeyMultibase string?)
(s/def ::verification-method/publicKeyJwk map?)

(s/def ::did-doc/verificationMethod
  (s/coll-of (s/keys :req-un [::verification-method/id
                              ::verification-method/type
                              ::verification-method/controller]
                     :opt-un [::verification-method/publicKeyMultibase
                              ::verification-method/publicKeyJwk])))

(s/def ::service/id string?)
(s/def ::service/type string?)
(s/def ::service/serviceEndpoint (s/or :url string? :map map?))

(s/def ::did-doc/service
  (s/coll-of (s/keys :req-un [::service/id
                              ::service/type
                              ::service/serviceEndpoint])))

(s/def ::did-doc
  (s/keys :req-un [::did-doc/id]
          :opt-un [::did-doc/alsoKnownAs
                   ::did-doc/verificationMethod
                   ::did-doc/service]))

(defn validate-did-doc
  "Validate a fetched document against ::did-doc and check that its :id
  matches the requested DID (packages/identity/src/did/base-resolver.ts:18-26).
  Returns the doc or {:error \"PoorlyFormattedDidDocument\" :did did}."
  [did doc]
  (if (and (s/valid? ::did-doc doc)
           (= did (:id doc)))
    doc
    {:error "PoorlyFormattedDidDocument"
     :message "DID document is malformed or does not match the requested DID."
     :did did}))

;; -----------------------------------------------------------------------------
;; DID resolution (per-method fetching)
;; -----------------------------------------------------------------------------

(defmulti ^:private fetch-did-doc
  "Method-specific fetching of the DID document."
  (fn [did opts cb] (:method (parse-did did))))

(defmethod fetch-did-doc :default
  [did opts cb]
  (cb {:error "UnsupportedDidMethod"
       :message (str "Unsupported DID method: " (pr-str did))
       :did did}))

(defmethod did-method-spec "plc"
  [_]
  (fn [{:keys [msid]}]
    (and (= 24 (count msid))
         (re-matches #"[a-z2-7]+" msid))))

(defmethod fetch-did-doc "plc"
  [did opts cb]
  (i/execute {::i/request {:method :get
                           :url (str (or (:plc-url opts) default-plc-url) "/" did)
                           :follow-redirects false
                           :headers {:accept "application/did+ld+json,application/json"}}
              ::i/queue [json/client-interceptor
                         http/client-interceptor]}
             :callback
             (fn [{:keys [error status body] :as resp}]
               (cb (cond
                     error                  resp
                     (http/success? status) {:did-doc body}
                     (= status 404)         {:error "DidNotFound" :did did}
                     :else                  (http/error-map resp))))))

(defn- web-did-msid->url
  "Transform an atproto Web DID msid into a URL string."
  [msid]
  (let [hostname (str/replace msid "%3A" ":")
        scheme (if (str/starts-with? hostname "localhost")
                 "http"
                 "https")]
    (str scheme "://" hostname "/")))

(defn web-did->url
  "Transform an atproto Web DID into a URL string."
  [did]
  (web-did-msid->url (:msid (parse-did did))))

(defn url->web-did
  "Take a DID URL string and return the atproto DID web."
  [url]
  (let [{:keys [host port]} (http/parse-url url)]
    (str "did:web:" host (when port
                           (str "%3A" port)))))

(defmethod did-method-spec "web"
  [_]
  (fn [{:keys [msid]}]
    (and
     ;; Ensure we can generate well formed URL from this DID
     (s/valid? ::http/url (web-did-msid->url msid))
     ;; Atproto does not allow path components in Web DIDs
     (not (str/index-of msid \:))
     ;; Atproto does not allow port numbers in Web DIDs, except for localhost
     (or (str/starts-with? msid "localhost")
         (not (str/index-of msid "%3A"))))))

(defmethod fetch-did-doc "web"
  [did opts cb]
  ;; ref: packages/identity/src/did/web-resolver.ts:19-50
  (let [msid (:msid (parse-did did))]
    (cond
      (str/blank? msid)
      (cb {:error "PoorlyFormattedDid" :did did})

      ;; atproto does not support path-form did:web
      (str/index-of msid \:)
      (cb {:error "UnsupportedDidWebPath" :did did})

      :else
      (i/execute {::i/request {:method :get
                               :url (str (web-did-msid->url msid) ".well-known/did.json")
                               :follow-redirects false
                               :headers {:accept "application/did+ld+json,application/json"}}
                  ::i/queue [json/client-interceptor
                             http/client-interceptor]}
                 :callback
                 (fn [{:keys [error status body] :as resp}]
                   (cb (cond
                         error                  resp
                         (http/success? status) {:did-doc body}
                         :else                  {:error "DidNotFound" :did did})))))))

(defn- fetch-validated-did-doc
  "Fetch the DID document and validate it against the requested DID."
  [did opts cb]
  (fetch-did-doc did opts
                 (fn [{:keys [error did-doc] :as resp}]
                   (if error
                     (cb resp)
                     (let [validated (validate-did-doc did did-doc)]
                       (if (:error validated)
                         (cb validated)
                         (cb {:did-doc validated})))))))

(defn- fetch-and-cache-did-doc
  "Fetch + validate the DID document, updating the cache: success
  populates it, a positive not-found evicts (or stores a negative marker
  when the policy enables it; ref base-resolver.ts:55-62)."
  [did {:keys [cache] :as opts} policy cb]
  (fetch-validated-did-doc
   did opts
   (fn [{:keys [error did-doc] :as resp}]
     (when cache
       (cond
         did-doc                  (cache/store cache did did-doc)
         (= error "DidNotFound")  (cache/store-negative cache policy did)))
     (cb resp))))

(defn resolve-did
  "Resolve a DID to a validated DID document.

  Options:
    :cache          atproto.identity.cache/Cache impl (no caching when absent)
    :cache-policy   merged over atproto.identity.cache/default-policy
    :force-refresh  bypass the cache read (still writes on success)
    :plc-url        PLC directory base URL (default \"https://plc.directory\")

  Caching semantics (ref packages/identity/src/did/base-resolver.ts:42-64):
  fresh hit -> cached doc; stale hit -> cached doc + async revalidate;
  expired/miss -> fetch; DidNotFound -> evict entry (or negative marker
  when :negative-ttl set); success -> store.

  Async; yields {:did-doc doc} or {:error \"DidNotFound\"|
  \"UnsupportedDidMethod\"|\"UnsupportedDidWebPath\"|
  \"PoorlyFormattedDidDocument\"|... :message ...}.

  Does not bi-directionally verify handle."
  [did & {:keys [cache cache-policy force-refresh] :as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))
        policy (merge cache/default-policy cache-policy)
        hit (when (and cache (not force-refresh))
              (cache/check cache policy did))]
    (cond
      (and hit (not (:expired? hit)) (:negative? hit))
      (cb {:error "DidNotFound" :did did})

      (and hit (not (:expired? hit)))
      (do (when (:stale? hit)
            ;; stale-while-revalidate: serve the cached doc, refresh behind
            (fetch-and-cache-did-doc did opts policy (fn [_])))
          (cb {:did-doc (:val hit)}))

      :else
      (fetch-and-cache-did-doc did opts policy cb))
    val))

;; -----------------------------------------------------------------------------
;; Handle
;; -----------------------------------------------------------------------------

(s/def ::handle
  ::lexicon/handle)

(defn- resolve-handle-with-dns
  [handle cb]
  (let [hostname (str "_atproto." handle)]
    (if (< 253 (count hostname))
      (cb {:error "HandleTooLong"})
      (i/execute {::i/request {:hostname hostname
                               :type "txt"}
                  ::i/queue [dns/interceptor]}
                 :callback
                 (fn [{:keys [error values] :as resp}]
                   (if error
                     (cb {:error "HandleNotFound"})
                     (let [dids (->> values
                                     (map #(some->> %
                                                    (re-matches #"^did=(.+)$")
                                                    (second)))
                                     (remove nil?)
                                     (seq))]
                       (cb (if (or (empty? dids)
                                   (< 1 (count dids)))
                             {:error "HandleNotFound"}
                             {:did (first dids)})))))))))

(defn- resolve-handle-with-https
  [handle cb]
  (i/execute {::i/request {:method :get
                           :timeout 3000
                           :url (str "https://" handle "/.well-known/atproto-did")}
              ::i/queue [http/client-interceptor]}
             :callback
             (fn [{:keys [error status body] :as resp}]
               (cb (if (http/success? status)
                     ;; first line only, and it must be a supported DID
                     ;; (ref: packages/identity/src/handle/index.ts:52-57)
                     (let [did (some-> body
                                       str
                                       str/split-lines
                                       first
                                       str/trim)]
                       (if (s/valid? ::did did)
                         {:did did}
                         {:error "HandleNotFound"}))
                     {:error "HandleNotFound"})))))

(defn resolve-handle
  "Resolves an atproto handle (hostname) to a DID.

  Does not necessarily bi-directionally verify against the the DID document."
  [handle & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (resolve-handle-with-dns handle
                             (fn [{:keys [error] :as resp}]
                               (if error
                                 (resolve-handle-with-https handle cb)
                                 (cb resp))))
    val))

;; -----------------------------------------------------------------------------
;; DID Document accessors
;; -----------------------------------------------------------------------------

(defn- find-item-by-id
  "Find the item under items-key (:service or :verificationMethod) whose
  :id is exactly `#fragment` or `{doc-id}#fragment`
  (strict matching, ref packages/common-web/src/did-doc.ts:109-140)."
  [did-doc items-key fragment]
  (let [doc-id (:id did-doc)]
    (->> (get did-doc items-key)
         (filter (fn [{item-id :id}]
                   (and (string? item-id)
                        (if (str/starts-with? item-id "#")
                          (= item-id fragment)
                          (= item-id (str doc-id fragment))))))
         (first))))

(defn- valid-service-url
  "The URL string if it is a parseable http(s) URL, else nil
  (SSRF guard, ref packages/common-web/src/did-doc.ts:146-156)."
  [url]
  (when (and (string? url)
             (or (str/starts-with? url "http://")
                 (str/starts-with? url "https://"))
             (http/parse-url url))
    url))

(defn did-doc-pds
  "The atproto personal data server declared in this DID document."
  [did-doc]
  (let [{service-type :type endpoint :serviceEndpoint}
        (find-item-by-id did-doc :service "#atproto_pds")]
    (when (= "AtprotoPersonalDataServer" service-type)
      (valid-service-url endpoint))))

(defn did-doc-handles
  "The atproto handles defined in this document."
  [did-doc]
  (->> did-doc
       :alsoKnownAs
       (map #(second (re-matches #"^at://(.+)$" %)))
       (remove nil?)))

(defn did-doc-also-known-as?
  "Whether the handle is defined in the DID document."
  [did-doc handle]
  (= handle (first (did-doc-handles did-doc))))

(defn did-doc-signing-key
  "The verification material registered for the given key id (default
  \"atproto\"). Returns {:type ... :public-key-multibase ...} or nil.
  ref: packages/common-web/src/did-doc.ts:39-58 (strict id matching)."
  [did-doc & {:keys [key-id] :or {key-id "atproto"}}]
  (let [{:keys [type publicKeyMultibase]}
        (find-item-by-id did-doc :verificationMethod (str "#" key-id))]
    (when (and type publicKeyMultibase)
      {:type type
       :public-key-multibase publicKeyMultibase})))

(defn- ok
  "x unless it is an SDK error map, else nil."
  [x]
  (when-not (and (map? x) (:error x))
    x))

(defn did-doc-signing-did-key
  "did:key string for the document's atproto signing key, or nil.

  Multikey verification methods convert via parse-multikey; the legacy
  EcdsaSecp256k1/r1VerificationKey2019 types decode the multibase bytes
  with the curve implied by the type.
  ref: packages/identity/src/did/atproto-data.ts:20-41"
  [did-doc]
  (when-let [{key-type :type multibase :public-key-multibase}
             (did-doc-signing-key did-doc)]
    (case key-type
      "Multikey"
      (let [parsed (ok (crypto/parse-multikey multibase))]
        (when parsed
          (ok (crypto/pubkey->did-key (:alg parsed) (:bytes parsed)))))

      "EcdsaSecp256r1VerificationKey2019"
      (when-let [b (ok (crypto/multibase->bytes multibase))]
        (ok (crypto/pubkey->did-key crypto/p256-jwt-alg b)))

      "EcdsaSecp256k1VerificationKey2019"
      (when-let [b (ok (crypto/multibase->bytes multibase))]
        (ok (crypto/pubkey->did-key crypto/k256-jwt-alg b)))

      nil)))

;; -----------------------------------------------------------------------------
;; Identity
;; -----------------------------------------------------------------------------

(defn- verified-identity
  [{:keys [did-doc handle]}]
  (if (and handle
           (not (did-doc-also-known-as? did-doc handle)))
    {:error "HandleNotFound"}
    (let [handle (or handle
                     (first (did-doc-handles did-doc)))]
      (cond-> {:did (:id did-doc)
               :did-doc did-doc
               :pds (did-doc-pds did-doc)}
        handle (assoc :handle handle)))))

(defn- resolve-with-did
  [did opts cb]
  (resolve-did did
               (assoc opts
                      :callback
                      (fn [{:keys [error] :as resp}]
                        (if error
                          (cb resp)
                          (cb (verified-identity resp)))))))

(defn- resolve-with-handle
  [handle opts cb]
  (resolve-handle handle
                  :callback
                  (fn [{:keys [error did] :as resp}]
                    (if error
                      (cb resp)
                      (resolve-did did
                                   (assoc opts
                                          :callback
                                          (fn [{:keys [error] :as resp}]
                                            (if error
                                              (cb resp)
                                              (cb (verified-identity
                                                   (assoc resp :handle handle)))))))))))

(defn resolve-identity
  "Resolves an identity (DID or Handle) to a full identity (DID document and verified handle).

  Takes the same :cache/:cache-policy/:force-refresh/:plc-url options
  as resolve-did.

  If successful, the response map will contain the following keys:
  :did     The identity's DID
  :handle  The handle, if present in the DID doc.
  :did-doc  The identity's DID document"
  [at-identifier & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))
        opts (dissoc opts :channel :callback :promise)]
    (cond
      (s/valid? ::did at-identifier)    (resolve-with-did at-identifier opts cb)
      (s/valid? ::handle at-identifier) (resolve-with-handle at-identifier opts cb)
      :else (cb {:error "InvalidAtIdentifier"
                 :message (str "Not a valid DID or handle: " (pr-str at-identifier))}))
    val))
