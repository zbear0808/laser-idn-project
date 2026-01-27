(ns laser-show.events.handlers.chain.params
  "Parameter-related handlers for chain operations.
   
   Contains:
   - Effect parameter operations (curve, spatial, scale, rotation, zones)
   - Parameter update handlers (update-param, update-param-from-text, update-color-param)
   - UI mode operations (set-ui-mode)"
  (:require
   [clojure.tools.logging :as log]
   [laser-show.events.helpers :as h]
   [laser-show.events.handlers.effect-params :as effect-params]))


;; ============================================================================
;; Effect Parameter Operations (Delegate to effect-params)
;; ============================================================================


(defn handle-add-curve-point
  "Thin wrapper that delegates to effect-params.
   Extracts params-path from config and effect-path, then calls effect-params."
  [state config {:keys [effect-path channel x y]}]
  (let [items-path (:items-path config)
        params-path (vec (concat items-path effect-path [:params]))]
    (effect-params/add-curve-point state params-path channel x y)))

(defn handle-update-curve-point
  "Thin wrapper that delegates to effect-params.
   Extracts params-path from config and effect-path, then calls effect-params."
  [state config {:keys [effect-path channel point-idx x y]}]
  (let [items-path (:items-path config)
        params-path (vec (concat items-path effect-path [:params]))]
    (effect-params/update-curve-point state params-path channel point-idx x y)))

(defn handle-remove-curve-point
  "Thin wrapper that delegates to effect-params.
   Extracts params-path from config and effect-path, then calls effect-params."
  [state config {:keys [effect-path channel point-idx]}]
  (let [items-path (:items-path config)
        params-path (vec (concat items-path effect-path [:params]))]
    (effect-params/remove-curve-point state params-path channel point-idx)))

(defn handle-set-active-curve-channel
  "Thin wrapper that delegates to effect-params.
   Extracts UI path from config and effect-path, then calls effect-params."
  [state config {:keys [effect-path tab-id]}]
  (let [ui-path (conj (:ui-path config) :ui-modes effect-path)]
    (effect-params/set-active-curve-channel state ui-path tab-id)))

(defn handle-update-spatial-params
  "Thin wrapper that delegates to effect-params.
   Extracts params-path from config and effect-path, then calls effect-params."
  [state config {:keys [effect-path point-id x y param-map]}]
  (let [items-path (:items-path config)
        params-path (vec (concat items-path effect-path [:params]))]
    (effect-params/update-spatial-params state params-path point-id x y param-map)))

(defn handle-update-scale-params
  "Thin wrapper that delegates to effect-params.
   Updates x-scale and y-scale parameters from scale drag operation."
  [state config {:keys [effect-path x-scale y-scale]}]
  (let [items-path (:items-path config)
        params-path (vec (concat items-path effect-path [:params]))]
    (effect-params/update-scale-params state params-path x-scale y-scale)))

(defn handle-update-rotation-param
  "Thin wrapper that delegates to effect-params.
   Updates angle parameter from rotation drag operation."
  [state config {:keys [effect-path angle]}]
  (let [items-path (:items-path config)
        params-path (vec (concat items-path effect-path [:params]))]
    (effect-params/update-rotation-param state params-path angle)))

(defn handle-reset-params
  "Thin wrapper that delegates to effect-params.
   Resets effect parameters to their default values."
  [state config {:keys [effect-path defaults-map]}]
  (let [items-path (:items-path config)
        params-path (vec (concat items-path effect-path [:params]))]
    (effect-params/reset-params state params-path defaults-map)))

(defn handle-toggle-zone-group
  "Thin wrapper that delegates to effect-params.
   Toggles a zone group in the target-zone-groups set."
  [state config {:keys [effect-path group-id]}]
  (let [items-path (:items-path config)
        params-path (vec (concat items-path effect-path [:params]))]
    (effect-params/toggle-zone-group state params-path group-id)))


;; ============================================================================
;; Parameter Update Handlers
;; ============================================================================


(defn handle-update-param
  "Update an effect parameter value.
   Extracts value from :value or :fx/event.
   
   Parameters:
   - state: Application state
   - config: Configuration map with :items-path
   - event: Map with :effect-path, :param-key, and :value or :fx/event
   
   Returns: Updated state"
  [state config {:keys [effect-path param-key] :as event}]
  (let [value (or (:fx/event event) (:value event))
        items-path (:items-path config)
        items-vec (vec (get-in state items-path []))
        updated-items (assoc-in items-vec (conj (vec effect-path) :params param-key) value)]
    (assoc-in state items-path updated-items)))

(defn handle-update-param-from-text
  "Update a parameter from text field input.
   Uses h/parse-and-clamp-from-text-event for parsing.
   
   Parameters:
   - state: Application state
   - config: Configuration map with :items-path
   - event: Map with :effect-path, :param-key, :min, :max, and :fx/event
   
   Returns: Updated state (unchanged if parsing fails)"
  [state config {:keys [effect-path param-key min max] :as event}]
  (if-let [clamped (h/parse-and-clamp-from-text-event (:fx/event event) min max)]
    (let [items-path (:items-path config)
          items-vec (vec (get-in state items-path []))
          updated-items (assoc-in items-vec (conj (vec effect-path) :params param-key) clamped)]
      (assoc-in state items-path updated-items))
    state))

(defn handle-update-color-param
  "Update color parameters from ColorPicker's ActionEvent.
   Extracts the color from the ColorPicker source and converts to normalized RGB.
   
   Updates :red, :green, :blue as separate parameters for modulator support.
   
   NOTE: Uses NORMALIZED values (0.0-1.0), not 8-bit (0-255).
   All color effects in laser-show use normalized color values internally.
   
   Parameters:
   - state: Application state
   - config: Configuration map with :items-path
   - event: Map with :effect-path and :fx/event (ActionEvent)
   
   Returns: Updated state"
  [state config {:keys [effect-path] :as event}]
  (let [action-event (:fx/event event)
        color-picker (.getSource action-event)
        color (.getValue color-picker)
        ;; Use normalized values (0.0-1.0) for color effects
        red (.getRed color)
        green (.getGreen color)
        blue (.getBlue color)
        items-path (:items-path config)
        items-vec (vec (get-in state items-path []))
        params-path (conj (vec effect-path) :params)
        updated-items (-> items-vec
                          (assoc-in (conj params-path :red) red)
                          (assoc-in (conj params-path :green) green)
                          (assoc-in (conj params-path :blue) blue))]
    (log/debug "handle-update-color-param: effect-path=" effect-path
               "rgb=" [red green blue])
    (assoc-in state items-path updated-items)))


;; ============================================================================
;; UI Mode Operations
;; ============================================================================


(defn handle-set-ui-mode
  "Set visual/numeric mode for effect parameter UI.
   
   Parameters:
   - state: Application state
   - config: Configuration map with :ui-path
   - effect-path: Path to the effect
   - mode: :visual or :numeric
   
   Returns: Updated state"
  [state config effect-path mode]
  (assoc-in state (conj (:ui-path config) :ui-modes effect-path) mode))
