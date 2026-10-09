(ns laser-show.views.components.trigger-bank
  "Trigger Configuration Bank
   
   Provides UI for assigning MIDI, OSC, or Keyboard inputs to trigger
   the current cue or effect cell."
  (:require [laser-show.common.util :as u]))

(defn trigger-bank
  "Configuration UI for resolving generalized triggers.
   
   Props:
   - :col, :row - Cell coordinates
   - :cell-mode - :cue or :effects (default: :cue)
   - :trigger-map - Map of input-key -> target-action
   - :learning-target - Current learning target action (or nil)"
  [{:keys [col row cell-mode trigger-map learning-target]
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
                            trigger-map)]
    {:fx/type :v-box
     :spacing 10
     :padding 10
     :children
     [{:fx/type :label
       :text "Trigger Configuration"
       :style-class "header-section"}

      {:fx/type :label
       :text (str "Map inputs to trigger this " target-label " cell")
       :style-class "description-text"}

      ;; Mapped Triggers List
      (if (seq cell-triggers)
        {:fx/type :v-box
         :spacing 5
         :children
         (u/concatv
          (mapv (fn [trigger-key]
                  (let [source (:source trigger-key)
                        label (case source
                                :midi (str "MIDI Ch " (inc (or (:channel trigger-key) 0))
                                           " Note " (:note trigger-key))
                                :osc (str "OSC " (:id trigger-key))
                                :keyboard (str "Key " (:id trigger-key))
                                (str trigger-key))]
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
                       :text "Remove"
                       :style-class "button-danger"
                       :on-action {:event/type :input/remove-trigger
                                   :trigger-key trigger-key}}]}))
                cell-triggers)
          [{:fx/type :button
            :text "Clear All Triggers"
            :style-class "button-secondary"
            :on-action {:event/type :input/clear-cell-triggers
                        :target-action current-target}}])}
        ;; Empty state
        {:fx/type :label
         :text "No triggers assigned yet"
         :style-class "dialog-placeholder-text"})

      (if learning?
        {:fx/type :v-box
         :spacing 5
         :children
         [{:fx/type :label
           :text "Waiting for input..."
           :style-class "status-text"}
          {:fx/type :button
           :text "Cancel Learning"
           :style-class "button-danger"
           :on-action {:event/type :input/cancel-learn}}]}
        {:fx/type :button
         :text "Learn Next Input"
         :style-class "button-primary"
         :on-action {:event/type :input/start-learn
                     :target-action current-target}})]}))
