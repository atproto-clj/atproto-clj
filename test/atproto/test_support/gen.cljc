(ns atproto.test-support.gen
  "Shared test.check generators for atproto identifiers."
  (:require [clojure.string :as str]
            [clojure.test.check.generators :as gen]))

(def lower-alpha "abcdefghijklmnopqrstuvwxyz")
(def base32-chars "abcdefghijklmnopqrstuvwxyz234567")
(def base58-chars "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz")
(def base64url-chars
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")

(defn string-of
  "Generator for a string of min..max characters drawn from chars."
  [chars min-len max-len]
  (gen/fmap str/join (gen/vector (gen/elements (seq chars)) min-len max-len)))

(def label
  "A lowercase DNS label starting with a letter."
  (gen/fmap (fn [[c cs]] (str c cs))
            (gen/tuple (gen/elements (seq lower-alpha))
                       (string-of (str lower-alpha "0123456789") 0 8))))

(def hostname
  "Two-or-more-label lowercase hostname with an alphabetic TLD
  (also valid as an atproto handle)."
  (gen/fmap (fn [[labels tld]] (str/join "." (conj labels tld)))
            (gen/tuple (gen/vector label 1 2)
                       (string-of lower-alpha 2 6))))

(def plc-msid
  "24 characters of lowercase RFC 4648 base32."
  (string-of base32-chars 24 24))

(def did-plc
  (gen/fmap #(str "did:plc:" %) plc-msid))

(def did-key
  "Structurally did:key-shaped strings (z-multibase payload); not
  necessarily decodable key material."
  (gen/fmap #(str "did:key:z" %) (string-of base58-chars 8 44)))

(def base64url
  "Unpadded base64url-alphabet strings."
  (string-of base64url-chars 4 86))
