(ns atproto.pds.actor-store.storage
  "Storage interface for per-actor durable state.

  atproto.pds.actor-store owns the library logic (async API, signing-key
  import/export, error mapping); an ActorStorage owns persistence:
  actor lifecycle, opaque key-material storage, and the per-actor
  reader/transactor handles. Implementations:
  atproto.pds.actor-store.memory and atproto.pds.actor-store.sqlite.

  The value passed to `with-reader` / `with-transaction` callbacks must
  satisfy the WS-04 blockstore protocols (atproto.repo.blockstore —
  reads on a reader, reads+writes on a transactor) and the record-index
  protocol (atproto.pds.actor-store.record/RecordIndex): all indexing —
  records, backlinks, record<->blob associations, blob metadata — is the
  storage implementation's responsibility. When the storage was built
  with a blobstore factory, the handle also carries the actor's
  BlobStore under :blobstore.

  Implementations are synchronous and throw ex-info with SDK-shaped
  {:error ...} data (\"ActorAlreadyExists\", \"ActorNotFound\", ...) on
  failure."
  )

(defprotocol ActorStorage
  (actor-exists? [storage did]
    "Whether durable state exists for this DID.")
  (create-actor! [storage did key-info]
    "Create the actor's storage and persist key-info (an opaque map of
    key material owned by the library layer). Throws
    {:error \"ActorAlreadyExists\"} when the actor exists.")
  (read-key-info [storage did]
    "The persisted key-info map, or nil when the actor does not exist.")
  (destroy-actor! [storage did]
    "Delete every trace of the actor's storage (blobs are the
    blobstore's concern). Idempotent.")
  (with-reader [storage did f]
    "Open a read-only handle and return (f reader); the handle is
    released afterwards. Writes through a reader must fail. Throws
    {:error \"ActorNotFound\"} for unknown actors.")
  (with-transaction [storage did f]
    "Run (f transactor) atomically: every write is committed iff f
    returns normally, and discarded when it throws. Throws
    {:error \"ActorNotFound\"} for unknown actors."))
