(ns laser-show.animation.keyframes
  "Keyframe interpolation for effect parameter animation.
   
   Keyframe modulators allow defining exact parameter values at specific
   positions within a period, with interpolation between them. Each keyframe's
   :interpolation key determines the curve used for interpolation TO THE NEXT keyframe.
   
   Supported interpolation modes:
   - :linear (default) - Linear interpolation
   - :exp-decay - Ease out (fast start, slow end)
   - :exp-grow - Ease in (slow start, fast end)
   - :step - Hold value until next keyframe
   
   Usage:
   {:keyframes [{:position 0.0 :params {:x 0 :y 0} :interpolation :linear}
                {:position 0.5 :params {:x 1 :y 1} :interpolation :exp-decay}
                {:position 1.0 :params {:x 0 :y 0} :interpolation :step}]
    :period 2.0
    :loop-mode :loop}"
  (:require
   [laser-show.animation.interpolation :as interp]
   [laser-show.animation.time :as time]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)


;; Keyframe Detection


(defn keyframe-modulator?
  "Check if a value is a keyframe modulator config.
   Keyframe modulators are maps with a :keyframes vector."
  [x]
  (and (map? x)
       (contains? x :keyframes)
       (vector? (:keyframes x))))

(defn spatial-keyframe-modulator?
  "Check if a value is a spatial keyframe modulator config.
   Spatial keyframe modulators have :type :spatial-keyframe and a :keyframes vector."
  [x]
  (and (map? x)
       (= (:type x) :spatial-keyframe)
       (contains? x :keyframes)
       (vector? (:keyframes x))))


;; Constants


(def ^:private sqrt-2 (Math/sqrt 2.0))


;; Unified Driver Support


(defn spatial-driver?
  "Returns true if the driver type is spatial (requires per-point evaluation)"
  [driver]
  (contains? #{:point-index :pos-x :pos-y :radial} driver))

(defn calculate-time-position
  "Calculate position in [0,1] based on time and period settings.
   
   Parameters:
   - config: Map with :period, :time-unit, :loop-mode keys
   - time-ms: Current time in milliseconds
   - context: Modulation context with :bpm and :trigger-time
   
   Returns: Position in [0, 1]"
  ^double [{:keys [period time-unit loop-mode]
            :or {period 1.0 time-unit :beats loop-mode :loop}}
           time-ms
           {:keys [bpm trigger-time] :as context}]
  (let [beats (time/get-beats-from-context context)]
    (if (= loop-mode :once)
      (let [total-duration (double period)
            progress (double (time/calculate-once-progress (or time-ms 0) trigger-time
                                                          total-duration (or time-unit :beats)
                                                          (or bpm 120.0)))]
        (clojure.core/min progress 1.0))
      (mod (/ beats (double period)) 1.0))))

(defn calculate-spatial-position
  "Calculate position in [0,1] based on spatial context and driver type.
   Delegates to get-spatial-position for the actual calculation.
   
   Parameters:
   - driver: Spatial driver type (:point-index, :pos-x, :pos-y, :radial)
   - context: Modulation context with per-point data
   - config: Optional config map for driver-specific settings
   
   Returns: Position in [0, 1]"
  (^double [driver context]
   (calculate-spatial-position driver context nil))
  (^double [driver context config]
   ;; Forward to get-spatial-position (defined below in Spatial Keyframe Support section)
   ;; Note: get-spatial-position takes axis as first param (same as driver)
   (let [x (double (or (:x context) 0.0))
         y (double (or (:y context) 0.0))
         point-index (long (or (:point-index context) 0))
         point-count (long (or (:point-count context) 1))
         normalize? (get config :normalize? true)]
     (case driver
       :point-index (if (<= point-count 1)
                      0.0
                      (/ (double point-index) (double (dec point-count))))
       :pos-x (/ (+ x 1.0) 2.0)
       :pos-y (/ (+ y 1.0) 2.0)
       :radial (let [dist (Math/sqrt (+ (* x x) (* y y)))
                     max-dist (if normalize? sqrt-2 1.0)]
                 (min 1.0 (/ dist max-dist)))
       0.0))))

(defn get-keyframe-position
  "Get the current position [0,1] for keyframe evaluation based on driver type.
   
   Routes to time-based or spatial calculation depending on the driver.
   
   Parameters:
   - config: Keyframe config map with :driver, :period, :time-unit, :loop-mode
   - time-ms: Current time in milliseconds
   - context: Modulation context with timing and spatial info
   
   Returns: Position in [0, 1]"
  ^double [{:keys [driver] :as config :or {driver :time}} time-ms context]
  (if (spatial-driver? driver)
    (calculate-spatial-position driver context config)
    (calculate-time-position config time-ms context)))

(defn get-edge-behavior
  "Get edge behavior, defaulting based on driver type.
   
   Spatial drivers default to :clamp (stay at edges).
   Time drivers default to :wrap (loop around).
   
   Parameters:
   - config: Map with :driver and :edge-behavior keys
   
   Returns: Edge behavior keyword (:clamp or :wrap)"
  [{:keys [driver edge-behavior] :or {driver :time}}]
  (or edge-behavior
      (if (spatial-driver? driver) :clamp :wrap)))


;; Keyframe Interpolation Helpers


(defn find-surrounding-keyframes
  "Find the keyframes before and after the given phase position.
   Handles wrap-around for looping.
   
   Parameters:
   - sorted-keyframes: Keyframes sorted by :position
   - phase: Position within period (0.0 to 1.0)
   
   Returns: [before-keyframe after-keyframe]"
  [sorted-keyframes phase]
  (let [n (count sorted-keyframes)]
    (cond
      (= n 1) [(first sorted-keyframes) (first sorted-keyframes)]
      
      :else
      (let [phase' (double phase)
            ;; Find first keyframe >= phase
            after-idx (->> sorted-keyframes
                          (map-indexed vector)
                          (filter (fn [[_ kf]] (>= (double (:position kf)) phase')))
                          first
                          first)]
        (if after-idx
          (let [before-idx (if (zero? (long after-idx)) (dec n) (dec (long after-idx)))]
            [(nth sorted-keyframes before-idx)
             (nth sorted-keyframes after-idx)])
          ;; Phase is past all keyframes - wrap to first
          [(last sorted-keyframes) (first sorted-keyframes)])))))

(defn calculate-interp-factor
  "Calculate linear interpolation factor between two keyframes.
   
   Parameters:
   - before: Keyframe before current phase
   - after: Keyframe after current phase
   - phase: Current position within period (0.0 to 1.0)
   
   Returns: Interpolation factor t (0.0 to 1.0)"
  ^double [before after ^double phase]
  (let [p1 (double (:position before))
        p2 (double (:position after))
        ;; Handle wrap-around case
        range-val (if (< p2 p1)
                    (+ (- 1.0 p1) p2)
                    (- p2 p1))
        offset (if (< phase p1)
                (+ (- 1.0 p1) phase)
                (- phase p1))]
   (if (zero? range-val) 0.0 (/ offset range-val))))

(defn find-surrounding-keyframes-clamped
 "Find the keyframes before and after the given position, without wrap-around.
  Used for spatial keyframes where clamping to first/last is preferred.
  
  Parameters:
  - sorted-keyframes: Keyframes sorted by :position
  - position: Position value (0.0 to 1.0)
  
  Returns: [before-keyframe after-keyframe]"
 [sorted-keyframes ^double position]
 (let [n (count sorted-keyframes)]
   (cond
     (= n 1)
     [(first sorted-keyframes) (first sorted-keyframes)]
     
     ;; Position at or before first keyframe
     (<= position (double (:position (first sorted-keyframes))))
     [(first sorted-keyframes) (first sorted-keyframes)]
     
     ;; Position at or after last keyframe
     (>= position (double (:position (last sorted-keyframes))))
     [(last sorted-keyframes) (last sorted-keyframes)]
     
     :else
     ;; Find the surrounding keyframes
     (let [after-idx (->> sorted-keyframes
                         (map-indexed vector)
                         (filter (fn [[_ kf]] (> (double (:position kf)) position)))
                         first
                         first)]
       (if after-idx
         [(nth sorted-keyframes (dec (long after-idx)))
          (nth sorted-keyframes after-idx)]
         ;; Shouldn't happen, but fallback to last
         [(last sorted-keyframes) (last sorted-keyframes)])))))


;; Main Keyframe Evaluation


(defn eval-keyframe
  "Evaluate keyframe modulator by interpolating between keyframes.
   
   The keyframe modulator allows users to define exact parameter values at
   specific positions within a period. Each keyframe's :interpolation key
   determines the curve used for interpolation TO THE NEXT keyframe.
   
   Supports both time-based drivers (default) and spatial drivers for per-point
   evaluation based on point position in space.
   
   Parameters:
   - config: Keyframe modulator config map with:
     - :keyframes - Vector of {:position (0.0-1.0) :params {...} :interpolation kw} maps
     - :driver - Driver type (:time, :point-index, :pos-x, :pos-y, :radial) (default :time)
     - :edge-behavior - Edge behavior (:wrap or :clamp) (default based on driver type)
     - :period - Beats per cycle for time driver (default 1.0)
     - :time-unit - :beats or :seconds (default :beats)
     - :loop-mode - :loop or :once (default :loop)
   - time-ms-or-context: Either time in milliseconds (number) or modulation context (map)
   - context: Optional modulation context with timing (:bpm, :trigger-time) and spatial info
              (:x, :y, :point-index, :point-count) for spatial drivers
   
   Returns: Interpolated params map, or nil if no keyframes"
  ([config time-ms-or-context]
   ;; Handle both calling conventions:
   ;; - (eval-keyframe config time-ms) - time-ms is a number, no context
   ;; - (eval-keyframe config context) - context is a map with :time-ms inside
   (if (map? time-ms-or-context)
     (eval-keyframe config (:time-ms time-ms-or-context) time-ms-or-context)
     (eval-keyframe config time-ms-or-context nil)))
  ([config time-ms context]
   (when-let [keyframes (seq (:keyframes config))]
     (let [;; Get position based on driver type (time or spatial)
           position (get-keyframe-position config time-ms context)
           
           ;; Get edge behavior for keyframe finding
           edge-behavior (get-edge-behavior config)
           
           sorted-keyframes (sort-by :position keyframes)
           
           ;; Choose keyframe finder based on edge behavior
           ;; :wrap uses find-surrounding-keyframes (loops around)
           ;; :clamp uses find-surrounding-keyframes-clamped (stays at edges)
           [before after] (if (= edge-behavior :clamp)
                           (find-surrounding-keyframes-clamped sorted-keyframes position)
                           (find-surrounding-keyframes sorted-keyframes position))

           t (calculate-interp-factor before after position)
           ;; Read interpolation mode from "before" keyframe, default to :linear
           interpolation (or (:interpolation before) :linear)]

       (interp/interpolate-params (:params before) (:params after) t interpolation)))))


;; Spatial Keyframe Support


(defn get-spatial-position
  "Calculate normalized position (0.0-1.0) based on spatial axis type.
   
   Parameters:
   - axis: Keyword specifying the spatial dimension
     - :point-index - Based on point index within frame
     - :pos-x - Based on x coordinate (-1..+1 -> 0..1)
     - :pos-y - Based on y coordinate (-1..+1 -> 0..1)
     - :radial - Based on distance from origin
     - :angle - Based on angle from origin (atan2)
   - context: Modulation context with per-point data
   - config: Optional config map with :normalize? for radial axis
   
   Returns: Normalized position (0.0 to 1.0)"
  ^double [axis context config]
  (case axis
    :point-index
    (let [point-index (long (or (:point-index context) 0))
          point-count (long (or (:point-count context) 1))]
      (if (<= point-count 1)
        0.0
        (/ (double point-index) (double (dec point-count)))))
    
    :pos-x
    (let [x (double (or (:x context) 0.0))]
      (/ (+ x 1.0) 2.0))
    
    :pos-y
    (let [y (double (or (:y context) 0.0))]
      (/ (+ y 1.0) 2.0))
    
    :radial
    (let [x (double (or (:x context) 0.0))
          y (double (or (:y context) 0.0))
          dist (Math/sqrt (+ (* x x) (* y y)))
          normalize? (get config :normalize? true)
          max-dist (if normalize? sqrt-2 1.0)]
      (min 1.0 (/ dist max-dist)))
    
    :angle
    (let [x (double (or (:x context) 0.0))
          y (double (or (:y context) 0.0))]
      (if (and (zero? x) (zero? y))
        0.0
        (let [angle (Math/atan2 y x)
              ;; atan2 returns -PI to PI, normalize to 0 to 1
              normalized (/ (+ angle Math/PI) (* 2.0 Math/PI))]
          normalized)))
    
    ;; Default to 0.0 for unknown axis
    0.0))

(defn eval-spatial-keyframe
  "Evaluate spatial keyframe modulator by interpolating based on spatial position.
   
   The spatial keyframe modulator allows defining values at specific spatial
   positions, with interpolation between them based on point position in space.
   
   Parameters:
   - config: Spatial keyframe modulator config map with:
     - :keyframes - Vector of {:position (0.0-1.0) :value number :interpolation kw} maps
     - :axis - Spatial axis keyword (:point-index, :pos-x, :pos-y, :radial, :angle)
     - :normalize? - Boolean for radial normalization (default true)
     - :min, :max - Optional fallback range when no keyframes defined
     - :value - Optional explicit fallback value
   - context: Modulation context with per-point data (:x, :y, :point-index, :point-count)
   
   Returns: Interpolated value as double, or fallback value if no keyframes"
  [{:keys [keyframes axis normalize? min max value]
    :or {axis :point-index normalize? true}
    :as config}
   context]
  
  (if (seq keyframes)
    (let [;; Calculate spatial position (0.0 to 1.0)
          position (get-spatial-position axis context config)
          
          ;; Sort keyframes by position
          sorted-keyframes (sort-by :position keyframes)
          
          ;; Find surrounding keyframes (clamped, no wrap-around)
          [before after] (find-surrounding-keyframes-clamped sorted-keyframes position)
          
          ;; Calculate linear interpolation factor
          t (calculate-interp-factor before after position)
          
          ;; Get interpolation mode from before keyframe
          interpolation (or (:interpolation before) :linear)
          
          ;; Apply interpolation curve
          curved-t (interp/apply-interpolation t interpolation)
          
          ;; Get values from keyframes
          v1 (double (or (:value before) 0.0))
          v2 (double (or (:value after) 0.0))]
      
      ;; Interpolate between values
      (interp/interpolate-value v1 v2 curved-t))
    
    ;; Fallback when no keyframes defined:
    ;; Return :value if present, otherwise midpoint of :min/:max, otherwise 0.0
    (double (or value
                (when (and min max)
                  (/ (+ (double min) (double max)) 2.0))
                0.0))))


;; Spatial Keyframe Compiler


(defn- compile-position-fn
  "Create a position calculation function for a specific axis.
   Returns an optimized function (fn [x y idx point-count] -> position).
   
   Pre-computes axis-specific constants to minimize per-point calculations."
  [axis normalize?]
  (case axis
    :point-index
    (fn ^double [^double _x ^double _y ^long idx ^long point-count]
      (if (<= point-count 1)
        0.0
        (/ (double idx) (double (dec point-count)))))
    
    :pos-x
    (fn ^double [^double x ^double _y ^long _idx ^long _point-count]
      (/ (+ x 1.0) 2.0))
    
    :pos-y
    (fn ^double [^double _x ^double y ^long _idx ^long _point-count]
      (/ (+ y 1.0) 2.0))
    
    :radial
    (let [max-dist (if normalize? sqrt-2 1.0)]
      (fn ^double [^double x ^double y ^long _idx ^long _point-count]
        (let [dist (Math/sqrt (+ (* x x) (* y y)))]
          (clojure.core/min 1.0 (/ dist max-dist)))))
    
    :angle
    (let [two-pi (* 2.0 Math/PI)]
      (fn ^double [^double x ^double y ^long _idx ^long _point-count]
        (if (and (zero? x) (zero? y))
          0.0
          (let [angle (Math/atan2 y x)
                normalized (/ (+ angle Math/PI) two-pi)]
            normalized))))
    
    ;; Default - return 0.0
    (constantly 0.0)))

(defn- precompute-keyframe-data
  "Pre-process keyframes for fast lookup.
   Returns a vector of maps with pre-computed values."
  [sorted-keyframes]
  (let [n (count sorted-keyframes)]
    (mapv (fn [i]
            (let [kf (nth sorted-keyframes i)
                  next-kf (if (< i (dec n))
                            (nth sorted-keyframes (inc i))
                            (first sorted-keyframes))  ;; Would only happen with wrap-around
                  pos (double (:position kf))
                  next-pos (double (:position next-kf))
                  value (double (or (:value kf) 0.0))
                  next-value (double (or (:value next-kf) 0.0))
                  interpolation (or (:interpolation kf) :linear)]
              {:position pos
               :next-position next-pos
               :value value
               :next-value next-value
               :interpolation interpolation}))
          (range n))))

(defn compile-spatial-keyframe
  "Compile spatial keyframe modulator to optimized per-point function.
   Pre-sorts keyframes, pre-computes axis calculation constants,
   and captures interpolation logic in closure.
   
   Parameters:
   - config: Spatial keyframe config map with :keyframes, :axis, :normalize? keys
   - point-count: Total number of points in frame
   
   Returns: (fn [^double x ^double y ^long idx] -> double)
   
   Note: The returned function handles all spatial axes (point-index, pos-x,
   pos-y, radial, angle) with axis-specific optimizations baked in."
  [config point-count]
  (let [{:keys [keyframes axis normalize? min max value]
         :or {axis :point-index normalize? true}} config
        point-count' (long point-count)]
    
    (if (seq keyframes)
      (let [;; Pre-sort keyframes by position
            sorted-keyframes (vec (sort-by :position keyframes))
            n (count sorted-keyframes)
            
            ;; Pre-compute position function for this axis
            pos-fn (compile-position-fn axis normalize?)
            
            ;; Pre-compute keyframe data for fast lookup
            kf-data (precompute-keyframe-data sorted-keyframes)
            
            ;; Extract position array for binary search
            positions (double-array (map :position sorted-keyframes))]
        
        (fn ^double [^double x ^double y ^long idx]
          (let [;; Calculate spatial position (0.0 to 1.0)
                position (pos-fn x y idx point-count')
                
                ;; Find surrounding keyframes using clamped lookup
                ;; (same logic as find-surrounding-keyframes-clamped but inlined)
                first-pos (aget positions 0)
                last-pos (aget positions (dec n))]
            
            (cond
              ;; Before first keyframe - clamp to first
              (<= position first-pos)
              (:value (first kf-data))
              
              ;; After last keyframe - clamp to last
              (>= position last-pos)
              (:value (last kf-data))
              
              :else
              ;; Find the surrounding keyframes
              (let [;; Linear search for small arrays (usually < 10 keyframes)
                    after-idx (loop [i 0]
                                (if (>= i n)
                                  nil
                                  (if (> (aget positions i) position)
                                    i
                                    (recur (inc i)))))
                    before-idx (if after-idx (dec (long after-idx)) (dec n))
                    before-kf (nth kf-data before-idx)
                    after-kf (nth kf-data (or after-idx 0))
                    
                    ;; Calculate interpolation factor
                    p1 (:position before-kf)
                    p2 (if after-idx
                         (:next-position before-kf)
                         1.0)
                    range-val (- p2 p1)
                    t (if (zero? range-val) 0.0 (/ (- position p1) range-val))
                    
                    ;; Apply interpolation curve
                    curved-t (interp/apply-interpolation t (:interpolation before-kf))
                    
                    ;; Interpolate values
                    v1 (:value before-kf)
                    v2 (double (or (:value after-kf) 0.0))]
                
                (interp/interpolate-value v1 v2 curved-t))))))
      
      ;; No keyframes - return constant function
      (let [fallback-value (double (or value
                                       (when (and min max)
                                         (/ (+ (double min) (double max)) 2.0))
                                       0.0))]
        (constantly fallback-value)))))
