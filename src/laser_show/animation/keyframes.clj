(ns laser-show.animation.keyframes
  "Keyframe interpolation for effect parameter animation.
   
   Keyframe modulators allow defining exact parameter values at specific
   positions within a period, with automatic linear interpolation between them.
   
   Usage:
   {:keyframes [{:position 0.0 :params {:x 0 :y 0}}
                {:position 0.5 :params {:x 1 :y 1}}
                {:position 1.0 :params {:x 0 :y 0}}]
    :period 2.0
    :loop-mode :loop}"
  (:require
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

(defn interpolate-params
  "Linearly interpolate between two parameter maps.
   
   Parameters:
   - params1: First parameter map (at t=0)
   - params2: Second parameter map (at t=1)
   - t: Interpolation factor (0.0 to 1.0)
   
   Returns: Interpolated parameter map"
  [params1 params2 ^double t]
  (into {}
        (mapv (fn [[k v1]]
                (let [v2 (get params2 k v1)]
                  [k (if (and (number? v1) (number? v2))
                       (+ (* (- 1.0 t) (double v1)) (* t (double v2)))
                       v1)]))
              params1)))


;; Main Keyframe Evaluation


(defn eval-keyframe
  "Evaluate keyframe modulator by interpolating between keyframes.
   
   The keyframe modulator allows users to define exact parameter values at
   specific positions within a period, with automatic linear interpolation
   between them.
   
   Parameters:
   - config: Keyframe modulator config map with:
     - :keyframes - Vector of {:position (0.0-1.0) :params {...}} maps
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

          t (calculate-interp-factor before after phase)]

      (interpolate-params (:params before) (:params after) t))))
