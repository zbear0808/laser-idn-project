(ns laser-show.animation.modulation
  "Parameter modulation system for effects - public API.
   
   This namespace provides the public interface for parameter modulation:
   - Context creation for parameter resolution
   - Modulator type detection
   - Per-point detection for position-based modulators
   - Parameter resolution (evaluating modulators)
   
   Implementation details (evaluator functions) are in modulator-evaluators.clj
   Keyframe interpolation logic is in keyframes.clj
   Time/beat utilities are in time.clj
   
   Usage:
   ;; Static value
   {:effect-id :scale :params {:x-scale 1.5}}
   
   ;; Modulated value (pure data config - serializable!)
   {:effect-id :scale :params {:x-scale {:type :sine :min 0.8 :max 1.2 :period 0.5}}}
   
   ;; MIDI controlled
   {:effect-id :scale :params {:x-scale {:type :midi :channel 1 :cc 7 :min 0.5 :max 2.0}}}"
  (:require
   [laser-show.animation.modulator-evaluators :as evaluators]
   [laser-show.animation.keyframes :as keyframes]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)


;; Re-exports from evaluators for backward compatibility


(def modulator-types
  "Set of valid modulator type keywords."
  evaluators/modulator-types)


;; Modulator Detection


(defn modulator-config?
  "Check if a value is a modulator config map (pure data representation).
   Modulator configs are maps with a :type key that matches a known modulator type."
  [x]
  (and (map? x)
       (contains? x :type)
       (contains? modulator-types (:type x))))


;; Modulation Context


(defn make-context
  "Create a modulation context for parameter resolution.
   
   Parameters:
   - time-ms: Current time in milliseconds
   - bpm: Current BPM
   - trigger-time: Time when the cue/effect was triggered (optional, for once-mode modulators)
   - midi-state: Map of {[channel cc] -> value} (optional)
   - osc-state: Map of {path -> value} (optional)
   
   Per-Point Parameters (for position-based modulators):
   - x: Normalized x coordinate (-1.0 to 1.0)
   - y: Normalized y coordinate (-1.0 to 1.0)
   - point-index: Index of current point in frame
   - point-count: Total number of points in frame
   
   Beat Accumulation Parameters (for smooth BPM-change animation):
   - accumulated-beats: Running total of beats since cue trigger (incremental)
   - accumulated-ms: Running total of ms since cue trigger (incremental)
   - phase-offset: Current smoothed phase correction offset
   - effective-beats: accumulated-beats + phase-offset (use for looping modulators)"
  [{:keys [time-ms bpm trigger-time midi-state osc-state
           x y point-index point-count
           accumulated-beats accumulated-ms phase-offset]
    :or {midi-state {} osc-state {}
         accumulated-beats 0.0 accumulated-ms 0.0 phase-offset 0.0}}]
  {:time-ms time-ms
   :bpm bpm
   :trigger-time trigger-time
   :midi-state midi-state
   :osc-state osc-state
   ;; Per-point fields for position-based modulators
   :x x
   :y y
   :point-index point-index
   :point-count point-count
   ;; Beat accumulation fields
   :accumulated-beats (or accumulated-beats 0.0)
   :accumulated-ms (or accumulated-ms 0.0)
   :phase-offset (or phase-offset 0.0)
   :effective-beats (+ (double (or accumulated-beats 0.0)) (double (or phase-offset 0.0)))})

(defn make-base-context
 "Create a base modulation context optimized for per-point iteration.
  This creates the context once without per-point fields, which can then
  be efficiently updated with with-point-context for each point.
  
  This avoids creating a full new map per point, instead using assoc
  to update only the per-point fields.
  
  Parameters:
  - time-ms: Current time in milliseconds
  - bpm: Current BPM
  - point-count: Total number of points in frame
  - timing-ctx: Optional map with accumulated-beats, accumulated-ms, phase-offset"
 [{:keys [time-ms bpm point-count trigger-time midi-state osc-state
          accumulated-beats accumulated-ms phase-offset]
   :or {midi-state {} osc-state {}}}]
 (let [acc-beats (double (or accumulated-beats 0.0))
       acc-ms (double (or accumulated-ms 0.0))
       phase-off (double (or phase-offset 0.0))]
   {:time-ms time-ms
    :bpm bpm
    :trigger-time trigger-time
    :midi-state midi-state
    :osc-state osc-state
    :point-count point-count
    :accumulated-beats acc-beats
    :accumulated-ms acc-ms
    :phase-offset phase-off
    :effective-beats (+ acc-beats phase-off)
    ;; Pre-set per-point fields to nil - will be updated via assoc
    :x nil
    :y nil
    :point-index nil}))

(defn with-point-context
 "Efficiently update a base context with per-point data.
  Uses assoc instead of creating a new map, much faster than make-context.
  
  Parameters:
  - base-ctx: Context created by make-base-context
  - x: Point x coordinate
  - y: Point y coordinate
  - point-index: Index of current point"
 [base-ctx x y point-index]
 (-> base-ctx
     (assoc :x x)
     (assoc :y y)
     (assoc :point-index point-index)))


;; Per-Point Context Detection


(def ^:private per-point-types
  "Modulator types that require per-point context (x, y, point-index, point-count)."
  #{:pos-x :pos-y :radial :angle :point-index :point-wave :pos-wave :pos-scroll :rainbow-hue})

(defn config-requires-per-point?
  "Check if a modulator config requires per-point context.
   Returns true if the config's type is in the per-point-types set AND :active? is true.
   Inactive modulators don't need per-point processing - they return a static value."
  [config]
  (and (modulator-config? config)
       (contains? per-point-types (:type config))
       (get config :active? true)))

(defn any-param-requires-per-point?
  "Check if any parameter in a params map requires per-point context.
   Recursively checks all values, including nested maps and collections."
  [params]
  (cond
    (modulator-config? params)
    (config-requires-per-point? params)

    (map? params)
    (some any-param-requires-per-point? (vals params))

    (coll? params)
    (some any-param-requires-per-point? params)

    :else
    false))


;; Main Evaluation - delegates to evaluators


(defn evaluate-modulator
  "Evaluate a modulator config with the given context.
   Uses the modulator-evaluators registry to look up the evaluator fn.
   Returns the calculated value."
  [config context]
  (evaluators/evaluate-modulator config context))


;; Parameter Resolution


(defn resolve-param
  "Resolve a parameter value.
   - If the param is a modulator config map (pure data), evaluate it if :active? is true
   - If :active? is false, extract and return the static :value field or midpoint
   - If it's a static value (number, string, etc.), return it as-is."
  [param context]
  (cond
    (not (modulator-config? param))
    param

    (get param :active? true)  ; Default to true for backward compatibility
    (evaluate-modulator param context)

    :else
    ;; Inactive modulator - return :value field or fall back to midpoint
    (let [{:keys [min max value]} param]
      (or value
          (when (and min max)
            (/ (+ (double min) (double max)) 2.0))
          0.0))))

(defn resolve-params
  "Resolve all parameters in a params map."
  [params context]
  (update-vals params #(resolve-param % context)))


;; Keyframe Modulator API - delegates to keyframes


(defn keyframe-modulator?
  "Check if a value is a keyframe modulator config.
   Keyframe modulators are maps with a :keyframes vector."
  [x]
  (keyframes/keyframe-modulator? x))

(defn eval-keyframe
  "Evaluate keyframe modulator by interpolating between keyframes.
   
   Parameters:
   - config: Keyframe modulator config map with:
     - :keyframes - Vector of {:position (0.0-1.0) :params {...}} maps
     - :period - Beats per cycle (default 1.0)
     - :time-unit - :beats or :seconds (default :beats)
     - :loop-mode - :loop or :once (default :loop)
   - context: Modulation context with timing info
   
   Returns: Interpolated params map, or nil if no keyframes"
  [config context]
  (keyframes/eval-keyframe config context))
