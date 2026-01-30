(ns laser-show.animation.modulator-defs
  "Modulator helper functions for working with modulated parameter values.
   
   This namespace provides utility functions for:
   - Checking if values are modulated
   - Building default modulator configs
   - Getting static values from modulated params
   
   For modulator type definitions, parameter specs, and evaluation,
   use laser-show.animation.modulator-registry instead."
  (:require [laser-show.animation.modulator-registry :as reg]))


(defn- create-default-spatial-keyframes
  "Create default keyframes for spatial-keyframe modulator.
   Creates two keyframes at positions 0.0 and 1.0 with min and max values."
  [min-val max-val]
  [{:position 0.0 :value (double min-val) :interpolation :linear}
   {:position 1.0 :value (double max-val) :interpolation :linear}])

(defn build-default-modulator
  "Build a default modulator config for the given type with param-spec bounds.
   
   Parameters:
   - mod-type: Keyword identifying the modulator type (e.g., :sine, :triangle)
   - param-spec: Parameter specification map with :min and :max bounds
   
   Returns a modulator config map with default values from the registry,
   optionally overriding min/max with values from param-spec.
   Includes :active? true by default.
   
   Special handling for :spatial-keyframe type: creates default keyframes
   at 0% and 100% positions with min/max values."
  [mod-type param-spec]
  (let [base-params (reg/get-params mod-type)
        defaults (into {:type mod-type
                        :active? true}  ; Add active flag by default
                       (mapv (fn [p] [(:key p) (:default p)])
                             base-params))
        ;; Get min/max values, preferring param-spec over registry defaults
        min-val (or (:min param-spec) (:min defaults) 0.0)
        max-val (or (:max param-spec) (:max defaults) 1.0)]
    ;; Override min/max with param-spec bounds if they have reasonable values
    (cond-> defaults
      (and (:min param-spec) (not= (:min param-spec) -10.0))
      (assoc :min (:min param-spec))
      
      (and (:max param-spec) (not= (:max param-spec) 10.0))
      (assoc :max (:max param-spec))
      
      ;; Special case: spatial-keyframe needs default keyframes
      (= mod-type :spatial-keyframe)
      (assoc :keyframes (create-default-spatial-keyframes min-val max-val)))))
