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


(defn- get-keyframe-enabled?
  "Check if keyframe modulator is enabled for the given effect.
   Returns true if the effect has an active keyframe modulator."
  [state domain entity-key effect-path]
  (let [;; Build path to effect based on domain
        base-path (case domain
                    :effect-chains [:grid :effect-chains entity-key]
                    :cue-chains [:grid :cue-chains entity-key :items]
                    :projector-effects [:projectors entity-key :effects]
                    ;; Fallback - may not work for all domains
                    [:grid domain entity-key])
        effect-full-path (into base-path (vec effect-path))
        effect (get-in state effect-full-path)]
    (get-in effect [:keyframe-modulator :enabled?] false)))

(defn- handle-toggle
  "Handle toggling between static value and modulator.
   
   When toggling ON (to modulated):
   - If keyframes are enabled, first disables them (mutual exclusivity)
   - If modulator config exists with :active? false, set :active? true
   - Otherwise create a default sine modulator with :active? true
   
   When toggling OFF (to static):
   - Set :active? false on existing modulator config (preserves settings)
   - Set :value to midpoint of min/max (so slider starts at sensible position)
   
   The :fx/event from the toggle-button contains the NEW selected state:
   - true = user wants modulation enabled
   - false = user wants static value
   
   Returns effects to update the parameter (and optionally disable keyframes)."
  [{:keys [domain entity-key effect-path param-key param-spec current-value state] :as event}]
  (let [want-modulated? (:fx/event event)
        keyframe-enabled? (and state (get-keyframe-enabled? state domain entity-key effect-path))
        new-value (if want-modulated?
                    ;; User wants modulation enabled
                    (if (mod/modulator-config? current-value)
                      ;; Existing modulator - just activate it
                      (assoc current-value :active? true)
                      ;; No modulator exists - create default
                      (mod-defs/build-default-modulator :sine param-spec))
                    ;; User wants static - deactivate modulator (preserves settings)
                    (if (mod/modulator-config? current-value)
                      ;; Deactivate and set initial :value for slider
                      (let [mod-min (get current-value :min 0.0)
                            mod-max (get current-value :max 1.0)
                            ;; Use existing :value if present, otherwise compute midpoint
                            initial-value (or (:value current-value)
                                             (/ (+ (double mod-min) (double mod-max)) 2.0))]
                        (assoc current-value
                               :active? false
                               :value initial-value))
                      ;; Already static - no change needed
                      current-value))]
    (log/debug "modulator/toggle - param-key:" param-key
               "want-modulated?:" want-modulated?
               "keyframe-enabled?:" keyframe-enabled?
               "has-existing-config?:" (mod/modulator-config? current-value))
    ;; If enabling modulation and keyframes are active, disable keyframes first
    ;; (keyframes and per-param modulators are mutually exclusive)
    (if (and want-modulated? keyframe-enabled?)
      ;; Dispatch keyframe disable, then update param
      {:dispatch {:event/type :keyframe/toggle-enabled
                  :domain domain
                  :entity-key entity-key
                  :effect-path effect-path
                  :enabled? false}
       :dispatch-later {:event {:event/type :chain/update-param
                                :domain domain
                                :entity-key entity-key
                                :effect-path effect-path
                                :param-key param-key
                                :value new-value}
                        :delay-ms 10}}
      ;; Just update the param
      {:dispatch {:event/type :chain/update-param
                  :domain domain
                  :entity-key entity-key
                  :effect-path effect-path
                  :param-key param-key
                  :value new-value}})))

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

(defn- handle-update-static-value
  "Handle updating the static value while preserving an inactive modulator config.
   
   When modulation is toggled off, we keep the modulator config with :active? false.
   When the user adjusts the static slider/text field, we need to update the :value
   field in the config (which is used as the static output) without losing the settings.
   
   If the current value is:
   - A modulator config: update its :value field
   - A plain number: just use the new value
   
   Returns a :dispatch effect to update the parameter via :chain/update-param."
  [{:keys [domain entity-key effect-path param-key current-value] :as event}]
  (let [new-static-value (:fx/event event)
        new-value (if (mod/modulator-config? current-value)
                    ;; Preserve the modulator config, just update :value
                    (assoc current-value :value new-static-value)
                    ;; Plain number - just use new value
                    new-static-value)]
    (log/debug "modulator/update-static-value - param-key:" param-key
               "new-value:" new-static-value
               "has-config?:" (mod/modulator-config? current-value))
    {:dispatch {:event/type :chain/update-param
                :domain domain
                :entity-key entity-key
                :effect-path effect-path
                :param-key param-key
                :value new-value}}))


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
    :modulator/update-static-value (handle-update-static-value event)
    :modulator/retrigger (handle-retrigger event)
    ;; Unknown modulator event
    (do
      (log/warn "Unknown modulator event type:" type)
      {})))
