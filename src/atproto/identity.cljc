(ns atproto.identity
  "Cross platform identity resolver for atproto."
  (:refer-clojure :exclude [resolve])
  (:require [clojure.string :as str]
            [atproto.runtime.interceptor :as i]
            [atproto.did :as did]
            [atproto.handle :as handle]))

(defn- verified-identity
  [{:keys [did doc handle]}]
  (if-let [pds (did/pds doc)]
    (let [handle (first (did/handles doc))]
      (cond-> {:did did
               :pds pds}
        handle (assoc :handle handle)))
    {:error "No PDS URL found in DID document."}))

(defn- resolve-with-did
  [did cb]
  (did/resolve did
               :callback
               (fn [{:keys [error] :as resp}]
                 (if error
                   (cb resp)
                   (cb (verified-identity resp))))))

(defn- resolve-with-handle
  [handle cb]
  (handle/resolve handle
                  :callback
                  (fn [{:keys [error did] :as resp}]
                    (if error
                      (cb resp)
                      (did/resolve did
                                   :callback
                                   (fn [{:keys [error doc] :as resp}]
                                     (cb (cond
                                           error
                                           resp

                                           (not (did/also-known-as? doc handle))
                                           (assoc resp :error "Cannot verify the identity. DID document does not include the handle.")

                                           :else
                                           (verified-identity resp)))))))))

(defn resolve
  "Take a handle or DID and return a verified identity.

  A verified identity is a map with the following keys:
  :did     The identity's DID
  :pds     The URL of this DID's atproto personal data server(PDS).
  :handle  The handle, if present in the DID doc.

  If a handle is passed, check that it is present in the
  resolved's DID document.

  If a DID is passed, return the handle in the DID
  document, if any."
  [input & {:as opts}]
  (let [[cb val] (i/platform-async opts)]
    (cond
      (did/valid? input)    (resolve-with-did input cb)
      (handle/valid? input) (resolve-with-handle input cb)
      :else                 (cb {:error (str "Cannot resolve identity with invalid handle or DID: " input)}))
    val))
