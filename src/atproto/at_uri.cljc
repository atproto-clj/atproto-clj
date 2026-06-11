(ns atproto.at-uri
  "Construct, parse, and access at:// URIs.

  Parsing is strict: the authority must be a valid handle or DID, the
  collection a valid NSID, and the rkey a valid record key (validated with
  the atproto.lexicon specs)."
  (:require [clojure.spec.alpha :as s]
            [atproto.lexicon :as lexicon]
            [atproto.lexicon.regex :as regex]))

(def ^:private max-length (* 8 1024))

(defn parse
  "Parse an at:// URI string into
  {:authority <did-or-handle> :collection <nsid>? :rkey <str>? :fragment <str>?}.

  Returns nil if the string is not a valid at:// URI (the authority,
  collection, and rkey segments are each validated)."
  [s]
  (when (and (string? s)
             (< (count s) max-length))
    (when-let [[_ authority collection rkey fragment] (re-matches regex/at-uri s)]
      (when (and (s/valid? ::lexicon/at-identifier authority)
                 (or (not collection) (s/valid? ::lexicon/nsid collection))
                 (or (not rkey) (s/valid? ::lexicon/record-key rkey)))
        (cond-> {:authority authority}
          collection (assoc :collection collection)
          rkey       (assoc :rkey rkey)
          fragment   (assoc :fragment fragment))))))

(defn valid?
  "Whether `s` is a valid at:// URI string."
  [s]
  (boolean (parse s)))

(defn- make*
  [authority collection rkey]
  (cond
    (not (s/valid? ::lexicon/at-identifier authority))
    {:error "InvalidAtUri"
     :message (str "Invalid authority: " (pr-str authority))}

    (and rkey (not collection))
    {:error "InvalidAtUri"
     :message "An rkey requires a collection."}

    (and collection (not (s/valid? ::lexicon/nsid collection)))
    {:error "InvalidAtUri"
     :message (str "Invalid collection: " (pr-str collection))}

    (and rkey (not (s/valid? ::lexicon/record-key rkey)))
    {:error "InvalidAtUri"
     :message (str "Invalid rkey: " (pr-str rkey))}

    :else
    (str "at://" authority
         (when collection (str "/" collection))
         (when rkey (str "/" rkey)))))

(defn make
  "Build a canonical at:// URI string from parts.

  Validates each part; returns {:error \"InvalidAtUri\" :message ...} on bad
  input. Accepts (make authority), (make authority collection),
  (make authority collection rkey), or (make {:authority a :collection c :rkey r})."
  ([parts]
   (if (map? parts)
     (make* (:authority parts) (:collection parts) (:rkey parts))
     (make* parts nil nil)))
  ([authority collection]
   (make* authority collection nil))
  ([authority collection rkey]
   (make* authority collection rkey)))

(defn- parts
  [s-or-parsed]
  (if (map? s-or-parsed)
    s-or-parsed
    (parse s-or-parsed)))

(defn authority
  "The authority (handle or DID) of the at:// URI, or nil."
  [s-or-parsed]
  (:authority (parts s-or-parsed)))

(defn collection
  "The collection NSID of the at:// URI, or nil."
  [s-or-parsed]
  (:collection (parts s-or-parsed)))

(defn rkey
  "The record key of the at:// URI, or nil."
  [s-or-parsed]
  (:rkey (parts s-or-parsed)))

(defn fragment
  "The fragment of the at:// URI (leading \"/\" included, \"#\" excluded), or nil."
  [s-or-parsed]
  (:fragment (parts s-or-parsed)))
