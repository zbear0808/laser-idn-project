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
   [laser-show.animation.time :as time]
   [laser-show.common.util :as u]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)


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
  (let [beats (double (time/get-beats-from-context context))
        period' (double period)]
    (if (= loop-mode :once)
      (let [progress (double (time/calculate-once-progress (or time-ms 0) trigger-time
                                                          period' (or time-unit :beats)
                                                          (or bpm 120.0)))]
        (clojure.core/min progress 1.0))
      (u/fmod (/ beats period') 1.0))))

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
       :point-index (let [pc (double point-count)]
                      (if (<= pc 1.0)
                        0.0
                        (double (/ (double point-index) (double (- pc 1.0))))))
       :pos-x (/ (+ x 1.0) 2.0)
       :pos-y (/ (+ y 1.0) 2.0)
       :radial (let [dist (Math/sqrt (+ (* x x) (* y y)))
                     max-dist (double (if normalize? sqrt-2 1.0))]
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


