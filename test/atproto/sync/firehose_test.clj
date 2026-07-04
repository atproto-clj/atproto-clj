(ns atproto.sync.firehose-test
  "Firehose client tests.

  Fixture frames are built with the SDK's own DAG-CBOR/CAR/frame codecs
  (each independently pinned byte-exact against vendored interop fixtures
  from the reference implementation — see test/interop-test-files and
  test/atproto/xrpc/frame_fixtures.json), then replayed over an in-process
  http-kit WebSocket server. No live network."
  (:require [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as httpkit]
            [atproto.crypto :as crypto]
            [atproto.data :as data]
            [atproto.data.cbor :as cbor]
            [atproto.identity :as identity]
            [atproto.lexicon :as lexicon]
            [atproto.repo :as repo]
            [atproto.repo.blockstore :as blockstore]
            [atproto.repo.car :as car]
            [atproto.repo.sync :as repo-sync]
            [atproto.repo.test-support.util :as util]
            [atproto.sync.cursor :as cursor]
            [atproto.sync.firehose :as firehose]
            [atproto.sync.runner :as runner]
            [atproto.support.ws-server :as ws-server]
            [atproto.xrpc.frames :as frames]))

(set! *warn-on-reflection* true)

(def did "did:plc:44ybard66vv44zksje25o7dz")

;; -----------------------------------------------------------------------------
;; Fixture construction
;; -----------------------------------------------------------------------------

(defn- record-block
  [record]
  (let [bytes (cbor/encode record)]
    {:cid (data/cid-for record) :bytes bytes :record record}))

(defn- commit-message
  "A realistic #commit wire message: ops over records, blocks = a real CAR."
  [& {:keys [seq ops-specs rev since]
      :or {rev "3lb2wxg22mk2u" since "3lb2wxfz44k2u"}}]
  (let [blocks (into {} (for [{:keys [record]} ops-specs :when record]
                          (let [{:keys [cid bytes]} (record-block record)]
                            [cid bytes])))
        commit-block (record-block {:did did :version 3 :rev rev})
        car-bytes (car/write-car (:cid commit-block)
                                 (cons commit-block
                                       (map (fn [[cid bytes]] {:cid cid :bytes bytes})
                                            blocks)))]
    {:$type "com.atproto.sync.subscribeRepos#commit"
     :seq seq
     :rebase false
     :tooBig false
     :repo did
     :commit (:cid commit-block)
     :rev rev
     :since since
     :blocks car-bytes
     :ops (mapv (fn [{:keys [action path record]}]
                  {:action action
                   :path path
                   :cid (when record (data/cid-for record))
                   :prev nil})
                ops-specs)
     :blobs []
     :time "2026-07-04T12:00:00.000Z"}))

(defn- identity-message
  [& {:keys [seq handle] :or {seq 2}}]
  (cond-> {:$type "com.atproto.sync.subscribeRepos#identity"
           :seq seq :did did :time "2026-07-04T12:00:01.000Z"}
    handle (assoc :handle handle)))

(defn- account-message
  [& {:keys [seq active status] :or {seq 3 active true}}]
  (cond-> {:$type "com.atproto.sync.subscribeRepos#account"
           :seq seq :did did :time "2026-07-04T12:00:02.000Z" :active active}
    status (assoc :status status)))

(defn- sync-message
  [& {:keys [seq] :or {seq 4}}]
  (let [commit-block (record-block {:did did :version 3 :rev "3lb2wxg22mk2u"})]
    {:$type "com.atproto.sync.subscribeRepos#sync"
     :seq seq :did did
     :blocks (car/write-car (:cid commit-block) [commit-block])
     :rev "3lb2wxg22mk2u"
     :time "2026-07-04T12:00:03.000Z"}))

(defn- message->frame-bytes
  [message]
  (frames/encode (frames/message-frame message :nsid firehose/nsid)))

(def post-record {:$type "app.bsky.feed.post"
                  :text "hello world"
                  :createdAt "2026-07-04T12:00:00.000Z"})
(def status-record {:$type "xyz.statusphere.status"
                    :status "🦋"
                    :createdAt "2026-07-04T12:00:00.000Z"})

;; -----------------------------------------------------------------------------
;; Parse layer
;; -----------------------------------------------------------------------------

(deftest body-with-type-test
  (is (= {:a 1 :$type "com.atproto.sync.subscribeRepos#commit"}
         (firehose/body-with-type firehose/nsid {:t "#commit" :body {:a 1}})))
  (is (= {:a 1 :$type "com.example.other#thing"}
         (firehose/body-with-type firehose/nsid {:t "com.example.other#thing" :body {:a 1}})))
  (is (= {:a 1}
         (firehose/body-with-type firehose/nsid {:body {:a 1}}))))

(deftest match-collection-fn-test
  (let [f (firehose/match-collection-fn ["app.bsky.feed.post" "xyz.statusphere.*"])]
    (is (true? (f "app.bsky.feed.post")))
    (is (false? (f "app.bsky.feed.like")))
    (is (true? (f "xyz.statusphere.status")))
    (is (false? (f "xyz.statusphereish.status"))))
  (is (true? ((firehose/match-collection-fn nil) "anything.at.all")))
  (is (true? ((firehose/match-collection-fn []) "anything.at.all"))))

(deftest parse-commit-test
  (testing "create + delete fan out with records extracted from the CAR"
    (let [message (commit-message
                   :seq 10
                   :ops-specs [{:action "create"
                                :path "app.bsky.feed.post/3kabc" :record post-record}
                               {:action "delete"
                                :path "app.bsky.feed.like/3kdef"}])
          {:keys [events error]} (firehose/parse-message message)]
      (is (nil? error))
      (is (= 2 (count events)))
      (let [[create delete] events]
        (is (= :create (:kind create)))
        (is (= 10 (:seq create)))
        (is (= did (:did create)))
        (is (= "app.bsky.feed.post" (:collection create)))
        (is (= "3kabc" (:rkey create)))
        (is (= (str "at://" did "/app.bsky.feed.post/3kabc") (:uri create)))
        (is (data/eq? post-record (:record create)))
        (is (= (data/cid-for post-record) (:cid create)))
        (is (= "3lb2wxg22mk2u" (:rev create)))
        (is (= "3lb2wxfz44k2u" (:since create)))
        (is (contains? (:blocks create) (data/cid-for post-record)))
        (is (data/cid? (:commit create)))
        (is (= :delete (:kind delete)))
        (is (= "app.bsky.feed.like" (:collection delete)))
        (is (not (contains? delete :record)))
        (is (not (contains? delete :cid))))))
  (testing "update action"
    (let [message (commit-message
                   :seq 11
                   :ops-specs [{:action "update"
                                :path "xyz.statusphere.status/self"
                                :record status-record}])
          {:keys [events]} (firehose/parse-message message)]
      (is (= :update (:kind (first events))))
      (is (data/eq? status-record (:record (first events))))))
  (testing "missing record block fails the message"
    (let [message (-> (commit-message
                       :seq 12
                       :ops-specs [{:action "create"
                                    :path "app.bsky.feed.post/3kabc"
                                    :record post-record}])
                      ;; CAR without the record block
                      (assoc :blocks (:blocks (sync-message))))
          result (firehose/parse-message message)]
      (is (= "FirehoseParseError" (:error result)))))
  (testing "tooBig commits are a parse error"
    (let [message (assoc (commit-message :seq 13 :ops-specs []) :tooBig true)]
      (is (= "FirehoseParseError" (:error (firehose/parse-message message))))))
  (testing "garbage CAR bytes are a parse error"
    (let [message (assoc (commit-message :seq 14 :ops-specs [])
                         :blocks (byte-array [1 2 3]))]
      (is (= "FirehoseParseError" (:error (firehose/parse-message message))))))
  (testing "unknown action is a parse error"
    (let [message (-> (commit-message
                       :seq 15
                       :ops-specs [{:action "create"
                                    :path "app.bsky.feed.post/3kabc"
                                    :record post-record}])
                      (assoc-in [:ops 0 :action] "explode"))]
      (is (= "FirehoseParseError" (:error (firehose/parse-message message)))))))

(deftest parse-commit-filtering-test
  (let [message (commit-message
                 :seq 20
                 :ops-specs [{:action "create"
                              :path "app.bsky.feed.post/3kabc" :record post-record}
                             {:action "create"
                              :path "xyz.statusphere.status/self" :record status-record}
                             {:action "delete"
                              :path "app.bsky.graph.follow/3kxyz"}])]
    (testing "exact NSID"
      (let [{:keys [events]} (firehose/parse-message
                              message :filter-collections ["app.bsky.feed.post"])]
        (is (= [["app.bsky.feed.post" :create]]
               (map (juxt :collection :kind) events)))))
    (testing "prefix pattern"
      (let [{:keys [events]} (firehose/parse-message
                              message :filter-collections ["xyz.statusphere.*"])]
        (is (= ["xyz.statusphere.status"] (map :collection events)))))
    (testing "deletes are filtered too"
      (let [{:keys [events]} (firehose/parse-message
                              message :filter-collections ["app.bsky.graph.follow"])]
        (is (= [:delete] (map :kind events)))))))

(deftest parse-sync-test
  (let [message (sync-message :seq 30)
        {:keys [events error]} (firehose/parse-message message)]
    (is (nil? error))
    (let [event (first events)]
      (is (= :sync (:kind event)))
      (is (= 30 (:seq event)))
      (is (= did (:did event)))
      (is (data/cid? (:cid event)))
      (is (contains? (:blocks event) (:cid event)))
      (is (= "3lb2wxg22mk2u" (:rev event))))))

(deftest parse-identity-account-info-test
  (testing "identity with and without handle"
    (is (= {:kind :identity :seq 2 :time "2026-07-04T12:00:01.000Z"
            :did did :handle "alice.test"}
           (first (:events (firehose/parse-message (identity-message :handle "alice.test"))))))
    (is (not (contains? (first (:events (firehose/parse-message (identity-message))))
                        :handle))))
  (testing "account, including open statuses"
    (let [event (first (:events (firehose/parse-message
                                 (account-message :active false :status "desynchronized"))))]
      (is (= :account (:kind event)))
      (is (false? (:active event)))
      (is (= "desynchronized" (:status event))))
    (is (not (contains? (first (:events (firehose/parse-message (account-message))))
                        :status))))
  (testing "#info passes through"
    (is (= {:kind :info :name "OutdatedCursor" :message "Requested cursor exceeded limit."}
           (first (:events (firehose/parse-message
                            {:$type "com.atproto.sync.subscribeRepos#info"
                             :name "OutdatedCursor"
                             :message "Requested cursor exceeded limit."}))))))
  (testing "unknown $type is a parse error"
    (is (= "FirehoseParseError"
           (:error (firehose/parse-message {:$type "com.atproto.sync.subscribeRepos#warp"
                                            :seq 1}))))))

;; -----------------------------------------------------------------------------
;; Verified commit parsing (stubbed WS-04 verification)
;; -----------------------------------------------------------------------------

(deftest parse-commit-verified-test
  (let [message (commit-message
                 :seq 40
                 :ops-specs [{:action "create"
                              :path "app.bsky.feed.post/3kabc" :record post-record}
                             {:action "delete"
                              :path "app.bsky.feed.like/3kdef"}])
        match-all (firehose/match-collection-fn nil)
        key-calls (atom [])
        resolve-key (fn [_did force-refresh? cb]
                      (swap! key-calls conj force-refresh?)
                      (cb "did:key:zStub"))]
    (testing "verified commits yield events; claims carry cid (nil for delete)"
      (let [verify-calls (atom [])
            result (promise)]
        (with-redefs [repo-sync/verify-proofs
                      (fn [_car claims _did did-key & {:keys [callback]}]
                        (swap! verify-calls conj {:claims claims :did-key did-key})
                        (callback {:verified claims :unverified []}))]
          (reset! key-calls [])
          (firehose/parse-commit-verified resolve-key message match-all #(deliver result %))
          (let [{:keys [events error]} (deref result 5000 {:error "Timeout"})]
            (is (nil? error))
            (is (= [:create :delete] (map :kind events)))
            (is (= [false] @key-calls))
            (let [{:keys [claims did-key]} (first @verify-calls)]
              (is (= "did:key:zStub" did-key))
              (is (= [{:collection "app.bsky.feed.post" :rkey "3kabc"
                       :cid (data/cid-for post-record)}
                      {:collection "app.bsky.feed.like" :rkey "3kdef" :cid nil}]
                     claims)))))))
    (testing "verification failure retries once with a forced key refresh"
      (let [attempts (atom 0)
            result (promise)]
        (with-redefs [repo-sync/verify-proofs
                      (fn [_car claims _did _key & {:keys [callback]}]
                        (if (= 1 (swap! attempts inc))
                          (callback {:error "RepoVerification" :message "bad sig"})
                          (callback {:verified claims :unverified []})))]
          (reset! key-calls [])
          (firehose/parse-commit-verified resolve-key message match-all #(deliver result %))
          (let [{:keys [events error]} (deref result 5000 {:error "Timeout"})]
            (is (nil? error))
            (is (= 2 (count events)))
            (is (= [false true] @key-calls))
            (is (= 2 @attempts))))))
    (testing "persistent verification failure is delivered after exactly two attempts"
      (let [attempts (atom 0)
            result (promise)]
        (with-redefs [repo-sync/verify-proofs
                      (fn [_car _claims _did _key & {:keys [callback]}]
                        (swap! attempts inc)
                        (callback {:error "RepoVerification" :message "bad sig"}))]
          (reset! key-calls [])
          (firehose/parse-commit-verified resolve-key message match-all #(deliver result %))
          (let [{:keys [error]} (deref result 5000 {})]
            (is (= "RepoVerification" error))
            (is (= 2 @attempts))
            (is (= [false true] @key-calls))))))
    (testing "unverified claims fail verification"
      (let [result (promise)]
        (with-redefs [repo-sync/verify-proofs
                      (fn [_car claims _did _key & {:keys [callback]}]
                        (callback {:verified [] :unverified claims}))]
          (firehose/parse-commit-verified resolve-key message match-all #(deliver result %))
          (is (= "RepoVerification" (:error (deref result 5000 {})))))))))

;; -----------------------------------------------------------------------------
;; Verified mode, end to end: a real signed commit through verify-proofs
;; -----------------------------------------------------------------------------

(def ^:private keypair
  (delay (util/ok! (util/result-of (crypto/generate "ES256K")))))

(defn- signed-commit-message
  "A #commit wire message for a real commit: an actual repo, an actual
  ES256K signature, and a CAR of the commit's relevant blocks."
  [& {:keys [seq] :or {seq 1}}]
  (let [storage (blockstore/memory-blockstore)
        r (util/ok! (util/result-of (repo/create storage did @keypair)))
        write {:action :create
               :collection "app.bsky.feed.post"
               :rkey "3kabc"
               :value post-record}
        commit-data (util/ok! (util/result-of
                               (repo/format-commit r write @keypair)))]
    {:$type "com.atproto.sync.subscribeRepos#commit"
     :seq seq
     :rebase false
     :tooBig false
     :repo did
     :commit (:cid commit-data)
     :rev (:rev commit-data)
     :since (:since commit-data)
     :blocks (car/write-car (:cid commit-data)
                            (map (fn [[cid bytes]] {:cid cid :bytes bytes})
                                 (:relevant-blocks commit-data)))
     :ops [{:action "create"
            :path "app.bsky.feed.post/3kabc"
            :cid (data/cid-for post-record)
            :prev nil}]
     :blobs []
     :time "2026-07-04T12:00:00.000Z"}))

(deftest parse-commit-verified-real-signature-test
  (let [message (signed-commit-message)
        match-all (firehose/match-collection-fn nil)]
    (testing "a genuinely signed commit verifies"
      (let [result (promise)]
        (firehose/parse-commit-verified
         (fn [_did _force? cb] (cb (crypto/did @keypair)))
         message match-all #(deliver result %))
        (let [{:keys [events error]} (deref result 10000 {:error "Timeout"})]
          (is (nil? error))
          (is (= [:create] (map :kind events)))
          (is (data/eq? post-record (:record (first events)))))))
    (testing "the wrong signing key is rejected (after one refresh retry)"
      (let [other (util/ok! (util/result-of (crypto/generate "ES256K")))
            calls (atom 0)
            result (promise)]
        (firehose/parse-commit-verified
         (fn [_did _force? cb] (swap! calls inc) (cb (crypto/did other)))
         message match-all #(deliver result %))
        (is (= "RepoVerification" (:error (deref result 10000 {:error "Timeout"}))))
        (is (= 2 @calls))))))

;; -----------------------------------------------------------------------------
;; Socket layer
;; -----------------------------------------------------------------------------

(defn- replay-server
  "WebSocket server that captures per-connection query strings and sends the
  frames returned by (frames-fn connection-number) on open."
  [frames-fn & {:keys [queries channels]}]
  (let [conns (atom 0)]
    (ws-server/start!
     :ring-handler
     (fn [req]
       (let [n (swap! conns inc)]
         (when queries (swap! queries conj (:query-string req)))
         (httpkit/as-channel
          req
          {:on-open (fn [ch]
                      (when channels (swap! channels conj ch))
                      (doseq [^bytes frame (frames-fn n)]
                        (httpkit/send! ch frame)))}))))))

(defn- collecting-handler
  "Handler collecting events into an atom, delivering the promise once
  (pred events) is true."
  [events done pred]
  (fn [event]
    (let [evs (swap! events conj event)]
      (when (pred evs)
        (deliver done true)))))

(deftest consume-verify-mode-test
  (let [message (signed-commit-message :seq 7)
        server (replay-server (fn [n] (when (= 1 n) [(message->frame-bytes message)])))
        done (promise)
        errors (atom [])
        handle (firehose/consume
                {:service (str "ws://127.0.0.1:" (:port server))
                 :handler (fn [event] (deliver done event))
                 :verify? true
                 :resolve-key-fn (fn [_did _force? cb] (cb (crypto/did @keypair)))
                 :on-error (fn [err] (swap! errors conj err))})]
    (try
      (let [event (deref done 10000 ::timeout)]
        (is (= :create (:kind event)))
        (is (= 7 (:seq event)))
        (is (data/eq? post-record (:record event)))
        (is (empty? @errors)))
      (finally
        (firehose/stop! handle)
        (ws-server/stop! server)))))

(deftest consume-delivers-typed-events-test
  (let [messages [(commit-message
                   :seq 1
                   :ops-specs [{:action "create"
                                :path "app.bsky.feed.post/3kabc" :record post-record}])
                  (identity-message :seq 2 :handle "alice.test")
                  (account-message :seq 3 :status "active")
                  (sync-message :seq 4)]
        server (replay-server (fn [n] (when (= 1 n) (map message->frame-bytes messages))))
        events (atom [])
        done (promise)
        store (cursor/memory-store)
        handle (firehose/consume
                {:service (str "ws://127.0.0.1:" (:port server))
                 :handler (collecting-handler events done #(= 4 (count %)))
                 :cursor-store store
                 :on-error (fn [err] (println "unexpected on-error:" (pr-str err)))})]
    (try
      (is (true? (deref done 5000 ::timeout)))
      (let [[commit ident account sync-ev] @events]
        (is (= :create (:kind commit)))
        (is (data/eq? post-record (:record commit)))
        (is (= :identity (:kind ident)))
        (is (= "alice.test" (:handle ident)))
        (is (= :account (:kind account)))
        (is (= "active" (:status account)))
        (is (= :sync (:kind sync-ev))))
      ;; Cursor advanced to the last handled seq.
      (let [c (promise)]
        (cursor/get-cursor store #(deliver c %))
        (is (= {:cursor 4} (deref c 1000 ::timeout))))
      (finally
        (firehose/stop! handle)
        (ws-server/stop! server)))))

(deftest consume-cursor-on-connect-test
  (let [queries (atom [])
        server (replay-server (fn [_] nil) :queries queries)
        handle (firehose/consume
                {:service (str "ws://127.0.0.1:" (:port server))
                 :handler (fn [_])
                 :cursor-store (cursor/memory-store {:cursor 41})})]
    (try
      (loop [n 0]
        (when (and (empty? @queries) (< n 100))
          (Thread/sleep 20)
          (recur (inc n))))
      (is (= ["cursor=41"] @queries))
      (finally
        (firehose/stop! handle)
        (ws-server/stop! server)))))

(deftest consume-reconnects-from-cursor-test
  (let [queries (atom [])
        channels (atom [])
        server (replay-server
                (fn [n] (when (= 1 n)
                          [(message->frame-bytes (identity-message :seq 42))]))
                :queries queries :channels channels)
        got-event (promise)
        handle (firehose/consume
                {:service (str "ws://127.0.0.1:" (:port server))
                 :handler (fn [event]
                            (when (= 42 (:seq event)) (deliver got-event true)))
                 :cursor-store (cursor/memory-store)
                 :on-error (fn [_])
                 :ws-opts {:max-reconnect-ms 200}})]
    (try
      (is (true? (deref got-event 5000 ::timeout)))
      ;; Drop the TCP connection; the client reconnects with ?cursor=42.
      (ws-server/drop-connection! (first @channels))
      (loop [n 0]
        (when (and (< (count @queries) 2) (< n 200))
          (Thread/sleep 25)
          (recur (inc n))))
      (is (= "cursor=42" (second @queries)))
      (finally
        (firehose/stop! handle)
        (ws-server/stop! server)))))

(deftest consume-gap-and-regression-test
  (let [messages [(identity-message :seq 1)
                  (identity-message :seq 5)     ;; gap
                  (identity-message :seq 3)]    ;; regression
        server (replay-server (fn [n] (when (= 1 n) (map message->frame-bytes messages))))
        events (atom [])
        errors (atom [])
        saw-gap (promise)
        saw-regression (promise)
        handle (firehose/consume
                {:service (str "ws://127.0.0.1:" (:port server))
                 :handler (fn [event]
                            (swap! events conj event)
                            (when (= :gap (:kind event)) (deliver saw-gap event)))
                 :on-error (fn [err]
                             (swap! errors conj err)
                             (when (= "SeqRegression" (:error err))
                               (deliver saw-regression err)))})]
    (try
      (let [gap (deref saw-gap 5000 ::timeout)]
        (is (= {:kind :gap :seq 5 :prev-seq 1} gap)))
      (let [regression (deref saw-regression 5000 ::timeout)]
        (is (= 3 (:seq regression))))
      ;; The regressed message was skipped.
      (is (= [1 5] (keep :seq (remove #(= :gap (:kind %)) @events))))
      (finally
        (firehose/stop! handle)
        (ws-server/stop! server)))))

(deftest consume-error-frame-and-resubscribe-test
  (let [conns (atom 0)
        server (ws-server/start!
                :ring-handler
                (fn [req]
                  (let [n (swap! conns inc)]
                    (httpkit/as-channel
                     req
                     {:on-open (fn [ch]
                                 (when (= 1 n)
                                   (httpkit/send!
                                    ch (frames/encode
                                        (frames/error-frame "ConsumerTooSlow" "too slow")))
                                   ;; real servers close 1008 after an error frame
                                   (.serverClose ^org.httpkit.server.AsyncChannel ch 1008)))}))))
        error-frame-err (promise)
        handle (firehose/consume
                {:service (str "ws://127.0.0.1:" (:port server))
                 :handler (fn [_])
                 :reconnect-delay-ms 50
                 :on-error (fn [err]
                             (when (= "ConsumerTooSlow" (:error err))
                               (deliver error-frame-err err)))})]
    (try
      (let [err (deref error-frame-err 5000 ::timeout)]
        (is (= "ConsumerTooSlow" (:error err)))
        (is (= "too slow" (:message err))))
      ;; The subscription re-subscribes after the error frame + close.
      (loop [n 0]
        (when (and (< @conns 2) (< n 200))
          (Thread/sleep 25)
          (recur (inc n))))
      (is (<= 2 @conns))
      (finally
        (firehose/stop! handle)
        (ws-server/stop! server)))))

(deftest consume-handler-error-test
  (let [messages [(identity-message :seq 1) (identity-message :seq 2)]
        server (replay-server (fn [n] (when (= 1 n) (map message->frame-bytes messages))))
        handled (atom [])
        errors (atom [])
        done (promise)
        store (cursor/memory-store)
        handle (firehose/consume
                {:service (str "ws://127.0.0.1:" (:port server))
                 :handler (fn [event]
                            (swap! handled conj (:seq event))
                            (when (= 1 (:seq event))
                              (throw (ex-info "boom" {})))
                            (when (= 2 (:seq event)) (deliver done true)))
                 :cursor-store store
                 :on-error (fn [err] (swap! errors conj err))})]
    (try
      (is (true? (deref done 5000 ::timeout)))
      (is (= [1 2] @handled))
      (is (some #(= "FirehoseHandlerError" (:error %)) @errors))
      ;; The cursor still advances past the failed event.
      (let [c (promise)]
        (cursor/get-cursor store #(deliver c %))
        (is (= {:cursor 2} (deref c 1000 ::timeout))))
      (finally
        (firehose/stop! handle)
        (ws-server/stop! server)))))

(deftest consume-exclude-test
  (let [messages [(account-message :seq 1)
                  (identity-message :seq 2)]
        server (replay-server (fn [n] (when (= 1 n) (map message->frame-bytes messages))))
        events (atom [])
        done (promise)
        handle (firehose/consume
                {:service (str "ws://127.0.0.1:" (:port server))
                 :handler (fn [event]
                            (swap! events conj event)
                            (when (= 2 (:seq event)) (deliver done true)))
                 :exclude #{:account}})]
    (try
      (is (true? (deref done 5000 ::timeout)))
      (is (= [:identity] (map :kind @events)))
      (finally
        (firehose/stop! handle)
        (ws-server/stop! server)))))

(deftest consume-runner-mode-test
  (let [messages [(commit-message
                   :seq 1
                   :ops-specs [{:action "create"
                                :path "app.bsky.feed.post/3kabc" :record post-record}])
                  (identity-message :seq 2)]
        server (replay-server (fn [n] (when (= 1 n) (map message->frame-bytes messages))))
        store (cursor/memory-store)
        r (runner/memory-runner :cursor-store store)
        events (atom [])
        done (promise)
        handle (firehose/consume
                {:service (str "ws://127.0.0.1:" (:port server))
                 :handler (fn [event]
                            (swap! events conj event)
                            (when (= 2 (:seq event)) (deliver done true)))
                 :runner r})]
    (try
      (is (true? (deref done 5000 ::timeout)))
      (let [drained (promise)]
        (runner/drain! r #(deliver drained %))
        (is (= {} (deref drained 5000 ::timeout))))
      (is (= [:create :identity] (map :kind @events)))
      (let [c (promise)]
        (cursor/get-cursor store #(deliver c %))
        (is (= {:cursor 2} (deref c 1000 ::timeout))))
      (finally
        (firehose/stop! handle)
        (runner/destroy! r)
        (ws-server/stop! server)))))

(deftest consume-config-validation-test
  (is (thrown? clojure.lang.ExceptionInfo
               (firehose/consume {:handler (fn [_])
                                  :runner (runner/memory-runner)
                                  :cursor-store (cursor/memory-store)})))
  (is (thrown? clojure.lang.ExceptionInfo
               (firehose/consume {:service "wss://example.com"}))))

;; -----------------------------------------------------------------------------
;; Lexicon validation mode
;; -----------------------------------------------------------------------------

(defonce ^:private registered-lexicon
  (delay (lexicon/register-specs! (lexicon/load-resources! "lexicons"))))

(deftest consume-validate-test
  @registered-lexicon
  (let [valid (identity-message :seq 1)
        ;; #identity requires :did
        invalid (dissoc (identity-message :seq 2) :did)
        server (replay-server
                (fn [n] (when (= 1 n) (map message->frame-bytes [invalid valid]))))
        events (atom [])
        errors (atom [])
        done (promise)
        handle (firehose/consume
                {:service (str "ws://127.0.0.1:" (:port server))
                 :handler (fn [event]
                            (swap! events conj event)
                            (deliver done true))
                 :validate? true
                 :on-error (fn [err] (swap! errors conj err))})]
    (try
      (is (true? (deref done 5000 ::timeout)))
      ;; the invalid message was skipped with an error; the valid one flowed
      (is (= [1] (map :seq @events)))
      (is (some #(= "InvalidMessage" (:error %)) @errors))
      (finally
        (firehose/stop! handle)
        (ws-server/stop! server)))))

;; -----------------------------------------------------------------------------
;; Identity enrichment
;; -----------------------------------------------------------------------------

(defn- did-doc-for
  [handle]
  {:id did
   :alsoKnownAs [(str "at://" handle)]
   :verificationMethod []
   :service []})

(defn- run-enrichment-scenario
  "Start a one-identity-frame server and consume with :resolve-identity? true
  while `redefs-thunk` (a fn wrapping firehose/consume + assertions) runs
  entirely inside with-redefs — enrichment happens on the listener thread,
  so the redefs must stay in effect until the event is delivered."
  [resolve-did-stub resolve-handle-stub assert-fn]
  (let [server (replay-server
                (fn [n] (when (= 1 n)
                          [(message->frame-bytes
                            (identity-message :seq 1 :handle "wire.handle"))])))
        done (promise)
        errors (atom [])]
    (with-redefs [identity/resolve-did resolve-did-stub
                  identity/resolve-handle (or resolve-handle-stub
                                              identity/resolve-handle)]
      (let [handle (firehose/consume
                    {:service (str "ws://127.0.0.1:" (:port server))
                     :handler (fn [event] (deliver done event))
                     :resolve-identity? true
                     :on-error (fn [err] (swap! errors conj err))})]
        (try
          (assert-fn (deref done 5000 ::timeout) @errors)
          (finally
            (firehose/stop! handle)
            (ws-server/stop! server)))))))

(deftest consume-resolve-identity-test
  (testing "verified handle is attached along with the did-doc"
    (run-enrichment-scenario
     (fn [_did & {:keys [callback]}]
       (callback {:did-doc (did-doc-for "alice.test")}))
     (fn [_handle & {:keys [callback]}]
       (callback {:did did}))
     (fn [event _errors]
       (is (= "alice.test" (:handle event)))
       (is (= did (get-in event [:did-doc :id]))))))
  (testing "unverifiable handle is omitted (not an error)"
    (run-enrichment-scenario
     (fn [_did & {:keys [callback]}]
       (callback {:did-doc (did-doc-for "alice.test")}))
     (fn [_handle & {:keys [callback]}]
       (callback {:did "did:plc:someoneelse"}))
     (fn [event errors]
       (is (not (contains? event :handle)))
       (is (some? (:did-doc event)))
       (is (empty? errors)))))
  (testing "resolution failure passes the raw event through with on-error"
    (run-enrichment-scenario
     (fn [_did & {:keys [callback]}]
       (callback {:error "DidNotFound"}))
     nil
     (fn [event errors]
       (is (= "wire.handle" (:handle event)))
       (is (nil? (:did-doc event)))
       (is (some #(= "DidNotFound" (:error %)) errors))))))
