(ns atproto.runtime.dns
  "Cross-platform DNS client."
  (:require [atproto.runtime.interceptor :as i]
            #?(:clj [clojure.core.async :as a]))
  #?(:clj (:import [java.util Hashtable]
                   [javax.naming.directory InitialDirContext]
                   [javax.naming NamingException])))

#?(:clj (set! *warn-on-reflection* true))

;; Timeout/retry configuration:
;; https://download.oracle.com/otn_hosted_doc/jdeveloper/904preview/jdk14doc/docs/guide/jndi/jndi-dns.html

(def interceptor
  #?(:clj
     {::i/name ::dns
      ::i/enter (fn [{:keys [::i/request] :as ctx}]
                  (let [{:keys [^String hostname type]} request
                        ch (a/io-thread
                            (try
                              (let [dir-ctx (InitialDirContext.
                                             (Hashtable.
                                              {"java.naming.factory.initial"
                                               "com.sun.jndi.dns.DnsContextFactory"
                                               "com.sun.jndi.dns.timeout.initial" "3000"
                                               "com.sun.jndi.dns.timeout.retries" "2"}))]
                                {:values (seq (some-> dir-ctx
                                                      (.getAttributes hostname
                                                                      ^"[Ljava.lang.String;"
                                                                      (into-array String [type]))
                                                      (.get type)
                                                      (.getAll)
                                                      (enumeration-seq)))})
                              (catch NamingException ne
                                {:error "DNS name not found"
                                 :exception (Throwable->map ne)})))]
                    (a/go
                      (i/continue (assoc ctx ::i/response (a/<! ch))))
                    nil))}))
