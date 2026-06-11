(ns atproto.runtime.crypto
  "Cross-platform cryptographic functions for atproto."
  (:require [clojure.string :as str]
            #?@(:cljs [[goog.crypt.base64 :as b64]]))
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
  "bytes -> standard-alphabet base64 string, no padding (atproto `$bytes` form)."
  [b]
  #?(:clj (.encodeToString (.withoutPadding (Base64/getEncoder)) ^bytes b)
     :cljs (b64/encodeByteArray
            (js/Uint8Array. (.-buffer b) (.-byteOffset b) (.-length b))
            (.-NO_PADDING b64/Alphabet))))

(defn base64-decode
  "Standard-alphabet base64 string (padded or unpadded) -> bytes.

  Returns nil if the input is not valid base64."
  [s]
  (when (string? s)
    (let [stripped (str/replace s #"=+$" "")]
      (when (and (re-matches #"[A-Za-z0-9+/]*" stripped)
                 (not= 1 (mod (count stripped) 4)))
        #?(:clj (try
                  (.decode (Base64/getDecoder) ^String stripped)
                  (catch Exception _ nil))
           :cljs (let [u8 (b64/decodeStringToUint8Array stripped)]
                   (js/Int8Array. (.-buffer u8) (.-byteOffset u8) (.-length u8))))))))

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
