(ns atproto.xrpc.error
  "Typed error taxonomy for XRPC responses, mirroring @atproto/lex-client errors.

  All XRPC failures are error maps of the shape:

  {:error <string> :message <string>? :status <int>? :headers <map>?
   :retryable? <boolean> :http-response <map>?}

  Server-provided JSON :error names are always preserved verbatim; the
  status-derived name is only used when the response body does not carry a
  valid {\"error\": ...} payload.")

(def status->error-name
  "HTTP status -> canonical XRPC error name (used when the response body
  does not carry a valid {\"error\": ...} payload)."
  {400 "InvalidRequest"
   401 "AuthenticationRequired"
   403 "Forbidden"
   404 "XRPCNotSupported"
   406 "NotAcceptable"
   413 "PayloadTooLarge"
   415 "UnsupportedMediaType"
   429 "RateLimitExceeded"
   500 "InternalServerError"
   501 "MethodNotImplemented"
   502 "UpstreamFailure"
   503 "NotEnoughResources"
   504 "UpstreamTimeout"})

(def retryable-statuses
  "HTTP statuses that indicate a transient error that may succeed on retry."
  #{408 425 429 500 502 503 504 522 524})

(defn derived-error-name
  "The canonical XRPC error name for an HTTP status without a valid error
  payload: the status->error-name entry, else InvalidRequest for unknown 4xx,
  UpstreamFailure for unknown 5xx, and XRPCNotSupported for 1xx/3xx."
  [status]
  (or (status->error-name status)
      (cond
        (and (number? status) (<= 500 status 599)) "UpstreamFailure"
        (and (number? status) (<= 400 status 499)) "InvalidRequest"
        :else "XRPCNotSupported")))

(defn retryable-status?
  [status]
  (boolean (and (not= 401 status)
                (contains? retryable-statuses status))))

(defn- error-payload
  "The response body when it is a valid XRPC error payload, else nil."
  [body]
  (when (and (map? body) (string? (:error body)))
    body))

(defn http-error
  "Build an error map from a non-2xx XRPC HTTP response.

  The body's :error/:message win when the body is a JSON error payload;
  otherwise the error name is derived from the status. The raw response is
  kept under :http-response."
  [{:keys [status headers body] :as http-response}]
  (let [payload (error-payload body)]
    (cond-> {:error (or (:error payload) (derived-error-name status))
             :message (or (:message payload)
                          (str "XRPC request failed with HTTP status " status "."))
             :status status
             :retryable? (retryable-status? status)
             :http-response http-response}
      headers (assoc :headers headers))))

(defn network-error
  "Wrap a runtime transport failure ({:error \"HTTPClientError\"/\"Timeout\"/...})
  as a retryable XRPC error map (no :status)."
  [err]
  (-> err
      (update :error #(or % "HTTPClientError"))
      (assoc :retryable? true)))

(defn invalid-request
  "Error map for a client-side request validation failure (never retryable)."
  [explain-data]
  {:error "InvalidRequest"
   :message "The request is not valid for this lexicon."
   :retryable? false
   :explain-data explain-data})

(defn retryable?
  "True if the error map is marked retryable."
  [error-map]
  (boolean (:retryable? error-map)))
