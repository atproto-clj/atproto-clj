(ns atproto.sync.backfill-test
  "Backfill tests: listRecords pagination against stubbed HTTP, and a
  CAR-based get-repo-walk over a real signed repo built with atproto.repo."
  (:require [clojure.test :refer [deftest is testing]]
            [atproto.crypto :as crypto]
            [atproto.identity :as identity]
            [atproto.repo :as repo]
            [atproto.repo.blockstore :as blockstore]
            [atproto.repo.sync :as repo-sync]
            [atproto.repo.test-support.util :as util]
            [atproto.runtime.http :as http]
            [atproto.sync.backfill :as backfill]
            [atproto.test-support.http :as fake-http]
            [atproto.xrpc.client :as xrpc]))

(set! *warn-on-reflection* true)

(def did "did:plc:44ybard66vv44zksje25o7dz")

(defn- listed-record
  [collection i]
  {:uri (str "at://" did "/" collection "/rkey-" (format "%03d" i))
   :cid "bafyreie5cvv4h45feadgeuwhbcutmh6t2ceseocckahdoe6uat64zmz454"
   :value {:$type collection :n i}})

(deftest list-records-walk-test
  (testing "paginates >100 records completely, in order, with :live false"
    (let [collection "xyz.statusphere.status"
          page1 (mapv #(listed-record collection %) (range 100))
          page2 (mapv #(listed-record collection %) (range 100 120))
          {:keys [handler requests]}
          (fake-http/scripted
           [(fake-http/json-response {:records page1 :cursor "page-2"})
            (fake-http/json-response {:records page2})])
          client (xrpc/init {:service "https://pds.test"})
          events (atom [])]
      (with-redefs [http/handle-request handler]
        (let [result (deref (backfill/list-records-walk
                             client
                             {:repo did
                              :collection collection
                              :handler #(swap! events conj %)})
                            5000 ::timeout)]
          (is (= {:count 120} result))
          (is (= 120 (count @events)))
          (is (every? #(= :create (:kind %)) @events))
          (is (every? #(false? (:live %)) @events))
          (is (= (map #(str "rkey-" (format "%03d" %)) (range 120))
                 (map :rkey @events)))
          (is (= did (:did (first @events))))
          (is (= {:$type collection :n 0} (:record (first @events))))
          ;; request shape: limit + ascending order + cursor threading
          (let [[req1 req2] @requests]
            (is (= "100" (get-in req1 [:query-params :limit])))
            (is (= "true" (get-in req1 [:query-params :reverse])))
            (is (nil? (get-in req1 [:query-params :cursor])))
            (is (= "page-2" (get-in req2 [:query-params :cursor]))))))))
  (testing "a throwing handler aborts the walk"
    (let [{:keys [handler]}
          (fake-http/scripted
           [(fake-http/json-response
             {:records (mapv #(listed-record "c.c.c" %) (range 3))})])
          client (xrpc/init {:service "https://pds.test"})
          seen (atom 0)]
      (with-redefs [http/handle-request handler]
        (let [result (deref (backfill/list-records-walk
                             client
                             {:repo did
                              :collection "c.c.c"
                              :handler (fn [_]
                                         (when (= 2 (swap! seen inc))
                                           (throw (ex-info "boom" {}))))})
                            5000 ::timeout)]
          (is (= "BackfillHandlerError" (:error result)))
          (is (= 2 @seen))))))
  (testing "missing arguments"
    (let [client (xrpc/init {:service "https://pds.test"})]
      (is (= "InvalidRequest"
             (:error (deref (backfill/list-records-walk client {:repo did})
                            1000 ::timeout)))))))

(deftest backfill-repo-test
  (let [{:keys [handler requests]}
        (fake-http/routed
         [["describeRepo"
           (fake-http/json-response
            {:did did :handle "alice.test"
             :collections ["app.bsky.feed.post"
                           "app.bsky.graph.follow"
                           "xyz.statusphere.status"]})]
          [(fn [req] (and (.contains ^String (:url req) "listRecords")
                          (= "app.bsky.feed.post"
                             (get-in req [:query-params :collection]))))
           (fake-http/json-response
            {:records (mapv #(listed-record "app.bsky.feed.post" %) (range 2))})]
          [(fn [req] (and (.contains ^String (:url req) "listRecords")
                          (= "xyz.statusphere.status"
                             (get-in req [:query-params :collection]))))
           (fake-http/json-response
            {:records [(listed-record "xyz.statusphere.status" 0)]})]])
        client (xrpc/init {:service "https://pds.test"})
        events (atom [])]
    (with-redefs [http/handle-request handler]
      (let [result (deref (backfill/backfill-repo
                           client
                           {:repo did
                            :filter-collections ["app.bsky.feed.post"
                                                 "xyz.statusphere.*"]
                            :handler #(swap! events conj %)})
                          5000 ::timeout)]
        (is (= {:count 3
                :collections ["app.bsky.feed.post" "xyz.statusphere.status"]}
               result))
        ;; the excluded collection was never fetched
        (is (not-any? #(= "app.bsky.graph.follow"
                          (get-in % [:query-params :collection]))
                      @requests))
        (is (= ["app.bsky.feed.post" "app.bsky.feed.post" "xyz.statusphere.status"]
               (map :collection @events)))))))

;; -----------------------------------------------------------------------------
;; CAR-based get-repo-walk over a real signed repo
;; -----------------------------------------------------------------------------

(def ^:private keypair
  (delay (util/ok! (util/result-of (crypto/generate "ES256K")))))

(defn- build-repo-car
  "Create a real signed repo with the given records and export it as CAR
  bytes. Returns {:car bytes :did-key \"did:key:...\"}."
  [records]
  (let [storage (blockstore/memory-blockstore)
        r (util/ok! (util/result-of
                     (repo/create storage did @keypair
                                  :initial-writes
                                  (mapv (fn [{:keys [collection rkey value]}]
                                          {:action :create
                                           :collection collection
                                           :rkey rkey
                                           :value value})
                                        records))))]
    {:car (util/ok! (repo-sync/repo->car storage (:cid r)))
     :did-key (crypto/did @keypair)}))

(defn- stub-resolve-identity
  [expected]
  (fn [at-identifier & {:keys [callback]}]
    (is (= expected at-identifier))
    (callback {:did did
               :did-doc {:id did :alsoKnownAs [] :verificationMethod [] :service []}})))

(deftest get-repo-walk-test
  (let [records [{:collection "app.bsky.feed.post" :rkey "3kaaa"
                  :value {:$type "app.bsky.feed.post" :text "one"}}
                 {:collection "app.bsky.feed.post" :rkey "3kbbb"
                  :value {:$type "app.bsky.feed.post" :text "two"}}
                 {:collection "xyz.statusphere.status" :rkey "self"
                  :value {:$type "xyz.statusphere.status" :status "🦋"}}]
        {:keys [car did-key]} (build-repo-car records)]
    (testing "verified walk emits every record as a :live false create"
      (let [{:keys [handler requests]}
            (fake-http/scripted
             [{:status 200
               :headers {:content-type "application/vnd.ipld.car"}
               :body car}])
            client (xrpc/init {:service "https://pds.test"})
            events (atom [])]
        (with-redefs [http/handle-request handler
                      identity/resolve-identity (stub-resolve-identity did)]
          (let [result (deref (backfill/get-repo-walk
                               client
                               {:repo did
                                :handler #(swap! events conj %)
                                :did-key did-key})
                              10000 ::timeout)]
            (is (= {:count 3 :did did} result))
            (is (= #{"one" "two"}
                   (->> @events
                        (filter #(= "app.bsky.feed.post" (:collection %)))
                        (map #(get-in % [:record :text]))
                        set)))
            (is (every? #(and (= :create (:kind %)) (false? (:live %))) @events))
            (is (every? #(= (str "at://" did "/" (:collection %) "/" (:rkey %))
                            (:uri %))
                        @events))
            ;; the CAR was fetched from the client's service with the did
            (let [url (:url (first @requests))]
              (is (.contains ^String url "com.atproto.sync.getRepo"))
              (is (.contains ^String url "did%3Aplc")))))))
    (testing "a CAR signed by a different key fails verification"
      (let [other-key (util/ok! (util/result-of (crypto/generate "ES256K")))
            {:keys [handler]}
            (fake-http/scripted
             [{:status 200
               :headers {:content-type "application/vnd.ipld.car"}
               :body car}])
            client (xrpc/init {:service "https://pds.test"})]
        (with-redefs [http/handle-request handler
                      identity/resolve-identity (stub-resolve-identity did)]
          (let [result (deref (backfill/get-repo-walk
                               client
                               {:repo did
                                :handler (fn [_])
                                :did-key (crypto/did other-key)})
                              10000 ::timeout)]
            (is (= "RepoVerification" (:error result)))))))
    (testing "http failure surfaces"
      (let [{:keys [handler]}
            (fake-http/scripted [(fake-http/json-response 404 {:error "RepoNotFound"})])
            client (xrpc/init {:service "https://pds.test"})]
        (with-redefs [http/handle-request handler
                      identity/resolve-identity (stub-resolve-identity did)]
          (let [result (deref (backfill/get-repo-walk
                               client
                               {:repo did :handler (fn [_]) :did-key did-key})
                              10000 ::timeout)]
            (is (= "HTTP_404" (:error result)))))))))
