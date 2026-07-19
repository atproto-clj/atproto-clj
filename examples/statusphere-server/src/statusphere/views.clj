(ns statusphere.views
  "Pure views: data in, hiccup out. No I/O — handlers query and resolve,
  then hand this namespace finished data. hiccup2 escapes everything by
  default; statuses and handles are untrusted network data."
  (:require [hiccup2.core :as h])
  (:import [java.time LocalDate ZoneOffset]
           [java.time.format DateTimeFormatter]
           [java.util Date]))

(set! *warn-on-reflection* true)

(def status-options
  "The emoji picker, mirroring STATUS_OPTIONS in the TS reference app."
  ["👍" "👎" "💙" "🥹" "😧" "😤" "🙃" "😉" "😎" "🤓" "🤨" "🥳" "😭"
   "🤯" "🫡" "💀" "✊" "🤘" "👀" "🧠" "👩‍💻" "🧑‍💻" "🥷" "🧌" "🦋" "🚀"])

(def error-messages
  "Fixed vocabulary for ?error= codes — never echo free text back into a page."
  {"oauth"          "Could not sign you in. Check your handle and try again."
   "invalid-status" "That doesn't look like a single emoji."
   "pds"            "Your PDS did not accept the status. Try again."})

;; -----------------------------------------------------------------------------
;; Fragments
;; -----------------------------------------------------------------------------

(defn- date-str [^Date d]
  (.format (DateTimeFormatter/ofPattern "EEEE, MMMM d, yyyy")
           (LocalDate/ofInstant (.toInstant d) ZoneOffset/UTC)))

(defn- today? [^Date d]
  (= (LocalDate/ofInstant (.toInstant d) ZoneOffset/UTC)
     (LocalDate/now ZoneOffset/UTC)))

(defn- csrf-field [csrf-token]
  [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}])

(defn- error-banner [error-code]
  (when-let [message (get error-messages error-code)]
    [:div.error.visible message]))

(defn- login-form [csrf-token]
  [:form.login-form {:action "/login" :method "post"}
   (csrf-field csrf-token)
   [:input {:type        "text"
            :name        "handle"
            :placeholder "Enter your handle (eg alice.bsky.social)"
            :required    "required"}]
   [:button {:type "submit"} "Log in"]])

(defn- signup-cta []
  [:div.signup-cta
   "Don't have an account on the Atmosphere? "
   [:a {:href "https://bsky.app"} "Sign up for Bluesky"]
   " to create one now!"])

(defn- session-card
  "Greeting + logout for a signed-in viewer {:did .. :handle .. :display-name ..}."
  [{:keys [display-name handle]} csrf-token]
  [:div.card
   [:form.session-form {:action "/logout" :method "post"}
    [:div "Hi, " [:strong (or display-name handle "friend")] ". What's your status today?"]
    [:div (csrf-field csrf-token)
     [:button {:type "submit"} "Log out"]]]])

(defn- status-picker
  "One form; every emoji is a submit button named `status`."
  [current-emoji csrf-token]
  [:form {:action "/status" :method "post"}
   (csrf-field csrf-token)
   [:div.status-options
    (for [emoji status-options]
      [:button.status-option
       {:type  "submit"
        :name  "status"
        :value emoji
        :class (when (= emoji current-emoji) "selected")}
       emoji])]])

(defn- status-line
  [first? {:keys [status/emoji status/author-did status/created-at]} handle-for]
  (let [handle (get handle-for author-did author-did)
        ^Date created created-at]
    [:div.status-line {:class (when first? "no-line")}
     [:div [:div.status emoji]]
     [:div.desc
      [:a.author {:href (str "https://bsky.app/profile/" handle)} (str "@" handle)]
      (if (today? created)
        (str " is feeling " emoji " today.")
        (str " was feeling " emoji " on " (date-str created) "."))]]))

;; -----------------------------------------------------------------------------
;; Pages
;; -----------------------------------------------------------------------------

(defn- page [title & body]
  (str (h/html {:mode :html}
               (h/raw "<!DOCTYPE html>")
               [:html
                [:head
                 [:meta {:charset "utf-8"}]
                 [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
                 [:title title]
                 [:link {:rel "stylesheet" :href "/style.css"}]]
                [:body [:div#root body]]])))

(defn home
  "The feed. `viewer` is nil when signed out; `handle-for` maps DID → handle;
  `statuses` are db entity maps; `my-status` is the viewer's current emoji."
  [{:keys [error viewer my-status statuses handle-for csrf-token]}]
  (page "Statusphere"
        (list
         (error-banner error)
         [:div#header
          [:h1 "Statusphere"]
          [:p "Set your status on the Atmosphere."]]
         [:div.container
          (if viewer
            (session-card viewer csrf-token)
            (list [:div.card (login-form csrf-token)] (signup-cta)))
          (when viewer
            (status-picker my-status csrf-token))
          (map-indexed (fn [i status] (status-line (zero? i) status handle-for))
                       statuses)])))

(defn login
  "Standalone login page, used for auth-required redirects and login errors."
  [{:keys [error csrf-token]}]
  (page "Log in — Statusphere"
        (list
         (error-banner error)
         [:div#header
          [:h1 "Statusphere"]
          [:p "Set your status on the Atmosphere."]]
         [:div.container
          [:div.card (login-form csrf-token)]
          (signup-cta)])))
