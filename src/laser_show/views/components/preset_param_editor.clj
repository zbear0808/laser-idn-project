(ns laser-show.views.components.preset-param-editor
  "Preset parameter editor component for the cue chain editor.
   
   Shows parameter controls for the selected preset based on its type.
   Parameters can be:
   - Float: Slider + text field
   - Int: Slider with integer values
   - Color: Color picker
   - Choice: Combo box
   
   Similar to the parameter editor in effect-chain-editor but for preset parameters."
  (:require [cljfx.api :as fx]
            [laser-show.animation.presets :as presets]))


;; Parameter Spec Helpers


(defn- params-vector->map
  "Convert preset parameters from vector format (registry) to map format (UI).
   [{:key :radius :default 0.5 ...}] -> {:radius {:default 0.5 ...}}"
  [params-vector]
  (into {}
        (mapv (fn [p] [(:key p) (dissoc p :key)])
              params-vector)))


;; Parameter Controls


(defn- param-slider
  "Slider control for numeric parameters with editable text field.
   
   Props:
   - :cell - [col row] of cell being edited
   - :preset-path - Path to the preset instance in the cue chain
   - :param-key - Parameter keyword
   - :param-spec - Parameter specification {:min :max :default ...}
   - :current-value - Current parameter value
   - :on-param-change - (optional) Function (param-key, value) for changes"
  [{:keys [cell preset-path param-key param-spec current-value on-param-change]}]
  (let [{:keys [min max label]} param-spec
        value (or current-value (:default param-spec) 0)
        [col row] cell
        is-int? (= :int (:type param-spec))]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type :label
                 :text (or label (name param-key))
                 :pref-width 100
                 :style "-fx-text-fill: #B0B0B0; -fx-font-size: 11;"}
                {:fx/type :slider
                 :min min
                 :max max
                 :value (double value)
                 :pref-width 150
                 :snap-to-ticks is-int?
                 :major-tick-unit (if is-int? 1.0 (/ (- max min) 10.0))
                 :on-value-changed (if on-param-change
                                     (fn [v] (on-param-change param-key (if is-int? (int v) v)))
                                     {:event/type :cue-chain/update-preset-param
                                      :col col :row row
                                      :preset-path preset-path
                                      :param-key param-key})}
                {:fx/type :text-field
                 :text (if is-int?
                         (str (int value))
                         (format "%.2f" (double value)))
                 :pref-width 55
                 :style "-fx-background-color: #404040; -fx-text-fill: white; -fx-font-size: 11; -fx-padding: 2 4;"
                 :on-action (if on-param-change
                              (fn [e]
                                (let [text-field (.getSource e)
                                      text (.getText text-field)]
                                  (try
                                    (let [v (if is-int?
                                              (Integer/parseInt text)
                                              (Double/parseDouble text))
                                          clamped (max min (min max v))]
                                      (on-param-change param-key clamped))
                                    (catch Exception _))))
                              {:event/type :cue-chain/update-preset-param-from-text
                               :col col :row row
                               :preset-path preset-path
                               :param-key param-key
                               :min min :max max})}]}))

(defn- param-color-picker
  "Color picker for color parameters.
   
   Props:
   - :cell - [col row] of cell being edited
   - :preset-path - Path to the preset instance
   - :param-key - Parameter keyword
   - :param-spec - Parameter specification
   - :current-value - Current color value [r g b]
   - :on-param-change - (optional) Function for changes"
  [{:keys [cell preset-path param-key param-spec current-value on-param-change]}]
  (let [{:keys [label]} param-spec
        ;; Ensure we have valid color vector and cell
        color-vec (if (and (vector? current-value) (= 3 (count current-value)))
                    current-value
                    (if (and (vector? (:default param-spec)) (= 3 (count (:default param-spec))))
                      (:default param-spec)
                      [255 255 255]))
        [r g b] color-vec
        [col row] (if (and (vector? cell) (= 2 (count cell)))
                    cell
                    [0 0])
        ;; Create JavaFX Color object for color-picker
        color-value (javafx.scene.paint.Color/rgb (int r) (int g) (int b))]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type :label
                 :text (or label (name param-key))
                 :pref-width 100
                 :style "-fx-text-fill: #B0B0B0; -fx-font-size: 11;"}
                {:fx/type :color-picker
                 :value color-value
                 :pref-width 100
                 :on-action (if on-param-change
                              (fn [e]
                                (let [cp (.getSource e)
                                      color (.getValue cp)
                                      new-color [(int (* 255 (.getRed color)))
                                                 (int (* 255 (.getGreen color)))
                                                 (int (* 255 (.getBlue color)))]]
                                  (on-param-change param-key new-color)))
                              {:event/type :cue-chain/update-preset-color
                               :col col :row row
                               :preset-path preset-path
                               :param-key param-key})}]}))

(defn- param-choice
  "Combo-box for choice parameters.
   
   Props:
   - :cell - [col row] of cell being edited
   - :preset-path - Path to the preset instance
   - :param-key - Parameter keyword
   - :param-spec - Parameter specification with :choices
   - :current-value - Current selected value
   - :on-param-change - (optional) Function for changes"
  [{:keys [cell preset-path param-key param-spec current-value on-param-change]}]
  (let [{:keys [choices label]} param-spec
        value (or current-value (:default param-spec))
        [col row] cell]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type :label
                 :text (or label (name param-key))
                 :pref-width 100
                 :style "-fx-text-fill: #B0B0B0; -fx-font-size: 11;"}
                {:fx/type :combo-box
                 :items (vec choices)
                 :value value
                 :pref-width 150
                 :button-cell (fn [item] {:text (if item (name item) "")})
                 :cell-factory {:fx/cell-type :list-cell
                                :describe (fn [item] {:text (if item (name item) "")})}
                 :on-value-changed (if on-param-change
                                     (fn [v] (on-param-change param-key v))
                                     {:event/type :cue-chain/update-preset-param
                                      :col col :row row
                                      :preset-path preset-path
                                      :param-key param-key})}]}))


;; Parameter Control Router


(defn- param-control
  "Render appropriate control based on parameter type."
  [{:keys [param-spec] :as props}]
  (case (:type param-spec :float)
    :color {:fx/type param-color-picker
            :cell (:cell props)
            :preset-path (:preset-path props)
            :param-key (:param-key props)
            :param-spec param-spec
            :current-value (:current-value props)
            :on-param-change (:on-param-change props)}
    :choice {:fx/type param-choice
             :cell (:cell props)
             :preset-path (:preset-path props)
             :param-key (:param-key props)
             :param-spec param-spec
             :current-value (:current-value props)
             :on-param-change (:on-param-change props)}
    ;; :float, :int - numeric slider
    {:fx/type param-slider
     :cell (:cell props)
     :preset-path (:preset-path props)
     :param-key (:param-key props)
     :param-spec param-spec
     :current-value (:current-value props)
     :on-param-change (:on-param-change props)}))


;; Main Preset Parameter Editor


(defn preset-param-editor
  "Parameter editor for the selected preset instance.
   Shows controls for all parameters defined in the preset's spec.
   
   Props:
   - :cell - [col row] of cell being edited
   - :preset-path - Path to the selected preset in the cue chain
   - :preset-instance - The preset instance data {:preset-id :params {...} ...}
   - :on-param-change - (optional) Function (param-key, value) for changes"
  [{:keys [cell preset-path preset-instance on-param-change]}]
  (let [preset-id (:preset-id preset-instance)
        preset-def (presets/get-preset preset-id)
        current-params (:params preset-instance {})
        params-map (params-vector->map (:parameters preset-def []))]
    {:fx/type :v-box
     :spacing 8
     :style "-fx-background-color: #2A2A2A; -fx-padding: 8;"
     :children [{:fx/type :label
                 :text (if preset-def
                         (str "PRESET: " (:name preset-def))
                         "PRESET PARAMETERS")
                 :style "-fx-text-fill: #808080; -fx-font-size: 11; -fx-font-weight: bold;"}
                (if preset-def
                  {:fx/type :scroll-pane
                   :fit-to-width true
                   :style "-fx-background-color: transparent; -fx-background: #2A2A2A;"
                   :content {:fx/type :v-box
                             :spacing 8
                             :padding {:top 4}
                             :children (vec
                                        (for [[param-key param-spec] params-map]
                                          {:fx/type param-control
                                           :cell cell
                                           :preset-path preset-path
                                           :param-key param-key
                                           :param-spec param-spec
                                           :current-value (get current-params param-key)
                                           :on-param-change on-param-change}))}}
                  {:fx/type :label
                   :text "Select a preset from the chain"
                   :style "-fx-text-fill: #606060; -fx-font-style: italic; -fx-font-size: 11;"})]}))
