(ns laser-show.common.timing
  "Timing utilities for precise time measurement and delays.
   Provides high-resolution timing functions for real-time applications.")


(defn nanotime
  "Get current time in nanoseconds from an arbitrary starting point.
   This is a monotonic clock that is not affected by system clock changes.
   Suitable for measuring elapsed time with nanosecond precision.
   
   Note: The returned value is only meaningful when compared to other
   nanotime values. It does not represent wall-clock time."
  ^long []
  (System/nanoTime))

(defn nanotime-us
  "Get current time in microseconds from an arbitrary starting point.
   Convenience wrapper around nanotime for microsecond precision."
  ^long []
  (quot (System/nanoTime) 1000))

(defn nanotime-ms
  "Get current time in milliseconds from an arbitrary starting point.
   Convenience wrapper around nanotime for millisecond precision."
  ^long []
  (quot (System/nanoTime) 1000000))


;; Precise Sleep/Delay Functions


(defn precise-sleep-until
  "Sleep until target time with sub-millisecond precision.
   
   Uses a hybrid two-phase approach for optimal precision and efficiency:
   
   Phase 1 (Coarse Sleep):
   - Uses Thread/sleep() for delays > 2ms
   - Efficient (near-zero CPU usage)
   - Less precise due to OS scheduler granularity
   
   Phase 2 (Fine Busy-Wait):
   - Uses tight loop with System/nanoTime() for final <2ms
   - Precise to ~100 microseconds
   - Uses 10-20% of one CPU core during busy-wait
   
   Parameters:
   - target-time-nanos: Absolute target time in nanoseconds (from System/nanoTime)
   
   Not recommended for:
   - Non-critical timing (UI animations)"
  [target-time-nanos]
  (let [now (System/nanoTime)
        sleep-nanos (- target-time-nanos now)
        max-busy-wait-nanos (* 12 1000 1000)]
    (when (pos? sleep-nanos)

      (when (> sleep-nanos max-busy-wait-nanos)
        (let [coarse-sleep-ms (quot (- sleep-nanos max-busy-wait-nanos) 1000000)]
          (Thread/sleep coarse-sleep-ms)))
      
      (while (< (System/nanoTime) target-time-nanos)
        (Thread/yield)))))

(defn precise-sleep-nanos
  "Sleep for a precise duration in nanoseconds.
   
   Convenience wrapper around precise-sleep-until for relative delays.
   
   Parameters:
   - duration-nanos: Duration to sleep in nanoseconds
   
   Example:
   ```clojure
   ;; Sleep for exactly 5ms
   (precise-sleep-nanos 5000000)
   ```"
  [duration-nanos]
  (precise-sleep-until (+ (System/nanoTime) duration-nanos)))

(defn precise-sleep-us
  "Sleep for a precise duration in microseconds.
   
   Parameters:
   - duration-us: Duration to sleep in microseconds
   
   Example:
   ```clojure
   ;; Sleep for exactly 100 microseconds
   (precise-sleep-us 100)
   ```"
  [duration-us]
  (precise-sleep-nanos (* duration-us 1000)))

(defn precise-sleep-ms
  "Sleep for a precise duration in milliseconds.
   
   More precise than Thread/sleep() but uses more CPU.
   
   Parameters:
   - duration-ms: Duration to sleep in milliseconds
   
   Example:
   ```clojure
   ;; Sleep for exactly 10ms
   (precise-sleep-ms 10)
   ```"
  [duration-ms]
  (precise-sleep-nanos (* duration-ms 1000000)))


;; Timing Utilities


(defn measure-nanos
  "Measure the execution time of a function in nanoseconds.
   
   Parameters:
   - f: Zero-argument function to measure
   
   Returns:
   - Map with :result (function return value) and :nanos (elapsed time)
   
   Example:
   ```clojure
   (measure-nanos #(Thread/sleep 10))
   ;; => {:result nil, :nanos 10123456}
   ```"
  [f]
  (let [start (System/nanoTime)
        result (f)
        end (System/nanoTime)]
    {:result result
     :nanos (- end start)}))

(defn measure-us
  "Measure the execution time of a function in microseconds."
  [f]
  (let [{:keys [result nanos]} (measure-nanos f)]
    {:result result
     :us (quot nanos 1000)}))

(defn measure-ms
  "Measure the execution time of a function in milliseconds."
  [f]
  (let [{:keys [result nanos]} (measure-nanos f)]
    {:result result
     :ms (quot nanos 1000000)}))


(defn nanos->micros
  "Convert nanoseconds to microseconds."
  ^long [nanos]
  (quot nanos 1000))


(defn fps->interval-nanos
  "Convert FPS to frame interval in nanoseconds.
   
   Example:
   ```clojure
   (fps->interval-nanos 30)
   ;; => 33333333  ; 33.33ms in nanoseconds
   ```"
  ^long [fps]
  (long (/ 1000000000 fps)))

(defn fps->interval-us
  "Convert FPS to frame interval in microseconds."
  ^long [fps]
  (long (/ 1000000 fps)))

(defn fps->interval-ms
  "Convert FPS to frame interval in milliseconds."
  ^double [fps]
  (/ 1000.0 fps))

(defn interval-nanos->fps
  "Convert frame interval in nanoseconds to FPS."
  ^double [interval-nanos]
  (/ 1000000000.0 interval-nanos))

(defn interval-us->fps
  "Convert frame interval in microseconds to FPS."
  ^double [interval-us]
  (/ 1000000.0 interval-us))

(defn interval-ms->fps
  "Convert frame interval in milliseconds to FPS."
  ^double [interval-ms]
  (/ 1000.0 interval-ms))
