(ns laser-show.animation.effects
  "Core effect system for laser animations using in-place array mutation.
   
   Effects mutate 2D float array frames in place for maximum performance.
   This avoids intermediate allocations and enables SIMD-friendly access patterns.
   
   Effect functions follow the pattern:
   (defn effect-name!
     \"Description. Mutates frame in place.\"
     [^\"[[D\" frame params...]
     (dotimes [i (alength frame)]
       ;; Access: (aget frame i t/X)
       ;; Mutate: (aset-double frame i t/X new-value)
       )
     frame)
   
   Effects return the frame for chaining convenience, but mutation is in place.
   
   Supports both static parameters and modulated parameters:
   ;; Static
   {:effect-id :scale :params {:x-scale 1.5}}
   
   ;; Modulated (using modulation.clj)
   {:effect-id :scale :params {:x-scale (mod/sine-mod 0.8 1.2 2.0)}}"
  (:require
   [clojure.tools.logging :as log]
   [laser-show.animation.chains :as chains]
   [laser-show.animation.modulation :as mod]
   [laser-show.animation.types :as t]
   [laser-show.common.util :as u]
   [laser-show.state.queries :as queries]))

(set! *unchecked-math* :warn-on-boxed)

;; Timing Modes


(def timing-modes
  "Supported timing modes for effects.
   - :static - No time dependency, constant parameters
   - :bpm - Synchronized to global BPM
   - :seconds - Time-based but not beat-synced"
  #{:static :bpm :seconds})


;; Effect Categories


(def effect-categories
  "Effect categories for organization and UI grouping.
   - :shape - Geometric transformations (scale, rotate, offset, etc.)
   - :color - Color modifications (hue, saturation, filters)
   - :intensity - Brightness and visibility effects
   - :calibration - Hardware-specific corrections (projector-only)
   - :zone - Routing effects that modify where cues are sent (not frame transforms)"
  #{:shape :color :intensity :calibration :zone})


;; Effect Registry


(defonce !effect-registry (atom {}))

(defn- valid-category?
  "Check if category is valid. Can be a single keyword or a set of keywords."
  [category]
  (or (contains? effect-categories category)
      (and (set? category)
           (every? #(contains? effect-categories %) category))))

(defn register-effect!
  "Register an effect definition in the global registry.
   
   Effect definition structure:
   {:id              :effect-id
    :name            \"Display Name\"
    :category        :shape/:color/:intensity/:calibration OR #{:shape :calibration} (set for multiple)
    :timing          :static/:bpm/:seconds
    :parameters      [{:key :param-name
                       :label \"Param Label\"
                       :type :float/:int/:bool/:color/:choice
                       :default default-value
                       :min min-value (optional)
                       :max max-value (optional)
                       :choices [...] (for :choice type)}]
    :apply-fn!       (fn [frame time-ms bpm params frame-ctx] frame)}"
  [effect-def]
  {:pre [(keyword? (:id effect-def))
         (string? (:name effect-def))
         (valid-category? (:category effect-def))
         (contains? timing-modes (:timing effect-def))
         (vector? (:parameters effect-def))
         (fn? (:apply-fn! effect-def))]}
  (swap! !effect-registry assoc (:id effect-def) effect-def)
  effect-def)

(defn get-effect
  "Get an effect definition by ID."
  [effect-id]
  (get @!effect-registry effect-id))

(defn list-effects
  "List all registered effects."
  []
  (vals @!effect-registry))

(defn- effect-in-category?
  "Check if an effect belongs to a category.
   Handles both single category keywords and category sets."
  [effect category]
  (let [effect-category (:category effect)]
    (if (set? effect-category)
      (contains? effect-category category)
      (= effect-category category))))

(defn list-effects-by-category
  "List effects filtered by category.
   Works with effects that have a single category keyword or a set of categories."
  [category]
  (filterv #(effect-in-category? % category) (list-effects)))




;; Parameter Helpers


(defn get-default-params
  "Get default parameter values for an effect."
  [effect-id]
  (when-let [effect-def (get-effect effect-id)]
    (u/map-into :key :default (:parameters effect-def))))



(defn merge-with-defaults
  "Merge provided params with defaults for an effect."
  [effect-id params]
  (merge (get-default-params effect-id) params))



;; Effect Instance Checking


(defn effect?
  "Check if an item is an effect (not a group).
   Items without :type or with :type :effect are effects."
  [item]
  (or (nil? (:type item))
      (= :effect (:type item))))

(def effect-instance-enabled?
  "Check if an effect instance is enabled."
  chains/item-enabled?)


;; Helper for Per-Point Modulation


(defn resolve-params-for-point
  "Resolve parameters that may contain per-point modulators.
   
   Parameters:
   - raw-params: Parameter map that may contain modulators
   - time-ms: Current time
   - bpm: Current BPM
   - x, y: Normalized point position
   - idx: Point index
   - count: Total point count
   - timing-ctx: Optional map with accumulated-beats, accumulated-ms, phase-offset
   
   Returns: Resolved parameter map with all modulators evaluated."
  ([raw-params time-ms bpm x y idx count]
   (resolve-params-for-point raw-params time-ms bpm x y idx count nil))
  ([raw-params time-ms bpm x y idx count timing-ctx]
   (let [context (mod/make-context (merge {:time-ms time-ms
                                           :bpm bpm
                                           :x x
                                           :y y
                                           :point-index idx
                                           :point-count count}
                                          timing-ctx))]
     (mod/resolve-params raw-params context))))

(defn make-base-context-for-frame
  "Create a base modulation context for a frame.
   Use with resolve-params-for-point-fast in hot loops.
   
   Parameters:
   - time-ms: Current time
   - bpm: Current BPM
   - point-count: Number of points in frame
   - timing-ctx: Optional map with accumulated-beats, accumulated-ms, phase-offset
   
   Returns: Base context that can be updated per-point without allocations."
  [time-ms bpm point-count timing-ctx]
  (mod/make-base-context (merge {:time-ms time-ms
                                 :bpm bpm
                                 :point-count point-count}
                                timing-ctx)))

(defn resolve-params-for-point-fast
  "Resolve parameters with a pre-created base context.
   More efficient for hot loops - uses assoc instead of creating new maps.
   
   Parameters:
   - raw-params: Parameter map that may contain modulators
   - base-ctx: Base context created with make-base-context-for-frame
   - x, y: Normalized point position
   - idx: Point index
   
   Returns: Resolved parameter map."
  [raw-params base-ctx x y idx]
  (let [context (mod/with-point-context base-ctx x y idx)]
    (mod/resolve-params raw-params context)))

(defn resolve-params-global
  "Resolve parameters globally (not per-point).
   Use this when no per-point modulators are present.
   
   Parameters:
   - raw-params: Parameter map that may contain modulators
   - time-ms: Current time
   - bpm: Current BPM
   - frame-ctx-or-timing-ctx: Either frame-ctx {:point-count n :timing-ctx {...}}
                              or direct timing-ctx {:accumulated-beats n :accumulated-ms n :phase-offset n}
   
   Returns: Resolved parameter map."
  ([raw-params time-ms bpm]
   (resolve-params-global raw-params time-ms bpm nil))
  ([raw-params time-ms bpm frame-ctx-or-timing-ctx]
   ;; Handle both frame-ctx with :timing-ctx key or direct timing context
   (let [timing-ctx (if (contains? frame-ctx-or-timing-ctx :timing-ctx)
                      (:timing-ctx frame-ctx-or-timing-ctx)
                      frame-ctx-or-timing-ctx)
         context (mod/make-base-context (merge {:time-ms time-ms :bpm bpm} timing-ctx))]
     (mod/resolve-params raw-params context))))


;; Effect Application


(defn apply-effect!
  "Apply a single effect to frame IN PLACE.
   
   Parameters:
   - frame: 2D float array to mutate
   - effect-instance: {:effect-id :scale :enabled true :params {...} :keyframe-modulator {...}}
   - time-ms: Current time in milliseconds
   - bpm: Current BPM
   - trigger-time: When the effect was triggered (for once-mode modulators)
   - frame-ctx: {:point-count n :timing-ctx {...}}
   
   Returns: The frame (mutated in place)"
  [^"[[D" frame effect-instance time-ms bpm trigger-time frame-ctx]
  (when (effect-instance-enabled? effect-instance)
    (let [effect-id (:effect-id effect-instance)]
      (when-let [effect-def (get-effect effect-id)]
        (let [apply-fn (:apply-fn! effect-def)
              
              ;; Check for keyframe modulator FIRST
              keyframe-mod (:keyframe-modulator effect-instance)
              keyframe-enabled? (and keyframe-mod (:enabled? keyframe-mod))
              
              ;; Resolve params based on modulation mode
              user-params (if keyframe-enabled?
                            ;; Keyframe mode: evaluate keyframe modulator to get params
                            (let [timing-ctx (:timing-ctx frame-ctx)
                                  context (mod/make-context (merge {:time-ms time-ms
                                                                    :bpm bpm
                                                                    :trigger-time trigger-time}
                                                                   timing-ctx))]
                              (mod/eval-keyframe keyframe-mod context))
                            ;; Normal mode: use per-param modulators
                            (:params effect-instance))
              
              merged-params (merge-with-defaults effect-id user-params)]
          (try
            (apply-fn frame time-ms bpm merged-params frame-ctx)
            (catch Exception e
              (log/error "apply-effect!:" effect-id "failed:" (.getMessage e))
              (log/debug "  params:" merged-params)))))))
  frame)

(defn apply-effect-chain!
  "Apply an effect chain to a frame IN PLACE.
   
   Supports nested groups - the chain is flattened before processing,
   respecting enabled? flags at both effect and group level.
   
   Parameters:
   - frame: 2D float array to mutate
   - chain: Effect chain {:effects [...]} - may contain groups
   - time-ms: Current time in milliseconds
   - bpm: Current BPM (from global state if not provided)
   - trigger-time: (optional) Time when the cue was triggered
   - timing-ctx: (optional) Timing context for modulator evaluation
   
   Returns: The frame (mutated in place)"
  ([^"[[D" frame chain time-ms]
   (apply-effect-chain! frame chain time-ms (queries/bpm) nil nil))
  ([^"[[D" frame chain time-ms bpm]
   (apply-effect-chain! frame chain time-ms bpm nil nil))
  ([^"[[D" frame chain time-ms bpm trigger-time]
   (apply-effect-chain! frame chain time-ms bpm trigger-time nil))
  ([^"[[D" frame chain time-ms bpm trigger-time timing-ctx]
   (when (and chain (seq (:effects chain)) (pos? (alength frame)))
     (let [point-count (alength frame)
           frame-ctx {:point-count point-count
                      :timing-ctx timing-ctx}
           effects (:effects chain)
           ;; Flatten the chain to handle nested groups
           flat-effects (chains/flatten-chain effects)]
       (doseq [effect flat-effects]
         (apply-effect! frame effect time-ms bpm trigger-time frame-ctx))))
   frame))


;; Convenience wrapper for apply-effect-chain! that clones first


(defn apply-effect-chain
  "Apply an effect chain to a frame, returning a modified frame.
   Clones the frame first to preserve original if needed.
   
   For performance-critical paths, use apply-effect-chain! with a frame
   that can be mutated in place."
  ([^"[[D" frame chain time-ms]
   (apply-effect-chain frame chain time-ms (queries/bpm) nil nil))
  ([^"[[D" frame chain time-ms bpm]
   (apply-effect-chain frame chain time-ms bpm nil nil))
  ([^"[[D" frame chain time-ms bpm trigger-time]
   (apply-effect-chain frame chain time-ms bpm trigger-time nil))
  ([^"[[D" frame chain time-ms bpm trigger-time timing-ctx]
   (if (or (nil? chain) (empty? (:effects chain)))
     frame
     (let [cloned (t/clone-frame frame)]
       (apply-effect-chain! cloned chain time-ms bpm trigger-time timing-ctx)
       cloned))))
