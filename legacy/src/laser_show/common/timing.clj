(ns laser-show.common.timing
  "Timing utilities for precise time measurement and delays.
   Provides high-resolution timing functions for real-time applications.")

(set! *unchecked-math* :warn-on-boxed)


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


(def ^:const ^long max-busy-wait-nanos 
  "Maximum time to busy-wait (15ms in nanoseconds)"
  5000000)

(defn precise-sleep-until
  "Sleep until target time with sub-millisecond precision.
   
   Uses a hybrid two-phase approach for optimal precision and efficiency:
   
   Phase 1 (Coarse Sleep):
   - Uses Thread/sleep() for delays > 5ms
   - Efficient (near-zero CPU usage)
   - Less precise due to OS scheduler granularity
   
   Phase 2 (Fine Busy-Wait):
   - Uses tight loop with System/nanoTime() for final <5ms
   - Precise to ~100 microseconds
   - Uses 10-20% of one CPU core during busy-wait
   
   Parameters:
   - target-time-nanos: Absolute target time in nanoseconds (from System/nanoTime)
   
   Not recommended for:
   - Non-critical timing (UI animations)"
  [^long target-time-nanos]
  (let [now (System/nanoTime)
        sleep-nanos (unchecked-subtract target-time-nanos now)]
    (when (pos? sleep-nanos)
      (when (> sleep-nanos max-busy-wait-nanos)
        (let [coarse-sleep-ms (quot (unchecked-subtract sleep-nanos max-busy-wait-nanos) 1000000)]
          (Thread/sleep coarse-sleep-ms)))
      (loop []
        (when (< (System/nanoTime) target-time-nanos)
          (Thread/yield)
          (recur))))))

(defn precise-sleep-nanos
  "Sleep for the specified duration in nanoseconds with sub-millisecond precision."
  [^long duration-nanos]
  (precise-sleep-until (unchecked-add (System/nanoTime) duration-nanos)))

(defn precise-sleep-us
  "Sleep for the specified duration in microseconds with sub-millisecond precision."
  [^long duration-us]
  (precise-sleep-nanos (unchecked-multiply duration-us 1000)))

(defn precise-sleep-ms
  "Sleep for the specified duration in milliseconds with sub-millisecond precision."
  [^long duration-ms]
  (precise-sleep-nanos (unchecked-multiply duration-ms 1000000)))


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
     :nanos (unchecked-subtract end start)}))

(defn measure-us
  "Measure the execution time of a function in microseconds."
  [f]
  (let [{:keys [result nanos]} (measure-nanos f)]
    {:result result
     :us (quot (long nanos) 1000)}))

(defn measure-ms
  "Measure the execution time of a function in milliseconds."
  [f]
  (let [{:keys [result nanos]} (measure-nanos f)]
    {:result result
     :ms (quot (long nanos) 1000000)}))


(defn nanos->micros
  "Convert nanoseconds to microseconds."
  ^long [^long nanos]
  (quot nanos 1000))


(defn fps->interval-nanos
  "Convert FPS to frame interval in nanoseconds.
   
   Example:
   ```clojure
   (fps->interval-nanos 30)
   ;; => 33333333  ; 33.33ms in nanoseconds
   ```"
  ^long [^long fps]
  (quot 1000000000 fps))

(defn fps->interval-us
  "Convert FPS to frame interval in microseconds."
  ^long [^long fps]
  (quot 1000000 fps))

(defn fps->interval-ms
  "Convert FPS to frame interval in milliseconds."
  ^double [^double fps]
  (/ 1000.0 fps))

(defn interval-nanos->fps
  "Convert frame interval in nanoseconds to FPS."
  ^double [^double interval-nanos]
  (/ 1000000000.0 interval-nanos))

(defn interval-us->fps
  "Convert frame interval in microseconds to FPS."
  ^double [^double interval-us]
  (/ 1000000.0 interval-us))

(defn interval-ms->fps
  "Convert frame interval in milliseconds to FPS."
  ^double [^double interval-ms]
  (/ 1000.0 interval-ms))
