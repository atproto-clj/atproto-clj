(ns atproto.runtime.datetime
  #?(:clj (:import [java.time Instant LocalDateTime OffsetDateTime ZoneOffset]
                   [java.time.temporal ChronoField]
                   [java.time.format DateTimeFormatter DateTimeFormatterBuilder DateTimeParseException SignStyle])))

#?(:clj (set! *warn-on-reflection* true))

#?(:clj
   (def datetime-formatter
     (-> (DateTimeFormatterBuilder.)
         (.append DateTimeFormatter/ISO_OFFSET_DATE_TIME)
         (.appendFraction ChronoField/NANO_OF_SECOND 0 9 true)
         (.toFormatter))))

(defn trim-fraction
  "Only keep the first 9 digits of the fraction."
  [s]
  (if-let [[_ dt fraction offset] (re-matches #"^(.*)\.([0-9]+)(Z|(?:[+-][0-2][0-9]:[0-5][0-9]))$" s)]
    (str dt "." (subs fraction 0 (min (count fraction) 9)) offset)
    s))

(defn parse
  [s]
  #?(:clj (try
            (OffsetDateTime/parse (trim-fraction s) datetime-formatter)
            (catch DateTimeParseException _))))

(defn current-time-millis
  []
  #?(:clj (System/currentTimeMillis)))

#?(:clj
   (def ^:private canonical-formatter
     "Canonical atproto datetime: UTC, millisecond precision, trailing Z."
     (-> (DateTimeFormatter/ofPattern "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
         (.withZone ZoneOffset/UTC))))

#?(:clj
   (def ^:private local-utc-formatter
     "Local date-time followed by a literal \" UTC\" suffix."
     (-> (DateTimeFormatterBuilder.)
         (.append DateTimeFormatter/ISO_LOCAL_DATE_TIME)
         (.appendLiteral " UTC")
         (.toFormatter))))

#?(:clj
   (defn- parse-lenient*
     "Try parsing a datetime string with each known format; an OffsetDateTime
     or nil."
     [s]
     (or (parse s)
         (try (-> (LocalDateTime/parse (trim-fraction s) local-utc-formatter)
                  (.atOffset ZoneOffset/UTC))
              (catch DateTimeParseException _))
         (try (OffsetDateTime/parse s DateTimeFormatter/RFC_1123_DATE_TIME)
              (catch DateTimeParseException _)))))

#?(:clj
   (defn- valid-atproto-year?
     "atproto datetimes must format with a 4-digit, non-negative UTC year."
     [^OffsetDateTime odt]
     (<= 0 (.getYear (.withOffsetSameInstant odt ZoneOffset/UTC)) 9999)))

(defn current-datetime
  "Now, as a canonical atproto datetime string (UTC, millisecond precision,
  trailing Z)."
  []
  #?(:clj (.format ^DateTimeFormatter canonical-formatter (Instant/now))
     :cljs {:error "NotImplemented"
            :message "current-datetime is not implemented on ClojureScript yet."}))

(defn normalize
  "Flexible datetime string -> canonical UTC ISO-8601 with millisecond
  precision (\"...sssZ\").

  Inputs without timezone information are interpreted as UTC. Returns
  {:error \"InvalidDatetime\" :message ...} when the string cannot be parsed
  as a datetime or normalizes outside the year 0000-9999 bounds."
  [s]
  #?(:clj
     (let [tz-designator? (and (string? s)
                               (or (re-find #"[+-]\d\d:?\d\d" s)
                                   (re-find #"\dZ\b" s)
                                   ;; timezone abbreviation, eg. "UTC", "GMT", "PST"
                                   (re-find #"\b[A-Z]{3,4}\b" s)))
           odt (when (string? s)
                 (if tz-designator?
                   ;; an explicit designator: parse as-is
                   (parse-lenient* s)
                   ;; no timezone information: interpret as UTC, falling
                   ;; back to parsing as-is
                   (or (parse-lenient* (str s "Z"))
                       (parse-lenient* (str s " UTC"))
                       (parse-lenient* s))))]
       (if (and odt (valid-atproto-year? odt))
         (.format ^DateTimeFormatter canonical-formatter (.toInstant ^OffsetDateTime odt))
         {:error "InvalidDatetime"
          :message (str "Datetime did not parse as any timestamp format: " (pr-str s))}))
     :cljs
     {:error "NotImplemented"
      :message "normalize is not implemented on ClojureScript yet."}))

(def epoch-datetime "1970-01-01T00:00:00.000Z")

(defn normalize-or-epoch
  "Like `normalize` but returns \"1970-01-01T00:00:00.000Z\" instead of an
  error map."
  [s]
  #?(:clj
     (let [result (normalize s)]
       (if (:error result)
         epoch-datetime
         result))
     :cljs
     {:error "NotImplemented"
      :message "normalize-or-epoch is not implemented on ClojureScript yet."}))
