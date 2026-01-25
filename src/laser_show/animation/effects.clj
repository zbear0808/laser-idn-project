(ns laser-show.animation.effects
  "Core effect system for laser animations using transducers.
   
   Effects are registered with an `apply-transducer` function that returns
   a transducer to transform points. When applying an effect chain,
   all transducers are composed together and applied in a single pass,
   avoiding intermediate sequence allocations.
   
   Supports both static parameters and modulated parameters:
   ;; Static
   {:effect-id :scale :params {:x-scale 1.5}}
   
   ;; Modulated (using modulation.clj)
   {:effect-id :scale :params {:x-scale (mod/sine-mod 0.8 1.2 2.0)}}
   
   Transducer signature:
   (fn [time-ms bpm resolved-params frame-context]
     ;; Returns a transducer that transforms 5-element point vectors
     (map (fn [pt] ...)))
   
   Point format: 5-element vector [x y r g b] where:
   - x, y: coordinates in [-1.0, 1.0]
   - r, g, b: colors in [0.0, 1.0]
   
   Access via index constants: (pt t/X), (pt t/Y), (pt t/R), (pt t/G), (pt t/B)
   Update via helper functions: t/update-point-xy, t/update-point-rgb, t/update-point-all
   
   Point index is obtained via map-indexed in per-point effect paths,
   and point-count is available in ctx parameter."
  (:require
   [clojure.tools.logging :as log]
   [laser-show.animation.chains :as chains]
   [laser-show.animation.modulation :as mod]
   [laser-show.common.util :as u]
   [laser-show.state.queries :as queries]))

(set! *warn-on-reflection* true)
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
    :apply-transducer (fn [time-ms bpm params frame-ctx] transducer)}"
  [effect-def]
  {:pre [(keyword? (:id effect-def))
         (string? (:name effect-def))
         (valid-category? (:category effect-def))
         (contains? timing-modes (:timing effect-def))
         (vector? (:parameters effect-def))
         (fn? (:apply-transducer effect-def))]}
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


;; Points stay as 5-element vectors throughout the pipeline!
;; No normalize/denormalize needed - effects work directly on [x y r g b] vectors.
;; Use t/X, t/Y, t/R, t/G, t/B constants for indexed access.
;; Use t/update-point-xy, t/update-point-rgb, t/update-point-all for updates.



;; Effect Transducer Composition


(defn make-effect-transducer
  "Create a transducer for a single effect instance.
   
   Parameters:
   - effect-instance: {:effect-id :scale :enabled true :params {...} :keyframe-modulator {...}}
   - time-ms: Current time in milliseconds
   - bpm: Current BPM
   - trigger-time: When the effect was triggered (for once-mode modulators)
   - frame-ctx: {:point-count n :timing-ctx {...}}
   
   Supports keyframe modulation: if :keyframe-modulator is present and enabled,
   the keyframe modulator's interpolated params are used instead of per-param modulators.
   
   Returns: A transducer, or nil if effect is disabled or unknown."
  [effect-instance time-ms bpm trigger-time frame-ctx]
  (when (effect-instance-enabled? effect-instance)
    (let [effect-id (:effect-id effect-instance)]
      (when-let [effect-def (get-effect effect-id)]
        (let [xf-fn (:apply-transducer effect-def)
              
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
          ;; Note: We pass merged-params (may contain modulators) to the transducer factory
          ;; The transducer is responsible for resolving them (either globally or per-point)
          ;; frame-ctx now includes :timing-ctx for modulator beat accumulation
          ;; When in keyframe mode, params are already resolved (no modulators)
          (try
            (xf-fn time-ms bpm merged-params frame-ctx)
            (catch Exception e
              (log/error "make-effect-transducer:" effect-id "failed:" (.getMessage e))
              (log/debug "  params:" merged-params)
              ;; Return identity transducer on error
              (map identity))))))))

(defn compose-effect-transducers
  "Compose transducers from a chain of effects.
   
   Supports nested groups - the chain is flattened before processing,
   respecting enabled? flags at both effect and group level.
   
   Parameters:
   - chain: Effect chain {:effects [...]} - may contain groups
   - time-ms: Current time in milliseconds
   - bpm: Current BPM
   - trigger-time: When effects were triggered
   - frame-ctx: {:point-count n}
   
   Returns: A single composed transducer, or (map identity) for empty chains."
  [chain time-ms bpm trigger-time frame-ctx]
  (let [effects (:effects chain)
        ;; Flatten the chain to handle nested groups
        flat-effects (chains/flatten-chain effects)
        transducers (keep #(make-effect-transducer % time-ms bpm trigger-time frame-ctx) flat-effects)]
    (if (empty? transducers)
      (map identity)
      (apply comp transducers))))


;; Effect Chain Application


(defn apply-effect-chain
  "Apply an effect chain to a frame using transducer composition.
   
   All effect transducers are composed together and applied in a single pass,
   avoiding intermediate sequence allocations.
   
   Parameters:
   - frame: LaserFrame (vector of points) to transform
   - chain: Effect chain {:effects [...]} or nil
   - time-ms: Current time in milliseconds
   - bpm: Current BPM (from global state if not provided)
   - trigger-time: (optional) Time when the cue was triggered
   - timing-ctx: (optional) Timing context for modulator evaluation
                 {:accumulated-beats :accumulated-ms :phase-offset :effective-beats}
   
   Returns: Transformed frame (vector of points)"
  ([frame chain time-ms]
   (apply-effect-chain frame chain time-ms (queries/bpm) nil nil))
  ([frame chain time-ms bpm]
   (apply-effect-chain frame chain time-ms bpm nil nil))
  ([frame chain time-ms bpm trigger-time]
   (apply-effect-chain frame chain time-ms bpm trigger-time nil))
  ([frame chain time-ms bpm trigger-time timing-ctx]
   (if (or (nil? chain) (empty? (:effects chain)))
     frame
     (let [point-count (count frame)
           ;; Include timing context in frame-ctx for modulator evaluation
           frame-ctx {:point-count point-count
                      :timing-ctx timing-ctx}
           ;; Points stay as 5-element vectors throughout - no conversion needed!
           effect-xf (compose-effect-transducers chain time-ms bpm trigger-time frame-ctx)]
       (into [] effect-xf frame)))))



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
         context (mod/make-context (merge {:time-ms time-ms :bpm bpm} timing-ctx))]
     (mod/resolve-params raw-params context))))




;; Simplified Per-Point Resolution via Resolver Functions


(defn make-param-resolver
  "Create a resolver function for a single parameter.
   
   This is the preferred way to handle per-point modulation. It returns a function
   (fn [x y idx] -> value) that efficiently resolves the parameter value.
   
   Optimization:
   - For static values: returns (constantly value) - no per-point overhead
   - For global modulators: resolves once, returns (constantly resolved-value)
   - For per-point modulators: returns fn that evaluates with point context
   
   Parameters:
   - param-key: Keyword key of the parameter to resolve
   - params: Full params map (may contain modulators)
   - time-ms: Current time in milliseconds
   - bpm: Current BPM
   - ctx: Frame context {:point-count n :timing-ctx {...}}
   
   Returns: (fn [x y idx] -> resolved-value)
   
   Example usage:
   ```clojure
   (let [get-amount (make-param-resolver :amount params time-ms bpm ctx)]
     (map-indexed
       (fn [idx pt]
         (let [amount (get-amount (pt t/X) (pt t/Y) idx)]
           ...))))
   ```"
  [param-key params time-ms bpm ctx]
  (let [param-val (get params param-key)
        timing-ctx (:timing-ctx ctx)
        point-count (:point-count ctx)]
    (cond
      ;; Static value (number, keyword, boolean, etc.) - return constant fn
      (not (mod/modulator-config? param-val))
      (constantly param-val)
      
      ;; Per-point modulator - return fn that evaluates per point
      (mod/config-requires-per-point? param-val)
      (let [base-ctx (mod/make-base-context
                       (merge {:time-ms time-ms
                               :bpm bpm
                               :point-count point-count}
                              timing-ctx))]
        (fn [x y idx]
          (let [point-ctx (mod/with-point-context base-ctx x y idx)]
            (mod/evaluate-modulator param-val point-ctx))))
      
      ;; Global modulator - resolve once, return constant fn
      :else
      (let [context (mod/make-context (merge {:time-ms time-ms :bpm bpm} timing-ctx))
            resolved (mod/resolve-param param-val context)]
        (constantly resolved)))))
