(ns atproto.xrpc.server.ring
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :refer [keywordize-keys stringify-keys]]
            [atproto.runtime.http :as http]
            [atproto.runtime.cast :as cast]
            [atproto.xrpc.server :as xrpc-server])
  (:import [java.io ByteArrayOutputStream]))

(set! *warn-on-reflection* true)

(defn input-stream->bytes ^bytes [is]
  (let [os (ByteArrayOutputStream.)]
    (io/copy is os)
    (let [ba (.toByteArray os)]
      (when (not (zero? (count ba)))
        ba))))

(defn ring-request->http-request
  [{:keys [request-method scheme server-name server-port uri query-string headers body app-ctx]}]
  (let [query-params (when (not-empty query-string)
                       (http/query-string->query-params query-string))]
    (cond-> {:method request-method
             :url (str (name scheme) "://"
                       server-name
                       (when server-port (str ":" server-port))
                       uri)
             :headers (keywordize-keys headers)
             :body (input-stream->bytes body)}
      app-ctx (assoc :app-ctx app-ctx)
      query-params (assoc :query-params query-params))))

(defn http-response->ring-response
  [http-response]
  (update http-response :headers stringify-keys))

(defn handler
  [server]
  (fn [ring-request]
    (when (str/starts-with? (:uri ring-request) "/xrpc/")
      (http-response->ring-response
       @(xrpc-server/handle-http-request server
                                         (ring-request->http-request ring-request))))))
