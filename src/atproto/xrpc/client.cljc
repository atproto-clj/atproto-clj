(ns atproto.xrpc.client
  "Cross-platform XRPC client for AT Proto."
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as s]
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

(defn init
  "Initialize a new XRPC client and return it.

  config keys: :service, :session, :validate-requests?. The returned client
  also carries ::refresh-state (atom) used to single-flight token refreshes.
  Throws if neither :service nor :session is provided."
  [{:keys [service session validate-requests?] :as config}]
  (if (and (not service) (not session))
    (throw (ex-info "A service or a session is required." config))
    {:service (or service (:pds session))
     :session (when session (atom session))
     :validate-requests? (boolean validate-requests?)
     ::refresh-state (atom nil)}))

(defn request-validator
  [{:keys [validate-requests?]}]
  {::i/name ::request-validator
   ::i/enter (fn [{:keys [::i/request] :as ctx}]
               (let [spec (binding [lexicon/*schema-validate* validate-requests?]
                            (lexicon/request-spec-key (:nsid request)))]
                 (if (not (s/valid? spec request))
                   (throw (ex-info "Invalid Request"
                                   {:explain-data (s/explain-data spec request)}))
                   ctx)))})

(defprotocol Session
  :extend-via-metadata true
  (auth-interceptor [session]
    "Interceptor to authenticate HTTP requests.")
  (refresh-token [session cb]
    "Refresh the session's tokens.

     MUST eventually call cb exactly once with either a NEW session value
     (satisfying Session, with refreshed tokens) or an {:error ...} map.
     Implementations must never throw out of the calling thread without
     invoking cb."))

(defn invalid-token-response?
  "True if this HTTP response indicates the access token was rejected and a
  refresh should be attempted. Matches:
  - 400 with a JSON body (content-type prefix \"application/json\") whose
    :error is \"ExpiredToken\"
  - 401 with no WWW-Authenticate header (bearer-auth convention)
  - 401 whose WWW-Authenticate starts with \"Bearer \" or \"DPoP \" and
    contains error=\"invalid_token\" (RFC 6750/9449)."
  [{:keys [error status headers body] :as http-response}]
  (boolean
   (and (not error)
        (case status
          400 (and (some-> (:content-type headers)
                           (str/starts-with? "application/json"))
                   (= "ExpiredToken" (:error body)))
          401 (let [www-authenticate (:www-authenticate headers)]
                (or (nil? www-authenticate)
                    (and (or (str/starts-with? www-authenticate "Bearer ")
                             (str/starts-with? www-authenticate "DPoP "))
                         (str/includes? www-authenticate "error=\"invalid_token\""))))
          false))))

(defn ^:deprecated expired-token-error?
  "Deprecated alias of `invalid-token-response?`."
  [http-response]
  (invalid-token-response? http-response))

(defn- single-flight-refresh!
  "Join the client's single-flight token refresh.

  cb is invoked exactly once with the refresh result: a new session value or
  an {:error ...} map. The first caller triggers the session's refresh-token;
  callers that arrive while a refresh is in flight queue on its result. On
  success the client's session atom is reset to the new session before any
  waiter is invoked."
  [{:keys [session ::refresh-state]} cb]
  (let [[prev _] (swap-vals! refresh-state
                             (fn [state]
                               (if state
                                 (update state :waiters conj cb)
                                 {:waiters [cb]})))]
    (when (nil? prev)
      (let [delivered? (atom false)
            deliver! (fn [result]
                       (when (compare-and-set! delivered? false true)
                         (let [[{:keys [waiters]} _] (swap-vals! refresh-state
                                                                (constantly nil))]
                           (when-not (:error result)
                             (reset! session result))
                           (run! #(% result) waiters))))]
        (try
          (refresh-token @session deliver!)
          (catch #?(:clj Throwable :cljs :default) t
            (deliver! {:error "TokenRefreshError"
                       :message (ex-message t)
                       :exception t})))))))

(defn delegate-auth-interceptor
  "Delegate authentication to the session, if any.

  ::i/enter snapshots the pristine context under ::original-ctx and conses
  the session's auth interceptor onto the queue.

  ::i/leave, when the response indicates the access token was rejected
  (see `invalid-token-response?`), the session is not itself a refresh client
  (:refresh?), and this request has not already been retried (::auth-retried?):
  joins the client's single-flight refresh (::refresh-state), then either
  retries the pristine request once with a fresh auth interceptor from the
  new session, or continues with the full {:error ...} map from the refresh
  callback as the response. A retried request that fails again with an
  invalid-token response is returned to the caller as-is (no second refresh).
  The leave fn returns nil after handing off to i/continue."
  [{:keys [session] :as client}]
  (let [wrap-auth (fn [ctx]
                    (-> ctx
                        (assoc ::original-ctx ctx)
                        (update ::i/queue #(cons (auth-interceptor @session) %))))]
    {::i/name ::delegate-auth-interceptor
     ::i/enter (fn [ctx]
                 (if session
                   (wrap-auth ctx)
                   ctx))
     ::i/leave (fn [{:keys [::i/response ::original-ctx] :as ctx}]
                 (if (and session
                          (invalid-token-response? response)
                          (not (:refresh? @session))
                          (not (::auth-retried? ctx)))
                   (do (single-flight-refresh!
                        client
                        (fn [{:keys [error] :as result}]
                          (if error
                            (i/continue (-> ctx
                                            (dissoc ::original-ctx)
                                            (assoc ::i/response result)))
                            (i/continue (wrap-auth (assoc original-ctx
                                                          ::auth-retried? true))))))
                       nil)
                   (dissoc ctx ::original-ctx ::auth-retried?)))}))

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
                         (let [encoding (or encoding
                                            (when (coll? body) "application/json"))]
                           (when (and body (not encoding))
                             (throw (ex-info "Missing encoding" request)))
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
