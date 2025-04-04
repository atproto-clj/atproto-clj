(ns atproto.xrpc.server
  "Functions to implement atproto HTTP services."
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [clojure.walk :refer [stringify-keys]]
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.http :as http]
            [atproto.runtime.json :as json]
            [atproto.runtime.cast :as cast]
            [atproto.data.json :as atproto-json]
            [atproto.lexicon :as lexicon]))

(defn init
  [{:keys [lexicon validate-response?] :as config}]
  (lexicon/register-specs! lexicon)
  config)

(defn invalid-request
  [message]
  {:status 400
   :error "InvalidRequest"
   :message message})

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

(defn auth-required
  []
  {:status 401
   :error "AuthenticationRequired"
   :message "Authentication Required"})

(defn decode-query-param
  "Decode a query string parameter based on the type definition."
  [type-def val]
  (case (:type type-def)
    "boolean" (case val
                "true"  true
                "false" false
                val)
    "integer" (if (re-matches #"^-?[0-9]+$" val)
                (clojure.edn/read-string val)
                val)
    "string"  val
    "unknown" val
    "array"   (mapv #(decode-query-param (:items type-def) %) val)))

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
          query-params
          properties))

(defn http-request->xrpc-request
  "Validate the HTTP request and return the NSID if valid and found in the lexicon."
  [lexicon {:keys [method url query-params headers body]}]
  (let [nsid (subs (:path (http/parse-url url))
                   (count "/xrpc/"))
        type-def (lexicon/type-def lexicon nsid)]
    (if (not type-def)
      (invalid-request "Unknown NSID")
      (let [{:keys [type parameters input]} type-def
            expected-method (case type
                              "procedure"    :post
                              "query"        :get
                              "subscription" :get)]
        (if (not (= expected-method method))
          (invalid-request "Incorrect HTTP method")
          (if (and (some? body) (not (:content-type headers)))
            (invalid-request "Missing encoding")
            (cond-> {:nsid nsid}
              (some? parameters)
              (assoc :params (query-params->xrpc-params parameters
                                                        query-params))

              (some? input)
              (assoc :encoding (:content-type headers)
                     :body body))))))))

(defmulti handle
  "Handle the XRPC request.

  Takes a request map with:
  :nsid      The NSID of the procedure or query (string)
  :params    The query parameters as a map
  :body      The input for procedures. `::atproto/data` or `bytes`
  :encoding  The encoding of the body (`application/json` for `::atproto/data`)

  The function must return a map with:
  :body      The response body: `::atproto/data` or `bytes`.
  :encoding  The encoding of the body (`application/json` for `::atproto/data`)"
  (fn [app-ctx request] (:nsid request)))

(defmethod handle :default [_ _] (method-not-implemented))

(defn interceptor
  "Handle HTTP requests and delegate execution to a `handle` method after parsing/validation."
  [{:keys [lexicon validate-response?]}]
  {::i/name ::interceptor
   ::i/enter (fn [{:keys [::i/request] :as ctx}]
               (assoc ctx
                      ::i/response
                      (let [xrpc-request (http-request->xrpc-request lexicon request)]
                        (if (:error xrpc-request)
                          xrpc-request
                          (let [spec-key (binding [lexicon/*schema-validate* true]
                                           (lexicon/request-spec-key (:nsid xrpc-request)))]
                            (if (not (s/valid? spec-key xrpc-request))
                              (invalid-request (s/explain-str spec-key xrpc-request))
                              (let [xrpc-response (handle (:app-ctx request) xrpc-request)]
                                (if (:error xrpc-response)
                                  xrpc-response
                                  (let [spec-key (binding [lexicon/*schema-validate* validate-response?]
                                                   (lexicon/response-spec-key (:nsid xrpc-request)))]
                                    (if (not (s/valid? spec-key xrpc-response))
                                      (invalid-response (s/explain-str spec-key xrpc-response))
                                      xrpc-response))))))))))
   ::i/leave (fn [ctx]
               (update ctx
                       ::i/response
                       (fn [{:keys [error status message encoding body] :as xrpc-response}]
                         (if error
                           (do
                             (cast/alert xrpc-response)
                             (if (not status)
                               (internal-server-error)
                               {:status status
                                :headers {:content-type "application/json"}
                                :body (cond-> {:error error}
                                        (some? message) (assoc :message message))}))
                           {:status 200
                            :headers {:content-type encoding}
                            :body body}))))})

(defn handle-http-request
  "Handle an HTTP request and delegate execution to the API implementation.

  The `http-request` accepts an extra key `:app-ctx` that will be passed to the handle method."
  [server http-request & {:as opts}]
  (i/execute {::i/request http-request
              ::i/queue [json/server-interceptor
                         atproto-json/server-interceptor
                         (interceptor server)]}))
