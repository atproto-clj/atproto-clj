(ns atproto.xrpc.client
  "Cross-platform XRPC client for AT Proto."
  (:require [clojure.spec.alpha :as s]
            [atproto.runtime.http :as http]
            [atproto.runtime.json :as json]
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.cast :as cast]
            [atproto.data.json :as atproto-json]
            [atproto.lexicon :as lexicon]))

;; TODO:
;; - return error for unkown params when validating request?
;; - should we only serialize known parameters if lexicon loaded?
;; - productionize (error handling, timeout, retry...)
;; - pagination w/ cursor
;; - consider bubbling up server error in the response map

(defn create
  "Initialize the XRPC client."
  [{:keys [service session validate-requests?] :as config}]
  (if (and (not service) (not session))
    (throw (ex-info "A service or a session is required." config))
    {:service (or service (:pds session))
     :session (when session (atom session))
     :validate-requests? (boolean validate-requests?)}))

(defn request-validator
  [{:keys [validate-requests?]}]
  {::i/name ::request-validator
   ::i/enter (fn [{:keys [::i/request] :as ctx}]
               (let [spec (lexicon/request-spec-key (:nsid request))]
                 (if (and validate-requests?
                          (not (s/valid? spec request)))
                   (throw (ex-info (s/explain-str spec request)
                                   (s/explain-data spec request)))
                   ctx)))})

(defprotocol Session
  :extend-via-metadata true
  (auth-interceptor [session] "Interceptor to authenticate HTTP requests.")
  (refresh-token [session cb] "Refresh the token."))

(defn expired-token-error?
  [{:keys [headers body]}]
  (and (= "application/json" (:content-type headers))
       (= "ExpiredToken" (:error body))))

(defn delegate-auth-interceptor
  "Delegate authentication to the session, if any."
  [{:keys [session] :as client}]
  {::i/name ::delegate-auth-interceptor
   ::i/enter (fn [ctx]
               (if session
                 (update ctx ::i/queue #(cons (auth-interceptor @session) %))
                 ctx))
   ::i/leave (fn [ctx]
               (if (and session
                        (expired-token-error? (::i/response ctx))
                        (not (:refresh? @session)))
                 (refresh-token @session
                                (fn [{:keys [error] :as new-session}]
                                  (if error
                                    (i/continue (assoc ctx ::i/response error))
                                    (do
                                      (reset! session new-session)
                                      (i/continue (dissoc ctx ::i/response))))))
                 ctx))})

(defn- url
  [{:keys [service]} nsid]
  (str service "/xrpc/" nsid))

(defn xrpc-params->query-params
  "Serialize the xrpc params into query string params."
  [params]
  (reduce (fn [qp [k v]]
            (if (coll? v)
              (reduce #(update %1 k (fnil conj []) (str %2))
                      qp
                      v)
              (assoc qp k (str v))))
          {}
          params))

(defn- handle-xrpc-response
  [{:keys [error status body] :as http-response}]
  (cond
    error                  http-response
    (http/success? status) (:body http-response)
    (:error body)          (:body http-response)
    :else                  (http/error-map http-response)))

(defn- procedure-interceptor
  [client]
  {::i/name ::procedure
   ::i/enter (fn [ctx]
               (update ctx
                       ::i/request
                       (fn [{:keys [nsid params encoding body] :as request}]
                         (cast/dev request)
                         (let [encoding (or encoding
                                            (and (coll? body) "application/json")
                                            (throw (ex-info "Missing encoding" request)))]
                           (cond-> {:method :post
                                    :url (url client nsid)}
                             params (assoc :query-params (xrpc-params->query-params params))
                             body (assoc :body body
                                         :headers {:content-type encoding}))))))
   ::i/leave (fn [ctx]
               (update ctx ::i/response handle-xrpc-response))})

(defn procedure
  [{:keys [session] :as client} request & {:as opts}]
  (i/execute {::i/request request
              ::i/queue [(request-validator client)
                         (procedure-interceptor client)
                         (delegate-auth-interceptor client)
                         atproto-json/client-interceptor
                         json/client-interceptor
                         http/client-interceptor]}
             opts))

(defn- query-interceptor
  [client]
  {::i/name ::query
   ::i/enter (fn [ctx]
               (update ctx
                       ::i/request
                       (fn [{:keys [nsid params]}]
                         (cond-> {:method :get
                                  :url (url client nsid)}
                           params (assoc :query-params (xrpc-params->query-params params))))))
   ::i/leave (fn [ctx]
               (update ctx ::i/response handle-xrpc-response))})

(defn query
  [{:keys [session] :as client} request & {:as opts}]
  (i/execute {::i/request request
              ::i/queue [(request-validator client)
                         (query-interceptor client)
                         (delegate-auth-interceptor client)
                         atproto-json/client-interceptor
                         json/client-interceptor
                         http/client-interceptor]}
             opts))
