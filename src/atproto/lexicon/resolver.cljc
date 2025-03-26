(ns atproto.lexicon.resolver
  (:require [atproto.runtime.interceptor :as i]
            [atproto.runtime.dns :as dns]
            [atproto.runtime.json :as json]
            [atproto.xrpc.client :as xrpc]))

(def lexicon-record-collection "com.atproto.lexicon.schema")

(defn- fetch-lexicon
  [{:keys [did pds rkey]} cb]
  (xrpc/query {:atproto.session/service pds}
              {:op :com.atproto.repo.getRecord
               :params {:repo did
                        :collection lexicon-record-collection
                        :rkey rkey}}
              :callback
              (fn [{:keys [error] :as resp}]
                (tap> resp)
                (if error (cb resp) (cb (:value resp))))))

(defn- nsid->did
  "Resolve this NSID to a DID using DNS."
  [nsid cb]
  (let [{:keys [domain-authority]} (nsid/parse nsid)
        hostname (->> (str/split domain-authority #"\.")
                      (reverse)
                      (into ["_lexicon"])
                      (str/join "."))]
    (println hostname)
    (if (< 253 (count hostname))
      (cb {:error (str "Cannot resolve nsid, hostname too long: " hostname)})
      (i/execute {::i/request {:hostname hostname
                               :type "txt"}
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
                       (cond
                         (empty? dids)      (cb {:error "Cannot resolve NSID. DID not found."})
                         :else              (cb {:did (first dids)})))))))))

(defn resolve-nsid
  "Resolve the NSID into a Lexicon."
  [nsid & {:as opts}]
  (let [[cb val] (i/platform-async opts)]
    (nsid->did nsid
               (fn [{:keys [error did] :as resp}]
                 (if error
                   (cb resp)
                   (did/resolve did
                                :callback
                                (fn [{:keys [error did doc] :as resp}]
                                  (if error
                                    (cb resp)
                                    (if-let [pds (did/pds doc)]
                                      (fetch-lexicon {:did did
                                                      :pds pds
                                                      :rkey nsid} cb)
                                      (cb {:error "DID doc is missing the PDS url."
                                           :nsid nsid
                                           :did did
                                           :doc doc}))))))))
    val))
