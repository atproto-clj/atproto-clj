(ns atproto.pds.actor-store
  "Per-actor durable storage: a sharded directory of SQLite databases
  plus the actor's exported signing key.

  Layout (packages/pds/src/actor-store/actor-store.ts getLocation):
    {directory}/{sha256-hex(did)[0:2]}/{did}/store.sqlite
    {directory}/{sha256-hex(did)[0:2]}/{did}/key

  Databases are opened per operation and closed afterwards (reference
  transact/read semantics). The store handed to read-actor /
  transact-actor! callbacks satisfies WS-04's blockstore protocols
  (atproto.repo.blockstore) and is accepted by every
  atproto.pds.actor-store.record fn; its :blobstore key holds the
  per-DID BlobStore when the store was initialized with a
  :blobstore-factory.

  Public fns are async per the SDK convention and yield
  {:error \"Name\" :message ...} maps on failure."
  (:require [clojure.java.io :as io]
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.json :as json]
            [atproto.runtime.crypto :as runtime.crypto]
            [atproto.crypto :as crypto]
            [atproto.pds.sql :as sql]
            [atproto.pds.actor-store.sqlite :as sqlite])
  (:import [java.io File]))

(set! *warn-on-reflection* true)

(defn init
  "Initialize an actor store.

  config:
    :directory          (required) root directory for actor data
    :blobstore-factory  optional (fn [did] -> atproto.pds.blobstore/BlobStore)"
  [{:keys [directory blobstore-factory] :as config}]
  {:directory (io/file directory)
   :blobstore-factory blobstore-factory})

(defn actor-dir
  "The sharded directory for a DID."
  ^File [store did]
  (io/file (:directory store)
           (subs (runtime.crypto/sha256-hex ^String did) 0 2)
           did))

(defn- db-file ^File [store did] (io/file (actor-dir store did) "store.sqlite"))
(defn- key-file ^File [store did] (io/file (actor-dir store did) "key"))

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

(defn- exists?* [store did] (.exists (db-file store did)))

(defn actor-exists?
  "Whether an actor store exists for this DID. Async; yields a boolean."
  [store did & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (cb (exists?* store did))
    val))

(defn create-actor!
  "Create the actor's directory, persist its exported signing key, and
  run the schema migrations.

  Async; yields {:did did} or {:error \"ActorAlreadyExists\"|...}. The
  keypair must have been created with :exportable? true."
  [store did keypair & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (cb (attempt
         (fn []
           (if (exists?* store did)
             {:error "ActorAlreadyExists"
              :message (str "Actor store already exists for " did)
              :did did}
             (let [priv (crypto/export keypair)]
               (if (and (map? priv) (:error priv))
                 priv
                 (let [dir (actor-dir store did)]
                   (.mkdirs dir)
                   (spit (key-file store did)
                         (json/write-str {:alg (crypto/alg keypair)
                                          :privateKeyHex (runtime.crypto/hex-encode priv)}))
                   (with-open [conn (sql/connect (.getPath (db-file store did)))]
                     (sql/migrate! conn sqlite/migrations))
                   {:did did})))))))
    val))

(defn signing-key
  "The actor's signing keypair, imported from the persisted key file.
  Async; yields a Keypair or {:error \"ActorNotFound\"|...}."
  [store did & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (cb (attempt
         (fn []
           (if-not (.exists (key-file store did))
             {:error "ActorNotFound"
              :message (str "No actor store for " did)
              :did did}
             (let [{:keys [alg privateKeyHex]} (json/read-str (slurp (key-file store did)))]
               @(crypto/import-private-key alg privateKeyHex :exportable? true))))))
    val))

(defn destroy-actor!
  "Delete the actor's directory (database + key). Blobs live in the
  blobstore and are the caller's responsibility. Async; yields {:did did}."
  [store did & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (cb (attempt
         (fn []
           (let [dir (actor-dir store did)]
             (when (.exists dir)
               (run! io/delete-file (reverse (file-seq dir))))
             {:did did}))))
    val))

(defn- open-store
  "Open the actor DB and wrap it as a SqlRepoStore. Throws ActorNotFound."
  [{:keys [blobstore-factory] :as store} did & {:keys [read-only?]}]
  (when-not (exists?* store did)
    (throw (ex-info (str "No actor store for " did)
                    {:error "ActorNotFound"
                     :message (str "No actor store for " did)
                     :did did})))
  (let [conn (sql/connect (.getPath (db-file store did))
                          :pragmas (when read-only? ["PRAGMA query_only = ON"]))]
    (sqlite/repo-store conn did (when blobstore-factory (blobstore-factory did)))))

(defn read-actor
  "Open the actor DB read-only and call (f reader); the reader satisfies
  the readable blockstore protocol plus the record/blob read fns.

  Async; yields (f reader) or {:error ...}."
  [store did f & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (cb (attempt
         (fn []
           (let [reader (open-store store did :read-only? true)]
             (try
               (f reader)
               (finally
                 (.close ^java.sql.Connection (:conn reader))))))))
    val))

(defn transact-actor!
  "Open the actor DB and run (f transactor) inside a single write
  transaction (BEGIN IMMEDIATE; rolled back if f throws). The
  transactor satisfies the full blockstore protocol (put-block!,
  put-blocks!, update-root!, apply-commit!) plus the record index and
  blob fns.

  Async; yields (f transactor) or {:error ...}."
  [store did f & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (cb (attempt
         (fn []
           (let [txn (open-store store did)]
             (try
               (sql/in-write-tx* (:conn txn) (fn [_] (f txn)))
               (finally
                 (.close ^java.sql.Connection (:conn txn))))))))
    val))
