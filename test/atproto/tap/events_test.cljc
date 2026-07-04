(ns atproto.tap.events-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.spec.alpha :as s]
            [atproto.data :as data]
            [atproto.runtime.bytes :as bytes]
            [atproto.tap.events :as events]
            #?@(:clj [[clojure.java.io :as io]
                      [atproto.runtime.json :as json]])))

#?(:clj (set! *warn-on-reflection* true))

(def did "did:plc:ewvi7nxzyoun6zhxrhs64oiz")
(def cid-str "bafyreie5cvv4h45feadgeuwhbcutmh6t2ceseocckahdoe6uat64zmz454")

#?(:clj
   (def fixtures
     "Wire events mirroring the @atproto/tap tests (see the fixture's _meta)."
     (-> (io/resource "atproto/tap/fixtures/tap-events.json")
         slurp
         json/read-str)))

#?(:clj
   (defn- valid-fixture [k] (get-in fixtures [:valid k])))
#?(:clj
   (defn- invalid-fixture [k] (get-in fixtures [:invalid k])))

#?(:clj
   (deftest record-create-test
     (let [event (events/parse-tap-event (valid-fixture :record-create))]
       (is (= {:type :record
               :id 1
               :action :create
               :did did
               :rev "3kao2c5nmvz2c"
               :collection "app.bsky.feed.post"
               :rkey "3kao2c5nmvv2c"
               :live true
               :cid cid-str
               :record {:$type "app.bsky.feed.post"
                        :text "hello world"
                        :createdAt "2026-06-10T00:00:00.000Z"}}
              event))
       (is (s/valid? ::events/event event)))))

#?(:clj
   (deftest record-update-test
     (let [event (events/parse-tap-event (valid-fixture :record-update))]
       (is (= {:type :record
               :id 2
               :action :update
               :did did
               :rev "3kao2c5nmvz2d"
               :collection "app.bsky.actor.profile"
               :rkey "self"
               :live false
               :cid cid-str
               :record {:$type "app.bsky.actor.profile"
                        :displayName "Alice"}}
              event))
       (is (s/valid? ::events/event event)))))

#?(:clj
   (deftest record-delete-test
     (let [event (events/parse-tap-event (valid-fixture :record-delete))]
       (testing "delete carries no :record/:cid"
         (is (= {:type :record
                 :id 3
                 :action :delete
                 :did did
                 :rev "3kao2c5nmvz2e"
                 :collection "app.bsky.feed.post"
                 :rkey "3kao2c5nmvv2c"
                 :live true}
                event))
         (is (not (contains? event :record)))
         (is (not (contains? event :cid)))
         (is (s/valid? ::events/event event))))))

#?(:clj
   (deftest record-body-decoding-test
     (let [event (events/parse-tap-event (valid-fixture :record-with-links))]
       (testing "{:$link ...} in the record body decodes to a CID object"
         (let [cid (get-in event [:record :subject :cid])]
           (is (data/cid? cid))
           (is (= cid-str (data/format-cid cid)))))
       (testing "{:$bytes ...} decodes to bytes"
         (is (bytes/bytes? (get-in event [:record :payload]))))
       (testing "the envelope :cid stays a string"
         (is (= cid-str (:cid event)))))))

#?(:clj
   (deftest identity-test
     (testing "wire is_active becomes :active; :handle/:status kept when present"
       (let [event (events/parse-tap-event (valid-fixture :identity))]
         (is (= {:type :identity
                 :id 5
                 :did did
                 :handle "alice.test"
                 :active true
                 :status "active"}
                event))
         (is (s/valid? ::events/event event))))
     (testing "optional :handle/:status omitted when absent"
       (let [event (events/parse-tap-event (valid-fixture :identity-minimal))]
         (is (= {:type :identity :id 6 :did did :active false} event))
         (is (not (contains? event :handle)))
         (is (not (contains? event :status)))
         (is (s/valid? ::events/event event))))))

#?(:clj
   (deftest unknown-extra-fields-test
     (testing "unknown extra wire fields are ignored (leniency)"
       (let [event (events/parse-tap-event (valid-fixture :record-extra-fields))]
         (is (nil? (:error event)))
         (is (= [:record 7 :delete] ((juxt :type :id :action) event)))))))

#?(:clj
   (deftest invalid-fixture-events-test
     (doseq [k [:missing-did :bad-type :wrong-action :create-without-cid
                :wrong-field-type :not-a-map]]
       (testing (str "invalid fixture " (name k))
         (let [result (events/parse-tap-event (invalid-fixture k))]
           (is (= "InvalidTapEvent" (:error result)))
           (is (string? (:message result))))))))

(deftest invalid-inline-test
  (testing "non-map input"
    (doseq [input [nil 42 "record" [:a :b]]]
      (is (= "InvalidTapEvent" (:error (events/parse-tap-event input))))))
  (testing "spec explain-data is attached"
    (let [result (events/parse-tap-event {:id 1 :type "record" :record {}})]
      (is (= "InvalidTapEvent" (:error result)))
      (is (some? (:explain-data result))))))
