(ns laser-show.profiling.frame-profiler
  "Frame profiler for measuring frame generation performance.
   
   Tracks timing for base frame generation and effect chain application
   in a rolling window of the last 1000 frames. Provides statistics like
   average, min, max, and p95 timings.
   
   Overhead is negligible so profiler can be always-on."
  (:require [clojure.tools.logging :as log]))

(defonce ^:private !samples
  (atom []))

(defonce !profiler-enabled
  (atom true))

;; Recording

(defn record-frame-timing!
  "Record timing for a frame. Adds timestamp and calculates total automatically.
   
   Parameters:
   - base-time-us: Time to generate base frame (microseconds)
   - effects-time-us: Time to apply effect chain (microseconds)
   - effect-count: Number of effects in the chain
   
   Example:
   ```clojure
   (record-frame-timing! {:base-time-us 150
                          :effects-time-us 80
                          :effect-count 3})
   ```"
  [{:keys [base-time-us effects-time-us effect-count]}]
  (when @!profiler-enabled
    (let [sample {:timestamp-ms (System/currentTimeMillis)
                  :base-time-us base-time-us
                  :effects-time-us effects-time-us
                  :total-time-us (+ base-time-us effects-time-us)
                  :effect-count effect-count}]
      (swap! !samples (fn [s] (vec (take-last 1000 (conj s sample))))))))

;; Query Functions

(defn get-samples
  "Get raw timing samples.
   
   Parameters:
   - n: (optional) Number of most recent samples to return. Returns all if not specified.
   
   Returns: Vector of timing samples, most recent last.
   
   Example:
   ```clojure
   (get-samples)     ; all samples (up to 1000)
   (get-samples 100) ; last 100 samples
   ```"
  ([]
   @!samples)
  ([n]
   (vec (take-last n @!samples))))

(defn get-sample-count
  "Get the current number of samples in the buffer."
  []
  (count @!samples))

;; Statistics

(defn- calculate-percentile
  "Calculate a percentile from a sorted sequence of numbers.
   
   Parameters:
   - sorted-values: Sorted sequence of numbers
   - p: Percentile (0.0 to 1.0, e.g., 0.95 for p95)
   
   Returns: Percentile value or nil if no values"
  [sorted-values p]
  (when (seq sorted-values)
    (let [n (count sorted-values)
          index (long (* p (dec n)))]
      (nth sorted-values index))))

(defn get-stats
  "Get statistics for recorded frame timings.
   
   Returns map with:
   - :sample-count - Number of samples in buffer
   - :avg-total-us - Average total frame time (microseconds)
   - :min-total-us - Minimum total frame time
   - :max-total-us - Maximum total frame time
   - :p95-total-us - 95th percentile total frame time
   - :avg-base-us - Average base frame generation time
   - :avg-effects-us - Average effect chain time
   
   Returns nil if no samples recorded."
  []
  (let [samples @!samples]
    (when (seq samples)
      (let [total-times (mapv :total-time-us samples)
            base-times (mapv :base-time-us samples)
            effects-times (mapv :effects-time-us samples)
            sorted-totals (sort total-times)
            
            avg-total (/ (reduce + total-times) (count total-times))
            avg-base (/ (reduce + base-times) (count base-times))
            avg-effects (/ (reduce + effects-times) (count effects-times))]
        
        {:sample-count (count samples)
         :avg-total-us avg-total
         :min-total-us (first sorted-totals)
         :max-total-us (last sorted-totals)
         :p95-total-us (calculate-percentile sorted-totals 0.95)
         :avg-base-us avg-base
         :avg-effects-us avg-effects}))))

(defn get-recent-stats
  "Get statistics for the N most recent samples.
   Useful for seeing current performance vs overall.
   
   Parameters:
   - n: Number of recent samples to analyze
   
   Example:
   ```clojure
   (get-recent-stats 100) ; stats for last 100 frames
   ```"
  [n]
  (let [recent-samples (get-samples n)]
    (when (seq recent-samples)
      (let [total-times (mapv :total-time-us recent-samples)
            base-times (mapv :base-time-us recent-samples)
            effects-times (mapv :effects-time-us recent-samples)
            sorted-totals (sort total-times)
            
            avg-total (/ (reduce + total-times) (count total-times))
            avg-base (/ (reduce + base-times) (count base-times))
            avg-effects (/ (reduce + effects-times) (count effects-times))]
        
        {:sample-count (count recent-samples)
         :avg-total-us avg-total
         :min-total-us (first sorted-totals)
         :max-total-us (last sorted-totals)
         :p95-total-us (calculate-percentile sorted-totals 0.95)
         :avg-base-us avg-base
         :avg-effects-us avg-effects}))))

;; Control Functions

(defn reset-profiler!
  "Clear all recorded samples. Useful for starting fresh measurements."
  []
  (reset! !samples [])
  nil)

(defn enable-profiler!
  "Enable profiling. Profiler is enabled by default."
  []
  (reset! !profiler-enabled true))

(defn disable-profiler!
  "Disable profiling. Stops recording new samples but keeps existing ones."
  []
  (reset! !profiler-enabled false))

(defn profiler-enabled?
  "Check if profiler is currently enabled."
  []
  @!profiler-enabled)

;; Display Helpers

(defn format-stats
  "Format statistics as a human-readable string.
   
   Example:
   ```clojure
   (println (format-stats (get-stats)))
   ```"
  [{:keys [sample-count avg-total-us min-total-us max-total-us p95-total-us
           avg-base-us avg-effects-us] :as stats}]
  (format (str "Frame Profiler Statistics (%d samples):\n"
               "  Total Time:    avg=%.1fµs  min=%dµs  max=%dµs  p95=%dµs\n"
               "  Base Frame:    avg=%.1fµs\n"
               "  Effect Chain:  avg=%.1fµs")
          sample-count
          avg-total-us min-total-us max-total-us p95-total-us
          avg-base-us
          avg-effects-us))

(defn print-stats
  "Print formatted statistics to stdout.
   Parameters:
   - recent-n: (optional) If provided, also prints stats for N recent samples
   
   Note: This function intentionally uses println for user-facing output,
   not logging, as it's meant to be called from REPL for diagnostics."
  ([]
   (if-let [stats (get-stats)]
     (println (format-stats stats))
     (println "No profiling samples recorded yet.")))
  ([recent-n]
   (print-stats)
   (when-let [recent-stats (get-recent-stats recent-n)]
     (println)
     (println (str "Recent " recent-n " frames:"))
     (println (format-stats recent-stats)))))
