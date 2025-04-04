(ns xyz.statusphere.auth
  (:require [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [atproto.oauth.client :as oauth-client]
            [atproto.oauth.client.store :as store]
            [xyz.statusphere.db :as db])
  (:import [java.net URLEncoder]))

(defn state-store
  [db]
  (reify store/Store
    (get* [_ key]
      (->> (sql/query db ["select state from auth_state where key = ?" key])
           first
           :auth_state/state))
    (set* [_ key state]
      (jdbc/execute-one! db
                         ["insert into auth_state (key, state) values (?, ?) on conflict(key) do update set state=?"
                          key state state]))
    (del* [_ key]
      (sql/delete! db :auth_state {:key key}))))

(defn session-store
  [db]
  (reify store/Store
    (get* [_ key]
      (->> (sql/query db ["select session from auth_session where key = ?" key])
           first
           :auth_session/session))
    (set* [_ key session]
      (jdbc/execute-one! db
                         ["insert into auth_session (key, session) values (?, ?) on conflict(key) do update set session=?"
                          key session session]))
    (del* [_ key]
      (sql/delete! db :auth_session {:key key}))))

(defn client
  [{:keys [public-url url keyset] :as config}]
  (let [redirect-uri (str url "/oauth/callback")
        scope "atproto transition:generic"
        client-id (if public-url
                    ;; In production, the client-metadata must be served at /client-metadata.json"
                    (str public-url "/client-metadata.json")
                    ;; During development the client-metadata is provided inline in the client_id
                    (format "http://localhost?redirect_uri=%s&scope=%s"
                            (URLEncoder/encode redirect-uri)
                            (URLEncoder/encode scope)))]
    (oauth-client/create
     {:client-metadata {:client_name "AT Proto Statusphere Example App in Clojure"
                        :client_id client-id
                        :client_uri url
                        :redirect_uris [redirect-uri]
                        :scope scope
                        :grant_types ["authorization_code" "refresh_token"]
                        :response_types ["code"]
                        :application_type "web"
                        :token_endpoint_auth_method "none" ;; "private_key_jwt"
                        :dpop_bound_access_tokens true}
      :keys keyset
      :state-store (state-store db/db)
      :session-store (session-store db/db)})))
