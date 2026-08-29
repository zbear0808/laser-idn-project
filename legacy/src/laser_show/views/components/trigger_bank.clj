(ns laser-show.views.components.trigger-bank
  "Trigger Configuration Bank
   
   Provides UI for assigning MIDI, OSC, or Keyboard inputs to trigger
   the current cue chain. Replacing older MIDI-only logic with generalized input.")

(defn trigger-bank
  "Configuration UI for resolving generalized triggers."
  [{:keys [col row cell-mode trigger-map learning-target]}]
  {:fx/type :v-box
   :spacing 10
   :padding 10
   :children
   [{:fx/type :label
     :text "Trigger Configuration"
     :style-class "header-section"}

    {:fx/type :label
     :text "Map inputs to trigger this cue cell"
     :style-class "description-text"}

    ;; Mapped Triggers List
    (let [;; Find triggers that map to this specific cell
          cell-triggers (->> trigger-map
                             (filter (fn [[_k v]]
                                       (and (= (:type v) :trigger-cue)
                                            (= (:target v) [col row]))))
                             (map first))]
      (if (seq cell-triggers)
        {:fx/type :v-box
         :spacing 5
         :children
         (for [trigger-key cell-triggers]
           (let [source (:source trigger-key)
                 label (case source
                         :midi (str "MIDI Ch " (:channel trigger-key) " Note " (:note trigger-key))
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
                            :trigger-key trigger-key}}]}))}
        ;; Empty state
        {:fx/type :label
         :text "No triggers assigned yet"
         :style-class "dialog-placeholder-text"}))

    (let [is-learning? (= learning-target {:type :trigger-cue :target [col row]})]
      (if is-learning?
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
                     :target-action {:type :trigger-cue
                                     :target [col row]}}}))]})
