(ns atproto.client
  "Cross-platform API.

  All functions are async, and follow the same pattern for specifying how
  results are returned. Each function takes keyword argument options, and
  callers can specify a :channel, :callback, or :promise which will receive
  the results.

  Not all mechanisms are supported on all platforms. If no result mechanism is
  specified, a platform-appropriate deferred value (e.g. promise or core.async
  channel will be returned.)"
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [atproto.runtime.interceptor :as i]
            [atproto.xrpc.client :as xrpc]
            [atproto.identity :as identity]
            [atproto.credentials :as credentials]))

(defn init
  "Initialize a new atproto client with the given config map.

  Supported keys in the config map:
  :service            Handle, DID, or app URL to connect to.
  :session            Required to make authenticated requests to the service.
  :credentials        Convenience to automatically create a credentials-based session.
  :validate-requests? Whether to validate requests before sending them to the server."
  [{:keys [service credentials session validate-requests?] :as config} & {:as opts}]
  (let [[cb val] (i/platform-async opts)]
    (cond

      ;; Resolve the service endpoint if a handle or DID was passed
      (or (s/valid? ::identity/handle service)
          (s/valid? ::identity/did service))
      (identity/resolve-identity service
                                 :callback
                                 (fn [{:keys [error did did-doc handle] :as resp}]
                                   (if error
                                     (cb resp)
                                     (init (assoc config
                                                  :service
                                                  (identity/did-doc-pds did-doc))
                                           :callback cb))))

      ;; Create a credentials-based session if credentials were passed
      credentials
      (credentials/create credentials
                          :callback
                          (fn [{:keys [error] :as session}]
                            (if error
                              (cb error)
                              (init (-> config
                                        (dissoc :credentials)
                                        (assoc :session session))
                                    :callback cb))))

      ;; Otherwise initialize an XRPC client for the service/session
      :else
      (cb (xrpc/init
           (cond-> {:validate-requests? (boolean validate-requests?)}
             service (assoc :service service)
             session (assoc :session session)))))
    val))

(defn did
  "The did of the authenticated user, or nil."
  [{:keys [session]}]
  (and session
       (:did @session)))

(defn procedure
  "Call the procedure on the server with the given parameters.

  The `request` map accepts the following keys:
  :nsid      NSID of the procedure, `string`, required.
  :params    Procedure parameters, `map`, optional.
  :body      Body of the procedure call, `::atproto/data` or `bytes`, optional
  :encoding  MIME type of the body, `string`, required if the body are `bytes`."
  [client request & {:as opts}]
  (xrpc/procedure client request opts))

(defn query
  "Issue a query against the server with the given parameters.

  The `reuqest` map accepts the following keys:
  :nsid      NSID of the query, `string`, required.
  :params    Query parameters, `map`, optional."
  [client request & {:as opts}]
  (xrpc/query client request opts))
