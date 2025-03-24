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
            [atproto.lexicon :as lexicon]))

;; todo:
;; - DID document validation
;; - parallelize handle resolution
;; - "productionize" resolution (error handling, timeout, retry...)

;; -----------------------------------------------------------------------------
;; DID
;; -----------------------------------------------------------------------------

(defn parse-did
  "Parse a valid DID and return a map with :method, :msid."
  [did]
  (when-let [[_ method msid] (re-matches #"^did:([^:]+):(.+)$" did)]
    {:method method
     :msid msid}))

(defmulti ^:private did-method-spec
  "Method-specific validation of the DID."
  :method)

(s/def ::did
  (s/and ::lexicon/did
         (s/conformer #(or (parse-did %)
                           ::s/invalid))
         (s/multi-spec did-method-spec identity)
         (s/conformer #(str "did:" (:method %) ":" (:msid %)))))

(defmulti ^:private fetch-did-doc
  "Method-specific fetching of the DID document."
  (fn [did cb] (:method (parse-did did))))

(defn resolve-did
  "Resolves DID to DID document.

  Does not bi-directionally verify handle."
  [did & {:as opts}]
  (let [[cb val] (i/platform-async opts)]
    (fetch-did-doc did cb)
    val))

(defmethod did-method-spec "plc"
  [_]
  (fn [{:keys [msid]}]
    (and (= 24 (count msid))
         (re-matches #"[a-z2-7]+" msid))))

(defmethod fetch-did-doc "plc"
  [did cb]
  (i/execute {::i/request {:method :get
                           :url (str "https://plc.directory/" did)
                           :follow-redirects false
                           :headers {:accept "application/did+ld+json,application/json"}}
              ::i/queue [json/interceptor
                         http/interceptor]}
             :callback
             (fn [{:keys [error status body] :as resp}]
               (cb (cond
                     (http/success? status) {:did-doc body}
                     (= status 404)         {:error "DidNotFound"}
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
  [did cb]
  (cb {:error "NotImplemented"}))

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
              ::i/queue [json/interceptor
                         http/interceptor]}
             :callback
             (fn [{:keys [error status body] :as resp}]
               (cb (if (http/success? status)
                     {:did (str/trim body)}
                     {:error "HandleNotFound"})))))

(defn resolve-handle
  "Resolves an atproto handle (hostname) to a DID.

  Does not necessarily bi-directionally verify against the the DID document."
  [handle & {:as opts}]
  (let [[cb val] (i/platform-async opts)]
    (resolve-handle-with-dns handle
                             (fn [{:keys [error] :as resp}]
                               (if error
                                 (resolve-handle-with-https handle cb)
                                 (cb resp))))
    val))

;; -----------------------------------------------------------------------------
;; DID Document
;; -----------------------------------------------------------------------------

(defn did-doc-pds
  "The atproto personal data server declared in this DID document."
  [did-doc]
  (some->> did-doc
           :service
           (filter (fn [service]
                     (and (str/ends-with? (:id service) "#atproto_pds")
                          (= "AtprotoPersonalDataServer" (:type service)))))
           (first)
           :serviceEndpoint))

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
               :did-doc did-doc}
        handle (assoc :handle handle)))))

(defn- resolve-with-did
  [did cb]
  (resolve-did did
               :callback
               (fn [{:keys [error] :as resp}]
                 (if error
                   (cb resp)
                   (cb (verified-identity resp))))))

(defn- resolve-with-handle
  [handle cb]
  (resolve-handle handle
                  :callback
                  (fn [{:keys [error did] :as resp}]
                    (if error
                      (cb resp)
                      (resolve-did did
                                   :callback
                                   (fn [{:keys [error did-doc] :as resp}]
                                     (if error
                                       (cb resp)
                                       (cb (verified-identity resp)))))))))

(defn resolve-identity
  "Resolves an identity (DID or Handle) to a full identity (DID document and verified handle).

  If successful, the response map will contain the following keys:
  :did     The identity's DID
  :handle  The handle, if present in the DID doc.
  :did-doc  The identity's DID document"
  [at-identifier & {:as opts}]
  (let [[cb val] (i/platform-async opts)]
    (cond
      (s/valid? ::did at-identifier)    (resolve-with-did at-identifier cb)
      (s/valid? ::handle at-identifier) (resolve-with-handle at-identifier cb))
    val))
