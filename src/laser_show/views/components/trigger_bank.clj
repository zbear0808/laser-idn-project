(ns laser-show.views.components.trigger-bank
  "Trigger Configuration Bank
   
   Provides UI for assigning MIDI, OSC, or Keyboard inputs to trigger
   the current cue or effect cell."
  (:require [clojure.string :as str]
            [laser-show.common.util :as u]
            [laser-show.input.keyboard :as keyboard])
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
   \"OSC /x\", \"Key Space\", or the key itself."
  [trigger-key]
  (case (:source trigger-key)
    :midi (str "MIDI Ch " (inc (or (:channel trigger-key) 0))
               " Note " (:note trigger-key))
    :osc (str "OSC " (:id trigger-key))
    :keyboard (str "Key " (keyboard/label (:id trigger-key)))
    (str trigger-key)))

(defn- binding-row [{:keys [source id] :as trigger-key}]
  (let [shortcut (when (= :keyboard source) (keyboard/builtin-shortcut id))]
    {:fx/type :h-box
     :fx/key trigger-key
     :spacing 10
     :alignment :center-left
     :children [{:fx/type :v-box
                 :h-box/hgrow :always
                 :spacing 2
                 :children (cond-> [{:fx/type :label
                                     :text (binding-label trigger-key)
                                     :style-class ["label" "trigger-item-label"]}]
                             shortcut (conj {:fx/type :label
                                             :text (str "Overrides built-in shortcut: " (:label shortcut))
                                             :wrap-text true
                                             :style-class "label-secondary"}))}
                {:fx/type :button
                 :id (str "trigger-remove-" (hash trigger-key))
                 :text "Remove"
                 :style-class "button-danger"
                 :on-action {:event/type :input/remove-trigger :trigger-key trigger-key}}]}))

(defn fader-label
  "An input store key learned for a fader as the user knows the control:
   \"MIDI Ch 1 CC 7\", \"OSC /fader/1\", \"OSC CC 5\" (a mapped address),
   \"Gamepad 1 trigger left\", or the key itself."
  [[source a b :as store-key]]
  (cond
    (and (= :midi source) (number? a)) (str "MIDI Ch " (inc (long a)) " CC " b)
    (and (= :osc source) (string? a)) (str "OSC " a)
    (= :osc source) (str "OSC CC " b)
    (and (= :gamepad source) (number? a)) (str "Gamepad " (inc (long a)) " " (str/replace (name b) "-" " "))
    :else (str store-key)))

(defn trigger-bank
  "Configuration UI for resolving generalized triggers.
   
   Props:
   - :col, :row - Cell coordinates
   - :cell-mode - :cue or :effects (default: :cue)
   - :trigger-map - Map of input-key -> target-action
   - :learn - The learn waiting for this cell, as its Cancel names it
     ({:learn-id :document-id}), or nil
   - :behavior - The behaviour shown (:follow or :momentary,
     subs/trigger-editor-behavior): a waiting learn's, else the cell's
     first binding's, else the editor's choice
   - :document-id - The open show's, sent with Learn and the choice"
  [{:keys [col row cell-mode trigger-map learn behavior document-id]
    :or {cell-mode :cue}}]
  (let [target-type (if (= cell-mode :effects) :trigger-effect :trigger-cue)
        target-label (if (= cell-mode :effects) "effect" "cue")
        current-target {:type target-type :target [col row]}
        learning? (some? learn)
        ;; Find triggers that map to this specific cell eagerly
        cell-triggers (into []
                            (keep (fn [[k v]]
                                    (when (and (= (:type v) target-type)
                                               (= (:target v) [col row]))
                                      k)))
                            trigger-map)
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
       :style-class "label-secondary"}

      ;; Mapped Triggers List
      (if (seq cell-triggers)
        {:fx/type :v-box
         :id "trigger-mapped-list"
         :spacing 5
         :children
         (u/concatv
          (mapv binding-row cell-triggers)
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
                            :target-action current-target
                            :document-id document-id}}]}

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
           :style-class "label-primary"}
          {:fx/type :button
           :id "trigger-cancel-learn-button"
           :text "Cancel Learning"
           :style-class "button-danger"
           :on-action (merge {:event/type :input/cancel-learn} learn)}]}
        {:fx/type :button
         :id "trigger-learn-button"
         :text "Learn Next Input"
         :style-class "button-primary"
         ;; The cell, not a behaviour: the handler reads the one shown now
         :on-action {:event/type :input/start-learn
                     :owner :trigger-editor
                     :target-action current-target
                     :document-id document-id}})]}))
