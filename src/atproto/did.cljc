(ns atproto.did
  "Decentralized identifiers (DIDs) are persistent, long-term identifiers for atproto accounts.

  DID = \"did:<method>:<method-specific-identifier>\"

  DIDs resolve to DID documents that contain information associated with the DID.

  See https://atproto.com/specs/did."
  (:refer-clojure :exclude [resolve])
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.http :as http]
            [atproto.runtime.json :as json]))

;; Helpers

(defn- char-in [c start end] (<= (int start) (int c) (int end)))
(defn- digit? [c] (char-in c \0 \9))
(defn- hex-char? [c] (or (digit? c) (char-in c \A \F)))
(defn- method-char? [c] (or (digit? c) (char-in c \a \z)))
(defn- base32-char? [c] (or (char-in c \a \z) (char-in c \2 \7)))

;; Parsing & validation

(s/def ::scheme #{"did"})

(defn method?
  "Whether the string is a well-formed method name.

  See https://www.w3.org/TR/did-1.0/#did-syntax"
  [s]
  (and (not (str/blank? s))
       (every? method-char? s)))

(s/def ::method (s/and string? method?))

(defn msid?
  "Whether the string is a well-formed method-specific identifier.

  See https://www.w3.org/TR/did-1.0/#did-syntax"
  [msid]
  (loop [[c & xs] (seq msid)
         i 0]
    (cond
      ;; EOS -> success!
      (not c)
      true

      ;; regular character
      (or (char-in c \a \z)
          (char-in c \A \Z)
          (char-in c \0 \9)
          (#{\. \- \_} c))
      (recur xs (inc i))

      ;; colon is acceptable if not at the end
      (and (= \: c)
           (not (= (inc i) (count msid))))
      (recur xs (inc i))

      ;; percent-encoded char
      (and (= \% c)
           (first xs) (hex-char? (first xs))
           (second xs) (hex-char? (second xs)))
      (recur (drop 2 xs) (+ i 3))

      ;; Otherwise invalid
      :else false)))

(s/def ::msid (s/and string? msid?))

(defn parse
  "Parse the input string into a map with :scheme, :method, and :msid."
  [input]
  (when-let [first-colon-idx (str/index-of input \:)]
    (when-let [second-colon-idx (str/index-of input \: (inc first-colon-idx))]
      {:scheme (subs input 0 first-colon-idx)
       :method (subs input (inc first-colon-idx) second-colon-idx)
       :msid (subs input (inc second-colon-idx))})))

(s/def ::did
  (s/and string?
         (s/conformer #(or (parse %) ::s/invalid))
         (s/keys :req-un [::scheme ::method ::msid])))

(defmulti method-spec
  "Method-specific validation of the atproto DID."
  :method)

(s/def ::at-did
  (s/and ::did
         (s/multi-spec method-spec :method)))

(defn conform
  "Conform the input into a atproto DID string.

  Return :atproto.did/invalid if the input is not a valid atproto DID string."
  [input]
  (let [atproto-did-map (s/conform ::at-did input)]
    (if (= atproto-did-map ::s/invalid)
      ::invalid
      (let [{:keys [scheme method msid]} atproto-did-map]
        (str scheme ":" method ":" msid)))))

(defn valid?
  "Whether the input is a valid atproto DID string."
  [input]
  (not (= ::invalid (conform input))))

(defmulti fetch-doc
  "Method-specific fetching of this DID's document."
  (fn [did cb] (:method (parse did))))

(defn resolve
  "The DID document for this DID."
  [input & {:as opts}]
  (let [[cb val] (i/platform-async opts)]
    (if (valid? input)
      (fetch-doc input (fn [{:keys [error doc] :as resp}]
                         (if error
                           (cb resp)
                           (cb {:did input
                                :doc doc}))))
      (cb {:error (str "Cannot resolve invalid DID: " input)}))
    val))

;; PLC method

(defmethod method-spec "plc"
  [_]
  (fn [{:keys [msid]}]
    (and (= 24 (count msid))
         (every? base32-char? msid))))

(defmethod fetch-doc "plc"
  [did cb]
  (i/execute {::i/request {:method :get
                           :url (str "https://plc.directory/" did)}
              ::i/queue [json/interceptor http/interceptor]}
             :callback
             (fn [{:keys [error status body] :as resp}]
               (cb (cond
                     error                  resp
                     (http/success? status) {:doc body}
                     :else                  (http/error-map resp))))))

;; Web method

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
  (when (valid? did)
    (web-did-msid->url (:msid (parse did)))))

(defn url->web-did
  "Take a DID URL string and return the atproto DID web."
  [url]
  (let [{:keys [host port]} (http/parse-url url)]
    (str "did:web:" host (when port
                           (str "%3A" port)))))

(defmethod method-spec "web"
  [_]
  (fn [{:keys [msid]}]
    (and
     ;; Ensure we can generate well formed URL from this DID
     (s/valid? :http/url (web-did-msid->url msid))
     ;; Atproto does not allow path components in Web DIDs
     (not (str/index-of msid \:))
     ;; Atproto does not allow port numbers in Web DIDs, except for localhost
     (or (str/starts-with? msid "localhost")
         (not (str/index-of msid "%3A"))))))

(defmethod fetch-doc "web"
  [did cb]
  (cb {:error "Not implemented"
       :did did}))

;; DID document

(defn pds
  "The atproto personal data server declared in this DID document."
  [doc]
  (some->> doc
           :service
           (filter (fn [service]
                     (and (str/ends-with? (:id service) "#atproto_pds")
                          (= "AtprotoPersonalDataServer" (:type service)))))
           (first)
           :serviceEndpoint))

(defn handles
  "The atproto handles defined in this document."
  [doc]
  (->> doc
       :alsoKnownAs
       (map #(second (re-matches #"^at://(.+)$" %)))
       (remove nil?)))

(defn also-known-as?
  "Whether the handle is defined in the DID document."
  [doc handle]
  (= handle (first (handles doc))))
