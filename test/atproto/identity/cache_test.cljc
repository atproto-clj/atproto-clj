(ns atproto.identity.cache-test
  "Cache behaviors replicated from the reference
  packages/identity/tests/did-cache.test.ts:48-102: cache-on-lookup,
  stale-serve+revalidate, expired-forces-refresh."
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            [atproto.runtime.http :as http]
            [atproto.test-support.http :as fake-http]
            [atproto.identity :as identity]
            [atproto.identity.cache :as cache]))

(def hour (* 1000 60 60))

(deftest memory-cache-test
  (let [c (cache/memory-cache)]
    (is (nil? (cache/get* c "k")))
    (cache/set* c "k" {:val 1 :updated-at 123})
    (is (= {:val 1 :updated-at 123} (cache/get* c "k")))
    (cache/set* c "k2" {:val 2 :updated-at 456})
    (cache/del* c "k")
    (is (nil? (cache/get* c "k")))
    (is (some? (cache/get* c "k2")))
    (cache/clear* c)
    (is (nil? (cache/get* c "k2")))))

(deftest check-classification-test
  (let [c (cache/memory-cache)
        policy cache/default-policy
        now (cache/now-ms)]
    (testing "miss"
      (is (nil? (cache/check c policy "missing"))))
    (testing "fresh"
      (cache/set* c "fresh" {:val :doc :updated-at now})
      (is (= {:val :doc :stale? false :expired? false :negative? false}
             (dissoc (cache/check c policy "fresh") :updated-at))))
    (testing "stale but not expired"
      (cache/set* c "stale" {:val :doc :updated-at (- now (* 2 hour))})
      (is (= {:val :doc :stale? true :expired? false :negative? false}
             (dissoc (cache/check c policy "stale") :updated-at))))
    (testing "expired"
      (cache/set* c "expired" {:val :doc :updated-at (- now (* 25 hour))})
      (is (= {:val :doc :stale? true :expired? true :negative? false}
             (dissoc (cache/check c policy "expired") :updated-at))))
    (testing "negative entries expire by :negative-ttl"
      (cache/set* c "neg" {:val nil :negative? true :updated-at now})
      ;; nil :negative-ttl (the default): always expired
      (is (true? (:expired? (cache/check c policy "neg"))))
      (is (= {:val nil :stale? false :expired? false :negative? true}
             (dissoc (cache/check c (assoc policy :negative-ttl hour) "neg")
                     :updated-at)))
      (cache/set* c "neg" {:val nil :negative? true :updated-at (- now (* 2 hour))})
      (is (true? (:expired? (cache/check c (assoc policy :negative-ttl hour) "neg")))))))

(deftest store-negative-honors-policy-test
  (let [c (cache/memory-cache)]
    (cache/store c "k" :doc)
    (testing "disabled by default: evicts instead"
      (cache/store-negative c cache/default-policy "k")
      (is (nil? (cache/get* c "k"))))
    (testing "enabled: stores a marker"
      (cache/store-negative c {:negative-ttl hour} "k")
      (is (true? (:negative? (cache/get* c "k")))))))

;; -----------------------------------------------------------------------------
;; resolve-did / resolve-identity integration (stubbed HTTP)
;; -----------------------------------------------------------------------------

(def did "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa")

(def doc {:id did
          :alsoKnownAs ["at://alice.test"]
          :service [{:id "#atproto_pds"
                     :type "AtprotoPersonalDataServer"
                     :serviceEndpoint "https://pds.test"}]})

#?(:clj
   (defn- plc-handler
     "Routed fake for the PLC directory; returns {:handler .. :requests ..}."
     ([] (plc-handler (fake-http/json-response doc)))
     ([response]
      (fake-http/routed [["plc.directory" response]]))))

#?(:clj
   (defn- eventually
     "Poll f until it returns truthy (or ~2s elapse); returns the last value."
     [f]
     (loop [n 100]
       (let [v (f)]
         (if (or v (zero? n))
           v
           (do (Thread/sleep 20)
               (recur (dec n))))))))

#?(:clj
   (deftest caches-dids-on-lookup-test
     (let [{:keys [handler requests]} (plc-handler)
           c (cache/memory-cache)]
       (with-redefs [http/handle-request handler]
         (is (= {:did-doc doc} (deref (identity/resolve-did did :cache c) 1000 ::timeout)))
         (is (= 1 (count @requests)))
         (is (= doc (:val (cache/get* c did))))
         ;; second resolve within :stale-ttl performs zero network calls
         (is (= {:did-doc doc} (deref (identity/resolve-did did :cache c) 1000 ::timeout)))
         (is (= 1 (count @requests)))))))

#?(:clj
   (deftest stale-hit-serves-and-revalidates-test
     (let [{:keys [handler requests]} (plc-handler)
           c (cache/memory-cache)
           stale-doc (assoc doc :stale true)]
       ;; seed a stale-but-not-expired entry with a doctored doc so the
       ;; revalidated value is distinguishable (ref did-cache.test.ts:69-90)
       (cache/set* c did {:val stale-doc :updated-at (- (cache/now-ms) (* 2 hour))})
       (with-redefs [http/handle-request handler]
         (is (= {:did-doc stale-doc}
                (deref (identity/resolve-did did :cache c) 1000 ::timeout))
             "the stale doc is served")
         (is (true? (eventually #(= doc (:val (cache/get* c did)))))
             "the cache is revalidated in the background")
         (is (= 1 (count @requests)))
         (is (= {:did-doc doc}
                (deref (identity/resolve-did did :cache c) 1000 ::timeout))
             "the refreshed doc is served afterwards")))))

#?(:clj
   (deftest expired-hit-refetches-test
     (let [{:keys [handler requests]} (plc-handler)
           c (cache/memory-cache)]
       (cache/set* c did {:val (assoc doc :expired true)
                          :updated-at (- (cache/now-ms) (* 25 hour))})
       (with-redefs [http/handle-request handler]
         (is (= {:did-doc doc} (deref (identity/resolve-did did :cache c) 1000 ::timeout))
             "the expired doc is never served")
         (is (= 1 (count @requests)))))))

#?(:clj
   (deftest force-refresh-bypasses-cache-test
     (let [{:keys [handler requests]} (plc-handler)
           c (cache/memory-cache)]
       (cache/store c did (assoc doc :old true))
       (with-redefs [http/handle-request handler]
         (is (= {:did-doc doc}
                (deref (identity/resolve-did did :cache c :force-refresh true)
                       1000 ::timeout)))
         (is (= 1 (count @requests)))
         (is (= doc (:val (cache/get* c did))) "the fresh doc is written back")))))

#?(:clj
   (deftest not-found-evicts-by-default-test
     (let [{:keys [handler requests]} (plc-handler {:status 404 :body "nope"})
           c (cache/memory-cache)]
       (cache/store c did doc)
       (with-redefs [http/handle-request handler]
         (is (= "DidNotFound"
                (:error (deref (identity/resolve-did did :cache c :force-refresh true)
                               1000 ::timeout))))
         (is (nil? (cache/get* c did)) "reference behavior: evict, no negative entry")
         ;; without negative caching every miss hits the network
         (deref (identity/resolve-did did :cache c) 1000 ::timeout)
         (is (= 2 (count @requests)))))))

#?(:clj
   (deftest negative-caching-opt-in-test
     (let [{:keys [handler requests]} (plc-handler {:status 404 :body "nope"})
           c (cache/memory-cache)
           policy {:negative-ttl hour}]
       (with-redefs [http/handle-request handler]
         (is (= "DidNotFound"
                (:error (deref (identity/resolve-did did :cache c :cache-policy policy)
                               1000 ::timeout))))
         (is (= "DidNotFound"
                (:error (deref (identity/resolve-did did :cache c :cache-policy policy)
                               1000 ::timeout))))
         (is (= 1 (count @requests)) "the second miss is served from the negative entry")))))

#?(:clj
   (deftest resolve-identity-uses-cache-test
     (let [{:keys [handler requests]} (plc-handler)
           c (cache/memory-cache)]
       (with-redefs [http/handle-request handler]
         (let [expected {:did did :did-doc doc :pds "https://pds.test" :handle "alice.test"}]
           (is (= expected (deref (identity/resolve-identity did :cache c) 1000 ::timeout)))
           (is (= expected (deref (identity/resolve-identity did :cache c) 1000 ::timeout)))
           (is (= 1 (count @requests))))))))
