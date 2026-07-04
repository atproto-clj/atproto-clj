(ns atproto.pds.firehose-test
  "Outbox algorithm tests (pure core.async) plus a websocket integration
  test serving com.atproto.sync.subscribeRepos through WS-08's transport
  and consuming raw frames with a vanilla java.net.http.WebSocket client."
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.core.async :as async]
            [org.httpkit.server :as httpkit]
            [atproto.data :as data]
            [atproto.data.cbor :as cbor]
            [atproto.lexicon :as lexicon]
            [atproto.repo.car :as car]
            [atproto.xrpc.frames :as frames]
            [atproto.xrpc.server :as xrpc-server]
            [atproto.xrpc.server.ring :as ring]
            [atproto.test-support.wait :refer [wait-until]]
            [atproto.pds.sequencer :as sequencer]
            [atproto.pds.sequencer.storage :as seq-storage]
            [atproto.pds.sequencer.sqlite :as seq-sqlite]
            [atproto.pds.firehose :as firehose])
  (:import [java.io ByteArrayOutputStream]
           [java.net URI]
           [java.net.http HttpClient WebSocket WebSocket$Listener]
           [java.nio ByteBuffer]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time Duration]
           [java.util.concurrent CompletableFuture]))

(def ^:dynamic *seqr* nil)

(defn- with-sequencer
  [f]
  (let [dir (str (Files/createTempDirectory "firehose-test" (make-array FileAttribute 0)))
        seqr @(sequencer/init {:storage (seq-sqlite/open
                                         {:db-path (str dir "/repo_seq.sqlite")})})]
    (try
      (binding [*seqr* seqr]
        (f))
      (finally
        @(sequencer/close! seqr)
        (run! io/delete-file (reverse (file-seq (io/file dir))))))))

(use-fixtures :each with-sequencer)

(defn- seed-identity!
  [seqr n]
  (dotimes [i n]
    @(sequencer/sequence-identity! seqr "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa"
                                   (str "user" i ".test"))))

(defn- take-with-timeout
  "Take one item, or ::timeout."
  [ch ms]
  (async/alt!!
    ch ([v] v)
    (async/timeout ms) ::timeout))

(defn- collect-until
  "Read messages until (pred msg) or the channel closes; returns the
  vector of everything read (including the matching item)."
  [ch pred ms]
  (loop [acc []]
    (let [msg (take-with-timeout ch ms)]
      (cond
        (= ::timeout msg) (conj acc ::timeout)
        (nil? msg) acc
        (pred msg) (conj acc msg)
        :else (recur (conj acc msg))))))

;; -----------------------------------------------------------------------------
;; Outbox (channel-level)
;; -----------------------------------------------------------------------------

(deftest backfill-cutover-live-exactly-once-test
  (let [seqr *seqr*
        total 100
        _ (seed-identity! seqr 40)
        close-ch (async/chan)
        {:keys [messages]} (firehose/handler seqr {:params {:cursor 0}
                                                   :close-ch close-ch})
        ;; sequence 60 more while the consumer backfills
        writer (async/thread
                 (dotimes [i 60]
                   @(sequencer/sequence-identity!
                     seqr "did:plc:bbbbbbbbbbbbbbbbbbbbbbbb" (str "live" i ".test"))))
        received (collect-until messages #(= total (:seq %)) 5000)]
    (async/<!! writer)
    (is (= (range 1 (inc total)) (map :seq received))
        "every event delivered exactly once, in order, across backfill/cutover/live")
    (is (every? #(= "com.atproto.sync.subscribeRepos#identity" (:$type %)) received))
    (is (every? #(string? (:time %)) received))
    (async/close! close-ch)
    (is (nil? (take-with-timeout messages 1000)))))

(deftest no-cursor-live-tail-test
  (let [seqr *seqr*
        _ (seed-identity! seqr 5)
        close-ch (async/chan)
        {:keys [messages]} (firehose/handler seqr {:params {} :close-ch close-ch})]
    ;; nothing backfilled
    @(sequencer/sequence-identity! seqr "did:plc:cccccccccccccccccccccccc" "live.test")
    (let [msg (take-with-timeout messages 5000)]
      (is (= 6 (:seq msg)))
      (is (= "live.test" (:handle msg))))
    (async/close! close-ch)
    (is (nil? (take-with-timeout messages 1000)))))

(deftest future-cursor-test
  (let [seqr *seqr*
        _ (seed-identity! seqr 2)
        close-ch (async/chan)
        {:keys [messages]} (firehose/handler seqr {:params {:cursor 10}
                                                   :close-ch close-ch})]
    (is (= {:frame/error "FutureCursor"
            :frame/message "Cursor in the future."}
           (take-with-timeout messages 5000)))
    (is (nil? (take-with-timeout messages 1000)))))

(deftest outdated-cursor-test
  (let [seqr *seqr*
        ;; seed three events already aged out of the backfill window
        ;; (appended at the storage level so the timestamps are ours)
        _ (dotimes [i 3]
            (seq-storage/append-event!
             (:storage seqr)
             {:did "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa"
              :event-type "identity"
              :event (cbor/encode {:did "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa"
                                   :handle (str "old" i ".test")})
              :sequenced-at "2020-01-01T00:00:00.000Z"}))
        _ (seed-identity! seqr 2)
        close-ch (async/chan)
        {:keys [messages]} (firehose/handler seqr {:params {:cursor 0}
                                                   :close-ch close-ch})
        info (take-with-timeout messages 5000)]
    (is (= "com.atproto.sync.subscribeRepos#info" (:$type info)))
    (is (= "OutdatedCursor" (:name info)))
    (is (= [4 5] (map :seq [(take-with-timeout messages 5000)
                            (take-with-timeout messages 5000)]))
        "resumes from the earliest in-window event")
    (async/close! close-ch)))

(deftest consumer-too-slow-test
  (let [seqr *seqr*
        close-ch (async/chan)
        ;; a probe subscriber shares the poll loop's delivery batches, so
        ;; once it has seen every event, the outbox listener has been
        ;; offered them too and the bounded buffer has overflowed
        probe-seen (atom 0)
        unsub-probe (sequencer/subscribe seqr (fn [batch] (swap! probe-seen + (count batch))))
        {:keys [messages]} (firehose/handler seqr {:params {} :close-ch close-ch}
                                             :max-buffer-size 2)]
    (seed-identity! seqr 10)
    ;; don't read until delivery has happened, so the consumer can't keep up
    (is (wait-until #(<= 10 @probe-seen)))
    (unsub-probe)
    (let [received (collect-until messages #(:frame/error %) 5000)
          events (butlast received)]
      (is (= {:frame/error "ConsumerTooSlow"
              :frame/message "Stream consumer too slow"}
             (last received)))
      (is (< (count events) 10) "overflow disconnected before delivering everything")
      (is (= (map :seq events) (range 1 (inc (count events))))
          "delivered events are still an in-order prefix"))
    (is (nil? (take-with-timeout messages 1000)))))

(deftest disconnect-unsubscribes-test
  (let [seqr *seqr*
        close-ch (async/chan)
        listener-count #(count (:listeners @(:state seqr)))
        {:keys [messages]} (firehose/handler seqr {:params {} :close-ch close-ch})]
    (is (wait-until #(= 1 (listener-count))))
    (async/close! close-ch)
    (is (nil? (take-with-timeout messages 1000)))
    (is (wait-until #(zero? (listener-count))))))

;; -----------------------------------------------------------------------------
;; Websocket integration through WS-08's transport
;; -----------------------------------------------------------------------------

(defonce firehose-lexicon
  (delay (lexicon/load-resources! "lexicons")))

(defmethod xrpc-server/handle-subscription firehose/nsid
  [app-ctx request]
  (firehose/handler (:sequencer app-ctx) request))

(defn- ws-collect
  "Connect and collect decoded binary frames until the server closes,
  the socket is aborted after :abort-after frames, or 5s elapse."
  [url & {:keys [abort-after]}]
  (let [frames (atom [])
        result (promise)
        buf (ByteArrayOutputStream.)
        listener (reify WebSocket$Listener
                   (onOpen [_ ws]
                     (.request ws 1))
                   (onBinary [_ ws data last?]
                     (let [^ByteBuffer data data
                           bytes (byte-array (.remaining data))]
                       (.get data bytes)
                       (.write buf bytes 0 (alength bytes)))
                     (when last?
                       (swap! frames conj (frames/decode (.toByteArray buf)))
                       (.reset buf))
                     (when (and abort-after (<= abort-after (count @frames)))
                       (.abort ws)
                       (deliver result {:frames @frames :close :aborted}))
                     (.request ws 1)
                     nil)
                   (onClose [_ _ws code reason]
                     (deliver result {:frames @frames :close {:code code :reason reason}})
                     nil)
                   (onError [_ _ws err]
                     (deliver result {:frames @frames :error err})
                     nil))]
    (try
      (.join ^CompletableFuture
             (-> (HttpClient/newHttpClient)
                 (.newWebSocketBuilder)
                 (.connectTimeout (Duration/ofSeconds 5))
                 (.buildAsync (URI/create url) listener)))
      (catch Exception e
        (deliver result {:error e})))
    (deref result 5000 {:error :timeout :frames @frames})))

(deftest subscribe-repos-websocket-test
  (let [seqr *seqr*
        did "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa"
        commit-bytes (cbor/encode {:fake "commit"})
        commit-cid (data/cid-link commit-bytes)
        server (xrpc-server/init {:lexicon @firehose-lexicon})
        http-server (httpkit/run-server
                     (fn [req]
                       ((ring/handler server) (assoc req :app-ctx {:sequencer seqr})))
                     {:port 0 :legacy-return-value? false})
        port (httpkit/server-port http-server)
        ws-url (fn [query] (str "ws://127.0.0.1:" port "/xrpc/" firehose/nsid
                                (when query (str "?" query))))]
    (try
      (seed-identity! seqr 2)
      @(sequencer/sequence-commit!
        seqr did
        {:cid commit-cid
         :rev "3aaaaaaaaaaa2a"
         :since nil
         :new-blocks {commit-cid commit-bytes}
         :relevant-blocks {commit-cid commit-bytes}
         :removed-cids #{}
         :ops [{:action "create" :path "app.bsky.feed.post/3aaaaaaaaaaa2a" :cid commit-cid}]})
      @(sequencer/sequence-account! seqr did {:active false :status "takendown"})

      (testing "backfill from cursor over a live websocket"
        (let [{:keys [frames close error]} (ws-collect (ws-url "cursor=0") :abort-after 4)]
          (is (nil? error))
          (is (= :aborted close))
          (is (= [{:op 1 :t "#identity"}
                  {:op 1 :t "#identity"}
                  {:op 1 :t "#commit"}
                  {:op 1 :t "#account"}]
                 (map #(select-keys % [:op :t]) frames)))
          (is (= [1 2 3 4] (map #(get-in % [:body :seq]) frames)))
          (let [commit-body (:body (nth frames 2))]
            (is (= did (:repo commit-body)))
            (is (= commit-cid (:commit commit-body)))
            (is (= "3aaaaaaaaaaa2a" (:rev commit-body)))
            (is (contains? commit-body :since))
            (is (= 1 (count (:ops commit-body))))
            (testing "the blocks bytes are a CAR rooted at the commit"
              (let [{:keys [root block-map]} (car/read-car-with-root (:blocks commit-body))]
                (is (= commit-cid root))
                (is (= [commit-cid] (keys block-map))))))
          (let [account-body (:body (nth frames 3))]
            (is (= {:seq 4 :did did :active false :status "takendown"}
                   (dissoc account-body :time))))))

      (testing "cursor in the future: error frame + close 1008"
        (let [{:keys [frames close error]} (ws-collect (ws-url "cursor=999"))]
          (is (nil? error))
          (is (= [{:op -1 :body {:error "FutureCursor"
                                 :message "Cursor in the future."}}]
                 frames))
          (is (= 1008 (:code close)))))

      (testing "a new event reaches an already-connected live subscriber"
        (let [result (future (ws-collect (ws-url "cursor=4") :abort-after 1))]
          ;; the outbox subscribes to the sequencer as soon as the
          ;; websocket subscription is up; wait for that instead of
          ;; guessing at connection latency
          (is (wait-until #(pos? (count (:listeners @(:state seqr))))))
          @(sequencer/sequence-identity! seqr did "fresh.test")
          (let [{:keys [frames error]} (deref result 6000 {:error :timeout})]
            (is (nil? error))
            (is (= "#identity" (:t (first frames))))
            (is (= "fresh.test" (get-in (first frames) [:body :handle]))))))
      (finally
        (httpkit/server-stop! http-server)))))
