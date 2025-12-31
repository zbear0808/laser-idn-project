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



;; Effect Application


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
   Effect parameters are merged with defaults to ensure no nil values.
   
   Returns: Transformed frame"
  ([frame effect-instance time-ms]
   (apply-effect frame effect-instance time-ms (time/get-global-bpm) nil))
  ([frame effect-instance time-ms bpm]
   (apply-effect frame effect-instance time-ms bpm nil))
  ([frame effect-instance time-ms bpm trigger-time]
   (if-not (effect-instance-enabled? effect-instance)
     frame
     (let [effect-id (:effect-id effect-instance)]
       (if-let [effect-def (get-effect effect-id)]
         (let [apply-fn (:apply-fn effect-def)
               user-params (:params effect-instance)
               ;; Merge with defaults to ensure no nil parameter values
               merged-params (merge-with-defaults effect-id user-params)
               context (mod/make-context {:time-ms time-ms :bpm bpm :trigger-time trigger-time})
               resolved-params (mod/resolve-params merged-params context)]
           ;; Debug logging - uncomment to trace effect application
           ;; (println "[DEBUG apply-effect]" effect-id "user-params:" user-params "merged:" merged-params "resolved:" resolved-params)
           (try
             (apply-fn frame time-ms bpm resolved-params)
             (catch Exception e
               (println "[ERROR apply-effect]" effect-id "failed:" (.getMessage e))
               (println "  user-params:" user-params)
               (println "  merged-params:" merged-params)
               (println "  resolved-params:" resolved-params)
               (println "  frame nil?" (nil? frame) "points count:" (count (:points frame)))
               frame)))
         (do
           (println "Warning: Unknown effect:" effect-id)
           frame))))))

; effect chain 

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

;; Utility Functions for Frame Transformation

(defn transform-point-full
  "Apply a transformation function to all points in a frame with full context.
   The transform-fn receives a flat map:
   {:x norm-x :y norm-y :r r :g g :b b :count count}
   
   Returns: transformed map (same structure), or nil to delete the point.
   Uses (into [] (keep ...)) to support point deletion.
   
   Note: Most effects should use this function. For effects that need point
   index, use transform-point-full-indexed instead."
  [frame transform-fn]
  (let [points (:points frame)
        point-count (count points)]
    (assoc frame :points
      (into []
        (keep
          (fn [point]
            (let [norm-x (/ (:x point) 32767.0)
                  norm-y (/ (:y point) 32767.0)
                  r (bit-and (:r point) 0xFF)
                  g (bit-and (:g point) 0xFF)
                  b (bit-and (:b point) 0xFF)
                  result (transform-fn {:x norm-x
                                        :y norm-y
                                        :r r
                                        :g g
                                        :b b
                                        :count point-count})]
              (when result
                (assoc point
                  :x (short (Math/round (* (max -1.0 (min 1.0 (:x result))) 32767.0)))
                  :y (short (Math/round (* (max -1.0 (min 1.0 (:y result))) 32767.0)))
                  :r (unchecked-byte (max 0 (min 255 (int (:r result)))))
                  :g (unchecked-byte (max 0 (min 255 (int (:g result)))))
                  :b (unchecked-byte (max 0 (min 255 (int (:b result)))))))))
          points)))))

(defn transform-point-full-indexed
  "Apply a transformation function to all points in a frame with index.
   The transform-fn receives a flat map:
   {:x norm-x :y norm-y :r r :g g :b b :idx idx :count count}
   
   Returns: transformed map (same structure), or nil to delete the point.
   Uses (into [] (keep-indexed ...)) to support point deletion.
   
   Note: Only use this for effects that specifically need point index.
   For most effects, use transform-point-full instead."
  [frame transform-fn]
  (let [points (:points frame)
        point-count (count points)]
    (assoc frame :points
      (into []
        (keep-indexed
          (fn [idx point]
            (let [norm-x (/ (:x point) 32767.0)
                  norm-y (/ (:y point) 32767.0)
                  r (bit-and (:r point) 0xFF)
                  g (bit-and (:g point) 0xFF)
                  b (bit-and (:b point) 0xFF)
                  result (transform-fn {:x norm-x
                                        :y norm-y
                                        :r r
                                        :g g
                                        :b b
                                        :idx idx
                                        :count point-count})]
              (when result
                (assoc point
                  :x (short (Math/round (* (max -1.0 (min 1.0 (:x result))) 32767.0)))
                  :y (short (Math/round (* (max -1.0 (min 1.0 (:y result))) 32767.0)))
                  :r (unchecked-byte (max 0 (min 255 (int (:r result)))))
                  :g (unchecked-byte (max 0 (min 255 (int (:g result)))))
                  :b (unchecked-byte (max 0 (min 255 (int (:b result)))))))))
          points)))))


;; Legacy Transform Functions (for backward compatibility during transition)




(defn transform-colors-per-point
  "LEGACY: Apply a per-point transformation to colors.
   The transform-fn receives [point-index point-count x y r g b resolved-params]
   where resolved-params is a map of resolved parameters for that specific point.
   Returns [r g b].
   
   This enables effects like:
   - Rainbow gradients based on position
   - Radial brightness fades
   - Wave patterns across points
   
   Parameters:
   - frame: The laser frame to transform
   - time-ms: Current time in milliseconds
   - bpm: Current BPM
   - raw-params: Parameter map that may contain per-point modulators
   - transform-fn: Function that receives [idx cnt x y r g b params] -> [r g b]
   - transform-blanked: If true, transforms blanked points (default false)
   
   NOTE: Consider using transform-point-full-indexed instead for new code."
  [frame time-ms bpm raw-params transform-fn & {:keys [transform-blanked] :or {transform-blanked false}}]
  (let [points (:points frame)
        point-count (count points)
        base-context {:time-ms time-ms :bpm bpm}]
    (update frame :points
      (fn [pts]
        (mapv (fn [idx point]
                (let [x (/ (:x point) 32767.0)
                      y (/ (:y point) 32767.0)
                      r (bit-and (:r point) 0xFF)
                      g (bit-and (:g point) 0xFF)
                      b (bit-and (:b point) 0xFF)]
                  (if (and (not transform-blanked)
                           (zero? r) (zero? g) (zero? b))
                    point  ; Skip blanked points
                    (let [point-context (assoc base-context
                                          :x x :y y
                                          :point-index idx
                                          :point-count point-count)
                          resolved-params (mod/resolve-params raw-params point-context)
                          [nr ng nb] (transform-fn idx point-count x y r g b resolved-params)]
                      (assoc point
                        :r (unchecked-byte (max 0 (min 255 (int nr))))
                        :g (unchecked-byte (max 0 (min 255 (int ng))))
                        :b (unchecked-byte (max 0 (min 255 (int nb)))))))))
              (range)
              pts)))))
