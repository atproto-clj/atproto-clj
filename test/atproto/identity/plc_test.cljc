(ns atproto.identity.plc-test
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            #?(:clj [clojure.java.io :as io])
            [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [atproto.runtime.http :as http]
            [atproto.runtime.json :as json]
            [atproto.test-support.http :as fake-http]
            [atproto.crypto :as crypto]
            [atproto.data.cbor :as cbor]
            [atproto.identity.plc :as plc]))

#?(:clj
   (defn- fixture
     [path]
     (json/read-str (slurp (io/resource (str "atproto/identity/fixtures/" path))))))

(def legacy-did "did:plc:yk4dd2qkboz2yv6tpubpc6co")
(def modern-did "did:plc:ewvi7nxzyoun6zhxrhs64oiz")

(def sample-operation
  {:type "plc_operation"
   :rotationKeys ["did:key:zQ3shhCGUqDKjStzuDxPkTxN6ujddP4RkEKJJouJGRRkaLGbg"]
   :verificationMethods {:atproto "did:key:zQ3shunBKsXixLxKtC5qeSG9E4J5RkGN57im31pcTzbNQnm5w"}
   :alsoKnownAs ["at://alice.test"]
   :services {:atproto_pds {:type "AtprotoPersonalDataServer"
                            :endpoint "https://pds.test"}}
   :prev nil
   :sig "dGVzdA"})

(def sample-tombstone
  {:type "plc_tombstone"
   :prev "bafyreihltcnuuyqp2jm24aqydpnlj7b6w3ogwrplomrjtg5rifv44mmjey"
   :sig "dGVzdA"})

;; -----------------------------------------------------------------------------
;; Specs
;; -----------------------------------------------------------------------------

(deftest operation-spec-test
  (is (s/valid? ::plc/operation sample-operation))
  (is (s/valid? ::plc/op sample-operation))
  (testing "rotation keys are 1-5 did:keys"
    (is (not (s/valid? ::plc/operation (assoc sample-operation :rotationKeys []))))
    (is (not (s/valid? ::plc/operation
                       (assoc sample-operation
                              :rotationKeys (vec (repeat 6 "did:key:zQ3shhCGUqDKjStzuDxPkTxN6ujddP4RkEKJJouJGRRkaLGbg"))))))
    (is (not (s/valid? ::plc/operation (assoc sample-operation :rotationKeys ["not-a-did-key"])))))
  (testing "missing required fields"
    (doseq [k [:type :rotationKeys :verificationMethods :alsoKnownAs :services :prev :sig]]
      (is (not (s/valid? ::plc/operation (dissoc sample-operation k))) (str k)))))

(deftest tombstone-spec-test
  (is (s/valid? ::plc/tombstone sample-tombstone))
  (is (s/valid? ::plc/op sample-tombstone))
  (testing "tombstones are strict: no extra keys, prev required"
    (is (not (s/valid? ::plc/tombstone (assoc sample-tombstone :extra "key"))))
    (is (not (s/valid? ::plc/tombstone (dissoc sample-tombstone :prev))))
    (is (not (s/valid? ::plc/tombstone (assoc sample-tombstone :prev nil))))))

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

#?(:clj
   (deftest normalize-op-test
     (let [legacy (:operation (first (fixture "audit-log-legacy.json")))]
       (testing "legacy create -> plc_operation (ref: operations.ts normalizeOp)"
         (is (= {:type "plc_operation"
                 :verificationMethods {:atproto (:signingKey legacy)}
                 :rotationKeys [(:recoveryKey legacy) (:signingKey legacy)]
                 :alsoKnownAs [(str "at://" (:handle legacy))]
                 :services {:atproto_pds {:type "AtprotoPersonalDataServer"
                                          :endpoint (:service legacy)}}
                 :prev nil
                 :sig (:sig legacy)}
                (plc/normalize-op legacy))))
       (testing "unsigned legacy create does not grow a nil :sig"
         (is (not (contains? (plc/normalize-op (dissoc legacy :sig)) :sig))))
       (testing "plc_operation and tombstones pass through"
         (is (= sample-operation (plc/normalize-op sample-operation)))
         (is (= sample-tombstone (plc/normalize-op sample-tombstone)))))))

;; -----------------------------------------------------------------------------
;; Signing payload, CIDs, DID derivation (golden fixtures)
;; -----------------------------------------------------------------------------

#?(:clj
   (deftest signing-payload-test
     (is (= (dissoc sample-operation :sig)
            (cbor/decode (plc/signing-payload sample-operation)))
         "the payload is the DAG-CBOR of the operation without :sig")))

#?(:clj
   (deftest golden-audit-log-test
     (doseq [[audit-file data-file did] [["audit-log-legacy.json" "data-legacy.json" legacy-did]
                                         ["audit-log-modern.json" "data-modern.json" modern-did]]]
       (testing audit-file
         (let [entries (fixture audit-file)
               genesis (:operation (first entries))]
           (testing "did derivation"
             (is (= did (plc/did-for-create-op genesis)))
             (is (re-matches #"did:plc:[a-z2-7]{24}" (plc/did-for-create-op genesis))))
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

#?(:clj (def recovery-kp (delay @(crypto/generate "ES256K"))))
#?(:clj (def regular-kp (delay @(crypto/generate "ES256"))))
#?(:clj (def attacker-kp (delay @(crypto/generate "ES256K"))))
#?(:clj (def signing-kp (delay @(crypto/generate "ES256K"))))

#?(:clj
   (defn- genesis!
     "A signed genesis {:did .. :op ..} with rotation keys
     [recovery regular], signed by the regular key."
     []
     (deref (plc/create-op {:signing-key (crypto/did @signing-kp)
                            :rotation-keys [(crypto/did @recovery-kp)
                                            (crypto/did @regular-kp)]
                            :handle "alice.test"
                            :pds "https://pds.test"
                            :signer @regular-kp})
            10000 ::timeout)))

#?(:clj
   (deftest create-op-test
     (let [{:keys [did op] :as res} (genesis!)]
       (is (nil? (:error res)))
       (is (re-matches #"did:plc:[a-z2-7]{24}" did))
       (is (s/valid? ::plc/operation op))
       (is (= ["at://alice.test"] (:alsoKnownAs op)))
       (is (nil? (:prev op)))
       (is (not (str/ends-with? (:sig op) "=")) "signature is unpadded base64url")
       (testing "verify-create-op accepts it and returns the document data"
         (is (= {:did did
                 :verificationMethods {:atproto (crypto/did @signing-kp)}
                 :rotationKeys [(crypto/did @recovery-kp) (crypto/did @regular-kp)]
                 :alsoKnownAs ["at://alice.test"]
                 :services {:atproto_pds {:type "AtprotoPersonalDataServer"
                                          :endpoint "https://pds.test"}}}
                (deref (plc/verify-create-op did op) 10000 ::timeout))))
       (testing "verify-op-sig identifies the signer"
         (is (= {:did-key (crypto/did @regular-kp)}
                (deref (plc/verify-op-sig (:rotationKeys op) op) 10000 ::timeout))))
       (testing "a non-rotation key is not accepted"
         (is (= "InvalidSignature"
                (:error (deref (plc/verify-op-sig [(crypto/did @attacker-kp)] op)
                               10000 ::timeout)))))
       (testing "padded signatures are rejected"
         (is (= "InvalidSignature"
                (:error (deref (plc/verify-op-sig (:rotationKeys op)
                                                  (update op :sig str "="))
                               10000 ::timeout))))))))

#?(:clj
   (deftest update-chain-test
     (let [{:keys [did op]} (genesis!)
           new-signer (crypto/did @attacker-kp)
           op2 (deref (plc/update-handle-op op @regular-kp "bob.test") 10000 ::timeout)
           op3 (deref (plc/update-pds-op op2 @regular-kp "pds2.test") 10000 ::timeout)
           op4 (deref (plc/update-signing-key-op op3 @regular-kp new-signer) 10000 ::timeout)
           op5 (deref (plc/update-rotation-keys-op op4 @regular-kp [(crypto/did @recovery-kp)])
                      10000 ::timeout)]
       (doseq [op [op2 op3 op4 op5]]
         (is (nil? (:error op))))
       (is (= (plc/cid-for-op op) (:prev op2)))
       (testing "the full chain verifies to the updated state"
         (is (= {:did did
                 :verificationMethods {:atproto new-signer}
                 :rotationKeys [(crypto/did @recovery-kp)]
                 :alsoKnownAs ["at://bob.test"]
                 :services {:atproto_pds {:type "AtprotoPersonalDataServer"
                                          :endpoint "https://pds2.test"}}}
                (deref (plc/verify-operation-log did [op op2 op3 op4 op5]) 10000 ::timeout))))
       (testing "rotation-key updates take effect: the old key may no longer sign"
         (let [op6 (deref (plc/update-handle-op op5 @regular-kp "eve.test") 10000 ::timeout)]
           (is (= "InvalidSignature"
                  (:error (deref (plc/verify-operation-log did [op op2 op3 op4 op5 op6])
                                 10000 ::timeout))))))
       (testing "tombstone terminates the chain"
         (let [ts (deref (plc/tombstone-op op5 @recovery-kp) 10000 ::timeout)]
           (is (s/valid? ::plc/tombstone ts))
           (is (= {:tombstoned true}
                  (deref (plc/verify-operation-log did [op op2 op3 op4 op5 ts])
                         10000 ::timeout)))
           (testing "tombstone must be the final operation"
             (is (= "MisorderedOperation"
                    (:error (deref (plc/verify-operation-log did [op op2 ts op3])
                                   10000 ::timeout)))))
           (testing "no operation may follow a tombstone"
             (is (= "DidTombstoned" (:error (deref (plc/update-handle-op ts @recovery-kp "x.test")
                                                   10000 ::timeout))))
             (is (= "DidTombstoned" (:error (deref (plc/tombstone-op ts @recovery-kp)
                                                   10000 ::timeout)))))))
       (testing "broken prev chain"
         (is (= "MisorderedOperation"
                (:error (deref (plc/verify-operation-log
                                did [op op2 (assoc op3 :prev (plc/cid-for-op op))])
                               10000 ::timeout)))))
       (testing "op signed by a non-rotation key"
         (let [bad (deref (plc/update-handle-op op2 @attacker-kp "mallory.test") 10000 ::timeout)]
           (is (= "InvalidSignature"
                  (:error (deref (plc/verify-operation-log did [op op2 bad]) 10000 ::timeout)))))))))

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

#?(:clj
   (deftest recovery-window-test
     (let [{:keys [did op]} (genesis!)
           ;; signed by the lower-authority (regular) key
           disputed (deref (plc/update-handle-op op @regular-kp "hijacked.test") 10000 ::timeout)
           ;; the higher-authority (recovery) key forks history from genesis
           recovered (deref (plc/update-handle-op op @recovery-kp "recovered.test") 10000 ::timeout)
           genesis-entry (entry op "2023-01-01T00:00:00.000Z")
           disputed-entry (entry disputed "2023-01-02T00:00:00.000Z")]
       (testing "recovery within the 72h window nullifies the disputed op"
         (is (= ["at://recovered.test"]
                (:alsoKnownAs (deref (plc/verify-operation-log
                                      did [genesis-entry
                                           disputed-entry
                                           (entry recovered "2023-01-03T00:00:00.000Z")])
                                     10000 ::timeout)))))
       (testing "recovery after 72h is rejected"
         (is (= "LateRecovery"
                (:error (deref (plc/verify-operation-log
                                did [genesis-entry
                                     disputed-entry
                                     (entry recovered "2023-01-05T00:00:00.001Z")])
                               10000 ::timeout)))))
       (testing "timestamps must increase monotonically"
         (is (= "MisorderedOperation"
                (:error (deref (plc/verify-operation-log
                                did [genesis-entry
                                     disputed-entry
                                     (entry recovered "2023-01-02T00:00:00.000Z")])
                               10000 ::timeout)))))
       (testing "a lower-authority key cannot nullify a higher-authority op"
         (let [from-recovery (deref (plc/update-handle-op op @recovery-kp "held.test")
                                    10000 ::timeout)
               attack (deref (plc/update-handle-op op @regular-kp "stolen.test")
                             10000 ::timeout)]
           (is (= "InvalidSignature"
                  (:error (deref (plc/verify-operation-log
                                  did [genesis-entry
                                       (entry from-recovery "2023-01-02T00:00:00.000Z")
                                       (entry attack "2023-01-02T01:00:00.000Z")])
                                 10000 ::timeout)))))))))

;; -----------------------------------------------------------------------------
;; Directory client (stubbed HTTP)
;; -----------------------------------------------------------------------------

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
