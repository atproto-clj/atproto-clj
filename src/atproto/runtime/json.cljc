(ns atproto.runtime.json
  "Cross platform JSON parser/serializer."
  (:require [clojure.string :as str]
            [atproto.runtime.interceptor :as i]
            #?(:clj [charred.api :as json])))

#?(:clj (set! *warn-on-reflection* true))

(defn- json-content-type?
  "Test if a request or response should be interpreted as json"
  [req-or-resp]
  (when-let [ct (:content-type (:headers req-or-resp))]
    (or (str/starts-with? ct "application/json")
        (str/starts-with? ct "application/did+ld+json"))))

(def read-str
  #?(:clj #(json/read-json % :key-fn keyword)))

(def write-str
  #?(:clj #(json/write-json-str %)))

(def client-interceptor
  "Interceptor for JSON request and response bodies"
  {::i/name ::interceptor
   ::i/enter (fn [ctx]
               (update ctx
                       ::i/request
                       (fn [{:keys [headers body] :as request}]
                         (if body
                           (let [request (if (not (:content-type (:headers request)))
                                           (assoc-in request  [:headers :content-type] "application/json")
                                           request)]
                             (if (json-content-type? request)
                               (update request :body write-str)
                               request))
                           request))))
   ::i/leave (fn leave-json [{:keys [::i/response] :as ctx}]
               (if (json-content-type? response)
                 (update-in ctx [::i/response :body] read-str)
                 ctx))})

(def server-interceptor
  "Parse JSON from an HTTP request body and serialize an HTTP response body to JSON."
  {::i/name ::server-interceptor
   ::i/enter (fn [ctx]
               (update ctx
                       ::i/request
                       (fn [{:keys [body] :as request}]
                         (if (and body (json-content-type? request))
                           (update request :body read-str)
                           request))))
   ::i/leave (fn [ctx]
               (update ctx
                       ::i/response
                       (fn [{:keys [body] :as response}]
                         (if (and body (json-content-type? response))
                           (update response :body write-str)
                           response))))})
