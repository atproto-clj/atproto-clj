(ns atproto.xrpc.server
  "Functions to implement atproto HTTP services."
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.http :as http]
            [atproto.runtime.json :as json]
            [atproto.lexicon :as lexicon]))

;; -----------------------------------------------------------------------------
;; Response/Error helpers
;; -----------------------------------------------------------------------------

(defn- invalid-request
  [message]
  {:status 400
   :error "InvalidRequest"
   :message message})

(defn- method-not-allowed
  []
  {:status 405
   :error "InvalidRequest"
   :message "Method Not Allowed"})

(defn- internal-server
  []
  {:status 500
   :error "InternalServerError"
   :message "Internal Server Error"})

(defn- invalid-response
  [message]
  {:status 500
   :error "InvalidResponse"
   :message message})

(defn- method-not-implemented
  [op]
  {:status 501
   :error "MethodNotImplemented"
   :message (str "Method Not Implemented: " op)})

(defn json-output
  [body]
  {:encoding "application/json"
   :body body})

;; -----------------------------------------------------------------------------
;; Request parsing
;; -----------------------------------------------------------------------------

(def xrpc-prefix "/xrpc")

(defn url->nsid
  "Return the NSID in this XRPC URL, if valid."
  [url]
  (when-let [{:keys [path]} (http/parse-url url)]
    (when (str/starts-with? path xrpc-prefix)
      (let [nsid (subs path (inc (count xrpc-prefix)))]
        (when (s/valid? ::lexicon/nsid nsid)
          nsid)))))

(defn validate-http-request
  "Validate the HTTP request and return the NSID if valid and found in the lexicon."
  [lexicon {:keys [method url]}]
  (let [nsid (url->nsid url)]
    (if (not nsid)
      (invalid-request "Invalid URL.")
      (let [type-def (lexicon/type-def lexicon nsid)]
        (if (not type-def)
          (invalid-request "Unknown NSID.")
          (let [{:keys [type parameters input]} type-def
                expected-method (case type
                                  "procedure"    :post
                                  "query"        :get
                                  "subscription" :get)]
            (if (not (= method expected-method))
              (method-not-allowed)
              {:nsid nsid
               :type-def type-def})))))))

(defn- query-params->xrpc-params
  "Parse the HTTP query parameters according to the Lexicon definition."
  [parameters query-params]
  (reduce (fn [query-params [p-name {:keys [type default]}]]
            (cond-> query-params

              ;; set the default value from the schema
              (and default (not (contains? query-params p-name)))
              (assoc p-name default)

              ;; convert the "true" and "false" strings into booleans
              (and (= type "boolean") (contains? query-params p-name))
              (update p-name #(case %
                                "true" true
                                "false" false
                                %))))
          query-params
          parameters))

(defn- http-request->xrpc-request
  [lexicon {:keys [method url query-params headers body] :as http-request}]
  (let [{:keys [error nsid type-def] :as resp} (validate-http-request lexicon http-request)]
    (if error
      resp
      (let [{:keys [parameters input]} type-def
            xrpc-request (cond-> {:op (keyword nsid)}
                           (some? parameters)
                           (assoc :params
                                  (if (seq query-params)
                                    (query-params->xrpc-params parameters
                                                               query-params)
                                    {}))

                           (some? input)
                           (assoc :input
                                  {:encoding (:content-type headers)
                                   :body body}))
            spec-key (lexicon/request-spec-key nsid)]
        (if (not (s/valid? spec-key xrpc-request))
          (invalid-request (s/explain-str spec-key xrpc-request))
          xrpc-request)))))

;; -----------------------------------------------------------------------------
;; Interceptors
;; -----------------------------------------------------------------------------

(defmulti handle
  "Handle the XRPC request: procedure or query.

  The implementation takes an `::xrpc/request` and return an `::xrpc/response`."
  :op)

(defmethod handle :default
  [{:keys [op] :as request}]
  (method-not-implemented op))

(defn interceptor
  "Handle HTTP requests and delegate execution to an `handle` method implementation after parsing/validation."
  [{:keys [lexicon]}]
  {::i/name ::interceptor
   ::i/enter (fn [{:keys [::i/request] :as ctx}]
               (let [{:keys [error] :as xrpc-request} (http-request->xrpc-request lexicon request)]
                 (assoc ctx
                        ::i/response
                        (if error
                          xrpc-request
                          (let [xrpc-response (handle xrpc-request)]
                            (if (not (:validate-response? ctx))
                              xrpc-response
                              (let [spec-key (lexicon/response-spec-key (name (:op xrpc-request)))]
                                (if (s/valid? spec-key xrpc-response)
                                  xrpc-response
                                  (invalid-response (s/explain-str spec-key xrpc-response))))))))))
   ::i/leave (fn [ctx]
               (update ctx
                       ::i/response
                       (fn [xrpc-response]
                         (if (:error xrpc-response)
                           (let [{:keys [error status message]} xrpc-response]
                             {:status (or status 500)
                              :headers {:content-type "application/json"}
                              :body (cond-> {:error error}
                                      (some? message) (assoc :message message))})
                           (let [{:keys [encoding body]} xrpc-response]
                             {:status 200
                              :headers {:content-type encoding}
                              :body body})))))})

(defn handle-request
  [http-request & {:as opts}]
  (i/execute {::i/request http-request
              ::i/queue [json/server-interceptor
                         interceptor]}))
