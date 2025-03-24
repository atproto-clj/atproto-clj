(ns statusphere
  "AT Protocol \"Statusphere\" Example App in Clojure."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.data.json :as json]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.cookie :refer [cookie-store]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.util.response :as r]
            [compojure.core :refer [GET POST defroutes]]
            [compojure.route :as route]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [atproto.client :as client]
            [atproto.tid :as tid]
            [atproto.identity]
            [atproto.session :as session]
            [atproto.session.oauth.client :as oauth-client]
            [statusphere.auth :as auth]
            [statusphere.view :as view]
            [statusphere.db :as db])
  (:import [java.time Instant]
           [java.util Base64]))

;; DID resolver

(defonce did-handle-store (atom {}))

(defn did->handle
  "Resolve the did into a handle or return the did itself."
  [did]
  (or (get @did-handle-store did)
      (let [{:keys [error handle] :as resp} @(atproto.identity/resolve-identity did)
            res (or handle did)]
        (when error
          (tap> resp))
        (when (not error)
          (swap! did-handle-store assoc did res)
          res))))

(defn dids->handles
  "A map of the dids to their handles."
  [dids]
  (reduce (fn [m did]
            (conj m [did (did->handle did)]))
          {}
          dids))

;; Inject the clients in the request

(defn wrap-oauth-client
  "Inject the oauth client in the request."
  [handler client]
  (fn [req]
    (handler (assoc req :oauth-client client))))

(defn wrap-atproto-client
  "If the user is authenticated, restore the oauth session and inject the atproto client in the request."
  [handler]
  (fn [{:keys [oauth-client session] :as request}]
    (or (when-let [did (::session/did session)]
          (let [{:keys [error] :as oauth-session} @(oauth-client/restore oauth-client did)]
            (when error
              (tap> oauth-session))
            (when (not error)
              (handler (assoc request :atproto-client @(client/create {:session oauth-session}))))))
        (handler request))))

;; Response helpers

(defn json
  [body]
  (r/content-type
   (r/response body)
   "application/json"))

(defn html
  [body]
  (r/content-type
   (r/response body)
   "text/html; charset=utf-8"))

;; Routes

(defroutes app

  (GET "/client-metadata.json" req
    (json (json/write-str (:client-metadata (:oauth-client req)))))

  (GET "/login" req
    (html (view/login (:params req))))

  (POST "/login" req
    (let [{:keys [error authorization-url] :as resp} @(oauth-client/authorize (:oauth-client req)
                                                                              (get-in req [:params :handle]))]
      (if error
        (do
          (tap> resp)
          (html (view/login {:error error})))
        (r/redirect authorization-url))))

  (GET "/oauth/callback" req
    (let [{:keys [error session] :as resp} @(oauth-client/callback (:oauth-client req)
                                                                   (:params req))]
      (if error
        (do
          (tap> resp)
          (html (view/login {:error error})))
        (assoc (r/redirect "/") :session session))))

  (POST "/logout" req
    ;; todo: destroy oauth session
    (assoc (r/redirect "/") :session nil))

  (GET "/" req
    (let [atproto-client (:atproto-client req)
          statuses (db/latest-statuses)
          my-status (when atproto-client
                      (db/my-status (client/did atproto-client)))
          profile (when atproto-client
                    (let [{:keys [error] :as resp} @(client/query atproto-client
                                                                  {:op "com.atproto.repo.getRecord"
                                                                   :params {:repo (client/did atproto-client)
                                                                            :collection "app.bsky.actor.profile"
                                                                            :rkey "self"}})]
                      (when error
                        (tap> resp))
                      (when (not error)
                        (:value resp))))
          did-handle-map (dids->handles (map :author-did statuses))]
      (html (view/home {:statuses statuses
                        :did-handle-map did-handle-map
                        :my-status my-status
                        :profile profile}))))

  (POST "/status" req
    (let [atproto-client (:atproto-client req)]
      (if (not atproto-client)
        {:status 401
         :body "Session required"}
        ;; todo: helper in client to create records
        (let [status {:status (:status (:params req))
                      :created-at (Instant/now)
                      :indexed-at (Instant/now)}
              rkey (tid/next-tid)
              record {:$type "xyz.statusphere.status"
                      :status (:status status)
                      :createdAt (str (:created-at status))}
              {:keys [error uri] :as resp} @(client/procedure atproto-client
                                                              {:op :com.atproto.repo.putRecord
                                                               :params {:repo (client/did atproto-client)
                                                                        :collection "xyz.statusphere.status"
                                                                        :rkey rkey
                                                                        :record record
                                                                        :validate false}})]
          (if error
            (do
              (tap> resp)
              {:status 500
               :body "Error: Failed to write the record."})
            (do
              (db/insert-status!
               (merge status
                      {:uri uri
                       :author-did (client/did atproto-client)}))
              (r/redirect "/")))))))

  (route/not-found
   (html (view/not-found))))

(defn handler
  [env]
  (let [oauth-client (auth/client env db/db)]
    (-> app
        (wrap-atproto-client)
        (wrap-oauth-client oauth-client)
        (wrap-resource "public")
        (wrap-session {:cookie-name "sid"
                       :store (cookie-store {:key (:cookie-secret env)})})
        (wrap-keyword-params)
        (wrap-params))))

(defn read-env
  [config-edn-path]
  (let [{:keys [port cookie-secret]} (-> (io/file config-edn-path)
                                         (io/reader)
                                         (java.io.PushbackReader.)
                                         (edn/read))]
    {:port port
     :cookie-secret (.decode (Base64/getDecoder) cookie-secret)}))

(defn -main [& args]
  (let [env (read-env (first args))]
    (db/up!)
    (run-jetty (handler env)
               {:port (:port env)
                :join? true})))

;; Development

(defonce server (atom nil))

(defn start-dev []
  (let [env (read-env "./config.edn")]
    (reset! server
            (run-jetty (handler env)
                       {:port (:port env)
                        :join? false}))))

(defn stop-dev []
  (when @server
    (.stop @server)))

(defn restart-dev []
  (stop-dev)
  (start-dev))

(comment

  (require 'statusphere :reload-all)

  (statusphere.db/up!)

  (statusphere.db/down!)

  (add-tap (fn [msg]
             (clojure.pprint/pprint msg)))

  (statusphere/restart-dev)

  )
