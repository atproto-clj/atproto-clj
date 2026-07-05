(ns atproto.tap.events
  "Tap event parsing: wire JSON -> flattened typed events.

  The wire shape is nested (port of @atproto/tap types.ts, commit b9ef557):

    {:id 1 :type \"record\"
     :record {:live true :did \"did:plc:x\" :rev \"...\"
              :collection \"app.bsky.feed.post\" :rkey \"3k...\"
              :action \"create\" :cid \"bafy...\" :record {...}}}
    {:id 2 :type \"identity\"
     :identity {:did \"...\" :handle \"...\" :is_active true :status \"active\"}}

  `parse-tap-event` flattens these to single-level maps (see ::event);
  the wire key is_active becomes :active. Unknown extra fields are
  ignored (Tap is young; stay lenient)."
  (:require [clojure.spec.alpha :as s]
            [atproto.data.json :as data-json]
            [atproto.tap.events.wire :as-alias wire]
            [atproto.tap.events.wire.record :as-alias wire-record]
            [atproto.tap.events.wire.identity :as-alias wire-identity]))

#?(:clj (set! *warn-on-reflection* true))

;; -----------------------------------------------------------------------------
;; Wire shape specs
;; -----------------------------------------------------------------------------

(s/def ::wire-record/live boolean?)
(s/def ::wire-record/did string?)
(s/def ::wire-record/rev string?)
(s/def ::wire-record/collection string?)
(s/def ::wire-record/rkey string?)
(s/def ::wire-record/action #{"create" "update" "delete"})
(s/def ::wire-record/cid string?)
(s/def ::wire-record/record map?)

(s/def ::wire/record
  (s/and (s/keys :req-un [::wire-record/live
                          ::wire-record/did
                          ::wire-record/rev
                          ::wire-record/collection
                          ::wire-record/rkey
                          ::wire-record/action]
                 :opt-un [::wire-record/cid
                          ::wire-record/record])
         ;; create/update carry the record body and its CID; delete does not.
         (fn [{:keys [action cid record]}]
           (or (= "delete" action)
               (and (some? cid) (some? record))))))

(s/def ::wire-identity/did string?)
(s/def ::wire-identity/handle string?)
(s/def ::wire-identity/is_active boolean?)
(s/def ::wire-identity/status string?)

(s/def ::wire/identity
  (s/keys :req-un [::wire-identity/did
                   ::wire-identity/is_active]
          :opt-un [::wire-identity/handle
                   ::wire-identity/status]))

(s/def ::wire/id int?)
(s/def ::wire/type #{"record" "identity"})

(defmulti ^:private wire-event-type :type)
(defmethod wire-event-type "record" [_]
  (s/keys :req-un [::wire/id ::wire/type ::wire/record]))
(defmethod wire-event-type "identity" [_]
  (s/keys :req-un [::wire/id ::wire/type ::wire/identity]))

(s/def ::wire/event (s/multi-spec wire-event-type :type))

;; -----------------------------------------------------------------------------
;; Flattened event spec
;; -----------------------------------------------------------------------------

(s/def ::type #{:record :identity})
(s/def ::id int?)
(s/def ::action #{:create :update :delete})
(s/def ::did string?)
(s/def ::rev string?)
(s/def ::collection string?)
(s/def ::rkey string?)
(s/def ::live boolean?)
(s/def ::record map?)
(s/def ::cid string?)
(s/def ::handle string?)
(s/def ::active boolean?)
(s/def ::status string?)

(defmulti ^:private event-type :type)
(defmethod event-type :record [_]
  (s/keys :req-un [::type ::id ::action ::did ::rev ::collection ::rkey ::live]
          :opt-un [::record ::cid]))
(defmethod event-type :identity [_]
  (s/keys :req-un [::type ::id ::did ::active]
          :opt-un [::handle ::status]))

(s/def ::event (s/multi-spec event-type :type))

;; -----------------------------------------------------------------------------
;; Parsing
;; -----------------------------------------------------------------------------

(defn parse-tap-event
  "Flatten a Tap wire event (JSON parsed with keyword keys) into a typed
  event map (port of tap/src/types.ts:76-101):

    {:type :record :id 1 :action :create|:update|:delete
     :did ... :rev ... :collection ... :rkey ... :live true|false
     :record <decoded> :cid \"...\"}       ;; :create/:update only
    {:type :identity :id 2 :did ... :active true
     :handle ... :status ...}              ;; :handle/:status when present

  Record bodies pass through atproto.data.json/decode, so {:$link ...}
  becomes a CID object and {:$bytes ...} becomes bytes.

  Invalid input -> {:error \"InvalidTapEvent\" :message ...
  :explain-data <spec explain-data>}."
  [data]
  (if-not (s/valid? ::wire/event data)
    {:error "InvalidTapEvent"
     :message "Not a valid Tap wire event."
     :explain-data (s/explain-data ::wire/event data)}
    (case (:type data)
      "record"
      (let [{:keys [live did rev collection rkey action cid record]} (:record data)
            action (keyword action)]
        (cond-> {:type :record
                 :id (:id data)
                 :action action
                 :did did
                 :rev rev
                 :collection collection
                 :rkey rkey
                 :live live}
          (not= :delete action) (assoc :record (data-json/decode record)
                                       :cid cid)))
      "identity"
      (let [{:keys [did handle is_active status]} (:identity data)]
        (cond-> {:type :identity
                 :id (:id data)
                 :did did
                 :active is_active}
          (some? handle) (assoc :handle handle)
          (some? status) (assoc :status status))))))
