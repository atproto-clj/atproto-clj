(ns statusphere.routes
  "Pedestal routes, interceptors, and handlers.

  Handlers are functions of request -> channel-of-response, written as go
  blocks; async-handler adapts one into an interceptor whose :enter returns
  a channel delivering the updated context (Pedestal's async contract).
  After the first async interceptor the chain runs on the core.async
  dispatch pool, so nothing on a request path may block: SDK calls are
  awaited via :channel (a/promise-chan), Datomic writes via
  (a/<! (a/io-thread @(d/transact ...)))."
  (:require [clojure.core.async :as a]
            [datomic.api :as d]
            [io.pedestal.connector :as pconn]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.csrf :as csrf]
            [io.pedestal.http.ring-middlewares :as ring-middlewares]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.secure-headers :as secure-headers]
            [io.pedestal.service.interceptors :as interceptors]
            [io.pedestal.service.resources :as resources]
            [ring.middleware.session.cookie :as cookie]
            [statusphere.db :as db]
            [statusphere.views :as views])
  (:import [java.util Base64]))

(set! *warn-on-reflection* true)

;; -----------------------------------------------------------------------------
;; Response helpers
;; -----------------------------------------------------------------------------

(defn- html [body]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body body})

(defn- redirect [location]
  {:status 303
   :headers {"Location" location}})

;; -----------------------------------------------------------------------------
;; Interceptors
;; -----------------------------------------------------------------------------

(defn- async-handler
  "Adapt a (request -> channel-of-response) fn into a Pedestal interceptor."
  [name f]
  {:name  name
   :enter (fn [context]
            (a/go (assoc context :response (a/<! (f (:request context))))))})

(defn- inject-app
  "assoc the app context (Datomic conn, oauth client, handle resolver,
  config) into the request as :app — handlers and tests both see it."
  [app]
  {:name  ::inject-app
   :enter (fn [context] (update context :request assoc :app app))})

;; -----------------------------------------------------------------------------
;; Handlers
;; -----------------------------------------------------------------------------

(defn- csrf-token [request]
  (get request csrf/anti-forgery-token))

(defn home
  [{:keys [app query-params] :as request}]
  (a/go
    (let [dbv      (d/db (:conn app))
          statuses (db/recent-statuses dbv 50)]
      (html (views/home {:error      (:error query-params)
                         :viewer     nil
                         :statuses   statuses
                         :handle-for {}
                         :csrf-token (csrf-token request)})))))

(defn login-page
  [{:keys [query-params] :as request}]
  (a/go
    (html (views/login {:error      (:error query-params)
                        :csrf-token (csrf-token request)}))))

;; -----------------------------------------------------------------------------
;; Routes & connector
;; -----------------------------------------------------------------------------

(defn routes [app]
  (let [common [(inject-app app)]]
    #{["/"      :get (conj common (async-handler ::home home))]
      ["/login" :get (conj common (async-handler ::login-page login-page))]}))

(defn connector-map
  "The Pedestal connector map: interceptor stack + routes. `app` is the
  request context map injected by inject-app.

  Interceptor order matters: session before anti-forgery (the token lives
  in the session), body-params before anti-forgery (the token arrives as a
  form field)."
  [{:keys [port cookie-secret public-url]} app]
  (let [host        (if public-url "0.0.0.0" "localhost")
        session-key (.decode (Base64/getDecoder) ^String cookie-secret)]
    (-> (pconn/default-connector-map host port)
        (pconn/with-interceptors
          [interceptors/log-request
           interceptors/not-found
           (ring-middlewares/content-type) ;; :leave — MIME type from file extension
           (ring-middlewares/session {:cookie-name  "sid"
                                      :store        (cookie/cookie-store {:key session-key})
                                      :cookie-attrs {:http-only true
                                                     ;; Lax is load-bearing: the OAuth
                                                     ;; callback is a top-level GET and
                                                     ;; must carry the session cookie.
                                                     :same-site :lax}})
           route/query-params
           (body-params/body-params)
           (csrf/anti-forgery)
           (secure-headers/secure-headers)])
        (pconn/with-routes
          (routes app)
          (resources/resource-routes {:resource-root "public"})))))
