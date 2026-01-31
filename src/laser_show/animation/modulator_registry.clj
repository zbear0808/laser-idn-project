(ns laser-show.animation.modulator-registry
  "Central registry for modulator type definitions.
   
   This namespace is the foundation for all modulator definitions:
   - Registry atom holds all registered modulator types
   - Registration function with validation
   - Accessor functions for querying modulator metadata
   - Helper functions for working with modulated values
   
   This file has NO dependencies on other modulator files.")

(set! *warn-on-reflection* true)


;; Registry State


(defonce ^{:doc "Registry atom: map from modulator id (keyword) to modulator info map.
   
   Each modulator info map contains:
   - :id        - Keyword identifier (e.g., :sine, :decay)
   - :name      - Display name (e.g., \"Sine\", \"Decay\")
   - :icon      - Icon string/emoji for UI
   - :category  - Category keyword (:wave, :one-shot, :special, :external, :internal)
   - :evaluator - Function (fn [config context] -> value) for evaluation
   - :params    - Vector of parameter definition maps
   - :per-point?   - Boolean, true if requires per-point context (default false)
   - :retrigger?   - Boolean, true if supports retriggering (default false)
   - :description  - Optional description string"}
  !modulators
  (atom {}))

(defonce ^{:doc "Registry atom: map from modulator type keyword to compiler function.
   
   Compilers are optional - not all modulator types have them.
   Each compiler function takes [config point-count] and returns
   an optimized (fn [x y idx] -> value) for per-point evaluation."}
  !compilers
  (atom {}))


;; Valid Categories


(def valid-categories
  "Set of valid modulator categories.
   
   - :wave      - Wave-based oscillators (sine, triangle, sawtooth, square, random)
   - :one-shot  - One-shot envelopes (decay)
   - :special   - Per-point/position based (pos-x, pos-y, radial, point-index)
   - :external  - External input (midi, osc) - not exposed in UI
   - :internal  - Hz-based variants and aliases - not exposed in UI"
  #{:wave
    :one-shot
    :special
    :external
    :internal})


;; Registration


(defn register-modulator!
  "Register a modulator type in the global registry.
   
   Required keys:
   - :id        - Keyword identifier (e.g., :sine)
   - :name      - Display name string
   - :icon      - Icon string for UI (emoji/text), OR
   - :icon-name - FontAwesome icon keyword (e.g., :wave-sine), stored as data for UI rendering
   - :category  - One of valid-categories
   - :evaluator - Evaluation function (fn [config context] -> value)
   - :params    - Vector of parameter definition maps
   
   Optional keys:
   - :icon-style   - FontAwesome style keyword (:solid or :regular, default :solid)
   - :per-point?   - Boolean (default false)
   - :retrigger?   - Boolean (default false)
   - :description  - String description
   
   Returns the registered modulator map."
  [{:keys [id name icon icon-name icon-style category evaluator params
           per-point? retrigger? description]
    :or {per-point? false
         retrigger? false
         icon-style :solid}
    :as modulator}]
  ;; Validation assertions
  (assert (keyword? id)
          (str "Modulator :id must be a keyword, got: " (type id)))
  (assert (string? name)
          (str "Modulator :name must be a string, got: " (type name)))
  (assert (or (string? icon) (keyword? icon-name))
          (str "Modulator must have either :icon (string) or :icon-name (keyword), got icon=" (type icon) " icon-name=" (type icon-name)))
  (assert (contains? valid-categories category)
          (str "Modulator :category must be one of " valid-categories ", got: " category))
  (assert (fn? evaluator)
          (str "Modulator :evaluator must be a function, got: " (type evaluator)))
  (assert (vector? params)
          (str "Modulator :params must be a vector, got: " (type params)))
  
  ;; Store icon-name and icon-style as data, view layer handles rendering
  (let [full-modulator (cond-> (assoc modulator
                                      :per-point? per-point?
                                      :retrigger? retrigger?)
                         icon-style (assoc :icon-style icon-style))]
    (swap! !modulators assoc id full-modulator)
    full-modulator))


;; Accessors


(defn get-modulator
  "Get modulator info map by id, or nil if not found."
  [id]
  (get @!modulators id))

(defn get-evaluator
  "Get evaluator function for a modulator type.
   Hot path - direct lookup for performance.
   Returns nil if modulator not found."
  [id]
  (when-let [modulator (get @!modulators id)]
    (:evaluator modulator)))


(defn all-ui-modulators
  "Get vector of modulator maps (for dropdowns).
   Filters out :external and :internal categories and non-visible modulators."
  []
  (->> (vals @!modulators) 
       (sort-by (juxt :category :name))))

(defn per-point?
  "Check if a modulator type requires per-point context.
   Returns false if modulator not found."
  [id]
  (boolean (:per-point? (get @!modulators id))))

(defn retrigger?
  "Check if a modulator type supports retriggering.
   Returns false if modulator not found."
  [id]
  (boolean (:retrigger? (get @!modulators id))))

(defn get-params
  "Get vector of parameter definitions for a modulator type.
   Returns empty vector if modulator not found."
  [id]
  (or (:params (get @!modulators id)) []))

(defn valid-modulator-type?
  "Check if the given id is a registered modulator type."
  [id]
  (contains? @!modulators id))


;; Helper Functions (moved from modulator_defs.clj)


(defn modulated?
  "Check if a value has modulation (has :modulator key with :type).
   This is a pure data check - no dependency on the registry.
   
   A modulated value is a map containing :type key with a keyword value."
  [value]
  (and (map? value)
       (contains? value :type)
       (keyword? (:type value))))

(defn get-static-value
  "Get static value from a potentially modulated value.
   
   For modulated values:
   - First checks for :value field (set when slider moved while inactive)
   - Falls back to mid-point of min/max
   - Returns default if no value can be determined
   
   For static values, returns the value itself (or default if nil)."
  [value default]
  (if (modulated? value)
    (let [{:keys [min max value]} value]
      ;; Check for explicit :value first (set by update-static-value handler)
      (or value
          (when (and min max)
            (/ (+ (double min) (double max)) 2.0))
          default
          0.0))
    (or value default 0.0)))

(defn active-modulator?
  "Check if a value is an active modulator config.
   Returns true only if:
   - Value is a modulator config (has :type key)
   - :active? key is true (defaults to true for backward compatibility)"
  [value]
  (and (modulated? value)
       (get value :active? true)))


;; Compiler Registry Functions


(defn register-compiler!
  "Register a compiler function for a modulator type.
   Compilers are optional - if not registered, the interpreter path is used.
   
   Parameters:
   - mod-type: Keyword modulator type (e.g., :pos-x)
   - compiler-fn: Function (fn [config point-count] -> (fn [x y idx] -> value))
   
   Returns: The registered compiler function"
  [mod-type compiler-fn]
  {:pre [(keyword? mod-type) (fn? compiler-fn)]}
  (swap! !compilers assoc mod-type compiler-fn)
  compiler-fn)

(defn get-compiler
  "Get compiler function for a modulator type.
   Returns nil if no compiler registered (use interpreter fallback).
   
   Parameters:
   - mod-type: Keyword modulator type
   
   Returns: Compiler function or nil"
  [mod-type]
  (get @!compilers mod-type))
