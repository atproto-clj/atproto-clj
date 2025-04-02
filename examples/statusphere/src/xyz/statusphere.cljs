(ns xyz.statusphere
  (:require [cljs.core.async :as async]
            [reagent.core :as r]
            [reagent.dom :as rd]
            ["react-dom/client" :refer [createRoot]]
            [atproto.client :as client]
            [atproto.runtime.cast :as cast]))

(enable-console-print!)
(cast/register :alert  cast/log)
(cast/register :event  cast/log)
(cast/register :dev    cast/log)

(def status-options ["👍", "👎", "💙", "🥹", "😧", "😤", "🙃", "😉", "😎", "🤓", "🤨", "🥳", "😭", "😤", "🤯", "🫡", "💀", "✊", "🤘", "👀", "🧠", "👩‍💻", "🧑‍💻", "🥷", "🧌", "🦋", "🚀"])

(def state (r/atom {:error nil
                    :statuses []
                    :profile nil
                    :status nil}))

(defn- format-date
  [date]
  (.toLocaleDateString date
                       js/undefined
                       (clj->js {:weekday "long"
                                 :year "numeric"
                                 :month "long"
                                 :day "numeric"})))

(defn- today?
  [date]
  (let [today (js/Date.)]
    (== (.setHours today 0 0 0 0)
        (.setHours date  0 0 0 0))))

(defn- bsky-link
  [handle]
  (str "https://bsky.app/profile/" handle))

(defn app
  [client]
  (let [{:keys [error client statuses profile status]} @state]
    [:div#root

     (when error
       [:div.error.visible
        error])

     [:div#header
      [:h1 "Statusphere"]
      [:p "Set your status on the Atmosphere."]]

     [:div.container

      ;; login/logout form
      (if profile
        [:div.card
         [:form {:action "/logout" :method "post" :class "session-form"}
          [:div
           "Hi, " [:strong (get profile :displayName "friend")] ". What's your status today?"]
          [:div
           [:button {:type "submit"} "Log out"]]]]
        [:form {:action "/login" :method "post" :class "login-form"}
         [:input {:type "text"
                  :name "handle"
                  :placeholder "Enter your handle (eg alice.bsky.social)"
                  :required "required"}]
         [:button {:type "submit"} "Log in"]])

      (when (not profile)
        [:div.signup-cta
         "Don't have an account on the Atmosphere?"
         [:a {:href "https://bsky.app"}
          "Sign up for Bluesky"]
         " to create one now!"])

      ;; status-options
      [:div.status-options
       (for [[idx option] (map-indexed vector status-options)]
         ^{:key idx} [:button {:class (str "status-option"
                                           (when (= option (:status status))
                                             " selected"))
                               :name "status"
                               :value option
                               :on-click (fn [evt]
                                           (async/go
                                             (let [resp (<! (client/procedure client
                                                                              {:nsid "xyz.statusphere.sendStatus"
                                                                               :body {:status option}}))]
                                               (swap! state (fn [state]
                                                              (cond-> state
                                                                (:status resp) (update :statuses #(into [(:status resp)] %))
                                                                :always (merge resp)))))))}
                      option])]

      ;; list of statuses
      [:div
       (for [[idx {:keys [profile createdAt] :as status}] (map-indexed vector statuses)]
         (let [handle (or (:handle profile) (:did profile))
               date (js/Date. createdAt)]
           ^{:key idx} [:div {:class (str "status-line" (when (zero? idx) " no-line"))}
                        [:div
                         [:div {:class "status"} (:status status)]]
                        [:div {:class "desc"}
                         [:a {:class "author"
                              :href (bsky-link handle)}
                          (str "@" handle)]
                         (if (today? date)
                           (str " is feeling " (:status status) " today.")
                           (str " was feeling " (:status status) " on " (format-date date)))]]))]]]))

(defonce root (createRoot (js/document.getElementById "app")))

(defn main
  []
  (async/go
    (let [client (<! (client/create {:service "http://127.0.0.1:8080"}))
          statuses-resp (<! (client/query client {:nsid "xyz.statusphere.getStatuses"}))
          user-resp (<! (client/query client {:nsid "xyz.statusphere.getUser"}))]
      (swap! state (fn [state]
                     (merge state
                            statuses-resp
                            (when (not (= "AuthenticationRequired" (:error user-resp)))
                              user-resp))))
      (.render root (r/as-element [app client])))))

(main)
