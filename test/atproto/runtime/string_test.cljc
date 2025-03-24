(ns atproto.runtime.string-test
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            [clojure.string :as str]
            [atproto.runtime.string :refer :all]))

(deftest test-utf8-length
  (is (= 1 (utf8-length "a")))
  (is (= 1 (utf8-length "~")))
  (is (= 2 (utf8-length "ö")))
  (is (= 2 (utf8-length "ñ")))
  (is (= 2 (utf8-length "©")))
  (is (= 3 (utf8-length "⽘")))
  (is (= 3 (utf8-length "☎")))
  (is (= 4 (utf8-length "𓋓")))
  (is (= 4 (utf8-length "😀")))
  (is (= 25 (utf8-length "👨‍👩‍👧‍👧"))))

(deftest test-grapheme-length
  (is (= 1 (grapheme-length "a")))
  (is (= 1 (grapheme-length "~")))
  (is (= 1 (grapheme-length "ö")))
  (is (= 1 (grapheme-length "ñ")))
  (is (= 1 (grapheme-length "©")))
  (is (= 1 (grapheme-length "⽘")))
  (is (= 1 (grapheme-length "☎")))
  (is (= 1 (grapheme-length "𓋓")))
  (is (= 1 (grapheme-length "😀")))
  (is (= 1 (grapheme-length "👨‍👩‍👧‍👧")))
  (is (= 10 (grapheme-length "a~öñ©⽘☎𓋓😀👨‍👩‍👧‍👧‍"))))
