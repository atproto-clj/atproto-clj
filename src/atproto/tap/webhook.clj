(ns atproto.tap.webhook
  "Ring handler for Tap webhook delivery mode.

  Tap POSTs one wire event per request; a 200 acks it, anything else makes
  Tap retry (at-least-once, like the channel). The equivalent of the
  @atproto/tap README express example (commit b9ef557)."
  (:require [clojure.spec.alpha :as s]
            [atproto.runtime.json :as json]
            [atproto.runtime.cast :as cast]
            [atproto.tap.auth :as auth]
            [atproto.tap.events :as events]))

(set! *warn-on-reflection* true)

(s/def ::admin-password string?)
(s/def ::handler fn?)
(s/def ::path string?)
(s/def ::config (s/keys :req-un [::admin-password ::handler]
                        :opt-un [::path]))

(defn- json-response
  [status body]
  {:status status
   :headers {"content-type" "application/json"}
   :body (json/write-str body)})

(defn handler
  "Build a Ring handler for Tap webhook POSTs.

  Config:
    :admin-password  shared secret; requests failing
                     atproto.tap.auth/admin-auth-valid? -> 401.
    :handler         (fn [event]) — the flattened event from
                     atproto.tap.events/parse-tap-event; return value
                     ignored; throw -> 500 (Tap retries).
    :path            route to match (default \"/tap\"); requests for other
                     paths -> nil (composable like
                     atproto.xrpc.server.ring/handler).

  Responses are JSON: 200 {} on success, 405 off-method, 401 on bad auth,
  400 on unparseable/invalid events, 500 on handler errors (the exception
  message is never leaked; it is cast as an alert)."
  [{:keys [admin-password handler path] :or {path "/tap"} :as config}]
  (when-not (s/valid? ::config config)
    (throw (ex-info "Invalid Tap webhook config."
                    {:error "InvalidConfig"
                     :message (s/explain-str ::config config)})))
  (fn [req]
    (when (= path (:uri req))
      (cond
        (not= :post (:request-method req))
        (json-response 405 {:error "MethodNotAllowed"})

        (not (auth/admin-auth-valid? admin-password
                                     (get-in req [:headers "authorization"])))
        (json-response 401 {:error "AuthRequired"})

        :else
        (let [data (try
                     (some-> (:body req) slurp json/read-str)
                     (catch Exception _ ::unparseable))
              event (if (= ::unparseable data)
                      {:error "InvalidTapEvent"
                       :message "Request body is not valid JSON."}
                      (events/parse-tap-event data))]
          (if (:error event)
            (json-response 400 {:error "InvalidTapEvent"
                                :message (:message event)})
            (try
              (handler event)
              (json-response 200 {})
              (catch Throwable t
                (cast/alert {:message "Tap webhook handler threw."
                             :ex t})
                (json-response 500 {:error "InternalError"})))))))))
