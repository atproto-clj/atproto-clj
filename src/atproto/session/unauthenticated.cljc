(ns atproto.session.unauthenticated
  "Unauthenticated session for public service/ops."
  (:require [clojure.spec.alpha :as s]
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.http :as http]
            [atproto.did :as did]
            [atproto.handle :as handle]
            [atproto.identity :as identity]
            [atproto.session :as session]))

(s/def ::service (s/or :handle ::handle/handle
                       :did ::did/at-did
                       :url ::http/url))

(defn- handle-identity-resolution
  [{:keys [error pds did handle] :as resp} cb]
  (if error
    (cb resp)
    (cb (cond-> #::session{:service pds
                           :authenticated? false
                           :refreshable? false}
          did (assoc ::session/did did)
          handle (assoc ::session/handle handle)))))

(defn create
  "Take a handle, did, or app URL and return an unauthenticated session."
  [service & {:as opts}]
  (let [[cb val] (i/platform-async opts)]
    (if (not (s/valid? ::service service))
      (cb (merge {:error "Invalid service."}
                 (s/explain-data ::service service)))
      (let [[type service] (s/conform ::service service)]
        (case type
          (:handle :did)
          (identity/resolve service
                            :callback
                            #(handle-identity-resolution % cb))
          :url
          (cb {::session/service service}))))
    val))
