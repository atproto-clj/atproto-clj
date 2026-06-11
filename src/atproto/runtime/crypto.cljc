(ns atproto.runtime.crypto
  "Cross-platform cryptographic functions for atproto."
  (:require [clojure.string :as str])
  #?(:clj (:import [java.util Base64 HexFormat]
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
  "SHA-256 digest of the input as a byte array.

  Accepts a byte array, or a string which is UTF-8 encoded first."
  [bytes-or-str]
  #?(:clj (.digest (MessageDigest/getInstance "SHA-256")
                   (if (string? bytes-or-str)
                     (.getBytes ^String bytes-or-str StandardCharsets/UTF_8)
                     ^bytes bytes-or-str))))

(defn hex-encode
  "Lowercase hex string of the byte array."
  [^bytes b]
  #?(:clj (.formatHex (HexFormat/of) b)))

(defn hex-decode
  "Bytes from a hex string, or nil if invalid."
  [s]
  #?(:clj (try (.parseHex (HexFormat/of) ^String s) (catch Exception _))))

(defn sha256-hex
  "Lowercase hex string of (sha256 bytes-or-str)."
  [bytes-or-str]
  (hex-encode (sha256 bytes-or-str)))

(defn base64-encode
  "Standard base64 (RFC 4648 §4) string of the byte array, without padding
  (required by the atproto $bytes form). base64-decode accepts both padded
  and unpadded input."
  [^bytes b]
  #?(:clj (.encodeToString (.withoutPadding (Base64/getEncoder)) b)))

(defn base64-decode
  [s]
  #?(:clj (try (.decode (Base64/getDecoder) ^String s) (catch Exception _))))

(defn base64url-encode
  "bytes -> url-safe base64 string."
  [^bytes bytes]
  #?(:clj (str (Base64URL/encode bytes))))

(defn base64url-decode
  "Decode a base64url string (padding optional) to bytes, or nil if invalid."
  [s]
  #?(:clj (try (.decode (Base64/getUrlDecoder) ^String s) (catch Exception _))))

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
