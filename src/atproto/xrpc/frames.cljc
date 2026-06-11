(ns atproto.xrpc.frames
  "Binary event-stream frames: a dag-CBOR header item followed by a
  dag-CBOR body item.

  A frame is exactly two concatenated DAG-CBOR items:
  - message frame: header {:op 1, :t \"#commit\"} (:t optional), any body
  - error frame:   header {:op -1}, body {:error name :message msg?}

  This is the canonical frame codec for the SDK (overview §4.7); the
  firehose client (WS-05) and stream server (WS-11C) both consume it.
  Pure and synchronous; mirrors packages/xrpc-server/src/stream/frames.ts
  in the reference implementation."
  (:require [clojure.string :as str]
            [atproto.data.cbor :as cbor]
            [atproto.runtime.bytes :as bytes]))

#?(:clj (set! *warn-on-reflection* true))

(def message-op 1)
(def error-op -1)

(defn message-frame
  "Frame for a message body.

  The body's :$type (if any) is moved to the header :t, compressed to
  \"#frag\" when its nsid is empty or equals `nsid` (reference
  MessageFrame.fromLexValue). Returns {:op 1 :body ... & :t}."
  [body & {:keys [nsid]}]
  (let [$type (when (map? body) (:$type body))]
    (if (not (string? $type))
      {:op message-op :body body}
      (let [[type-nsid frag :as split] (str/split $type #"#" -1)
            t (if (and (= 2 (count split))
                       (or (= "" type-nsid) (= nsid type-nsid)))
                (str "#" frag)
                $type)]
        {:op message-op :t t :body (dissoc body :$type)}))))

(defn error-frame
  "Frame carrying {:error name :message msg?}; header {:op -1}."
  [error & [message]]
  {:op error-op
   :body (cond-> {:error error}
           (some? message) (assoc :message message))})

(defn- frame-header
  "The dag-CBOR header item for a frame map, or nil if the frame is invalid."
  [{:keys [op t]}]
  (cond
    (= op message-op) (cond-> {:op message-op}
                        (some? t) (assoc :t t))
    (= op error-op)   {:op error-op}))

(defn encode
  "Frame map -> bytes (header item ++ body item).
  Throws ex-info for a frame with an invalid :op."
  [{:keys [op body] :as frame}]
  (if-let [header (frame-header frame)]
    (let [^bytes h (cbor/encode header)
          ^bytes b (cbor/encode body)]
      #?(:clj (let [out (byte-array (+ (alength h) (alength b)))]
                (System/arraycopy h 0 out 0 (alength h))
                (System/arraycopy b 0 out (alength h) (alength b))
                out)
         :cljs (let [out (js/Uint8Array. (+ (.-length h) (.-length b)))]
                 (.set out h 0)
                 (.set out b (.-length h))
                 out)))
    (throw (ex-info "Invalid frame op."
                    {:error "InvalidFrame"
                     :message (str "Invalid frame op: " (pr-str op))}))))

(defn- invalid-frame
  [message]
  {:error "InvalidFrame" :message message})

(defn- valid-header?
  [header]
  (and (map? header)
       (or (and (= message-op (:op header))
                (or (not (contains? header :t))
                    (string? (:t header))))
           (= error-op (:op header)))))

(defn- valid-error-body?
  [body]
  (and (map? body)
       (string? (:error body))
       (or (not (contains? body :message))
           (string? (:message body)))))

(defn decode
  "bytes -> {:op 1 :t \"#x\" :body {...}} | {:op -1 :body {:error ...}}
  or {:error \"InvalidFrame\" :message ...} for a missing body, more than
  two CBOR items, an invalid header, or an invalid error-frame body."
  [bytes]
  (let [items (try
                (cbor/decode-multi bytes)
                (catch #?(:clj Exception :cljs :default) e
                  (invalid-frame (str "Invalid frame CBOR: " (ex-message e)))))]
    (if (:error items)
      items
      (let [[header body] items]
        (cond
          (< 2 (count items))
          (invalid-frame "Too many CBOR data items in frame.")

          (< (count items) 2)
          (invalid-frame "Missing frame body.")

          (not (valid-header? header))
          (invalid-frame (str "Invalid frame header: " (pr-str header)))

          (= message-op (:op header))
          (cond-> {:op message-op :body body}
            (contains? header :t) (assoc :t (:t header)))

          :else
          (if (valid-error-body? body)
            {:op error-op :body body}
            (invalid-frame (str "Invalid error frame body: " (pr-str body)))))))))
