(ns atproto.handle
  "Handles are human-friendly but less-permanent identifiers for atproto accounts.

  See https://atproto.com/specs/handle."
  (:refer-clojure :exclude [resolve])
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [atproto.runtime.interceptor :as i]
            [atproto.runtime.dns :as dns]
            [atproto.runtime.json :as json]
            [atproto.runtime.http :as http]))

;; From https://github.com/bluesky-social/atproto/blob/main/packages/syntax/src/handle.ts
(def regex
  #"^([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$")

(s/def ::handle
  (s/and string?
         #(re-find regex %)
         (s/conformer str/lower-case)))

(defn conform
  "Conform the input into a atproto handle.

  Return :atproto.handle/invalid if the handle is invalid."
  [input]
  (let [handle (s/conform ::handle input)]
    (if (= handle ::s/invalid)
      ::invalid
      handle)))

(defn valid?
  "Whether the input is a valid atproto handle."
  [input]
  (not (= ::invalid (conform input))))

(defn- resolve-with-dns
  [handle cb]
  (let [hostname (str "_atproto." handle)]
    (if (< 253 (count hostname))
      (cb {:error "Handle Too Long"
           :handle handle})
      (i/execute {::i/request {:hostname hostname :type "txt"}
                  ::i/queue [dns/interceptor]}
                 :callback
                 (fn [{:keys [error values] :as resp}]
                   (if error
                     (cb resp)
                     (let [dids (->> values
                                     (map #(some->> %
                                                    (re-matches #"^did=(.+)$")
                                                    (second)))
                                     (remove nil?)
                                     (seq))]
                       (cb (cond
                             (empty? dids)      {:error "DID not found." :handle handle}
                             (< 1 (count dids)) {:error "Too many DIDs found." :handle handle :dids dids}
                             :else              {:did (first dids)})))))))))

(defn- resolve-with-https
  [handle cb]
  (i/execute {::i/request {:method :get
                           :timeout 3000
                           :url (str "https://" handle "/.well-known/atproto-did")}
              ::i/queue [json/interceptor http/interceptor]}
             :callback
             (fn [{:keys [error status body] :as resp}]
               (cb (cond
                     error                  resp
                     (http/success? status) {:did (str/trim body)}
                     :else                  (http/error-map resp))))))

(defn resolve
  "Resolve the handle to a DID."
  [input & {:as opts}]
  (let [[cb val] (i/platform-async opts)
        handle (conform input)]
    (if (= ::invalid handle)
      (cb {:error (str "Cannot resolve invalid handle: " input)})
      (resolve-with-dns handle
                        (fn [{:keys [error] :as resp}]
                          (if error
                            (resolve-with-https handle cb)
                            (cb resp)))))
    val))
