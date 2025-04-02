(ns atproto.runtime.crypto
  "Cross-platform cryptographic functions for atproto."
  (:require [clojure.string :as str])
  #?(:clj (:import [java.util Base64]
                   [com.nimbusds.jose.util Base64URL]
                   [java.security SecureRandom MessageDigest]
                   [java.nio.charset StandardCharsets])))

#?(:clj (set! *warn-on-reflection* true))

(defn now
  "Number of seconds since epoch."
  []
  #?(:clj (int (/ (System/currentTimeMillis) 1000))))

(defn random-bytes
  "Random byte array of the given size."
  [size]
  #?(:clj (let [seed (byte-array size)]
            (.nextBytes (SecureRandom.) seed)
            seed)))

(defn sha256
  "sha256 of the bytes"
  [s]
  #?(:clj (.digest (MessageDigest/getInstance "SHA-256")
                   (.getBytes ^String s StandardCharsets/UTF_8))))

(defn base64-encode
  [s]
  #?(:clj (try (.encodeToString (Base64/getEncoder) ^String s) (catch Exception _))))

(defn base64-decode
  [s]
  #?(:clj (try (.decode (Base64/getDecoder) ^String s) (catch Exception _))))

(defn base64url-encode
  "bytes -> url-safe base64 string."
  [^bytes bytes]
  #?(:clj (str (Base64URL/encode bytes))))

(defn generate-pkce
  "Proof Key for Code Exchange (S256) with a verifier of the given size."
  [size]
  (let [verifier (base64url-encode (random-bytes size))]
    {:verifier verifier
     :challenge (base64url-encode (sha256 verifier))
     :method "S256"}))

(defn generate-nonce
  "Generate a random base64 url-safe string."
  [size]
  (base64url-encode (random-bytes size)))
