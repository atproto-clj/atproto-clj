(ns atproto.runtime.json
  "Cross platform JSON parser/serializer."
  (:require [clojure.string :as str]
            [clojure.walk :refer [keywordize-keys]]
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.bytes :as bytes]
            [atproto.runtime.cast :as cast]
            #?(:clj [charred.api :as json])))

#?(:clj (set! *warn-on-reflection* true))

(defn- json-content-type?
  "Test if a request or response should be interpreted as json"
  [req-or-resp]
  (when-let [ct (:content-type (:headers req-or-resp))]
    (or (str/starts-with? ct "application/json")
        (str/starts-with? ct "application/did+ld+json"))))

(def read-str
  #?(:clj #(json/read-json % :key-fn keyword)
     :cljs #(keywordize-keys (js->clj (.parse js/JSON %)))))

(def write-str
  #?(:clj #(json/write-json-str %)
     :cljs #(.stringify js/JSON (clj->js %))))

(def client-interceptor
  "Interceptor for JSON request and response bodies"
  {::i/name ::client-interceptor
   ::i/enter (fn [ctx]
               (update ctx
                       ::i/request
                       (fn [{:keys [body] :as request}]
                         (if (and body (json-content-type? request))
                           (update request :body write-str)
                           request))))
   ::i/leave (fn [ctx]
               (update ctx
                       ::i/response
                       (fn [{:keys [body] :as response}]
                         (if (and body (json-content-type? response))
                           (update response :body read-str)
                           response))))})

(def server-interceptor
  "Parse JSON from an HTTP request body and serialize an HTTP response body to JSON."
  {::i/name ::server-interceptor
   ::i/enter (fn [ctx]
               (update ctx
                       ::i/request
                       (fn [{:keys [body] :as request}]
                         (if (and body (json-content-type? request))
                           (update request :body #(read-str (bytes/->utf8 %)))
                           request))))
   ::i/leave (fn [ctx]
               (update ctx
                       ::i/response
                       (fn [{:keys [body] :as response}]
                         (if (and body (json-content-type? response))
                           (update response :body write-str)
                           response))))})
