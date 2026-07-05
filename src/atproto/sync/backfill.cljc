(ns atproto.sync.backfill
  "Backfill a repo's records ahead of / alongside live streaming.

  Two strategies:
  - list-records-walk / backfill-repo: paginate com.atproto.repo.listRecords
    (optionally per collection from com.atproto.repo.describeRepo). Simple,
    unauthenticated-data path.
  - get-repo-walk: fetch the whole repo as a CAR (com.atproto.sync.getRepo),
    verify the commit signature and MST structure via atproto.repo.sync,
    and walk the verified records. One round-trip, authenticated.

  All emit the same synthetic events as a firehose :create, marked
  {:live false} (Tap-style backfill-before-live semantics):

    {:kind :create :live false :did ... :collection ... :rkey ...
     :record {...} :cid <cid> :uri \"at://did/coll/rkey\"}"
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [atproto.at-uri :as at-uri]
            [atproto.data :as data]
            [atproto.identity :as identity]
            [atproto.runtime.cast :as cast]
            [atproto.runtime.http :as http]
            [atproto.runtime.interceptor :as i]
            [atproto.sync.firehose :as firehose]
            [atproto.xrpc.client :as xrpc]))

#?(:clj (set! *warn-on-reflection* true))

(s/def ::repo string?)
(s/def ::collection string?)
(s/def ::handler fn?)
(s/def ::limit pos-int?)
(s/def ::max-pages pos-int?)
(s/def ::filter-collections (s/coll-of string?))
(s/def ::did-key string?)
(s/def ::walk-config
  (s/keys :req-un [::repo ::collection ::handler] :opt-un [::limit ::max-pages]))
(s/def ::repo-config
  (s/keys :req-un [::repo ::handler]
          :opt-un [::filter-collections ::limit ::max-pages]))
(s/def ::get-repo-config
  (s/keys :req-un [::repo ::handler] :opt-un [::did-key]))

(s/def ::kind #{:create})
(s/def ::live false?)
(s/def ::did string?)
(s/def ::rkey (s/nilable string?))
(s/def ::uri (s/nilable string?))
;; The synthetic backfill event shape handed to handlers.
(s/def ::event
  (s/keys :req-un [::kind ::live ::did ::collection ::rkey ::uri]))

(defn- async-opts [opts]
  (select-keys opts [:channel :callback :promise]))

(defn- invalid-request
  [spec m]
  {:error "InvalidRequest"
   :message (s/explain-str spec m)})

(defn- record->event
  [did collection {:keys [uri cid value]}]
  (let [rkey (or (:rkey (at-uri/parse uri))
                 (when uri (last (str/split uri #"/"))))]
    {:kind :create
     :live false
     :did did
     :collection collection
     :rkey rkey
     :record value
     :cid (if (string? cid) (or (data/parse-cid cid) cid) cid)
     :uri uri}))

(defn- run-handler
  "Invoke the user handler; a throw aborts the walk with an error map."
  [handler event]
  (try
    (handler event)
    nil
    (catch #?(:clj Throwable :cljs :default) t
      (cast/alert {:message "Backfill handler threw." :ex t})
      {:error "BackfillHandlerError"
       :message (ex-message t)
       :event event})))

(defn list-records-walk
  "Walk com.atproto.repo.listRecords for one repo+collection via the
  supplied atproto.xrpc.client, calling handler with synthetic
  {:kind :create :live false ...} events in ascending rkey order.
  Paginates with :cursor/:limit (default 100).

  m keys: :repo (did or handle), :collection, :limit, :handler; an
  optional :max-pages caps pagination. Async; the final callback gets
  {:count n} or {:error ...} (a throwing handler aborts the walk with
  {:error \"BackfillHandlerError\"})."
  [xrpc-client {:keys [repo collection limit handler max-pages] :as m} & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (if-not (s/valid? ::walk-config m)
      (cb (invalid-request ::walk-config m))
      (xrpc/fetch-pages
       xrpc-client
       {:nsid "com.atproto.repo.listRecords"
        :params {:repo repo
                 :collection collection
                 :limit (or limit 100)
                 ;; listRecords returns descending rkey order by default;
                 ;; reverse for ascending rkey order.
                 :reverse true}}
       (fn [count page]
         ;; The inner `reduced` stops this page's reduce (unwrapping once);
         ;; the surviving `reduced` then short-circuits fetch-pages with the
         ;; error map as the final result.
         (reduce (fn [n record]
                   (if-let [err (run-handler
                                 handler (record->event repo collection record))]
                     (reduced (reduced err))
                     (inc n)))
                 count
                 (:records page)))
       0
       :items-fn :records
       :max-pages max-pages
       :callback (fn [result]
                   (cb (if (map? result) result {:count result})))))
    val))

(defn backfill-repo
  "Backfill every collection of a repo: com.atproto.repo.describeRepo
  enumerates the collections (optionally narrowed by :filter-collections —
  exact NSIDs or `prefix.*` patterns, as in atproto.sync.firehose), then
  list-records-walk runs for each in turn.

  m keys: :repo, :handler, :filter-collections, :limit, :max-pages.
  Async; the final callback gets {:count n :collections [...]} or
  {:error ...}."
  [xrpc-client {:keys [repo filter-collections handler] :as m} & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (if-not (s/valid? ::repo-config m)
      (cb (invalid-request ::repo-config m))
      (xrpc/query
       xrpc-client
       {:nsid "com.atproto.repo.describeRepo"
        :params {:repo repo}}
       :callback
       (fn [{:keys [error collections] :as resp}]
         (if error
           (cb resp)
           (let [match (firehose/match-collection-fn filter-collections)
                 collections (filterv match collections)
                 walk (fn walk [remaining done total]
                        (if-let [collection (first remaining)]
                          (list-records-walk
                           xrpc-client
                           (assoc (select-keys m [:repo :limit :max-pages])
                                  :collection collection
                                  :handler handler)
                           :callback
                           (fn [{:keys [error count] :as result}]
                             (if error
                               (cb result)
                               (walk (rest remaining)
                                     (conj done collection)
                                     (+ total count)))))
                          (cb {:count total :collections done})))]
             (walk collections [] 0))))))
    val))

;; -----------------------------------------------------------------------------
;; CAR-based backfill (WS-04-gated finale)
;; -----------------------------------------------------------------------------

#?(:clj
   (defn- fetch-repo-car
     "GET {service}/xrpc/com.atproto.sync.getRepo?did={did} as bytes."
     [service did cb]
     (i/execute
      {::i/request {:method :get
                    :url (str (str/replace service #"/+$" "")
                              "/xrpc/com.atproto.sync.getRepo?did="
                              (http/url-encode did))
                    :headers {:accept "application/vnd.ipld.car"}
                    ;; http-kit: return the body as a byte array
                    :as :byte-array}
       ::i/queue [http/client-interceptor]}
      :callback
      (fn [{:keys [error status body] :as resp}]
        (cond
          error (cb resp)
          (not (http/success? status)) (cb (http/error-map resp))
          :else (cb {:car body}))))))

#?(:clj
   (defn get-repo-walk
     "Fetch com.atproto.sync.getRepo (a CAR of the whole repo), verify the
     commit signature + MST via atproto.repo.sync/verify-records, and emit
     the same synthetic {:kind :create :live false ...} events as
     list-records-walk. A single, authenticated round-trip.

     m keys:
       :repo      DID or handle (resolved via atproto.identity)
       :handler   (fn [event])
       :did-key   optional \"did:key:z...\" for signature verification;
                  defaults to the atproto signing key from the resolved
                  DID document.

     Async; the final callback gets {:count n :did did} or {:error ...}
     ({:error \"RepoVerification\"} when the CAR fails verification)."
     [xrpc-client {:keys [repo handler did-key] :as m} & {:as opts}]
     (let [[cb val] (i/platform-async (async-opts opts))
           verify-records (requiring-resolve 'atproto.repo.sync/verify-records)
           service (:service xrpc-client)]
       (if-not (and (s/valid? ::get-repo-config m) service)
         (cb (if service
               (invalid-request ::get-repo-config m)
               {:error "InvalidRequest"
                :message "get-repo-walk requires a client with :service."}))
         (identity/resolve-identity
          repo
          :callback
          (fn [{:keys [error did did-doc] :as resp}]
            (if error
              (cb resp)
              (let [did-key (or did-key (identity/did-doc-signing-did-key did-doc))]
                (if-not did-key
                  (cb {:error "PoorlyFormattedDidDocument"
                       :message (str "No atproto signing key in DID document for " did)})
                  (fetch-repo-car
                   service did
                   (fn [{:keys [error car] :as resp}]
                     (if error
                       (cb resp)
                       (verify-records
                        car did did-key
                        :callback
                        (fn [records]
                          (if (:error records)
                            (cb records)
                            (loop [records records n 0]
                              (if-let [{:keys [collection rkey cid value]} (first records)]
                                (if-let [err (run-handler
                                              handler
                                              {:kind :create
                                               :live false
                                               :did did
                                               :collection collection
                                               :rkey rkey
                                               :record value
                                               :cid cid
                                               :uri (str "at://" did "/" collection "/" rkey)})]
                                  (cb err)
                                  (recur (rest records) (inc n)))
                                (cb {:count n :did did})))))))))))))))
       val)))

(comment
  ;; Live verification against a public PDS (manual; not run in CI).

  (require '[atproto.xrpc.client :as xrpc])
  (def client (xrpc/init {:service "https://bsky.social"}))

  ;; Paginated walk of one collection:
  @(list-records-walk client {:repo "bsky.app"
                              :collection "app.bsky.feed.post"
                              :handler #(prn (:uri %))})

  ;; All collections (compare :count with listRecords totals):
  @(backfill-repo client {:repo "bsky.app" :handler (fn [_])})

  ;; Single verified CAR round-trip:
  @(get-repo-walk client {:repo "bsky.app" :handler #(prn (:uri %))})
  )
