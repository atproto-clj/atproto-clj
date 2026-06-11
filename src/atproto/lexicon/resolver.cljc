(ns atproto.lexicon.resolver
  "Network resolution of Lexicon schemas from NSIDs.

  Resolution flow: NSID -> authority DID (`_lexicon` DNS TXT record, or
  `:did-authority` override) -> DID document -> PDS endpoint ->
  `com.atproto.lexicon.schema` record at
  `at://<did>/com.atproto.lexicon.schema/<nsid>` -> validated Lexicon
  document.

  Trust model: by default records are fetched with
  `com.atproto.repo.getRecord` and trusted as returned by the PDS. With
  `:verify? true`, records are fetched as commit proofs via
  `com.atproto.sync.getRecord` and cryptographically verified (CAR read,
  commit signature against the DID document's #atproto signing key, MST
  inclusion walk) before use, like the reference resolvers.

  See https://atproto.com/specs/lexicon#lexicon-publication-and-resolution"
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [clojure.walk :as walk]
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.dns :as dns]
            [atproto.runtime.cast :as cast]
            [atproto.runtime.bytes :as bytes]
            [atproto.data :as data]
            [atproto.crypto :as crypto]
            [atproto.identity :as identity]
            [atproto.lexicon :as lexicon]
            [atproto.lexicon.schema :as-alias schema]
            [atproto.repo.sync :as repo-sync]
            [atproto.xrpc.client :as xrpc]
            #?@(:clj [[clojure.java.io :as io]
                      [clojure.edn :as edn]
                      [clojure.pprint :as pprint]
                      [charred.api :as charred]])))

(def lexicon-record-collection "com.atproto.lexicon.schema")

(defn- txt-record-value
  "Normalize a DNS TXT record value.

  Long TXT records are chunked into multiple character-strings which some
  resolvers return as a single space-separated sequence of quoted strings;
  concatenate the chunks (the reference implementation joins chunks the same
  way)."
  [v]
  (if (re-matches #"\"[^\"]*\"(?:\s+\"[^\"]*\")*" v)
    (->> (re-seq #"\"([^\"]*)\"" v)
         (map second)
         (apply str))
    v))

(defn resolve-nsid-authority
  "Resolve the DID with authority over this NSID via the `_lexicon` DNS TXT record.

  Looks up TXT records for `_lexicon.<domain-authority>` (e.g.
  `_lexicon.feed.bsky.app` for `app.bsky.feed.post`). Exactly one `did=`
  record must exist and contain a syntactically valid DID.

  Async (callback/promise/channel per atproto.runtime.interceptor/platform-async).
  Success: {:did \"did:plc:...\"}
  Errors:  {:error \"LexiconAuthorityNotFound\" :message ... :nsid ...}
           {:error \"InvalidNsid\" :message ...}"
  [nsid & {:as opts}]
  (let [[cb val] (i/platform-async (select-keys opts [:callback :promise :channel]))]
    (if-let [{:keys [authority]} (lexicon/parse-nsid nsid)]
      (let [hostname (str "_lexicon." authority)]
        (if (< 253 (count hostname))
          (cb {:error "InvalidNsid"
               :message (str "Cannot resolve NSID, hostname too long: " hostname)
               :nsid nsid})
          (i/execute {::i/request {:hostname hostname
                                   :type "txt"}
                      ::i/queue [dns/interceptor]}
                     :callback
                     (fn [{:keys [error values] :as resp}]
                       (if error
                         (do
                           (cast/dev {:message "Lexicon authority DNS lookup failed"
                                      :hostname hostname
                                      :response resp})
                           (cb {:error "LexiconAuthorityNotFound"
                                :message (str "No _lexicon DNS TXT record found for " hostname ".")
                                :nsid nsid}))
                         (let [dids (->> values
                                         (map txt-record-value)
                                         (keep #(second (re-matches #"^did=(.+)$" %))))]
                           (cond
                             (empty? dids)
                             (cb {:error "LexiconAuthorityNotFound"
                                  :message (str "No did= TXT record found at " hostname ".")
                                  :nsid nsid})

                             (< 1 (count dids))
                             (cb {:error "LexiconAuthorityNotFound"
                                  :message (str "Multiple did= TXT records found at " hostname
                                                "; exactly one is required.")
                                  :nsid nsid})

                             (not (s/valid? ::lexicon/did (first dids)))
                             (cb {:error "LexiconAuthorityNotFound"
                                  :message (str "Invalid DID in the " hostname
                                                " TXT record: " (first dids))
                                  :nsid nsid})

                             :else
                             (cb {:did (first dids)}))))))))
      (cb {:error "InvalidNsid"
           :message (str "Invalid NSID: " nsid)}))
    val))

(defn- validate-lexicon-record
  "Validate the fetched lexicon schema record.

  Return the success map for `resolve-nsid`, or an error map."
  [nsid did {:keys [uri cid value]}]
  (cond
    (and (:$type value)
         (not= lexicon-record-collection (:$type value)))
    {:error "InvalidLexiconDocument"
     :message (str "Record is not a " lexicon-record-collection
                   " record: " (:$type value))
     :nsid nsid}

    (not (s/valid? ::schema/file value))
    {:error "InvalidLexiconDocument"
     :message (str "The record at " uri " is not a valid Lexicon document.")
     :nsid nsid
     :explain-data (s/explain-data ::schema/file value)}

    (not= nsid (:id value))
    {:error "LexiconNsidMismatch"
     :message (str "The Lexicon document id (" (:id value)
                   ") does not match the resolved NSID (" nsid ").")
     :nsid nsid
     :id (:id value)}

    :else
    (cond-> {:nsid nsid
             :did did
             :uri (or uri (str "at://" did "/" lexicon-record-collection "/" nsid))
             :lexicon value}
      cid (assoc :cid cid))))

(defn- did-doc-signing-did-key
  "The did:key string for the DID document's #atproto verification method,
  or nil.

  Mirrors the reference's getKey/getDidKeyFromMultibase
  (packages/identity/src/did/atproto-data.ts): Multikey verification methods
  are parsed as multikeys; the legacy EcdsaSecp256r1VerificationKey2019 /
  EcdsaSecp256k1VerificationKey2019 types carry a multibase-encoded public
  key. To be superseded by WS-06's atproto.identity helper."
  [did-doc]
  (when-let [{:keys [type publicKeyMultibase]}
             (->> (:verificationMethod did-doc)
                  (filter #(some-> (:id %) (str/ends-with? "#atproto")))
                  (first))]
    (when publicKeyMultibase
      (let [did-key
            (case type
              "Multikey"
              (let [{:keys [alg bytes] :as parsed} (crypto/parse-multikey
                                                    publicKeyMultibase)]
                (when-not (:error parsed)
                  (crypto/pubkey->did-key alg bytes)))

              "EcdsaSecp256r1VerificationKey2019"
              (let [key-bytes (crypto/multibase->bytes publicKeyMultibase)]
                (when-not (:error key-bytes)
                  (crypto/pubkey->did-key "ES256" key-bytes)))

              "EcdsaSecp256k1VerificationKey2019"
              (let [key-bytes (crypto/multibase->bytes publicKeyMultibase)]
                (when-not (:error key-bytes)
                  (crypto/pubkey->did-key "ES256K" key-bytes)))

              nil)]
        (when (string? did-key)
          did-key)))))

(defn- car-body->bytes
  "Normalize a com.atproto.sync.getRecord response body to bytes (the JVM
  HTTP client yields an InputStream for non-text content types)."
  [body]
  #?(:clj (cond
            (bytes/bytes? body) body
            (instance? java.io.InputStream body) (.readAllBytes ^java.io.InputStream body)
            :else body)
     :cljs body))

(defn- fetch-lexicon-unverified
  "Fetch the lexicon schema record with com.atproto.repo.getRecord (no
  commit proof; the PDS is trusted) and validate it."
  [nsid did pds cb]
  (xrpc/query (xrpc/init {:service pds})
              {:nsid "com.atproto.repo.getRecord"
               :params {:repo did
                        :collection lexicon-record-collection
                        :rkey nsid}}
              :callback
              (fn [{:keys [error] :as resp}]
                (if error
                  (do
                    (cast/dev {:message "Lexicon record fetch failed"
                               :nsid nsid
                               :did did
                               :response resp})
                    (cb {:error "LexiconResolutionError"
                         :message (str "Could not fetch the lexicon record for "
                                       nsid " from " pds
                                       ": " (or (:message resp) error))
                         :nsid nsid
                         :did did}))
                  (cb (validate-lexicon-record nsid did resp))))))

(defn- fetch-lexicon-verified
  "Fetch the lexicon schema record as a com.atproto.sync.getRecord commit
  proof, verify it (commit signature against the authority's signing key +
  MST inclusion), and validate the proven record."
  [nsid did pds did-key cb]
  (xrpc/query (xrpc/init {:service pds})
              {:nsid "com.atproto.sync.getRecord"
               :params {:did did
                        :collection lexicon-record-collection
                        :rkey nsid}}
              :callback
              (fn [resp]
                (if (and (map? resp) (:error resp))
                  (do
                    (cast/dev {:message "Lexicon record proof fetch failed"
                               :nsid nsid
                               :did did
                               :response resp})
                    (cb {:error "LexiconResolutionError"
                         :message (str "Could not fetch the lexicon record proof for "
                                       nsid " from " pds
                                       ": " (or (:message resp) (:error resp)))
                         :nsid nsid
                         :did did}))
                  (repo-sync/verify-records
                   (car-body->bytes resp) did did-key
                   :callback
                   (fn [records]
                     (if (and (map? records) (:error records))
                       (cb {:error "LexiconVerificationError"
                            :message (str "Could not verify the lexicon record proof for "
                                          nsid ": " (or (:message records)
                                                        (:error records)))
                            :nsid nsid
                            :did did
                            :cause records})
                       (if-let [{:keys [cid value]}
                                (->> records
                                     (filter #(and (= lexicon-record-collection
                                                      (:collection %))
                                                   (= nsid (:rkey %))))
                                     (first))]
                         (cb (validate-lexicon-record
                              nsid did
                              {:uri (str "at://" did "/"
                                         lexicon-record-collection "/" nsid)
                               :cid (data/format-cid cid)
                               :value value}))
                         (cb {:error "LexiconVerificationError"
                              :message (str "The record proof for " nsid
                                            " does not include the lexicon record.")
                              :nsid nsid
                              :did did})))))))))

(defn- fetch-lexicon
  "Fetch the lexicon schema record from the authority DID's PDS and validate it."
  [nsid did {:keys [verify?]} cb]
  (identity/resolve-did
   did
   :callback
   (fn [{:keys [error did-doc] :as resp}]
     (if error
       (cb {:error "LexiconResolutionError"
            :message (str "Could not resolve the authority DID " did
                          ": " (or (:message resp) error))
            :nsid nsid
            :did did})
       (let [pds (identity/did-doc-pds did-doc)]
         (cond
           (not pds)
           (cb {:error "LexiconResolutionError"
                :message (str "The DID document for " did " has no PDS endpoint.")
                :nsid nsid
                :did did})

           (not verify?)
           (fetch-lexicon-unverified nsid did pds cb)

           :else
           (if-let [did-key (did-doc-signing-did-key did-doc)]
             (fetch-lexicon-verified nsid did pds did-key cb)
             (cb {:error "LexiconResolutionError"
                  :message (str "The DID document for " did
                                " has no usable #atproto signing key.")
                  :nsid nsid
                  :did did}))))))))

(defn memory-cache
  "A simple atom-backed cache for resolve-nsid. Optional :ttl-ms."
  [& {:keys [ttl-ms]}]
  {:ttl-ms ttl-ms
   :data (atom {})})

(defn- now-ms
  []
  #?(:clj (System/currentTimeMillis)
     :cljs (.now js/Date)))

(defn- cache-lookup
  [{:keys [ttl-ms data]} nsid]
  (when-let [{:keys [val at]} (get @data nsid)]
    (when (or (nil? ttl-ms)
              (< (- (now-ms) at) ttl-ms))
      val)))

(defn- cache-store!
  [{:keys [data]} nsid val]
  (swap! data assoc nsid {:val val :at (now-ms)}))

(defn resolve-nsid
  "Resolve the NSID to its published Lexicon schema document.

  Steps: authority DID (DNS, or :did-authority override) -> resolve DID doc
  (atproto.identity/resolve-did) -> PDS endpoint (identity/did-doc-pds)
  -> com.atproto.repo.getRecord {:repo did
                                 :collection \"com.atproto.lexicon.schema\"
                                 :rkey nsid}
  -> validate (:value response) against :atproto.lexicon.schema/file,
     check (= (:id doc) nsid).

  Options:
  :did-authority  Skip DNS and use this DID as the authority.
  :verify?        Fetch the record as a com.atproto.sync.getRecord commit
                  proof and cryptographically verify it (commit signature
                  against the DID document's #atproto signing key + MST
                  inclusion) instead of trusting the PDS.
  :cache          A cache from `memory-cache`; a hit short-circuits the network.
  :force-refresh  Bypass and repopulate the cache.

  Async (callback/promise/channel per atproto.runtime.interceptor/platform-async).
  Success: {:nsid nsid
            :did \"did:...\"        ; authority
            :uri \"at://did:.../com.atproto.lexicon.schema/<nsid>\"
            :cid \"bafy...\"        ; from the getRecord response/proof, when present
            :lexicon {...}}         ; the schema document (Clojure map)
  Errors:  {:error \"InvalidNsid\" :message ...}
           {:error \"LexiconAuthorityNotFound\" ...}
           {:error \"LexiconResolutionError\" :message ... :nsid ...}   ; DID/PDS/fetch failures
           {:error \"LexiconVerificationError\" :message ... :nsid ...} ; proof verification (:verify?)
           {:error \"InvalidLexiconDocument\" :message ... :nsid ... :explain-data ...}
           {:error \"LexiconNsidMismatch\" :message ... :nsid ... :id ...}"
  [nsid & {:keys [did-authority verify? cache force-refresh] :as opts}]
  (let [[cb val] (i/platform-async (select-keys opts [:callback :promise :channel]))
        cb (if cache
             (fn [{:keys [error] :as resp}]
               (when-not error
                 (cache-store! cache nsid resp))
               (cb resp))
             cb)
        fetch-opts {:verify? verify?}]
    (cond
      (not (lexicon/parse-nsid nsid))
      (cb {:error "InvalidNsid"
           :message (str "Invalid NSID: " nsid)})

      :else
      (if-let [cached (and cache
                           (not force-refresh)
                           (cache-lookup cache nsid))]
        (cb cached)
        (if did-authority
          (fetch-lexicon nsid did-authority fetch-opts cb)
          (resolve-nsid-authority nsid
                                  :callback
                                  (fn [{:keys [error did] :as resp}]
                                    (if error
                                      (cb resp)
                                      (fetch-lexicon nsid did fetch-opts cb)))))))
    val))

;; -----------------------------------------------------------------------------
;; Installer (dev helper)
;; -----------------------------------------------------------------------------

(defn document-nsid-refs
  "The NSIDs referenced by this schema document, excluding its own id.

  Walks every def for refs (ref, union refs, string knownValues token refs)
  and returns the set of NSIDs they point to, à la the TS lex installer's
  listDocumentNsidRefs."
  [doc]
  (let [refs (volatile! #{})]
    (walk/postwalk
     (fn [x]
       (when (map? x)
         (when (string? (:ref x))
           (vswap! refs conj (:ref x)))
         (when (= "union" (:type x))
           (doseq [r (:refs x)]
             (when (string? r)
               (vswap! refs conj r))))
         (when (= "string" (:type x))
           (doseq [r (:knownValues x)]
             (when (string? r)
               (vswap! refs conj r)))))
       x)
     doc)
    (->> @refs
         (keep #(let [nsid (first (str/split % #"#"))]
                  (when (and (seq nsid)
                             (lexicon/parse-nsid nsid))
                    nsid)))
         (remove #{(:id doc)})
         (set))))

#?(:clj
   (defn- nsid->relative-path
     "The installed file path for this NSID (app.bsky.feed.post ->
     app/bsky/feed/post.json)."
     [nsid]
     (str (str/join "/" (str/split nsid #"\.")) ".json")))

#?(:clj
   (defn- update-manifest!
     "Record installed schemas in <dir>/manifest.edn: each file is added to
     :files (distinct, sorted) and its resolution under
     :resolved {nsid {:uri ... :cid ...}}."
     [dir entries]
     (let [manifest-file (io/file dir "manifest.edn")
           manifest (if (.exists manifest-file)
                      (edn/read-string (slurp manifest-file))
                      {})
           manifest (-> manifest
                        (update :files
                                #(->> (concat % (map :path entries))
                                      (distinct)
                                      (sort)
                                      (vec)))
                        (update :resolved
                                merge
                                (into {}
                                      (map (fn [{:keys [nsid uri cid]}]
                                             [nsid (cond-> {:uri uri}
                                                     cid (assoc :cid cid))]))
                                      entries)))]
       (io/make-parents manifest-file)
       (spit manifest-file (with-out-str (pprint/pprint manifest))))))

#?(:clj
   (defn install!
     "Dev helper: resolve `nsid` and write its schema document as JSON under
     `dir`, mirroring the NSID hierarchy (app.bsky.feed.post ->
     app/bsky/feed/post.json), à la the TS lex CLI installer.

     Options:
     :dir    target directory (default \"resources/lexicons\")
     :deps?  when true, recursively install schemas referenced by ref/union/
             knownValues until fixpoint.
     plus resolve-nsid options (:did-authority — applied to `nsid` only —
     :verify?, :cache, :force-refresh).

     Synchronous; returns {:installed [nsid ...]} or {:error ...}.
     Updates <dir>/manifest.edn with the installed files and their
     {:uri ... :cid ...} resolutions."
     [nsid & {:keys [dir deps?] :or {dir "resources/lexicons"} :as opts}]
     (let [shared-opts (select-keys opts [:verify? :cache :force-refresh])]
       (loop [queue [nsid]
              seen #{}
              entries []]
         (if-let [current (first queue)]
           (if (seen current)
             (recur (rest queue) seen entries)
             (let [resolve-opts (cond-> shared-opts
                                  ;; the DID authority override only applies to
                                  ;; the requested NSID; deps resolve via DNS
                                  (and (= current nsid) (:did-authority opts))
                                  (assoc :did-authority (:did-authority opts)))
                   {:keys [error lexicon uri cid] :as resp}
                   (deref (apply resolve-nsid current (mapcat identity resolve-opts)))]
               (if error
                 resp
                 (let [path (nsid->relative-path current)
                       file (io/file dir path)]
                   (io/make-parents file)
                   (spit file (charred/write-json-str lexicon :indent-str "  "))
                   (recur (cond-> (rest queue)
                            deps? (concat (remove seen (document-nsid-refs lexicon))))
                          (conj seen current)
                          (conj entries {:nsid current
                                         :path path
                                         :uri uri
                                         :cid cid}))))))
           (do
             (update-manifest! dir entries)
             {:installed (mapv :nsid entries)}))))))
