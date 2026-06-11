(ns atproto.service-auth
  "Mint and verify atproto inter-service JWTs (iss/aud/lxm/exp/jti).

  Service JWTs are short-lived bearer tokens signed directly with an
  atproto signing keypair (ES256/ES256K), used to authenticate requests
  between atproto services. Mirrors the reference implementation in
  packages/xrpc-server/src/auth.ts.

  All async functions follow the SDK convention: trailing kwarg opts
  with :callback/:promise/:channel adapters; errors are
  {:error \"Name\" :message ... :status 401} maps, never thrown."
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.http :as http]
            [atproto.runtime.crypto :as runtime-crypto]
            [atproto.runtime.jwt :as jwt]
            [atproto.runtime.cast :as cast]
            [atproto.crypto :as crypto]
            [atproto.identity :as identity]
            [atproto.lexicon :as lexicon]
            [atproto.xrpc.client :as xrpc-client]))

#?(:clj (set! *warn-on-reflection* true))

(defn- async-opts
  "The async-adapter keys of an options map (see i/platform-async)."
  [opts]
  (select-keys opts [:channel :callback :promise]))

;; -----------------------------------------------------------------------------
;; Claims
;; -----------------------------------------------------------------------------

(defn- did-or-service?
  "True for a DID, or a DID with a single non-empty #serviceId fragment
  (e.g. \"did:web:example.com#atproto_labeler\")."
  [v]
  (and (string? v)
       (if-let [idx (str/index-of v "#")]
         (and (< (inc idx) (count v))
              (not (str/index-of v "#" (inc idx)))
              (s/valid? ::lexicon/did (subs v 0 idx)))
         (s/valid? ::lexicon/did v))))

(s/def ::iss did-or-service?)
(s/def ::aud did-or-service?)
(s/def ::lxm (s/nilable ::lexicon/nsid))
(s/def ::exp pos-int?)
(s/def ::iat pos-int?)
(s/def ::keypair #(satisfies? crypto/Keypair %))
(s/def ::create-jwt-params
  (s/keys :req-un [::iss ::aud ::keypair]
          :opt-un [::lxm ::exp ::iat]))

(def ^:private forbidden-typs
  "JWT `typ` header values that must never be accepted as service tokens:
  OAuth 2.0 access tokens (RFC 9068), @atproto refresh tokens, and DPoP
  proofs (RFC 9449)."
  #{"at+jwt" "refresh+jwt" "dpop+jwt"})

;; -----------------------------------------------------------------------------
;; Minting
;; -----------------------------------------------------------------------------

(defn create-jwt
  "Mint a service JWT.

  `params`:
  :iss      (required) issuer DID, optionally with #serviceId fragment.
  :aud      (required) audience DID (or did#serviceId).
  :keypair  (required) atproto.crypto signing keypair (provides alg + sign).
  :lxm      NSID of the method this token is bound to. Strongly recommended.
  :exp      expiration, seconds since epoch. Default: iat + 60.
  :iat      issued-at, seconds since epoch. Default: now.

  Async; yields {:token \"<jwt>\"} or {:error ...}.
  A fresh 16-byte hex :jti claim is generated for every token."
  [params & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))
        {:keys [iss aud keypair lxm exp iat]} params]
    (if (not (s/valid? ::create-jwt-params params))
      (cb {:error "InvalidServiceJwtParams"
           :message (s/explain-str ::create-jwt-params params)})
      (let [iat (or iat (runtime-crypto/now))
            exp (or exp (+ iat 60))
            jti (runtime-crypto/hex-encode (runtime-crypto/random-bytes 16))
            claims (cond-> {:iat iat
                            :iss iss
                            :aud aud
                            :exp exp}
                     lxm (assoc :lxm lxm)
                     true (assoc :jti jti))]
        (jwt/sign keypair {:typ "JWT"} claims
                  :callback (fn [resp]
                              (cb (if (:error resp)
                                    resp
                                    {:token resp}))))))
    val))

(defn auth-headers
  "Async; yields {:headers {:authorization \"Bearer <jwt>\"}} for `create-jwt` params."
  [params & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (create-jwt params
                :callback (fn [{:keys [error token] :as resp}]
                            (cb (if error
                                  resp
                                  {:headers {:authorization (str "Bearer " token)}}))))
    val))

;; -----------------------------------------------------------------------------
;; Verification
;; -----------------------------------------------------------------------------

(defn- auth-error
  [error message]
  {:error error :message message :status 401})

(defn- with-401
  "Ensure an error map carries an HTTP status (default 401)."
  [error-map]
  (cond-> error-map
    (not (:status error-map)) (assoc :status 401)))

(def ^:private bad-jwt (auth-error "BadJwt" "poorly formatted jwt"))

(defn- claims-shape-error
  "Structural validation of the service-JWT payload (reference parsePayload)."
  [{:keys [iss aud exp lxm] :as claims}]
  (when (or (not (string? iss))
            (not (string? aud))
            (not (number? exp))
            (and (some? lxm) (not (string? lxm))))
    bad-jwt))

(defn- policy-error
  "The pre-signature policy checks, in reference order. Returns an error
  map or nil. `aud`/`lxm` are the expected values; nil skips the check."
  [{:keys [header claims]} {:keys [aud lxm now]}]
  (let [now (or now (runtime-crypto/now))]
    (cond
      (not (string? (:alg header)))
      bad-jwt

      (contains? forbidden-typs (:typ header))
      (auth-error "BadJwtType" (str "Invalid jwt type " (pr-str (:typ header))))

      (claims-shape-error claims)
      (claims-shape-error claims)

      (> now (:exp claims))
      (auth-error "JwtExpired" "jwt expired")

      (and (some? aud) (not= aud (:aud claims)))
      (auth-error "BadJwtAudience" "jwt audience does not match service did")

      (and (some? lxm) (not= lxm (:lxm claims)))
      (auth-error "BadJwtLexiconMethod"
                  (if (some? (:lxm claims))
                    (str "bad jwt lexicon method (\"lxm\"). must match: " lxm)
                    (str "missing jwt lexicon method (\"lxm\"). must match: " lxm)))

      (not (did-or-service? (:iss claims)))
      (auth-error "BadJwtIss" "jwt iss is not a valid did"))))

(defn- check-signature
  "Run the signature check; cb receives true, false, or an error map
  already translated to BadJwtSignature."
  [verify-signature {:keys [header signing-input signature]} did-key cb]
  (verify-signature did-key signature signing-input
                    :alg (:alg header)
                    :allow-malleable? true
                    :callback
                    (fn [result]
                      (cb (if (map? result)
                            (auth-error "BadJwtSignature" "could not verify jwt signature")
                            result)))))

(defn verify-jwt
  "Verify a service JWT string and return its claims.

  `opts-map`:
  :aud              own service DID; nil skips the audience check.
  :lxm              expected method NSID; nil skips the lxm check.
  :get-signing-key  (required) (fn [iss force-refresh? cb]) yielding
                    {:key \"did:key:...\"} or {:error ...}. Called a second
                    time with force-refresh? true when signature
                    verification fails (key rotation).
  :verify-signature optional override for the raw signature check
                    (tests/stubs). Same signature as the default,
                    atproto.crypto/verify-did-sig:
                    (fn [did-key sig-bytes msg-bytes & {:keys [alg
                     allow-malleable? callback]}]) yielding boolean or
                    {:error ...}. Called with :allow-malleable? true
                    (service-JWT parity).

  Async; yields the payload {:iss :aud :exp :iat :lxm :jti ...} on success,
  or {:error <Name> :message ... :status 401} where <Name> is one of:
  BadJwt, BadJwtType, JwtExpired, BadJwtAudience, BadJwtLexiconMethod,
  BadJwtIss, BadJwtSignature (or the :get-signing-key fn's own error,
  e.g. UntrustedIss)."
  [jwt {:keys [get-signing-key verify-signature] :as opts-map} & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))
        verify-signature (or verify-signature crypto/verify-did-sig)
        parsed (jwt/parse jwt)]
    (cond
      (or (nil? parsed) (:error parsed))
      (cb bad-jwt)

      :else
      (if-let [error (policy-error parsed opts-map)]
        (cb error)
        (let [claims (:claims parsed)
              fail (auth-error "BadJwtSignature"
                               "jwt signature does not match jwt issuer")]
          (get-signing-key
           (:iss claims) false
           (fn [{:keys [error key] :as resp}]
             (if error
               (cb (with-401 resp))
               (check-signature
                verify-signature parsed key
                (fn [result]
                  (cond
                    (true? result) (cb claims)
                    (map? result)  (cb result)
                    :else
                    ;; The signature failed against the cached key; re-resolve
                    ;; with force-refresh in case of a recent key rotation and
                    ;; retry once, only if the key actually changed.
                    (get-signing-key
                     (:iss claims) true
                     (fn [{:keys [error] fresh-key :key :as resp}]
                       (cond
                         error (cb (with-401 resp))
                         (= fresh-key key) (cb fail)
                         :else (check-signature
                                verify-signature parsed fresh-key
                                (fn [result]
                                  (cond
                                    (true? result) (cb claims)
                                    (map? result)  (cb result)
                                    :else          (cb fail))))))))))))))))
    val))

;; -----------------------------------------------------------------------------
;; DID-document signing-key resolution
;; -----------------------------------------------------------------------------

(defn- verification-material
  "The verification method in the DID doc whose id ends in #<key-id>,
  or nil (reference getVerificationMaterial)."
  [did-doc key-id]
  (->> (:verificationMethod did-doc)
       (filter (fn [{:keys [id publicKeyMultibase]}]
                 (and (string? id)
                      (str/ends-with? id (str "#" key-id))
                      (string? publicKeyMultibase))))
       (first)))

(defn- multibase-material->did-key
  "did:key string for a DID-doc verification method, or {:error ...}.
  Composition per overview §4.2 / reference getDidKeyFromMultibase."
  [{:keys [type publicKeyMultibase]}]
  (case type
    "Multikey"
    (let [{:keys [error alg bytes] :as parsed} (crypto/parse-multikey publicKeyMultibase)]
      (if error parsed (crypto/pubkey->did-key alg bytes)))

    ("EcdsaSecp256r1VerificationKey2019" "EcdsaSecp256k1VerificationKey2019")
    (let [alg (if (= type "EcdsaSecp256r1VerificationKey2019") "ES256" "ES256K")
          bytes (crypto/multibase->bytes publicKeyMultibase)]
      (if (map? bytes) bytes (crypto/pubkey->did-key alg bytes)))

    {:error "UnsupportedKeyType"
     :message (str "Unsupported verification method type: " (pr-str type))}))

(defn did-signing-key-resolver
  "Build a :get-signing-key fn backed by atproto.identity DID resolution.

  Resolves the issuer's DID document and extracts the did:key for the
  verification method `#atproto` (or `#atproto_label` when iss has the
  #atproto_labeler service fragment), per the reference PDS.

  `opts-map`:
  :allowed-issuers  optional collection of permitted iss values (incl.
                    fragments); others yield {:error \"UntrustedIss\"
                    :status 401}.
  :resolve-did      optional resolution override (tests/stubs); same
                    async signature as atproto.identity/resolve-did
                    (the default). The :force-refresh kwarg is forwarded;
                    today's resolver has no cache and ignores it, and the
                    WS-06 cache-aware behavior is picked up transparently."
  [{:keys [allowed-issuers resolve-did] :as opts-map}]
  (let [resolve-did (or resolve-did identity/resolve-did)
        allowed (when allowed-issuers (set allowed-issuers))]
    (fn [iss force-refresh? cb]
      (if (and allowed (not (contains? allowed iss)))
        (cb (auth-error "UntrustedIss" "Untrusted issuer"))
        (let [[did service-id] (str/split iss #"#" 2)
              key-id (if (= "atproto_labeler" service-id)
                       "atproto_label"
                       "atproto")]
          (resolve-did did
                       :force-refresh (boolean force-refresh?)
                       :callback
                       (fn [{:keys [error did-doc] :as resp}]
                         (cb (if error
                               (auth-error "AuthenticationRequired"
                                           "could not resolve iss did")
                               (let [material (verification-material did-doc key-id)
                                     did-key (when material
                                               (multibase-material->did-key material))]
                                 (if (or (nil? did-key) (:error did-key))
                                   (auth-error "AuthenticationRequired"
                                               "missing or bad key in did doc")
                                   {:key did-key})))))))))))

;; -----------------------------------------------------------------------------
;; Client side
;; -----------------------------------------------------------------------------

(defn get-service-auth
  "Ask the user's PDS to mint a service token on their behalf
  (com.atproto.server.getServiceAuth).

  `client` is an authenticated atproto.client/xrpc client.
  `params`: {:aud ... :lxm ... :exp ...} per the lexicon.
  Async; yields {:token \"...\"} or the XRPC error (e.g. BadExpiration)."
  [client params & {:as opts}]
  (let [[cb val] (i/platform-async (async-opts opts))]
    (xrpc-client/query client
                       {:nsid "com.atproto.server.getServiceAuth"
                        :params params}
                       :callback cb)
    val))

(defn- request-nsid
  "The NSID from an /xrpc/<nsid> request URL, or nil."
  [url]
  (when-let [path (:path (http/parse-url url))]
    (when (str/starts-with? path "/xrpc/")
      (not-empty (subs path (count "/xrpc/"))))))

(defn- session-auth-interceptor
  [{:keys [iss aud keypair] :as sess}]
  {::i/name ::auth-interceptor
   ::i/enter (fn [{:keys [::i/request] :as ctx}]
               (create-jwt {:iss iss
                            :aud aud
                            :keypair keypair
                            :lxm (request-nsid (:url request))}
                           :callback
                           (fn [{:keys [error token] :as resp}]
                             (i/continue
                              (if error
                                (assoc ctx ::i/response resp)
                                (assoc-in ctx
                                          [::i/request :headers :authorization]
                                          (str "Bearer " token))))))
               nil)})

(defn- session-refresh-token
  "WS-01 Session contract: cb is called exactly once. There is no refresh
  state to update — every request mints a fresh short-lived token — so the
  session itself is the \"refreshed\" session and the retried request
  re-mints."
  [sess cb]
  (cb sess))

(defn session
  "An xrpc-client Session that authenticates requests with self-minted
  service JWTs (for services that hold their own signing key).

  config:
  :iss      issuer DID (this service).
  :keypair  atproto.crypto signing keypair.
  :aud      audience DID of the target service.
  :service  target service URL (becomes the session :pds).

  The auth interceptor derives :lxm from the request URL's /xrpc/<nsid>
  path and mints a fresh short-lived token per request."
  [{:keys [iss aud keypair service] :as config}]
  (with-meta
    {:iss iss
     :aud aud
     :keypair keypair
     :pds service}
    {`xrpc-client/auth-interceptor session-auth-interceptor
     `xrpc-client/refresh-token session-refresh-token}))
