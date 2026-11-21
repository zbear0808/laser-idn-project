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
   [laser-show.animation.chains :as chains]
   [laser-show.animation.modulation :as mod]
   [laser-show.common.util :as u]
   [laser-show.animation.keyframes :as kf]
   [laser-show.backend.output-errors :as oe]))

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
                       :choices [...] (for :choice type)
                       :modulatable? true (optional, a :choice: a modulator's
                                           value picks a choice by index, choice-at)}]
    :apply-transducer (fn [time-ms bpm params frame-ctx] transducer)}
   
   Optional keys, kept and read elsewhere:
   - :hidden? true: registered (an old show's effect still reads) but offered
     nowhere (zones plan Z4's Zone Selector)
   - :scope: where the effect banks offer it, a set of :item and :global
     (absent means both); a zone-stage effect is :global (vibe Phase 8)
   - :apply-zones (fn [frames params ctx] frames'): a zone-stage effect that
     moves a cue's frames between places (routing.zone-remap, zones plan Z5);
     its :apply-transducer is identity
   - :select-map (fn [params pulses beats maps] map-id): a zone-stage effect
     the show clock asks for the zone map (the Map cycle, zones plan Z5)"
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


(defn- extract-keyframe-param-keys
  "Extract all param keys from keyframe configs for spatial drivers.
   Returns a set of param keys that appear in any keyframe."
  [keyframe-mod]
  (let [keyframes (get keyframe-mod :keyframes [])]
    (into #{} (mapcat (comp keys :params) keyframes))))

(defn- create-spatial-keyframe-params
  "Create synthetic per-point modulator configs for each param in a spatial keyframe.
   These configs will be processed by make-param-resolver for per-point evaluation.
   
   Returns: Map of {param-key -> unified-keyframe-param-config}"
  [keyframe-mod]
  (let [param-keys (extract-keyframe-param-keys keyframe-mod)]
    (into {}
          (map (fn [k]
                 [k {:type :unified-keyframe-param
                     :keyframe-mod keyframe-mod
                     :param-key k
                     :active? true}])
               param-keys))))

(def ^:private failed-to-build
  "Stands in for an effect whose transducer failed to build: it passes points through."
  (map identity))

(defn- effect-error-key [effect-instance]
  [:effect (or (:id effect-instance) (:effect-id effect-instance))])

(defn- timing-of
  "The timing context in ctx: a frame context's :timing-ctx, or ctx itself
   when it is a bare timing context."
  [ctx]
  (if (contains? ctx :timing-ctx) (:timing-ctx ctx) ctx))

(defn- mod-context
  "The modulation context for ctx: the one apply-effect-chain made for the
   whole chain (:mod-ctx), or else a new one from ctx's timing context, whose
   own :time-ms and :bpm win over time-ms and bpm."
  [ctx time-ms bpm]
  (if-let [shared (:mod-ctx ctx)]
    @shared
    (mod/make-context (merge {:time-ms time-ms :bpm bpm} (timing-of ctx)))))

(defn- base-context
  "As mod-context, the per-point base context (:base-ctx) for point-count points."
  [ctx time-ms bpm point-count]
  (if-let [shared (:base-ctx ctx)]
    @shared
    (mod/make-base-context (merge {:time-ms time-ms :bpm bpm :point-count point-count} (timing-of ctx)))))

(defn- frame-context
  "What every effect of one chain call shares: the frame's point count, the
   timing context, and the modulation and per-point base contexts, each made
   on first use, so a chain makes at most one of each and a chain with only
   static parameters none."
  [point-count timing-ctx]
  {:point-count point-count
   :timing-ctx timing-ctx
   :mod-ctx (delay (mod/make-context timing-ctx))
   :base-ctx (delay (mod/make-base-context (merge {:point-count point-count} timing-ctx)))})

(defn make-effect-transducer
  "Create a transducer for a single effect instance.
   
   Parameters:
   - effect-instance: {:effect-id :scale :enabled? true :params {...} :keyframe-modulator {...}}
   - frame-ctx: {:point-count n :timing-ctx {...}}, and the chain's shared
     :mod-ctx and :base-ctx when apply-effect-chain made it. The effect gets
     the timing context's :time-ms and :bpm as its time and tempo.
   
   Supports keyframe modulation: if :keyframe-modulator is present and enabled,
   the keyframe modulator's interpolated params are used instead of per-param modulators.
   
   For spatial keyframe drivers (point-index, pos-x, pos-y, radial), creates synthetic
   per-point modulator configs that get evaluated per-point by make-param-resolver.
   
   Returns: A transducer, or nil if effect is disabled or unknown."
  [effect-instance frame-ctx]
  (when (effect-instance-enabled? effect-instance)
    (let [effect-id (:effect-id effect-instance)]
      (when-let [effect-def (get-effect effect-id)]
        (let [xf-fn (:apply-transducer effect-def)
              {:keys [time-ms bpm]} (:timing-ctx frame-ctx)
              
              ;; Check for keyframe modulator FIRST
              keyframe-mod (:keyframe-modulator effect-instance)
              keyframe-enabled? (and keyframe-mod (:enabled? keyframe-mod))
              
              ;; Check if spatial driver (requires per-point evaluation)
              spatial-driver? (and keyframe-enabled?
                                   (mod/keyframe-modulator-requires-per-point? effect-instance))
              
              ;; Resolve params based on modulation mode
              user-params (cond
                            ;; Spatial keyframe mode: create synthetic per-point modulator configs
                            ;; These will be resolved per-point by make-param-resolver
                            spatial-driver?
                            (create-spatial-keyframe-params keyframe-mod)
                            
                            ;; Time-based keyframe mode: evaluate once per frame
                            keyframe-enabled?
                            (kf/eval-keyframe keyframe-mod (mod-context frame-ctx time-ms bpm))
                            
                            ;; Normal mode: use per-param modulators
                            :else
                            (:params effect-instance))
              
              merged-params (merge-with-defaults effect-id user-params)]
          ;; Note: We pass merged-params (may contain modulators) to the transducer factory
          ;; The transducer is responsible for resolving them (either globally or per-point)
          ;; frame-ctx now includes :timing-ctx for modulator beat accumulation
          ;; When in keyframe mode (non-spatial), params are already resolved (no modulators)
          ;; When in spatial keyframe mode, params contain synthetic modulator configs
          (try
            (xf-fn time-ms bpm merged-params frame-ctx)
            (catch Throwable e
              (oe/report! (effect-error-key effect-instance) e)
              failed-to-build)))))))

(defn- effect-transducers
  "[effect transducer] for each enabled, known effect of the chain, after
   flattening nested groups."
  [chain frame-ctx]
  (into []
        (keep (fn [effect]
                (when-let [xf (make-effect-transducer effect frame-ctx)]
                  [effect xf])))
        (chains/flatten-chain (:effects chain))))

(defn- compose-pairs [pairs]
  (if (empty? pairs)
    (map identity)
    (apply comp (map second pairs))))

(defn compose-effect-transducers
  "Compose transducers from a chain of effects.
   
   Supports nested groups - the chain is flattened before processing,
   respecting enabled? flags at both effect and group level.
   
   Parameters:
   - chain: Effect chain {:effects [...]} - may contain groups
   - frame-ctx: {:point-count n :timing-ctx {...}} (see make-effect-transducer)
   
   Returns: A single composed transducer, or (map identity) for empty chains."
  [chain frame-ctx]
  (compose-pairs (effect-transducers chain frame-ctx)))

(defn- apply-pairs-one-by-one
  "Apply each [effect transducer] on its own, skipping (and reporting) each
   effect that throws."
  [frame pairs]
  (reduce (fn [frame [effect xf]]
            (if (identical? xf failed-to-build)
              frame
              (let [k (effect-error-key effect)]
                (try
                  (let [out (into [] xf frame)]
                    (oe/ok! k)
                    out)
                  (catch Throwable e
                    (oe/report! k e)
                    frame)))))
          (into [] frame)
          pairs))

(defn- apply-effects-one-by-one
  "The fallback of apply-effect-chain: the chain's effects applied one at a
   time, each one that throws skipped. Without errors, equal to the composed
   single pass."
  [frame chain frame-ctx]
  (apply-pairs-one-by-one frame (effect-transducers chain frame-ctx)))


;; Effect Chain Application


(defn apply-effect-chain
  "Apply an effect chain to a frame using transducer composition.
   
   All effect transducers are composed together and applied in a single pass,
   avoiding intermediate sequence allocations. If that pass throws, the effects
   are applied one at a time instead, and each effect that throws is skipped
   and reported to output-errors; the others still apply.
   
   Parameters:
   - frame: LaserFrame (vector of points) to transform
   - chain: Effect chain {:effects [...]} or nil
   - timing-ctx: (optional) the timing context modulators read
     (cue-timing/get-cue-timing-context, playback/clip-timing-ctx): its
     :time-ms and :bpm are the effects' time and tempo. Without it, modulators
     read beat 0.
   
   The chain's effects share one modulation context and one per-point base
   context, each made only if a parameter needs it.
   
   Returns: Transformed frame (vector of points)"
  ([frame chain]
   (apply-effect-chain frame chain nil))
  ([frame chain timing-ctx]
   (if (or (nil? chain) (empty? (:effects chain)))
     frame
     (let [frame-ctx (frame-context (count frame) timing-ctx)
           ;; Points stay as 5-element vectors throughout - no conversion needed!
           pairs (effect-transducers chain frame-ctx)]
       (try
         (let [out (into [] (compose-pairs pairs) frame)]
           (when (oe/failing?)
             (doseq [[effect xf] pairs
                     :when (not (identical? xf failed-to-build))]
               (oe/ok! (effect-error-key effect))))
           out)
         (catch Throwable _
           (apply-pairs-one-by-one frame pairs)))))))



(defn resolve-params-global
  "Resolve parameters globally (not per-point).
   Use this when no per-point modulators are present.
   
   Parameters:
   - raw-params: Parameter map that may contain modulators
   - time-ms: Current time
   - bpm: Current BPM
   - frame-ctx-or-timing-ctx: Either frame-ctx {:point-count n :timing-ctx {...}}
                              or direct timing-ctx {:accumulated-beats n :accumulated-ms n :phase-offset n}
   
   A context is only made when a parameter is a modulator: the chain's shared
   one when the frame context has it.
   
   Returns: Resolved parameter map."
  ([raw-params time-ms bpm]
   (resolve-params-global raw-params time-ms bpm nil))
  ([raw-params time-ms bpm frame-ctx-or-timing-ctx]
   (mod/resolve-params raw-params
                       (when (some mod/modulator-config? (vals raw-params))
                         (mod-context frame-ctx-or-timing-ctx time-ms bpm)))))




;; Simplified Per-Point Resolution via Resolver Functions


(defn make-param-resolver
  "Create a resolver function for a single parameter.
   
   This is the preferred way to handle per-point modulation. It returns a function
   (fn [x y idx] -> value) that efficiently resolves the parameter value.
   
   Optimization hierarchy:
   1. Static values: returns (constantly value) - no per-point overhead
   2. Global modulators: resolves once, returns (constantly resolved-value)
   3. Per-point modulators WITH compiler: compiles once, returns optimized fn
   4. Per-point modulators WITHOUT compiler: falls back to interpreter
   
   Parameters:
   - param-key: Keyword key of the parameter to resolve
   - params: Full params map (may contain modulators)
   - time-ms: Current time in milliseconds
   - bpm: Current BPM
   - ctx: Frame context {:point-count n :timing-ctx {...}}, or a bare timing
     context. When apply-effect-chain made it, the resolver uses the chain's
     shared contexts (:mod-ctx, :base-ctx) instead of making its own.
   
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
        point-count (:point-count ctx)]
    (cond
      ;; Static value (number, keyword, boolean, etc.) - return constant fn
      (not (mod/modulator-config? param-val))
      (constantly param-val)
      
      ;; Per-point modulator - try compilation first, fall back to interpreter
      (mod/config-requires-per-point? param-val)
      (if-let [compiled-fn (mod/compile-per-point-modulator param-val point-count)]
        ;; Compiled path: use optimized function directly
        compiled-fn
        ;; Interpreter fallback: evaluate per point with context
        (let [base-ctx (base-context ctx time-ms bpm point-count)]
          (fn [x y idx]
            (let [point-ctx (mod/with-point-context base-ctx x y idx)]
              (mod/evaluate-modulator param-val point-ctx)))))
      
      ;; Global modulator - resolve once, return constant fn
      :else
      (let [resolved (mod/resolve-param param-val (mod-context ctx time-ms bpm))]
        (constantly resolved)))))

(def resolve-param-fn
  "Alias for make-param-resolver for compatibility with modulation architecture."
  make-param-resolver)


;; Zone-stage effects (zones plan Z5, 07 §4.3)

(defn zone-stage-effect?
  "Is effect-def (a registered effect) a zone-stage one: it moves frames
   between places (:apply-zones) or picks the zone map (:select-map), and
   never touches a point?"
  [effect-def]
  (boolean (or (:apply-zones effect-def) (:select-map effect-def))))

(defn choice-at
  "The choice a modulated :choice parameter's value v picks: the one at
   index ⌊v + 1e-9⌋, clamped to choices; nil for none."
  [choices v]
  (let [n (count choices)]
    (when (pos? n)
      (nth choices (max 0 (min (dec n) (long (Math/floor (+ (double v) 1e-9)))))))))

(defn resolve-effect-params
  "An effect instance's parameters at timing-ctx, as its transducer would
   get them (make-effect-transducer, without a spatial driver): an enabled
   keyframe animation's, else its params, over the effect's defaults, each
   modulator resolved once (resolve-params-global); then each :modulatable?
   choice a modulator gave a number picks its choice by index (choice-at).
   For a zone-stage effect, which no transducer reads (vibe Phase 8)."
  [effect-instance timing-ctx]
  (let [effect-id (:effect-id effect-instance)
        km (:keyframe-modulator effect-instance)
        user-params (if (and km (:enabled? km))
                      (kf/eval-keyframe km (mod-context timing-ctx (:time-ms timing-ctx) (:bpm timing-ctx)))
                      (:params effect-instance))
        resolved (resolve-params-global (merge-with-defaults effect-id user-params)
                                        (:time-ms timing-ctx) (:bpm timing-ctx) timing-ctx)]
    (reduce (fn [params {:keys [key type choices modulatable?]}]
              (let [v (get params key)]
                (if (and modulatable? (= :choice type) (number? v))
                  (assoc params key (choice-at choices v))
                  params)))
            resolved
            (:parameters (get-effect effect-id)))))

(set! *warn-on-reflection* false)
(set! *unchecked-math* false)

