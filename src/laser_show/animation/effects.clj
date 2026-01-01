(ns laser-show.animation.effects
  "Core effect system for laser animations using transducers.
   
   Effects are registered with an `apply-transducer` function that returns
   a transducer to transform normalized points. When applying an effect chain,
   all transducers are composed together and applied in a single pass,
   avoiding intermediate sequence allocations.
   
   Supports both static parameters and modulated parameters:
   ;; Static
   {:effect-id :scale :params {:x-scale 1.5}}
   
   ;; Modulated (using modulation.clj)
   {:effect-id :scale :params {:x-scale (mod/sine-mod 0.8 1.2 2.0)}}
   
   Transducer signature:
   (fn [time-ms bpm resolved-params frame-context]
     ;; Returns a transducer that transforms normalized points
     (map (fn [pt] ...)))
   
   Normalized point format:
   {:x double (-1.0 to 1.0)
    :y double (-1.0 to 1.0)
    :r int (0-255)
    :g int (0-255)
    :b int (0-255)
    :idx int (point index)
    :count int (total points)
    :raw map (original point for pass-through)}"
  (:require [laser-show.animation.time :as time]
            [laser-show.animation.modulation :as mod]))


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
   - :calibration - Hardware-specific corrections (projector-only)"
  #{:shape :color :intensity :calibration})


;; Effect Registry


(defonce !effect-registry (atom {}))

(defn register-effect!
  "Register an effect definition in the global registry.
   
   Effect definition structure:
   {:id              :effect-id
    :name            \"Display Name\"
    :category        :shape/:color/:intensity/:calibration
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
         (contains? effect-categories (:category effect-def))
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

(defn list-effects-by-category
  "List effects filtered by category."
  [category]
  (filter #(= category (:category %)) (list-effects)))




;; Parameter Helpers


(defn get-default-params
  "Get default parameter values for an effect."
  [effect-id]
  (when-let [effect-def (get-effect effect-id)]
    (into {}
          (map (fn [p] [(:key p) (:default p)])
               (:parameters effect-def)))))



(defn merge-with-defaults
  "Merge provided params with defaults for an effect."
  [effect-id params]
  (merge (get-default-params effect-id) params))



;; Effect Instance


(defn effect-instance-enabled?
  "Check if an effect instance is enabled."
  [instance]
  (:enabled instance true))



;; Point Normalization Transducers


(defn normalize-point
  "Convert a raw frame point to normalized format.
   
   Raw format: {:x short :y short :r byte :g byte :b byte}
   Normalized: {:x [-1.0, 1.0] :y [-1.0, 1.0] :r [0-255] :g [0-255] :b [0-255] :raw original}"
  [point]
  {:x (/ (:x point) 32767.0)
   :y (/ (:y point) 32767.0)
   :r (bit-and (:r point) 0xFF)
   :g (bit-and (:g point) 0xFF)
   :b (bit-and (:b point) 0xFF)
   :raw point})

(defn denormalize-point
  "Convert a normalized point back to raw frame format.
   Returns nil if input is nil (supports point filtering).
   
   Clamps coordinates to [-1.0, 1.0] and colors to [0, 255]."
  [pt]
  (when pt
    (assoc (:raw pt)
      :x (short (Math/round (* (max -1.0 (min 1.0 (double (:x pt)))) 32767.0)))
      :y (short (Math/round (* (max -1.0 (min 1.0 (double (:y pt)))) 32767.0)))
      :r (unchecked-byte (max 0 (min 255 (int (:r pt)))))
      :g (unchecked-byte (max 0 (min 255 (int (:g pt)))))
      :b (unchecked-byte (max 0 (min 255 (int (:b pt))))))))

(def normalize-xf
  "Transducer that normalizes raw frame points."
  (map normalize-point))

(def denormalize-xf
  "Transducer that converts normalized points back to raw format.
   Uses `keep` to support point deletion (nil -> filtered out)."
  (keep denormalize-point))

(defn add-index-xf
  "Returns a transducer that adds :idx and :count to each point.
   Uses volatile for efficient stateful transformation."
  [point-count]
  (let [idx (volatile! -1)]
    (map (fn [pt]
           (assoc pt
             :idx (vswap! idx inc)
             :count point-count)))))


;; Effect Transducer Composition


(defn make-effect-transducer
  "Create a transducer for a single effect instance.
   
   Parameters:
   - effect-instance: {:effect-id :scale :enabled true :params {...}}
   - time-ms: Current time in milliseconds
   - bpm: Current BPM
   - trigger-time: When the effect was triggered (for once-mode modulators)
   - frame-ctx: {:point-count n}
   
   Returns: A transducer, or nil if effect is disabled or unknown."
  [effect-instance time-ms bpm trigger-time frame-ctx]
  (when (effect-instance-enabled? effect-instance)
    (let [effect-id (:effect-id effect-instance)]
      (when-let [effect-def (get-effect effect-id)]
        (let [xf-fn (:apply-transducer effect-def)
              user-params (:params effect-instance)
              merged-params (merge-with-defaults effect-id user-params)]
          ;; Note: We pass merged-params (may contain modulators) to the transducer factory
          ;; The transducer is responsible for resolving them (either globally or per-point)
          (try
            (xf-fn time-ms bpm merged-params frame-ctx)
            (catch Exception e
              (println "[ERROR make-effect-transducer]" effect-id "failed:" (.getMessage e))
              (println "  params:" merged-params)
              ;; Return identity transducer on error
              (map identity))))))))

(defn compose-effect-transducers
  "Compose transducers from a chain of effects.
   
   Parameters:
   - chain: Effect chain {:effects [...]}
   - time-ms: Current time in milliseconds
   - bpm: Current BPM
   - trigger-time: When effects were triggered
   - frame-ctx: {:point-count n}
   
   Returns: A single composed transducer, or (map identity) for empty chains."
  [chain time-ms bpm trigger-time frame-ctx]
  (let [effects (:effects chain)
        transducers (keep #(make-effect-transducer % time-ms bpm trigger-time frame-ctx) effects)]
    (if (empty? transducers)
      (map identity)
      (apply comp transducers))))


;; Effect Chain Application


(defn apply-effect-chain
  "Apply an effect chain to a frame using transducer composition.
   
   All effect transducers are composed together and applied in a single pass,
   avoiding intermediate sequence allocations.
   
   Parameters:
   - frame: LaserFrame to transform
   - chain: Effect chain {:effects [...]} or nil
   - time-ms: Current time in milliseconds
   - bpm: Current BPM (from global state if not provided)
   - trigger-time: (optional) Time when the cue was triggered
   
   Returns: Transformed frame"
  ([frame chain time-ms]
   (apply-effect-chain frame chain time-ms (time/get-global-bpm) nil))
  ([frame chain time-ms bpm]
   (apply-effect-chain frame chain time-ms bpm nil))
  ([frame chain time-ms bpm trigger-time]
   (if (or (nil? chain) (empty? (:effects chain)))
     frame
     (let [points (:points frame)
           point-count (count points)
           frame-ctx {:point-count point-count}
           
           ;; Compose: normalize -> add-index -> effects... -> denormalize
           effect-xf (compose-effect-transducers chain time-ms bpm trigger-time frame-ctx)
           full-xf (comp normalize-xf
                        (add-index-xf point-count)
                        effect-xf
                        denormalize-xf)]
       (assoc frame :points (into [] full-xf points))))))


;; Single Effect Application (convenience)


(defn apply-effect
  "Apply a single effect to a frame.
   
   This is a convenience function that wraps the effect in a chain.
   For multiple effects, use apply-effect-chain directly for better performance.
   
   Parameters:
   - frame: LaserFrame to transform
   - effect-instance: {:effect-id ... :enabled ... :params ...}
   - time-ms: Current time in milliseconds
   - bpm: Current BPM (from global state if not provided)
   - trigger-time: (optional) Time when the effect was triggered
   
   Returns: Transformed frame"
  ([frame effect-instance time-ms]
   (apply-effect frame effect-instance time-ms (time/get-global-bpm) nil))
  ([frame effect-instance time-ms bpm]
   (apply-effect frame effect-instance time-ms bpm nil))
  ([frame effect-instance time-ms bpm trigger-time]
   (apply-effect-chain frame {:effects [effect-instance]} time-ms bpm trigger-time)))


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
   
   Returns: Resolved parameter map with all modulators evaluated."
  [raw-params time-ms bpm x y idx count]
  (let [context (mod/make-context {:time-ms time-ms
                                   :bpm bpm
                                   :x x
                                   :y y
                                   :point-index idx
                                   :point-count count})]
    (mod/resolve-params raw-params context)))

(defn resolve-params-global
  "Resolve parameters globally (not per-point).
   Use this when no per-point modulators are present.
   
   Parameters:
   - raw-params: Parameter map that may contain modulators
   - time-ms: Current time
   - bpm: Current BPM
   
   Returns: Resolved parameter map."
  [raw-params time-ms bpm]
  (let [context (mod/make-context {:time-ms time-ms :bpm bpm})]
    (mod/resolve-params raw-params context)))


;; Transducer Helpers for Effect Implementations


(defn simple-point-xf
  "Create a simple point transformation transducer.
   
   The transform-fn receives a normalized point map and returns
   a transformed point map (or nil to delete the point).
   
   Example:
   (simple-point-xf
     (fn [{:keys [x y] :as pt}]
       (assoc pt :x (* x 2) :y (* y 2))))"
  [transform-fn]
  (keep transform-fn))

(defn color-xf
  "Create a color transformation transducer that skips blanked points.
   
   The transform-fn receives {:r :g :b} and returns {:r :g :b}.
   Blanked points (r=g=b=0) are passed through unchanged.
   
   Example:
   (color-xf
     (fn [{:keys [r g b]}]
       {:r (- 255 r) :g (- 255 g) :b (- 255 b)}))"
  [transform-fn]
  (map (fn [{:keys [r g b] :as pt}]
         (if (and (zero? r) (zero? g) (zero? b))
           pt  ;; Skip blanked points
           (merge pt (transform-fn pt))))))
