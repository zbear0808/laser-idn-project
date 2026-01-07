(ns laser-show.views.components.effect-param-ui
  "Shared effect parameter UI components.
   
   Used by both effect-chain-editor and cue-chain-editor to render
   effect parameters with standard controls (sliders, checkboxes, etc.)
   and custom visual editors (translate, corner-pin, RGB curves).
   
   Key Features:
   - Event-template pattern for flexible event routing
   - Support for visual/numeric mode toggle
   - Automatic control type selection based on parameter spec
   - Custom visual renderers for spatial and curve effects"
  (:require [laser-show.views.components.custom-param-renderers :as custom-renderers]))


;; Parameter Spec Conversion


(defn params-vector->map
  "Convert effect parameters from vector format (registry) to map format (UI).
   
   Registry format: [{:key :x-scale :default 1.0 :min 0.0 :max 2.0} ...]
   UI format: {:x-scale {:default 1.0 :min 0.0 :max 2.0} ...}
   
   This makes it easier to look up parameter specs by key in the UI."
  [params-vector]
  (into {}
        (mapv (fn [p] [(:key p) (dissoc p :key)])
              params-vector)))


;; Basic Parameter Controls


(defn param-slider
  "Slider control for numeric parameters with editable text field.
   
   Props:
   - :param-key - Parameter key (e.g., :x-scale)
   - :param-spec - Parameter specification {:min :max :default :type ...}
   - :current-value - Current parameter value
   - :on-change-event - Event template with :param-key added for slider changes
   - :on-text-event - Event template with :param-key, :min, :max added for text field"
  [{:keys [param-key param-spec current-value on-change-event on-text-event]}]
  (let [{:keys [min max]} param-spec
        value (or current-value (:default param-spec) 0)]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type :label
                 :text (name param-key)
                 :pref-width 80
                 :style "-fx-text-fill: #B0B0B0; -fx-font-size: 11;"}
                {:fx/type :slider
                 :min min
                 :max max
                 :value (double value)
                 :pref-width 150
                 :on-value-changed (assoc on-change-event :param-key param-key)}
                {:fx/type :text-field
                 :text (format "%.2f" (double value))
                 :pref-width 55
                 :style "-fx-background-color: #404040; -fx-text-fill: white; -fx-font-size: 11; -fx-padding: 2 4;"
                 :on-action (assoc on-text-event
                                   :param-key param-key
                                   :min min
                                   :max max)}]}))

(defn param-choice
  "Combo-box for choice parameters.
   
   Props:
   - :param-key - Parameter key
   - :param-spec - Parameter specification with :choices vector
   - :current-value - Current parameter value
   - :on-change-event - Event template with :param-key added"
  [{:keys [param-key param-spec current-value on-change-event]}]
  (let [choices (:choices param-spec [])
        value (or current-value (:default param-spec))]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type :label
                 :text (name param-key)
                 :pref-width 80
                 :style "-fx-text-fill: #B0B0B0; -fx-font-size: 11;"}
                {:fx/type :combo-box
                 :items choices
                 :value value
                 :pref-width 150
                 :button-cell (fn [item] {:text (if item (name item) "")})
                 :cell-factory {:fx/cell-type :list-cell
                                :describe (fn [item] {:text (if item (name item) "")})}
                 :on-value-changed (assoc on-change-event :param-key param-key)}]}))

(defn param-checkbox
  "Checkbox for boolean parameters.
   
   Props:
   - :param-key - Parameter key
   - :param-spec - Parameter specification with :default boolean
   - :current-value - Current parameter value
   - :on-change-event - Event template with :param-key added"
  [{:keys [param-key param-spec current-value on-change-event]}]
  (let [value (if (some? current-value) current-value (:default param-spec false))]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type :check-box
                 :text (name param-key)
                 :selected value
                 :style "-fx-text-fill: #B0B0B0; -fx-font-size: 11;"
                 :on-selected-changed (assoc on-change-event :param-key param-key)}]}))

(defn param-control
  "Render appropriate control based on parameter type.
   
   Routes to param-slider, param-choice, or param-checkbox based on
   the :type field in param-spec.
   
   Props:
   - :param-key - Parameter key
   - :param-spec - Parameter specification
   - :current-value - Current parameter value
   - :on-change-event - Event template for value changes
   - :on-text-event - Event template for text field changes (slider only)"
  [{:keys [param-spec] :as props}]
  (case (:type param-spec :float)
    :choice {:fx/type param-choice
             :param-key (:param-key props)
             :param-spec param-spec
             :current-value (:current-value props)
             :on-change-event (:on-change-event props)}
    :bool {:fx/type param-checkbox
           :param-key (:param-key props)
           :param-spec param-spec
           :current-value (:current-value props)
           :on-change-event (:on-change-event props)}
    ;; Default: numeric slider
    {:fx/type param-slider
     :param-key (:param-key props)
     :param-spec param-spec
     :current-value (:current-value props)
     :on-change-event (:on-change-event props)
     :on-text-event (:on-text-event props)}))


;; Custom Parameter Renderer with Visual Editors


(defn custom-param-renderer
  "Renders effect parameters with custom UI and mode toggle.
   
   Supports three types of custom renderers:
   1. RGB Curves - Visual-only curve editor with R/G/B tabs
   2. Translate (spatial-2d) - Drag point to adjust X/Y position
   3. Corner Pin (corner-pin-2d) - Drag 4 corners for perspective mapping
   
   Props:
   - :effect-def - Effect definition with :ui-hints {:renderer :spatial-2d ...}
   - :current-params - Current parameter values map
   - :params-map - Parameter specifications map (from params-vector->map)
   - :ui-mode - Current UI mode (:visual or :numeric)
   - :dialog-data - Dialog state data (for curve editor channel tracking, ui-modes)
   
   Event template props (for visual editors):
   - :spatial-event-template - Base event for spatial param updates (translate, corner-pin)
   - :spatial-event-keys - Additional keys to pass through (e.g., :col, :row, :effect-path)
   
   Event template props (for param controls):
   - :on-change-event - Event template for param value changes
   - :on-text-event - Event template for text field updates
   - :on-mode-change-event - Event template for visual/numeric mode toggle
   
   RGB Curves props:
   - :rgb-domain - Domain for RGB curves (:effect-chains, :item-effects, etc.)
   - :rgb-entity-key - Entity key for RGB curves
   - :rgb-effect-path - Effect path for RGB curves"
  [{:keys [effect-def current-params ui-mode params-map dialog-data
           spatial-event-template spatial-event-keys
           on-change-event on-text-event on-mode-change-event
           rgb-domain rgb-entity-key rgb-effect-path]}]
  (let [ui-hints (:ui-hints effect-def)
        renderer-type (:renderer ui-hints)
        actual-mode (or ui-mode (:default-mode ui-hints :visual))]
    ;; RGB Curves is visual-only, no mode toggle - use the public rgb-curves-visual-editor
    (if (= renderer-type :rgb-curves)
      {:fx/type custom-renderers/rgb-curves-visual-editor
       :domain rgb-domain
       :entity-key rgb-entity-key
       :effect-path rgb-effect-path
       :current-params current-params
       :dialog-data dialog-data}
      
      ;; Other custom renderers have mode toggle
      {:fx/type :v-box
       :spacing 8
       :children [;; Mode toggle buttons
                  {:fx/type :h-box
                   :spacing 6
                   :alignment :center-left
                   :padding {:bottom 8}
                   :children [{:fx/type :label
                              :text "Edit Mode:"
                              :style "-fx-text-fill: #808080; -fx-font-size: 10;"}
                             {:fx/type :button
                              :text "👁 Visual"
                              :style (str "-fx-font-size: 9; -fx-padding: 3 10; "
                                         (if (= actual-mode :visual)
                                           "-fx-background-color: #4A6FA5; -fx-text-fill: white;"
                                           "-fx-background-color: #404040; -fx-text-fill: #B0B0B0;"))
                              :on-action (assoc on-mode-change-event :mode :visual)}
                             {:fx/type :button
                              :text "🔢 Numeric"
                              :style (str "-fx-font-size: 9; -fx-padding: 3 10; "
                                         (if (= actual-mode :numeric)
                                           "-fx-background-color: #4A6FA5; -fx-text-fill: white;"
                                           "-fx-background-color: #404040; -fx-text-fill: #B0B0B0;"))
                              :on-action (assoc on-mode-change-event :mode :numeric)}]}
                  
                  ;; Render based on mode
                  (if (= actual-mode :visual)
                    ;; Custom visual renderer
                    (case renderer-type
                      :spatial-2d {:fx/type custom-renderers/translate-visual-editor
                                  :current-params current-params
                                  :param-specs (:parameters effect-def)
                                  :event-template (merge spatial-event-template spatial-event-keys)
                                  :fx-key (get spatial-event-keys :effect-path)}
                      
                      :corner-pin-2d {:fx/type custom-renderers/corner-pin-visual-editor
                                     :current-params current-params
                                     :param-specs (:parameters effect-def)
                                     :event-template (merge spatial-event-template spatial-event-keys)
                                     :fx-key (get spatial-event-keys :effect-path)}
                      
                      ;; Fallback to standard params
                      {:fx/type :v-box
                       :spacing 6
                       :children (vec
                                  (for [[param-key param-spec] params-map]
                                    {:fx/type param-control
                                     :param-key param-key
                                     :param-spec param-spec
                                     :current-value (get current-params param-key)
                                     :on-change-event on-change-event
                                     :on-text-event on-text-event}))})
                    
                    ;; Numeric mode - standard sliders
                    {:fx/type :v-box
                     :spacing 6
                     :children (vec
                                (for [[param-key param-spec] params-map]
                                  {:fx/type param-control
                                   :param-key param-key
                                   :param-spec param-spec
                                   :current-value (get current-params param-key)
                                   :on-change-event on-change-event
                                   :on-text-event on-text-event}))})]})))
