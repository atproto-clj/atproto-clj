(ns atproto.identity.plc-test
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            #?(:clj [clojure.java.io :as io])
            [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [atproto.runtime.http :as http]
            [atproto.runtime.json :as json]
            [atproto.test-support.http :as fake-http]
            [atproto.test-support.gen :as tsg]
            [atproto.crypto :as crypto]
            [atproto.data :as data]
            [atproto.data.cbor :as cbor]
            [atproto.identity.plc :as plc]))

#?(:clj
   (defn- fixture
     [path]
     (json/read-str (slurp (io/resource (str "atproto/identity/fixtures/" path))))))

(def legacy-did "did:plc:yk4dd2qkboz2yv6tpubpc6co")
(def modern-did "did:plc:ewvi7nxzyoun6zhxrhs64oiz")

;; -----------------------------------------------------------------------------
;; Generators
;; -----------------------------------------------------------------------------

(def gen-cid
  "Real CIDv1 dag-cbor strings (of tiny synthetic operations)."
  (gen/fmap #(plc/cid-for-op {:n %}) gen/nat))

(def gen-unsigned-operation
  (gen/let [rotation (gen/vector tsg/did-key 1 5)
            vm (gen/map (gen/elements [:atproto :signer :labeler]) tsg/did-key
                        {:max-elements 3})
            aka (gen/vector (gen/fmap #(str "at://" %) tsg/hostname) 0 3)
            endpoint tsg/hostname
            prev (gen/one-of [(gen/return nil) gen-cid])]
    {:type "plc_operation"
     :rotationKeys rotation
     :verificationMethods vm
     :alsoKnownAs aka
     :services {:atproto_pds {:type "AtprotoPersonalDataServer"
                              :endpoint (str "https://" endpoint)}}
     :prev prev}))

(def gen-operation
  (gen/let [op gen-unsigned-operation
            sig tsg/base64url]
    (assoc op :sig sig)))

(def gen-tombstone
  (gen/let [prev gen-cid
            sig tsg/base64url]
    {:type "plc_tombstone" :prev prev :sig sig}))

(def gen-legacy-case
  "A legacy create op plus the handle/endpoint its normalization must
  produce (handles may arrive bare, at://-, or http(s)://-prefixed)."
  (gen/let [signing-key tsg/did-key
            recovery-key tsg/did-key
            handle-host tsg/hostname
            handle-style (gen/elements [:bare :at :https])
            service-host tsg/hostname
            service-style (gen/elements [:bare :http :https])
            sig (gen/one-of [(gen/return nil) tsg/base64url])]
    {:legacy (cond-> {:type "create"
                      :signingKey signing-key
                      :recoveryKey recovery-key
                      :handle (case handle-style
                                :bare handle-host
                                :at (str "at://" handle-host)
                                :https (str "https://" handle-host))
                      :service (case service-style
                                 :bare service-host
                                 :http (str "http://" service-host)
                                 :https (str "https://" service-host))
                      :prev nil}
               sig (assoc :sig sig))
     :expected-handle (str "at://" handle-host)
     :expected-endpoint (case service-style
                          :bare (str "https://" service-host)
                          :http (str "http://" service-host)
                          :https (str "https://" service-host))}))

;; -----------------------------------------------------------------------------
;; Specs
;; -----------------------------------------------------------------------------

(defspec operation-spec-spec 100
  (prop/for-all [op gen-operation
                 missing (gen/elements [:type :rotationKeys :verificationMethods
                                        :alsoKnownAs :services :prev :sig])]
    (and (s/valid? ::plc/operation op)
         (s/valid? ::plc/op op)
         (not (s/valid? ::plc/operation (dissoc op missing)))
         ;; rotation keys are 1-5 did:keys
         (not (s/valid? ::plc/operation (assoc op :rotationKeys [])))
         (not (s/valid? ::plc/operation
                        (assoc op :rotationKeys
                               (vec (repeat 6 (first (:rotationKeys op)))))))
         (not (s/valid? ::plc/operation (assoc op :rotationKeys ["not-a-did-key"]))))))

(defspec tombstone-spec-spec 100
  (prop/for-all [ts gen-tombstone
                 extra-key (gen/elements [:services :handle :foo])]
    (and (s/valid? ::plc/tombstone ts)
         (s/valid? ::plc/op ts)
         ;; tombstones are strict: exactly {:type :prev :sig}
         (not (s/valid? ::plc/tombstone (assoc ts extra-key "x")))
         (not (s/valid? ::plc/tombstone (dissoc ts :prev)))
         (not (s/valid? ::plc/tombstone (assoc ts :prev nil))))))

#?(:clj
   (deftest fixture-spec-test
     (let [legacy-genesis (:operation (first (fixture "audit-log-legacy.json")))
           modern-ops (mapv :operation (fixture "audit-log-modern.json"))]
       (is (s/valid? ::plc/legacy-create-op legacy-genesis))
       (is (s/valid? ::plc/compatible-op legacy-genesis))
       (is (not (s/valid? ::plc/operation legacy-genesis)))
       (doseq [op modern-ops]
         (is (s/valid? ::plc/operation op))))))

;; -----------------------------------------------------------------------------
;; normalize-op
;; -----------------------------------------------------------------------------

(defspec normalize-op-spec 100
  ;; ref: operations.ts normalizeOp
  (prop/for-all [{:keys [legacy expected-handle expected-endpoint]} gen-legacy-case
                 op gen-operation
                 ts gen-tombstone]
    (let [n (plc/normalize-op legacy)]
      (and (= "plc_operation" (:type n))
           (= [(:recoveryKey legacy) (:signingKey legacy)] (:rotationKeys n))
           (= {:atproto (:signingKey legacy)} (:verificationMethods n))
           (= [expected-handle] (:alsoKnownAs n))
           (= {:atproto_pds {:type "AtprotoPersonalDataServer"
                             :endpoint expected-endpoint}}
              (:services n))
           (nil? (:prev n))
           ;; signature presence is preserved, never invented
           (= (contains? legacy :sig) (contains? n :sig))
           (or (not (contains? legacy :sig))
               (s/valid? ::plc/operation n))
           ;; idempotent; plc_operation and tombstones pass through
           (= n (plc/normalize-op n))
           (= op (plc/normalize-op op))
           (= ts (plc/normalize-op ts))))))

#?(:clj
   (deftest normalize-op-fixture-test
     ;; real-world legacy create op from the vendored audit log
     (let [legacy (:operation (first (fixture "audit-log-legacy.json")))]
       (is (= {:type "plc_operation"
               :verificationMethods {:atproto (:signingKey legacy)}
               :rotationKeys [(:recoveryKey legacy) (:signingKey legacy)]
               :alsoKnownAs [(str "at://" (:handle legacy))]
               :services {:atproto_pds {:type "AtprotoPersonalDataServer"
                                        :endpoint (:service legacy)}}
               :prev nil
               :sig (:sig legacy)}
              (plc/normalize-op legacy))))))

;; -----------------------------------------------------------------------------
;; Signing payload, CIDs, DID derivation
;; -----------------------------------------------------------------------------

#?(:clj
   (defspec signing-payload-spec 100
     ;; the signing payload is exactly the DAG-CBOR of the op without :sig
     (prop/for-all [op gen-operation]
       (= (dissoc op :sig)
          (cbor/decode (plc/signing-payload op))))))

#?(:clj
   (defspec did-and-cid-derivation-spec 100
     (prop/for-all [op gen-operation]
       (let [did (plc/did-for-create-op op)
             cid (plc/cid-for-op op)]
         (and (re-matches #"did:plc:[a-z2-7]{24}" did)
              (some? (data/parse-cid cid))
              ;; deterministic
              (= did (plc/did-for-create-op op))
              (= cid (plc/cid-for-op op))
              ;; sensitive to every byte, including the signature
              (not= did (plc/did-for-create-op (update op :sig str "x")))
              (not= cid (plc/cid-for-op (update op :sig str "x"))))))))

#?(:clj
   (deftest golden-audit-log-test
     (doseq [[audit-file data-file did] [["audit-log-legacy.json" "data-legacy.json" legacy-did]
                                         ["audit-log-modern.json" "data-modern.json" modern-did]]]
       (testing audit-file
         (let [entries (fixture audit-file)
               genesis (:operation (first entries))]
           (testing "did derivation"
             (is (= did (plc/did-for-create-op genesis))))
           (testing "every recorded cid is recomputed exactly"
             (doseq [{:keys [cid operation]} entries]
               (is (= cid (plc/cid-for-op operation)))))
           (testing "prev chain links each op to its predecessor"
             (doseq [[a b] (partition 2 1 entries)]
               (is (= (:cid a) (:prev (:operation b))))))
           (testing "full verification (audit entries) reaches the directory's data"
             (let [expected (fixture data-file)
                   result (deref (plc/verify-operation-log did entries) 10000 ::timeout)]
               (is (= expected result))))
           (testing "full verification (plain operation log)"
             (let [result (deref (plc/verify-operation-log did (mapv :operation entries))
                                 10000 ::timeout)]
               (is (= (fixture data-file) result)))))))))

#?(:clj
   (deftest golden-verify-rejects-tampering-test
     (let [entries (fixture "audit-log-modern.json")
           ops (mapv :operation entries)]
       (testing "wrong did"
         (is (= "GenesisHashMismatch"
                (:error (deref (plc/verify-operation-log legacy-did ops) 10000 ::timeout)))))
       (testing "mutated op content fails signature verification"
         (let [tampered (assoc-in ops [0 :alsoKnownAs] ["at://mallory.test"])]
           (is (= "InvalidSignature"
                  ;; the genesis DID is derived from the signed bytes, so
                  ;; re-derive it: the tampered op must still fail on :sig
                  (:error (deref (plc/verify-operation-log
                                  (plc/did-for-create-op (first tampered))
                                  tampered)
                                 10000 ::timeout))))))
       (testing "tampered audit cid is rejected"
         (let [tampered (assoc-in (vec entries) [1 :cid]
                                  (plc/cid-for-op (:operation (first entries))))]
           (is (= "CidMismatch"
                  (:error (deref (plc/verify-operation-log modern-did tampered)
                                 10000 ::timeout)))))))))

;; -----------------------------------------------------------------------------
;; Operation building & verification with real keypairs
;; -----------------------------------------------------------------------------

#?(:clj (def recovery-kp (delay (deref (crypto/generate "ES256K") 10000 ::timeout))))
#?(:clj (def regular-kp (delay (deref (crypto/generate "ES256") 10000 ::timeout))))
#?(:clj (def attacker-kp (delay (deref (crypto/generate "ES256K") 10000 ::timeout))))

#?(:clj
   (def keypair-pool
     "Three distinct keypairs reused across generative trials (key
     generation, not signing, is the expensive part)."
     (delay [@recovery-kp @regular-kp @attacker-kp])))

#?(:clj (defn- pool-dids [] (mapv crypto/did @keypair-pool)))
#?(:clj (defn- kp-for [did] (first (filter #(= did (crypto/did %)) @keypair-pool))))

#?(:clj
   (defspec sign-op-round-trip-spec 15
     (prop/for-all [op gen-unsigned-operation
                    signer-idx (gen/choose 0 2)]
       (let [rotation (pool-dids)
             signer (nth @keypair-pool signer-idx)
             op (assoc op :rotationKeys rotation)
             signed (deref (plc/sign-op op signer) 10000 ::timeout)
             others (vec (remove #{(crypto/did signer)} rotation))]
         (and (nil? (:error signed))
              (= op (dissoc signed :sig))
              ;; unpadded base64url signature
              (some? (re-matches #"[A-Za-z0-9_-]+" (:sig signed)))
              ;; verify identifies the signer among the rotation keys
              (= {:did-key (crypto/did signer)}
                 (deref (plc/verify-op-sig rotation signed) 10000 ::timeout))
              ;; the other keys alone do not verify
              (= "InvalidSignature"
                 (:error (deref (plc/verify-op-sig others signed) 10000 ::timeout)))
              ;; padded signatures are rejected outright
              (= "InvalidSignature"
                 (:error (deref (plc/verify-op-sig rotation (update signed :sig str "="))
                                10000 ::timeout)))
              ;; any mutation of the signed content invalidates the signature
              (= "InvalidSignature"
                 (:error (deref (plc/verify-op-sig
                                 rotation (assoc signed :prev (plc/cid-for-op signed)))
                                10000 ::timeout))))))))

#?(:clj
   (defspec create-op-spec 10
     (prop/for-all [handle tsg/hostname
                    pds tsg/hostname
                    signer-idx (gen/choose 0 2)]
       (let [rotation (pool-dids)
             signing-key (first rotation)
             {:keys [error did op]} (deref (plc/create-op {:signing-key signing-key
                                                           :rotation-keys rotation
                                                           :handle handle
                                                           :pds pds
                                                           :signer (nth @keypair-pool signer-idx)})
                                           10000 ::timeout)]
         (and (nil? error)
              (re-matches #"did:plc:[a-z2-7]{24}" did)
              (s/valid? ::plc/operation op)
              (nil? (:prev op))
              ;; genesis validation recomputes the same DID and state
              (= {:did did
                  :verificationMethods {:atproto signing-key}
                  :rotationKeys rotation
                  :alsoKnownAs [(str "at://" handle)]
                  :services {:atproto_pds {:type "AtprotoPersonalDataServer"
                                           :endpoint (str "https://" pds)}}}
                 (deref (plc/verify-create-op did op) 10000 ::timeout)))))))

;; --- model-based update chains ----------------------------------------------

#?(:clj
   (def model-genesis
     "Genesis op (signed once) whose rotation keys are the whole pool."
     (delay
       (deref (plc/create-op {:signing-key (first (pool-dids))
                              :rotation-keys (pool-dids)
                              :handle "alice.test"
                              :pds "pds.test"
                              :signer (first @keypair-pool)})
              10000 ::timeout))))

#?(:clj
   (def gen-update-step
     (gen/one-of
      [(gen/tuple (gen/return :handle) tsg/hostname)
       (gen/tuple (gen/return :pds) tsg/hostname)
       (gen/tuple (gen/return :signing-key) (gen/choose 0 2))
       (gen/tuple (gen/return :rotation-keys)
                  (gen/fmap #(vec (distinct %)) (gen/vector (gen/choose 0 2) 1 3)))])))

#?(:clj
   (defn- apply-update-step
     "Build the next op with the corresponding convenience builder (signed
     by the current first rotation key) and update the model state."
     [{:keys [ops data]} [kind arg]]
     (let [signer (kp-for (first (:rotationKeys data)))
           last-op (peek ops)
           dids (pool-dids)
           op (deref (case kind
                       :handle (plc/update-handle-op last-op signer arg)
                       :pds (plc/update-pds-op last-op signer arg)
                       :signing-key (plc/update-signing-key-op last-op signer (dids arg))
                       :rotation-keys (plc/update-rotation-keys-op
                                       last-op signer (mapv dids arg)))
                     10000 ::timeout)
           data' (case kind
                   :handle (assoc-in data [:alsoKnownAs 0] (str "at://" arg))
                   :pds (assoc-in data [:services :atproto_pds]
                                  {:type "AtprotoPersonalDataServer"
                                   :endpoint (str "https://" arg)})
                   :signing-key (assoc-in data [:verificationMethods :atproto] (dids arg))
                   :rotation-keys (assoc data :rotationKeys (mapv dids arg)))]
       {:ops (conj ops op) :data data'})))

#?(:clj
   (defspec update-chain-model-spec 10
     ;; verify-operation-log over a randomly built chain of convenience-
     ;; builder updates computes exactly the state of a pure model fold
     (prop/for-all [steps (gen/vector gen-update-step 0 4)
                    tombstone? gen/boolean]
       (let [{:keys [did op]} @model-genesis
             init {:ops [op]
                   :data {:did did
                          :verificationMethods {:atproto (first (pool-dids))}
                          :rotationKeys (pool-dids)
                          :alsoKnownAs ["at://alice.test"]
                          :services {:atproto_pds {:type "AtprotoPersonalDataServer"
                                                   :endpoint "https://pds.test"}}}}
             {:keys [ops data]} (reduce apply-update-step init steps)
             ops (if tombstone?
                   (conj ops (deref (plc/tombstone-op (peek ops)
                                                      (kp-for (first (:rotationKeys data))))
                                    10000 ::timeout))
                   ops)
             result (deref (plc/verify-operation-log did ops) 30000 ::timeout)]
         (= (if tombstone? {:tombstoned true} data)
            result)))))

#?(:clj
   (deftest update-chain-adversarial-test
     (let [{:keys [did op]} @model-genesis
           op2 (deref (plc/update-handle-op op @regular-kp "bob.test") 10000 ::timeout)
           rotated (deref (plc/update-rotation-keys-op op2 @regular-kp
                                                       [(crypto/did @recovery-kp)])
                          10000 ::timeout)
           ts (deref (plc/tombstone-op op2 @recovery-kp) 10000 ::timeout)]
       (is (every? #(nil? (:error %)) [op2 rotated ts]))
       (testing "rotation-key updates take effect: a removed key may no longer sign"
         (let [stale (deref (plc/update-handle-op rotated @regular-kp "eve.test")
                            10000 ::timeout)]
           (is (= "InvalidSignature"
                  (:error (deref (plc/verify-operation-log did [op op2 rotated stale])
                                 10000 ::timeout))))))
       (testing "tombstone must be the final operation"
         (is (= "MisorderedOperation"
                (:error (deref (plc/verify-operation-log did [op op2 ts rotated])
                               10000 ::timeout)))))
       (testing "no operation may follow a tombstone"
         (is (= "DidTombstoned"
                (:error (deref (plc/update-handle-op ts @recovery-kp "x.test") 10000 ::timeout))))
         (is (= "DidTombstoned"
                (:error (deref (plc/tombstone-op ts @recovery-kp) 10000 ::timeout)))))
       (testing "broken prev chain"
         (is (= "MisorderedOperation"
                (:error (deref (plc/verify-operation-log
                                did [op op2 (assoc rotated :prev (plc/cid-for-op op))])
                               10000 ::timeout)))))
       (testing "op signed by a non-rotation key"
         (let [outsider (deref (crypto/generate "ES256K") 10000 ::timeout)
               bad (deref (plc/update-handle-op op2 outsider "mallory.test") 10000 ::timeout)]
           (is (= "InvalidSignature"
                  (:error (deref (plc/verify-operation-log did [op op2 bad])
                                 10000 ::timeout)))))))))

;; -----------------------------------------------------------------------------
;; Recovery window (nullified forks in audit logs)
;; -----------------------------------------------------------------------------

#?(:clj
   (defn- entry
     [op created-at]
     {:operation op
      :cid (plc/cid-for-op op)
      :nullified false
      :createdAt created-at}))

#?(:clj (defn- iso [ms] (str (java.time.Instant/ofEpochMilli ms))))

#?(:clj
   (def recovery-fixture
     "Genesis with rotation keys [recovery regular], an op signed by the
     lower-authority (regular) key, and a competing fork from genesis
     signed by the higher-authority (recovery) key. Signed once; the
     properties only vary the timestamps."
     (delay
       (let [{:keys [did op]} (deref (plc/create-op
                                      {:signing-key (crypto/did @regular-kp)
                                       :rotation-keys [(crypto/did @recovery-kp)
                                                       (crypto/did @regular-kp)]
                                       :handle "alice.test"
                                       :pds "pds.test"
                                       :signer @regular-kp})
                                     10000 ::timeout)]
         {:did did
          :genesis op
          :disputed (deref (plc/update-handle-op op @regular-kp "hijacked.test")
                           10000 ::timeout)
          :recovered (deref (plc/update-handle-op op @recovery-kp "recovered.test")
                            10000 ::timeout)}))))

#?(:clj (def t0 1672531200000)) ;; 2023-01-01T00:00:00Z

#?(:clj
   (defspec recovery-window-boundary-spec 30
     ;; a nullifying fork is accepted iff its timestamp strictly increases
     ;; and it lands within 72h of the first nullified op
     (prop/for-all [offset-min (gen/choose (* -2 60) (* 5 24 60))]
       (let [{:keys [did genesis disputed recovered]} @recovery-fixture
             disputed-at (+ t0 (* 24 60 60000))
             res (deref (plc/verify-operation-log
                         did
                         [(entry genesis (iso t0))
                          (entry disputed (iso disputed-at))
                          (entry recovered (iso (+ disputed-at (* offset-min 60000))))])
                        30000 ::timeout)]
         (cond
           (<= offset-min 0) (= "MisorderedOperation" (:error res))
           (<= offset-min (* 72 60)) (= ["at://recovered.test"] (:alsoKnownAs res))
           :else (= "LateRecovery" (:error res)))))))

#?(:clj
   (deftest lower-authority-cannot-nullify-test
     (let [{:keys [did genesis]} @recovery-fixture
           from-recovery (deref (plc/update-handle-op genesis @recovery-kp "held.test")
                                10000 ::timeout)
           attack (deref (plc/update-handle-op genesis @regular-kp "stolen.test")
                         10000 ::timeout)]
       (is (= "InvalidSignature"
              (:error (deref (plc/verify-operation-log
                              did
                              [(entry genesis (iso t0))
                               (entry from-recovery (iso (+ t0 3600000)))
                               (entry attack (iso (+ t0 7200000)))])
                             10000 ::timeout)))))))

;; -----------------------------------------------------------------------------
;; Directory client (stubbed HTTP)
;; -----------------------------------------------------------------------------

(def sample-operation
  {:type "plc_operation"
   :rotationKeys ["did:key:zQ3shhCGUqDKjStzuDxPkTxN6ujddP4RkEKJJouJGRRkaLGbg"]
   :verificationMethods {:atproto "did:key:zQ3shunBKsXixLxKtC5qeSG9E4J5RkGN57im31pcTzbNQnm5w"}
   :alsoKnownAs ["at://alice.test"]
   :services {:atproto_pds {:type "AtprotoPersonalDataServer"
                            :endpoint "https://pds.test"}}
   :prev nil
   :sig "dGVzdA"})

#?(:clj
   (deftest directory-get-test
     (let [did "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa"
           doc {:id did}
           data {:did did :rotationKeys []}
           ops [{:type "plc_operation"}]
           {:keys [handler requests]}
           (fake-http/routed
            [["/log/audit" (fake-http/json-response ops)]
             ["/log/last" (fake-http/json-response (first ops))]
             ["/log" (fake-http/json-response ops)]
             ["/data" (fake-http/json-response data)]
             [(str "/" did) (fake-http/json-response doc)]])]
       (with-redefs [http/handle-request handler]
         (is (= {:did-doc doc} (deref (plc/get-did-doc did) 1000 ::timeout)))
         (is (= {:data data} (deref (plc/get-data did) 1000 ::timeout)))
         (is (= {:ops ops} (deref (plc/get-operation-log did) 1000 ::timeout)))
         (is (= {:ops ops} (deref (plc/get-audit-log did) 1000 ::timeout)))
         (is (= {:op (first ops)} (deref (plc/get-last-op did) 1000 ::timeout)))
         (is (= [(str "https://plc.directory/" did)
                 (str "https://plc.directory/" did "/data")
                 (str "https://plc.directory/" did "/log")
                 (str "https://plc.directory/" did "/log/audit")
                 (str "https://plc.directory/" did "/log/last")]
                (map :url @requests)))))))

#?(:clj
   (deftest directory-error-mapping-test
     (let [did "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa"]
       (testing "404 -> DidNotFound with the directory's message"
         (let [{:keys [handler]} (fake-http/scripted
                                  [(fake-http/json-response 404 {:message "DID not registered: x"})])]
           (with-redefs [http/handle-request handler]
             (is (= {:error "DidNotFound" :did did :message "DID not registered: x"}
                    (deref (plc/get-did-doc did) 1000 ::timeout))))))
       (testing "other failures surface status + message"
         (let [{:keys [handler]} (fake-http/scripted
                                  [(fake-http/json-response 410 {:message "DID is tombstoned"})])]
           (with-redefs [http/handle-request handler]
             (let [res (deref (plc/get-did-doc did) 1000 ::timeout)]
               (is (= "HTTP_410" (:error res)))
               (is (= "DID is tombstoned" (:message res))))))))))

#?(:clj
   (deftest submit-test
     (let [did "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa"]
       (testing "posts the op as JSON"
         (let [{:keys [handler requests]} (fake-http/scripted [{:status 200 :body ""}])]
           (with-redefs [http/handle-request handler]
             (is (= {:success true}
                    (deref (plc/submit did sample-operation :plc-url "https://plc.test")
                           1000 ::timeout)))
             (let [req (first @requests)]
               (is (= :post (:method req)))
               (is (= (str "https://plc.test/" did) (:url req)))
               (is (= sample-operation (json/read-str (:body req))))))))
       (testing "directory rejection surfaces the message"
         (let [{:keys [handler]} (fake-http/scripted
                                  [(fake-http/json-response 400 {:message "invalid signature"})])]
           (with-redefs [http/handle-request handler]
             (let [res (deref (plc/submit did sample-operation) 1000 ::timeout)]
               (is (= "HTTP_400" (:error res)))
               (is (= "invalid signature" (:message res))))))))))

#?(:clj
   (deftest export-test
     (let [lines [{:did "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa"
                   :operation {:type "plc_operation"}
                   :cid "bafyreihltcnuuyqp2jm24aqydpnlj7b6w3ogwrplomrjtg5rifv44mmjey"
                   :nullified false
                   :createdAt "2023-01-01T00:00:00.000Z"}
                  {:did "did:plc:bbbbbbbbbbbbbbbbbbbbbbbb"
                   :operation {:type "plc_tombstone"}
                   :cid "bafyreihltcnuuyqp2jm24aqydpnlj7b6w3ogwrplomrjtg5rifv44mmjey"
                   :nullified false
                   :createdAt "2023-01-02T00:00:00.000Z"}]
           {:keys [handler requests]}
           (fake-http/scripted
            [{:status 200
              :headers {:content-type "application/jsonlines"}
              :body (str/join "\n" (map json/write-str lines))}])]
       (with-redefs [http/handle-request handler]
         (is (= {:ops lines}
                (deref (plc/export :count 2 :after "2022-01-01T00:00:00.000Z") 1000 ::timeout)))
         (let [req (first @requests)]
           (is (= "https://plc.directory/export" (:url req)))
           (is (= {:count "2" :after "2022-01-01T00:00:00.000Z"} (:query-params req))))))))
