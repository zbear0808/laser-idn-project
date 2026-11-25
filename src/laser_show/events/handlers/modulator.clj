(ns laser-show.events.handlers.modulator
  "Event handlers for modulator parameter operations.
   
   Handles modulator toggle, type selection, and sub-parameter updates.
   The toggle updates the parameter and its effect's keyframe mode together.
   
   Event Types:
   - :modulator/toggle - Toggle between static value and modulator
   - :modulator/set-type - Change an active modulator's type
   - :modulator/update-param - Update one field of an active modulator
   - :modulator/update-static-value - Set the static value of a modulator
     that is off (a number, or a modulatable choice's choice)
   
   All events have:
   - :domain - The chain domain (:effect-chains, :cue-chains, :projector-effects,
     the timeline's :timeline-items and :timeline-tracks, :cue-zone-patterns)
   - :entity-key - The entity key within the domain
   - :param-key - The parameter being modulated
   
   The toggle and the three field edits (set-type, update-param,
   update-static-value) name their effect by its instance, not by its
   place: :effect-instance-id, and :owner-id for an effect a cue item, a
   clip, a track or a folder owns (chain-helpers/resolve-effect-instance-path).
   Each reads the parameter as it is stored now where that effect is now, and
   the field edits change their own field only, so two edits from one render
   both stay (reactivity plan 07). An effect that is gone, or no change, does
   nothing ({}); nor, for a field edit, do keyframes on, a stored value of
   another kind or mode, or invalid input."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [laser-show.animation.modulation :as mod]
   [laser-show.animation.modulator-defs :as mod-defs]
   [laser-show.animation.modulator-registry :as reg]
   [laser-show.events.helpers :as h]
   [laser-show.events.handlers.chain.helpers :as chain-helpers]
   [laser-show.events.handlers.chain.params :as params]
   [laser-show.events.handlers.keyframe :as keyframe]))


;; Handler Functions


(defn- handle-toggle
  "Turn modulation of the event's parameter on or off (its :fx/event), for
   the effect it names by its instance, where that effect is now
   (chain-helpers/resolve-effect-instance-path). Turning it on disables
   the effect's active keyframes too, in one edit. Use the effect's stored
   settings; the editor may be showing a keyframe's value. Once the effect
   is gone (another may hold its place now), or when nothing would change,
   it does nothing ({})."
  [{:keys [state domain entity-key param-key param-spec] enabled? :fx/event :as event}]
  (if-let [effect-path (chain-helpers/resolve-effect-instance-path state event)]
    (let [config (chain-helpers/chain-config domain entity-key)
          effect (get-in state (into (:items-path config) effect-path))
          value (get-in effect [:params param-key])
          configured? (mod/modulator-config? value)
          new-value (cond
                      (and enabled? configured?) (assoc value :active? true)
                      enabled? (mod-defs/build-default-modulator :sine param-spec)
                      configured? (assoc value :active? false
                                         :value (or (:value value)
                                                    (/ (+ (double (:min value 0.0)) (double (:max value 1.0))) 2.0)))
                      :else value)
          keyframes-off? (boolean (and enabled? (get-in effect [:keyframe-modulator :enabled?])))
          param-changed? (not= value new-value)]
      (if (or keyframes-off? param-changed?)
        {:state (cond-> state
                  keyframes-off? (keyframe/handle-toggle-enabled config effect-path false)
                  param-changed? (params/handle-update-param config {:effect-path effect-path :param-key param-key :value new-value}))}
        {}))
    {}))


;; A modulator's field edits (reactivity plan 07)


(defn- active-config?
  "Is v a modulator config that is on? (One saved before :active? existed
   is.)"
  [v]
  (and (mod/modulator-config? v) (reg/active-modulator? v)))

(defn- inactive-config?
  "Is v a modulator config that is off?"
  [v]
  (and (mod/modulator-config? v) (not (reg/active-modulator? v))))

(defn- field-target
  "The effect a field edit names by its instance
   (chain-helpers/resolve-effect-instance-path), where it is now, and the
   base value stored at the event's :param-key: {:config :effect-path
   :value}, or nil when the effect or that parameter is gone, or the
   effect's keyframes are on (they set its parameters then)."
  [{:keys [state domain entity-key param-key] :as event}]
  (when-let [effect-path (chain-helpers/resolve-effect-instance-path state event)]
    (let [config (chain-helpers/chain-config domain entity-key)
          effect (get-in state (into (:items-path config) effect-path))
          params (:params effect)]
      (when (and (map? params)
                 (contains? params param-key)
                 (not (get-in effect [:keyframe-modulator :enabled?])))
        {:config config :effect-path effect-path :value (get params param-key)}))))

(defn- same?
  "Is new what old already is: equal, or the same number (a slider gives
   4.0 for the 4 a default holds)?"
  [old new]
  (if (and (number? old) (number? new)) (== old new) (= old new)))

(defn- write-param
  "{:state} with value as the target's parameter: params/handle-update-param
   given only the target's place, the parameter and the value."
  [state {:keys [config effect-path]} param-key value]
  {:state (params/handle-update-param state config {:effect-path effect-path :param-key param-key :value value})})

(defn- selectable-type?
  "Does an editor of domain offer mod-type in its type dropdown: a
   registered type, not an internal one, and for calibration
   (:projector-effects), which never sees a press, not one that follows a
   press (views.components.modulator-param-control/modulator-choices)?"
  [domain mod-type]
  (let [category (:category (reg/get-modulator mod-type))]
    (and (some? category)
         (not= :internal category)
         (not (and (= :projector-effects domain) (= :pulse category))))))

(defn- handle-set-type
  "Change the type of the active modulator stored at the event's parameter:
   the new type's defaults within the parameter's bounds (:param-spec,
   mod-defs/build-default-modulator), with the stored Min and Max when it
   has them. The type is :mod-type, the id the dropdown sends (or a type
   keyword, or an item's :id, as :fx/event). The type it has already
   changes nothing, so its fields stay; nor does a type its editor doesn't
   offer, or a modulator that is off.
   
   Example: stored {:type :triangle :min 0.2 :max 0.9 :period 7.0}, :mod-type :square
   => {:type :square :active? true :min 0.2 :max 0.9 :period 1.0 :duty-cycle 0.5 ...}"
  [{:keys [state domain param-key param-spec] :as event}]
  (let [fx-event (:fx/event event)
        mod-type (or (:mod-type event)
                     (when (keyword? fx-event) fx-event)
                     (when (map? fx-event) (:id fx-event)))
        {stored :value :as target} (field-target event)]
    (log/trace "modulator/set-type - param-key:" param-key "new type:" mod-type)
    (if (and (active-config? stored)
             (selectable-type? domain mod-type)
             (not= mod-type (:type stored)))
      (let [fields (set (map :key (reg/get-params mod-type)))
            carry (fn [config k]
                    (cond-> config
                      (and (contains? stored k) (contains? fields k)) (assoc k (get stored k))))]
        (write-param state target param-key
                     (reduce carry (mod-defs/build-default-modulator mod-type param-spec) [:min :max])))
      {})))

(defn- parse-text-value
  "Parse a text field value, clamping to min/max bounds, a whole number for
   an :int field (int?): nil unless it is a finite number
   (h/parse-number-text)."
  [text-value min-val max-val int?]
  (h/parse-number-text (str text-value) min-val max-val int?))

(defn- parse-number-list
  "The numbers in text, split on commas and spaces, as doubles; what doesn't
   parse is left out, so \"\" is []."
  [text]
  (into []
        (keep #(try (Double/parseDouble %) (catch NumberFormatException _ nil)))
        (remove str/blank? (str/split (str text) #"[\s,]+"))))

(defn- text-or-edn
  "A text field's text, or the EDN it holds when it starts with [, { or :
   (the text itself when that doesn't read)."
  [text]
  (try
    (if (or (str/starts-with? text "[")
            (str/starts-with? text "{")
            (str/starts-with? text ":"))
      (edn/read-string text)
      text)
    (catch Exception _ text)))

(defn- field-def
  "The definition of key among mod-type's fields (the registry's), or nil."
  [mod-type key]
  (some #(when (= key (:key %)) %) (reg/get-params mod-type)))

(defn- field-value
  "The value event sets a field to, given the field's definition (the
   registry's), or nil for none. Typed :text (:mod-text-field? or
   :text-field?; the field reads it on the FX thread) is read as the
   field's type says: a :text field's text, or the EDN in it; a
   :number-list's numbers (the Step's palette, \"\" none); otherwise a
   number, clamped to :mod-param-min and :mod-param-max (parse-text-value).
   A control's own value (:value, else :fx/event) is taken when it fits the
   field: a finite number for a number, one of its :choices for a choice,
   true or false for a checkbox. An :int field's number is a whole one,
   rounded (a slider's 5.6 is 6, as typed \"5.6\" is). Never anything else,
   such as the ActionEvent a text field's Enter delivers."
  [{:keys [type choices]} event]
  (if (or (:mod-text-field? event) (:text-field? event))
    (let [text (:text event)]
      (when (string? text)
        (case type
          :text (text-or-edn text)
          :number-list (parse-number-list text)
          (:float :int nil) (parse-text-value text (or (:mod-param-min event) -10.0) (or (:mod-param-max event) 10.0)
                                              (= :int type))
          nil)))
    (let [v (h/first-some (:value event) (:fx/event event))]
      (case type
        (:float nil) (when (h/finite-number? v) v)
        :int (when (h/finite-number? v) (Math/round (double v)))
        :choice (when (some #(= v %) choices) v)
        :boolean (when (boolean? v) v)
        nil))))

(defn- handle-update-mod-param
  "Change one field (:mod-param-key) of the active modulator stored at the
   event's parameter, keeping every other: the value field-value reads, for
   a field its type defines (the registry's: a field a saved config leaves
   out can be set, one only another type has can't). A modulator that is
   off, a number, a value that doesn't fit the field, or the value it has
   already changes nothing."
  [{:keys [state param-key mod-param-key] :as event}]
  (let [{stored :value :as target} (field-target event)
        field (when (active-config? stored) (field-def (:type stored) mod-param-key))
        new-val (when field (field-value field event))]
    (log/trace "modulator/update-param - param-key:" param-key "mod-param-key:" mod-param-key "new-val:" new-val)
    (if (and (some? new-val)
             (not (and (contains? stored mod-param-key) (same? (get stored mod-param-key) new-val))))
      (write-param state target param-key (assoc stored mod-param-key new-val))
      {})))

(defn- handle-retrigger
  "Handle retrigger event for a modulator.
   
   Resets the playback timing accumulators to simulate a fresh trigger.
   This causes beat-synced modulators to restart their cycle.
   
   Returns a :dispatch effect to reset timing using the existing transport/retrigger event."
  [{:keys [param-key] :as _event}]
  (log/trace "modulator/retrigger - param-key:" param-key)
  ;; Reset the playback accumulators using existing transport/retrigger handler
  {:dispatch {:event/type :transport/retrigger}})

(defn- static-value
  "The static value event sets, or nil for none. A field's typed :text is a
   number (h/parse-number-text, with :min, :max and :is-int?). Otherwise
   :value or :fx/event when it is a finite number (the slider's; a whole
   one for an :int parameter, params/param-number), or a keyword the
   parameter's definition lists in its :choices (a modulatable choice's
   combo box; the definition is the target effect's, params/param-spec);
   never anything else, such as the ActionEvent a text field's Enter
   delivers."
  [state {:keys [config effect-path]} {:keys [param-key] :as event}]
  (if (contains? event :text)
    (h/parse-number-text (:text event) (:min event) (:max event) (:is-int? event))
    (let [value (h/first-some (:value event) (:fx/event event))]
      (cond
        (h/finite-number? value) (params/param-number (params/param-spec state config effect-path param-key) value)
        (keyword? value) (some #{value} (:choices (params/param-spec state config effect-path param-key)))))))

(defn- handle-update-static-value
  "Set the static value of the modulator that is off at the event's
   parameter (static-value's), keeping its type, its range and every other
   setting. Once that parameter is an active modulator, a number or gone,
   the edit is from a control that no longer shows: nothing changes; nor
   does the value it has already."
  [{:keys [state param-key] :as event}]
  (let [{stored :value :as target} (field-target event)
        new-static (when (inactive-config? stored) (static-value state target event))]
    (log/trace "modulator/update-static-value - param-key:" param-key "new-value:" new-static)
    (if (and (some? new-static) (not (same? (:value stored) new-static)))
      (write-param state target param-key (assoc stored :value new-static))
      {})))


;; Main Dispatcher


(defn handle
  "Main dispatcher for modulator events.
   
   Routes to appropriate handler based on :event/type. The toggle and the
   field edits return their :state, or {} when nothing changes; the
   retrigger a :dispatch."
  [{:keys [event/type] :as event}]
  (log/trace "modulator/handle - type:" type)
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
