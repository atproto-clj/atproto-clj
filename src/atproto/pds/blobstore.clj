(ns atproto.pds.blobstore
  "BlobStore protocol for PDS blob storage.

  Mirrors the reference BlobStore interface
  (packages/repo/src/storage/types.ts:33-45): blobs are uploaded to
  temporary storage first, then made permanent once their CID is known
  and the referencing record commits. Permanent blobs can be moved to a
  quarantine area (and back) by moderation actions.

  Implementations are synchronous and scoped to a single DID; they may
  throw (ex-info with SDK-shaped {:error ...} data) on hard failures.
  The disk implementation lives in atproto.pds.blobstore.disk."
  (:require [atproto.data :as data]))

(set! *warn-on-reflection* true)

(defprotocol BlobStore
  (put-temp! [store bytes]
    "Store bytes under a fresh random temp key. Returns the key (string).")
  (make-permanent! [store key cid]
    "Promote the temp blob at key to permanent storage under cid.
    Idempotent: if cid is already stored, the temp copy is discarded.
    Throws {:error \"BlobNotFound\"} if neither exists.")
  (put-permanent! [store cid bytes]
    "Store bytes directly under cid (import/backfill path).")
  (quarantine! [store cid]
    "Move a stored blob to the quarantine area.
    Throws {:error \"BlobNotFound\"} if the blob is not stored.")
  (unquarantine! [store cid]
    "Move a quarantined blob back to permanent storage.
    Throws {:error \"BlobNotFound\"} if the blob is not quarantined.")
  (get-blob-bytes [store cid]
    "The stored blob's bytes, or nil if missing (or quarantined).")
  (get-blob-stream [store cid]
    "An InputStream over the stored blob, or nil if missing.")
  (has-temp? [store key]
    "Whether a temp blob exists for key.")
  (has-stored? [store cid]
    "Whether a permanent (non-quarantined) blob exists for cid.")
  (delete-blob! [store cid]
    "Delete the permanent blob for cid, if present.")
  (delete-blobs! [store cids]
    "Delete the permanent blobs for every cid in cids."))

(defn cid-str
  "The canonical string form of a blob CID (accepts a CID or a string)."
  [cid]
  (if (string? cid)
    cid
    (data/format-cid cid)))
