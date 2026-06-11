(ns atproto.xrpc.error-test
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            [atproto.xrpc.error :as xrpc-error]))

(deftest status->error-name-test
  (are [status name] (= name (xrpc-error/derived-error-name status))
    400 "InvalidRequest"
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
    504 "UpstreamTimeout"
    ;; fallback buckets
    418 "InvalidRequest"
    422 "InvalidRequest"
    599 "UpstreamFailure"
    522 "UpstreamFailure"
    100 "XRPCNotSupported"
    301 "XRPCNotSupported"))

(deftest http-error-test
  ;; a JSON error payload always wins over the derived name
  (let [response {:status 400
                  :headers {:content-type "application/json"}
                  :body {:error "InvalidSwap" :message "Commit was at bafy..."}}
        err (xrpc-error/http-error response)]
    (is (= "InvalidSwap" (:error err)))
    (is (= "Commit was at bafy..." (:message err)))
    (is (= 400 (:status err)))
    (is (= {:content-type "application/json"} (:headers err)))
    (is (false? (:retryable? err)))
    (is (= response (:http-response err))))
  ;; server error names are preserved verbatim
  (is (= "ExpiredToken"
         (:error (xrpc-error/http-error {:status 400 :body {:error "ExpiredToken"}}))))
  ;; non-payload bodies fall back to the derived name with a generic message
  (let [err (xrpc-error/http-error {:status 502 :body "<html>Bad Gateway</html>"})]
    (is (= "UpstreamFailure" (:error err)))
    (is (string? (:message err)))
    (is (true? (:retryable? err))))
  (let [err (xrpc-error/http-error {:status 404 :body {:not-an-error "x"}})]
    (is (= "XRPCNotSupported" (:error err))))
  ;; a map body whose :error is not a string is not an error payload
  (is (= "InvalidRequest"
         (:error (xrpc-error/http-error {:status 400 :body {:error 42}})))))

(deftest retryable-statuses-test
  (doseq [status [408 425 429 500 502 503 504 522 524]]
    (is (true? (:retryable? (xrpc-error/http-error {:status status})))
        (str "expected retryable: " status)))
  (doseq [status [400 401 403 404 406 413 415 501 100 301 418]]
    (is (false? (:retryable? (xrpc-error/http-error {:status status})))
        (str "expected not retryable: " status))))

(deftest network-error-test
  (let [err (xrpc-error/network-error {:error "HTTPClientError" :message "connection refused"})]
    (is (= "HTTPClientError" (:error err)))
    (is (= "connection refused" (:message err)))
    (is (true? (:retryable? err)))
    (is (nil? (:status err))))
  (is (= "Timeout" (:error (xrpc-error/network-error {:error "Timeout"}))))
  (is (= "HTTPClientError" (:error (xrpc-error/network-error {})))))

(deftest invalid-request-test
  (let [err (xrpc-error/invalid-request {:problems []})]
    (is (= "InvalidRequest" (:error err)))
    (is (string? (:message err)))
    (is (false? (:retryable? err)))
    (is (= {:problems []} (:explain-data err)))))

(deftest retryable?-test
  (is (true? (xrpc-error/retryable? {:error "Timeout" :retryable? true})))
  (is (false? (xrpc-error/retryable? {:error "InvalidRequest" :retryable? false})))
  (is (false? (xrpc-error/retryable? {:error "InvalidRequest"})))
  (is (false? (xrpc-error/retryable? nil))))
