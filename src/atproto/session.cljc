(ns atproto.session
  (:require [clojure.spec.alpha :as s]
            [atproto.interceptor :as i]
            [atproto.http :as http]
            [atproto.did :as did]
            [atproto.handle :as handle]))

(s/def ::session
  (s/keys :req [::service ::authenticated? ::refreshable?]
          :opt [::did ::handle]))

;; An AppView URL for unauthenticated sessions.
;; A PDS URL for authenticated sessions.
(s/def ::service ::http/url)

;; The DID of the authenticated user.
(s/def ::did ::did/did)

;; The handle of the authenticated user, if any.
(s/def ::handle ::handle/handle)

;; Whether the session is authenticated
(s/def ::authenticated? boolean?)

;; Whether the session is refreshable
(s/def ::refreshable? boolean?)

(defprotocol AuthenticatedSession
  :extend-via-metadata true
  (auth-interceptor [session] "The interceptor to manage auth."))

(defprotocol RefreshableSession
  :extend-via-metadata true
  (refresh-session [session cb] "Refresh the session."))

;; todo: figure out if we need a public fn or if it should only be automatic
(defn refresh
  [session & {:as opts}]
  (when (::refreshable? session)
    (let [[cb val] (i/platform-async opts)]
      (refresh-session session cb)
      val)))
