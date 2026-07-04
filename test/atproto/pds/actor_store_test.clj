(ns atproto.pds.actor-store-test
  "Actor-store tests, run against every ActorStorage implementation
  (memory and SQLite) so the storage contract stays conformant; the
  blockstore conformance suite also runs against WS-04's plain memory
  blockstore."
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [atproto.data :as data]
            [atproto.data.cbor :as cbor]
            [atproto.runtime.bytes :as bytes]
            [atproto.runtime.json :as json]
            [atproto.runtime.crypto :as runtime.crypto]
            [atproto.crypto :as crypto]
            [atproto.repo :as repo]
            [atproto.repo.blockstore :as blockstore]
            [atproto.pds.sql :as sql]
            [atproto.pds.blobstore.disk :as disk]
            [atproto.pds.actor-store :as actor-store]
            [atproto.pds.actor-store.storage :as storage]
            [atproto.pds.actor-store.record :as record]
            [atproto.pds.actor-store.memory :as memory]
            [atproto.pds.actor-store.sqlite :as sqlite])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def did "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa")

(defn- temp-dir
  []
  (str (Files/createTempDirectory "actor-store-test" (make-array FileAttribute 0))))

(defn- delete-recursively!
  [dir]
  (let [f (io/file dir)]
    (when (.exists f)
      (run! io/delete-file (reverse (file-seq f))))))

(defn- with-each-storage
  "Run (f label actor-storage) once per ActorStorage implementation."
  [f]
  (doseq [[label make cleanup-fn]
          (let [dir (temp-dir)]
            [["memory" (fn [] (memory/storage)) (fn [])]
             ["sqlite" (fn [] (sqlite/storage {:directory dir}))
              (fn [] (delete-recursively! dir))]])]
    (testing (str "[" label "]")
      (let [stor (make)]
        (try
          (f label stor)
          (finally (cleanup-fn)))))))

(def ^:private fresh-counter (atom 0))

(defn- fresh-did [] (str "did:plc:fresh" (swap! fresh-counter inc)))

(defn- in-fresh-txn
  "Create a fresh actor on stor and run (f txn) in one transaction."
  [stor f]
  (let [did (fresh-did)]
    (storage/create-actor! stor did {:alg "ES256K" :private-key-hex "00"})
    (storage/with-transaction stor did f)))

;; -----------------------------------------------------------------------------
;; Blockstore conformance (WS-04 memory impl + both actor storages)
;; -----------------------------------------------------------------------------

(defn- block
  "[cid bytes] for a data value."
  [value]
  (let [b (cbor/encode value)]
    [(data/cid-link b) b]))

(defn- blockstore-conformance-suite
  "in-fresh-store: (fn [f] ...) runs (f bs) against a fresh writable store."
  [in-fresh-store]
  (testing "put/get/has/get-blocks/missing"
    (in-fresh-store
     (fn [bs]
       (let [[cid-a bytes-a] (block {:a 1})
             [cid-b bytes-b] (block {:b 2})
             [cid-c _] (block {:c 3})]
         (is (nil? (blockstore/get-bytes bs cid-a)))
         (is (false? (boolean (blockstore/has-block? bs cid-a))))
         (blockstore/put-block! bs cid-a bytes-a)
         (blockstore/put-blocks! bs {cid-b bytes-b})
         (is (bytes/eq? bytes-a (blockstore/get-bytes bs cid-a)))
         (is (blockstore/has-block? bs cid-b))
         ;; re-putting an existing block is a no-op
         (blockstore/put-block! bs cid-a bytes-a)
         (let [{:keys [blocks missing]} (blockstore/get-blocks bs [cid-a cid-b cid-c])]
           (is (= [cid-c] missing))
           (is (= #{cid-a cid-b} (set (keys blocks))))
           (is (bytes/eq? bytes-a (get blocks cid-a))))))))
  (testing "update-root! and get-root"
    (in-fresh-store
     (fn [bs]
       (let [[cid-a _] (block {:a 1})]
         (is (nil? (blockstore/get-root bs)))
         (blockstore/update-root! bs cid-a "3aaaaaaaaaaa2a")
         (is (= cid-a (blockstore/get-root bs)))))))
  (testing "apply-commit! sets the root, adds new blocks, deletes removed"
    (in-fresh-store
     (fn [bs]
       (let [[cid-a bytes-a] (block {:a 1})
             [cid-b bytes-b] (block {:b 2})
             [cid-c bytes-c] (block {:c 3})]
         (blockstore/apply-commit! bs {:cid cid-a
                                       :rev "3aaaaaaaaaaa2a"
                                       :new-blocks {cid-a bytes-a cid-b bytes-b}
                                       :removed-cids #{}})
         (is (= cid-a (blockstore/get-root bs)))
         (blockstore/apply-commit! bs {:cid cid-c
                                       :rev "3aaaaaaaaaab2a"
                                       :new-blocks {cid-c bytes-c}
                                       :removed-cids #{cid-b}})
         (is (= cid-c (blockstore/get-root bs)))
         (is (blockstore/has-block? bs cid-a))
         (is (not (blockstore/has-block? bs cid-b)))
         (is (bytes/eq? bytes-c (blockstore/get-bytes bs cid-c))))))))

(deftest memory-blockstore-conformance-test
  (blockstore-conformance-suite (fn [f] (f (blockstore/memory-blockstore)))))

(deftest actor-storage-blockstore-conformance-test
  (with-each-storage
    (fn [_label stor]
      (blockstore-conformance-suite (partial in-fresh-txn stor)))))

(deftest sqlite-blockstore-repo-rev-test
  (testing "blocks written via apply-commit! are indexed under the commit rev"
    (let [dir (temp-dir)
          stor (sqlite/storage {:directory dir})]
      (try
        (in-fresh-txn
         stor
         (fn [bs]
           (let [[cid-a bytes-a] (block {:a 1})]
             (blockstore/apply-commit! bs {:cid cid-a
                                           :rev "3aaaaaaaaaaa2a"
                                           :new-blocks {cid-a bytes-a}
                                           :removed-cids #{}})
             (is (= "3aaaaaaaaaaa2a" (sqlite/root-rev bs)))
             (is (= "3aaaaaaaaaaa2a"
                    (:repoRev (sql/execute-one!
                               (:conn bs)
                               ["SELECT repoRev FROM repo_block WHERE cid = ?"
                                (data/format-cid cid-a)])))))))
        (finally (delete-recursively! dir))))))

;; -----------------------------------------------------------------------------
;; Vendored CAR fixtures round-trip block-for-block through every storage
;; -----------------------------------------------------------------------------

(defn- json-fixtures
  []
  (json/read-str
   (slurp (io/resource "interop-test-files/repo/car-file-fixtures.json"))))

(deftest car-fixtures-round-trip-test
  (with-each-storage
    (fn [_label stor]
      (doseq [{:keys [root blocks]}
              (json-fixtures)]
        (in-fresh-txn
         stor
         (fn [bs]
           (let [fixture-blocks (mapv (fn [{:keys [cid bytes]}]
                                        {:cid (data/parse-cid cid)
                                         :bytes (runtime.crypto/base64-decode bytes)})
                                      blocks)]
             (blockstore/put-blocks! bs (into {} (map (juxt :cid :bytes)) fixture-blocks))
             (blockstore/update-root! bs (data/parse-cid root) "3aaaaaaaaaaa2a")
             (is (= (data/parse-cid root) (blockstore/get-root bs)))
             (let [{:keys [blocks missing]} (blockstore/get-blocks bs (map :cid fixture-blocks))]
               (is (empty? missing))
               (doseq [{:keys [cid bytes]} fixture-blocks]
                 (is (bytes/eq? bytes (get blocks cid))))))))))))

;; -----------------------------------------------------------------------------
;; Record index (via the RecordIndex protocol, both storages)
;; -----------------------------------------------------------------------------

(defn- post-uri [did rkey] (str "at://" did "/app.bsky.feed.post/" rkey))

(deftest record-index-test
  (with-each-storage
    (fn [_label stor]
      (in-fresh-txn
       stor
       (fn [bs]
         (let [did (:did bs)
               value {:text "hello" :createdAt "2026-06-01T00:00:00Z"}
               [cid bytes] (block value)
               uri (post-uri did "3aaaaaaaaaaa2a")]
           (blockstore/put-block! bs cid bytes)
           (record/index-record! bs {:uri uri :cid cid :record value :repo-rev "3aaaaaaaaaaa2a"})
           (testing "get-record joins the block content"
             (let [rec (record/get-record bs uri)]
               (is (= uri (:uri rec)))
               (is (= cid (:cid rec)))
               (is (= value (:value rec)))
               (is (string? (:indexed-at rec)))))
           (testing "re-indexing updates the row"
             (let [value' {:text "hello again" :createdAt "2026-06-01T00:00:00Z"}
                   [cid' bytes'] (block value')]
               (blockstore/put-block! bs cid' bytes')
               (record/index-record! bs {:uri uri :cid cid' :record value' :repo-rev "3aaaaaaaaaab2a"})
               (is (= value' (:value (record/get-record bs uri))))))
           (testing "an invalid record uri is rejected"
             (is (thrown? Exception
                          (record/index-record! bs {:uri (str "at://" did)
                                                    :cid cid :record value}))))
           (testing "delete-record! removes the row"
             (record/delete-record! bs uri)
             (is (nil? (record/get-record bs uri))))))))))

(deftest record-backlinks-test
  (with-each-storage
    (fn [_label stor]
      (in-fresh-txn
       stor
       (fn [bs]
         (let [did (:did bs)
               target "did:plc:bbbbbbbbbbbbbbbbbbbbbbbb"
               follow {:subject target :createdAt "2026-06-01T00:00:00Z"}
               [cid bytes] (block follow)
               uri (str "at://" did "/app.bsky.graph.follow/3aaaaaaaaaaa2a")
               like {:subject {:uri (post-uri did "3aaaaaaaaaab2a")
                               :cid "bafyreiapldaco7m23c7qzc4w42r7kxmcswm64nkindtuh4vwztrpoe7m5m"}
                     :createdAt "2026-06-01T00:00:00Z"}
               [like-cid like-bytes] (block like)
               like-uri (str "at://" did "/app.bsky.feed.like/3aaaaaaaaaac2a")]
           (blockstore/put-blocks! bs {cid bytes like-cid like-bytes})
           (record/index-record! bs {:uri uri :cid cid :record follow :repo-rev "r"})
           (record/index-record! bs {:uri like-uri :cid like-cid :record like :repo-rev "r"})
           (is (= [uri] (record/backlink-uris bs "subject" target)))
           (is (= [like-uri] (record/backlink-uris bs "subject.uri" (post-uri did "3aaaaaaaaaab2a"))))
           (record/delete-record! bs uri)
           (is (= [] (record/backlink-uris bs "subject" target)))))))))

(deftest record-blob-index-test
  (with-each-storage
    (fn [_label stor]
      (in-fresh-txn
       stor
       (fn [bs]
         (let [did (:did bs)
               blob-bytes (byte-array [1 2 3])
               blob-cid (data/blob-ref blob-bytes)
               value {:text "with image"
                      :embed {:$type "app.bsky.embed.images"
                              :images [{:image {:$type "blob"
                                                :ref blob-cid
                                                :mimeType "image/png"
                                                :size 3}
                                        :alt ""}]}}
               [cid bytes] (block value)
               uri (post-uri did "3aaaaaaaaaaa2a")]
           (blockstore/put-block! bs cid bytes)
           (record/index-record! bs {:uri uri :cid cid :record value :repo-rev "r"})
           (record/register-blob! bs {:cid blob-cid :mime-type "image/png" :size 3 :temp-key "tk"})
           (is (= [uri] (record/record-uris-for-blob bs blob-cid)))
           (let [meta (record/blob-metadata bs blob-cid)]
             (is (= blob-cid (:cid meta)))
             (is (= "image/png" (:mime-type meta)))
             (is (= 3 (:size meta))))
           (record/delete-record! bs uri)
           (is (= [] (record/record-uris-for-blob bs blob-cid)))))))))

(deftest list-records-test
  (with-each-storage
    (fn [_label stor]
      (in-fresh-txn
       stor
       (fn [bs]
         (let [did (:did bs)
               rkeys (mapv #(str "3aaaaaaaaaa" (char (+ (int \a) %)) "2a") (range 5))]
           (doseq [rkey rkeys]
             (let [value {:text rkey}
                   [cid bytes] (block value)]
               (blockstore/put-block! bs cid bytes)
               (record/index-record! bs {:uri (post-uri did rkey) :cid cid
                                         :record value :repo-rev "r"})))
           (testing "ascending pagination"
             (let [{:keys [records cursor]} (record/list-records bs {:collection "app.bsky.feed.post"
                                                                     :limit 3})]
               (is (= (mapv #(post-uri did %) (take 3 rkeys)) (mapv :uri records)))
               (is (= (nth rkeys 2) cursor))
               (let [{:keys [records cursor]} (record/list-records bs {:collection "app.bsky.feed.post"
                                                                       :limit 3
                                                                       :cursor cursor})]
                 (is (= (mapv #(post-uri did %) (drop 3 rkeys)) (mapv :uri records)))
                 (is (nil? cursor)))))
           (testing "reverse order"
             (let [{:keys [records]} (record/list-records bs {:collection "app.bsky.feed.post"
                                                              :limit 10
                                                              :reverse? true})]
               (is (= (mapv #(post-uri did %) (reverse rkeys)) (mapv :uri records)))))
           (testing "list-collections"
             (is (= ["app.bsky.feed.post"] (record/list-collections bs))))))))))

;; -----------------------------------------------------------------------------
;; Actor store lifecycle + full repo integration (library API, both storages)
;; -----------------------------------------------------------------------------

(deftest actor-lifecycle-test
  (with-each-storage
    (fn [label stor]
      (let [store (actor-store/init {:storage stor})
            did (fresh-did)
            keypair @(crypto/generate "ES256K" :exportable? true)]
        (is (false? @(actor-store/actor-exists? store did)))
        (is (= "ActorNotFound" (:error @(actor-store/read-actor store did identity))))
        (is (= "ActorNotFound" (:error @(actor-store/signing-key store did))))
        (is (= {:did did} @(actor-store/create-actor! store did keypair)))
        (is (true? @(actor-store/actor-exists? store did)))
        (testing "creating twice fails"
          (is (= "ActorAlreadyExists" (:error @(actor-store/create-actor! store did keypair)))))
        (testing "the signing key round-trips"
          (let [restored @(actor-store/signing-key store did)]
            (is (satisfies? crypto/Keypair restored))
            (is (= (crypto/did keypair) (crypto/did restored)))))
        (testing "a non-exportable keypair is rejected"
          (let [kp @(crypto/generate "ES256K")]
            (is (= "PrivateKeyNotExportable"
                   (:error @(actor-store/create-actor! store (fresh-did) kp))))))
        (testing "destroy removes everything"
          (is (= {:did did} @(actor-store/destroy-actor! store did)))
          (is (false? @(actor-store/actor-exists? store did))))))))

(deftest sqlite-storage-layout-test
  (testing "the sqlite layout is sharded by sha256(did) prefix"
    (let [dir (temp-dir)
          stor (sqlite/storage {:directory dir})
          keypair @(crypto/generate "ES256K" :exportable? true)]
      (try
        @(actor-store/create-actor! (actor-store/init {:storage stor}) did keypair)
        (is (.exists (io/file dir
                              (subs (runtime.crypto/sha256-hex did) 0 2)
                              did
                              "store.sqlite")))
        (is (.exists (io/file dir
                              (subs (runtime.crypto/sha256-hex did) 0 2)
                              did
                              "key")))
        (finally (delete-recursively! dir))))))

(deftest init-requires-storage-test
  (is (thrown? Exception (actor-store/init {})))
  (is (thrown? Exception (actor-store/init {:directory "/tmp/nope"}))))

(deftest actor-repo-integration-test
  ;; end to end: create an actor, commit records through the storage
  ;; with the WS-04 repo, read them back
  (with-each-storage
    (fn [_label stor]
      (let [blob-dir (temp-dir)
            store (actor-store/init {:storage stor})
            did (fresh-did)
            keypair @(crypto/generate "ES256K" :exportable? true)]
        (try
          @(actor-store/create-actor! store did keypair)
          (let [commit-result
                @(actor-store/transact-actor!
                  store did
                  (fn [txn]
                    (let [handle @(repo/create txn did keypair)]
                      (if (:error handle)
                        handle
                        (let [value {:text "hello world" :createdAt "2026-06-01T00:00:00Z"}
                              handle' @(repo/apply-writes
                                        handle
                                        {:action :create
                                         :collection "app.bsky.feed.post"
                                         :rkey "3aaaaaaaaaaa2a"
                                         :value value}
                                        keypair)]
                          (if (:error handle')
                            handle'
                            (let [{:keys [cid]} (repo/get-record handle' "app.bsky.feed.post" "3aaaaaaaaaaa2a")]
                              (record/index-record! txn {:uri (post-uri did "3aaaaaaaaaaa2a")
                                                         :cid cid
                                                         :record value
                                                         :repo-rev (:rev (:commit handle'))})
                              {:rev (:rev (:commit handle'))})))))))]
            (is (nil? (:error commit-result)))
            (let [read-result
                  @(actor-store/read-actor
                    store did
                    (fn [reader]
                      {:root? (some? (blockstore/get-root reader))
                       :contents (repo/contents (repo/load reader))
                       :indexed (record/get-record reader (post-uri did "3aaaaaaaaaaa2a"))}))]
              (is (nil? (:error read-result)))
              (is (true? (:root? read-result)))
              (is (= {"app.bsky.feed.post"
                      {"3aaaaaaaaaaa2a" {:text "hello world"
                                         :createdAt "2026-06-01T00:00:00Z"}}}
                     (:contents read-result)))
              (is (= {:text "hello world" :createdAt "2026-06-01T00:00:00Z"}
                     (:value (:indexed read-result))))))
          (testing "writes inside a failed transaction roll back"
            (let [resp @(actor-store/transact-actor!
                         store did
                         (fn [txn]
                           (let [[cid bytes] (block {:orphan true})]
                             (blockstore/put-block! txn cid bytes))
                           (throw (ex-info "boom" {:error "Boom" :message "boom"}))))]
              (is (= "Boom" (:error resp))))
            (let [[cid _] (block {:orphan true})
                  found @(actor-store/read-actor store did #(blockstore/has-block? % cid))]
              (is (false? (boolean found)))))
          (testing "read-actor handles cannot write"
            (let [resp @(actor-store/read-actor
                         store did
                         (fn [reader]
                           (let [[cid bytes] (block {:sneaky true})]
                             (blockstore/put-block! reader cid bytes))))]
              (is (some? (:error resp)))))
          (finally
            (delete-recursively! blob-dir)))))))

(deftest blobstore-attachment-test
  (testing "the handle carries the per-DID blobstore when a factory is configured"
    (let [blob-dir (temp-dir)
          stor (memory/storage {:blobstore-factory (disk/factory {:base-dir blob-dir})})
          store (actor-store/init {:storage stor})
          did (fresh-did)
          keypair @(crypto/generate "ES256K" :exportable? true)]
      (try
        @(actor-store/create-actor! store did keypair)
        (is (true? @(actor-store/read-actor store did #(some? (:blobstore %)))))
        (finally (delete-recursively! blob-dir))))))
