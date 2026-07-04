(ns atproto.sync.firehose
  "com.atproto.sync.subscribeRepos (firehose) client.

  Port of the reference @atproto/sync Firehose. Binary frames are decoded
  with atproto.xrpc.frames, commit/sync CARs with atproto.repo.car, and
  events are delivered as :kind-keyed maps (shapes below). Commit
  verification is opt-in via :verify? (see parse-commit-verified);
  unverified parsing is the default, matching the reference.

  Event shapes (envelope keys kebab-case; wire-level lexicon fields keep
  their camelCase names; block maps are CID-keyed like atproto.repo.car):

    {:kind :create | :update | :delete
     :seq n :time \"...\" :did \"did:...\" :rev \"...\" :since \"...\"|nil
     :commit <cid> :uri \"at://did/coll/rkey\" :collection \"...\" :rkey \"...\"
     :blocks {<cid> bytes}                ;; block map from the commit CAR
     :record {...} :cid <cid>}            ;; :create/:update only
    {:kind :sync     :seq :time :did :cid :rev :blocks}
    {:kind :identity :seq :time :did :handle          ;; :handle optional;
                     :did-doc}                        ;; enriched mode only
    {:kind :account  :seq :time :did :active :status} ;; :status open string
    {:kind :info     :name \"OutdatedCursor\" :message}
    {:kind :gap      :seq :prev-seq}      ;; emitted on non-consecutive seq

  Seq semantics: relay seqs are increasing but not guaranteed contiguous
  (events may be filtered/aborted server-side), so a jump is observable —
  a :gap event plus a cast metric — never an error. A seq regression
  surfaces through :on-error as {:error \"SeqRegression\"} and the message
  is skipped. Consumers decide what to do in both cases."
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [atproto.data :as data]
            [atproto.data.cbor :as cbor]
            [atproto.identity :as identity]
            [atproto.lexicon :as lexicon]
            [atproto.repo.car :as car]
            [atproto.runtime.cast :as cast]
            [atproto.runtime.ws :as ws]
            [atproto.sync.cursor :as cursor]
            [atproto.sync.runner :as runner]
            [atproto.xrpc.frames :as frames]))

#?(:clj (set! *warn-on-reflection* true))

(def nsid "com.atproto.sync.subscribeRepos")
(def default-service "wss://bsky.network")

;; -----------------------------------------------------------------------------
;; Specs
;; -----------------------------------------------------------------------------

(s/def ::kind #{:create :update :delete :sync :identity :account :info :gap})
(s/def ::seq int?)
(s/def ::did string?)
(s/def ::event (s/and (s/keys :req-un [::kind])
                      (fn [{:keys [kind] :as event}]
                        (case kind
                          (:create :update) (and (:seq event) (:did event)
                                                 (:collection event) (:rkey event)
                                                 (contains? event :record))
                          :delete (and (:seq event) (:did event)
                                       (:collection event) (:rkey event))
                          (:sync :identity :account) (and (:seq event) (:did event))
                          :info (string? (:name event))
                          :gap (and (int? (:seq event))
                                    (int? (:prev-seq event)))))))

(s/def ::service string?)
(s/def ::handler fn?)
(s/def ::on-error fn?)
(s/def ::cursor int?)
(s/def ::filter-collections (s/coll-of string?))
(s/def ::exclude (s/coll-of #{:commit :sync :identity :account} :kind set?))
(s/def ::validate? boolean?)
(s/def ::resolve-identity? boolean?)
(s/def ::verify? boolean?)
(s/def ::reconnect-delay-ms nat-int?)
(s/def ::config
  (s/keys :req-un [::handler]
          :opt-un [::service ::on-error ::cursor ::filter-collections
                   ::exclude ::validate? ::resolve-identity? ::verify?
                   ::reconnect-delay-ms]))

;; -----------------------------------------------------------------------------
;; Message typing & filtering
;; -----------------------------------------------------------------------------

(defn body-with-type
  "Reconstruct the body's :$type from a decoded message frame's :t header:
  \"#commit\" -> \"<nsid>#commit\"; a fully-qualified :t is used as-is
  (port of xrpc-server/src/stream/subscription.ts:40-47)."
  [method {:keys [t body]}]
  (if (string? t)
    (assoc body :$type (if (str/starts-with? t "#") (str method t) t))
    body))

(defn match-collection-fn
  "Compile :filter-collections into a predicate. Entries are exact NSIDs or
  `prefix.*` patterns (port of @atproto/sync firehose/index.ts:67-85).
  nil/empty -> match everything."
  [filter-collections]
  (if (empty? filter-collections)
    (constantly true)
    (let [patterns (mapv (fn [entry]
                           (if (str/ends-with? entry ".*")
                             ;; keep the trailing dot: "app.bsky.feed.*"
                             ;; matches "app.bsky.feed.post"
                             (let [prefix (subs entry 0 (dec (count entry)))]
                               #(str/starts-with? % prefix))
                             #(= entry %)))
                         filter-collections)]
      (fn [collection]
        (boolean (some #(% collection) patterns))))))

(defn- parse-error
  [message & [event]]
  (cond-> {:error "FirehoseParseError" :message message}
    event (assoc :event event)))

;; -----------------------------------------------------------------------------
;; Pure-ish parsing (CAR/CBOR decoding, no I/O)
;; -----------------------------------------------------------------------------

(defn parse-commit-unverified
  "#commit message -> {:events [...]} fanning out one event per repo op
  (unverified), or {:error \"FirehoseParseError\" ...}. Records for
  create/update ops are extracted from the commit's CAR `blocks`
  (port of @atproto/sync formatCommitOps, firehose/index.ts:272-318)."
  [{:keys [seq time repo commit rev since blocks ops tooBig] :as message}
   match-collection]
  (if tooBig
    ;; Deprecated oversized-commit form: blocks are omitted from the wire,
    ;; so ops cannot be resolved into records.
    (parse-error "Skipping tooBig commit; its blocks are not on the wire."
                 {:seq seq :did repo})
    (let [car (car/read-car blocks)]
      (if (:error car)
        (parse-error (str "Invalid commit CAR: " (:message car))
                     {:seq seq :did repo})
        (let [block-map (:block-map car)]
          (reduce
           (fn [{:keys [events] :as acc} {:keys [action path cid]}]
             (let [[collection rkey] (str/split path #"/" 2)]
               (if-not (and collection rkey (match-collection collection))
                 acc
                 (let [base {:seq seq
                             :time time
                             :did repo
                             :commit commit
                             :rev rev
                             :since since
                             :uri (str "at://" repo "/" path)
                             :collection collection
                             :rkey rkey
                             :blocks block-map}]
                   (case action
                     "delete"
                     (update acc :events conj (assoc base :kind :delete))

                     ("create" "update")
                     (let [record-bytes (get block-map cid)]
                       (if (nil? record-bytes)
                         (reduced
                          (parse-error (str "Missing record block " (data/format-cid cid)
                                            " for " path " in commit CAR.")
                                       {:seq seq :did repo}))
                         (let [record (try
                                        (cbor/decode record-bytes)
                                        (catch #?(:clj Exception :cljs :default) e
                                          {::undecodable (ex-message e)}))]
                           (if (::undecodable record)
                             (reduced
                              (parse-error (str "Undecodable record block for " path ": "
                                                (::undecodable record))
                                           {:seq seq :did repo}))
                             (update acc :events conj
                                     (assoc base
                                            :kind (if (= action "create") :create :update)
                                            :record record
                                            :cid cid))))))

                     (reduced
                      (parse-error (str "Unknown repo op action: " (pr-str action))
                                   {:seq seq :did repo})))))))
           {:events []}
           ops))))))

(defn parse-sync-event
  "#sync message -> {:events [{:kind :sync ...}]} | {:error ...}
  (port of firehose/index.ts:320-334)."
  [{:keys [seq time did blocks rev] :as message}]
  (let [car (car/read-car-with-root blocks)]
    (if (:error car)
      (parse-error (str "Invalid sync CAR: " (:message car)) {:seq seq :did did})
      {:events [{:kind :sync
                 :seq seq
                 :time time
                 :did did
                 :cid (:root car)
                 :rev rev
                 :blocks (:block-map car)}]})))

(defn parse-identity-event
  "#identity message -> {:events [{:kind :identity ...}]}, without
  resolution/verification (see :resolve-identity? for the enriched form)."
  [{:keys [seq time did handle] :as message}]
  {:events [(cond-> {:kind :identity :seq seq :time time :did did}
              handle (assoc :handle handle))]})

(defn parse-account-event
  "#account message -> {:events [{:kind :account ...}]}. The :status string
  passes through openly (desynchronized/throttled/... accepted), unlike the
  reference which drops unknown statuses."
  [{:keys [seq time did active status] :as message}]
  {:events [(cond-> {:kind :account :seq seq :time time :did did :active active}
              status (assoc :status status))]})

(defn parse-message
  "Decoded+typed subscribeRepos message map (with :$type) -> {:events [...]}
  or {:error \"FirehoseParseError\" ...}. A #commit fans out to one event
  per op (unverified).

  Options:
    :filter-collections  as documented on `consume`."
  [message & {:keys [filter-collections]}]
  (let [$type (:$type message)
        fragment (when (string? $type)
                   (second (str/split $type #"#" 2)))]
    (case fragment
      "commit" (parse-commit-unverified
                message (match-collection-fn filter-collections))
      "sync" (parse-sync-event message)
      "identity" (parse-identity-event message)
      "account" (parse-account-event message)
      "info" {:events [(cond-> {:kind :info :name (:name message)}
                         (:message message) (assoc :message (:message message)))]}
      (parse-error (str "Unknown subscribeRepos message $type: " (pr-str $type))))))

;; -----------------------------------------------------------------------------
;; Verified commit parsing (consumes the WS-04 verification contract)
;; -----------------------------------------------------------------------------

#?(:clj
   (defn- signing-did-key
     "Resolve did -> the repo signing key as a did:key string.
     cb gets the did:key or {:error ...}."
     [did force-refresh? cb]
     (identity/resolve-did
      did
      :force-refresh force-refresh?
      :callback (fn [{:keys [error did-doc] :as resp}]
                  (if error
                    (cb resp)
                    (if-let [did-key (identity/did-doc-signing-did-key did-doc)]
                      (cb did-key)
                      (cb {:error "PoorlyFormattedDidDocument"
                           :message (str "No atproto signing key in DID document for " did)})))))))

#?(:clj
   (defn- verify-commit-events
     "Verify parsed commit events against the commit CAR via
     atproto.repo.sync/verify-proofs. cb gets {:events events} or
     {:error \"RepoVerification\" ...}."
     [verify-proofs-fn car-bytes did did-key events cb]
     (let [claims (mapv (fn [{:keys [kind collection rkey cid]}]
                          {:collection collection
                           :rkey rkey
                           :cid (when (not= :delete kind) cid)})
                        events)]
       (verify-proofs-fn
        car-bytes claims did did-key
        :callback
        (fn [{:keys [error verified unverified] :as resp}]
          (cond
            error (cb resp)
            (clojure.core/seq unverified)
            (cb {:error "RepoVerification"
                 :message (str "Unverified claims in commit: "
                               (pr-str (mapv #(select-keys % [:collection :rkey]) unverified)))})
            :else (cb {:events events})))))))

#?(:clj
   (defn parse-commit-verified
     "Like parse-commit-unverified, but additionally verifies the commit
     signature and per-op inclusion proofs via the WS-04 contract
     (atproto.repo.sync/verify-proofs). On verification failure, retries
     exactly once with a forced signing-key refresh (port of
     firehose/index.ts:203-251). Async: calls cb with {:events events} or
     {:error \"RepoVerification\"|\"FirehoseParseError\"|... :message ...}.

     `resolve-key-fn` is (fn [did force-refresh? cb]) yielding a did:key
     string or an error map; pass nil for the default identity-based
     resolver."
     [resolve-key-fn commit-message match-collection cb]
     (let [resolve-key-fn (or resolve-key-fn signing-did-key)
           ;; Late-bound so unit tests can stub verification.
           verify-proofs-fn (requiring-resolve 'atproto.repo.sync/verify-proofs)
           parsed (parse-commit-unverified commit-message match-collection)]
       (if (:error parsed)
         (cb parsed)
         (let [did (:repo commit-message)
               car-bytes (:blocks commit-message)
               events (:events parsed)
               attempt (fn attempt [force-refresh?]
                         (resolve-key-fn
                          did force-refresh?
                          (fn [key-resp]
                            (if (:error key-resp)
                              (cb key-resp)
                              (verify-commit-events
                               verify-proofs-fn car-bytes did key-resp events
                               (fn [{:keys [error] :as resp}]
                                 (if (and error (not force-refresh?))
                                   ;; The signing key may have rotated:
                                   ;; retry once with a forced refresh.
                                   (attempt true)
                                   (cb resp))))))))]
           (if (empty? events)
             (cb {:events []})
             (attempt false)))))))

;; -----------------------------------------------------------------------------
;; Identity enrichment
;; -----------------------------------------------------------------------------

#?(:clj
   (defn- enrich-identity-event
     "Resolve the event's DID (forced refresh, mirroring the reference) and
     attach :did-doc plus a bidirectionally verified :handle. An
     unverifiable handle is omitted, not an error (firehose/index.ts:336-368).
     A resolution failure leaves the event untouched and reports through
     on-error. Synchronous (blocks the calling thread)."
     [event on-error]
     (let [result (promise)]
       (identity/resolve-did
        (:did event)
        :force-refresh true
        :callback
        (fn [{:keys [error did-doc] :as resp}]
          (if error
            (deliver result resp)
            (let [handle (first (identity/did-doc-handles did-doc))
                  finish (fn [verified?]
                           (deliver result
                                    (cond-> (-> event
                                                (dissoc :handle)
                                                (assoc :did-doc did-doc))
                                      verified? (assoc :handle handle))))]
              (if-not handle
                (finish false)
                (identity/resolve-handle
                 handle
                 :callback (fn [{:keys [did]}]
                             (finish (= did (:did event))))))))))
       (let [outcome (deref result 30000 {:error "Timeout"
                                          :message "Identity resolution timed out."})]
         (if (:error outcome)
           (do (when on-error
                 (on-error (assoc outcome :event event)))
               event)
           outcome)))))

;; -----------------------------------------------------------------------------
;; Consume loop
;; -----------------------------------------------------------------------------

#?(:clj
   (defn- frame->message
     "Decode one binary frame into a typed message map, or an error map
     {:error \"InvalidFrame\"|\"FutureCursor\"|\"ConsumerTooSlow\"|... }."
     [frame-bytes]
     (let [frame (frames/decode frame-bytes)]
       (cond
         (:error frame) frame
         (= frames/error-op (:op frame)) (:body frame)   ;; {:error name & :message}
         :else (body-with-type nsid frame)))))

#?(:clj
   (defn- check-seq!
     "Track seq continuity. Returns nil to proceed, a :gap event to emit
     first, or ::regression to skip the message."
     [last-seq seq]
     (when seq
       (let [prev @last-seq]
         (vreset! last-seq seq)
         (cond
           (nil? prev) nil
           (<= seq prev) (do (vreset! last-seq prev) ::regression)
           (< (inc prev) seq) {:kind :gap :seq seq :prev-seq prev})))))

#?(:clj
   (defn- validate-message
     "nil when valid (or validation disabled), else an error map."
     [validate? message]
     (when validate?
       (let [spec (lexicon/message-spec-key nsid)]
         (when-not (try (s/valid? spec message)
                        (catch Exception e
                          (throw (ex-info (str "Cannot validate subscribeRepos messages; "
                                               "was the lexicon registered via "
                                               "atproto.lexicon/register-specs!? "
                                               (ex-message e))
                                          {:error "LexiconNotRegistered"} e))))
           {:error "InvalidMessage"
            :message (str "subscribeRepos message failed lexicon validation: "
                          (pr-str (:$type message)))})))))

#?(:clj
   (defn- kind-excluded?
     [exclude {:keys [kind]}]
     (boolean
      (and exclude
           ;; :info and :gap are never excluded
           (not (#{:info :gap} kind))
           (contains? exclude
                      (case kind
                        (:create :update :delete) :commit
                        kind))))))

#?(:clj
   (defn- dispatch-event!
     "Deliver one event through the runner (did-carrying events) or directly
     to the handler; advance the cursor store after handler completion."
     [{:keys [handler on-error runner cursor-store]} event]
     (let [seq (:seq event)
           did (:did event)
           run-handler
           (fn [cb]
             (try
               (handler event)
               (catch Throwable t
                 (when on-error
                   (on-error {:error "FirehoseHandlerError"
                              :message (ex-message t)
                              :exception t
                              :event event})))
               (finally (cb {}))))]
       (if (and runner seq did)
         (runner/track-event runner did seq run-handler)
         (run-handler
          (fn [_]
            (when (and cursor-store seq)
              (cursor/set-cursor
               cursor-store seq
               (fn [{:keys [error] :as resp}]
                 (when (and error on-error)
                   (on-error (assoc resp :event event))))))))))))

#?(:clj
   (defn- process-frame!
     "Full pipeline for one binary frame, called on the ws listener thread —
     blocking here is what backpressures the socket (one message of demand
     at a time)."
     [{:keys [on-error last-seq validate? exclude filter-collections
              match-collection resolve-identity? verify? resolve-key-fn]
       :as ctx}
      frame-bytes]
     (let [message (frame->message frame-bytes)
           invalid (when-not (:error message)
                     (validate-message validate? message))]
       (cond
         (:error message)
         (when on-error (on-error message))

         invalid
         (when on-error (on-error invalid))

         :else
         (let [seq-check (check-seq! last-seq (:seq message))]
           (cond
             (= ::regression seq-check)
             (when on-error
               (on-error {:error "SeqRegression"
                          :message (str "Sequence number regressed to " (:seq message))
                          :seq (:seq message)}))

             :else
             (do
               (when seq-check   ;; :gap event
                 (cast/metric {:message "Firehose seq gap."
                               :name :firehose/seq-gap
                               :seq (:seq seq-check)
                               :prev-seq (:prev-seq seq-check)})
                 (dispatch-event! ctx seq-check))
               (let [commit? (str/ends-with? (str (:$type message)) "#commit")
                     parsed (if (and verify? commit?)
                              (let [p (promise)]
                                (parse-commit-verified
                                 resolve-key-fn message match-collection
                                 #(deliver p %))
                                (deref p 60000 {:error "Timeout"
                                                :message "Commit verification timed out."}))
                              (parse-message message
                                             :filter-collections filter-collections))]
                 (if (:error parsed)
                   (when on-error (on-error parsed))
                   (doseq [event (:events parsed)]
                     (when-not (kind-excluded? exclude event)
                       (let [event (if (and resolve-identity?
                                            (= :identity (:kind event)))
                                     (enrich-identity-event event on-error)
                                     event)]
                         (dispatch-event! ctx event))))))))))
       nil)))

#?(:clj
   (defn- validation-error!
     [message]
     (throw (ex-info message {:error "InvalidConfig" :message message}))))

#?(:clj
   (defn- subscribe-url
     "Build {service}/xrpc/{nsid}?cursor={n}."
     [service cursor]
     (str (str/replace service #"/+$" "")
          "/xrpc/" nsid
          (when cursor (str "?cursor=" cursor)))))

#?(:clj
   (defn- cursor-url-fn
     "url-fn for atproto.runtime.ws: consult the cursor source on every
     (re)connect."
     [{:keys [service runner cursor-store last-processed initial-cursor]}]
     (fn [cb]
       (cond
         runner (runner/get-cursor
                 runner (fn [{:keys [cursor]}]
                          (cb (subscribe-url service (or cursor initial-cursor)))))
         cursor-store (cursor/get-cursor
                       cursor-store
                       (fn [{:keys [error cursor] :as resp}]
                         (if error
                           (cb resp)
                           (cb (subscribe-url service (or cursor initial-cursor))))))
         :else (cb (subscribe-url service (or @last-processed initial-cursor)))))))

(defn consume
  "Subscribe to the firehose and process events. JVM only (see
  atproto.runtime.ws).

  Config:
    :service           e.g. \"wss://bsky.network\" (the default)
    :handler           (fn [event]) called serially on the socket thread;
                       the cursor only advances after it returns. Required.
    :runner            an atproto.sync.runner runner for partitioned
                       concurrency; the handler is passed to the runner and
                       the runner becomes the cursor source. Mutually
                       exclusive with :cursor-store.
    :on-error          (fn [{:keys [error message event exception]}])
                       non-fatal: FirehoseParseError, FirehoseHandlerError,
                       InvalidFrame, InvalidMessage, SeqRegression,
                       RepoVerification, WS-level errors. Stream-level error
                       frames (FutureCursor, ConsumerTooSlow) also surface
                       here before the automatic reconnect.
    :cursor            initial cursor (int), used until the cursor source
                       has a value.
    :cursor-store      atproto.sync.cursor/CursorStore; consulted on every
                       (re)connect, written after each handled event.
    :filter-collections [\"app.bsky.feed.post\" \"xyz.statusphere.*\"]
                       client-side commit-op filter; exact NSIDs or `.*`
                       prefix patterns.
    :exclude           #{:commit :sync :identity :account} kinds to drop.
    :validate?         validate messages against the registered
                       com.atproto.sync.subscribeRepos lexicon
                       (atproto.lexicon/register-specs! must have run);
                       invalid messages -> on-error, skipped. Default false.
    :resolve-identity? enrich :identity events with the resolved DID doc
                       and a bidirectionally verified handle (an
                       unverifiable handle is omitted). Default false.
    :verify?           verify commit signatures + inclusion proofs via the
                       WS-04 contract, with one forced key-refresh retry;
                       failing commits are dropped through on-error.
                       Default false.
    :resolve-key-fn    (fn [did force-refresh? cb]) yielding the repo's
                       signing key as a did:key string (or an error map);
                       defaults to resolution via atproto.identity. Only
                       used with :verify?.
    :reconnect-delay-ms delay before re-subscribing after a server close or
                       fatal websocket error (default 3000, reference
                       firehose/index.ts:139).
    :ws-opts           extra options merged into atproto.runtime.ws/connect
                       (:max-reconnect-ms, :heartbeat-interval-ms, ...).

  The subscription reconnects until stopped: transport-level failures are
  retried by atproto.runtime.ws with backoff; server closes and error
  frames re-subscribe from the persisted cursor after :reconnect-delay-ms.

  Returns a handle; (stop! handle) shuts down."
  [{:keys [service handler runner cursor cursor-store on-error
           filter-collections exclude validate? resolve-identity? verify?
           reconnect-delay-ms ws-opts resolve-key-fn]
    :or {service default-service reconnect-delay-ms 3000}
    :as config}]
  #?(:cljs {:error "NotImplemented"
            :message "The firehose client is not yet implemented on ClojureScript."}
     :clj
     (do
       (when-not (s/valid? ::config config)
         (validation-error! (str "Invalid firehose config: "
                                 (s/explain-str ::config config))))
       (when (and runner cursor-store)
         (validation-error! ":runner and :cursor-store are mutually exclusive; the runner is the cursor source."))
       (when validate?
         ;; Fail fast (rather than per-message) when the lexicon is absent.
         (validate-message true {:$type (str nsid "#info") :name "probe"}))
       (let [last-processed (volatile! nil)
             state (atom {:stopped? false :ws nil})
             ctx {:handler handler
                  :runner runner
                  :cursor-store cursor-store
                  :on-error on-error
                  :last-seq (volatile! nil)
                  :validate? validate?
                  :exclude (when (clojure.core/seq exclude) (set exclude))
                  :filter-collections filter-collections
                  :match-collection (match-collection-fn filter-collections)
                  :resolve-identity? resolve-identity?
                  :verify? verify?
                  :resolve-key-fn resolve-key-fn}
             url-fn (cursor-url-fn {:service service
                                    :runner runner
                                    :cursor-store cursor-store
                                    :last-processed last-processed
                                    :initial-cursor cursor})
             on-message (fn [frame-bytes]
                          (if (string? frame-bytes)
                            (when on-error
                              (on-error {:error "InvalidFrame"
                                         :message "Unexpected text frame on subscribeRepos."}))
                            (do (process-frame! ctx frame-bytes)
                                (when-let [s @(:last-seq ctx)]
                                  (vreset! last-processed s)))))
             connect!
             (fn connect! []
               (when-not (:stopped? @state)
                 (let [reconnect (fn [info]
                                   (when-not (:stopped? @state)
                                     (cast/event {:message "Firehose subscription ended; re-subscribing."
                                                  :info info})
                                     (future
                                       (Thread/sleep ^long reconnect-delay-ms)
                                       (connect!))))
                       ws-handle (ws/connect
                                  (merge {:max-reconnect-ms 64000}
                                         ws-opts
                                         {:url-fn url-fn
                                          :on-message on-message
                                          :on-error (fn [{:keys [fatal] :as err}]
                                                      (when on-error (on-error err))
                                                      (when fatal (reconnect err)))
                                          :on-close (fn [info] (reconnect info))}))]
                   (swap! state assoc :ws ws-handle)
                   ;; stop! may have raced the connect; close the socket it
                   ;; couldn't see.
                   (when (:stopped? @state)
                     (ws/close! ws-handle)))))]
         (connect!)
         {:state state :ctx ctx}))))

(defn stop!
  "Shut down a firehose subscription: close the websocket and stop
  reconnecting. In-flight handler calls complete (and their cursor writes
  land) on their own threads; callers using a :runner should drain it
  afterwards. Idempotent."
  [handle]
  #?(:clj
     (let [[{:keys [ws stopped?]} _]
           (swap-vals! (:state handle) assoc :stopped? true)]
       (when (and ws (not stopped?))
         (ws/close! ws))
       nil)
     :cljs nil))

(comment
  ;; Live verification against the public relay (manual; not run in CI).

  (def store (cursor/memory-store))

  (def handle
    (consume {:service "wss://bsky.network"
              :handler (fn [event]
                         (when (= :create (:kind event))
                           (prn (:collection event) (:uri event))))
              :cursor-store store
              :filter-collections ["app.bsky.feed.post"]
              :on-error prn}))

  ;; Verified mode (signature + proof checks; noticeably slower):
  ;; (consume {:service "wss://bsky.network" :handler prn :verify? true
  ;;           :on-error prn})

  (cursor/get-cursor store prn)
  (stop! handle)
  )
