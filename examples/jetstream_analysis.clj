(ns jetstream-analysis
  (:require
   [charred.api :as json]
   [clojure.core.async :as async]
   [clojure.java.io :as io]
   [atproto.jetstream :as jetstream])
  (:import
   [java.time Duration Instant]))

(defn calculate-message-sizes
  [msg]
  (if msg  ; Guard against nil messages
    {:raw-bytes   (count (str msg))
     :content-len (count (or (get-in msg [:record :text]) ""))}
    {:raw-bytes   0
     :content-len 0}))

(defn format-bytes
  [bytes]
  (let [bytes (float (or bytes 0))]  ; Convert to float to handle ratios
    (cond
      (< bytes 1024)          (format "%d B" (long bytes))
      (< bytes (* 1024 1024)) (format "%.2f KB" (double (/ bytes 1024.0)))
      :else                   (format "%.2f MB" (double (/ bytes (* 1024.0 1024.0)))))))

(defn create-window [start duration]
  {:start-time         start
   :end-time          (.plus start duration)
   :message-count     0
   :total-raw-bytes   0
   :total-content-len 0
   :max-msg-size      0
   :min-msg-size      Long/MAX_VALUE
   :types             {}})

(defn create-analysis-state
  [window-duration-seconds]
  (let [now      (Instant/now)
        duration (Duration/ofSeconds window-duration-seconds)]
    {:start-time      now
     :stop-time       nil
     :processed-count 0
     :current-window  (create-window now duration)
     :windows         []
     :window-duration duration}))

(defn update-window
  "Update window stats with a new message"
  [window msg]
  (if (and window msg)  ; Guard against nil window or message
    (let [{:keys [raw-bytes content-len]} (calculate-message-sizes msg)]
      (-> window
          (update :message-count inc)
          (update :total-raw-bytes + raw-bytes)
          (update :total-content-len + content-len)
          (update :max-msg-size max raw-bytes)
          (update :min-msg-size min raw-bytes)
          (update-in [:types (:type msg)] (fnil inc 0))))
    window))

(defn rotate-window!
  [{:keys [current-window window-duration] :as state}]
  (let [now (Instant/now)]
    (if (.isAfter now (:end-time current-window))
      (let [new-window (create-window now window-duration)]
        (-> state
            (update :windows conj current-window)
            (assoc :current-window new-window)))
      state)))

(defn process-message!
  [state msg]
  (-> state
      rotate-window!
      (update :processed-count inc)
      (update :current-window update-window msg)))

(defn start-analysis
  [conn & {:keys [window-duration-seconds]
           :or   {window-duration-seconds 60}}]
  (let [analysis-ch (async/chan 1024)
        state       (atom (create-analysis-state window-duration-seconds))
        mult        (async/mult (:events conn))]

    (async/tap mult analysis-ch)

    (async/go-loop []
      (when-let [msg (async/<! analysis-ch)]
        (swap! state process-message! msg)
        (recur)))

    {:state       state
     :analysis-ch analysis-ch}))

(defn window-summary
  "Create a human-readable summary of a time window"
  [{:keys [message-count total-raw-bytes total-content-len
           max-msg-size min-msg-size types]
    :as window}]
  (if window
    {:message-count (or message-count 0)
     :total-size    (format-bytes total-raw-bytes)
     :total-content (format-bytes total-content-len)
     :avg-msg-size  (format-bytes (if (pos? (or message-count 0))
                                   (float (/ (or total-raw-bytes 0)
                                           (max 1 message-count)))
                                   0))
     :max-msg-size  (format-bytes (or max-msg-size 0))
     :min-msg-size  (format-bytes (if (= min-msg-size Long/MAX_VALUE)
                                   0
                                   (or min-msg-size 0)))
     :types         (or types {})}

    {:message-count 0
     :total-size    "0 B"
     :total-content "0 B"
     :avg-msg-size  "0 B"
     :max-msg-size  "0 B"
     :min-msg-size  "0 B"
     :types         {}}))

(defn get-summary
  [{:keys [start-time stop-time processed-count windows current-window]
    :as state}]
  (if state
    (let [end-time      (or stop-time (Instant/now))
          duration      (Duration/between (or start-time end-time) end-time)
          total-windows (if current-window
                         (conj (vec windows) current-window)
                         (vec windows))
          runtime-secs  (.getSeconds duration)]
      {:runtime-seconds  runtime-secs
       :status           (if stop-time "stopped" "running")
       :total-messages   (or processed-count 0)
       :messages-per-sec (float (/ (or processed-count 0)
                                  (max 1 runtime-secs)))
       :windows-summary  (->> total-windows
                            (map window-summary)
                            (take-last 5))})
    {:runtime-seconds  0
     :status           "unknown"
     :total-messages   0
     :messages-per-sec 0.0
     :windows-summary  []}))

(defn stop-analysis
  [{:keys [analysis-ch state]}]
  (when state
    (swap! state assoc :stop-time (Instant/now)))
  (when analysis-ch
    (async/close! analysis-ch)))


;; Save data for further analysis
(def json-writer
  (json/write-json-fn
   {:escape-unicode       true
    :escape-js-separators true
    :escape-slash         true}))

(def json-reader
  (json/parse-json-fn
   {:key-fn  keyword
    :profile :immutable}))

(defn ensure-samples-dir!
  "Create samples directory if it doesn't exist"
  []
  (let [dir (io/file "samples")]
    (when-not (.exists dir)
      (.mkdir dir))
    dir))

(defn collect-samples
  "Collect N sample messages from the firehose and save to a file.
   Returns a channel that closes when collection is complete."
  [conn n & {:keys [filename]
             :or   {filename (format "samples/firehose-%s.json"
                                    (.toString (Instant/now)))}}]
  (ensure-samples-dir!)
  (let [done-ch   (async/chan)
        samples   (atom [])
        sample-ch (async/chan 1024)
        mult      (async/mult (:events conn))]

    (async/tap mult sample-ch)

    (async/go-loop []
      (if-let [msg (async/<! sample-ch)]
        (do
          (swap! samples conj msg)
          (if (>= (count @samples) n)
            (do
              (with-open [w (io/writer filename)]
                (json-writer w @samples))
              (async/close! sample-ch)
              (async/close! done-ch))
            (recur)))
        (async/close! done-ch)))

    {:filename filename
     :done-ch  done-ch}))

(defn read-samples
  "Read samples from a JSON file"
  [filename]
  (with-open [r (io/reader filename)]
    (json-reader (slurp r))))


(comment

  ;; Example usage for firehose analysis
  (def conn (jetstream/connect-jetstream (async/chan 1024)))
  (def analysis (start-analysis conn :window-duration-seconds 60))

  ;; Get current stats while running
  (get-summary @(:state analysis))

  ;; Stop and get final stats
  (stop-analysis analysis)
  (get-summary @(:state analysis))

  ;; Cleanup firehose connection
  (jetstream/disconnect conn)


  ;; Example usage for sample collection
  (def conn (jetstream/connect-jetstream (async/chan 1024)))

  ;; Collect 10 messages
  (def collection (collect-samples conn 10))

  ;; Wait for collection to complete
  (async/<!! (:done-ch collection))

  ;; The samples are now saved in samples/firehose-{timestamp}.json
  ;; You can also specify a custom filename:
  (collect-samples conn 5
                  :filename "samples/small-sample.json")

  ;; Read saved samples back
  (read-samples "samples/small-sample.json")

  ;; Cleanup
  (jetstream/disconnect conn)
  ,)
