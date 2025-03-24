(ns atproto.session.unauthenticated
  "Unauthenticated session for public service/ops."
  (:require [clojure.spec.alpha :as s]
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.http :as http]
            [atproto.identity :as identity]
            [atproto.session :as session]))

(defn create
  "Take a handle, did, or app URL and return an unauthenticated session."
  [service & {:as opts}]
  (let [[cb val] (i/platform-async opts)]
    (cond
      (or (s/valid? ::identity/handle service)
          (s/valid? ::identity/did service))
      (identity/resolve-identity service
                                 :callback
                                 (fn [{:keys [error did did-doc handle] :as resp}]
                                   (if error
                                     (cb resp)
                                     (cb (cond-> #::session{:service (identity/did-doc-pds did-doc)
                                                            :did did
                                                            :authenticated? false
                                                            :refreshable? false}
                                           handle (assoc ::session/handle handle))))))

      (s/valid? ::http/url service)
      (cb {::session/service service}))

    val))
