(ns atproto.oauth.provider.dpop
  "Server-side DPoP proof verification (RFC 9449).

  Mirror of the client in atproto.oauth.client.dpop, port of the
  reference DpopManager (oauth-provider/src/dpop/dpop-manager.ts):
  proof JWT checks (typ/alg/embedded JWK/signature/jti replay/htm/htu
  normalization/iat age/ath binding) and rotating HMAC-seeded server
  nonces with a use_dpop_nonce challenge."
  (:require [clojure.string :as str]
            [atproto.runtime.crypto :as crypto]
            [atproto.runtime.jwt :as jwt]
            [atproto.runtime.http :as http]
            [atproto.oauth.provider.store :as store])
  #?(:clj (:import [javax.crypto Mac]
                   [javax.crypto.spec SecretKeySpec]
                   [java.nio ByteBuffer])))

;; Maximum age of a proof's iat, and jti replay-protection window.
(def max-proof-age-s 300)
;; Nonce rotation interval; the previous, current, and next windows are
;; accepted (reference dpop-nonce.ts).
(def default-rotation-interval-ms (* 10 60 1000))

(defn create
  "Create a DPoP verifier.

  opts:
    :replay-store          (required) atproto.oauth.provider.store/ReplayStore
    :secret                32 random bytes seeding the nonce rotation
                           (generated when absent)
    :rotation-interval-ms  nonce rotation period (default 10 minutes)"
  [{:keys [secret rotation-interval-ms replay-store]}]
  {:secret (or secret (crypto/random-bytes 32))
   :rotation-interval-ms (or rotation-interval-ms default-rotation-interval-ms)
   :replay-store replay-store})

;; -----------------------------------------------------------------------------
;; Rotating nonces
;; -----------------------------------------------------------------------------

#?(:clj
   (defn- hmac-sha256
     ^bytes [^bytes secret ^bytes msg]
     (let [mac (Mac/getInstance "HmacSHA256")]
       (.init mac (SecretKeySpec. secret "HmacSHA256"))
       (.doFinal mac msg))))

#?(:clj
   (defn- nonce-for-counter
     [{:keys [secret]} counter]
     (crypto/base64url-encode
      (hmac-sha256 secret (.array (doto (ByteBuffer/allocate 8)
                                    (.putLong (long counter))))))))

#?(:clj
   (defn- nonce-counter
     [{:keys [rotation-interval-ms]}]
     (quot (System/currentTimeMillis) rotation-interval-ms)))

(defn next-nonce
  "The current nonce value for DPoP-Nonce response headers."
  [verifier]
  #?(:clj (nonce-for-counter verifier (nonce-counter verifier))))

(defn- valid-nonce?
  "Whether the nonce matches the previous, current, or next window."
  [verifier nonce]
  #?(:clj (let [counter (nonce-counter verifier)]
            (boolean (some #(= nonce (nonce-for-counter verifier (+ counter %)))
                           [0 -1 1])))))

;; -----------------------------------------------------------------------------
;; Proof verification
;; -----------------------------------------------------------------------------

(defn jwk-thumbprint
  "RFC 7638 SHA-256 JWK thumbprint, base64url (the DPoP jkt)."
  [jwk]
  #?(:clj (str (.computeThumbprint (jwt/clj->jwk jwk)))))

(defn- invalid
  [message]
  {:error "InvalidDpopProof" :message message})

(defn- use-nonce
  [verifier message]
  {:error "UseDpopNonce" :message message :dpop-nonce (next-nonce verifier)})

(defn- normalize-htu
  "htu comparison form: lowercase scheme://host[:port]path, query and
  fragment stripped (RFC 9449 §4.3)."
  [url]
  (when-let [{:keys [protocol host port path]} (when (string? url)
                                                 (http/parse-url url))]
    (str (str/lower-case (name protocol)) "://"
         (str/lower-case host)
         (when port (str ":" port))
         path)))

(def ^:private private-jwk-members #{:d :p :q :dp :dq :qi :k :oth})

(defn check-proof
  "Validate a DPoP proof JWT against the request it accompanies.

  request:
    :proof            the DPoP header value (compact JWS)
    :method           HTTP method (keyword or string)
    :url              full request URL
    :access-token     when bound to an access token: the token string
                      (enforces the ath claim)
    :nonce-required?  when true, a missing/invalid nonce claim yields the
                      UseDpopNonce challenge

  Sync. Success: {:jkt thumbprint :jti jti :jwk jwk}
  Errors: {:error \"InvalidDpopProof\" :message ...} or
          {:error \"UseDpopNonce\" :dpop-nonce next}."
  [verifier {:keys [proof method url access-token nonce-required?]}]
  (let [{:keys [header claims] :as parsed} (when (string? proof) (jwt/parse proof))]
    (cond
      (nil? parsed)
      (invalid "Missing DPoP proof.")

      (:error parsed)
      (invalid (:message parsed))

      (not= "dpop+jwt" (:typ header))
      (invalid "DPoP proof typ must be dpop+jwt.")

      (not (contains? #{"ES256" "ES256K"} (:alg header)))
      (invalid (str "Unsupported DPoP proof alg: " (pr-str (:alg header))))

      (not (map? (:jwk header)))
      (invalid "DPoP proof must embed its public JWK in the header.")

      (some private-jwk-members (keys (:jwk header)))
      (invalid "DPoP proof JWK must not contain private key material.")

      :else
      (let [jwk (:jwk header)
            ;; malleable: proofs come from standard JOSE stacks, which do
            ;; not low-S-normalize ECDSA signatures (Nimbus included)
            verified #?(:clj @(jwt/verify proof {:jwk jwk}
                                          :allow-malleable? true
                                          :leeway 30)
                        :cljs {:error "NotImplemented"})
            now (crypto/now)
            {:keys [jti htm htu iat nonce ath]} claims]
        (cond
          (:error verified)
          (invalid (str "Invalid DPoP proof: " (or (:message verified) (:error verified))))

          (not (and (string? jti) (seq jti) (<= (count jti) 255)))
          (invalid "DPoP proof requires a jti claim.")

          (not (number? iat))
          (invalid "DPoP proof requires an iat claim.")

          (< iat (- now max-proof-age-s))
          (invalid "DPoP proof iat is too old.")

          (not= htm (some-> method name str/upper-case))
          (invalid "DPoP proof htm does not match the request method.")

          (or (nil? (normalize-htu htu))
              (not= (normalize-htu htu) (normalize-htu url)))
          (invalid "DPoP proof htu does not match the request URL.")

          (and (nil? nonce) nonce-required?)
          (use-nonce verifier "Authorization server requires nonce in DPoP proof.")

          (and (some? nonce) (not (valid-nonce? verifier nonce)))
          (use-nonce verifier "DPoP proof nonce is not recent enough.")

          (and access-token
               (not= ath (crypto/base64url-encode (crypto/sha256 access-token))))
          (invalid "DPoP proof ath does not match the access token.")

          (and ath (not access-token))
          (invalid "DPoP proof has an unexpected ath claim.")

          (not (store/unique? (:replay-store verifier) "dpop" jti
                              (+ now max-proof-age-s)))
          (invalid "DPoP proof jti was already used.")

          :else
          {:jkt (jwk-thumbprint jwk)
           :jti jti
           :jwk jwk})))))
