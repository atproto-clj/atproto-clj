(ns atproto.session.credentials
  "Authenticated session with an identifier and password."
  (:require [clojure.spec.alpha :as s]
            [atproto.runtime.interceptor :as i]
            [atproto.identity :as identity]
            [atproto.xrpc.client :as xrpc]
            [atproto.session :as session]
            [atproto.session.unauthenticated :as unauthenticated]))

;; todo:
;; - would the user need to access the JSON tokens from the session?
;; - do we need to copy over some data from the previous session when refreshing?
;; - auto-refresh

(declare refresh-session auth-interceptor)

(defn- auth-session
  "Build a session from the PDS response."
  [{:keys [did didDoc handle] :as resp} cb]
  (cb (with-meta
        (cond-> #::session{:service (identity/did-doc-pds didDoc)
                           :did did
                           :authenticated? true
                           :refreshable? true}
          handle (assoc ::session/handle handle))
        {`session/auth-interceptor #(auth-interceptor % (select-keys resp [:accessJwt :refreshJwt]))
         `session/refresh-session refresh-session})))

(defn- xrpc-create-session
  "Call `createSession` on the PDS to authenticate those credentials."
  [unauth-session credentials cb]
  (xrpc/procedure unauth-session
                  {:op :com.atproto.server.createSession
                   :params {:identifier (::session/did unauth-session)
                            :password (:password credentials)}}
                  :callback
                  (fn [{:keys [error] :as resp}]
                    (if error
                      (cb resp)
                      (cb (auth-session resp))))))

(defn create
  "Authenticate those credentials and return a session that can be used with `atproto.client`.

  The identifier can be an atproto handle or did."
  [credentials & {:as opts}]
  (let [[cb val] (i/platform-async opts)]
    ;; We first create an unauthenticated session to the user's PDS to
    ;; be able to call the `createSession` xrpc procedure.
    (unauthenticated/create (:identifier credentials)
                            :callback
                            (fn [{:keys [error] :as unauth-session}]
                              (if error
                                (cb unauth-session)
                                (xrpc-create-session unauth-session credentials cb))))
    val))

(defn refresh-session
  "Refresh the credentials session."
  [session cb]
  (xrpc/procedure (assoc session :refresh? true)
                  {:op :com.atproto.server.refreshSession}
                  :callback
                  (fn [{:keys [error] :as resp}]
                    (if error
                      (cb resp)
                      (cb (auth-session resp))))))

(defn auth-interceptor
  "Authenticate HTTP requests using the session."
  [session {:keys [accessJwt refreshJwt]}]
  {::i/name ::auth-interceptor
   ::i/enter (fn [ctx]
               (assoc-in ctx
                         [::i/request :headers :authorization]
                         (str "Bearer " (if (:refresh? session)
                                          refreshJwt
                                          accessJwt))))
   ::i/leave (fn [ctx]
               ctx)})
