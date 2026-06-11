(ns repo-verify
  "Fetch a full repository export from a PDS and verify it client-side.

  Downloads the CAR from com.atproto.sync.getRepo, resolves the
  account's signing key from its DID document, verifies the commit
  signature and the full MST walk, then spot-checks one record against
  com.atproto.repo.getRecord.

  Run with: AT_IDENTIFIER=<did or handle> clj -M:dev examples/repo_verify.clj"
  (:require [clojure.pprint :refer [pprint]]
            [atproto.client :as at]
            [atproto.identity :as identity]
            [atproto.crypto :as crypto]
            [atproto.data :as data]
            [atproto.repo :as repo]
            [atproto.repo.blockstore :as blockstore]
            [atproto.repo.sync :as repo.sync]))

(def at-id (or (System/getenv "AT_IDENTIFIER") "atproto.com"))

;; Resolve the identity to a DID, its PDS, and its DID document.

(def identity- @(identity/resolve-identity at-id))
(def did (:did identity-))
(def did-doc (:did-doc identity-))

(def pds
  (->> (:service did-doc)
       (some #(when (= "AtprotoPersonalDataServer" (:type %)) %))
       :serviceEndpoint))

(println "DID:" did)
(println "PDS:" pds)

;; The repo signing key, as a did:key string, from the DID document.

(def signing-key
  (let [{:keys [publicKeyMultibase]}
        (some #(when (= "atproto" (last (clojure.string/split (:id %) #"#"))) %)
              (:verificationMethod did-doc))
        parsed (crypto/parse-multikey publicKeyMultibase)]
    (crypto/pubkey->did-key (:alg parsed) (:bytes parsed))))

(println "Signing key:" signing-key)

;; Download the full repo export (a CAR file).

(def client @(at/init {:service pds}))

(def car-bytes
  (:body @(at/query client {:nsid "com.atproto.sync.getRepo"
                            :params {:did did}})))

(println "CAR size:" (count car-bytes) "bytes")

;; Verify: commit signature, MST walk, all leaves present.

(def verified
  @(repo.sync/verify-repo-car car-bytes :did did :signing-key signing-key))

(when (:error verified)
  (println "Verification failed!")
  (pprint verified)
  (System/exit 1))

(println "Verified" (count (:creates verified)) "records at rev"
         (:rev (:commit verified)))

;; Rebuild the repo locally from the verified commit.

(def storage (blockstore/memory-blockstore))
(blockstore/apply-commit! storage (:commit verified))
(def local-repo (repo/load storage))

;; Spot-check one record against the PDS's getRecord endpoint.

(let [{:keys [collection rkey cid]} (rand-nth (:creates verified))
      local (repo/get-record local-repo collection rkey)
      remote @(at/query client {:nsid "com.atproto.repo.getRecord"
                                :params {:repo did
                                         :collection collection
                                         :rkey rkey}})]
  (println "Spot-checking" (str collection "/" rkey))
  ;; the record CID is the hash of the record bytes, so CID equality
  ;; between the local (verified) copy and the PDS is the real check
  (assert (= cid (:cid local)))
  (assert (= (data/format-cid cid) (:cid remote)))
  (println "Record matches:" (:uri remote)))
