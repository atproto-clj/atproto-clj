(ns atproto.xrpc.client
  "Cross-platform XRPC client for AT Proto."
  (:require [clojure.spec.alpha :as s]
            [atproto.runtime.http :as http]
            [atproto.runtime.json :as json]
            [atproto.runtime.interceptor :as i]
            [atproto.session :as session]))

;; TODO:
;; - should async opts be in args?
;; - add option to validate args
;; - productionize (error handling, timeout, retry...)

(defn- url
  [service op]
  (str service "/xrpc/" (name op)))

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
                       (fn [{:keys [op params input]}]
                         (cond-> {:method :post
                                  :url (url (::session/service session) op)
                                  :query-params params}
                           input (assoc :headers {:content-type (:encoding input)}
                                        :body (:body input))))))
   ::i/leave (fn [ctx]
               (update ctx ::i/response handle-xrpc-response))})

(defn procedure
  "Execute the procedure on the session's service endpoint."
  [session request & {:as opts}]
  (i/execute {::i/request request
              ::i/queue (->> [(procedure-interceptor session)
                              (when (::session/authenticated? session)
                                (session/auth-interceptor session))
                              json/client-interceptor
                              http/client-interceptor]
                             (remove nil?))}
             opts))

(defn- query-interceptor
  [session]
  {::i/name ::query
   ::i/enter (fn [ctx]
               (update ctx
                       ::i/request
                       (fn [{:keys [op params]}]
                         {:method :get
                          :url (url (::session/service session) op)
                          :query-params params})))
   ::i/leave (fn [ctx]
               (update ctx ::i/response handle-xrpc-response))})

(defn query
  "Execute the query on the session's service endpoint."
  [session request & {:as opts}]
  (i/execute {::i/request request
              ::i/queue [(query-interceptor session)
                         (when (::session/authenticated? session)
                           (session/auth-interceptor session))
                         json/client-interceptor
                         http/client-interceptor]}
             opts))
