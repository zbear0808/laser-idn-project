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

;; Main Keyframe Evaluation


(defn eval-keyframe
  "Evaluate keyframe modulator by interpolating between keyframes.
   
   The keyframe modulator allows users to define exact parameter values at
   specific positions within a period. Each keyframe's :interpolation key
   determines the curve used for interpolation TO THE NEXT keyframe.
   
   Parameters:
   - config: Keyframe modulator config map with:
     - :keyframes - Vector of {:position (0.0-1.0) :params {...} :interpolation kw} maps
     - :period - Beats per cycle (default 1.0)
     - :time-unit - :beats or :seconds (default :beats)
     - :loop-mode - :loop or :once (default :loop)
   - context: Modulation context with timing info
   
   Returns: Interpolated params map, or nil if no keyframes"
  [{:keys [keyframes period loop-mode time-unit]
    :or {period 1.0 loop-mode :loop time-unit :beats}
    :as _config}
   {:keys [time-ms bpm trigger-time] :as context}]
  
  (when (seq keyframes)
    (let [;; Calculate position within period (0.0 to 1.0)
          beats (time/get-beats-from-context context)
          phase (if (= loop-mode :once)
                  (let [total-duration (double period)
                        progress (double (time/calculate-once-progress (or time-ms 0) trigger-time
                                                                        total-duration (or time-unit :beats)
                                                                        (or bpm 120.0)))]
                    (clojure.core/min progress 1.0))
                  (mod (/ beats (double period)) 1.0))

          sorted-keyframes (sort-by :position keyframes)
          [before after] (find-surrounding-keyframes sorted-keyframes phase)

          t (calculate-interp-factor before after phase)
          ;; Read interpolation mode from "before" keyframe, default to :linear
          interpolation (or (:interpolation before) :linear)]

      (interp/interpolate-params (:params before) (:params after) t interpolation))))


;; Spatial Keyframe Support


(def ^:private sqrt-2 (Math/sqrt 2.0))

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
