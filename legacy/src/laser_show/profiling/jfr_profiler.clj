(ns laser-show.profiling.jfr-profiler
  "Java Flight Recorder integration for low-overhead continuous profiling.
   
   JFR provides near-zero overhead profiling (<1%) suitable for continuous
   use during development and production. Key features:
   
   - Continuous recording with circular buffer (dump on demand)
   - Real-time event streaming for spike detection
   - Custom JFR events for frame timing (FrameGenerationEvent, etc.)
   - Correlation with JVM events (GC, JIT, thread states)
   
   Quick Start:
   ```clojure
   (start-recording!)            ; Start continuous recording
   (start-spike-detection! 5000) ; Alert when frame >5ms
   ;; ... use app ...
   (dump-recording!)             ; Save last 5 min to file
   (stop-recording!)             ; Stop when done
   ```
   
   Analysis:
   - Open .jfr files in JDK Mission Control
   - View timeline to see frame timing over time
   - Correlate with GC pauses and JIT compilation events"
  (:require [clojure.tools.logging :as log]
            [clojure.java.io :as io])
  (:import [jdk.jfr Configuration FlightRecorder Recording]
           [jdk.jfr.consumer RecordingStream RecordedEvent]
           [java.time Duration]
           [java.time.format DateTimeFormatter]
           [java.time LocalDateTime]
           [java.nio.file Path Paths]
           [java.util.function Consumer]))


;; Forward declarations for functions used before definition
(declare stop-recording!)
(declare stop-spike-detection!)



;; Configuration



(def ^:private output-dir "./profiling-results/jfr")

(def ^:private default-max-age "5m")
(def ^:private default-max-size "100m")

(defn- ensure-output-dir!
  "Ensure the JFR output directory exists."
  []
  (let [dir (io/file output-dir)]
    (when-not (.exists dir)
      (.mkdirs dir)
      (log/info "Created JFR output directory:" output-dir))))



;; Recording State



(defonce ^:private !recording (atom nil))
(defonce ^:private !stream (atom nil))
(defonce ^:private !spike-callback (atom nil))



;; Size Parsing



(defn- parse-size
  "Parse size string like '100m' or '1g' to bytes."
  [size-str]
  (let [s (str size-str)
        multiplier (case (last s)
                     (\k \K) 1024
                     (\m \M) (* 1024 1024)
                     (\g \G) (* 1024 1024 1024)
                     1)
        num-str (if (Character/isDigit (last s))
                  s
                  (subs s 0 (dec (count s))))]
    (* (Long/parseLong num-str) multiplier)))

(defn- parse-duration
  "Parse duration string like '5m' or '1h' to Duration."
  [duration-str]
  (let [s (str duration-str)
        unit (case (last s)
               (\s \S) "S"
               (\m \M) "M"
               (\h \H) "H"
               "S")
        num-str (if (Character/isDigit (last s))
                  s
                  (subs s 0 (dec (count s))))]
    (Duration/parse (str "PT" num-str unit))))



;; Recording Management



(defn start-recording!
  "Start a continuous JFR recording.
   
   The recording runs with low overhead and stores events in a circular
   buffer that can be dumped on demand. Events older than max-age are
   discarded automatically.
   
   Options:
   - :max-age  - How long to keep events (default \"5m\", e.g., \"10m\", \"1h\")
   - :max-size - Maximum recording size (default \"100m\", e.g., \"500m\", \"1g\")
   - :settings - JFR settings: :default, :profile, or path to .jfc file
                 :default = minimal overhead, :profile = more detail
   
   Returns: The Recording object
   
   Example:
   ```clojure
   (start-recording!)
   (start-recording! {:max-age \"10m\" :settings :profile})
   ```"
  ([] (start-recording! {}))
  ([{:keys [max-age max-size settings]
     :or {max-age default-max-age 
          max-size default-max-size 
          settings :profile}}]
   (when @!recording
     (log/info "Stopping existing recording before starting new one")
     (stop-recording!))
   
   (ensure-output-dir!)
   
   (try
     (let [config (case settings
                    :default (Configuration/getConfiguration "default")
                    :profile (Configuration/getConfiguration "profile")
                    (Configuration/create (Paths/get settings (into-array String []))))
           recording (doto (Recording. config)
                       (.setMaxAge (parse-duration max-age))
                       (.setMaxSize (parse-size max-size))
                       (.setName "laser-show-continuous")
                       (.setToDisk true)
                       (.start))]
       (reset! !recording recording)
       (log/info "JFR recording started" {:max-age max-age :max-size max-size :settings settings})
       (println (format "🔴 JFR recording started (max-age: %s, max-size: %s, settings: %s)"
                        max-age max-size settings))
       recording)
     (catch Exception e
       (log/error "Failed to start JFR recording" e)
       (println "❌ Failed to start JFR recording:" (.getMessage e))
       nil))))

(defn stop-recording!
  "Stop the current JFR recording.
   
   Also stops spike detection if active.
   
   Example:
   ```clojure
   (stop-recording!)
   ```"
  []
  (stop-spike-detection!)
  (when-let [recording @!recording]
    (try
      (.stop recording)
      (.close recording)
      (reset! !recording nil)
      (log/info "JFR recording stopped")
      (println "⏹️  JFR recording stopped")
      (catch Exception e
        (log/error "Error stopping JFR recording" e)
        (println "❌ Error stopping JFR recording:" (.getMessage e))))))

(defn recording-active?
  "Check if a JFR recording is currently active."
  []
  (boolean @!recording))

(defn dump-recording!
  "Dump the current recording to a file.
   
   The recording continues after dumping, so you can dump multiple times
   to capture different time windows. Useful when you observe an issue
   and want to save the events leading up to it.
   
   Parameters:
   - filename: (optional) Output filename (default: timestamped .jfr file)
   
   Returns: Path to the saved file, or nil on error
   
   Example:
   ```clojure
   (dump-recording!)
   (dump-recording! \"spike-investigation.jfr\")
   ```"
  ([] (dump-recording! nil))
  ([filename]
   (if-let [recording @!recording]
     (try
       (ensure-output-dir!)
       (let [ts (LocalDateTime/now)
             formatter (DateTimeFormatter/ofPattern "yyyyMMdd_HHmmss")
             fname (or filename
                       (format "%s-frame-profile.jfr" (.format ts formatter)))
             full-path (str output-dir "/" fname)
             path (Paths/get full-path (into-array String []))]
         (.dump recording path)
         (log/info "JFR recording dumped" {:path full-path})
         (println (str "💾 Recording dumped to: " full-path))
         full-path)
       (catch Exception e
         (log/error "Failed to dump JFR recording" e)
         (println "❌ Failed to dump recording:" (.getMessage e))
         nil))
     (do
       (println "⚠️  No active recording. Call (start-recording!) first.")
       nil))))



;; Spike Detection



(defn start-spike-detection!
  "Start real-time monitoring for frame latency spikes.
   
   Uses JFR event streaming to detect when frame generation exceeds
   the threshold. This is more efficient than polling and provides
   immediate notification of performance issues.
   
   Parameters:
   - threshold-us: Microseconds threshold for alerts (e.g., 5000 for 5ms)
   - callback: (optional) Function called with event data when spike detected.
               Receives map with :total-time-us, :base-time-us, :effects-time-us,
               :effect-count, :point-count. Default prints alert to console.
   
   Example:
   ```clojure
   ;; Alert on frames >5ms (typical frame budget at 60 FPS is 16.7ms)
   (start-spike-detection! 5000)
   
   ;; Custom callback for logging or other handling
   (start-spike-detection! 5000 
     (fn [{:keys [total-time-us effect-count]}]
       (log/warn \"Frame spike:\" total-time-us \"us with\" effect-count \"effects\")))
   ```"
  ([threshold-us]
   (start-spike-detection! threshold-us nil))
  ([threshold-us callback]
   (when @!stream
     (stop-spike-detection!))
   
   (let [cb (or callback
                (fn [{:keys [total-time-us base-time-us effects-time-us effect-count point-count]}]
                  (println (format "⚠️  SPIKE: %d µs total (base: %d µs, effects: %d µs) [effects: %d, points: %d]"
                                   total-time-us base-time-us effects-time-us effect-count point-count))))
         threshold-long (long threshold-us)
         event-consumer (reify Consumer
                          (accept [_ event]
                            (let [^RecordedEvent recorded-event event
                                  total (.getLong recorded-event "totalTimeUs")]
                              (when (> total threshold-long)
                                (cb {:total-time-us total
                                     :base-time-us (.getLong recorded-event "baseTimeUs")
                                     :effects-time-us (.getLong recorded-event "effectsTimeUs")
                                     :effect-count (.getInt recorded-event "effectCount")
                                     :point-count (.getInt recorded-event "pointCount")})))))
         stream (doto (RecordingStream.)
                  (.enable "laser_show.FrameGeneration")
                  (.onEvent "laser_show.FrameGeneration" event-consumer))]
     
     ;; Start stream in background thread
     (future
       (try
         (.start stream)
         (catch Exception e
           (when-not (instance? InterruptedException e)
             (log/error "JFR stream error" e)))))
     
     (reset! !stream stream)
     (reset! !spike-callback cb)
     (log/info "JFR spike detection started" {:threshold-us threshold-us})
     (println (format "👁️  Spike detection started (threshold: %d µs = %.2f ms)"
                      threshold-us (/ threshold-us 1000.0))))))

(defn stop-spike-detection!
  "Stop real-time spike detection.
   
   Example:
   ```clojure
   (stop-spike-detection!)
   ```"
  []
  (when-let [stream @!stream]
    (try
      (.close stream)
      (catch Exception e
        (log/debug "Error closing JFR stream" e)))
    (reset! !stream nil)
    (reset! !spike-callback nil)
    (log/info "JFR spike detection stopped")
    (println "👁️  Spike detection stopped")))

(defn spike-detection-active?
  "Check if spike detection is currently active."
  []
  (boolean @!stream))

(defn spike-auto-dump!
  "Start spike detection that automatically dumps recording on spike.
   
   Useful for capturing the events leading up to a performance issue.
   When a spike is detected, the current recording buffer is saved
   automatically. A cooldown prevents excessive dumps.
   
   Parameters:
   - threshold-us: Microseconds threshold for auto-dump (e.g., 10000 for 10ms)
   - cooldown-sec: (optional) Minimum seconds between dumps (default 30)
   
   Example:
   ```clojure
   (start-recording!)          ; Must have recording active
   (spike-auto-dump! 10000)    ; Dump on any frame >10ms
   (spike-auto-dump! 5000 60)  ; Dump on >5ms, max once per minute
   ```"
  ([threshold-us]
   (spike-auto-dump! threshold-us 30))
  ([threshold-us cooldown-sec]
   (if-not @!recording
     (println "⚠️  No active recording. Call (start-recording!) first.")
     (let [last-dump (atom 0)
           cooldown-ms (* cooldown-sec 1000)]
       (start-spike-detection! threshold-us
         (fn [{:keys [total-time-us effect-count point-count]}]
           (let [now (System/currentTimeMillis)
                 since-last (- now @last-dump)]
             (when (> since-last cooldown-ms)
               (reset! last-dump now)
               (println (format "⚠️  SPIKE: %d µs - Auto-dumping recording..." total-time-us))
               (dump-recording! (format "spike-%dus-%d.jfr" total-time-us now))))))
       (println (format "🤖 Auto-dump enabled (threshold: %d µs, cooldown: %d sec)"
                        threshold-us cooldown-sec))))))



;; Status and Information



(defn status
  "Get current JFR profiler status.
   
   Returns a map with:
   - :recording-active? - Whether recording is running
   - :spike-detection-active? - Whether spike detection is running
   - :output-dir - Directory for JFR files
   
   Example:
   ```clojure
   (status)
   ```"
  []
  {:recording-active? (recording-active?)
   :spike-detection-active? (spike-detection-active?)
   :output-dir output-dir})

(defn print-status
  "Print current JFR profiler status.
   
   Example:
   ```clojure
   (print-status)
   ```"
  []
  (let [{:keys [recording-active? spike-detection-active? output-dir]} (status)]
    (println "📊 JFR Profiler Status:")
    (println (format "   Recording: %s" (if recording-active? "ACTIVE 🔴" "inactive")))
    (println (format "   Spike Detection: %s" (if spike-detection-active? "ACTIVE 👁️" "inactive")))
    (println (format "   Output Directory: %s" output-dir))))

(defn list-recordings
  "List all JFR recordings in the output directory.
   
   Returns: Vector of maps with :file, :path, :size-mb, :modified
   
   Example:
   ```clojure
   (list-recordings)
   ```"
  []
  (let [dir (io/file output-dir)]
    (when (.exists dir)
      (->> (.listFiles dir)
           (filter #(.endsWith (.getName %) ".jfr"))
           (mapv (fn [f]
                   {:file (.getName f)
                    :path (.getAbsolutePath f)
                    :size-mb (format "%.2f" (/ (.length f) 1024.0 1024.0))
                    :modified (java.util.Date. (.lastModified f))}))
           (sort-by :modified)
           reverse
           vec))))

(defn print-recordings
  "Print list of JFR recordings.
   
   Example:
   ```clojure
   (print-recordings)
   ```"
  []
  (let [recordings (list-recordings)]
    (if (seq recordings)
      (do
        (println "📁 JFR Recordings:")
        (doseq [{:keys [file size-mb modified]} recordings]
          (println (format "   %s (%s MB) - %s" file size-mb modified))))
      (println "📁 No JFR recordings found in" output-dir))))



;; JFR Event Emission (Clojure API for Java events)



(defn emit-frame-event!
  "Emit a FrameGenerationEvent to the JFR recording.
   
   Called automatically by frame-service.clj when generating frames.
   Can also be called manually for testing.
   
   Parameters map:
   - :base-time-us - Base frame generation time (microseconds)
   - :effects-time-us - Effect chain application time (microseconds)
   - :effect-count - Number of effects applied
   - :point-count - Number of laser points in the frame
   
   Example:
   ```clojure
   (emit-frame-event! {:base-time-us 150
                        :effects-time-us 80
                        :effect-count 3
                        :point-count 500})
   ```"
  [{:keys [base-time-us effects-time-us effect-count point-count]
    :or {base-time-us 0 effects-time-us 0 effect-count 0 point-count 0}}]
  (try
    (laser_show.profiling.FrameGenerationEvent/emit
      (long base-time-us)
      (long effects-time-us)
      (int effect-count)
      (int point-count))
    (catch Exception e
      ;; Silently ignore if events not registered (e.g., JFR not started)
      nil)))

(defn emit-effect-chain-event!
  "Emit an EffectChainEvent to the JFR recording.
   
   Parameters map:
   - :duration-us - Time to apply all effects (microseconds)
   - :effect-count - Number of effects applied
   - :point-count - Number of points processed
   
   Example:
   ```clojure
   (emit-effect-chain-event! {:duration-us 80
                               :effect-count 3
                               :point-count 500})
   ```"
  [{:keys [duration-us effect-count point-count]
    :or {duration-us 0 effect-count 0 point-count 0}}]
  (try
    (laser_show.profiling.EffectChainEvent/emit
      (long duration-us)
      (int effect-count)
      (int point-count))
    (catch Exception e
      nil)))

(defn emit-idn-streaming-event!
  "Emit an IdnStreamingEvent to the JFR recording.
   
   Parameters map:
   - :interval-us - Time since last frame (microseconds)
   - :packet-bytes - Size of the packet in bytes
   - :point-count - Number of points in the frame
   - :target-host - Target projector IP address
   
   Example:
   ```clojure
   (emit-idn-streaming-event! {:interval-us 16667
                                :packet-bytes 2048
                                :point-count 500
                                :target-host \"192.168.1.100\"})
   ```"
  [{:keys [interval-us packet-bytes point-count target-host]
    :or {interval-us 0 packet-bytes 0 point-count 0 target-host ""}}]
  (try
    (laser_show.profiling.IdnStreamingEvent/emit
      (long interval-us)
      (long packet-bytes)
      (int point-count)
      (str target-host))
    (catch Exception e
      nil)))
