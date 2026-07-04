(ns atproto.pds.blobstore-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [atproto.data :as data]
            [atproto.runtime.bytes :as bytes]
            [atproto.pds.blobstore :as blobstore]
            [atproto.pds.blobstore.disk :as disk])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def did "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa")

(defn- temp-dir
  []
  (str (Files/createTempDirectory "blobstore-test" (make-array FileAttribute 0))))

(defn- delete-recursively!
  [dir]
  (let [f (io/file dir)]
    (when (.exists f)
      (run! io/delete-file (reverse (file-seq f))))))

(def ^:dynamic *store* nil)
(def ^:dynamic *base-dir* nil)

(defn- with-store
  [f]
  (let [dir (temp-dir)]
    (try
      (binding [*base-dir* dir
                *store* (disk/disk-blobstore did {:base-dir dir})]
        (f))
      (finally
        (delete-recursively! dir)))))

(use-fixtures :each with-store)

(def blob-bytes (byte-array (range 100)))
(def blob-cid (data/blob-ref blob-bytes))

(deftest temp-to-permanent-lifecycle-test
  (let [key (blobstore/put-temp! *store* blob-bytes)]
    (is (string? key))
    (is (blobstore/has-temp? *store* key))
    (is (not (blobstore/has-stored? *store* blob-cid)))
    (blobstore/make-permanent! *store* key blob-cid)
    (is (not (blobstore/has-temp? *store* key)))
    (is (blobstore/has-stored? *store* blob-cid))
    (is (bytes/eq? blob-bytes (blobstore/get-blob-bytes *store* blob-cid)))
    (with-open [in (blobstore/get-blob-stream *store* blob-cid)]
      (is (bytes/eq? blob-bytes (.readAllBytes in))))))

(deftest make-permanent-idempotency-test
  (testing "a second make-permanent! for an already-stored cid discards the temp copy"
    (let [k1 (blobstore/put-temp! *store* blob-bytes)
          k2 (blobstore/put-temp! *store* blob-bytes)]
      (is (not= k1 k2))
      (blobstore/make-permanent! *store* k1 blob-cid)
      (blobstore/make-permanent! *store* k2 blob-cid)
      (is (not (blobstore/has-temp? *store* k2)))
      (is (blobstore/has-stored? *store* blob-cid))))
  (testing "make-permanent! with no temp and no stored blob throws BlobNotFound"
    (let [other-cid (data/blob-ref (byte-array [1 2 3]))]
      (is (= "BlobNotFound"
             (:error (try (blobstore/make-permanent! *store* "nope" other-cid)
                          (catch Exception e (ex-data e)))))))))

(deftest put-permanent-test
  (blobstore/put-permanent! *store* blob-cid blob-bytes)
  (is (blobstore/has-stored? *store* blob-cid))
  (is (bytes/eq? blob-bytes (blobstore/get-blob-bytes *store* blob-cid))))

(deftest quarantine-lifecycle-test
  (blobstore/put-permanent! *store* blob-cid blob-bytes)
  (blobstore/quarantine! *store* blob-cid)
  (is (not (blobstore/has-stored? *store* blob-cid)))
  (is (nil? (blobstore/get-blob-bytes *store* blob-cid)))
  (blobstore/unquarantine! *store* blob-cid)
  (is (blobstore/has-stored? *store* blob-cid))
  (is (bytes/eq? blob-bytes (blobstore/get-blob-bytes *store* blob-cid)))
  (testing "quarantining a missing blob throws"
    (let [other-cid (data/blob-ref (byte-array [9 9 9]))]
      (is (= "BlobNotFound"
             (:error (try (blobstore/quarantine! *store* other-cid)
                          (catch Exception e (ex-data e))))))
      (is (= "BlobNotFound"
             (:error (try (blobstore/unquarantine! *store* other-cid)
                          (catch Exception e (ex-data e)))))))))

(deftest delete-test
  (let [payloads (mapv #(byte-array [%]) (range 3))
        cids (mapv data/blob-ref payloads)]
    (doseq [[cid b] (map vector cids payloads)]
      (blobstore/put-permanent! *store* cid b))
    (blobstore/delete-blob! *store* (first cids))
    (is (not (blobstore/has-stored? *store* (first cids))))
    (is (blobstore/has-stored? *store* (second cids)))
    (blobstore/delete-blobs! *store* (rest cids))
    (is (not-any? #(blobstore/has-stored? *store* %) cids))
    (testing "deleting a missing blob is a no-op"
      (is (nil? (blobstore/delete-blob! *store* (first cids)))))))

(deftest per-did-scoping-test
  (testing "two DIDs sharing a directory layout do not see each other's blobs"
    (let [factory (disk/factory {:base-dir *base-dir*})
          other (factory "did:plc:bbbbbbbbbbbbbbbbbbbbbbbb")]
      (blobstore/put-permanent! *store* blob-cid blob-bytes)
      (is (not (blobstore/has-stored? other blob-cid)))
      (is (nil? (blobstore/get-blob-bytes other blob-cid))))))

(deftest missing-blob-reads-test
  (is (nil? (blobstore/get-blob-bytes *store* blob-cid)))
  (is (nil? (blobstore/get-blob-stream *store* blob-cid)))
  (is (false? (blobstore/has-temp? *store* "missing")))
  (is (false? (blobstore/has-stored? *store* blob-cid))))
