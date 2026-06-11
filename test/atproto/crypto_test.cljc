(ns atproto.crypto-test
  (:require #?(:clj [clojure.test :refer :all]
               :cljs [cljs.test :refer :all])
            [clojure.java.io :as io]
            [clojure.string :as str]
            [multiformats.base :as mb]
            [atproto.runtime.bytes :as bytes]
            [atproto.runtime.json :as json]
            [atproto.crypto :as crypto])
  #?(:clj (:import [java.math BigInteger]
                   [java.util Arrays Base64])))

(def algs ["ES256" "ES256K"])

;; Curve orders, for low-S assertions.
(def curve-order
  {"ES256" (BigInteger. "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551" 16)
   "ES256K" (BigInteger. "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141" 16)})

(defn- load-fixture
  [path]
  (json/read-str (slurp (io/resource (str "interop-test-files/crypto/" path)))))

(defn- b64-decode
  "Decode standard base64 without padding (the fixture encoding)."
  ^bytes [s]
  #?(:clj (.decode (Base64/getDecoder) ^String s)))

(defn- result-of
  [async-val]
  (deref async-val 1000 ::timeout))

;; -----------------------------------------------------------------------------
;; Keypairs
;; -----------------------------------------------------------------------------

#?(:clj
   (deftest keypair-test
     (doseq [alg algs]
       (testing (str alg " sign/verify round-trip")
         (let [kp (result-of (crypto/generate alg))
               msg (.getBytes "hello world" "UTF-8")
               sig (result-of (crypto/sign kp msg))]
           (is (= alg (crypto/alg kp)))
           (is (= 33 (alength ^bytes (crypto/public-key kp))))
           (is (= 64 (alength ^bytes sig)))
           (is (true? (result-of (crypto/verify (crypto/public-key kp) sig msg :alg alg))))
           (testing "mutated message fails"
             (is (false? (result-of (crypto/verify (crypto/public-key kp) sig
                                                   (.getBytes "hello world!" "UTF-8")
                                                   :alg alg)))))
           (testing "mutated signature fails"
             (let [bad (Arrays/copyOf ^bytes sig 64)]
               (aset bad 50 (unchecked-byte (bit-xor (aget bad 50) 0xff)))
               (is (false? (result-of (crypto/verify (crypto/public-key kp) bad msg :alg alg))))))))
       (testing (str alg " export/import preserves did")
         (let [kp (result-of (crypto/generate alg :exportable? true))
               priv (crypto/export kp)
               imported (result-of (crypto/import-private-key alg priv))]
           (is (= 32 (alength ^bytes priv)))
           (is (= (crypto/did kp) (crypto/did imported)))
           (testing "and from a hex string (TS parity)"
             (let [hex (apply str (map #(format "%02x" %) priv))
                   from-hex (result-of (crypto/import-private-key alg hex))]
               (is (= (crypto/did kp) (crypto/did from-hex)))))))
       (testing (str alg " non-exportable keypair refuses export")
         (let [kp (result-of (crypto/generate alg))]
           (is (= "PrivateKeyNotExportable" (:error (crypto/export kp))))
           (is (= "PrivateKeyNotExportable" (:error (crypto/keypair->jwk kp))))))
       (testing (str alg " JWK round-trip")
         (let [kp (result-of (crypto/generate alg :exportable? true))
               jwk (crypto/keypair->jwk kp)
               kp2 (crypto/jwk->keypair jwk)]
           (is (= "EC" (:kty jwk)))
           (is (= ({"ES256" "P-256" "ES256K" "secp256k1"} alg) (:crv jwk)))
           (is (= (crypto/did kp) (crypto/did kp2))))))
     (testing "error cases"
       (is (= "UnsupportedAlgorithm" (:error (result-of (crypto/generate "ES512")))))
       (is (= "UnsupportedAlgorithm" (:error (result-of (crypto/import-private-key "RS256" (byte-array 32))))))
       (is (= "InvalidPrivateKey" (:error (result-of (crypto/import-private-key "ES256" (byte-array 31))))))
       (is (= "InvalidPrivateKey" (:error (result-of (crypto/import-private-key "ES256" (byte-array 32))))))
       (is (= "InvalidPrivateKey" (:error (result-of (crypto/import-private-key "ES256" "not-hex")))))
       (is (= "InvalidJwk" (:error (crypto/jwk->keypair {:kty "RSA"}))))
       (is (= "InvalidJwk" (:error (crypto/jwk->keypair {:kty "EC" :crv "P-256"})))))))

;; -----------------------------------------------------------------------------
;; Key compression
;; -----------------------------------------------------------------------------

#?(:clj
   (deftest key-compression-test
     (doseq [alg algs]
       (testing (str alg " compressed <-> uncompressed round-trips")
         (dotimes [_ 100]
           (let [kp (result-of (crypto/generate alg))
                 ^bytes compressed (crypto/public-key kp)
                 ^bytes uncompressed (crypto/decompress-pubkey alg compressed)]
             (is (= 33 (alength compressed)))
             (is (= 65 (alength uncompressed)))
             (is (= 0x04 (aget uncompressed 0)))
             (is (bytes/eq? compressed (crypto/compress-pubkey alg uncompressed)))
             ;; compress-pubkey accepts either form
             (is (bytes/eq? compressed (crypto/compress-pubkey alg compressed)))))))
     (testing "error cases"
       (is (= "InvalidPublicKey" (:error (crypto/decompress-pubkey "ES256" (byte-array 65)))))
       ;; x = 2^256 - 1 is not a valid field element on either curve
       (let [bad (byte-array (cons 0x02 (repeat 32 -1)))]
         (is (= "InvalidPublicKey" (:error (crypto/decompress-pubkey "ES256" bad))))
         (is (= "InvalidPublicKey" (:error (crypto/compress-pubkey "ES256K" bad)))))
       (is (= "UnsupportedAlgorithm" (:error (crypto/compress-pubkey "EdDSA" (byte-array 33))))))))

;; -----------------------------------------------------------------------------
;; did:key W3C test vectors
;; -----------------------------------------------------------------------------

#?(:clj
   (deftest didkey-w3c-vectors-test
     (testing "secp256k1 vectors (hex private keys)"
       (let [vectors (load-fixture "w3c_didkey_K256.json")]
         (is (= 5 (count vectors)))
         (doseq [{:keys [privateKeyBytesHex publicDidKey]} vectors]
           (let [kp (result-of (crypto/import-private-key "ES256K" privateKeyBytesHex))]
             (is (= publicDidKey (crypto/did kp)))))))
     (testing "P-256 vectors (base58btc private keys)"
       (let [vectors (load-fixture "w3c_didkey_P256.json")]
         (is (= 1 (count vectors)))
         (doseq [{:keys [privateKeyBytesBase58 publicDidKey]} vectors]
           (let [priv (mb/parse* :base58btc privateKeyBytesBase58)
                 kp (result-of (crypto/import-private-key "ES256" priv))]
             (is (= publicDidKey (crypto/did kp)))))))))

#?(:clj
   (deftest didkey-round-trip-test
     (doseq [alg algs]
       (testing (str alg " pubkey->did-key / did-key->pubkey round-trip")
         (let [kp (result-of (crypto/generate alg))
               ^bytes compressed (crypto/public-key kp)
               did (crypto/pubkey->did-key alg compressed)
               {parsed-alg :alg ^bytes parsed-bytes :bytes} (crypto/did-key->pubkey did)]
           (is (string? did))
           (is (str/starts-with? did "did:key:z"))
           (is (= alg parsed-alg))
           ;; parsing returns the uncompressed (65-byte) key
           (is (= 65 (alength parsed-bytes)))
           (is (bytes/eq? compressed (crypto/compress-pubkey alg parsed-bytes)))
           ;; uncompressed input formats to the same did
           (is (= did (crypto/pubkey->did-key alg parsed-bytes)))
           ;; multikey helpers compose with the did:key fns
           (is (= did (str "did:key:" (crypto/format-multikey alg compressed))))
           (is (= alg (:alg (crypto/parse-multikey (subs did (count "did:key:")))))))))))

#?(:clj
   (deftest didkey-error-test
     (testing "ed25519 did:key is unsupported"
       (is (= "UnsupportedKeyType"
              (:error (crypto/did-key->pubkey
                       "did:key:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK")))))
     (testing "non-base58btc multibase"
       (is (= "InvalidDidKey" (:error (crypto/did-key->pubkey "did:key:uAAAA"))))
       (is (= "InvalidDidKey" (:error (crypto/parse-multikey "uAAAA")))))
     (testing "missing did:key prefix"
       (is (= "InvalidDidKey" (:error (crypto/did-key->pubkey "did:web:example.com"))))
       (is (= "InvalidDidKey" (:error (crypto/did-key->pubkey nil)))))
     (testing "invalid base58 payload"
       (is (= "InvalidDidKey" (:error (crypto/did-key->pubkey "did:key:z0OIl")))))))

;; -----------------------------------------------------------------------------
;; Signature interop vectors (the core parity test)
;; -----------------------------------------------------------------------------

#?(:clj
   (deftest signature-fixtures-test
     (let [vectors (load-fixture "signature-fixtures.json")]
       (is (= 6 (count vectors)))
       (doseq [{:keys [comment messageBase64 algorithm publicKeyDid publicKeyMultibase
                       signatureBase64 validSignature tags]} vectors]
         (let [msg (b64-decode messageBase64)
               sig (b64-decode signatureBase64)
               multibase-key (crypto/multibase->bytes publicKeyMultibase)
               {:keys [alg bytes]} (crypto/did-key->pubkey publicKeyDid)]
           (testing (str comment ": multibase key matches the did:key")
             (is (= algorithm alg))
             (is (bytes/eq? multibase-key (crypto/compress-pubkey alg bytes))))
           (testing (str comment ": strict verify matches validSignature")
             (is (= validSignature
                    (result-of (crypto/verify multibase-key sig msg :alg algorithm))))
             (is (= validSignature
                    (result-of (crypto/verify-did-sig publicKeyDid sig msg)))))
           (when (seq tags)
             (testing (str comment ": malleable verify accepts " (pr-str tags))
               (is (true? (result-of (crypto/verify multibase-key sig msg
                                                    :alg algorithm
                                                    :allow-malleable? true))))
               (is (true? (result-of (crypto/verify-did-sig publicKeyDid sig msg
                                                            :allow-malleable? true)))))))))))

;; -----------------------------------------------------------------------------
;; Negative & edge cases
;; -----------------------------------------------------------------------------

#?(:clj
   (deftest verify-edge-cases-test
     (let [kp (result-of (crypto/generate "ES256"))
           msg (.getBytes "edge cases" "UTF-8")
           ^bytes sig (result-of (crypto/sign kp msg))]
       (testing "wrong :alg for the key never verifies"
         (is (not (true? (result-of (crypto/verify (crypto/public-key kp) sig msg
                                                   :alg "ES256K"))))))
       (testing "alg assertion mismatch on verify-did-sig"
         (is (= "AlgorithmMismatch"
                (:error (result-of (crypto/verify-did-sig (crypto/did kp) sig msg
                                                          :alg "ES256K"))))))
       (testing "wrong-length signatures are false, not errors"
         (is (false? (result-of (crypto/verify (crypto/public-key kp)
                                               (Arrays/copyOf sig 63) msg :alg "ES256"))))
         (is (false? (result-of (crypto/verify (crypto/public-key kp)
                                               (Arrays/copyOf sig 65) msg :alg "ES256"))))
         (is (false? (result-of (crypto/verify (crypto/public-key kp)
                                               (byte-array 0) msg :alg "ES256")))))
       (testing "unsupported alg and invalid pubkey are errors, not false"
         (is (= "UnsupportedAlgorithm"
                (:error (result-of (crypto/verify (crypto/public-key kp) sig msg)))))
         (is (= "InvalidPublicKey"
                (:error (result-of (crypto/verify (byte-array 33) sig msg :alg "ES256")))))))))

#?(:clj
   (deftest low-s-and-determinism-test
     (doseq [alg algs]
       (testing (str alg " signatures are low-S and deterministic")
         (let [kp (result-of (crypto/generate alg))
               ^BigInteger n (curve-order alg)
               half (.shiftRight n 1)]
           (dotimes [i 25]
             (let [msg (.getBytes (str "message " i) "UTF-8")
                   ^bytes sig (result-of (crypto/sign kp msg))
                   s (BigInteger. 1 (Arrays/copyOfRange sig 32 64))]
               (is (= 64 (alength sig)))
               (is (<= (.compareTo s half) 0))
               (is (true? (result-of (crypto/verify (crypto/public-key kp) sig msg :alg alg))))
               (testing "RFC 6979: signing twice yields identical bytes"
                 (is (bytes/eq? sig (result-of (crypto/sign kp msg))))))))))))

;; -----------------------------------------------------------------------------
;; Multibase wrappers
;; -----------------------------------------------------------------------------

#?(:clj
   (deftest multibase-test
     (let [b (byte-array [1 2 3 4 5])]
       (doseq [base [:base16 :base32 :base58btc :base64 :base64url]]
         (let [s (crypto/bytes->multibase base b)]
           (is (bytes/eq? b (crypto/multibase->bytes s))))))
     (is (= "InvalidMultibase" (:error (crypto/multibase->bytes "?abc"))))))
