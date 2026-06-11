(ns atproto.client.repo
  "Convenience wrappers over com.atproto.repo.* XRPC methods.

  All functions follow the SDK async convention (trailing keyword options
  with :channel/:callback/:promise, returning a platform deferred) and
  deliver error maps on failure. `client` is an atproto.client/xrpc client.

  :repo defaults to the client's authenticated DID; it is an error
  ({:error \"NotAuthenticated\"}) if neither is available. Request maps also
  accept the :headers/:timeout/:signal/:max-retries passthrough keys.

  (Named atproto.client.repo, not atproto.repo: atproto.repo is reserved for
  the MST/commit repository layer, mirroring @atproto/repo.)"
  (:require [atproto.client :as client]
            [atproto.runtime.interceptor :as i]
            [atproto.xrpc.client :as xrpc]))

(def ^:private passthrough-keys
  [:headers :timeout :signal :max-retries])

(def ^:private not-authenticated
  {:error "NotAuthenticated"
   :message "No :repo provided and the client has no authenticated session."})

(defn- invalid-request
  [message]
  {:error "InvalidRequest" :message message})

(defn create-record
  "com.atproto.repo.createRecord.

  m: :record      required, atproto data map
     :collection  optional, defaults to the record's :$type
     :repo        optional, defaults to the client's authenticated DID
     :rkey        optional; omitted, the server generates a TID (pass
                  e.g. (atproto.tid/next-tid) for client-side determinism)
     :validate?   tri-state: true/false/nil (server default)
     :swap-commit CID string for optimistic concurrency

  Result body: {:uri ... :cid ...} (the uri is usable with
  atproto.at-uri/parse)."
  [client {:keys [record repo collection rkey validate? swap-commit] :as m} & {:as opts}]
  (let [[cb val] (i/platform-async opts)
        repo (or repo (client/did client))
        collection (or collection (:$type record))]
    (cond
      (not (map? record))
      (cb (invalid-request "A :record map is required."))

      (nil? repo)
      (cb not-authenticated)

      (nil? collection)
      (cb (invalid-request "No :collection provided and the record has no :$type."))

      :else
      (client/procedure client
                        (merge (select-keys m passthrough-keys)
                               {:nsid "com.atproto.repo.createRecord"
                                :body (cond-> {:repo repo
                                               :collection collection
                                               :record record}
                                        rkey (assoc :rkey rkey)
                                        (some? validate?) (assoc :validate validate?)
                                        swap-commit (assoc :swapCommit swap-commit))})
                        :callback cb))
    val))

(defn get-record
  "com.atproto.repo.getRecord.

  m: :collection and :rkey required; :repo (defaults to the authenticated
  DID) and :cid optional.

  Result: {:uri ... :cid ... :value {...}}."
  [client {:keys [repo collection rkey cid] :as m} & {:as opts}]
  (let [[cb val] (i/platform-async opts)
        repo (or repo (client/did client))]
    (cond
      (or (nil? collection) (nil? rkey))
      (cb (invalid-request ":collection and :rkey are required."))

      (nil? repo)
      (cb not-authenticated)

      :else
      (client/query client
                    (merge (select-keys m passthrough-keys)
                           {:nsid "com.atproto.repo.getRecord"
                            :params (cond-> {:repo repo
                                             :collection collection
                                             :rkey rkey}
                                      cid (assoc :cid cid))})
                    :callback cb))
    val))

(defn put-record
  "com.atproto.repo.putRecord.

  m: :record and :rkey required; :collection (defaults to the record's
  :$type), :repo, :validate?, :swap-commit, :swap-record optional
  (:swap-record may be a CID string or nil-but-provided to assert that the
  record does not exist yet; use :swap-record only when present in m)."
  [client {:keys [record repo collection rkey validate? swap-commit swap-record] :as m} & {:as opts}]
  (let [[cb val] (i/platform-async opts)
        repo (or repo (client/did client))
        collection (or collection (:$type record))]
    (cond
      (not (map? record))
      (cb (invalid-request "A :record map is required."))

      (nil? rkey)
      (cb (invalid-request ":rkey is required."))

      (nil? repo)
      (cb not-authenticated)

      (nil? collection)
      (cb (invalid-request "No :collection provided and the record has no :$type."))

      :else
      (client/procedure client
                        (merge (select-keys m passthrough-keys)
                               {:nsid "com.atproto.repo.putRecord"
                                :body (cond-> {:repo repo
                                               :collection collection
                                               :rkey rkey
                                               :record record}
                                        (some? validate?) (assoc :validate validate?)
                                        swap-commit (assoc :swapCommit swap-commit)
                                        (contains? m :swap-record) (assoc :swapRecord swap-record))})
                        :callback cb))
    val))

(defn delete-record
  "com.atproto.repo.deleteRecord.

  m: :collection and :rkey required; :repo, :swap-commit, :swap-record
  optional."
  [client {:keys [repo collection rkey swap-commit swap-record] :as m} & {:as opts}]
  (let [[cb val] (i/platform-async opts)
        repo (or repo (client/did client))]
    (cond
      (or (nil? collection) (nil? rkey))
      (cb (invalid-request ":collection and :rkey are required."))

      (nil? repo)
      (cb not-authenticated)

      :else
      (client/procedure client
                        (merge (select-keys m passthrough-keys)
                               {:nsid "com.atproto.repo.deleteRecord"
                                :body (cond-> {:repo repo
                                               :collection collection
                                               :rkey rkey}
                                        swap-commit (assoc :swapCommit swap-commit)
                                        swap-record (assoc :swapRecord swap-record))})
                        :callback cb))
    val))

(defn- list-records-request
  [repo {:keys [collection limit cursor] :as m}]
  (merge (select-keys m passthrough-keys)
         {:nsid "com.atproto.repo.listRecords"
          :params (cond-> {:repo repo
                           :collection collection}
                    limit (assoc :limit limit)
                    cursor (assoc :cursor cursor)
                    (contains? m :reverse) (assoc :reverse (:reverse m)))}))

(defn list-records
  "com.atproto.repo.listRecords - a single page.

  m: :collection required; :repo, :limit, :cursor, :reverse optional.

  Result: {:records [{:uri ... :cid ... :value {...}} ...] :cursor ...}."
  [client {:keys [repo collection] :as m} & {:as opts}]
  (let [[cb val] (i/platform-async opts)
        repo (or repo (client/did client))]
    (cond
      (nil? collection) (cb (invalid-request ":collection is required."))
      (nil? repo)       (cb not-authenticated)
      :else             (client/query client (list-records-request repo m) :callback cb))
    val))

(defn list-all-records
  "All pages of com.atproto.repo.listRecords, collecting :records into a
  single vector (via atproto.xrpc.client/fetch-all).

  m: as list-records, plus :max-pages to guard unbounded collections."
  [client {:keys [repo collection max-pages] :as m} & {:as opts}]
  (let [[cb val] (i/platform-async opts)
        repo (or repo (client/did client))]
    (cond
      (nil? collection) (cb (invalid-request ":collection is required."))
      (nil? repo)       (cb not-authenticated)
      :else             (xrpc/fetch-all client
                                        (list-records-request repo m)
                                        :max-pages max-pages
                                        :callback cb))
    val))

#?(:clj
   (defn record-seq
     "Lazy, blocking seq of records across listRecords pages (CLJ only; do
     not use on event-loop threads). m: as list-records. If a page fetch
     fails, the error map is the final element of the seq."
     [client {:keys [repo collection] :as m}]
     (let [repo (or repo (client/did client))]
       (cond
         (nil? collection) (list (invalid-request ":collection is required."))
         (nil? repo)       (list not-authenticated)
         :else             (mapcat (fn [page]
                                     (if (:error page)
                                       [page]
                                       (:records page)))
                                   (xrpc/page-seq client (list-records-request repo m)))))))

(def ^:private write-type->lex-type
  {:create "com.atproto.repo.applyWrites#create"
   :update "com.atproto.repo.applyWrites#update"
   :delete "com.atproto.repo.applyWrites#delete"})

(defn- write->op
  [{:keys [type collection rkey value]}]
  (when-let [lex-type (write-type->lex-type type)]
    (cond-> {:$type lex-type}
      collection (assoc :collection collection)
      rkey (assoc :rkey rkey)
      (not= :delete type) (assoc :value value))))

(defn apply-writes
  "com.atproto.repo.applyWrites.

  m: :writes - vector of plain maps, translated to the $type-tagged
     com.atproto.repo.applyWrites#create/update/delete unions:
       {:type :create :collection nsid :rkey (optional) :value record}
       {:type :update :collection nsid :rkey rkey :value record}
       {:type :delete :collection nsid :rkey rkey}
     :repo, :validate?, :swap-commit optional."
  [client {:keys [writes repo validate? swap-commit] :as m} & {:as opts}]
  (let [[cb val] (i/platform-async opts)
        repo (or repo (client/did client))
        ops (mapv write->op writes)]
    (cond
      (or (empty? ops) (some nil? ops))
      (cb (invalid-request "Each write requires a :type of :create, :update, or :delete."))

      (nil? repo)
      (cb not-authenticated)

      :else
      (client/procedure client
                        (merge (select-keys m passthrough-keys)
                               {:nsid "com.atproto.repo.applyWrites"
                                :body (cond-> {:repo repo
                                               :writes ops}
                                        (some? validate?) (assoc :validate validate?)
                                        swap-commit (assoc :swapCommit swap-commit))})
                        :callback cb))
    val))

(defn upload-blob
  "com.atproto.repo.uploadBlob.

  `blob` is platform bytes (CLJ: byte[], InputStream, or File; CLJS:
  js/Blob, js/ArrayBuffer, or js/Uint8Array). `encoding` is the blob's MIME
  type - required on CLJ; on CLJS it defaults to a js/Blob's .-type.
  The :headers/:timeout/:signal/:max-retries passthrough keys are read from
  the trailing options.

  Result body: {:blob {:$type \"blob\" :ref ... :mimeType ... :size ...}}
  (already decoded to atproto data by the data.json interceptor)."
  [client blob encoding & {:as opts}]
  (let [[cb val] (i/platform-async opts)
        encoding #?(:clj encoding
                    :cljs (or encoding
                              (when (instance? js/Blob blob)
                                (not-empty (.-type blob)))))]
    (if (nil? encoding)
      (cb (invalid-request "An encoding MIME type is required to upload a blob."))
      (client/procedure client
                        (merge (select-keys opts passthrough-keys)
                               {:nsid "com.atproto.repo.uploadBlob"
                                :body blob
                                :encoding encoding})
                        :callback cb))
    val))
