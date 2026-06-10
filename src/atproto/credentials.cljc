(ns atproto.credentials
  "Authenticated session with an identifier and password."
  (:require [clojure.spec.alpha :as s]
            [atproto.runtime.interceptor :as i]
            [atproto.identity :as identity]
            [atproto.xrpc.client :as xrpc-client]))

(declare auth-interceptor xrpc-refresh-session)

(s/def ::accessJwt string?)
(s/def ::refreshJwt string?)
(s/def ::session
  (s/keys :req-un [::accessJwt ::refreshJwt]))

(defn- session
  "Build a session map (with Session protocol metadata) from a
  createSession/refreshSession response, falling back to `prev` for
  :did/:pds/:handle when the response omits did/didDoc/handle."
  ([resp] (session nil resp))
  ([prev {:keys [did didDoc handle accessJwt refreshJwt]}]
   (let [did (or did (:did prev))
         pds (if didDoc (identity/did-doc-pds didDoc) (:pds prev))
         handle (or handle (:handle prev))]
     (with-meta
       (cond-> {:accessJwt accessJwt
                :refreshJwt refreshJwt}
         did (assoc :did did)
         pds (assoc :pds pds)
         handle (assoc :handle handle))
       {`xrpc-client/auth-interceptor auth-interceptor
        `xrpc-client/refresh-token xrpc-refresh-session}))))

(defn- refresh-client
  "An XRPC client that authenticates with the session's refresh JWT."
  [sess]
  (xrpc-client/init {:session (assoc sess :refresh? true)}))

(defn- xrpc-create-session
  "Call `createSession` on the PDS to authenticate those credentials."
  [identity credentials cb]
  (let [xrpc-client (xrpc-client/init {:service (:pds identity)})]
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

(defn- session-killing-error?
  "True when this refreshSession failure means the session is definitively
  dead (HTTP 401, ExpiredToken, InvalidToken). Network and other transient
  errors return false."
  [{:keys [error http-response]}]
  (boolean
   (or (contains? #{"ExpiredToken" "InvalidToken"} error)
       (= 401 (:status http-response)))))

(defn- xrpc-refresh-session
  "Session protocol impl. POSTs com.atproto.server.refreshSession through a
  refresh-mode XRPC client. cb receives the rebuilt session (new
  accessJwt/refreshJwt, metadata reattached) or {:error ...}. Delivers
  {:error \"InvalidDID\" ...} if the response :did differs from the current
  session's.

  Definitive failures (HTTP 401, InvalidDID, ExpiredToken, InvalidToken) are
  tagged with :atproto.xrpc.client/session-expired? so the XRPC client drops
  the dead session; transient failures (e.g. network errors) leave it in
  place."
  [sess cb]
  (xrpc-client/procedure (refresh-client sess)
                         {:nsid "com.atproto.server.refreshSession"}
                         :callback
                         (fn [{:keys [error] :as resp}]
                           (cond
                             error
                             (cb (cond-> resp
                                   (session-killing-error? resp)
                                   (assoc ::xrpc-client/session-expired? true)))

                             (and (:did sess) (not= (:did sess) (:did resp)))
                             (cb {:error "InvalidDID"
                                  :message "The DID changed across the session refresh."
                                  :did (:did resp)
                                  ::xrpc-client/session-expired? true})

                             :else
                             (cb (session sess resp))))))

(defn logout
  "Invalidate the session server-side (com.atproto.server.deleteSession,
  authenticated with the refreshJwt). Server errors are reported in the
  result but the session should be considered dead regardless.

  Async via :callback/:promise/:channel opts; resolves to {} or {:error ...}."
  [session & {:as opts}]
  (let [[cb val] (i/platform-async opts)]
    (xrpc-client/procedure (refresh-client session)
                           {:nsid "com.atproto.server.deleteSession"}
                           :callback
                           (fn [{:keys [error] :as resp}]
                             (cb (if error resp {}))))
    val))

(defn auth-interceptor
  [{:keys [accessJwt refreshJwt refresh?] :as session}]
  {::i/name :auth-interceptor
   ::i/enter (fn [ctx]
               (assoc-in ctx
                         [::i/request :headers :authorization]
                         (str "Bearer " (if refresh? refreshJwt accessJwt))))})
