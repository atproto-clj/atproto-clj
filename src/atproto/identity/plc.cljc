(ns atproto.identity.plc
  "did:plc operations and PLC directory client.

  Operation building/derivation (signing payloads, CIDs, DID derivation)
  is synchronous; signing, verification, and the directory client follow
  the SDK async convention (trailing options map with
  :callback/:promise/:channel adapters).

  Spec: https://web.plc.directory/spec/v0.1/did-plc
  Reference: https://github.com/did-method-plc/did-method-plc packages/lib"
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [multiformats.base :as mb]
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.http :as http]
            [atproto.runtime.json :as json]
            [atproto.runtime.crypto :as runtime.crypto]
            [atproto.crypto :as crypto]
            [atproto.data :as data]
            [atproto.data.cbor :as cbor]
            [atproto.identity.plc.operation :as-alias op]
            [atproto.identity.plc.tombstone :as-alias tombstone]
            [atproto.identity.plc.legacy-create :as-alias legacy]
            [atproto.identity.plc.service :as-alias service]))

#?(:clj (set! *warn-on-reflection* true))

(def default-plc-url "https://plc.directory")

(def recovery-window-ms
  "Window during which a rotation key may rewrite (nullify) operations
  signed by a lower-authority key (ref: data.ts assureValidNextOp)."
  (* 72 60 60 1000))

(defn- async-opts
  "The async-adapter keys of an options map (see i/platform-async).
  Other keys must be stripped before requesting the platform default."
  [opts]
  (select-keys opts [:channel :callback :promise]))

;; -----------------------------------------------------------------------------
;; Specs (ref: did-method-plc packages/lib/src/types.ts)
;; -----------------------------------------------------------------------------

(s/def ::did-key
  (s/and string? #(str/starts-with? % "did:key:")))

(s/def ::service/type string?)
(s/def ::service/endpoint string?)
(s/def ::service-entry
  (s/keys :req-un [::service/type ::service/endpoint]))

(s/def ::op/type #{"plc_operation"})
(s/def ::op/rotationKeys (s/coll-of ::did-key :min-count 1 :max-count 5))
(s/def ::op/verificationMethods (s/map-of keyword? ::did-key))
(s/def ::op/alsoKnownAs (s/coll-of string?))
(s/def ::op/services (s/map-of keyword? ::service-entry))
(s/def ::op/prev (s/nilable string?))
(s/def ::op/sig string?)

(s/def ::operation
  (s/keys :req-un [::op/type
                   ::op/rotationKeys
                   ::op/verificationMethods
                   ::op/alsoKnownAs
                   ::op/services
                   ::op/prev
                   ::op/sig]))

(s/def ::tombstone/type #{"plc_tombstone"})
(s/def ::tombstone/prev string?)
(s/def ::tombstone/sig string?)

(s/def ::tombstone
  (s/and (s/keys :req-un [::tombstone/type
                          ::tombstone/prev
                          ::tombstone/sig])
         #(= #{:type :prev :sig} (set (keys %)))))

(s/def ::legacy/type #{"create"})
(s/def ::legacy/signingKey ::did-key)
(s/def ::legacy/recoveryKey ::did-key)
(s/def ::legacy/handle string?)
(s/def ::legacy/service string?)
(s/def ::legacy/prev nil?)
(s/def ::legacy/sig string?)

(s/def ::legacy-create-op
  (s/keys :req-un [::legacy/type
                   ::legacy/signingKey
                   ::legacy/recoveryKey
                   ::legacy/handle
                   ::legacy/service
                   ::legacy/prev
                   ::legacy/sig]))

(s/def ::compatible-op
  (s/nonconforming
   (s/or :operation ::operation
         :legacy-create ::legacy-create-op)))

(s/def ::op-or-tombstone
  (s/nonconforming
   (s/or :operation ::operation
         :tombstone ::tombstone)))

(s/def ::op
  (s/nonconforming
   (s/or :operation ::operation
         :tombstone ::tombstone
         :legacy-create ::legacy-create-op)))

(defn tombstone?
  "Whether the operation is a tombstone."
  [op]
  (= "plc_tombstone" (:type op)))

;; -----------------------------------------------------------------------------
;; Operation building & DID derivation
;; -----------------------------------------------------------------------------

(defn- ensure-http-prefix
  [s]
  (if (or (str/starts-with? s "http://")
          (str/starts-with? s "https://"))
    s
    (str "https://" s)))

(defn- ensure-atproto-prefix
  [s]
  (if (str/starts-with? s "at://")
    s
    (str "at://" (-> s
                     (str/replace "http://" "")
                     (str/replace "https://" "")))))

(defn normalize-op
  "Legacy create op -> plc_operation shape; pass plc_operation/tombstone
  through. ref: operations.ts normalizeOp"
  [{:keys [type signingKey recoveryKey handle service prev] :as op}]
  (if (not= "create" type)
    op
    (cond-> {:type "plc_operation"
             :verificationMethods {:atproto signingKey}
             :rotationKeys [recoveryKey signingKey]
             :alsoKnownAs [(ensure-atproto-prefix handle)]
             :services {:atproto_pds {:type "AtprotoPersonalDataServer"
                                      :endpoint (ensure-http-prefix service)}}
             :prev prev}
      (contains? op :sig) (assoc :sig (:sig op)))))

(defn signing-payload
  "Deterministic DAG-CBOR bytes of the operation without its signature.
  Throws (as atproto.data.cbor/encode) on data outside the data model."
  [op]
  (cbor/encode (dissoc op :sig)))

(defn cid-for-op
  "CIDv1 dag-cbor sha2-256 base32 string of the (signed) operation's
  DAG-CBOR bytes; used as :prev in the next operation."
  [signed-op]
  (data/format-cid (data/cid-link (cbor/encode signed-op))))

(defn did-for-create-op
  "did:plc derivation: \"did:plc:\" + first 24 chars of the lowercase,
  unpadded RFC 4648 base32 of (sha256 (dag-cbor signed-genesis-op)).
  ref: operations.ts didForCreateOp; spec §DID Creation."
  [signed-genesis-op]
  (let [digest (runtime.crypto/sha256 (cbor/encode signed-genesis-op))
        ;; multiformats base32 is lowercase unpadded RFC 4648 with a
        ;; one-character multibase prefix; strip the prefix.
        b32 (subs (mb/format :base32 digest) 1)]
    (str "did:plc:" (subs b32 0 24))))

(defn sign-op
  "Sign an unsigned operation with a rotation keypair (atproto.crypto
  Keypair; low-S 64-byte compact signature). Adds :sig as unpadded
  base64url. Async; yields the signed op or {:error ...}.
  ref: operations.ts addSignature"
  [op rotation-keypair & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))
        payload (try
                  (signing-payload op)
                  (catch #?(:clj Exception :cljs :default) e
                    (ex-data e)))]
    (if (:error payload)
      (cb payload)
      (crypto/sign rotation-keypair payload
                   :callback
                   (fn [sig]
                     (if (:error sig)
                       (cb sig)
                       (cb (assoc op :sig (runtime.crypto/base64url-encode sig)))))))
    val))

(defn format-atproto-op
  "Unsigned plc_operation in the standard atproto shape.
  ref: operations.ts formatAtprotoOp"
  [{:keys [signing-key rotation-keys handle pds prev]}]
  {:type "plc_operation"
   :verificationMethods {:atproto signing-key}
   :rotationKeys (vec rotation-keys)
   :alsoKnownAs [(ensure-atproto-prefix handle)]
   :services {:atproto_pds {:type "AtprotoPersonalDataServer"
                            :endpoint (ensure-http-prefix pds)}}
   :prev prev})

(defn create-op
  "Build and sign a genesis operation and derive its DID.

  opts: :signing-key (did:key string), :rotation-keys [did:key ...],
        :handle, :pds, :signer (rotation Keypair).

  Async; yields {:did did :op signed-op} or {:error ...}.
  ref: operations.ts createOp / atprotoOp"
  [{:keys [signer] :as params} & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (sign-op (format-atproto-op (assoc params :prev nil)) signer
             :callback
             (fn [{:keys [error] :as signed}]
               (if error
                 (cb signed)
                 (cb {:did (did-for-create-op signed)
                      :op signed}))))
    val))

(defn update-op
  "Build and sign an update operation: :prev is the CID of last-op and
  the new state is (f normalized-last-op-without-sig-and-prev); f must
  return the unsigned operation (its :prev is set afterwards). Yields
  {:error \"DidTombstoned\"} if last-op is a tombstone.
  ref: operations.ts createUpdateOp"
  [last-op signer f & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (if (tombstone? last-op)
      (cb {:error "DidTombstoned"
           :message "Cannot apply an operation to a tombstoned DID."})
      (let [prev (try
                   (cid-for-op last-op)
                   (catch #?(:clj Exception :cljs :default) e
                     (ex-data e)))]
        (if (:error prev)
          (cb prev)
          ;; drop :sig so it can't leak into the next operation
          (let [normalized (dissoc (normalize-op last-op) :sig)
                unsigned (assoc (f (dissoc normalized :prev)) :prev prev)]
            (sign-op unsigned signer :callback cb)))))
    val))

(defn- set-handle
  "Replace the first at:// alias (or prepend one).
  ref: operations.ts createAtprotoUpdateOp"
  [normalized handle]
  (let [formatted (ensure-atproto-prefix handle)
        aka (vec (:alsoKnownAs normalized))
        idx (first (keep-indexed (fn [i a] (when (str/starts-with? a "at://") i))
                                 aka))]
    (assoc normalized :alsoKnownAs (if idx
                                     (assoc aka idx formatted)
                                     (into [formatted] aka)))))

(defn update-handle-op
  "Update operation setting the handle (first at:// alias)."
  [last-op signer handle & {:as opts}]
  (update-op last-op signer #(set-handle % handle) opts))

(defn update-pds-op
  "Update operation setting the atproto_pds service endpoint."
  [last-op signer url & {:as opts}]
  (update-op last-op signer
             #(assoc-in % [:services :atproto_pds]
                        {:type "AtprotoPersonalDataServer"
                         :endpoint (ensure-http-prefix url)})
             opts))

(defn update-rotation-keys-op
  "Update operation replacing the rotation keys."
  [last-op signer did-keys & {:as opts}]
  (update-op last-op signer #(assoc % :rotationKeys (vec did-keys)) opts))

(defn update-signing-key-op
  "Update operation replacing the atproto signing key."
  [last-op signer did-key & {:as opts}]
  (update-op last-op signer
             #(assoc-in % [:verificationMethods :atproto] did-key)
             opts))

(defn tombstone-op
  "Signed {:type \"plc_tombstone\" :prev (cid-for-op last-op)}.
  ref: operations.ts tombstoneOp"
  [last-op signer & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (if (tombstone? last-op)
      (cb {:error "DidTombstoned"
           :message "Cannot apply an operation to a tombstoned DID."})
      (let [prev (try
                   (cid-for-op last-op)
                   (catch #?(:clj Exception :cljs :default) e
                     (ex-data e)))]
        (if (:error prev)
          (cb prev)
          (sign-op {:type "plc_tombstone" :prev prev} signer :callback cb))))
    val))

;; -----------------------------------------------------------------------------
;; Directory client (ref: did-method-plc packages/lib/src/client.ts)
;; -----------------------------------------------------------------------------

(defn- directory-error
  "Surface the directory's JSON error body in the error map."
  [{:keys [body] :as resp}]
  (cond-> (http/error-map resp)
    (and (map? body) (:message body)) (assoc :message (:message body))))

(defn- directory-get
  [url did cb wrap]
  (i/execute {::i/request {:method :get
                           :url url
                           :headers {:accept "application/json"}}
              ::i/queue [json/client-interceptor
                         http/client-interceptor]}
             :callback
             (fn [{:keys [error status body] :as resp}]
               (cb (cond
                     error                  resp
                     (http/success? status) (wrap body)
                     (= 404 status)         (cond-> {:error "DidNotFound"}
                                              did (assoc :did did)
                                              (and (map? body) (:message body))
                                              (assoc :message (:message body)))
                     :else                  (directory-error resp))))))

(defn- plc-url
  [opts]
  (or (:plc-url opts) default-plc-url))

(defn get-did-doc
  "GET /{did} — the DID document. Async; yields {:did-doc doc}."
  [did & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (directory-get (str (plc-url opts) "/" did) did cb #(hash-map :did-doc %))
    val))

(defn get-data
  "GET /{did}/data — the document data (verificationMethods, rotationKeys,
  alsoKnownAs, services). Async; yields {:data m}."
  [did & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (directory-get (str (plc-url opts) "/" did "/data") did cb #(hash-map :data %))
    val))

(defn get-operation-log
  "GET /{did}/log — the canonical operation log. Async; yields {:ops [op ...]}."
  [did & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (directory-get (str (plc-url opts) "/" did "/log") did cb #(hash-map :ops %))
    val))

(defn get-audit-log
  "GET /{did}/log/audit — the auditable log, including nullified
  operations. Async; yields {:ops [{:did .. :operation .. :cid ..
  :nullified .. :createdAt ..} ...]}."
  [did & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (directory-get (str (plc-url opts) "/" did "/log/audit") did cb #(hash-map :ops %))
    val))

(defn get-last-op
  "GET /{did}/log/last — the most recent operation. Async; yields {:op op}."
  [did & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (directory-get (str (plc-url opts) "/" did "/log/last") did cb #(hash-map :op %))
    val))

(defn submit
  "POST the signed operation as JSON to /{did}. Async; yields
  {:success true} or an error map (the directory's message is surfaced
  for HTTP 4xx)."
  [did signed-op & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (i/execute {::i/request {:method :post
                             :url (str (plc-url opts) "/" did)
                             :headers {:content-type "application/json"}
                             :body signed-op}
                ::i/queue [json/client-interceptor
                           http/client-interceptor]}
               :callback
               (fn [{:keys [error status] :as resp}]
                 (cb (cond
                       error                  resp
                       (http/success? status) {:success true}
                       :else                  (directory-error resp)))))
    val))

(defn export
  "GET /export?count=&after= — paginated dump of operations across DIDs
  (JSON lines). Async; yields {:ops [{:did .. :operation .. :cid ..
  :nullified .. :createdAt .. :seq? ..} ...]}."
  [& {:keys [count after] :as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (i/execute {::i/request {:method :get
                             :url (str (plc-url opts) "/export")
                             :query-params (cond-> {}
                                             count (assoc :count (str count))
                                             after (assoc :after (str after)))}
                ::i/queue [http/client-interceptor]}
               :callback
               (fn [{:keys [error status body] :as resp}]
                 (cb (cond
                       error resp

                       (http/success? status)
                       {:ops (->> (str/split-lines (str body))
                                  (remove str/blank?)
                                  (mapv json/read-str))}

                       :else (http/error-map resp)))))
    val))

;; -----------------------------------------------------------------------------
;; Verification (ref: operations.ts assureValidSig/assureValidCreationOp,
;; data.ts validateOperationLog/assureValidNextOp)
;; -----------------------------------------------------------------------------

(defn- op->data
  "DocumentData of a (normalized) operation, or nil for a tombstone.
  ref: data.ts opToData"
  [did op]
  (when-not (tombstone? op)
    (let [{:keys [verificationMethods rotationKeys alsoKnownAs services]}
          (normalize-op op)]
      {:did did
       :verificationMethods verificationMethods
       :rotationKeys rotationKeys
       :alsoKnownAs alsoKnownAs
       :services services})))

(defn verify-op-sig
  "Verify the operation's signature against a collection of allowed
  rotation did:keys. Async; yields {:did-key k} identifying the signer,
  or {:error \"InvalidSignature\" ...}. Padded signatures are rejected.
  ref: operations.ts assureValidSig"
  [allowed-did-keys op & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))
        sig (:sig op)
        invalid (fn [message]
                  {:error "InvalidSignature" :message message :op op})]
    (cond
      (not (string? sig))
      (cb (invalid "Operation has no signature."))

      (str/ends-with? sig "=")
      (cb (invalid "Signature must be unpadded base64url."))

      :else
      (let [sig-bytes (runtime.crypto/base64url-decode sig)
            payload (try
                      (signing-payload op)
                      (catch #?(:clj Exception :cljs :default) e
                        (ex-data e)))]
        (cond
          (nil? sig-bytes) (cb (invalid "Signature is not valid base64url."))
          (:error payload) (cb payload)
          :else
          (letfn [(try-keys [[did-key & more]]
                    (if (nil? did-key)
                      (cb (invalid "Signature not valid for any allowed key."))
                      (crypto/verify-did-sig
                       did-key sig-bytes payload
                       :callback
                       (fn [res]
                         (if (true? res)
                           (cb {:did-key did-key})
                           ;; false or malformed did:key: try the next one
                           (try-keys more))))))]
            (try-keys (seq allowed-did-keys))))))
    val))

(defn verify-create-op
  "Validate a genesis operation for the given DID: not a tombstone,
  signed by one of its own rotation keys, null :prev, and the DID
  matches did-for-create-op. Async; yields the DocumentData map or an
  error map. ref: operations.ts assureValidCreationOp"
  [did op & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (if (tombstone? op)
      (cb {:error "MisorderedOperation"
           :message "Genesis operation cannot be a tombstone."})
      (let [normalized (normalize-op op)]
        (verify-op-sig (:rotationKeys normalized) op
                       :callback
                       (fn [{:keys [error] :as res}]
                         (cond
                           error
                           (cb res)

                           (some? (:prev op))
                           (cb {:error "ImproperOperation"
                                :message "Expected null prev on genesis operation."})

                           :else
                           (let [expected (did-for-create-op op)]
                             (if (= expected did)
                               (cb (op->data did normalized))
                               (cb {:error "GenesisHashMismatch"
                                    :message "Hash of genesis operation does not match DID identifier."
                                    :expected expected
                                    :did did}))))))))
    val))

(defn- same-cid?
  "Compare two CID strings by parsed value (robust to multibase choice)."
  [a b]
  (let [ca (when (string? a) (data/parse-cid a))
        cb (when (string? b) (data/parse-cid b))]
    (and ca cb (= ca cb))))

(defn- verify-plain-log
  "Port of data.ts validateOperationLog over a vector of plain operations."
  [did ops cb]
  (let [n (count ops)]
    (if-not (and (s/valid? ::compatible-op (first ops))
                 (every? #(s/valid? ::op-or-tombstone %) (rest ops)))
      (cb {:error "ImproperOperation"
           :message "Improperly formatted operation in log."})
      (verify-create-op
       did (first ops)
       :callback
       (fn [{:keys [error] :as doc}]
         (if error
           (cb doc)
           (letfn [(step [i doc prev-cid]
                     (if (= i n)
                       (cb doc)
                       (let [op (nth ops i)]
                         (if-not (and (:prev op) (same-cid? (:prev op) prev-cid))
                           (cb {:error "MisorderedOperation"
                                :message "Operation does not reference the CID of its predecessor."})
                           (verify-op-sig
                            (:rotationKeys doc) op
                            :callback
                            (fn [{:keys [error] :as res}]
                              (cond
                                error
                                (cb res)

                                (tombstone? op)
                                (if (= i (dec n))
                                  (cb {:tombstoned true})
                                  (cb {:error "MisorderedOperation"
                                       :message "Tombstone must be the final operation."}))

                                :else
                                (step (inc i) (op->data did op) (cid-for-op op)))))))))]
             (step 1 doc (cid-for-op (first ops))))))))))

(defn- instant-ms
  "Epoch milliseconds of an ISO-8601 timestamp string."
  [s]
  #?(:clj (.toEpochMilli (java.time.Instant/parse s))
     :cljs (.parse js/Date s)))

(defn- verify-audit-entries
  "Replay an auditable log ({:operation :cid :createdAt ...} entries),
  allowing nullified forks within the 72h recovery window
  (ref: data.ts assureValidNextOp)."
  [did entries cb]
  (let [n (count entries)
        bad-cid (first (remove #(same-cid? (:cid %)
                                           (try
                                             (cid-for-op (:operation %))
                                             (catch #?(:clj Exception :cljs :default) _ nil)))
                               entries))]
    (if bad-cid
      (cb {:error "CidMismatch"
           :message "Recorded CID does not match the operation bytes."
           :cid (:cid bad-cid)})
      (letfn [(finish [canonical]
                (let [last-op (:operation (peek canonical))]
                  (cb (if (tombstone? last-op)
                        {:tombstoned true}
                        (op->data did last-op)))))
              (step [i canonical]
                (if (= i n)
                  (finish canonical)
                  (let [{:keys [operation] :as entry} (nth entries i)
                        idx (when (:prev operation)
                              (first (keep-indexed
                                      (fn [j e]
                                        (when (same-cid? (:prev operation) (:cid e)) j))
                                      canonical)))]
                    (if (nil? idx)
                      (cb {:error "MisorderedOperation"
                           :message "Operation does not reference a CID in the active history."})
                      (let [in-history (subvec canonical 0 (inc idx))
                            nullified (subvec canonical (inc idx))
                            last-op (:operation (peek in-history))]
                        (if (tombstone? last-op)
                          (cb {:error "MisorderedOperation"
                               :message "Cannot apply an operation to a tombstone."})
                          (let [rotation-keys (:rotationKeys (normalize-op last-op))]
                            (if (empty? nullified)
                              (verify-op-sig
                               rotation-keys operation
                               :callback
                               (fn [{:keys [error] :as res}]
                                 (if error
                                   (cb res)
                                   (step (inc i) (conj in-history entry)))))
                              ;; fork: a higher-authority rotation key rewrites history
                              (verify-op-sig
                               rotation-keys (:operation (first nullified))
                               :callback
                               (fn [{:keys [error did-key] :as res}]
                                 (if error
                                   (cb res)
                                   (let [more-powerful (vec (take-while #(not= % did-key)
                                                                        rotation-keys))]
                                     (verify-op-sig
                                      more-powerful operation
                                      :callback
                                      (fn [{:keys [error] :as res}]
                                        (cond
                                          error
                                          (cb res)

                                          ;; timestamps must increase monotonically
                                          (<= (instant-ms (:createdAt entry))
                                              (instant-ms (:createdAt (nth entries (dec i)))))
                                          (cb {:error "MisorderedOperation"
                                               :message "Operation timestamps must increase monotonically."})

                                          (< recovery-window-ms
                                             (- (instant-ms (:createdAt entry))
                                                (instant-ms (:createdAt (first nullified)))))
                                          (cb {:error "LateRecovery"
                                               :message "Recovery operation occurred outside of the allowed 72 hr recovery window."})

                                          :else
                                          (step (inc i) (conj in-history entry)))))))))))))))))]
        (verify-create-op
         did (:operation (first entries))
         :callback
         (fn [{:keys [error] :as doc}]
           (if error
             (cb doc)
             (step 1 [(first entries)]))))))))

(defn verify-operation-log
  "Validate a did:plc operation log end-to-end and compute the final
  state. Accepts either the plain operation log (GET /{did}/log) or the
  auditable log entries (GET /{did}/log/audit, {:operation :cid
  :nullified :createdAt} maps); the auditable form additionally allows
  nullified forks signed by a higher-authority rotation key within the
  72-hour recovery window.

  Async; yields the final DocumentData
  {:did :verificationMethods :rotationKeys :alsoKnownAs :services},
  or {:tombstoned true}, or an error map ({:error \"MisorderedOperation\"|
  \"InvalidSignature\"|\"LateRecovery\"|\"ImproperOperation\"|
  \"GenesisHashMismatch\"|\"CidMismatch\" ...}).
  ref: data.ts validateOperationLog"
  [did ops & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))
        ops (vec ops)]
    (cond
      (empty? ops)
      (cb {:error "ImproperOperation" :message "Empty operation log."})

      (contains? (first ops) :operation)
      (verify-audit-entries did ops cb)

      :else
      (verify-plain-log did ops cb))
    val))
