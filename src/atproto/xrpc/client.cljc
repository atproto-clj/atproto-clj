(ns atproto.xrpc.client
  "Cross-platform XRPC client for AT Proto."
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [atproto.runtime.http :as http]
            [atproto.runtime.json :as json]
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.cast :as cast]
            [atproto.runtime.retry :as retry]
            [atproto.data.json :as atproto-json]
            [atproto.xrpc.error :as xrpc-error]
            [atproto.lexicon :as lexicon]))

;; TODO:
;; - return error for unkown params when validating request?
;; - should we only serialize known parameters if lexicon loaded?

(def ^:private service-proxy-regex
  ;; "<did>#<service-identifier>", e.g. "did:web:api.bsky.app#bsky_appview"
  #"^did:[a-z]+:[a-zA-Z0-9._:%-]*[a-zA-Z0-9._-]#[a-zA-Z0-9_]+$")

(s/def ::service-proxy
  (s/and string? #(re-matches service-proxy-regex %)))

(defn init
  "Initialize a new XRPC client and return it.

  config keys:
  :service             Base URL of the XRPC service.
  :session             Session to authenticate requests with.
  :validate-requests?  Validate requests against their lexicon before sending.
  :validate-responses? Validate successful response bodies against their
                       lexicon (schema-invalid responses yield
                       {:error \"InvalidResponse\" ...}).
  :headers             Map of default headers for every request (lowercase
                       keyword keys).
  :service-proxy       \"<did>#<service-id>\" emitted as the atproto-proxy
                       header (a per-request atproto-proxy header wins).
  :labelers            Coll of labeler DIDs, or {:did ... :redact? true} maps,
                       emitted as the atproto-accept-labelers header.
  :timeout             Default per-request timeout in ms.
  :max-retries         Default retry count for retryable errors (default 0 = off).

  The returned client also carries ::refresh-state (atom) used to
  single-flight token refreshes. Throws if neither :service nor :session is
  provided, or if :service-proxy is malformed."
  [{:keys [service session validate-requests? validate-responses?
           headers service-proxy labelers timeout max-retries] :as config}]
  (cond
    (and (not service) (not session))
    (throw (ex-info "A service or a session is required." config))

    (and service-proxy (not (s/valid? ::service-proxy service-proxy)))
    (throw (ex-info "Invalid :service-proxy; expected \"<did>#<service-id>\"."
                    {:service-proxy service-proxy}))

    :else
    (cond-> {:service (or service (:pds session))
             :session (when session (atom session))
             :validate-requests? (boolean validate-requests?)
             :validate-responses? (boolean validate-responses?)
             ::refresh-state (atom nil)}
      headers       (assoc :headers headers)
      service-proxy (assoc :service-proxy service-proxy)
      labelers      (assoc :labelers labelers)
      timeout       (assoc :timeout timeout)
      max-retries   (assoc :max-retries max-retries))))

(defn with-service-proxy
  "Return a client that routes requests via the given service, e.g.
  \"did:web:api.bsky.app#bsky_appview\" (emitted as the atproto-proxy header)."
  [client service]
  (if (s/valid? ::service-proxy service)
    (assoc client :service-proxy service)
    (throw (ex-info "Invalid :service-proxy; expected \"<did>#<service-id>\"."
                    {:service-proxy service}))))

(defn with-labelers
  "Return a client that sends the given labelers (a coll of labeler DIDs, or
  {:did ... :redact? true} maps) as the atproto-accept-labelers header."
  [client labelers]
  (assoc client :labelers labelers))

(defn- labelers-header-value
  [labelers]
  (->> labelers
       (map (fn [labeler]
              (if (map? labeler)
                (str (:did labeler) (when (:redact? labeler) ";redact"))
                labeler)))
       (str/join ", ")))

(defn- merged-headers
  "Merge the headers for an outgoing request.

  Precedence: client defaults < proxy/labelers < per-request headers <
  computed headers. The atproto-proxy header is only set from the client when
  absent per-request; atproto-accept-labelers merges the client's labelers
  with any per-request value."
  [{:keys [headers service-proxy labelers]} request-headers computed-headers]
  (let [merged (merge headers request-headers)
        merged (if (and service-proxy (not (:atproto-proxy merged)))
                 (assoc merged :atproto-proxy service-proxy)
                 merged)
        merged (if (seq labelers)
                 (assoc merged :atproto-accept-labelers
                        (->> [(labelers-header-value labelers)
                              (some-> (:atproto-accept-labelers merged) str/trim)]
                             (remove str/blank?)
                             (str/join ", ")))
                 merged)]
    (merge merged computed-headers)))

(defn request-validator
  [{:keys [validate-requests?]}]
  {::i/name ::request-validator
   ::i/enter (fn [{:keys [::i/request] :as ctx}]
               (let [spec (binding [lexicon/*schema-validate* validate-requests?]
                            (lexicon/request-spec-key (:nsid request)))]
                 (if (not (s/valid? spec request))
                   (assoc ctx ::i/response (xrpc-error/invalid-request
                                            (s/explain-data spec request)))
                   ctx)))})

(defn- response-validator
  "Validate successful XRPC response bodies against the method's output schema.

  Leave-stage only; sits between the query/procedure interceptor and the
  transport interceptors so it sees the decoded HTTP response. A
  schema-invalid successful response is replaced with
  {:error \"InvalidResponse\" :message ... :explain-data ...}. Skipped when
  :validate-responses? is false, for error-bodied or failed responses, and
  for NSIDs whose schema is not registered. Validation is lenient
  (lexicon/*strict* bound to false): server responses are parsed laxly while
  requests stay strict, per the reference implementation's guidance
  (lex-schema validator.ts)."
  [{:keys [validate-responses?]} nsid]
  {::i/name ::response-validator
   ::i/leave
   (fn [ctx]
     (update ctx
             ::i/response
             (fn [{:keys [error status headers body] :as http-response}]
               (if-not (and validate-responses?
                            (not error)
                            (http/success? status)
                            (not (and (map? body) (:error body))))
                 http-response
                 (let [spec (binding [lexicon/*schema-validate* true]
                              (lexicon/response-spec-key nsid))]
                   (if-not (or (fn? spec) (s/get-spec spec))
                     http-response
                     (let [xrpc-response (cond-> {:body body}
                                           (:content-type headers)
                                           (assoc :encoding
                                                  (-> (:content-type headers)
                                                      (str/split #";")
                                                      first
                                                      str/trim)))]
                       (binding [lexicon/*strict* false]
                         (if (s/valid? spec xrpc-response)
                           http-response
                           {:error "InvalidResponse"
                            :message (str "The response body does not match the "
                                          "output schema for " nsid ".")
                            :retryable? false
                            :explain-data (s/explain-data spec xrpc-response)})))))))))})

(defprotocol Session
  :extend-via-metadata true
  (auth-interceptor [session]
    "Interceptor to authenticate HTTP requests.")
  (refresh-token [session cb]
    "Refresh the session's tokens.

     MUST eventually call cb exactly once with either a NEW session value
     (satisfying Session, with refreshed tokens) or an {:error ...} map.
     Error maps may carry ::session-expired? true to signal that the session
     is definitively dead (e.g. the server rejected the refresh token); the
     client then drops its session and subsequent requests are sent
     unauthenticated. Implementations must never throw out of the calling
     thread without invoking cb."))

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
  waiter is invoked; on an error carrying ::session-expired? the session atom
  is reset to nil (the session is dropped)."
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
                           (if (:error result)
                             (when (::session-expired? result)
                               (reset! session nil))
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
  The leave fn returns nil after handing off to i/continue.

  A session dropped after a definitive refresh failure (see ::session-expired?
  on `refresh-token`) leaves nil in the session atom: subsequent requests are
  sent unauthenticated and no further refresh is attempted."
  [{:keys [session] :as client}]
  (let [wrap-auth (fn [ctx]
                    (-> ctx
                        (assoc ::original-ctx ctx)
                        (update ::i/queue #(cons (auth-interceptor @session) %))))]
    {::i/name ::delegate-auth-interceptor
     ::i/enter (fn [ctx]
                 (if (and session @session)
                   (wrap-auth ctx)
                   ctx))
     ::i/leave (fn [{:keys [::i/response ::original-ctx] :as ctx}]
                 (if (and session
                          @session
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

(defn- with-headers-meta
  "Attach the response headers as ::headers metadata when the body supports
  metadata (maps/collections); other bodies are returned untouched."
  [body headers]
  (if #?(:clj (instance? clojure.lang.IObj body)
         :cljs (implements? IWithMeta body))
    (vary-meta body assoc ::headers headers)
    body))

(defn response-headers
  "The HTTP response headers of a successful XRPC call, read from the
  body's metadata (nil for bodies that do not support metadata)."
  [body]
  (::headers (meta body)))

(defn- handle-xrpc-response
  [{:keys [error status headers body] :as http-response}]
  (cond
    ;; transport-level failures are retryable; other error maps reaching the
    ;; leave chain (auth refresh failures, Aborted) pass through unchanged
    error                  (case error
                             ("HTTPClientError" "Timeout") (xrpc-error/network-error http-response)
                             http-response)
    (http/success? status) (with-headers-meta body headers)
    :else                  (xrpc-error/http-error http-response)))

(defn- procedure-interceptor
  [client]
  {::i/name ::procedure
   ::i/enter (fn [{:keys [::i/request] :as ctx}]
               (let [{:keys [nsid params encoding body headers]} request
                     encoding (or encoding
                                  (when (coll? body) "application/json"))]
                 (cond
                   (and body (not encoding))
                   (assoc ctx ::i/response
                          {:error "InvalidRequest"
                           :message "Missing :encoding for the request body."
                           :retryable? false})

                   (and body (:content-type headers))
                   (assoc ctx ::i/response
                          {:error "InvalidRequest"
                           :message "Do not set a content-type header; use :encoding to specify the body MIME type."
                           :retryable? false})

                   :else
                   (assoc ctx ::i/request
                          (let [request-headers (merged-headers client
                                                                headers
                                                                (when body
                                                                  {:content-type encoding}))
                                timeout (or (:timeout request) (:timeout client))
                                signal (:signal request)]
                            (cond-> {:method :post
                                     :url (url client nsid)}
                              (seq request-headers) (assoc :headers request-headers)
                              params (assoc :query-params (xrpc-params->query-params params))
                              body (assoc :body body)
                              timeout (assoc :timeout timeout)
                              signal (assoc :signal signal)))))))
   ::i/leave (fn [ctx]
               (update ctx ::i/response handle-xrpc-response))})

(defn- query-interceptor
  [client]
  {::i/name ::query
   ::i/enter (fn [ctx]
               (update ctx
                       ::i/request
                       (fn [{:keys [nsid params headers timeout signal]}]
                         (let [request-headers (merged-headers client headers nil)
                               timeout (or timeout (:timeout client))]
                           (cond-> {:method :get
                                    :url (url client nsid)}
                             (seq request-headers) (assoc :headers request-headers)
                             params (assoc :query-params (xrpc-params->query-params params))
                             timeout (assoc :timeout timeout)
                             signal (assoc :signal signal))))))
   ::i/leave (fn [ctx]
               (update ctx ::i/response handle-xrpc-response))})

(defn abort-signal
  "Create a cancellation signal accepted by query/procedure as :signal.
  Trip it with `abort!`."
  []
  (retry/abort-signal))

(defn abort!
  "Trip the signal. Pending xrpc calls deliver {:error \"Aborted\"} exactly
  once and, where the platform supports it (CLJS XhrIo), the in-flight HTTP
  request is aborted. On CLJ the underlying http-kit request cannot be
  interrupted; its eventual result is discarded."
  [signal]
  (retry/abort! signal))

(def ^:private aborted-response
  {:error "Aborted" :message "Request aborted."})

(defn- signal-interceptor
  "Replace the response with {:error \"Aborted\"} when the signal has been
  tripped, checked at chain entry (before the request is sent) and exit."
  [signal]
  (let [check (fn [ctx]
                (if (retry/aborted? signal)
                  (assoc ctx ::i/response aborted-response)
                  ctx))]
    {::i/name ::signal
     ::i/enter check
     ::i/leave check}))

(defn- execute-xrpc
  "Execute the XRPC interceptor chain for the request.

  When the request carries :max-retries (or the client has a default),
  retryable errors are retried with exponential backoff; the whole chain is
  re-executed so auth refresh works per attempt. When the request carries a
  :signal, abort! delivers {:error \"Aborted\"} exactly once (in-flight
  results are discarded)."
  [client xrpc-interceptor {:keys [signal] :as request} opts]
  (let [max-retries (or (:max-retries request) (:max-retries client) 0)
        ctx {::i/request request
             ::i/queue (cond->> [(request-validator client)
                                 xrpc-interceptor
                                 (response-validator client (:nsid request))
                                 (delegate-auth-interceptor client)
                                 atproto-json/client-interceptor
                                 json/client-interceptor
                                 http/client-interceptor]
                         signal (cons (signal-interceptor signal)))}]
    (if (or (pos? max-retries) signal)
      (let [[cb val] (i/platform-async opts)
            delivered? (atom false)
            deliver! (fn [result]
                       (when (compare-and-set! delivered? false true)
                         (cb result)))]
        (when signal
          (retry/on-abort! signal #(deliver! aborted-response)))
        (retry/with-retry (fn [attempt-cb]
                            (i/execute ctx :callback attempt-cb))
                          {:max-retries max-retries
                           :signal signal}
                          deliver!)
        val)
      (i/execute ctx opts))))

(defn procedure
  [client request & {:as opts}]
  (execute-xrpc client (procedure-interceptor client) request opts))

(defn query
  [client request & {:as opts}]
  (execute-xrpc client (query-interceptor client) request opts))

(defn- no-cursor?
  [cursor]
  (or (nil? cursor)
      (and (string? cursor) (str/blank? cursor))))

(defn fetch-pages
  "Cursor pagination driver.

  Repeatedly calls `query` with :cursor threaded from each response into the
  request's :params, invoking (step-fn acc page) per page (a `reduced` acc
  short-circuits). Stops when the response has no cursor, the page's items
  are empty, :max-pages is reached, or an error occurs (the error map is
  then the delivered result). Async like everything else; the deferred value
  is the final acc.

  opts: :items-fn (default :records), :cursor-fn (default :cursor),
        :max-pages, plus :channel/:callback/:promise."
  [client request step-fn init-acc & {:as opts}]
  (let [items-fn (or (:items-fn opts) :records)
        cursor-fn (or (:cursor-fn opts) :cursor)
        max-pages (:max-pages opts)
        [cb val] (i/platform-async (select-keys opts [:channel :callback :promise]))]
    (letfn [(fetch! [request acc page-count]
              (query client request
                     :callback
                     (fn [page]
                       (if (:error page)
                         (cb page)
                         (let [result (step-fn acc page)
                               acc (unreduced result)
                               cursor (cursor-fn page)
                               page-count (inc page-count)]
                           (if (or (reduced? result)
                                   (no-cursor? cursor)
                                   (empty? (items-fn page))
                                   (and max-pages (<= max-pages page-count)))
                             (cb acc)
                             (fetch! (assoc-in request [:params :cursor] cursor)
                                     acc
                                     page-count)))))))]
      (fetch! request init-acc 0))
    val))

(defn fetch-all
  "fetch-pages collecting (items-fn page) from every page into a single
  vector. Guard unbounded collections with :max-pages."
  [client request & {:as opts}]
  (let [items-fn (or (:items-fn opts) :records)]
    (fetch-pages client request
                 (fn [acc page] (into acc (items-fn page)))
                 []
                 opts)))

#?(:clj
   (defn page-seq
     "Lazy seq of page bodies, paginating like `fetch-pages`; each step blocks
     on the underlying promise. CLJ-only convenience; do not use on
     event-loop threads. If a page fetch fails, the error map is the final
     element of the seq.

     opts: :items-fn (default :records), :cursor-fn (default :cursor)."
     [client request & {:as opts}]
     (let [items-fn (or (:items-fn opts) :records)
           cursor-fn (or (:cursor-fn opts) :cursor)
           step (fn step [request]
                  (lazy-seq
                   (let [page @(query client request)]
                     (if (:error page)
                       (list page)
                       (let [cursor (cursor-fn page)]
                         (cons page
                               (when-not (or (no-cursor? cursor)
                                             (empty? (items-fn page)))
                                 (step (assoc-in request [:params :cursor] cursor)))))))))]
       (step request))))
