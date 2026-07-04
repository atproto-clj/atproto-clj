(ns atproto.pds.blobstore.disk
  "Filesystem BlobStore implementation.

  Layout (reference packages/pds/src/disk-blobstore.ts):
    {base-dir}/{did}/{cid}         permanent blobs
    {tmp-dir}/{did}/{key}          temporary uploads (random base32 key)
    {quarantine-dir}/{did}/{cid}   quarantined blobs

  One store instance is scoped to a single DID; a `factory` builds the
  per-DID instances the actor store hands out."
  (:require [clojure.java.io :as io]
            [multiformats.base :as mb]
            [atproto.runtime.crypto :as runtime.crypto]
            [atproto.pds.blobstore :as blobstore])
  (:import [java.io File InputStream]
           [java.nio.file Files Path StandardCopyOption]))

(set! *warn-on-reflection* true)

(defn- random-key
  "Random lowercase base32 temp key (reference randomStr)."
  []
  ;; multiformats prefixes the base32 alphabet char; strip it
  (subs (mb/format :base32 (runtime.crypto/random-bytes 16)) 1))

(defn- err!
  [error message]
  (throw (ex-info message {:error error :message message})))

(defn- ^File ensured-parent
  [^File f]
  (some-> (.getParentFile f) (.mkdirs))
  f)

(defn- move!
  "Atomically move src to dst (falling back to copy+delete)."
  [^File src ^File dst]
  (ensured-parent dst)
  (try
    (Files/move (.toPath src) (.toPath dst)
                (into-array java.nio.file.CopyOption
                            [StandardCopyOption/ATOMIC_MOVE
                             StandardCopyOption/REPLACE_EXISTING]))
    (catch java.nio.file.AtomicMoveNotSupportedException _
      (Files/move (.toPath src) (.toPath dst)
                  (into-array java.nio.file.CopyOption
                              [StandardCopyOption/REPLACE_EXISTING])))))

(defrecord DiskBlobStore [did ^File base-dir ^File tmp-dir ^File quarantine-dir]
  blobstore/BlobStore
  (put-temp! [_ bytes]
    (let [key (random-key)
          f (ensured-parent (io/file tmp-dir did key))]
      (io/copy ^bytes bytes f)
      key))
  (make-permanent! [this key cid]
    (let [tmp (io/file tmp-dir did key)
          dst (io/file base-dir did (blobstore/cid-str cid))]
      (cond
        (blobstore/has-stored? this cid)
        (io/delete-file tmp true)

        (.exists tmp)
        (move! tmp dst)

        :else
        (err! "BlobNotFound" (str "No temp blob for key: " key))))
    nil)
  (put-permanent! [_ cid bytes]
    (let [f (ensured-parent (io/file base-dir did (blobstore/cid-str cid)))]
      (io/copy ^bytes bytes f))
    nil)
  (quarantine! [_ cid]
    (let [cid (blobstore/cid-str cid)
          src (io/file base-dir did cid)]
      (if (.exists src)
        (move! src (io/file quarantine-dir did cid))
        (err! "BlobNotFound" (str "No stored blob for cid: " cid))))
    nil)
  (unquarantine! [_ cid]
    (let [cid (blobstore/cid-str cid)
          src (io/file quarantine-dir did cid)]
      (if (.exists src)
        (move! src (io/file base-dir did cid))
        (err! "BlobNotFound" (str "No quarantined blob for cid: " cid))))
    nil)
  (get-blob-bytes [_ cid]
    (let [f (io/file base-dir did (blobstore/cid-str cid))]
      (when (.exists f)
        (Files/readAllBytes (.toPath f)))))
  (get-blob-stream [_ cid]
    (let [f (io/file base-dir did (blobstore/cid-str cid))]
      (when (.exists f)
        (io/input-stream f))))
  (has-temp? [_ key]
    (.exists (io/file tmp-dir did key)))
  (has-stored? [_ cid]
    (.exists (io/file base-dir did (blobstore/cid-str cid))))
  (delete-blob! [_ cid]
    (io/delete-file (io/file base-dir did (blobstore/cid-str cid)) true)
    nil)
  (delete-blobs! [this cids]
    (run! #(blobstore/delete-blob! this %) cids)
    nil))

(defn disk-blobstore
  "A DiskBlobStore for one DID.

  opts:
    :base-dir        (required) permanent blob root
    :tmp-dir         temp upload root (default {base-dir}/tempblobs)
    :quarantine-dir  quarantine root (default {base-dir}/quarantine)"
  [did {:keys [base-dir tmp-dir quarantine-dir]}]
  (let [base (io/file base-dir)]
    (->DiskBlobStore did
                     base
                     (if tmp-dir (io/file tmp-dir) (io/file base "tempblobs"))
                     (if quarantine-dir (io/file quarantine-dir) (io/file base "quarantine")))))

(defn factory
  "A (fn [did] -> BlobStore) over a shared directory layout (the
  :blobstore-factory the actor store expects)."
  [opts]
  (fn [did] (disk-blobstore did opts)))
