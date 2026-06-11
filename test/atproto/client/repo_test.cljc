(ns atproto.client.repo-test
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.http :as http]
            [atproto.runtime.json :as json]
            [atproto.test-support.http :as fake-http]
            [atproto.xrpc.client :as xrpc]
            [atproto.client.repo :as repo]))

(defn- did-session
  "A no-op Session carrying a DID (implemented via metadata)."
  [did]
  (with-meta
    {:did did :pds "https://pds.test"}
    {`xrpc/auth-interceptor
     (fn [_] {::i/name ::noop-auth
              ::i/enter identity})
     `xrpc/refresh-token
     (fn [_ cb] (cb {:error "TokenRefreshError"}))}))

(defn- authed-client
  []
  (xrpc/init {:session (did-session "did:plc:tester")}))

(defn- anon-client
  []
  (xrpc/init {:service "https://pds.test"}))

(defn- sent
  "The nsid, decoded JSON body, and params of a captured request."
  [request]
  {:nsid (last (clojure.string/split (:url request) #"/"))
   :method (:method request)
   :body (some-> (:body request) json/read-str)
   :params (:query-params request)})

(def ^:private post-record
  {:$type "app.bsky.feed.post" :text "hello"})

#?(:clj
   (deftest create-record-test
     ;; collection from $type, repo from the session DID
     (let [{:keys [handler requests]} (fake-http/scripted
                                       [(fake-http/json-response
                                         {:uri "at://did:plc:tester/app.bsky.feed.post/3jzfcijpj2z2a"
                                          :cid "bafyfake"})])]
       (with-redefs [http/handle-request handler]
         (let [resp (deref (repo/create-record (authed-client) {:record post-record})
                           1000 ::timeout)]
           (is (= "bafyfake" (:cid resp)))
           (let [{:keys [nsid method body]} (sent (first @requests))]
             (is (= "com.atproto.repo.createRecord" nsid))
             (is (= :post method))
             (is (= {:repo "did:plc:tester"
                     :collection "app.bsky.feed.post"
                     :record post-record}
                    body))))))
     ;; explicit args + swap-commit/validate?/rkey passthrough
     (let [{:keys [handler requests]} (fake-http/scripted
                                       [(fake-http/json-response {:uri "u" :cid "c"})])]
       (with-redefs [http/handle-request handler]
         (deref (repo/create-record (anon-client)
                                    {:record post-record
                                     :repo "did:plc:other"
                                     :collection "com.example.custom"
                                     :rkey "3jzfcijpj2z2a"
                                     :validate? false
                                     :swap-commit "bafycommit"})
                1000 ::timeout)
         (let [{:keys [body]} (sent (first @requests))]
           (is (= "did:plc:other" (:repo body)))
           (is (= "com.example.custom" (:collection body)))
           (is (= "3jzfcijpj2z2a" (:rkey body)))
           (is (false? (:validate body)))
           (is (= "bafycommit" (:swapCommit body))))))
     ;; errors
     (is (= "NotAuthenticated"
            (:error (deref (repo/create-record (anon-client) {:record post-record})
                           1000 ::timeout))))
     (is (= "InvalidRequest"
            (:error (deref (repo/create-record (authed-client) {})
                           1000 ::timeout))))
     (is (= "InvalidRequest"
            (:error (deref (repo/create-record (authed-client) {:record {:text "no type"}})
                           1000 ::timeout))))))

#?(:clj
   (deftest get-record-test
     (let [{:keys [handler requests]} (fake-http/scripted
                                       [(fake-http/json-response
                                         {:uri "u" :cid "c" :value post-record})])]
       (with-redefs [http/handle-request handler]
         (let [resp (deref (repo/get-record (authed-client)
                                            {:collection "app.bsky.feed.post"
                                             :rkey "3jzfcijpj2z2a"
                                             :cid "bafyrecord"})
                           1000 ::timeout)]
           (is (= post-record (:value resp)))
           (let [{:keys [nsid method params]} (sent (first @requests))]
             (is (= "com.atproto.repo.getRecord" nsid))
             (is (= :get method))
             (is (= {:repo "did:plc:tester"
                     :collection "app.bsky.feed.post"
                     :rkey "3jzfcijpj2z2a"
                     :cid "bafyrecord"}
                    params))))))
     (is (= "InvalidRequest"
            (:error (deref (repo/get-record (authed-client) {:collection "app.bsky.feed.post"})
                           1000 ::timeout))))
     (is (= "NotAuthenticated"
            (:error (deref (repo/get-record (anon-client) {:collection "app.bsky.feed.post"
                                                           :rkey "x"})
                           1000 ::timeout))))))

#?(:clj
   (deftest put-record-test
     (let [{:keys [handler requests]} (fake-http/scripted
                                       [(fake-http/json-response {:uri "u" :cid "c"})])]
       (with-redefs [http/handle-request handler]
         (deref (repo/put-record (authed-client)
                                 {:record post-record
                                  :rkey "3jzfcijpj2z2a"
                                  :validate? true
                                  :swap-commit "bafycommit"
                                  :swap-record "bafyrecord"})
                1000 ::timeout)
         (let [{:keys [nsid body]} (sent (first @requests))]
           (is (= "com.atproto.repo.putRecord" nsid))
           (is (= {:repo "did:plc:tester"
                   :collection "app.bsky.feed.post"
                   :rkey "3jzfcijpj2z2a"
                   :record post-record
                   :validate true
                   :swapCommit "bafycommit"
                   :swapRecord "bafyrecord"}
                  body)))))
     ;; an explicit nil :swap-record asserts the record does not exist yet
     (let [{:keys [handler requests]} (fake-http/scripted
                                       [(fake-http/json-response {:uri "u" :cid "c"})])]
       (with-redefs [http/handle-request handler]
         (deref (repo/put-record (authed-client)
                                 {:record post-record
                                  :rkey "3jzfcijpj2z2a"
                                  :swap-record nil})
                1000 ::timeout)
         (let [{:keys [body]} (sent (first @requests))]
           (is (contains? body :swapRecord))
           (is (nil? (:swapRecord body))))))
     (is (= "InvalidRequest"
            (:error (deref (repo/put-record (authed-client) {:record post-record})
                           1000 ::timeout))))))

#?(:clj
   (deftest delete-record-test
     (let [{:keys [handler requests]} (fake-http/scripted
                                       [(fake-http/json-response {})])]
       (with-redefs [http/handle-request handler]
         (deref (repo/delete-record (authed-client)
                                    {:collection "app.bsky.feed.post"
                                     :rkey "3jzfcijpj2z2a"
                                     :swap-commit "bafycommit"
                                     :swap-record "bafyrecord"})
                1000 ::timeout)
         (let [{:keys [nsid body]} (sent (first @requests))]
           (is (= "com.atproto.repo.deleteRecord" nsid))
           (is (= {:repo "did:plc:tester"
                   :collection "app.bsky.feed.post"
                   :rkey "3jzfcijpj2z2a"
                   :swapCommit "bafycommit"
                   :swapRecord "bafyrecord"}
                  body)))))
     (is (= "InvalidRequest"
            (:error (deref (repo/delete-record (authed-client) {:rkey "x"})
                           1000 ::timeout))))))

(defn- record-page
  ([records] (record-page records nil))
  ([records cursor]
   (fake-http/json-response (cond-> {:records records}
                              cursor (assoc :cursor cursor)))))

#?(:clj
   (deftest list-records-test
     (let [{:keys [handler requests]} (fake-http/scripted
                                       [(record-page [{:uri "u" :cid "c" :value post-record}] "next")])]
       (with-redefs [http/handle-request handler]
         (let [resp (deref (repo/list-records (authed-client)
                                              {:collection "app.bsky.feed.post"
                                               :limit 10
                                               :cursor "prev"
                                               :reverse true})
                           1000 ::timeout)]
           (is (= "next" (:cursor resp)))
           (is (= 1 (count (:records resp))))
           (let [{:keys [nsid method params]} (sent (first @requests))]
             (is (= "com.atproto.repo.listRecords" nsid))
             (is (= :get method))
             (is (= {:repo "did:plc:tester"
                     :collection "app.bsky.feed.post"
                     :limit "10"
                     :cursor "prev"
                     :reverse "true"}
                    params))))))
     (is (= "InvalidRequest"
            (:error (deref (repo/list-records (authed-client) {})
                           1000 ::timeout))))))

#?(:clj
   (deftest list-all-records-test
     (let [{:keys [handler requests]} (fake-http/scripted
                                       [(record-page [{:uri "1"} {:uri "2"}] "a")
                                        (record-page [{:uri "3"}])])]
       (with-redefs [http/handle-request handler]
         (let [resp (deref (repo/list-all-records (authed-client)
                                                  {:collection "app.bsky.feed.post"})
                           1000 ::timeout)]
           (is (= [{:uri "1"} {:uri "2"} {:uri "3"}] resp))
           (is (= 2 (count @requests)))
           (is (= "a" (get-in (second @requests) [:query-params :cursor]))))))
     ;; :max-pages guard
     (let [{:keys [handler requests]} (fake-http/scripted
                                       (repeat 5 (record-page [{:uri "x"}] "more")))]
       (with-redefs [http/handle-request handler]
         (let [resp (deref (repo/list-all-records (authed-client)
                                                  {:collection "app.bsky.feed.post"
                                                   :max-pages 2})
                           1000 ::timeout)]
           (is (= 2 (count resp)))
           (is (= 2 (count @requests))))))))

#?(:clj
   (deftest record-seq-test
     (let [{:keys [handler]} (fake-http/scripted
                              [(record-page [{:uri "1"} {:uri "2"}] "a")
                               (record-page [{:uri "3"}])])]
       (with-redefs [http/handle-request handler]
         (is (= [{:uri "1"} {:uri "2"} {:uri "3"}]
                (vec (repo/record-seq (authed-client) {:collection "app.bsky.feed.post"}))))))
     (is (= "NotAuthenticated"
            (:error (first (repo/record-seq (anon-client) {:collection "app.bsky.feed.post"})))))))

#?(:clj
   (deftest apply-writes-test
     (let [{:keys [handler requests]} (fake-http/scripted
                                       [(fake-http/json-response {:results []})])]
       (with-redefs [http/handle-request handler]
         (deref (repo/apply-writes (authed-client)
                                   {:writes [{:type :create
                                              :collection "app.bsky.feed.post"
                                              :value post-record}
                                             {:type :update
                                              :collection "app.bsky.feed.post"
                                              :rkey "3jzfcijpj2z2a"
                                              :value post-record}
                                             {:type :delete
                                              :collection "app.bsky.feed.post"
                                              :rkey "3jzfcijpj2z2b"}]
                                    :validate? true
                                    :swap-commit "bafycommit"})
                1000 ::timeout)
         (let [{:keys [nsid body]} (sent (first @requests))]
           (is (= "com.atproto.repo.applyWrites" nsid))
           (is (= "did:plc:tester" (:repo body)))
           (is (true? (:validate body)))
           (is (= "bafycommit" (:swapCommit body)))
           (is (= [{:$type "com.atproto.repo.applyWrites#create"
                    :collection "app.bsky.feed.post"
                    :value post-record}
                   {:$type "com.atproto.repo.applyWrites#update"
                    :collection "app.bsky.feed.post"
                    :rkey "3jzfcijpj2z2a"
                    :value post-record}
                   {:$type "com.atproto.repo.applyWrites#delete"
                    :collection "app.bsky.feed.post"
                    :rkey "3jzfcijpj2z2b"}]
                  (:writes body))))))
     ;; unknown write types are rejected
     (is (= "InvalidRequest"
            (:error (deref (repo/apply-writes (authed-client)
                                              {:writes [{:type :upsert :collection "c" :rkey "r"}]})
                           1000 ::timeout))))
     (is (= "InvalidRequest"
            (:error (deref (repo/apply-writes (authed-client) {:writes []})
                           1000 ::timeout))))))

#?(:clj
   (deftest upload-blob-test
     (let [{:keys [handler requests]} (fake-http/scripted
                                       [(fake-http/json-response
                                         {:blob {:$type "blob"
                                                 :ref {:$link "bafkfake"}
                                                 :mimeType "image/png"
                                                 :size 3}})])
           blob (byte-array [1 2 3])]
       (with-redefs [http/handle-request handler]
         (let [resp (deref (repo/upload-blob (authed-client) blob "image/png")
                           1000 ::timeout)]
           (is (= "image/png" (get-in resp [:blob :mimeType])))
           (let [request (first @requests)]
             (is (clojure.string/ends-with? (:url request) "/xrpc/com.atproto.repo.uploadBlob"))
             ;; the bytes reach the wire untouched, with the encoding as content-type
             (is (identical? blob (:body request)))
             (is (= "image/png" (get-in request [:headers :content-type])))))))
     (is (= "InvalidRequest"
            (:error (deref (repo/upload-blob (authed-client) (byte-array [1]) nil)
                           1000 ::timeout))))))
