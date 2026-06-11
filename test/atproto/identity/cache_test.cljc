(ns atproto.identity.cache-test
  "Cache behaviors replicated from the reference
  packages/identity/tests/did-cache.test.ts:48-102: cache-on-lookup,
  stale-serve+revalidate, expired-forces-refresh."
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [atproto.runtime.http :as http]
            [atproto.test-support.http :as fake-http]
            [atproto.identity :as identity]
            [atproto.identity.cache :as cache]))

(def minute (* 1000 60))
(def hour (* 1000 60 60))

;; -----------------------------------------------------------------------------
;; Generative properties
;; -----------------------------------------------------------------------------

(def gen-cache-op
  "An operation against a small key space, for model-based testing."
  (let [gen-key (gen/elements ["a" "b" "c" "d"])]
    (gen/one-of [(gen/tuple (gen/return :set) gen-key gen/small-integer)
                 (gen/tuple (gen/return :del) gen-key)
                 (gen/return [:clear])])))

(defspec memory-cache-model-spec 100
  ;; memory-cache behaves exactly like a map under set*/del*/clear*
  (prop/for-all [ops (gen/vector gen-cache-op 0 25)]
    (let [c (cache/memory-cache)
          model (reduce (fn [m [op k v]]
                          (case op
                            :set (do (cache/set* c k {:val v :updated-at 0})
                                     (assoc m k {:val v :updated-at 0}))
                            :del (do (cache/del* c k)
                                     (dissoc m k))
                            :clear (do (cache/clear* c)
                                       {})))
                        {} ops)]
      (every? (fn [k] (= (get model k) (cache/get* c k)))
              ["a" "b" "c" "d" "missing"]))))

;; TTLs in whole minutes with entry ages offset by 30s, so the few ms
;; between set* and check can never flip a comparison.
(defspec check-classification-spec 100
  (prop/for-all [age-min (gen/choose 0 (* 2 24 60))
                 stale-min (gen/choose 1 (* 24 60))
                 max-min (gen/choose 1 (* 2 24 60))
                 negative? gen/boolean
                 negative-min (gen/one-of [(gen/return nil)
                                           (gen/choose 1 (* 24 60))])]
    (let [c (cache/memory-cache)
          policy {:stale-ttl (* stale-min minute)
                  :max-ttl (* max-min minute)
                  :negative-ttl (some-> negative-min (* minute))}
          age (+ (* age-min minute) (* 30 1000))
          _ (cache/set* c "k" (cond-> {:val :doc
                                       :updated-at (- (cache/now-ms) age)}
                                negative? (assoc :negative? true)))
          result (cache/check c policy "k")
          expected-expired? (if negative?
                              (or (nil? negative-min) (< (* negative-min minute) age))
                              (< (* max-min minute) age))
          expected-stale? (if negative?
                            expected-expired?
                            (< (* stale-min minute) age))]
      (and (nil? (cache/check c policy "missing"))
           (= {:val :doc
               :stale? expected-stale?
               :expired? expected-expired?
               :negative? negative?}
              (dissoc result :updated-at))))))

(defspec store-negative-policy-spec 50
  ;; a negative marker is stored iff the policy enables negative caching;
  ;; otherwise the existing entry is evicted
  (prop/for-all [negative-min (gen/one-of [(gen/return nil)
                                           (gen/choose 1 (* 24 60))])]
    (let [c (cache/memory-cache)]
      (cache/store c "k" :doc)
      (cache/store-negative c {:negative-ttl (some-> negative-min (* minute))} "k")
      (if negative-min
        (true? (:negative? (cache/get* c "k")))
        (nil? (cache/get* c "k"))))))

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
