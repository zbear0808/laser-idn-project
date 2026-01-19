(ns laser-show.profiling.frame-profiler
  "Frame profiler for measuring frame generation performance.
   
   Tracks timing for base frame generation and effect chain application
   in a rolling window of the last 1000 frames. Provides statistics like
   average, min, max, and p95 timings.
   
   Overhead is negligible so profiler can be always-on.")

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



