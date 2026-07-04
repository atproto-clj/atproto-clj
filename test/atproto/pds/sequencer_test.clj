(ns atproto.pds.sequencer-test
  "Sequencer library tests, run against every SequencerStorage
  implementation (memory and SQLite) to keep them conformant."
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [atproto.data :as data]
            [atproto.data.cbor :as cbor]
            [atproto.runtime.bytes :as bytes]
            [atproto.repo.car :as car]
            [atproto.pds.sequencer :as sequencer]
            [atproto.pds.sequencer.memory :as memory]
            [atproto.pds.sequencer.sqlite :as sqlite])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent CountDownLatch TimeUnit]))

(def did "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa")

(defn- with-each-sequencer
  "Run (f seqr) once per storage implementation."
  [f]
  (doseq [[label make-storage] [["memory" (fn [] (memory/open))]
                                ["sqlite" (fn []
                                            (let [dir (str (Files/createTempDirectory
                                                            "sequencer-test"
                                                            (make-array FileAttribute 0)))]
                                              (with-meta
                                                (sqlite/open {:db-path (str dir "/repo_seq.sqlite")})
                                                {:dir dir})))]]]
    (testing (str "[" label "]")
      (let [storage (make-storage)
            seqr @(sequencer/init {:storage storage})]
        (is (nil? (:error seqr)))
        (try
          (f seqr)
          (finally
            @(sequencer/close! seqr)
            (when-let [dir (:dir (meta storage))]
              (run! io/delete-file (reverse (file-seq (io/file dir)))))))))))

(defn- block
  [value]
  (let [b (cbor/encode value)]
    [(data/cid-link b) b]))

(defn- commit-data
  [n]
  (let [[commit-cid commit-bytes] (block {:n n :commit true})
        [rec-cid rec-bytes] (block {:n n :record true})]
    {:cid commit-cid
     :rev (str "3aaaaaaaaaa" n "2a")
     :since nil
     :prev nil
     :new-blocks {commit-cid commit-bytes rec-cid rec-bytes}
     :relevant-blocks {commit-cid commit-bytes rec-cid rec-bytes}
     :removed-cids #{}
     :ops [{:action "create"
            :path (str "app.bsky.feed.post/3aaaaaaaaaa" n "2a")
            :cid rec-cid}]}))

(deftest sequence-and-decode-test
  (with-each-sequencer
    (fn [seqr]
      (let [cd (commit-data "a")
            r1 @(sequencer/sequence-commit! seqr did cd)
            r2 @(sequencer/sequence-identity! seqr did "alice.test")
            r3 @(sequencer/sequence-account! seqr did {:active false :status "takendown"})
            r4 @(sequencer/sequence-sync! seqr did {:cid (:cid cd)
                                                    :rev (:rev cd)
                                                    :blocks (select-keys (:new-blocks cd) [(:cid cd)])})]
        (is (= [1 2 3 4] (map :seq [r1 r2 r3 r4])))
        (is (= 4 (sequencer/current-seq seqr)))
        (let [[commit identity account sync] (sequencer/request-range seqr {})]
          (testing "#commit payload"
            (is (= :commit (:type commit)))
            (is (= did (:did commit)))
            (is (string? (:time commit)))
            (let [evt (:event commit)]
              (is (= did (:repo evt)))
              (is (= (:cid cd) (:commit evt)))
              (is (= (:rev cd) (:rev evt)))
              (is (contains? evt :since))
              (is (nil? (:since evt)))
              (is (false? (:rebase evt)))
              (is (false? (:tooBig evt)))
              (is (= [] (:blobs evt)))
              (is (= (:ops cd) (:ops evt)))
              (testing "the CAR slice is rooted at the commit and holds the relevant blocks"
                (let [{:keys [root block-map]} (car/read-car-with-root (:blocks evt))]
                  (is (= (:cid cd) root))
                  (is (= (set (keys (:relevant-blocks cd))) (set (keys block-map))))))))
          (testing "#identity payload"
            (is (= :identity (:type identity)))
            (is (= {:did did :handle "alice.test"} (:event identity))))
          (testing "#account payload"
            (is (= :account (:type account)))
            (is (= {:did did :active false :status "takendown"} (:event account))))
          (testing "#sync payload"
            (is (= :sync (:type sync)))
            (let [evt (:event sync)]
              (is (= did (:did evt)))
              (is (= (:rev cd) (:rev evt)))
              (let [{:keys [root blocks]} (car/read-car-with-root (:blocks evt))]
                (is (= (:cid cd) root))
                (is (= 1 (count blocks)))))))))))

(deftest cursor-queries-test
  (with-each-sequencer
    (fn [seqr]
      (dotimes [n 5]
        @(sequencer/sequence-identity! seqr did (str "user" n ".test")))
      (testing "current-seq / next-after"
        (is (= 5 (sequencer/current-seq seqr)))
        (is (= 3 (:seq (sequencer/next-after seqr 2))))
        (is (nil? (sequencer/next-after seqr 5))))
      (testing "request-range bounds and limit"
        (is (= [2 3 4 5] (map :seq (sequencer/request-range seqr {:earliest-seq 1}))))
        (is (= [2 3] (map :seq (sequencer/request-range seqr {:earliest-seq 1 :latest-seq 3}))))
        (is (= [1 2] (map :seq (sequencer/request-range seqr {:limit 2})))))
      (testing "earliest-after-time"
        (let [t3 (:time (sequencer/next-after seqr 2))]
          (is (<= (:seq (sequencer/earliest-after-time seqr t3)) 3))
          (is (nil? (sequencer/earliest-after-time seqr "9999-01-01T00:00:00.000Z")))))
      (testing "invalidated rows are skipped by request-range"
        (sequencer/invalidate! seqr 3)
        (is (= [1 2 4 5] (map :seq (sequencer/request-range seqr {}))))
        (testing "but not by next-after (reference parity)"
          (is (= 3 (:seq (sequencer/next-after seqr 2)))))))))

(deftest empty-log-test
  (with-each-sequencer
    (fn [seqr]
      (is (nil? (sequencer/current-seq seqr)))
      (is (nil? (sequencer/next-after seqr 0)))
      (is (= [] (sequencer/request-range seqr {}))))))

(deftest subscribe-delivery-test
  (with-each-sequencer
    (fn [seqr]
      (let [seen (atom [])
            latch (CountDownLatch. 10)
            unsub (sequencer/subscribe seqr
                                       (fn [batch]
                                         (swap! seen into batch)
                                         (dotimes [_ (count batch)]
                                           (.countDown latch))))]
        (dotimes [n 10]
          @(sequencer/sequence-identity! seqr did (str "user" n ".test")))
        (is (.await latch 5 TimeUnit/SECONDS))
        (is (= (range 1 11) (map :seq @seen)))
        (testing "unsubscribing stops delivery"
          (unsub)
          @(sequencer/sequence-identity! seqr did "late.test")
          (Thread/sleep 200)
          (is (= 10 (count @seen))))))))

(deftest concurrent-writers-test
  ;; monotonic, gap-free, duplicate-free seqs under concurrent sequencing
  (with-each-sequencer
    (fn [seqr]
      (let [n-threads 8
            per-thread 25
            results (atom [])
            threads (mapv (fn [t]
                            (Thread.
                             (fn []
                               (dotimes [n per-thread]
                                 (let [r @(sequencer/sequence-identity!
                                           seqr
                                           (str "did:plc:writer" t)
                                           (str "w" t "n" n ".test"))]
                                   (swap! results conj r))))))
                          (range n-threads))]
        (run! #(.start ^Thread %) threads)
        (run! #(.join ^Thread % 10000) threads)
        (let [seqs (map :seq @results)
              total (* n-threads per-thread)]
          (is (every? some? seqs))
          (is (= total (count (set seqs))))
          (is (= (set (range 1 (inc total))) (set seqs)))
          (is (= total (sequencer/current-seq seqr)))
          (is (= (range 1 (inc total))
                 (map :seq (sequencer/request-range seqr {})))))))))

(deftest car-bytes-pass-through-test
  ;; pre-encoded CAR bytes are stored as-is
  (with-each-sequencer
    (fn [seqr]
      (let [[cid bytes] (block {:commit true})
            car-bytes (car/write-car cid [{:cid cid :bytes bytes}])]
        @(sequencer/sequence-sync! seqr did {:cid cid :rev "3aaaaaaaaaaa2a" :blocks car-bytes})
        (let [[evt] (sequencer/request-range seqr {})]
          (is (bytes/eq? car-bytes (:blocks (:event evt)))))))))

(deftest init-requires-storage-test
  (is (= "InvalidStorage" (:error @(sequencer/init {}))))
  (is (= "InvalidStorage" (:error @(sequencer/init {:db-path "/tmp/nope.sqlite"})))))
