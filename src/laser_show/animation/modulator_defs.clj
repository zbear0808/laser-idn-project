(ns laser-show.animation.modulator-defs
  "Modulator type definitions and parameter specifications.
   
   This namespace provides the single source of truth for:
   - Available modulator types and their metadata (icons, names, categories)
   - Parameter definitions for each modulator type (ranges, defaults, types)
   - Helper functions for working with modulator configs
   
   Both UI components and event handlers import from this namespace
   to ensure consistency and avoid duplication."
  (:require [laser-show.animation.modulation :as mod]))



;; Modulator Type Definitions



(def wave-modulators
  "Wave-based modulator types. Period can be beats or seconds."
  [{:id :sine :name "Sine" :icon "〰️"}
   {:id :triangle :name "Triangle" :icon "△"}
   {:id :sawtooth :name "Sawtooth" :icon "⟋|"}
   {:id :square :name "Square" :icon "▭"}
   {:id :random :name "Random" :icon "⚡"}
   {:id :step :name "Step" :icon "⊟"}])

(def one-shot-modulators
  "One-shot modulators that run once when triggered."
  [{:id :decay :name "Decay" :icon "↘"}])

(def special-modulators
  "Special modulators for per-point effects."
  [{:id :pos-x :name "Position X" :icon "↔"}
   {:id :pos-y :name "Position Y" :icon "↕"}
   {:id :radial :name "Radial" :icon "◎"}
   {:id :point-index :name "Point Index" :icon "🔢"}])

(def all-modulator-types
  "All available modulator types grouped by category."
  {:wave wave-modulators
   :one-shot one-shot-modulators
   :special special-modulators})

(def modulator-type->info
  "Map of modulator type keyword to info map.
   Enables quick lookup of modulator metadata by type."
  (into {}
        (mapcat (fn [[_category mods]]
                  (mapv (fn [m] [(:id m) m]) mods)))
        all-modulator-types))

(def all-modulator-type-list
  "Flat list of all modulator types in display order."
  (vec (concat wave-modulators one-shot-modulators special-modulators)))



;; Modulator Parameter Definitions



(def modulator-params
  "Parameter definitions for each modulator type.
   Each param has :key, :label, :type, :min, :max, :default.
   Wave modulators have period-unit to switch between beats and seconds."
  {:sine [{:key :min :label "Min" :type :float :min -10.0 :max 10.0 :default 0.0}
          {:key :max :label "Max" :type :float :min -10.0 :max 10.0 :default 1.0}
          {:key :period :label "Period" :type :float :min 0.0625 :max 16.0 :default 1.0}
          {:key :period-unit :label "Unit" :type :choice :choices [:beats :seconds] :default :beats}
          {:key :loop-mode :label "Mode" :type :choice :choices [:loop :once] :default :loop}
          {:key :once-periods :label "# Periods" :type :float :min 0.125 :max 8.0 :default 1.0}
          {:key :phase :label "Phase" :type :float :min 0.0 :max 1.0 :default 0.0}]
   
   :triangle [{:key :min :label "Min" :type :float :min -10.0 :max 10.0 :default 0.0}
              {:key :max :label "Max" :type :float :min -10.0 :max 10.0 :default 1.0}
              {:key :period :label "Period" :type :float :min 0.0625 :max 16.0 :default 1.0}
              {:key :period-unit :label "Unit" :type :choice :choices [:beats :seconds] :default :beats}
              {:key :loop-mode :label "Mode" :type :choice :choices [:loop :once] :default :loop}
              {:key :once-periods :label "# Periods" :type :float :min 0.125 :max 8.0 :default 1.0}
              {:key :phase :label "Phase" :type :float :min 0.0 :max 1.0 :default 0.0}]
   
   :sawtooth [{:key :min :label "Min" :type :float :min -10.0 :max 10.0 :default 0.0}
              {:key :max :label "Max" :type :float :min -10.0 :max 10.0 :default 1.0}
              {:key :period :label "Period" :type :float :min 0.0625 :max 16.0 :default 1.0}
              {:key :period-unit :label "Unit" :type :choice :choices [:beats :seconds] :default :beats}
              {:key :loop-mode :label "Mode" :type :choice :choices [:loop :once] :default :loop}
              {:key :once-periods :label "# Periods" :type :float :min 0.125 :max 8.0 :default 1.0}
              {:key :phase :label "Phase" :type :float :min 0.0 :max 1.0 :default 0.0}]
   
   :square [{:key :min :label "Min" :type :float :min -10.0 :max 10.0 :default 0.0}
            {:key :max :label "Max" :type :float :min -10.0 :max 10.0 :default 1.0}
            {:key :period :label "Period" :type :float :min 0.0625 :max 16.0 :default 1.0}
            {:key :period-unit :label "Unit" :type :choice :choices [:beats :seconds] :default :beats}
            {:key :loop-mode :label "Mode" :type :choice :choices [:loop :once] :default :loop}
            {:key :once-periods :label "# Periods" :type :float :min 0.125 :max 8.0 :default 1.0}
            {:key :duty-cycle :label "Duty Cycle" :type :float :min 0.0 :max 1.0 :default 0.5}
            {:key :phase :label "Phase" :type :float :min 0.0 :max 1.0 :default 0.0}]
   
   :random [{:key :min :label "Min" :type :float :min -10.0 :max 10.0 :default 0.0}
            {:key :max :label "Max" :type :float :min -10.0 :max 10.0 :default 1.0}
            {:key :period :label "Period" :type :float :min 0.0625 :max 16.0 :default 1.0}
            {:key :period-unit :label "Unit" :type :choice :choices [:beats :seconds] :default :beats}
            {:key :loop-mode :label "Mode" :type :choice :choices [:loop :once] :default :loop}
            {:key :once-periods :label "# Periods" :type :float :min 0.125 :max 8.0 :default 1.0}]
   
   :step [{:key :values :label "Values" :type :text :default "[0 0.5 1]"}
          {:key :period :label "Period" :type :float :min 0.0625 :max 16.0 :default 1.0}
          {:key :period-unit :label "Unit" :type :choice :choices [:beats :seconds] :default :beats}
          {:key :loop-mode :label "Mode" :type :choice :choices [:loop :once] :default :loop}
          {:key :once-periods :label "# Periods" :type :float :min 0.125 :max 8.0 :default 1.0}]
   
   :decay [{:key :min :label "Min" :type :float :min -10.0 :max 10.0 :default 0.0}
           {:key :max :label "Max" :type :float :min -10.0 :max 10.0 :default 1.0}
           {:key :duration :label "Duration" :type :float :min 0.0625 :max 16.0 :default 1.0}
           {:key :duration-unit :label "Unit" :type :choice :choices [:beats :seconds] :default :beats}
           {:key :decay-curve :label "Curve" :type :choice :choices [:linear :exp :log] :default :exp}]
   
   :pos-x [{:key :min :label "Min" :type :float :min -10.0 :max 10.0 :default 0.0}
           {:key :max :label "Max" :type :float :min -10.0 :max 10.0 :default 1.0}]
   
   :pos-y [{:key :min :label "Min" :type :float :min -10.0 :max 10.0 :default 0.0}
           {:key :max :label "Max" :type :float :min -10.0 :max 10.0 :default 1.0}]
   
   :radial [{:key :min :label "Min" :type :float :min -10.0 :max 10.0 :default 0.0}
            {:key :max :label "Max" :type :float :min -10.0 :max 10.0 :default 1.0}]
   
   :point-index [{:key :min :label "Min" :type :float :min -10.0 :max 10.0 :default 0.0}
                 {:key :max :label "Max" :type :float :min -10.0 :max 10.0 :default 1.0}]})



;; Helper Functions



(defn modulated?
  "Check if a param value is a modulator config."
  [value]
  (mod/modulator-config? value))

(defn get-static-value
  "Extract static value from param (handles both static and modulated).
   For modulated values, returns the mid-point of min/max."
  [value default-value]
  (if (modulated? value)
    (let [{:keys [min max]} value]
      (if (and min max)
        (/ (+ (double min) (double max)) 2.0)
        (or default-value 0.0)))
    (or value default-value 0.0)))

(defn build-default-modulator
  "Build a default modulator config for the given type with param-spec bounds.
   
   Parameters:
   - mod-type: Keyword identifying the modulator type (e.g., :sine, :triangle)
   - param-spec: Parameter specification map with :min and :max bounds
   
   Returns a modulator config map with default values from modulator-params,
   optionally overriding min/max with values from param-spec."
  [mod-type param-spec]
  (let [base-params (get modulator-params mod-type [])
        defaults (into {:type mod-type}
                       (mapv (fn [p] [(:key p) (:default p)])
                             base-params))]
    ;; Override min/max with param-spec bounds if they have reasonable values
    (cond-> defaults
      (and (:min param-spec) (not= (:min param-spec) -10.0))
      (assoc :min (:min param-spec))
      
      (and (:max param-spec) (not= (:max param-spec) 10.0))
      (assoc :max (:max param-spec)))))

(def retrigger-modulator-types
  "Set of modulator types that support retriggering."
  #{:sine :triangle :sawtooth :square :random :step :decay})

(defn supports-retrigger?
  "Check if a modulator type supports retriggering."
  [mod-type]
  (contains? retrigger-modulator-types mod-type))
