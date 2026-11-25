(ns laser-show.views.components.preset-param-editor
  "Preset parameter editor component for the cue chain editor.
   
   Shows parameter controls for the selected preset based on its type.
   Parameters can be:
   - Float: Slider + text field
   - Int: Slider with integer values
   - Color: Color picker
   - Choice: Combo box
   
   Uses the shared parameter-controls namespace for rendering controls.
   Its updates are :cue-chain/update-preset-param, -param-from-text and
   :cue-chain/update-preset-color, naming the item by its :id (follow-up
   N10)."
  (:require [laser-show.views.components.parameter-controls :as param-controls]
            [laser-show.animation.presets :as presets]))

(defn preset-param-editor
  "Parameter editor for the selected preset instance.
   Shows controls for all parameters defined in the preset's spec. It never
   gets shorter than its rows: in the cue chain editor, what is below it (an
   effect's parameters, which scroll) gives way first.
   
   Its edits name the item by its :id and the cue by its cell, never the
   item's place (follow-up N10, as R2's Target choice): the handlers find
   the item where it is when they run, and do nothing once it is gone.
   
   Props:
   - :cell - [col row] of cell being edited
   - :preset-instance - The preset instance data {:id :preset-id :params {...} ...}"
  [{:keys [cell preset-instance]}]
  (let [preset-id (:preset-id preset-instance)
        preset-def (presets/presets-by-id preset-id)
        current-params (:params preset-instance {})
        params-map (param-controls/params-vector->map (:parameters preset-def []))
        [col row] (if (and (vector? cell) (= 2 (count cell)))
                    cell
                    [0 0])
        ;; The controls add :param-key and the value, the typed :text or the
        ;; picked :color (events/handlers/cue_chain.clj)
        item {:col col :row row :item-id (:id preset-instance)}
        on-change-event (assoc item :event/type :cue-chain/update-preset-param)
        on-text-event (assoc item :event/type :cue-chain/update-preset-param-from-text)
        on-color-event (assoc item :event/type :cue-chain/update-preset-color)]
    {:fx/type :v-box
     :spacing 8
     :min-height :use-pref-size
     :style-class "dialog-section"
     :children [{:fx/type :label
                 :text (if preset-def
                         (str "PRESET: " (:name preset-def))
                         "PRESET PARAMETERS")
                 :style-class "header-section"}
                (if preset-def
                   {:fx/type :scroll-pane
                    :fit-to-width true
                    :style-class "scroll-pane-base"
                    :content {:fx/type :v-box
                              :spacing 8
                              :padding {:top 4}
                              :children (vec
                                         (for [[param-key param-spec] params-map
                                               ;; Skip :green and :blue if they're part of a color group
                                               ;; (we render them via the color picker when we hit :red)
                                               :when (not (and (:color-group? param-spec)
                                                               (#{:green :blue} param-key)))]
                                           (if (and (:color-group? param-spec) (= :red param-key))
                                             ;; Render combined color picker for RGB color group
                                             {:fx/type param-controls/param-color-picker
                                              :current-params current-params
                                              :on-change-event on-color-event
                                              :label-width 100}
                                             ;; Render normal control for non-color params
                                             {:fx/type param-controls/param-control
                                              :param-key param-key
                                              :param-spec param-spec
                                              :current-value (get current-params param-key)
                                              :on-change-event on-change-event
                                              :on-text-event on-text-event
                                              :label-width 100})))}}
                   {:fx/type :label
                    :text "Select a preset from the chain"
                    :style-class "dialog-placeholder-text"})]}))
