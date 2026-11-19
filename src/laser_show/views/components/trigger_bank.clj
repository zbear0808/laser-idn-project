(ns laser-show.views.components.trigger-bank
  "Trigger Configuration Bank
   
   Provides UI for assigning MIDI, OSC, or Keyboard inputs to trigger
   the current cue or effect cell."
  (:require [laser-show.common.util :as u])
  (:import [javafx.util StringConverter]))

(def ^:private option-name
  "Shows a behaviour option by its :name, in the list and on the button. A
   converter rather than cell factories: the button shows the converter's
   text while its value isn't among the items, as when one render changes
   both (opening the editor on an effect cell after a cue cell)."
  (proxy [StringConverter] []
    (toString [option] (str (:name option)))
    (fromString [_] nil)))

(defn- behavior-options
  "The behaviours a binding can have. The trigger path acts only on
   :momentary; any other behaviour plays the cue the way its own trigger mode
   says, or toggles the effect cell (events/handlers/triggers.clj)."
  [cell-mode]
  (if (= cell-mode :effects)
    [{:id :follow
      :name "Toggle on each press"
      :description "A press turns the effect on or off."}
     {:id :momentary
      :name "While held (momentary)"
      :description "The effect is on while the input is held down."}]
    [{:id :follow
      :name "Follow the cue's mode (toggle or retrigger)"
      :description "A press does what the cue's trigger mode says."}
     {:id :momentary
      :name "While held (momentary)"
      :description "The cue plays while the input is held down."}]))

(defn binding-label
  "A trigger-map key as the user knows the control: \"MIDI Ch 1 Note 36\",
   \"OSC /x\", \"Key :space\", or the key itself."
  [trigger-key]
  (case (:source trigger-key)
    :midi (str "MIDI Ch " (inc (or (:channel trigger-key) 0))
               " Note " (:note trigger-key))
    :osc (str "OSC " (:id trigger-key))
    :keyboard (str "Key " (:id trigger-key))
    (str trigger-key)))

(defn trigger-bank
  "Configuration UI for resolving generalized triggers.
   
   Props:
   - :col, :row - Cell coordinates
   - :cell-mode - :cue or :effects (default: :cue)
   - :trigger-map - Map of input-key -> target-action
   - :learning-target - Current learning target action (or nil)
   - :chosen-behavior - The behaviour picked for the next learned binding
     (or nil), shown while the cell has no bindings"
  [{:keys [col row cell-mode trigger-map learning-target chosen-behavior]
    :or {cell-mode :cue}}]
  (let [target-type (if (= cell-mode :effects) :trigger-effect :trigger-cue)
        target-label (if (= cell-mode :effects) "effect" "cue")
        current-target {:type target-type :target [col row]}
        learning? (= learning-target current-target)
        ;; Find triggers that map to this specific cell eagerly
        cell-triggers (into []
                            (keep (fn [[k v]]
                                    (when (and (= (:type v) target-type)
                                               (= (:target v) [col row]))
                                      k)))
                            trigger-map)
        current-action (some (fn [[_ action]]
                               (when (and (= (:type action) target-type)
                                            (= (:target action) [col row]))
                                 action))
                             trigger-map)
        ;; A :retrigger, :toggle, :quantized-decay or :one-shot binding from
        ;; an older project follows the cue's mode, so it shows as that
        behavior (if (= :momentary (if current-action (:behavior current-action) chosen-behavior))
                   :momentary
                   :follow)
        options (behavior-options cell-mode)
        option (u/seek #(= behavior (:id %)) options)]
    {:fx/type :v-box
     :spacing 10
     :padding 10
     :children
     [{:fx/type :label
       :id "trigger-configuration-title"
       :text "Trigger Configuration"
       :style-class "header-section"}

      {:fx/type :label
       :text (str "Map inputs to trigger this " target-label " cell")
       :style-class "description-text"}

      ;; Mapped Triggers List
      (if (seq cell-triggers)
        {:fx/type :v-box
         :id "trigger-mapped-list"
         :spacing 5
         :children
         (u/concatv
          (mapv (fn [trigger-key]
                  (let [label (binding-label trigger-key)]
                    {:fx/type :h-box
                     :spacing 10
                     :alignment :center-left
                     :children
                     [{:fx/type :label
                       :text label
                       :style-class "trigger-item-label"}
                      {:fx/type :region
                       :h-box/hgrow :always}
                      {:fx/type :button
                       :id (str "trigger-remove-" (hash trigger-key))
                       :text "Remove"
                       :style-class "button-danger"
                       :on-action {:event/type :input/remove-trigger
                                   :trigger-key trigger-key}}]}))
                cell-triggers)
          [{:fx/type :button
            :id "trigger-clear-all"
            :text "Clear All Triggers"
            :style-class "button-secondary"
            :on-action {:event/type :input/clear-cell-triggers
                        :target-action current-target}}])}
        ;; Empty state
        {:fx/type :label
         :text "No triggers assigned yet"
         :style-class "dialog-placeholder-text"})

      {:fx/type :h-box
       :id "trigger-behavior-controls"
       :spacing 8
       :alignment :center-left
       :children
       [{:fx/type :label :text "Behavior:"}
        {:fx/type :combo-box
         :id "trigger-behavior-selector"
         :value option
         :items options
         :converter option-name
         :on-value-changed {:event/type :input/update-cell-trigger-action
                            :target-action current-target}}]}

      {:fx/type :label
       :id "trigger-behavior-description"
       :text (:description option)
       :wrap-text true
       :style-class "label-secondary"}

      (if learning?
        {:fx/type :v-box
         :id "trigger-learning-panel"
         :spacing 5
         :children
         [{:fx/type :label
           :text "Waiting for input..."
           :style-class "status-text"}
          {:fx/type :button
           :id "trigger-cancel-learn-button"
           :text "Cancel Learning"
           :style-class "button-danger"
           :on-action {:event/type :input/cancel-learn}}]}
        {:fx/type :button
         :id "trigger-learn-button"
         :text "Learn Next Input"
         :style-class "button-primary"
         :on-action {:event/type :input/start-learn
                     :target-action (assoc current-target :behavior behavior)}})]}))
