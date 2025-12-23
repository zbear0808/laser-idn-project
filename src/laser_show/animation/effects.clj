(ns laser-show.animation.effects
  "Core effect system for laser animations.
   Defines the effect protocol, timing modes, and effect registry.
   
   Supports both static parameters and modulated parameters:
   ;; Static (backward compatible)
   {:effect-id :scale :params {:x-scale 1.5}}
   
   ;; Modulated (using modulation.clj)
   {:effect-id :scale :params {:x-scale (mod/sine-mod 0.8 1.2 2.0)}}"
  (:require [laser-show.animation.time :as time]
            [laser-show.animation.modulation :as mod]))

;; ============================================================================
;; Timing Modes
;; ============================================================================

(def timing-modes
  "Supported timing modes for effects.
   - :static - No time dependency, constant parameters
   - :bpm - Synchronized to global BPM
   - :seconds - Time-based but not beat-synced"
  #{:static :bpm :seconds})

;; ============================================================================
;; Effect Categories
;; ============================================================================

(def effect-categories
  "Effect categories for organization and UI grouping.
   - :shape - Geometric transformations (scale, rotate, offset, etc.)
   - :color - Color modifications (hue, saturation, filters)
   - :intensity - Brightness and visibility effects
   - :calibration - Hardware-specific corrections (projector-only)"
  #{:shape :color :intensity :calibration})

;; ============================================================================
;; Effect Registry
;; ============================================================================

(defonce !effect-registry (atom {}))

(defn register-effect!
  "Register an effect definition in the global registry.
   
   Effect definition structure:
   {:id           :effect-id
    :name         \"Display Name\"
    :category     :shape/:color/:intensity/:calibration
    :timing       :static/:bpm/:seconds
    :parameters   [{:key :param-name
                    :label \"Param Label\"
                    :type :float/:int/:bool/:color/:choice
                    :default default-value
                    :min min-value (optional)
                    :max max-value (optional)
                    :choices [...] (for :choice type)}]
    :apply-fn     (fn [frame time-ms bpm params] transformed-frame)}"
  [effect-def]
  {:pre [(keyword? (:id effect-def))
         (string? (:name effect-def))
         (contains? effect-categories (:category effect-def))
         (contains? timing-modes (:timing effect-def))
         (vector? (:parameters effect-def))
         (fn? (:apply-fn effect-def))]}
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

(defn effect-exists?
  "Check if an effect is registered."
  [effect-id]
  (contains? @!effect-registry effect-id))

;; ============================================================================
;; Parameter Helpers
;; ============================================================================

(defn get-default-params
  "Get default parameter values for an effect."
  [effect-id]
  (when-let [effect-def (get-effect effect-id)]
    (into {}
          (map (fn [p] [(:key p) (:default p)])
               (:parameters effect-def)))))

(defn validate-params
  "Validate parameters against an effect definition.
   Returns {:valid? true/false :errors [...]}"
  [effect-id params]
  (if-let [effect-def (get-effect effect-id)]
    (let [param-defs (:parameters effect-def)
          errors (reduce
                  (fn [errs param-def]
                    (let [k (:key param-def)
                          v (get params k)
                          min-v (:min param-def)
                          max-v (:max param-def)]
                      (cond-> errs
                        (and (nil? v) (nil? (:default param-def)))
                        (conj (str "Missing required parameter: " k))
                        
                        (and (some? v) (some? min-v) (< v min-v))
                        (conj (str "Parameter " k " below minimum: " v " < " min-v))
                        
                        (and (some? v) (some? max-v) (> v max-v))
                        (conj (str "Parameter " k " above maximum: " v " > " max-v)))))
                  []
                  param-defs)]
      {:valid? (empty? errors) :errors errors})
    {:valid? false :errors [(str "Unknown effect: " effect-id)]}))

(defn merge-with-defaults
  "Merge provided params with defaults for an effect."
  [effect-id params]
  (merge (get-default-params effect-id) params))

;; ============================================================================
;; Modulation Helpers for Effects
;; ============================================================================

(defn resolve-param
  "Resolve a parameter that may be a static value or a modulator.
   Effects can call this to support both static and modulated parameters.
   
   Parameters:
   - param: Static value or modulator function
   - time-ms: Current time in milliseconds
   - bpm: Current BPM
   
   Returns: Resolved numeric value"
  [param time-ms bpm]
  (let [context {:time-ms time-ms :bpm bpm}]
    (mod/resolve-param param context)))

(defn resolve-params-map
  "Resolve all parameters in a map that may contain modulators.
   
   Parameters:
   - params: Map of parameter values (static or modulators)
   - time-ms: Current time in milliseconds
   - bpm: Current BPM
   
   Returns: Map with all modulators resolved to values"
  [params time-ms bpm]
  (let [context {:time-ms time-ms :bpm bpm}]
    (mod/resolve-params params context)))

;; ============================================================================
;; Effect Instance
;; ============================================================================

(defn make-effect-instance
  "Create an effect instance with parameters.
   
   Returns:
   {:effect-id :effect-id
    :enabled true/false
    :params {:param1 value1 ...}}"
  [effect-id & {:keys [enabled params] :or {enabled true params {}}}]
  {:effect-id effect-id
   :enabled enabled
   :params (merge-with-defaults effect-id params)})

(defn effect-instance-enabled?
  "Check if an effect instance is enabled."
  [instance]
  (:enabled instance true))

(defn set-effect-enabled
  "Set the enabled state of an effect instance."
  [instance enabled]
  (assoc instance :enabled enabled))

(defn update-effect-params
  "Update parameters of an effect instance."
  [instance new-params]
  (update instance :params merge new-params))

;; ============================================================================
;; Effect Application
;; ============================================================================

(defn apply-effect
  "Apply a single effect to a frame.
   
   Parameters:
   - frame: LaserFrame to transform
   - effect-instance: {:effect-id ... :enabled ... :params ...}
   - time-ms: Current time in milliseconds
   - bpm: Current BPM (from global state if not provided)
   - trigger-time: (optional) Time when the cue/effect was triggered, for once-mode modulators
   
   Parameters can be static values or modulators (from modulation.clj).
   Modulators are automatically resolved before the effect is applied.
   
   Returns: Transformed frame"
  ([frame effect-instance time-ms]
   (apply-effect frame effect-instance time-ms (time/get-global-bpm) nil))
  ([frame effect-instance time-ms bpm]
   (apply-effect frame effect-instance time-ms bpm nil))
  ([frame effect-instance time-ms bpm trigger-time]
   (if-not (effect-instance-enabled? effect-instance)
     frame
     (if-let [effect-def (get-effect (:effect-id effect-instance))]
       (let [apply-fn (:apply-fn effect-def)
             raw-params (:params effect-instance)
             context (mod/make-context {:time-ms time-ms :bpm bpm :trigger-time trigger-time})
             resolved-params (mod/resolve-params raw-params context)]
         (apply-fn frame time-ms bpm resolved-params))
       (do
         (println "Warning: Unknown effect:" (:effect-id effect-instance))
         frame)))))

;; ============================================================================
;; Effect Chain
;; ============================================================================

(defn make-effect-chain
  "Create an effect chain from a sequence of effect instances.
   
   Returns:
   {:effects [effect-instance1 effect-instance2 ...]}"
  [& effect-instances]
  {:effects (vec effect-instances)})

(defn empty-effect-chain
  "Create an empty effect chain."
  []
  {:effects []})

(defn effect-chain?
  "Check if something is an effect chain."
  [x]
  (and (map? x) (vector? (:effects x))))

(defn add-effect-to-chain
  "Add an effect instance to the end of a chain."
  [chain effect-instance]
  (update chain :effects conj effect-instance))

(defn insert-effect-at
  "Insert an effect instance at a specific index."
  [chain index effect-instance]
  (update chain :effects
          (fn [effects]
            (vec (concat (take index effects)
                         [effect-instance]
                         (drop index effects))))))

(defn remove-effect-at
  "Remove an effect instance at a specific index."
  [chain index]
  (update chain :effects
          (fn [effects]
            (vec (concat (take index effects)
                         (drop (inc index) effects))))))

(defn update-effect-at
  "Update an effect instance at a specific index."
  [chain index updates]
  (update-in chain [:effects index] merge updates))

(defn reorder-effects
  "Move an effect from one index to another."
  [chain from-index to-index]
  (let [effects (:effects chain)
        effect (nth effects from-index)
        without (vec (concat (take from-index effects)
                             (drop (inc from-index) effects)))]
    (assoc chain :effects
           (vec (concat (take to-index without)
                        [effect]
                        (drop to-index without))))))

(defn enable-effect-at
  "Enable an effect at a specific index."
  [chain index]
  (assoc-in chain [:effects index :enabled] true))

(defn disable-effect-at
  "Disable an effect at a specific index."
  [chain index]
  (assoc-in chain [:effects index :enabled] false))

(defn chain-length
  "Get the number of effects in a chain."
  [chain]
  (count (:effects chain)))

;; ============================================================================
;; Effect Chain Application
;; ============================================================================

(defn apply-effect-chain
  "Apply an effect chain to a frame.
   Effects are applied in order, each receiving the output of the previous.
   
   Parameters:
   - frame: LaserFrame to transform
   - chain: Effect chain or nil
   - time-ms: Current time in milliseconds
   - bpm: Current BPM (from global state if not provided)
   - trigger-time: (optional) Time when the cue was triggered, for once-mode modulators
   
   Returns: Transformed frame"
  ([frame chain time-ms]
   (apply-effect-chain frame chain time-ms (time/get-global-bpm) nil))
  ([frame chain time-ms bpm]
   (apply-effect-chain frame chain time-ms bpm nil))
  ([frame chain time-ms bpm trigger-time]
   (if (or (nil? chain) (empty? (:effects chain)))
     frame
     (reduce
      (fn [f effect-instance]
        (apply-effect f effect-instance time-ms bpm trigger-time))
      frame
      (:effects chain)))))

;; ============================================================================
;; Utility Functions for Frame Transformation
;; ============================================================================

(defn transform-points
  "Apply a transformation function to all points in a frame.
   The transform-fn receives a point and returns a transformed point."
  [frame transform-fn]
  (update frame :points #(mapv transform-fn %)))

(defn transform-points-indexed
  "Apply a transformation function to all points with index.
   The transform-fn receives [index point] and returns a transformed point."
  [frame transform-fn]
  (update frame :points
          (fn [points]
            (mapv (fn [idx pt] (transform-fn idx pt))
                  (range)
                  points))))

(defn transform-colors
  "Apply a transformation function to point colors.
   The transform-fn receives [r g b] and returns [r g b].
   Blanked points (r=g=b=0) are not transformed by default."
  [frame transform-fn & {:keys [transform-blanked] :or {transform-blanked false}}]
  (transform-points
   frame
   (fn [point]
     (let [r (bit-and (:r point) 0xFF)
           g (bit-and (:g point) 0xFF)
           b (bit-and (:b point) 0xFF)]
       (if (and (not transform-blanked)
                (zero? r) (zero? g) (zero? b))
         point
         (let [[nr ng nb] (transform-fn [r g b])]
           (assoc point
                  :r (unchecked-byte (max 0 (min 255 (int nr))))
                  :g (unchecked-byte (max 0 (min 255 (int ng))))
                  :b (unchecked-byte (max 0 (min 255 (int nb)))))))))))

(defn transform-positions
  "Apply a transformation function to point positions.
   The transform-fn receives [norm-x norm-y] in [-1,1] range and returns [norm-x norm-y]."
  [frame transform-fn]
  (transform-points
   frame
   (fn [point]
     (let [norm-x (/ (:x point) 32767.0)
           norm-y (/ (:y point) 32767.0)
           [new-x new-y] (transform-fn [norm-x norm-y])]
       (assoc point
              :x (short (Math/round (* (max -1.0 (min 1.0 new-x)) 32767.0)))
              :y (short (Math/round (* (max -1.0 (min 1.0 new-y)) 32767.0))))))))
