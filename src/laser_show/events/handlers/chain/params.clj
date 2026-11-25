(ns laser-show.events.handlers.chain.params
  "Parameter-related handlers for chain operations.
   
   Contains:
   - Effect parameter operations (curve, spatial, scale, rotation, zones)
   - Parameter update handlers (update-param, update-param-from-text, update-color-param)
   - UI mode operations (set-ui-mode)"
  (:require
   [clojure.tools.logging :as log]
   [laser-show.animation.effects :as effects]
   [laser-show.animation.presets :as presets]
   [laser-show.events.helpers :as h]
   [laser-show.events.handlers.chain.helpers :as chain-helpers]
   [laser-show.events.handlers.effect-params :as effect-params]))

;; Effect Parameter Operations (Delegate to effect-params)

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

(defn handle-reset-params
  "Thin wrapper that delegates to effect-params.
   Resets effect parameters to their default values."
  [state config {:keys [effect-path defaults-map]}]
  (let [items-path (:items-path config)
        params-path (vec (concat items-path effect-path [:params]))]
    (effect-params/reset-params state params-path defaults-map)))

;; Parameter Update Handlers

(defn set-clamp-after [state {:keys [items-path]} {:keys [effect-path effect-instance-id value]}]
  (let [path (into items-path effect-path)
        effect (get-in state path)]
    (if (and (boolean? value) (= effect-instance-id (:id effect))
             (:transforms-xy? (effects/get-effect (:effect-id effect))))
      (assoc-in state (conj path :clamp-after?) value)
      state)))

(declare param-spec param-number)

(defn handle-update-param
  "Update an effect parameter value.
   Extracts value from :value or :fx/event; a number for an :int parameter
   is stored as a whole one (param-number, by its definition: param-spec),
   so a slider's mid-drag 25.6 is 26.
   
   Parameters:
   - state: Application state
   - config: Configuration map with :items-path
   - event: Map with :effect-path, :param-key, and :value or :fx/event
   
   Returns: Updated state"
  [state config {:keys [effect-path param-key] :as event}]
  (let [value (param-number (param-spec state config effect-path param-key)
                            (h/first-some (:fx/event event) (:value event)))
        items-path (:items-path config)
        items-vec (vec (get-in state items-path []))
        updated-items (assoc-in items-vec (conj (vec effect-path) :params param-key) value)]
    (assoc-in state items-path updated-items)))

(defn handle-update-param-from-text
  "Update a parameter from its text field: the typed :text as a number
   (h/parse-number-text, with :min, :max and :is-int?).
   
   Parameters:
   - state: Application state
   - config: Configuration map with :items-path
   - event: Map with :effect-path, :param-key, :min, :max, :is-int? and
     :text (the field reads it on the FX thread)
   
   Returns: Updated state (unchanged if the text isn't a number)"
  [state config {:keys [effect-path param-key min max is-int? text]}]
  (if-some [number (h/parse-number-text text min max is-int?)]
    (let [items-path (:items-path config)
          items-vec (vec (get-in state items-path []))
          updated-items (assoc-in items-vec (conj (vec effect-path) :params param-key) number)]
      (assoc-in state items-path updated-items))
    state))

(defn- item-parameters
  "The parameter definitions of item: an effect's, the effect registry's for
   its :effect-id; a cue item's or a clip's, its preset's (:preset-id);
   nil for anything else."
  [item]
  (cond
    (:effect-id item) (:parameters (effects/get-effect (:effect-id item)))
    (:preset-id item) (:parameters (presets/presets-by-id (:preset-id item)))))

(defn param-spec
  "The definition of param-key for the item at effect-path (a path under
   config's :items-path): an effect's from the effect registry, a cue
   item's or a clip's from its preset (item-parameters); nil when there is
   no such item, definition or parameter."
  [state config effect-path param-key]
  (let [item (get-in state (into (:items-path config) effect-path))]
    (some #(when (= param-key (:key %)) %) (item-parameters item))))

(defn param-number
  "value as a parameter of spec holds it: for an :int parameter a finite
   number rounded to a whole number, a long, as h/parse-number-text reads
   a typed one; any other value, or another parameter's, as it is.
   
   Example: (param-number {:key :kpps :type :int} 25.6) => 26"
  [spec value]
  (if (and (= :int (:type spec)) (h/finite-number? value))
    (Math/round (double value))
    value))

(defn numeric-spec?
  "Does a parameter of spec hold a number: :float or :int, or no :type (a
   slider, as the editors show it)?"
  [spec]
  (contains? #{:float :int} (:type spec :float)))

(defn color-value
  "The colour a colour edit sets: its :color, [red green blue] as the
   colour picker reads it on the FX thread (parameter-controls/color-picker),
   each a finite number, clamped to 0.0-1.0; nil for anything else, such
   as the picker's ActionEvent, which is never read here.
   
   Example: (color-value {:color [1.0 0.5 0.0]}) => [1.0 0.5 0.0]"
  [event]
  (let [c (:color event)]
    (when (and (vector? c) (= 3 (count c)) (every? h/finite-number? c))
      (mapv #(max 0.0 (min 1.0 (double %))) c))))

(defn- color-target
  "Where a colour edit goes, under config's :items-path: the effect it names
   by its instance (and owner), where that effect is now
   (chain-helpers/resolve-effect-instance-path), or nil once it is gone; an
   edit that names no instance (the preset editor's: a cue item's own
   colour, whose parameters all go by their place) goes to its
   :effect-path."
  [state event]
  (if (contains? event :effect-instance-id)
    (chain-helpers/resolve-effect-instance-path state event)
    (:effect-path event)))

(defn handle-update-color-param
  "Update the color parameters of the effect the event names (color-target)
   to the event's :color (color-value): :red, :green and :blue, separate
   parameters for modulator support.
   
   NOTE: Uses NORMALIZED values (0.0-1.0), not 8-bit (0-255).
   All color effects in laser-show use normalized color values internally.
   
   Parameters:
   - state: Application state
   - config: Configuration map with :items-path
   - event: Map with :domain, :entity-key, :effect-instance-id (and
     :owner-id for an owned effect), or for a cue item's own colour
     :effect-path, and :color [red green blue]
   
   Returns: Updated state (unchanged without a colour, or without an effect
   there)"
  [state config event]
  (let [items-path (:items-path config)
        items-vec (vec (get-in state items-path []))
        effect-path (vec (color-target state event))
        params-path (conj effect-path :params)]
    (if-let [[red green blue] (when (and (seq effect-path) (map? (get-in items-vec effect-path)))
                                (color-value event))]
      (do (log/debug "handle-update-color-param: effect-path=" effect-path "rgb=" [red green blue])
          (assoc-in state items-path (-> items-vec
                                         (assoc-in (conj params-path :red) red)
                                         (assoc-in (conj params-path :green) green)
                                         (assoc-in (conj params-path :blue) blue))))
      state)))


;; UI Mode Operations


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
