(ns atproto.oauth.provider.store
  "Store protocols for the OAuth provider, plus in-memory implementations.

  Mirrors the seven pluggable stores of the reference provider
  (packages/oauth/oauth-provider/src/oauth-store.ts), minus the lexicon
  store (out of scope). Protocols are synchronous (precedent:
  atproto.oauth.client.store); values are plain maps. Implementations
  may throw; the provider converts to error maps at its public boundary.

  SQLite implementations live in atproto.oauth.provider.store.sqlite."
  (:require [atproto.runtime.crypto :as crypto]))

(defprotocol RequestStore
  "Pending authorization requests (PAR -> authorize -> code)."
  (create-request! [_ request-id data])
  (read-request    [_ request-id] "The request data, or nil.")
  (update-request! [_ request-id data] "Merge data into the request.")
  (delete-request! [_ request-id])
  (consume-request-code! [_ code]
    "Atomically fetch-and-delete the request holding this code.
    Returns {:request-id id :data data} or nil. Must never return the
    same code twice, including under concurrency."))

(defprotocol TokenStore
  "Issued token state (one row per access/refresh token pair)."
  (create-token! [_ token-id data refresh-token])
  (read-token    [_ token-id] "{:token-id .. :data .. :refresh-token ..} or nil.")
  (find-by-refresh-token [_ refresh-token])
  (find-by-code  [_ code] "The token issued for an authorization code, or nil.")
  (rotate-token! [_ token-id new-token-id new-refresh-token data]
    "Replace ids and data for a refresh grant. The old token-id and
    refresh token must no longer resolve afterwards.")
  (delete-token! [_ token-id]))

(defprotocol AccountStore
  "Host-app account integration: credential verification and
  device-session <-> account bindings."
  (authenticate-account [_ credentials]
    "credentials: {:identifier .. :password ..}. The account map
    ({:sub did, :handle?, ...}) or nil.")
  (get-account [_ sub] "The account map for sub, or nil.")
  (add-device-account! [_ device-id sub remember?])
  (get-device-account [_ device-id sub]
    "{:sub .. :remember? .. :authenticated-at ..} or nil.")
  (list-device-accounts [_ device-id])
  (remove-device-account! [_ device-id sub])
  (set-authorized-client! [_ sub client-id data])
  (get-authorized-client [_ sub client-id]))

(defprotocol DeviceStore
  "Browser/device sessions (cookie-bound by the host app)."
  (create-device! [_ device-id data])
  (read-device    [_ device-id])
  (update-device! [_ device-id data])
  (delete-device! [_ device-id]))

(defprotocol ReplayStore
  (unique? [_ nonce-namespace jti expires-at]
    "True iff jti has not been seen in this namespace; records it until
    expires-at (epoch seconds)."))

(defprotocol ClientStore
  "Optional first-party client registry consulted before fetching
  client-id metadata documents over HTTP."
  (get-client-metadata [_ client-id] "The stored metadata map, or nil."))

;; -----------------------------------------------------------------------------
;; In-memory implementations
;; -----------------------------------------------------------------------------

(defn memory-request-store
  []
  (let [state (atom {})]
    (reify RequestStore
      (create-request! [_ request-id data]
        (swap! state assoc request-id data)
        nil)
      (read-request [_ request-id]
        (get @state request-id))
      (update-request! [_ request-id data]
        (swap! state update request-id merge data)
        nil)
      (delete-request! [_ request-id]
        (swap! state dissoc request-id)
        nil)
      (consume-request-code! [_ code]
        (when code
          (let [hit (fn [m] (first (filter #(= code (:code (val %))) m)))
                [old _] (swap-vals! state
                                    (fn [m]
                                      (if-let [[id _] (hit m)]
                                        (dissoc m id)
                                        m)))]
            (when-let [[id data] (hit old)]
              {:request-id id :data data})))))))

(defn memory-token-store
  []
  (let [state (atom {})]
    (letfn [(find-first [pred]
              (some-> (first (filter (comp pred val) @state)) val))]
      (reify TokenStore
        (create-token! [_ token-id data refresh-token]
          (swap! state assoc token-id {:token-id token-id
                                       :data data
                                       :refresh-token refresh-token})
          nil)
        (read-token [_ token-id]
          (get @state token-id))
        (find-by-refresh-token [_ refresh-token]
          (when refresh-token
            ;; match the current refresh token, or a previously-rotated
            ;; (used) one so reuse can be detected and the family revoked
            (or (find-first #(= refresh-token (:refresh-token %)))
                (find-first #(contains? (set (get-in % [:data :used-refresh-tokens]))
                                        refresh-token)))))
        (find-by-code [_ code]
          (when code
            (find-first #(= code (get-in % [:data :code])))))
        (rotate-token! [_ token-id new-token-id new-refresh-token data]
          (swap! state
                 (fn [m]
                   (-> m
                       (dissoc token-id)
                       (assoc new-token-id {:token-id new-token-id
                                            :data data
                                            :refresh-token new-refresh-token}))))
          nil)
        (delete-token! [_ token-id]
          (swap! state dissoc token-id)
          nil)))))

(defn memory-account-store
  "accounts: [{:sub .. :password .. :handle? ..} ...]; authentication
  matches :identifier against :sub or :handle."
  [accounts]
  (let [accounts (vec accounts)
        state (atom {:device-accounts {} :authorized-clients {}})]
    (reify AccountStore
      (authenticate-account [_ {:keys [identifier password]}]
        (some (fn [{:keys [sub handle] :as account}]
                (when (and (or (= identifier sub) (= identifier handle))
                           (= password (:password account)))
                  (dissoc account :password)))
              accounts))
      (get-account [_ sub]
        (some (fn [account]
                (when (= sub (:sub account))
                  (dissoc account :password)))
              accounts))
      (add-device-account! [_ device-id sub remember?]
        (swap! state assoc-in [:device-accounts [device-id sub]]
               {:sub sub
                :remember? (boolean remember?)
                :authenticated-at (crypto/now)})
        nil)
      (get-device-account [_ device-id sub]
        (get-in @state [:device-accounts [device-id sub]]))
      (list-device-accounts [_ device-id]
        (->> (:device-accounts @state)
             (filter (fn [[[d _] _]] (= d device-id)))
             (mapv val)))
      (remove-device-account! [_ device-id sub]
        (swap! state update :device-accounts dissoc [device-id sub])
        nil)
      (set-authorized-client! [_ sub client-id data]
        (swap! state assoc-in [:authorized-clients [sub client-id]] data)
        nil)
      (get-authorized-client [_ sub client-id]
        (get-in @state [:authorized-clients [sub client-id]])))))

(defn memory-device-store
  []
  (let [state (atom {})]
    (reify DeviceStore
      (create-device! [_ device-id data]
        (swap! state assoc device-id data)
        nil)
      (read-device [_ device-id]
        (get @state device-id))
      (update-device! [_ device-id data]
        (swap! state update device-id merge data)
        nil)
      (delete-device! [_ device-id]
        (swap! state dissoc device-id)
        nil))))

(defn memory-replay-store
  []
  (let [state (atom {})]
    (reify ReplayStore
      (unique? [_ nonce-namespace jti expires-at]
        (let [k [nonce-namespace jti]
              now (crypto/now)
              [old _] (swap-vals! state
                                  (fn [m]
                                    (-> (into {} (filter #(< now (val %))) m)
                                        (assoc k expires-at))))]
          (not (contains? old k)))))))

(defn memory-client-store
  "clients: {client-id metadata-map}."
  [clients]
  (reify ClientStore
    (get-client-metadata [_ client-id]
      (get clients client-id))))

(defn memory-stores
  "All-protocol in-memory implementation for tests/dev.

  opts:
    :accounts  seed accounts for the account store
    :clients   {client-id metadata} for the client store"
  [& {:keys [accounts clients]}]
  {:request-store (memory-request-store)
   :token-store (memory-token-store)
   :account-store (memory-account-store accounts)
   :device-store (memory-device-store)
   :replay-store (memory-replay-store)
   :client-store (when clients (memory-client-store clients))})
