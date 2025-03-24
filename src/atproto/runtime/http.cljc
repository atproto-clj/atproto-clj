(ns atproto.runtime.http
  "Cross-platform HTTP client."
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [clojure.walk :refer [stringify-keys]]
            [atproto.runtime.interceptor :as i]
            #?@(:clj [[org.httpkit.client :as http]]))
  #?(:clj (:import [java.net URI URL MalformedURLException URLEncoder URLDecoder])))

#?(:clj (set! *warn-on-reflection* true))

(defn success?
  [code]
  (and (number? code)
       (<= 200 code 299)))

(defn client-error?
  [code]
  (and (number? code)
       (<= 400 code 499)))

(defn redirect?
  [code]
  (and (number? code)
       (<= 300 code 399)))

(defn error-map
  "Given an unsuccessful HTTP response, convert to an error map"
  [resp]
  {:error (str "HTTP " (:status resp))
   :http-response resp})

(defn url-encode
  "Encode the string to be included in a URL."
  [s]
  #?(:clj (URLEncoder/encode s)))

(defn url-decode
  "Encode the string to be included in a URL."
  [s]
  #?(:clj (URLDecoder/decode s)))

(defn parse-uri
  "Return a map with: [scheme]://[authority][path]?[query]#[fragment].

  Return nil if the string cannot be parsed."
  [s]
  #?(:clj
     (try
       (when (not (str/blank? s))
         (let [uri (URI. s)]
           (cond-> {:scheme (.getScheme uri)
                    :authority (.getAuthority uri)}
             (not (str/blank? (.getPath uri)))     (assoc :path (.getPath uri))
             (not (str/blank? (.getQuery uri)))    (assoc :query (.getQuery uri))
             (not (str/blank? (.getFragment uri))) (assoc :fragment (.getFragment uri)))))
       (catch Exception _))))

(defn parse-url
  "Return a map with: [protocol]://[host]:[port][path]?[query-params]#[fragment]

  All values are strings except for query-params which is a
  map of keywords to URL-decoded values.

  Query string parameters with the same name will be collected
  under the same key.

  Return nil if the string cannot be parsed."
  [s]
  #?(:clj
     (try
       (let [url (URL. s)
             params (when-let [qs (.getQuery url)]
                      (some->> (str/split qs #"&")
                               (map #(str/split % #"="))
                               (reduce (fn [m [k v]]
                                         (cond-> m
                                           (not (str/blank? v))
                                           (update (keyword k)
                                                   #(let [dv (url-decode v)]
                                                      (cond
                                                        (coll? %) (conj % dv)
                                                        (some? %) [% dv]
                                                        :else     dv)))))
                                       {})))
             fragment (.getRef url)]
         (cond-> {:protocol (.getProtocol url)
                  :host (.getHost url)}
           (not (= -1 (.getPort url)))       (assoc :port (.getPort url))
           (not (str/blank? (.getPath url))) (assoc :path (.getPath url))
           (seq params)                      (assoc :query-params params)
           fragment                          (assoc :fragment fragment)))
       (catch Exception e
         (tap> e)))))

(defn serialize-url
  "Take a URL map and return the URL string.

  The query-params values will be URL-encoded."
  [{:keys [protocol host port path query-params fragment]}]
  (str protocol "://"
       host
       (when port (str ":" port))
       path
       (when (not (empty? query-params))
         (->> query-params
              (map (fn [[k v]] (str (name k) "=" (url-encode v))))
              (str/join "&")
              (str "?")))
       (when fragment (str "#" fragment))))

(s/def ::url
  parse-url)

(def interceptor
  "Return implementation-specific HTTP interceptor."
  #?(:clj
     {::i/name ::http
      ::i/enter (fn [ctx]
                  (let [ctx (update-in ctx [::i/request :headers] stringify-keys)]
                    (http/request (::i/request ctx)
                                  (fn [{:keys [^Throwable error] :as resp}]
                                    (i/continue
                                     (assoc ctx ::i/response (if error
                                                               {:error (.getName (.getClass error))
                                                                :message (.getMessage error)
                                                                :exception error}
                                                               (dissoc resp :opts))))))
                    nil))}))
