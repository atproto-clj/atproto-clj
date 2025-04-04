(ns xyz.statusphere.server
  (:require [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.cookie :refer [cookie-store]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.util.response :as r]
            [compojure.core :refer [GET POST routes]]
            [compojure.route :as route]
            [atproto.xrpc.server.ring :as ring-adapter]
            [atproto.runtime.json :as json]
            [atproto.runtime.cast :as cast]
            [atproto.oauth.client :as oauth-client]
            [atproto.lexicon :as lexicon]
            [atproto.xrpc.server :as xrpc]
            [atproto.client :as at]
            [xyz.statusphere.auth :as auth]))

(defn app
  [xrpc-server]
  (routes

   (ring-adapter/handler xrpc-server)

   (GET "/client-metadata.json" req
     (r/content-type
      (r/response (json/write-str (:client-metadata (:oauth-client (:app-ctx req)))))
      "application/json"))

   (POST "/login" req
     (let [{:keys [error authorization-url] :as resp} @(oauth-client/authorize
                                                        (:oauth-client (:app-ctx req))
                                                        (get-in req [:params :handle]))]
       (when error
         (cast/alert resp))
       (r/redirect authorization-url)))

   (GET "/oauth/callback" req
     (let [{:keys [error session] :as resp} @(oauth-client/callback
                                              (:oauth-client (:app-ctx req))
                                              (:params req))]
       (when error
         (cast/alert resp))
       (assoc (r/redirect "/")
              :session session)))

   (POST "/logout" req
     ;; todo: destroy oauth session
     (assoc (r/redirect "/")
            :session nil))

   (GET "/favicon.ico" _
     {:status 404})

   (GET "*" _
     (r/resource-response "public/index.html"))))

(defn wrap-oauth-client
  "Inject the oauth client in the request."
  [handler client]
  (fn [req]
    (handler (assoc-in req [:app-ctx :oauth-client] client))))

(defn wrap-atproto-client
  "If the user is authenticated, restore the oauth session and inject the atproto client in the request."
  [handler]
  (fn [{:keys [app-ctx session] :as request}]
    (let [{:keys [oauth-client]} app-ctx]
      (handler
       (if-let [did (:did session)]
         (let [{:keys [error] :as oauth-session} @(oauth-client/restore oauth-client did)]
           (if error
             (do
               (cast/alert oauth-session)
               request)
             (assoc-in request [:app-ctx :atproto-client] @(at/init {:session oauth-session}))))
         request)))))

(defn handler
  [config]
  (let [oauth-client (auth/client config)
        xrpc-server (xrpc/init {:lexicon (lexicon/load-resources! "lexicons")
                                :validate-response? true})]
    (-> (app xrpc-server)
        (wrap-atproto-client)
        (wrap-oauth-client oauth-client)
        (wrap-resource "public")
        (wrap-session {:cookie-name "sid"
                       :store (cookie-store {:key (.decode (java.util.Base64/getDecoder)
                                                           (:cookie-secret config))})})
        (wrap-keyword-params)
        (wrap-params))))

(defn start
  [{:keys [port] :as config}]
  (cast/event {:message "Starting the web server..."})
  (let [server (run-jetty (handler config)
                          {:port port
                           :join? false})]
    (cast/event {:message "Web server started."})
    server))

(defn stop
  [server]
  (cast/event {:message "Stopping the web server..."})
  (.stop server)
  (cast/event {:message "Web server stopped..."}))
