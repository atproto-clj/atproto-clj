(ns atproto.runtime.varint
  "Unsigned LEB128 varints (multiformats unsigned-varint).

  Used for CAR block framing and CID prefixes. Thin wrapper over
  multiformats.varint so consumers don't bind to the library directly."
  (:require [multiformats.varint :as varint]))

(defn encode
  "n -> bytes"
  #?(:clj ^bytes [n] :cljs [n])
  (varint/encode n))

(defn decode
  "bytes -> n (reads from offset 0, ignores trailing bytes)"
  [bytes]
  (varint/decode bytes))

(defn read-bytes
  "Read a varint at offset. Returns [value bytes-read].

  Throws ex-info on truncation or >9-byte varints."
  [bytes offset]
  (varint/read-bytes bytes offset))
