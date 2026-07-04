(ns atproto.tap.auth
  "Tap admin authentication: HTTP Basic with the fixed username \"admin\"
  and a shared admin password.

  Port of @atproto/tap util.ts (commit b9ef557)."
  (:require [clojure.string :as str])
  #?(:clj (:import [java.nio.charset StandardCharsets]
                   [java.security MessageDigest]
                   [java.util Base64])))

#?(:clj (set! *warn-on-reflection* true))

(defn- b64-encode
  "utf-8 string -> standard base64 with padding (HTTP Basic form)."
  [s]
  #?(:clj (.encodeToString (Base64/getEncoder)
                           (.getBytes ^String s StandardCharsets/UTF_8))
     ;; cljs stub: js/btoa only handles latin1 code points.
     :cljs (js/btoa s)))

(defn- b64-decode
  "base64 string -> utf-8 string, or nil when undecodable."
  [s]
  #?(:clj (try
            (String. (.decode (Base64/getDecoder) ^String s)
                     StandardCharsets/UTF_8)
            (catch Exception _ nil))
     :cljs (try (js/atob s) (catch :default _ nil))))

(defn format-admin-auth-header
  "password -> \"Basic \" + base64(\"admin:\" + password)."
  [password]
  (str "Basic " (b64-encode (str "admin:" password))))

(defn parse-admin-auth-header
  "Extract the admin password from a Basic authorization header value.

  Returns the password string, or {:error \"InvalidAuthHeader\" :message ...}
  when the header is missing, not Basic, undecodable, or the username is not
  exactly \"admin\" (port of tap/src/util.ts:5-14)."
  [header]
  (let [invalid (fn [msg] {:error "InvalidAuthHeader" :message msg})]
    (cond
      (not (string? header))
      (invalid "Missing authorization header.")

      (not (str/starts-with? header "Basic "))
      (invalid "Expected a Basic authorization header.")

      :else
      (let [decoded (b64-decode (subs header (count "Basic ")))]
        (if (nil? decoded)
          (invalid "Authorization header credentials are not valid base64.")
          (let [idx (str/index-of decoded ":")]
            (if (or (nil? idx)
                    (not= "admin" (subs decoded 0 idx)))
              (invalid "Expected Basic credentials for the \"admin\" user.")
              (subs decoded (inc idx)))))))))

(defn admin-auth-valid?
  "Whether the authorization header value carries the expected admin
  password. Timing-safe comparison on the JVM (MessageDigest/isEqual over
  the utf-8 bytes); any parse error -> false."
  [expected-password header]
  (let [password (parse-admin-auth-header header)]
    (if (or (map? password) (not (string? expected-password)))
      false
      #?(:clj (MessageDigest/isEqual
               (.getBytes ^String expected-password StandardCharsets/UTF_8)
               (.getBytes ^String password StandardCharsets/UTF_8))
         :cljs (= expected-password password)))))
