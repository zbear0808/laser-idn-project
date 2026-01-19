(ns laser-show.views.components.preset-param-editor
  "Preset parameter editor component for the cue chain editor.
   
   Shows parameter controls for the selected preset based on its type.
   Parameters can be:
   - Float: Slider + text field
   - Int: Slider with integer values
   - Color: Color picker
   - Choice: Combo box
   
   Uses the shared parameter-controls namespace for rendering controls."
  (:require [laser-show.views.components.parameter-controls :as param-controls]
            [laser-show.animation.presets :as presets]))

(defn preset-param-editor
  "Parameter editor for the selected preset instance.
   Shows controls for all parameters defined in the preset's spec.
   
   Props:
   - :cell - [col row] of cell being edited
   - :preset-path - Path to the selected preset in the cue chain
   - :preset-instance - The preset instance data {:preset-id :params {...} ...}
   - :on-param-change - (optional) Function (param-key, value) for changes
                        If not provided, uses default cue-chain events"
  [{:keys [cell preset-path preset-instance on-param-change]}]
  (let [preset-id (:preset-id preset-instance)
        preset-def (presets/get-preset preset-id)
        current-params (:params preset-instance {})
        params-map (param-controls/params-vector->map (:parameters preset-def []))
        [col row] (if (and (vector? cell) (= 2 (count cell)))
                    cell
                    [0 0])
        ;; Build event templates using generic chain handlers
        ;; These get :param-key added by the controls
        ;; Use :effect-path key for consistency with chain handlers (path to item within chain)
        on-change-event (if on-param-change
                          ;; Custom handler - wrap in a map that will invoke the fn
                          {:event/type :cue-chain/invoke-param-callback
                           :callback on-param-change}
                          ;; Use generic chain handler
                          {:event/type :chain/update-param
                           :domain :cue-chains
                           :entity-key [col row]
                           :effect-path preset-path})
        on-text-event (if on-param-change
                        {:event/type :cue-chain/invoke-param-text-callback
                         :callback on-param-change}
                        ;; Use generic chain handler
                        {:event/type :chain/update-param-from-text
                         :domain :cue-chains
                         :entity-key [col row]
                         :effect-path preset-path})
        on-color-event (if on-param-change
                         {:event/type :cue-chain/invoke-param-color-callback
                          :callback on-param-change}
                         ;; Use generic chain handler for color (converts color picker action event)
                         {:event/type :chain/update-color-param
                          :domain :cue-chains
                          :entity-key [col row]
                          :effect-path preset-path})]
    {:fx/type :v-box
     :spacing 8
     :style-class "dialog-section"
     :children [{:fx/type :label
                 :text (if preset-def
                         (str "PRESET: " (:name preset-def))
                         "PRESET PARAMETERS")
                 :style-class "dialog-section-header"}
                (if preset-def
                  {:fx/type :scroll-pane
                   :fit-to-width true
                   :style-class "dialog-scroll-pane"
                   :content {:fx/type :v-box
                             :spacing 8
                             :padding {:top 4}
                             :children (vec
                                        (for [[param-key param-spec] params-map]
                                          ;; Use the appropriate event for color params
                                          (let [change-evt (if (= :color (:type param-spec))
                                                             on-color-event
                                                             on-change-event)]
                                            {:fx/type param-controls/param-control
                                             :param-key param-key
                                             :param-spec param-spec
                                             :current-value (get current-params param-key)
                                             :on-change-event change-evt
                                             :on-text-event on-text-event
                                             :label-width 100})))}}
                  {:fx/type :label
                   :text "Select a preset from the chain"
                   :style-class "dialog-placeholder-text"})]}))
