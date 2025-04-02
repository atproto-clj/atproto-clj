(ns atproto.oauth.client.dpop
  "DPoP implementation for the OAuth client."
  (:require [clojure.string :as str]
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.jwt :as jwt]
            [atproto.runtime.http :as http]
            [atproto.runtime.crypto :as crypto]
            [atproto.runtime.cast :as cast]))

;; host -> nonce
(defonce nonce-store (atom {}))

;; atproto clients and servers must support ES256
(def default-alg "ES256")

(defn generate-key []
  (jwt/generate-jwk {:alg default-alg}))

(defn- dpop-wrapper
  [{:keys [iss dpop-key]}]
  (fn [{:keys [::i/request] :as ctx}]
    (let [{:keys [server-nonce method url headers]} request
          host (:host (http/parse-url url))
          nonce (or server-nonce
                    (get @nonce-store host))
          ath (when-let [auth (:authorization headers)]
                (when (str/starts-with? auth "DPoP ")
                  (crypto/base64url-encode
                   (crypto/sha256
                    (subs auth 5)))))
          _ (cast/dev {::claims {:iss iss
                                 :jti (crypto/generate-nonce 12)
                                 :htm (str/upper-case (name method))
                                 :htu url
                                 :iat (crypto/now)
                                 :nonce nonce
                                 :ath ath}})
          _ (cast/dev {::headers {:typ "dpop+jwt"
                                  :alg default-alg
                                  :jwk (jwt/public-jwk dpop-key)}})
          proof (jwt/generate dpop-key
                              {:typ "dpop+jwt"
                               :alg default-alg
                               :jwk (jwt/public-jwk dpop-key)}
                              {:iss iss
                               :jti (crypto/generate-nonce 12)
                               :htm (str/upper-case (name method))
                               :htu url
                               :iat (crypto/now)
                               :nonce nonce
                               :ath ath})]
      (-> ctx
          (update ::i/request assoc-in [:headers :dpop] proof)
          (assoc ::original-ctx ctx)
          (assoc ::origin host)
          (assoc ::nonce nonce)))))

(defn- use-dpop-nonce-error?
  [{:keys [status headers body]}]
  (case status
    ;; resource server returns a 401 w/ WWW-Authenticate header
    401 (let [{:keys [www-authenticate]} headers]
          (and (str/starts-with? www-authenticate "DPoP")
               (str/includes? www-authenticate "error=\"use_dpop_nonce\"")))
    ;; authorization server returns a 400 w/ code in body
    400 (= "use_dpop_nonce" (:error body))
    false))

(defn interceptor
  [{:keys [iss dpop-key] :as opts}]
  (let [wrap-dpop (dpop-wrapper opts)]
    {::i/name ::dpop-interceptor
     ::i/enter wrap-dpop
     ::i/leave (fn [{:keys [::i/response ::original-ctx ::origin ::nonce] :as ctx}]
                 (let [{:keys [status headers body]} response]
                   ;; store the nonce for this origin if new
                   (when-let [next-nonce (:dpop-nonce headers)]
                     (when (not (= next-nonce nonce))
                       (swap! nonce-store assoc origin next-nonce)))
                   ;; retry if server asks to use their nonce
                   (if (and (use-dpop-nonce-error? response)
                            (not (:server-nonce (::i/request original-ctx))))
                     (i/continue (wrap-dpop (assoc-in original-ctx
                                                      [::i/request :server-nonce]
                                                      (:dpop-nonce headers))))
                     (dissoc ctx ::original-ctx ::origin ::nonce))))}))
