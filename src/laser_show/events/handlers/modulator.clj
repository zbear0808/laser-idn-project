(ns laser-show.events.handlers.modulator
  "Event handlers for modulator parameter operations.
   
   Handles modulator toggle, type selection, and sub-parameter updates.
   These handlers transform modulator-specific events into the appropriate
   parameter values and delegate to :chain/update-param for state updates.
   
   Event Types:
   - :modulator/toggle - Toggle between static value and modulator
   - :modulator/set-type - Change modulator type
   - :modulator/update-param - Update a modulator sub-parameter
   
   All events require:
   - :domain - The chain domain (:effect-chains, :cue-chains, :projector-effects)
   - :entity-key - The entity key within the domain
   - :effect-path - Path to the effect within the chain
   - :param-key - The parameter being modulated"
  (:require
   [clojure.tools.logging :as log]
   [laser-show.animation.modulation :as mod]
   [laser-show.animation.modulator-defs :as mod-defs]))


;; Handler Functions


(defn- handle-toggle
  "Handle toggling between static value and modulator.
   
   When toggling ON (to modulated): creates a default sine modulator
   When toggling OFF (to static): extracts the midpoint value
   
   The :fx/event from the toggle-button contains the NEW selected state:
   - true = user wants modulation enabled
   - false = user wants static value
   
   Returns a :dispatch effect to update the parameter via :chain/update-param."
  [{:keys [domain entity-key effect-path param-key param-spec current-value] :as event}]
  (let [want-modulated? (:fx/event event)
        new-value (if want-modulated?
                    ;; User wants modulation -> create default modulator
                    (mod-defs/build-default-modulator :sine param-spec)
                    ;; User wants static -> extract midpoint value
                    (mod-defs/get-static-value current-value (:default param-spec)))]
    (log/debug "modulator/toggle - param-key:" param-key "want-modulated?:" want-modulated?)
    {:dispatch {:event/type :chain/update-param
                :domain domain
                :entity-key entity-key
                :effect-path effect-path
                :param-key param-key
                :value new-value}}))

(defn- handle-set-type
  "Handle changing the modulator type.
   
   Creates a new modulator config with the selected type, preserving
   min/max from the current modulator if they were customized.
   
   The :fx/event may be:
   - A modulator type keyword directly (e.g., :sine)
   - A modulator info map from combo-box (e.g., {:id :sine :name \"Sine\" :icon \"〰️\"})
   
   Returns a :dispatch effect to update the parameter via :chain/update-param."
  [{:keys [domain entity-key effect-path param-key param-spec current-value] :as event}]
  (let [fx-event (:fx/event event)
        mod-type (cond
                   ;; If it's a map with :id, extract the :id (combo-box item)
                   (and (map? fx-event) (:id fx-event)) (:id fx-event)
                   ;; If it's a keyword, use it directly
                   (keyword? fx-event) fx-event
                   ;; Fall back to :mod-type key if present
                   :else (:mod-type event))]
    (log/debug "modulator/set-type - param-key:" param-key "new type:" mod-type)
    (if-not mod-type
      (do
        (log/warn "modulator/set-type called without valid mod-type")
        {})
      (let [new-config (mod-defs/build-default-modulator mod-type param-spec)
            final-config (cond-> new-config
                           (and (mod/modulator-config? current-value)
                                (contains? current-value :min))
                           (assoc :min (:min current-value))
                           (and (mod/modulator-config? current-value)
                                (contains? current-value :max))
                           (assoc :max (:max current-value)))]
        {:dispatch {:event/type :chain/update-param
                    :domain domain
                    :entity-key entity-key
                    :effect-path effect-path
                    :param-key param-key
                    :value final-config}}))))

(defn- parse-text-value
  "Parse a text field value, clamping to min/max bounds."
  [text-value min-val max-val]
  (try
    (let [parsed (Double/parseDouble (str text-value))]
      (max (double min-val) (min (double max-val) parsed)))
    (catch Exception _
      nil)))

(defn- handle-update-mod-param
  "Handle updating a sub-parameter of a modulator.
   
   Updates a specific key within the modulator config (e.g., :period, :phase).
   
   Supports both slider values (:fx/event) and text field values.
   For text fields, looks for :mod-text-field? true and parses the text.
   
   Returns a :dispatch effect to update the parameter via :chain/update-param."
  [{:keys [domain entity-key effect-path param-key current-value mod-param-key] :as event}]
  (let [;; Handle text field input - parse the text from the event source
        text-field? (or (:mod-text-field? event) (:text-field? event))
        raw-val (or (:value event) (:fx/event event))
        ;; For text field, extract text from the event (TextField text or source)
        new-val (if text-field?
                  (let [text-val (cond
                                   (string? raw-val) raw-val
                                   (instance? javafx.scene.control.TextField raw-val) (.getText ^javafx.scene.control.TextField raw-val)
                                   (instance? javafx.event.ActionEvent raw-val)
                                   (let [source (.getSource ^javafx.event.ActionEvent raw-val)]
                                     (when (instance? javafx.scene.control.TextField source)
                                       (.getText ^javafx.scene.control.TextField source)))
                                   :else (str raw-val))
                        min-val (or (:mod-param-min event) -10.0)
                        max-val (or (:mod-param-max event) 10.0)]
                    (parse-text-value text-val min-val max-val))
                  raw-val)]
    (log/debug "modulator/update-param - param-key:" param-key "mod-param-key:" mod-param-key "new-val:" new-val "text-field?:" text-field?)
    (when new-val
      (if (mod/modulator-config? current-value)
        (let [updated-config (assoc current-value mod-param-key new-val)]
          {:dispatch {:event/type :chain/update-param
                      :domain domain
                      :entity-key entity-key
                      :effect-path effect-path
                      :param-key param-key
                      :value updated-config}})
        ;; If not a modulator config, something is wrong - log and no-op
        (do
          (log/warn "modulator/update-param called on non-modulator value:" current-value)
          {})))))

(defn- handle-retrigger
  "Handle retrigger event for a modulator.
   
   Resets the playback timing accumulators to simulate a fresh trigger.
   This causes beat-synced modulators to restart their cycle.
   
   Returns a :dispatch effect to reset timing using the existing transport/retrigger event."
  [{:keys [param-key] :as _event}]
  (log/debug "modulator/retrigger - param-key:" param-key)
  ;; Reset the playback accumulators using existing transport/retrigger handler
  {:dispatch {:event/type :transport/retrigger}})


;; Main Dispatcher


(defn handle
  "Main dispatcher for modulator events.
   
   Routes to appropriate handler based on :event/type.
   All handlers return effect maps (typically :dispatch to chain/update-param)."
  [{:keys [event/type] :as event}]
  (log/debug "modulator/handle - type:" type)
  (case type
    :modulator/toggle (handle-toggle event)
    :modulator/set-type (handle-set-type event)
    :modulator/update-param (handle-update-mod-param event)
    :modulator/retrigger (handle-retrigger event)
    ;; Unknown modulator event
    (do
      (log/warn "Unknown modulator event type:" type)
      {})))
