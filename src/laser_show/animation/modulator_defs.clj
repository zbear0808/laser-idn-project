(ns laser-show.animation.modulator-defs
  "Modulator helper functions for working with modulated parameter values.
   
   This namespace provides utility functions for:
   - Checking if values are modulated
   - Building default modulator configs
   - Getting static values from modulated params
   
   For modulator type definitions, parameter specs, and evaluation,
   use laser-show.animation.modulator-registry instead."
  (:require [laser-show.animation.modulator-registry :as reg]))



;; Helper Functions



(defn modulated?
  "Check if a param value is a modulator config.
   Delegates to registry for consistency."
  [value]
  (reg/modulated? value))

(defn get-static-value
  "Extract static value from param (handles both static and modulated).
   Delegates to registry for consistency."
  [value default-value]
  (reg/get-static-value value default-value))

(defn build-default-modulator
  "Build a default modulator config for the given type with param-spec bounds.
   
   Parameters:
   - mod-type: Keyword identifying the modulator type (e.g., :sine, :triangle)
   - param-spec: Parameter specification map with :min and :max bounds
   
   Returns a modulator config map with default values from the registry,
   optionally overriding min/max with values from param-spec.
   Includes :active? true by default."
  [mod-type param-spec]
  (let [base-params (reg/get-params mod-type)
        defaults (into {:type mod-type
                        :active? true}  ; Add active flag by default
                       (mapv (fn [p] [(:key p) (:default p)])
                             base-params))]
    ;; Override min/max with param-spec bounds if they have reasonable values
    (cond-> defaults
      (and (:min param-spec) (not= (:min param-spec) -10.0))
      (assoc :min (:min param-spec))
      
      (and (:max param-spec) (not= (:max param-spec) 10.0))
      (assoc :max (:max param-spec)))))

(defn active-modulator?
  "Check if a param value is an active modulator config.
   Delegates to registry for consistency."
  [value]
  (reg/active-modulator? value))

(defn supports-retrigger?
  "Check if a modulator type supports retriggering.
   Delegates to registry."
  [mod-type]
  (reg/retrigger? mod-type))
