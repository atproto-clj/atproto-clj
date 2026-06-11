(ns atproto.runtime.datetime
  #?(:clj (:import [java.time LocalDateTime OffsetDateTime]
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

(defn parse-lenient
  "Lenient ISO-8601-ish datetime parse: the timezone offset is optional.

  Tries the strict atproto datetime parse first, then falls back to a local
  (offset-less) ISO date-time. Returns a platform datetime or nil. Mirrors
  the reference implementation's isDatetimeStringLenient
  (packages/syntax/src/datetime.ts)."
  [s]
  (when (string? s)
    (or (parse s)
        #?(:clj (try
                  (LocalDateTime/parse (trim-fraction s)
                                       DateTimeFormatter/ISO_LOCAL_DATE_TIME)
                  (catch DateTimeParseException _))))))

(defn current-time-millis
  []
  #?(:clj (System/currentTimeMillis)))
