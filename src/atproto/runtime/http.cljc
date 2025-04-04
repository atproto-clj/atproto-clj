(ns atproto.runtime.http
  "Cross-platform HTTP client."
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [clojure.walk :refer [stringify-keys]]
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.http.request :as-alias request]
            [atproto.runtime.http.response :as-alias response]
            [atproto.runtime.http.url :as-alias url]
            [atproto.runtime.cast :as cast]
            #?@(:clj [[org.httpkit.client :as http]]))
  #?(:clj (:import [java.net URI URL MalformedURLException URLEncoder URLDecoder])
     :cljs (:import [goog.net EventType XhrIo]
                    [goog Uri])))

#?(:clj (set! *warn-on-reflection* true))

(defn success?
  [code]
  (and (number? code)
       (<= 200 code 299)))

(defn redirect?
  [code]
  (and (number? code)
       (<= 300 code 399)))

(defn client-error?
  [code]
  (and (number? code)
       (<= 400 code 499)))

(defn error-map
  "Given an unsuccessful HTTP response, convert to an error map"
  [resp]
  {:error (str "HTTP_" (:status resp))
   :http-response resp})

(defn url-encode
  "Encode the string to be included in a URL."
  [s]
  #?(:clj (URLEncoder/encode s)))

(defn url-decode
  "Encode the string to be included in a URL."
  [s]
  #?(:clj (URLDecoder/decode s)))

(defn query-string->query-params
  [qs]
  (some->> (str/split qs #"&")
           (map #(str/split % #"="))
           (reduce (fn [m [k v]]
                     (if (str/blank? v)
                       m
                       (update m
                               (keyword k)
                               #(let [dv (url-decode v)]
                                  (cond
                                    (coll? %) (conj % dv)
                                    (some? %) [% dv]
                                    :else     dv)))))
                   {})))

(defn query-params->query-string
  [qp]
  (some->> qp
           (mapcat (fn [[k v]]
                     (if (coll? v)
                       (map #(str (name k) "=" (url-encode %)) v)
                       [(str (name k) "=" (url-encode v))])))
           (str/join "&" )))

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
                      (query-string->query-params qs))
             fragment (.getRef url)]
         (cond-> {:protocol (keyword (str/lower-case (.getProtocol url)))
                  :host (.getHost url)}
           (not (= -1 (.getPort url)))       (assoc :port (.getPort url))
           (not (str/blank? (.getPath url))) (assoc :path (.getPath url))
           (seq params)                      (assoc :query-params params)
           fragment                          (assoc :fragment fragment)))
       (catch Exception _))))

(s/def ::url
  (s/and string?
         #?(:clj #(try (URL. %) (catch Exception _)))))

(s/def ::uri
  (s/and string?
         #(not (str/blank? %))
         #?(:clj #(try (URI. %) (catch Exception _)))))

(defn serialize-url
  "Take a URL map and return the URL string.

  The query-params values will be URL-encoded."
  [{:keys [protocol host port path query-params fragment]}]
  (str (name protocol) "://"
       host
       (when port (str ":" port))
       path
       (when (not (empty? query-params))
         (->> query-params
              (map (fn [[k v]] (str (name k) "=" (url-encode v))))
              (str/join "&")
              (str "?")))
       (when fragment (str "#" fragment))))

(defn handle-request
  "runtime-specific handling of the http request"
  [http-request cb]
  #?(:clj
     (http/request (update http-request :headers stringify-keys)
                   (fn [{:keys [^Throwable error] :as http-response}]
                     (let [http-response (dissoc http-response :opts)]
                       (cb (if error
                             {:error "HTTPClientError"
                              :message (.getMessage error)
                              :ex error}
                             http-response)))))

     :cljs
     (let [{:keys [method url query-params headers body]} http-request]
       (let [uri (doto (Uri. url)
                   (.setQuery (query-params->query-string query-params)))
             xhr (doto (XhrIo.)
                   (.setTimeoutInterval 0))]
         (.listen xhr
                  EventType/COMPLETE
                  (fn [evt]
                    (let [target (.-target evt)
                          error (not-empty (.getLastError target))
                          http-response {:status (.getStatus target)
                                         :headers (->> (.getAllResponseHeaders target)
                                                       (str/split-lines)
                                                       (reduce (fn [headers line]
                                                                 (let [[k v] (str/split line #":")]
                                                                   (if (not (str/blank? k))
                                                                     (assoc headers
                                                                            (keyword
                                                                             (str/lower-case
                                                                              (str/trim k)))
                                                                            (str/trim v))
                                                                     headers)))
                                                               {}))
                                         :body (.getResponse target)}]
                      (cb http-response))))
         (.send xhr
                uri
                (name method)
                body
                (clj->js (stringify-keys headers)))))))

(def client-interceptor
  {::i/name ::client
   ::i/enter (fn [{:keys [::i/request] :as ctx}]
               (handle-request request
                               (fn [response]
                                 (i/continue (assoc ctx ::i/response response)))))})
