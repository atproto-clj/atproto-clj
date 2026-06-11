(ns atproto.xrpc.server
  "Functions to implement atproto HTTP services.

  Request pipeline (reference parity, packages/xrpc-server/src/server.ts):
  parse/validate params -> authenticate -> validate input -> rate-limit ->
  handle -> validate output. Auth failures fire before input validation."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.http :as http]
            [atproto.runtime.json :as json]
            [atproto.runtime.cast :as cast]
            [atproto.runtime.crypto :as runtime-crypto]
            [atproto.data.json :as atproto-json]
            [atproto.lexicon :as lexicon]
            [atproto.service-auth :as service-auth]
            [atproto.xrpc.rate-limit :as rate-limit]))

#?(:clj (set! *warn-on-reflection* true))

(defn init
  "Initialize the XRPC server.

  config:
  :lexicon            (required) the Lexicon to serve.
  :validate-response? validate handler responses against the lexicon.
  :auth               map of nsid (string) -> auth verifier fn, plus an
                      optional :default entry applied to routes without a
                      specific entry. Routes absent from the map (and no
                      :default) are unauthenticated. A verifier is async,
                      callback style: (fn [ctx cb]) where ctx is
                      {:nsid ... :params ... :request ... :app-ctx ...}
                      and cb receives {:credentials ... & anything} on
                      success or {:error ... :message ... :status 401/403}
                      on failure.
  :rate-limit         {:global [limiter ...]          ;; RateLimiter instances
                       :routes {nsid [limiter ...]}}  ;; per-route"
  [{:keys [lexicon validate-response? auth rate-limit] :as config}]
  (lexicon/register-specs! lexicon)
  config)

;; -----------------------------------------------------------------------------
;; Error helpers
;; -----------------------------------------------------------------------------
;; Status table per the reference ResponseType enum (packages/xrpc/src/types.ts).
;; Handlers may also return {:error "CustomName" :status n :message ...} to
;; surface lexicon-defined error names; an :error without :status maps to a
;; generic 500.

(defn invalid-request
  [message]
  {:status 400
   :error "InvalidRequest"
   :message message})

(defn auth-required
  ([] (auth-required "Authentication Required"))
  ([message]
   {:status 401
    :error "AuthenticationRequired"
    :message message}))

(defn forbidden
  ([] (forbidden "Forbidden"))
  ([message]
   {:status 403
    :error "Forbidden"
    :message message}))

(defn xrpc-not-supported
  []
  {:status 404
   :error "XRPCNotSupported"
   :message "XRPC Not Supported"})

(defn not-acceptable
  ([] (not-acceptable "Not Acceptable"))
  ([message]
   {:status 406
    :error "NotAcceptable"
    :message message}))

(defn payload-too-large
  ([] (payload-too-large "Payload Too Large"))
  ([message]
   {:status 413
    :error "PayloadTooLarge"
    :message message}))

(defn unsupported-media-type
  ([] (unsupported-media-type "Unsupported Media Type"))
  ([message]
   {:status 415
    :error "UnsupportedMediaType"
    :message message}))

(defn rate-limit-exceeded
  ([] (rate-limit-exceeded nil))
  ([ratelimit-status]
   (cond-> {:status 429
            :error "RateLimitExceeded"
            :message "Rate Limit Exceeded"}
     ratelimit-status (assoc :ratelimit-status ratelimit-status))))

(defn invalid-response
  [message]
  {:status 500
   :error "InvalidResponse"
   :message message})

(defn internal-server-error
  []
  {:status 500
   :error "InternalServerError"
   :message "Internal Server Error"})

(defn method-not-implemented
  []
  {:status 501
   :error "MethodNotImplemented"
   :message "Method Not Implemented"})

(defn upstream-failure
  []
  {:status 502
   :error "UpstreamFailure"
   :message "Upstream Failure"})

(defn not-enough-resources
  []
  {:status 503
   :error "NotEnoughResources"
   :message "Not Enough Resources"})

(defn upstream-timeout
  []
  {:status 504
   :error "UpstreamTimeout"
   :message "Upstream Timeout"})

(def ^:private generic-5xx-messages
  {500 "Internal Server Error"
   501 "Method Not Implemented"
   502 "Upstream Failure"
   503 "Not Enough Resources"
   504 "Upstream Timeout"})

;; -----------------------------------------------------------------------------
;; HTTP request parsing
;; -----------------------------------------------------------------------------

(defn decode-query-param
  "Decode a query string parameter based on the type definition."
  [type-def val]
  (case (:type type-def)
    "boolean" (case val
                "true"  true
                "false" false
                val)
    "integer" (if (re-matches #"^-?[0-9]+$" val)
                (edn/read-string val)
                val)
    "string"  val
    "unknown" val
    "array"   (mapv #(decode-query-param (:items type-def) %)
                    (if (coll? val) val [val]))))

(defn- query-params->xrpc-params
  "Parse the HTTP query parameters according to the Lexicon definition."
  [{:keys [properties]} query-params]
  (reduce (fn [query-params [p-name type-def]]
            (cond
              (contains? query-params p-name)
              (assoc query-params p-name (decode-query-param type-def (query-params p-name)))

              (:default type-def)
              (assoc query-params p-name (:default type-def))

              :else
              query-params))
          (or query-params {})
          properties))

(defn url-nsid
  "The syntactically valid NSID from an /xrpc/<nsid> URL, or nil for a
  malformed XRPC path."
  [url]
  (when-let [path (:path (http/parse-url url))]
    (when (str/starts-with? path "/xrpc/")
      (let [nsid (subs path (count "/xrpc/"))]
        (when (s/valid? ::lexicon/nsid nsid)
          nsid)))))

(defn http-request->xrpc-request
  "Parse and validate the HTTP request envelope against the lexicon.

  Returns the xrpc-request, or an error map: malformed /xrpc/ path -> 400,
  valid-but-unknown NSID -> 501 (reference catchall), wrong HTTP verb -> 400."
  [lexicon {:keys [method url query-params headers body]}]
  (let [nsid (url-nsid url)
        type-def (when nsid (lexicon/type-def lexicon nsid))]
    (cond
      (nil? nsid)
      (invalid-request "Invalid XRPC path.")

      (nil? type-def)
      (method-not-implemented)

      :else
      (let [{:keys [type parameters input]} type-def
            expected-method (case type
                              "procedure"    :post
                              "query"        :get
                              "subscription" :get)]
        (cond
          (not= expected-method method)
          (invalid-request "Incorrect HTTP method")

          (and (some? body) (not (:content-type headers)))
          (invalid-request "Missing encoding")

          :else
          (cond-> {:nsid nsid}
            (some? parameters)
            (assoc :params (query-params->xrpc-params parameters query-params))

            (some? input)
            (assoc :encoding (:content-type headers)
                   :body body)))))))

;; -----------------------------------------------------------------------------
;; Handlers
;; -----------------------------------------------------------------------------

(defmulti handle
  "Handle the XRPC request.

  Takes a request map with:
  :nsid      The NSID of the procedure or query (string)
  :params    The query parameters as a map
  :body      The input for procedures. `::atproto/data` or `bytes`
  :encoding  The encoding of the body (`application/json` for `::atproto/data`)
  :auth      The route's auth verifier success map (when configured),
             e.g. {:credentials ...}

  The function must return a map with:
  :body      The response body: `::atproto/data` or `bytes`.
  :encoding  The encoding of the body (`application/json` for `::atproto/data`)
  or an error map (:error/:message and optional :status)."
  (fn [app-ctx request] (:nsid request)))

(defmethod handle :default [_ _] (method-not-implemented))

(defmulti handle-subscription
  "Handle a subscription (websocket) request for an NSID.

  Takes [app-ctx request] where request has :nsid, :params, :auth, and
  (once the transport is connected) :close-ch — a core.async channel that
  is closed when the client disconnects, so producers can stop.

  Must return a map:
    {:messages ch}    core.async channel of outgoing messages. Each item
                      is either atproto data (maps; :$type is lifted into
                      the frame header) or {:frame/error \"Name\"
                      :frame/message \"...\"} to emit an error frame and
                      close with ws code 1008. Closing the channel closes
                      the socket normally (1000).
  or {:error ...} to reject the subscription."
  (fn [app-ctx request] (:nsid request)))

(defmethod handle-subscription :default [_ _] (method-not-implemented))

;; -----------------------------------------------------------------------------
;; Auth
;; -----------------------------------------------------------------------------

(defn- auth-verifier-for
  [auth nsid]
  (when auth
    (or (get auth nsid)
        (:default auth))))

(defn- with-401
  [error-map]
  (cond-> error-map
    (not (:status error-map)) (assoc :status 401)))

(defn- run-auth
  "Run the route's auth verifier, if any. cb receives nil (no verifier),
  the verifier's success map, or an error map (401 default status)."
  [verifier verifier-ctx cb]
  (if (not verifier)
    (cb nil)
    (try
      (verifier verifier-ctx
                (fn [{:keys [error] :as result}]
                  (cb (cond-> result error with-401))))
      (catch #?(:clj Throwable :cljs :default) t
        (cast/alert {:message "Error in auth verifier" :ex t})
        (cb (auth-required))))))

(defn service-auth-verifier
  "Ready-made auth verifier for inter-service JWTs.

  config:
  :own-did          this service's DID (audience check; nil to skip).
  :allowed-issuers  optional issuer allow-list.
  :get-signing-key  optional override; defaults to
                    (service-auth/did-signing-key-resolver config).

  Extracts the Bearer token ({:error \"MissingJwt\" :status 401} when
  absent), verifies it with :lxm bound to the route nsid, and yields
  {:credentials {:type :service :did <iss-did> :iss <full-iss>
                 :payload <claims>}}."
  [{:keys [own-did get-signing-key] :as config}]
  (let [get-signing-key (or get-signing-key
                            (service-auth/did-signing-key-resolver config))]
    (fn [{:keys [nsid request]} cb]
      (let [authorization (get-in request [:headers :authorization])]
        (if (not (and (string? authorization)
                      (str/starts-with? authorization "Bearer ")))
          (cb {:error "MissingJwt" :message "missing jwt" :status 401})
          (service-auth/verify-jwt
           (subs authorization (count "Bearer "))
           {:aud own-did
            :lxm nsid
            :get-signing-key get-signing-key}
           :callback
           (fn [{:keys [error iss] :as resp}]
             (cb (if error
                   resp
                   {:credentials {:type :service
                                  :did (first (str/split iss #"#" 2))
                                  :iss iss
                                  :payload resp}})))))))))

;; -----------------------------------------------------------------------------
;; Request validation (params before auth, input after)
;; -----------------------------------------------------------------------------

(defn- request-spec
  [nsid]
  (binding [lexicon/*schema-validate* true]
    (lexicon/request-spec-key nsid)))

(defn- input-problem?
  "True when this spec problem concerns the request input (:body/:encoding)
  rather than its params; input problems are reported only after auth."
  [{:keys [path in pred]}]
  (let [k (or (first in) (first path))]
    (or (#{:body :encoding} k)
        ;; a missing :body/:encoding key: pred is (fn [%] (contains? % :body))
        (and (nil? k)
             (coll? pred)
             (boolean (some #{:body :encoding}
                            (filter keyword? (tree-seq coll? seq pred))))))))

;; -----------------------------------------------------------------------------
;; Rate limiting
;; -----------------------------------------------------------------------------

(defn- route-limiters
  [{:keys [global routes]} nsid]
  (concat global (get routes nsid)))

(defn- consume-limiters
  "Consume limiters in order; cb receives the tightest status map (or nil),
  or the first {:error ...}."
  [limiters limiter-ctx cb]
  (letfn [(step [remaining tightest]
            (if (empty? remaining)
              (cb tightest)
              (rate-limit/consume
               (first remaining) limiter-ctx
               (fn [result]
                 (cond
                   (:error result) (cb result)
                   (nil? result)   (step (rest remaining) tightest)
                   :else (step (rest remaining)
                               (if (or (nil? tightest)
                                       (< (:remaining-points result)
                                          (:remaining-points tightest)))
                                 result
                                 tightest)))))))]
    (step (seq limiters) nil)))

(defn- ratelimit-headers
  "RateLimit-* response headers for a limiter status map (reference
  rate-limiter-http.ts setResHeaders)."
  [{:keys [limit duration remaining-points ms-before-next] :as status}]
  (when status
    {:ratelimit-limit (str limit)
     :ratelimit-remaining (str remaining-points)
     :ratelimit-reset (str (+ (runtime-crypto/now)
                              (quot (or ms-before-next 0) 1000)))
     :ratelimit-policy (str limit ";w=" duration)}))

;; -----------------------------------------------------------------------------
;; Pipeline
;; -----------------------------------------------------------------------------

(defn- run-handle
  "Invoke the handle multimethod, mapping thrown exceptions to a 500."
  [app-ctx xrpc-request]
  (try
    (handle app-ctx xrpc-request)
    (catch #?(:clj Throwable :cljs :default) t
      (cast/alert {:message "Error in XRPC handler" :ex t})
      (internal-server-error))))

(defn- validate-response
  [{:keys [validate-response?]} nsid xrpc-response]
  (let [spec-key (binding [lexicon/*schema-validate* (boolean validate-response?)]
                   (lexicon/response-spec-key nsid))]
    ;; responses are validated leniently; request validation stays strict
    (binding [lexicon/*strict* false]
      (if (s/valid? spec-key xrpc-response)
        xrpc-response
        (invalid-response (s/explain-str spec-key xrpc-response))))))

(defn- process-request
  "Run the pipeline for a parsed xrpc-request; respond receives the
  xrpc-response (success or error map, possibly with :ratelimit-status)."
  [{:keys [auth rate-limit] :as server} http-request xrpc-request respond]
  (let [{:keys [nsid]} xrpc-request
        spec (request-spec nsid)
        problems (::s/problems (s/explain-data spec xrpc-request))
        verifier-ctx {:nsid nsid
                      :params (:params xrpc-request)
                      :request http-request
                      :app-ctx (:app-ctx http-request)}]
    (if (seq (remove input-problem? problems))
      (respond (invalid-request (s/explain-str spec xrpc-request)))
      (run-auth
       (auth-verifier-for auth nsid) verifier-ctx
       (fn [{:keys [error] :as auth-result}]
         (cond
           error
           (respond auth-result)

           (seq problems)
           (respond (invalid-request (s/explain-str spec xrpc-request)))

           :else
           (consume-limiters
            (route-limiters rate-limit nsid)
            (cond-> verifier-ctx auth-result (assoc :auth auth-result))
            (fn [{:keys [error] :as limit-result}]
              (if error
                (respond limit-result)
                (let [xrpc-request (cond-> xrpc-request
                                     auth-result (assoc :auth auth-result))
                      xrpc-response (run-handle (:app-ctx http-request) xrpc-request)]
                  (respond
                   (cond-> (if (:error xrpc-response)
                             xrpc-response
                             (validate-response server nsid xrpc-response))
                     limit-result (assoc :ratelimit-status limit-result)))))))))))))

(defn interceptor
  "Handle HTTP requests and delegate execution to a `handle` method after
  parsing/validation. Auth and rate-limit hooks are async; the enter fn
  continues the chain via i/continue from their callbacks."
  [{:keys [lexicon] :as server}]
  {::i/name ::interceptor
   ::i/enter (fn [{:keys [::i/request] :as ctx}]
               (let [respond #(i/continue (assoc ctx ::i/response %))
                     xrpc-request (http-request->xrpc-request lexicon request)]
                 (if (:error xrpc-request)
                   (respond xrpc-request)
                   (process-request server request xrpc-request respond))
                 nil))
   ::i/leave (fn [ctx]
               (update ctx
                       ::i/response
                       (fn [{:keys [error status message encoding body ratelimit-status]
                             :as xrpc-response}]
                         (let [rl-headers (ratelimit-headers ratelimit-status)]
                           (if error
                             (do
                               (cast/alert (dissoc xrpc-response :ratelimit-status))
                               (let [{:keys [status] :as sanitized}
                                     (cond
                                       (not status) (internal-server-error)
                                       ;; 5xx messages never leak internals
                                       (<= 500 status)
                                       (assoc xrpc-response
                                              :message (get generic-5xx-messages
                                                            status
                                                            "Internal Server Error"))
                                       :else xrpc-response)]
                                 {:status status
                                  :headers (merge {:content-type "application/json"}
                                                  rl-headers)
                                  :body (cond-> {:error (:error sanitized)}
                                          (some? (:message sanitized))
                                          (assoc :message (:message sanitized)))}))
                             {:status 200
                              :headers (merge {:content-type encoding} rl-headers)
                              :body body})))))})

(defn subscription-request
  "Validate an HTTP (upgrade) request against the lexicon and run the
  route's auth verifier. Async; yields the xrpc-request for
  handle-subscription, or {:error ...}. Shared by transport adapters."
  [{:keys [lexicon auth] :as server} http-request & {:as opts}]
  (let [[cb val] (i/platform-async (select-keys opts [:channel :callback :promise]))
        nsid (url-nsid (:url http-request))
        type-def (when nsid (lexicon/type-def lexicon nsid))]
    (cond
      (nil? nsid)
      (cb (invalid-request "Invalid XRPC path."))

      (nil? type-def)
      (cb (method-not-implemented))

      (not= "subscription" (:type type-def))
      (cb (invalid-request "Not a subscription endpoint."))

      :else
      (let [{:keys [parameters]} type-def
            xrpc-request (cond-> {:nsid nsid}
                           (some? parameters)
                           (assoc :params (query-params->xrpc-params
                                           parameters
                                           (:query-params http-request))))
            spec (request-spec nsid)]
        (if (not (s/valid? spec xrpc-request))
          (cb (invalid-request (s/explain-str spec xrpc-request)))
          (run-auth (auth-verifier-for auth nsid)
                    {:nsid nsid
                     :params (:params xrpc-request)
                     :request http-request
                     :app-ctx (:app-ctx http-request)}
                    (fn [{:keys [error] :as auth-result}]
                      (cb (cond
                            error auth-result
                            auth-result (assoc xrpc-request :auth auth-result)
                            :else xrpc-request)))))))
    val))

(defn handle-http-request
  "Handle an HTTP request and delegate execution to the API implementation.

  The `http-request` accepts an extra key `:app-ctx` that will be passed to the handle method."
  [server http-request & {:as opts}]
  (i/execute {::i/request http-request
              ::i/queue [json/server-interceptor
                         atproto-json/server-interceptor
                         (interceptor server)]}
             opts))
