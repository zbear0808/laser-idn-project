(ns laser-show.animation.modulator-evaluators
  "Modulator evaluation implementations.
   
   Contains all eval-* functions for different modulator types.
   The modulator-evaluators map is used by modulators.clj during
   registration to look up evaluator functions.
   
   For runtime evaluation, use laser-show.animation.modulation/evaluate-modulator
   which looks up evaluators via the registry (reg/get-evaluator).
   
   Each evaluator takes [config context] and returns a numeric value."
  (:require
   [laser-show.animation.time :as time]
   [laser-show.animation.interpolation :as interp]
   [laser-show.common.util :as u]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)


;; Phase Calculation Helpers


(defn- calculate-loop-mode-phase
  "Calculate phase for loop-mode modulators using effective-beats.
   Returns phase as a double."
  ^double [period phase time-unit context]
  (time/calculate-modulator-phase context period phase :loop period time-unit))

(defn- calculate-wave-phase
  "Calculate phase for standard wave modulators (sine, triangle).
   Handles both loop and once modes. Returns final phase as double."
  ^double [{:keys [period phase loop-mode time-unit] :as config} context]
  (if (= loop-mode :once)
    (:final-phase (time/calculate-once-mode-phase-data config context))
    (calculate-loop-mode-phase period phase time-unit context)))

(defn- calculate-sawtooth-phase
  "Calculate phase for sawtooth modulator with edge-case handling.
   Prevents wrap-around to 0 at cycle end. Returns final phase as double."
  ^double [{:keys [period phase loop-mode time-unit] :as config} context]
  (if (= loop-mode :once)
    (let [{:keys [final-phase total-phase done?]} (time/calculate-once-mode-phase-data config context)
          total-phase' (double total-phase)
          final-phase' (double final-phase)
          ;; Handle edge case during animation: when total-phase wraps near integer
          adjusted-cycle-phase (let [raw-phase (u/fmod total-phase' 1.0)]
                                 (if (and (< raw-phase 0.001) (>= total-phase' 0.999))
                                   0.9999
                                   raw-phase))]
      ;; After completion: use 0.9999 if final phase wraps to 0
      (if done?
        (if (< final-phase' 0.001) 0.9999 final-phase')
        adjusted-cycle-phase))
    (calculate-loop-mode-phase period phase time-unit context)))

(defn- calculate-square-phase
  "Calculate phase for square modulator.
   Returns total-phase during animation, final-phase after completion.
   This allows square-fn to apply mod itself during animation."
  ^double [{:keys [period phase loop-mode time-unit] :as config} context]
  (if (= loop-mode :once)
    (let [{:keys [final-phase total-phase done?]} (time/calculate-once-mode-phase-data config context)
          final-phase' (double final-phase)
          total-phase' (double total-phase)]
      (if done?
        ;; After completion: return clamped final phase
        (if (< final-phase' 0.001) 0.9999 final-phase')
        ;; During animation: return total-phase (square-fn will mod it)
        total-phase'))
    (calculate-loop-mode-phase period phase time-unit context)))

(defn- calculate-random-phase
  "Calculate phase for random modulator.
   Note: Random doesn't use phase offset in once-mode calculations."
  ^double [{:keys [period once-periods time-unit loop-mode]}
           {:keys [time-ms bpm trigger-time] :as context}]
  (if (= loop-mode :once)
    (let [num-cycles (double once-periods)
          total-duration (* (double period) num-cycles)
          progress (double (time/calculate-once-progress
                            time-ms
                            trigger-time
                            total-duration
                            time-unit
                            bpm))
          total-phase (* progress num-cycles)]
      ;; Hold at final position when done
      (if (>= progress 1.0) num-cycles total-phase))
    ;; Loop mode: pass 0.0 for phase (random uses seed-based approach)
    (time/calculate-modulator-phase context period 0.0 :loop period time-unit)))


;; Wave Modulators


(defn- eval-sine
  "Evaluate sine wave modulator.
   In once mode, completes once-periods cycles then holds at the final position."
  [{:keys [min max] :as config} context]
  (let [phase (calculate-wave-phase config context)]
    (time/oscillate (double min) (double max) phase :sine)))

(defn- eval-triangle
  "Evaluate triangle wave modulator.
   In once mode, completes once-periods cycles then holds at the final position."
  [{:keys [min max] :as config} context]
  (let [phase (calculate-wave-phase config context)]
    (time/oscillate (double min) (double max) phase :triangle)))

(defn- eval-sawtooth
  "Evaluate sawtooth wave modulator.
   In once mode, completes once-periods cycles then holds at the final position.
   Uses effective-beats for smooth BPM-change animation in loop mode."
  [{:keys [min max] :as config} context]
  (let [phase (calculate-sawtooth-phase config context)]
    (time/oscillate (double min) (double max) phase :sawtooth)))

(defn- eval-square
  "Evaluate square wave modulator.
   In once mode, completes once-periods cycles then holds at the final position.
   Uses effective-beats for smooth BPM-change animation in loop mode."
  [{:keys [min max duty-cycle] :as config} context]
  (let [phase (calculate-square-phase config context)
        cycle-phase (u/fmod phase 1.0)]
    (if (< cycle-phase (double duty-cycle))
      (double max)
      (double min))))


;; Decay Modulators


(defn- eval-exp-decay
  "Evaluate exponential decay (beat-synced).
   Uses effective-beats for smooth BPM-change animation."
  [{:keys [min max decay-type]} context]
  (let [beats (time/get-beats-from-context context)
        phase (u/fmod beats 1.0)
        start-v (double max)
        end-v (double min)]
    (case decay-type
      :exp (let [range-exp (- start-v end-v)
                 decay-factor (Math/exp (* (- phase) 3.0))]
             (+ end-v (* decay-factor range-exp)))
      ;; :linear is default
      (let [range-v (- end-v start-v)]
        (+ start-v (* phase range-v))))))


;; Random Modulators


(defn- eval-random
  "Evaluate random modulator.
   In once mode, generates random values through once-periods cycles then holds at the final position.
   Uses effective-beats for smooth BPM-change animation in loop mode."
  [{:keys [min max period changes-per-beat] :as config} context]
  (let [phase (calculate-random-phase config context)
        changes-in-period (double (or changes-per-beat (/ 1.0 (double period))))
        seed (long (* phase changes-in-period))
        rng (java.util.Random. seed)
        t (.nextDouble ^java.util.Random rng)
        range-v (- (double max) (double min))]
    (+ (double min) (* t range-v))))


;; External Input Modulators (MIDI/OSC)


(defn- eval-midi
  "Evaluate MIDI CC modulator.
   Reads from generic input-values."
  [{:keys [channel cc min max]} {:keys [input-values]}]
  (let [cc-val (double (get input-values [:midi channel cc] 0))
        range-v (- (double max) (double min))]
    (+ (double min) (* (/ cc-val 127.0) range-v))))

(defn- eval-osc
  "Evaluate OSC parameter modulator.
   Reads from generic input-values."
  [{:keys [path min max]} {:keys [input-values]}]
  (let [osc-val (double (get input-values [:osc path] 0.0))
        range-v (- (double max) (double min))]
    (+ (double min) (* osc-val range-v))))

(defn- eval-input
  "Evaluate generic input modulator."
  [{:keys [source-key min max raw-min raw-max]} {:keys [input-values]}]
  (let [raw-min (double (or raw-min 0.0))
        raw-max (double (or raw-max 1.0))
        raw-val (double (get input-values source-key raw-min))
        raw-range (if (zero? (- raw-max raw-min)) 1.0 (- raw-max raw-min))
        norm-val (/ (- raw-val raw-min) raw-range)
        range-v (- (double max) (double min))]
    (+ (double min) (* norm-val range-v))))


;; Per-Point Modulators


(defn- eval-point-index
  "Evaluate point index modulator."
  [{:keys [min max wrap?]} {:keys [point-index point-count]}]
  (if (and point-index point-count (pos? (double point-count)))
    (let [t (/ (double point-index) (clojure.core/max 1.0 (dec (double point-count))))
          range-v (- (double max) (double min))]
      (+ (double min) (* (if wrap? (u/fmod t 1.0) t) range-v)))
    (double min)))

(defn- eval-pos-x
  "Evaluate position X modulator."
  [{:keys [min max]} {:keys [x]}]
  (if x
    (let [t (/ (+ (double x) 1.0) 2.0)  ; normalize -1..1 to 0..1
          range-v (- (double max) (double min))]
      (+ (double min) (* t range-v)))
    (double min)))

(defn- eval-pos-y
  "Evaluate position Y modulator."
  [{:keys [min max]} {:keys [y]}]
  (if y
    (let [t (/ (+ (double y) 1.0) 2.0)
          range-v (- (double max) (double min))]
      (+ (double min) (* t range-v)))
    (double min)))

(defn- eval-radial
  "Evaluate position radial modulator."
  [{:keys [min max normalize?]} {:keys [x y]}]
  (if (and x y)
    (let [dist (Math/sqrt (+ (* (double x) (double x))
                             (* (double y) (double y))))
          max-dist (if normalize? (Math/sqrt 2.0) 1.0)
          t (clojure.core/min 1.0 (/ dist max-dist))
          range-v (- (double max) (double min))]
      (+ (double min) (* t range-v)))
    (double min)))

(defn- eval-angle
  "Evaluate angle modulator - maps point angle from origin to range.
   Uses atan2(y, x) normalized to 0-2π."
  [{:keys [min max] :or {min 0.0 max 1.0}}
   {:keys [x y]}]
  (if (and x y)
    (let [raw-angle (Math/atan2 (double y) (double x))
          ;; Normalize from -π..π to 0..2π
          normalized (if (neg? raw-angle)
                       (+ raw-angle (* 2.0 Math/PI))
                       raw-angle)
          ;; Map to 0..1
          t (/ normalized (* 2.0 Math/PI))
          range-v (- (double max) (double min))]
      (+ (double min) (* t range-v)))
    (double min)))


;; Unified Keyframe Param Modulator
;; For per-point evaluation of spatial keyframe drivers


(def ^:private sqrt-2 (Math/sqrt 2.0))

(defn- calculate-spatial-position-for-driver
  "Calculate position [0,1] for a spatial driver given point coordinates.
   Returns double. Note: No ^double type hint due to JVM limitation (>4 primitive args)."
  [driver x y point-index point-count normalize?]
  (let [x (double x)
        y (double y)
        point-index (long point-index)
        point-count (long point-count)]
    (double
     (case driver
       :point-index (let [pc (double point-count)
                          denom (- pc 1.0)]
                      (if (<= pc 1.0)
                        0.0
                        (/ (double point-index) denom)))
       :pos-x (/ (+ x 1.0) 2.0)
       :pos-y (/ (+ y 1.0) 2.0)
       :radial (let [dist (Math/sqrt (+ (* x x) (* y y)))
                     max-dist (double (if normalize? sqrt-2 1.0))]
                 (clojure.core/min 1.0 (/ dist max-dist)))
       0.0))))

(defn- find-surrounding-keyframes-clamped
  "Find the keyframes before and after the given position, clamped to edges."
  [sorted-keyframes ^double position]
  (let [n (count sorted-keyframes)]
    (cond
      (= n 1)
      [(first sorted-keyframes) (first sorted-keyframes)]

      (<= position (double (:position (first sorted-keyframes))))
      [(first sorted-keyframes) (first sorted-keyframes)]

      (>= position (double (:position (last sorted-keyframes))))
      [(last sorted-keyframes) (last sorted-keyframes)]

      :else
      (let [after-idx (->> sorted-keyframes
                           (map-indexed vector)
                           (filter (fn [[_ kf]] (> (double (:position kf)) position)))
                           first
                           first)]
        (if after-idx
          [(nth sorted-keyframes (dec (long after-idx)))
           (nth sorted-keyframes after-idx)]
          [(last sorted-keyframes) (last sorted-keyframes)])))))

(defn- calculate-interp-factor
  "Calculate interpolation factor between two keyframes."
  ^double [before after ^double position]
  (let [p1 (double (:position before))
        p2 (double (:position after))
        range-val (- p2 p1)
        offset (- position p1)]
    (if (zero? range-val) 0.0 (/ offset range-val))))

(defn eval-unified-keyframe-param
  "Evaluate a unified keyframe modulator for a specific param key.
   Uses spatial driver to calculate position based on point coordinates.
   
   Config keys:
   - :keyframe-mod - The full keyframe modulator config
   - :param-key - The param key to extract from interpolated result
   
   Returns: The interpolated value for the specified param key"
  [{:keys [keyframe-mod param-key]} context]
  (let [{:keys [keyframes driver normalize?]
         :or {driver :point-index normalize? true}} keyframe-mod
        {:keys [x y point-index point-count]} context]
    (if (seq keyframes)
      (let [position (calculate-spatial-position-for-driver
                      driver
                      (double (or x 0.0))
                      (double (or y 0.0))
                      (long (or point-index 0))
                      (long (or point-count 1))
                      normalize?)
            sorted-keyframes (sort-by :position keyframes)
            [before after] (find-surrounding-keyframes-clamped sorted-keyframes position)
            t (calculate-interp-factor before after position)
            interpolation (or (:interpolation before) :linear)
            curved-t (interp/apply-interpolation t interpolation)
            ;; Get param values from keyframes
            v1 (get-in before [:params param-key] 0.0)
            v2 (get-in after [:params param-key] 0.0)]
        ;; Interpolate if both are numbers, otherwise return v1
        (if (and (number? v1) (number? v2))
          (interp/interpolate-value (double v1) (double v2) curved-t)
          v1))
      ;; No keyframes - return default
      0.0)))


;; Modulator Evaluators Registry


(def modulator-evaluators
  "Map of modulator type keywords to their evaluation functions.
   Each function takes [config context] and returns a value."
  {:sine         eval-sine
   :triangle     eval-triangle
   :sawtooth     eval-sawtooth
   :square       eval-square
   :exp-decay    eval-exp-decay
   :random       eval-random
   :midi         eval-midi
   :osc          eval-osc
   :input        eval-input
   :point-index  eval-point-index
   :pos-x        eval-pos-x
   :pos-y        eval-pos-y
   :radial       eval-radial
   :angle        eval-angle
   :unified-keyframe-param eval-unified-keyframe-param})


;; Per-Point Modulator Compilers
;; These functions pre-compute constants and return optimized (fn [x y idx] -> value)


(defn compile-pos-x
  "Compile position X modulator to optimized per-point function.
   Pre-computes min, max, and range values.
   
   Parameters:
   - config: Modulator config map with :min, :max keys
   - point-count: Total number of points in frame (unused for pos-x)
   
   Returns: (fn [^double x ^double y ^long idx] -> double)"
  [config _point-count]
  (let [min-v (double (get config :min 0.0))
        max-v (double (get config :max 1.0))
        range-v (- max-v min-v)]
    (fn ^double [^double x ^double _y ^long _idx]
      (let [t (/ (+ x 1.0) 2.0)]  ; normalize -1..1 to 0..1
        (+ min-v (* t range-v))))))

(defn compile-pos-y
  "Compile position Y modulator to optimized per-point function.
   Pre-computes min, max, and range values.
   
   Parameters:
   - config: Modulator config map with :min, :max keys
   - point-count: Total number of points in frame (unused for pos-y)
   
   Returns: (fn [^double x ^double y ^long idx] -> double)"
  [config _point-count]
  (let [min-v (double (get config :min 0.0))
        max-v (double (get config :max 1.0))
        range-v (- max-v min-v)]
    (fn ^double [^double _x ^double y ^long _idx]
      (let [t (/ (+ y 1.0) 2.0)]  ; normalize -1..1 to 0..1
        (+ min-v (* t range-v))))))

(defn compile-radial
  "Compile radial distance modulator to optimized per-point function.
   Pre-computes min, max, range, and max-distance values.
   
   Parameters:
   - config: Modulator config map with :min, :max, :normalize? keys
   - point-count: Total number of points in frame (unused for radial)
   
   Returns: (fn [^double x ^double y ^long idx] -> double)"
  [config _point-count]
  (let [min-v (double (get config :min 0.0))
        max-v (double (get config :max 1.0))
        range-v (- max-v min-v)
        normalize? (get config :normalize? true)
        max-dist (if normalize? (Math/sqrt 2.0) 1.0)]
    (fn ^double [^double x ^double y ^long _idx]
      (let [dist (Math/sqrt (+ (* x x) (* y y)))
            t (clojure.core/min 1.0 (/ dist max-dist))]
        (+ min-v (* t range-v))))))

(defn compile-point-index
  "Compile point index modulator to optimized per-point function.
   Pre-computes min, max, range, and denominator for index normalization.
   
   Parameters:
   - config: Modulator config map with :min, :max, :wrap? keys
   - point-count: Total number of points in frame
   
   Returns: (fn [^double x ^double y ^long idx] -> double)"
  [config point-count]
  (let [min-v (double (get config :min 0.0))
        max-v (double (get config :max 1.0))
        range-v (- max-v min-v)
        wrap? (boolean (get config :wrap? false))
        ;; Pre-compute denominator - avoid div by zero
        denom (clojure.core/max 1.0 (double (dec (long point-count))))]
    (if (pos? (long point-count))
      (fn ^double [^double _x ^double _y ^long idx]
        (let [t (/ (double idx) denom)
              t' (if wrap? (u/fmod t 1.0) t)]
          (+ min-v (* t' range-v))))
      ;; Edge case: no points - return min
      (constantly min-v))))

(defn compile-angle
  "Compile angle modulator to optimized per-point function.
   Pre-computes min, max, range, and 2*PI constant.
   
   Parameters:
   - config: Modulator config map with :min, :max keys
   - point-count: Total number of points in frame (unused for angle)
   
   Returns: (fn [^double x ^double y ^long idx] -> double)"
  [config _point-count]
  (let [min-v (double (get config :min 0.0))
        max-v (double (get config :max 1.0))
        range-v (- max-v min-v)
        two-pi (* 2.0 Math/PI)]
    (fn ^double [^double x ^double y ^long _idx]
      (let [raw-angle (Math/atan2 y x)
            ;; Normalize from -π..π to 0..2π
            normalized (if (neg? raw-angle)
                         (+ raw-angle two-pi)
                         raw-angle)
            ;; Map to 0..1
            t (/ normalized two-pi)]
        (+ min-v (* t range-v))))))


(defn compile-unified-keyframe-param
  "Compile unified keyframe param modulator to optimized per-point function.
   Pre-sorts keyframes, pre-computes position functions, and creates a fast lookup.
   
   Parameters:
   - config: Config map with :keyframe-mod and :param-key
   - point-count: Total number of points in frame
   
   Returns: (fn [^double x ^double y ^long idx] -> double)"
  [config point-count]
  (let [{:keys [keyframe-mod param-key]} config
        {:keys [keyframes driver normalize?]
         :or {driver :point-index normalize? true}} keyframe-mod
        point-count' (long point-count)]

    (if (seq keyframes)
      (let [;; Pre-sort keyframes by position
            sorted-keyframes (vec (sort-by :position keyframes))
            n (count sorted-keyframes)

            ;; Pre-compute position calculation fn based on driver
            ;; Note: denom is pre-computed outside the fn for primitive performance
            pos-fn (case driver
                     :point-index
                     (let [pc (double point-count')
                           pc-minus-1 (- pc 1.0)
                           denom (clojure.core/max 1.0 pc-minus-1)]
                       (fn ^double [^double _x ^double _y ^long idx ^long _pc]
                         (if (<= pc 1.0)
                           0.0
                           (/ (double idx) denom))))

                     :pos-x
                     (fn ^double [^double x ^double _y ^long _idx ^long _pc]
                       (/ (+ x 1.0) 2.0))

                     :pos-y
                     (fn ^double [^double _x ^double y ^long _idx ^long _pc]
                       (/ (+ y 1.0) 2.0))

                     :radial
                     (let [max-dist (double (if normalize? sqrt-2 1.0))]
                       (fn ^double [^double x ^double y ^long _idx ^long _pc]
                         (let [dist (Math/sqrt (+ (* x x) (* y y)))]
                           (clojure.core/min 1.0 (/ dist max-dist)))))

                     ;; Default
                     (constantly 0.0))

            ;; Pre-extract positions array for fast lookup
            positions (double-array (map :position sorted-keyframes))

            ;; Pre-extract param values for this param-key
            param-values (double-array
                          (map #(double (get-in % [:params param-key] 0.0))
                               sorted-keyframes))

            ;; Pre-extract interpolation modes
            interp-modes (mapv #(or (:interpolation %) :linear) sorted-keyframes)]

        (fn ^double [^double x ^double y ^long idx]
          (let [position (double (pos-fn x y idx point-count'))
                first-pos (aget positions 0)
                last-pos (aget positions (dec n))]

            (cond
              ;; Before first keyframe - clamp to first
              (<= position first-pos)
              (aget param-values 0)

              ;; After last keyframe - clamp to last
              (>= position last-pos)
              (aget param-values (dec n))

              :else
              ;; Find surrounding keyframes
              (let [after-idx (loop [i (int 0)]
                                (if (>= i n)
                                  nil
                                  (if (> (aget positions i) position)
                                    i
                                    (recur (inc i)))))
                    before-idx (long (if after-idx (dec (long after-idx)) (dec n)))

                    ;; Calculate interpolation factor
                    p1 (aget positions before-idx)
                    p2 (aget positions (long (or after-idx 0)))
                    range-val (- p2 p1)
                    t (if (zero? range-val) 0.0 (/ (- position p1) range-val))

                    ;; Apply interpolation curve
                    curved-t (interp/apply-interpolation t (nth interp-modes before-idx))

                    ;; Get values
                    v1 (aget param-values before-idx)
                    v2 (aget param-values (long (or after-idx 0)))]

                (interp/interpolate-value v1 v2 curved-t))))))

      ;; No keyframes - return constant 0.0
      (constantly 0.0))))


;; Modulator Compilers Registry


(def modulator-compilers
  "Map of modulator type keywords to their compiler functions.
   Each compiler takes [config point-count] and returns (fn [x y idx] -> value).
   
   Compilers are optional - not all modulator types have them.
   Only per-point modulators benefit from compilation."
  {:pos-x                   compile-pos-x
   :pos-y                   compile-pos-y
   :radial                  compile-radial
   :point-index             compile-point-index
   :angle                   compile-angle
   :unified-keyframe-param  compile-unified-keyframe-param})
