(ns atproto.lexicon.loading-test
  "Bundled-lexicon resource loading: manifest consistency, full registration,
  and jar-safe loading."
  (:require [clojure.test :refer :all]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [atproto.runtime.json :as json]
            [atproto.lexicon :as lexicon])
  (:import [java.io File]
           [java.net URL URLClassLoader]
           [java.util.jar JarOutputStream]
           [java.util.zip ZipEntry]))

(deftest manifest-matches-tree-test
  (let [manifest (edn/read-string (slurp (io/resource "lexicons/manifest.edn")))
        root (io/file (io/resource "lexicons"))
        root-path (.getPath root)
        on-disk (->> (file-seq root)
                     (filter #(str/ends-with? (.getName ^File %) ".json"))
                     (map #(subs (.getPath ^File %) (inc (count root-path))))
                     set)]
    (is (= "https://github.com/bluesky-social/atproto" (:source manifest)))
    (is (re-matches #"[0-9a-f]{40}" (:commit manifest)))
    (is (string? (:fetched manifest)))
    (testing "the manifest :files list matches the on-disk JSON tree exactly"
      (is (= on-disk (set (:files manifest))))
      (is (apply distinct? (:files manifest))))))

(deftest load-and-register-all-test
  (let [lex (lexicon/load-resources! "lexicons")]
    (is (<= 251 (count lex)))
    (is (contains? lex "com.atproto.lexicon.schema"))
    (is (contains? lex "com.atproto.repo.getRecord"))
    (is (contains? lex "app.bsky.feed.post"))
    (is (nil? (lexicon/register-specs! lex)))
    (testing "registered schemas produce validators"
      (binding [lexicon/*schema-validate* true]
        (is (fn? (lexicon/request-spec-key "com.atproto.repo.getRecord")))
        (is (fn? (lexicon/response-spec-key "com.atproto.repo.getRecord"))))
      (is (fn? (lexicon/registered-validator :app.bsky.feed/post)))
      (is (= "tid" (get-in (lexicon/registered-schema "app.bsky.feed.post")
                           [:defs :main :key]))))
    (testing "a known-good record validates against the registered schema"
      (let [post {:$type "app.bsky.feed.post"
                  :text "hello world"
                  :createdAt "2026-06-11T00:00:00Z"}]
        (is ((lexicon/registered-validator :app.bsky.feed/post) post))
        (is (not ((lexicon/registered-validator :app.bsky.feed/post)
                  (dissoc post :text))))))))

;; expanding at compile time of this namespace (top level, so the emitted
;; do-forms compile separately) and evaluating registers the bundled schemas
(lexicon/embed-resources! "lexicons")

(deftest embed-resources-expansion-test
  ;; the macro reads the resources on the compiling JVM and emits literal
  ;; schema data + registration calls: no runtime IO, no eval (cljs-safe)
  (let [[do-op & forms] (macroexpand-1 `(lexicon/embed-resources! "lexicons"))
        registrations (butlast forms)]
    (is (= 'do do-op))
    (is (= 251 (count registrations)))
    (is (every? (fn [[register-op [lexicon-op [quoted-schema]]]]
                  (and (= `lexicon/register-specs! register-op)
                       (= `lexicon/lexicon lexicon-op)
                       (= 'quote (first quoted-schema))
                       (= 1 (:lexicon (second quoted-schema)))))
                registrations))))

(deftest embed-resources-registration-test
  (is (fn? (lexicon/registered-validator :app.bsky.actor/profile)))
  (is (fn? (lexicon/registered-validator :com.atproto.lexicon/schema))))

(deftest jar-loading-test
  (let [jar-file (File/createTempFile "atproto-lexicons" ".jar")
        schema {:lexicon 1
                :id "com.example.jarTest"
                :defs {:main {:type "object"
                              :properties {:name {:type "string"}}}}}]
    (try
      (with-open [out (JarOutputStream. (io/output-stream jar-file))]
        (let [add-entry! (fn [path ^String content]
                           (.putNextEntry out (ZipEntry. ^String path))
                           (let [bytes (.getBytes content "UTF-8")]
                             (.write out bytes 0 (alength bytes)))
                           (.closeEntry out))]
          (add-entry! "jar-lexicons/manifest.edn"
                      (pr-str {:source "test"
                               :commit "0000000000000000000000000000000000000000"
                               :fetched "2026-06-11"
                               :files ["com/example/jarTest.json"]}))
          (add-entry! "jar-lexicons/com/example/jarTest.json"
                      (json/write-str schema))))
      (let [thread (Thread/currentThread)
            original (.getContextClassLoader thread)]
        (try
          (.setContextClassLoader thread
                                  (URLClassLoader.
                                   (into-array URL [(.toURL (.toURI jar-file))])
                                   original))
          (let [lex (lexicon/load-resources! "jar-lexicons")]
            (is (= {"com.example.jarTest" schema} lex)))
          (finally
            (.setContextClassLoader thread original))))
      (finally
        (.delete jar-file)))))
