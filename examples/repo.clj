(ns repo
  "Writing and reading data in ATProto Data Repositories."
  (:require [clojure.pprint :refer [pprint]]
            [clojure.spec.alpha :as s]
            [atproto.client :as at]
            [atproto.tid :as tid]))

;; ATProto Data Repositories are XRPC services.
;; Schema: https://github.com/bluesky-social/atproto/tree/main/lexicons/com/atproto/repo

(def at-id (System/getenv "AT_IDENTIFIER"))

(def client
  @(at/init {:credentials {:identifier at-id
                           :password (System/getenv "AT_PASSWORD")}}))

;; Describe the repo

(def repo
  @(at/query client
             {:nsid "com.atproto.repo.describeRepo"
              :params {:repo at-id}}))

(println "Collections:")
(doseq [collection (:collections repo)]
  (println "- " collection))

;; Create a new record
;;
;; We can write any valid ATProto data to the repo.

(def record
  {:$type "xyz.statusphere.record"
   :status "💙"
   :createdAt (str (java.time.Instant/now))})

(assert (s/valid? :atproto.data/object record))

(def new-record
  @(at/procedure client
                 {:nsid "com.atproto.repo.createRecord"
                  :body {:repo at-id
                         :collection "xyz.statusphere.status"
                         :rkey (tid/next-tid)
                         ;; The repo may not have the xyz.statusphere Lexicon
                         :validate false
                         :record record}}))

(pprint new-record)

(def rkey (:rkey (s/conform :atproto.lexicon/at-uri (:uri new-record))))

;; Retrieve a record

(def resp
  @(at/query client
             {:nsid "com.atproto.repo.getRecord"
              :params {:repo at-id
                       :collection "xyz.statusphere.status"
                       :rkey rkey}}))

(assert (:uri resp))

;; Upload a blob

(defn slurp-bytes
  [x]
  (with-open [in (clojure.java.io/input-stream x)
              out (java.io.ByteArrayOutputStream.)]
    (clojure.java.io/copy in out)
    (.toByteArray out)))

(def png-bytes (slurp-bytes (clojure.java.io/resource "logo.png")))

(def resp
  @(at/procedure client
                 {:nsid "com.atproto.repo.uploadBlob"
                  :encoding "image/png"
                  :body png-bytes}))

(assert (:blob resp))
(assert (= (count png-bytes) (:size (:blob resp))))
