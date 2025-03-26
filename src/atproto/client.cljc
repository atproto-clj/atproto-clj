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
            [atproto.session :as session]
            [atproto.session.unauthenticated :as unauthenticated-session]
            [atproto.session.credentials :as credentials-session]
            [atproto.client.config :as-alias config]))

(defn create
  "Create a new atproto client with the given config map.

  Supported keys:
  :session     (optional) The unauthenticated or authenticated session to use.
  :credentials (optional) Convenience to create a Credentials-based session (if no :session).
  :service     (optional) Convenience to create an Unauthenticated session (if no :session)."
  [{:keys [session credentials service] :as config} & {:as opts}]
  (let [[cb val] (i/platform-async opts)]
    (cond
      session
      (cb {::session session})

      credentials
      (credentials-session/create credentials
                                  :callback
                                  (fn [{:keys [error] :as session}]
                                    (if error
                                      (cb session)
                                      (cb {::session session}))))

      service
      (unauthenticated-session/create service
                                      :callback
                                      (fn [{:keys [error] :as session}]
                                        (if error
                                          (cb session)
                                          (cb {::session session})))))
    val))

(defn did
  "The did of the authenticated user, or nil."
  [{:keys [::session]}]
  (and (::session/authenticated? session)
       (::session/did session)))

(defn procedure
  "Issue a procedure call with the given arguments."
  [{:keys [::session]} args & {:as opts}]
  (xrpc/procedure session args opts))

(defn query
  "Issue a query with the igven arguments."
  [{:keys [::session]} args & {:as opts}]
  (xrpc/query session args opts))
