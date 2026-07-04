(ns atproto.oauth.provider.store.sqlite
  "SQLite implementations of the OAuth provider store protocols.

  One database holds every store's tables, following the reference
  combined store (packages/pds/src/account-manager/oauth-store.ts and
  db/schema). Map-valued store data is serialized as JSON. Accounts are
  read-only here: authentication is the host app's concern
  (authenticate-account is unsupported), while device<->account bindings
  and authorized-client records persist."
  (:require [atproto.runtime.crypto :as crypto]
            [atproto.runtime.json :as json]
            [atproto.pds.sql :as sql]
            [atproto.oauth.provider.store :as store])
  (:import [java.sql Connection]))

(set! *warn-on-reflection* true)

(def migrations
  [["001-init"
    [(str "CREATE TABLE authorization_request ("
          "id TEXT PRIMARY KEY, "
          "code TEXT, "
          "dataJson TEXT NOT NULL, "
          "expiresAt INTEGER NOT NULL)")
     "CREATE INDEX authorization_request_code_idx ON authorization_request (code)"
     (str "CREATE TABLE token ("
          "id TEXT PRIMARY KEY, "
          "refreshToken TEXT, "
          "code TEXT, "
          "dataJson TEXT NOT NULL)")
     "CREATE INDEX token_refresh_token_idx ON token (refreshToken)"
     "CREATE INDEX token_code_idx ON token (code)"
     (str "CREATE TABLE used_refresh_token ("
          "refreshToken TEXT PRIMARY KEY, "
          "tokenId TEXT NOT NULL)")
     (str "CREATE TABLE device ("
          "id TEXT PRIMARY KEY, "
          "dataJson TEXT NOT NULL)")
     (str "CREATE TABLE device_account ("
          "deviceId TEXT NOT NULL, "
          "sub TEXT NOT NULL, "
          "dataJson TEXT NOT NULL, "
          "PRIMARY KEY (deviceId, sub))")
     (str "CREATE TABLE authorized_client ("
          "sub TEXT NOT NULL, "
          "clientId TEXT NOT NULL, "
          "dataJson TEXT NOT NULL, "
          "PRIMARY KEY (sub, clientId))")
     (str "CREATE TABLE replay ("
          "namespace TEXT NOT NULL, "
          "jti TEXT NOT NULL, "
          "expiresAt INTEGER NOT NULL, "
          "PRIMARY KEY (namespace, jti))")]]])

(defn- ->json [m] (json/write-str m))
(defn- <-json [s] (when s (json/read-str s)))

(defn sqlite-request-store
  [^Connection conn]
  (reify store/RequestStore
    (create-request! [_ request-id data]
      (sql/execute! conn
                    ["INSERT INTO authorization_request (id, code, dataJson, expiresAt) VALUES (?, ?, ?, ?)"
                     request-id (:code data) (->json data) (long (:expires-at data))])
      nil)
    (read-request [_ request-id]
      (some-> (sql/execute-one! conn ["SELECT dataJson FROM authorization_request WHERE id = ?" request-id])
              :dataJson <-json))
    (update-request! [_ request-id data]
      (let [current (store/read-request (sqlite-request-store conn) request-id)
            merged (merge current data)]
        (sql/execute! conn
                      ["UPDATE authorization_request SET code = ?, dataJson = ? WHERE id = ?"
                       (:code merged) (->json merged) request-id]))
      nil)
    (delete-request! [_ request-id]
      (sql/execute! conn ["DELETE FROM authorization_request WHERE id = ?" request-id])
      nil)
    (consume-request-code! [_ code]
      (when code
        (sql/in-write-tx*
         conn
         (fn [conn]
           (when-let [row (sql/execute-one!
                           conn ["SELECT id, dataJson FROM authorization_request WHERE code = ?" code])]
             (sql/execute! conn ["DELETE FROM authorization_request WHERE id = ?" (:id row)])
             {:request-id (:id row) :data (<-json (:dataJson row))})))))))

(defn sqlite-token-store
  [^Connection conn]
  (letfn [(row->token [row]
            (when row
              {:token-id (:id row)
               :data (<-json (:dataJson row))
               :refresh-token (:refreshToken row)}))]
    (reify store/TokenStore
      (create-token! [_ token-id data refresh-token]
        (sql/execute! conn
                      ["INSERT INTO token (id, refreshToken, code, dataJson) VALUES (?, ?, ?, ?)"
                       token-id refresh-token (:code data) (->json data)])
        nil)
      (read-token [_ token-id]
        (row->token (sql/execute-one! conn ["SELECT * FROM token WHERE id = ?" token-id])))
      (find-by-refresh-token [_ refresh-token]
        (when refresh-token
          (or (row->token (sql/execute-one! conn ["SELECT * FROM token WHERE refreshToken = ?" refresh-token]))
              ;; a previously-rotated (used) refresh token: resolve its family
              (when-let [used (sql/execute-one!
                               conn ["SELECT tokenId FROM used_refresh_token WHERE refreshToken = ?"
                                     refresh-token])]
                (row->token (sql/execute-one! conn ["SELECT * FROM token WHERE id = ?" (:tokenId used)]))))))
      (find-by-code [_ code]
        (when code
          (row->token (sql/execute-one! conn ["SELECT * FROM token WHERE code = ?" code]))))
      (rotate-token! [_ token-id new-token-id new-refresh-token data]
        (sql/in-write-tx*
         conn
         (fn [conn]
           ;; record every used refresh token against the new id so a
           ;; later replay resolves (and revokes) the family
           (doseq [used (:used-refresh-tokens data)]
             (sql/execute! conn
                           [(str "INSERT INTO used_refresh_token (refreshToken, tokenId) VALUES (?, ?) "
                                 "ON CONFLICT (refreshToken) DO UPDATE SET tokenId = excluded.tokenId")
                            used new-token-id]))
           (sql/execute! conn ["DELETE FROM token WHERE id = ?" token-id])
           (sql/execute! conn
                         ["INSERT INTO token (id, refreshToken, code, dataJson) VALUES (?, ?, ?, ?)"
                          new-token-id new-refresh-token (:code data) (->json data)])))
        nil)
      (delete-token! [_ token-id]
        (sql/in-write-tx*
         conn
         (fn [conn]
           (sql/execute! conn ["DELETE FROM used_refresh_token WHERE tokenId = ?" token-id])
           (sql/execute! conn ["DELETE FROM token WHERE id = ?" token-id])))
        nil))))

(defn sqlite-account-store
  "Device<->account bindings + authorized clients in SQLite. Credential
  verification and account lookup are delegated to `account-fns`:
  {:authenticate (fn [credentials] account|nil)
   :get-account  (fn [sub] account|nil)}."
  [^Connection conn {:keys [authenticate get-account] :as account-fns}]
  (reify store/AccountStore
    (authenticate-account [_ credentials]
      (when authenticate (authenticate credentials)))
    (get-account [_ sub]
      (when get-account (get-account sub)))
    (add-device-account! [_ device-id sub remember?]
      (let [data {:sub sub :remember? (boolean remember?) :authenticated-at (crypto/now)}]
        (sql/execute! conn
                      [(str "INSERT INTO device_account (deviceId, sub, dataJson) VALUES (?, ?, ?) "
                            "ON CONFLICT (deviceId, sub) DO UPDATE SET dataJson = excluded.dataJson")
                       device-id sub (->json data)]))
      nil)
    (get-device-account [_ device-id sub]
      (some-> (sql/execute-one! conn
                                ["SELECT dataJson FROM device_account WHERE deviceId = ? AND sub = ?"
                                 device-id sub])
              :dataJson <-json))
    (list-device-accounts [_ device-id]
      (mapv (comp <-json :dataJson)
            (sql/execute! conn ["SELECT dataJson FROM device_account WHERE deviceId = ?" device-id])))
    (remove-device-account! [_ device-id sub]
      (sql/execute! conn ["DELETE FROM device_account WHERE deviceId = ? AND sub = ?" device-id sub])
      nil)
    (set-authorized-client! [_ sub client-id data]
      (sql/execute! conn
                    [(str "INSERT INTO authorized_client (sub, clientId, dataJson) VALUES (?, ?, ?) "
                          "ON CONFLICT (sub, clientId) DO UPDATE SET dataJson = excluded.dataJson")
                     sub client-id (->json data)])
      nil)
    (get-authorized-client [_ sub client-id]
      (some-> (sql/execute-one! conn
                                ["SELECT dataJson FROM authorized_client WHERE sub = ? AND clientId = ?"
                                 sub client-id])
              :dataJson <-json))))

(defn sqlite-device-store
  [^Connection conn]
  (reify store/DeviceStore
    (create-device! [_ device-id data]
      (sql/execute! conn
                    [(str "INSERT INTO device (id, dataJson) VALUES (?, ?) "
                          "ON CONFLICT (id) DO UPDATE SET dataJson = excluded.dataJson")
                     device-id (->json data)])
      nil)
    (read-device [_ device-id]
      (some-> (sql/execute-one! conn ["SELECT dataJson FROM device WHERE id = ?" device-id])
              :dataJson <-json))
    (update-device! [_ device-id data]
      (let [current (store/read-device (sqlite-device-store conn) device-id)]
        (sql/execute! conn ["UPDATE device SET dataJson = ? WHERE id = ?"
                            (->json (merge current data)) device-id]))
      nil)
    (delete-device! [_ device-id]
      (sql/execute! conn ["DELETE FROM device WHERE id = ?" device-id])
      nil)))

(defn sqlite-replay-store
  [^Connection conn]
  (reify store/ReplayStore
    (unique? [_ nonce-namespace jti expires-at]
      (sql/in-write-tx*
       conn
       (fn [conn]
         (sql/execute! conn ["DELETE FROM replay WHERE expiresAt < ?" (long (crypto/now))])
         (if (sql/execute-one! conn
                               ["SELECT jti FROM replay WHERE namespace = ? AND jti = ?"
                                nonce-namespace jti])
           false
           (do (sql/execute! conn
                             ["INSERT INTO replay (namespace, jti, expiresAt) VALUES (?, ?, ?)"
                              nonce-namespace jti (long expires-at)])
               true)))))))

(defn stores
  "Open (creating/migrating) a SQLite database and build the full set of
  provider stores.

  opts:
    :db-path       (required) SQLite file path (or :memory)
    :account-fns   {:authenticate (fn [creds] account) :get-account (fn [sub] account)}
    :client-store  optional atproto.oauth.provider.store/ClientStore

  Returns {:stores {..} :conn conn :close! (fn [])}."
  [{:keys [db-path account-fns client-store]}]
  (when (not= :memory db-path)
    (sql/ensure-parent-dir! db-path))
  (let [conn (sql/connect db-path)]
    (sql/migrate! conn migrations)
    {:conn conn
     :close! (fn [] (.close conn))
     :stores (cond-> {:request-store (sqlite-request-store conn)
                      :token-store (sqlite-token-store conn)
                      :account-store (sqlite-account-store conn (or account-fns {}))
                      :device-store (sqlite-device-store conn)
                      :replay-store (sqlite-replay-store conn)}
               client-store (assoc :client-store client-store))}))
