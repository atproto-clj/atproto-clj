(ns atproto.credentials
  "Authenticated session with an identifier and password."
  (:require [clojure.spec.alpha :as s]
            [atproto.runtime.interceptor :as i]
            [atproto.identity :as identity]
            [atproto.xrpc.client :as xrpc-client]))

(declare auth-interceptor xrpc-refresh-session)

(defn- session
  [{:keys [did didDoc handle accessJwt refreshJwt]}]
  (with-meta
    (cond-> {:accessJwt accessJwt
             :refreshJwt refreshJwt}
      did (assoc :did did)
      didDoc (assoc :pds (identity/did-doc-pds didDoc))
      handle (assoc :handle handle))
    {`xrpc-client/auth-interceptor auth-interceptor
     `xrpc-client/refresh-token xrpc-refresh-session}))

(defn- xrpc-create-session
  "Call `createSession` on the PDS to authenticate those credentials."
  [identity credentials cb]
  (let [xrpc-client (xrpc-client/create {:service (:pds identity)})]
    (xrpc-client/procedure xrpc-client
                           {:nsid "com.atproto.server.createSession"
                            :body {:identifier (:did identity)
                                   :password (:password credentials)}}
                           :callback
                           (fn [{:keys [error] :as resp}]
                             (if error
                               (cb resp)
                               (cb (session resp)))))))

(defn create
  "Authenticate those credentials and return a session that can be used with `atproto.client`.

  The identifier can be an atproto handle or did."
  [credentials & {:as opts}]
  (let [[cb val] (i/platform-async opts)]
    (identity/resolve-identity (:identifier credentials)
                               :callback
                               (fn [{:keys [error] :as identity}]
                                 (if error
                                   (cb identity)
                                   (xrpc-create-session identity credentials cb))))
    val))

(defn xrpc-refresh-session
  [session cb]
  (xrpc-client/procedure (xrpc-client/create (assoc session :refresh? true))
                         {:nsid "com.atproto.server.refreshSession"}
                         :callback
                         (fn [{:keys [error] :as resp}]
                           (if error
                             (cb resp)
                             (cb (merge session
                                        (session resp)))))))

(defn auth-interceptor
  [{:keys [accessJwt refreshJwt refresh?] :as session}]
  {::i/name :auth-interceptor
   ::i/enter (fn [ctx]
               (assoc-in ctx
                         [::i/request :headers :authorization]
                         (str "Bearer " (if refresh? refreshJwt accessJwt))))})
