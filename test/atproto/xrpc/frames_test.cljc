(ns atproto.xrpc.frames-test
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            [atproto.runtime.json :as json]
            [atproto.runtime.bytes :as bytes]
            [atproto.data.cbor :as cbor]
            [atproto.xrpc.frames :as frames]
            #?(:clj [clojure.java.io :as io])))

(def fixtures
  #?(:clj (json/read-str (slurp (io/resource "atproto/xrpc/frame_fixtures.json")))))

(defn- fixture-bytes
  ^bytes [fixture]
  (byte-array (map unchecked-byte (:bytes fixture))))

(defn- concat-bytes
  ^bytes [& bs]
  (byte-array (mapcat seq bs)))

(deftest message-frame-fixture-test
  (let [{:keys [header body] :as fixture} (:message_frame fixtures)
        expected (fixture-bytes fixture)]
    (testing "encode matches the reference bytes exactly"
      (is (bytes/eq? expected
                     (frames/encode {:op 1 :t (:t header) :body body}))))
    (testing "message-frame lifts :$type into the header"
      (is (bytes/eq? expected
                     (frames/encode (frames/message-frame
                                     (assoc body :$type "com.example.stream#d")
                                     :nsid "com.example.stream")))))
    (testing "decode inverts encode"
      (is (= {:op 1 :t "#d" :body body}
             (frames/decode expected))))))

(deftest error-frame-fixture-test
  (let [{:keys [body] :as fixture} (:error_frame fixtures)
        expected (fixture-bytes fixture)]
    (testing "encode matches the reference bytes exactly"
      (is (bytes/eq? expected
                     (frames/encode (frames/error-frame (:error body) (:message body))))))
    (testing "decode inverts encode"
      (is (= {:op -1 :body body}
             (frames/decode expected))))))

(deftest type-lifting-test
  (testing "compresses #frag when the nsid matches"
    (is (= {:op 1 :t "#commit" :body {:a 1}}
           (frames/message-frame {:$type "com.example.stream#commit" :a 1}
                                 :nsid "com.example.stream"))))
  (testing "compresses a bare #frag $type"
    (is (= {:op 1 :t "#commit" :body {:a 1}}
           (frames/message-frame {:$type "#commit" :a 1}
                                 :nsid "com.example.stream"))))
  (testing "keeps the full $type when the nsid differs"
    (is (= {:op 1 :t "com.other.stream#commit" :body {:a 1}}
           (frames/message-frame {:$type "com.other.stream#commit" :a 1}
                                 :nsid "com.example.stream"))))
  (testing "no :t without a string $type"
    (is (= {:op 1 :body {:a 1}}
           (frames/message-frame {:a 1} :nsid "com.example.stream")))
    (is (= {:op 1 :body [1 2 3]}
           (frames/message-frame [1 2 3] :nsid "com.example.stream")))))

(deftest decode-rejection-test
  (let [message (fixture-bytes (:message_frame fixtures))
        header-only #?(:clj (byte-array (take (:header_length (:message_frame fixtures))
                                              (seq message))))]
    (testing "not CBOR at all"
      (is (= "InvalidFrame" (:error (frames/decode #?(:clj (.getBytes "some utf8 bytes" "UTF-8"))))))
      (is (= "InvalidFrame" (:error (frames/decode (byte-array 0))))))
    (testing "unknown header op"
      (is (= "InvalidFrame"
             (:error (frames/decode (concat-bytes (cbor/encode {:op -2})
                                                  (cbor/encode {:a "b"})))))))
    (testing "missing body"
      (is (= "InvalidFrame" (:error (frames/decode header-only)))))
    (testing "too many CBOR data items"
      (is (= "InvalidFrame"
             (:error (frames/decode (concat-bytes message
                                                  (cbor/encode {:d "e"})))))))
    (testing "invalid error frame body"
      (is (= "InvalidFrame"
             (:error (frames/decode (concat-bytes (cbor/encode {:op -1})
                                                  (cbor/encode {:blah 1})))))))))

(deftest encode-rejection-test
  (testing "invalid op throws"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (frames/encode {:op 2 :body {}})))))
