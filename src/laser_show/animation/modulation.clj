(ns laser-show.animation.modulation
  "Parameter modulation system for effects - public API.
   
   This namespace provides the public interface for parameter modulation:
   - Context creation for parameter resolution
   - Modulator type detection
   - Per-point detection for position-based modulators
   - Parameter resolution (evaluating modulators)
   
   Implementation details (evaluator functions) are in modulator-evaluators.clj
   Modulator registrations are in modulators.clj
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
   [laser-show.animation.keyframes :as kf]
   [laser-show.animation.modulator-registry :as reg]
   [laser-show.animation.modulators] ;; Load modulator registrations
   [laser-show.common.util :as u]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)


(defn modulator-config?
  "Check if a value is a modulator config map (pure data representation).
   Modulator configs are maps with a :type key that matches a known modulator type."
  [x]
  (and (map? x)
       (contains? x :type)
       (reg/valid-modulator-type? (:type x))))

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
  (u/->map& 
   time-ms bpm trigger-time midi-state osc-state x y point-index 
   point-count accumulated-beats accumulated-ms phase-offset
   :effective-beats (+ (double (or accumulated-beats 0.0)) (double (or phase-offset 0.0)))))

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
   (u/->map&
    time-ms bpm trigger-time midi-state osc-state point-count
    :accumulated-beats acc-beats
    :accumulated-ms acc-ms
    :phase-offset phase-off
    :effective-beats (+ acc-beats phase-off)
    ;; Pre-set per-point fields to nil - will be updated via assoc
    :x nil
    :y nil
    :point-index nil)))

(defn with-point-context
  "Efficiently update a base context with per-point data.
  Uses assoc instead of creating a new map, much faster than make-context.
  
  Parameters:
  - base-ctx: Context created by make-base-context
  - x: Point x coordinate
  - y: Point y coordinate
  - point-index: Index of current point"
  [base-ctx x y point-index]
  (merge base-ctx (u/->map x y point-index)))


;; Per-Point Context Detection


(defn config-requires-per-point?
  "Check if a modulator config requires per-point context.
   Returns true if the modulator type is registered with :per-point? true AND :active? is true.
   Inactive modulators don't need per-point processing - they return a static value."
  [config]
  (and (modulator-config? config)
       (reg/per-point? (:type config))
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

(defn keyframe-modulator-requires-per-point?
  "Returns true if an effect's keyframe-modulator has a spatial driver
   that requires per-point evaluation.
   
   When an effect has a keyframe-modulator with a spatial driver
   (:point-index, :pos-x, :pos-y, :radial), each point needs to be evaluated
   individually since each point has different spatial coordinates.
   
   Parameters:
   - effect: Effect map with optional :keyframe-modulator key
   
   Returns: Boolean"
  [effect]
  (when-let [km (:keyframe-modulator effect)]
    (when (:enabled? km)
      (let [driver (get km :driver :time)]
        (kf/spatial-driver? driver)))))


(defn- get-defaults-for-type
  "Get default values for a modulator type from the registry.
   Returns a map of param-key -> default-value."
  [mod-type]
  (-> (u/map-into :key :default (reg/get-params mod-type))
      (u/filter-vals some?)))

(defn evaluate-modulator
  "Evaluate a modulator config with the given context.
   Uses the modulator registry to look up the evaluator fn (hot path).
   Merges in default values from registry for any missing config keys.
   Returns the calculated value, or default from :value/:min if evaluator not found."
  [config context]
  (if-let [eval-fn (reg/get-evaluator (:type config))]
    (let [defaults (get-defaults-for-type (:type config))
          full-config (merge defaults config)]
      (eval-fn full-config context))
    ;; Default fallback for unknown types
    (get config :value (get config :min 0.0))))


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

    (get param :active? true) 
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


;; Per-Point Modulator Compilation


(defn compilable-per-point?
  "Check if a modulator config can be compiled to optimized form.
   Returns true if:
   - Config is a per-point modulator (per-point? = true)
   - Config is active (:active? defaults to true)
   - A compiler is registered for this modulator type
   
   Parameters:
   - config: Modulator config map
   
   Returns: Boolean"
  [config]
  (and (modulator-config? config)
       (get config :active? true)
       (reg/per-point? (:type config))
       (reg/has-compiler? (:type config))))

(defn compile-per-point-modulator
  "Compile a per-point modulator config into an optimized function.
   
   This is the main entry point for modulator compilation. It:
   1. Looks up the compiler from the registry
   2. Merges defaults with config
   3. Calls the compiler to produce an optimized function
   
   If no compiler is registered for the modulator type, returns nil
   (caller should fall back to interpreter path).
   
   Parameters:
   - config: Modulator config map (must have :type key)
   - point-count: Total number of points in frame
   
   Returns: (fn [^double x ^double y ^long idx] -> double) or nil"
  [config point-count]
  (when-let [compiler (reg/get-compiler (:type config))]
    (let [defaults (get-defaults-for-type (:type config))
          full-config (merge defaults config)]
      (compiler full-config point-count))))
