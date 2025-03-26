(ns atproto.xrpc.client
  "Cross-platform XRPC client for AT Proto."
  (:require [clojure.spec.alpha :as s]
            [atproto.runtime.http :as http]
            [atproto.runtime.json :as json]
            [atproto.runtime.interceptor :as i]
            [atproto.session :as session]))

;; TODO:
;; - should parameters be in args?
;; - how to represent authentication data?
;; - timeout
;; - retry w/ backoff

(defn- url
  [service nsid]
  (str service "/xrpc/" (name nsid)))

(defn- handle-xrpc-response
  [{:keys [error status body] :as http-response}]
  (cond
    error                  http-response
    (http/success? status) (:body http-response)
    (:error body)          (:body http-response)
    :else                  (http/error-map http-response)))

(defn- procedure-interceptor
  [session]
  {::i/name ::procedure
   ::i/enter (fn [ctx]
               (update ctx
                       ::i/request
                       (fn [{:keys [op params headers]}]
                         (let [body (when (not (and (coll? params)
                                                    (empty? params)))
                                      params)
                               headers (cond
                                         (:content-type headers) headers
                                         (not body) headers
                                         (coll? body) (assoc headers :content-type "application/json")
                                         :else (throw (ex-info "Must supply content-type header" {})))]
                           (cond-> {:method :post
                                    :url (url (::session/service session) op)}
                             body (assoc :body body)
                             headers (assoc :headers headers))))))
   ::i/leave (fn [ctx]
               (update ctx ::i/response handle-xrpc-response))})

(defn procedure
  "Execute the procedure on the session's service endpoint."
  [session request & {:as opts}]
  (i/execute {::i/request request
              ::i/queue (->> [(procedure-interceptor session)
                              (when (::session/authenticated? session)
                                (session/auth-interceptor session))
                              json/interceptor
                              http/interceptor]
                             (remove nil?))}
             opts))

(defn- query-interceptor
  [session]
  {::i/name ::query
   ::i/enter (fn [ctx]
               (update ctx
                       ::i/request
                       (fn [{:keys [op params headers]}]
                         {:method :get
                          :url (url (::session/service session) op)
                          :query-params params
                          :headers headers})))
   ::i/leave (fn [ctx]
               (update ctx ::i/response handle-xrpc-response))})

(defn query
  "Execute the query on the session's service endpoint."
  [session request & {:as opts}]
  (i/execute {::i/request request
              ::i/queue [(query-interceptor session)
                         (when (::session/authenticated? session)
                           (session/auth-interceptor session))
                         json/interceptor
                         http/interceptor]}
             opts))
