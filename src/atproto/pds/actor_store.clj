(ns atproto.pds.actor-store
  "Per-actor durable storage: signing key + repo blocks + record index.

  This namespace owns the library logic — the async public API, signing
  keypair export/import (key material is persisted opaquely by the
  storage), and error mapping. Persistence and indexing live behind the
  atproto.pds.actor-store.storage/ActorStorage protocol; pass an
  implementation to `init` (atproto.pds.actor-store.memory or
  atproto.pds.actor-store.sqlite ship with the SDK).

  The value handed to read-actor / transact-actor! callbacks satisfies
  WS-04's blockstore protocols (atproto.repo.blockstore) and the
  record-index protocol (atproto.pds.actor-store.record/RecordIndex),
  and carries the actor's BlobStore under :blobstore when the storage
  was built with a blobstore factory.

  Public fns are async per the SDK convention and yield
  {:error \"Name\" :message ...} maps on failure."
  (:require [atproto.runtime.interceptor :as i]
            [atproto.runtime.crypto :as runtime.crypto]
            [atproto.crypto :as crypto]
            [atproto.pds.actor-store.storage :as storage]))

(set! *warn-on-reflection* true)

(defn init
  "Initialize an actor store over an ActorStorage implementation.

  config: {:storage <atproto.pds.actor-store.storage/ActorStorage>}
  (see atproto.pds.actor-store.memory/storage and
  atproto.pds.actor-store.sqlite/storage)"
  [{:keys [storage] :as config}]
  (when-not (satisfies? storage/ActorStorage storage)
    (throw (ex-info "config requires a :storage implementing ActorStorage."
                    {:error "InvalidStorage"
                     :message "config requires a :storage implementing ActorStorage."})))
  {:storage storage})

(defn- async-opts [opts] (select-keys opts [:channel :callback :promise]))

(defn- attempt
  "Run f, converting throws into SDK error maps."
  [f]
  (try
    (f)
    (catch Exception e
      (let [d (ex-data e)]
        (if (and (map? d) (:error d))
          (select-keys d [:error :message :did])
          {:error "ActorStoreError" :message (ex-message e)})))))

(defn actor-exists?
  "Whether an actor store exists for this DID. Async; yields a boolean."
  [{:keys [storage]} did & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (cb (attempt #(boolean (storage/actor-exists? storage did))))
    val))

(defn create-actor!
  "Create the actor's storage and persist its exported signing key.

  Async; yields {:did did} or {:error \"ActorAlreadyExists\"|...}. The
  keypair must have been created with :exportable? true."
  [{:keys [storage]} did keypair & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (cb (attempt
         (fn []
           (let [priv (crypto/export keypair)]
             (if (and (map? priv) (:error priv))
               priv
               (do (storage/create-actor! storage did
                                          {:alg (crypto/alg keypair)
                                           :private-key-hex (runtime.crypto/hex-encode priv)})
                   {:did did}))))))
    val))

(defn signing-key
  "The actor's signing keypair, imported from the persisted key
  material. Async; yields a Keypair or {:error \"ActorNotFound\"|...}."
  [{:keys [storage]} did & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (cb (attempt
         (fn []
           (if-let [{:keys [alg private-key-hex]} (storage/read-key-info storage did)]
             @(crypto/import-private-key alg private-key-hex :exportable? true)
             {:error "ActorNotFound"
              :message (str "No actor store for " did)
              :did did}))))
    val))

(defn destroy-actor!
  "Delete the actor's storage (database + key material). Blobs live in
  the blobstore and are the caller's responsibility. Async; yields
  {:did did}."
  [{:keys [storage]} did & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (cb (attempt
         (fn []
           (storage/destroy-actor! storage did)
           {:did did})))
    val))

(defn read-actor
  "Open the actor's storage read-only and call (f reader); the reader
  satisfies the readable blockstore protocol plus the record-index
  reads. Async; yields (f reader) or {:error ...}."
  [{:keys [storage]} did f & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (cb (attempt #(storage/with-reader storage did f)))
    val))

(defn transact-actor!
  "Run (f transactor) inside a single storage transaction (committed iff
  f returns normally). The transactor satisfies the full blockstore
  protocol (put-block!, put-blocks!, update-root!, apply-commit!) plus
  the record-index protocol. Async; yields (f transactor) or
  {:error ...}."
  [{:keys [storage]} did f & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (cb (attempt #(storage/with-transaction storage did f)))
    val))
