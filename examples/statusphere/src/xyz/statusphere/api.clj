(ns xyz.statusphere.api
  (:require [clojure.spec.alpha :as s]
            [atproto.runtime.cast :as cast]
            [atproto.identity :as identity]
            [atproto.xrpc.server :as xrpc]
            [atproto.lexicon :as lexicon]
            [atproto.tid :as tid]
            [atproto.client :as at]
            [xyz.statusphere.db :as db]))

(defonce did-handle-store (atom {}))

(defn did->handle
  "Resolve the did to a handle or return the did itself."
  [did]
  (or (get @did-handle-store did)
      (let [{:keys [error handle] :as resp} @(identity/resolve-identity did)]
        (if error
          (cast/alert resp)
          (let [res (or handle did)]
            (swap! did-handle-store assoc did res)
            res)))))

(defn profile->profile-view
  "Bluesky profile record -> profile view returned by the API."
  [did {:keys [avatar displayName createdAt]}]
  (cond-> {:$type "app.bsky.actor.defs#profileView"
           :did did
           :handle (did->handle did)
           :displayName displayName
           :createdAt createdAt}
    avatar (assoc :avatar (format "https://atproto.pictures/img/%s/%s" did (:ref avatar)))))

(defn status->status-view
  "Database status -> status view returned by the API."
  [{:keys [status/uri status/status status/author_did status/created_at]}]
  {:uri uri
   :status status
   :createdAt created_at
   :profile {:did author_did
             :handle (did->handle author_did)}})

(defmethod xrpc/handle "xyz.statusphere.getStatuses"
  [{:keys [atproto-client]} {:keys [params] :as request}]
  {:encoding "application/json"
   :body {:statuses (map status->status-view
                         (db/statuses params))}})

(defmethod xrpc/handle "xyz.statusphere.getUser"
  [{:keys [atproto-client]} request]
  (if (not atproto-client)
    (xrpc/auth-required)
    (let [did (at/did atproto-client)
          {:keys [error] :as resp} @(at/query atproto-client
                                              {:nsid "com.atproto.repo.getRecord"
                                               :params {:repo did
                                                        :collection "app.bsky.actor.profile"
                                                        :rkey "self"}})]
      (if error
        resp
        (let [profile (:value resp)]
          (if (not (s/valid? ::lexicon/record profile))
            {:error "InvalidLexiconProfile"
             :profile profile
             :explain-data (s/explain-data ::lexicon/record profile)}
            (let [status (db/user-status {:did did})]
              {:encoding "application/json"
               :body (cond-> {:profile (profile->profile-view did profile)}
                       status (assoc :status (status->status-view status)))})))))))

(defmethod xrpc/handle "xyz.statusphere.sendStatus"
  [{:keys [atproto-client]} {:keys [body] :as request}]
  (if (not atproto-client)
    (xrpc/auth-required)
    (let [did (at/did atproto-client)
          status {:$type "xyz.statusphere.status"
                  :status (:status body)
                  :createdAt (str (java.time.Instant/now))}]
      (if (not (s/valid? ::lexicon/record status))
        {:error "InvalidStatus"
         :message (s/explain-str ::lexicon/record status)}
        (let [{:keys [error] :as resp} @(at/procedure atproto-client
                                                      {:nsid "com.atproto.repo.putRecord"
                                                       :body {:repo did
                                                              :collection "xyz.statusphere.status"
                                                              :rkey (tid/next-tid)
                                                              :record status
                                                              :validate false}})]
          (if error
            resp
            (let [optimistic-status #:status{:uri (:uri resp)
                                             :author_did did
                                             :status (:status status)
                                             :created_at (:createdAt status)
                                             :indexed_at (:createdAt status)}]
              (db/upsert-status! optimistic-status)
              {:encoding "application/json"
               :body {:status (status->status-view optimistic-status)}})))))))
