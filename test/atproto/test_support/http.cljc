(ns atproto.test-support.http
  "Scripted/routed fakes for atproto.runtime.http/handle-request.

  Use with-redefs to replace atproto.runtime.http/handle-request with the
  :handler of one of these fakes; this exercises the real interceptor chains
  (json, atproto-json, dpop, auth) against canned HTTP responses."
  (:require [clojure.string :as str]
            [atproto.runtime.json :as json]))

(defn json-response
  "A canned HTTP response with a JSON body."
  ([body] (json-response 200 body))
  ([status body] (json-response status nil body))
  ([status headers body]
   {:status status
    :headers (merge {:content-type "application/json"} headers)
    :body (json/write-str body)}))

(defn scripted
  "A fake handler that replays `responses` in order.

  Returns {:handler f :requests requests-atom}: f is a drop-in replacement
  for atproto.runtime.http/handle-request; every request received is appended
  to the :requests atom. Each response may be a map or a
  (fn [request] response). Requests beyond the script receive an
  {:error \"NoScriptedResponse\"} map."
  [responses]
  (let [queue (atom (seq responses))
        requests (atom [])]
    {:requests requests
     :handler (fn [request cb]
                (swap! requests conj request)
                (let [[prev _] (swap-vals! queue next)
                      response (first prev)]
                  (cond
                    (nil? response) (cb {:error "NoScriptedResponse"
                                         :message (str "No scripted response for "
                                                       (some-> (:method request) name)
                                                       " " (:url request))})
                    (fn? response)  (cb (response request))
                    :else           (cb response))))}))

(defn routed
  "A fake handler that dispatches on the request.

  `routes` is an ordered seq of [matcher response] pairs; a matcher is a
  substring of the request URL or a (fn [request] boolean); a response is a
  map or a (fn [request] response). The first matching route wins; unmatched
  requests receive an {:error \"NoRoute\"} map."
  [routes]
  (let [requests (atom [])]
    {:requests requests
     :handler (fn [{:keys [url] :as request} cb]
                (swap! requests conj request)
                (if-let [[_ response] (first (filter (fn [[matcher _]]
                                                       (if (string? matcher)
                                                         (str/includes? (or url "") matcher)
                                                         (matcher request)))
                                                     routes))]
                  (cb (if (fn? response) (response request) response))
                  (cb {:error "NoRoute"
                       :message (str "No route for " url)})))}))
