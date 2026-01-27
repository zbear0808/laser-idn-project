(ns laser-show.views.components.parameter-controls
  "Unified parameter control components for effects and presets.
   
   All controls use an event-template pattern for flexible event routing.
   Event templates are maps that get merged with parameter-specific keys
   like :param-key when the control fires an event.
   
   Supported parameter types:
   - :float / :int - Numeric slider with text field
   - :bool - Checkbox
   - :choice - Combo box dropdown
   
   Note: Color parameters are now handled as separate :red, :green, :blue floats
   for modulator support. Use param-color-picker directly when you need a unified
   color picker interface that reads/writes these separate RGB params.
   
   Usage:
   ```clojure
   {:fx/type param-control
    :param-key :x-scale
    :param-spec {:min 0.0 :max 2.0 :default 1.0 :type :float}
    :current-value 1.5
    :on-change-event {:event/type :my/update-param}}
   ```"
  (:require [laser-show.animation.modulator-defs :as mod-defs]
            [laser-show.common.util :as u]))


;; Parameter Spec Conversion


(defn params-vector->map
  "Convert effect/preset parameters from vector format (registry) to map format (UI).
   
   Registry format: [{:key :x-scale :default 1.0 :min 0.0 :max 2.0} ...]
   UI format: {:x-scale {:key :x-scale :default 1.0 :min 0.0 :max 2.0} ...}
   
   This makes it easier to look up parameter specs by key in the UI.
   Note: The :key field is preserved in the spec for convenience."
  [params-vector]
  (u/map-into :key params-vector))


;; Basic Parameter Controls


(defn param-slider
  "Slider control for numeric parameters with editable text field.
   
   Supports both :float and :int parameter types.
   Int parameters use snap-to-ticks and display as integers.
   
   Defensively handles modulator config maps by extracting static values.
   This can happen in edge cases where keyframes/modulation state gets out of sync.
   
   Props:
   - :param-key - Parameter key (e.g., :x-scale)
   - :param-spec - Parameter specification {:min :max :default :type :label ...}
   - :current-value - Current parameter value (should be numeric, but handles maps defensively)
   - :on-change-event - Event template with :param-key and :fx/event added for slider changes
   - :on-text-event - Event template with :param-key, :min, :max added for text field
   - :label-width - Optional label width (default 80)"
  [{:keys [param-key param-spec current-value on-change-event on-text-event label-width]}]
  (let [{:keys [min max label]} param-spec
        ;; Defensive: extract static value if passed a modulator config map
        ;; This shouldn't happen in normal operation (keyframes normalize params),
        ;; but protects against edge cases that would cause ClassCastException
        value (cond
                (number? current-value) current-value
                (mod-defs/modulated? current-value) (mod-defs/get-static-value current-value (:default param-spec))
                :else (or (:default param-spec) 0))
        is-int? (= :int (:type param-spec))
        display-label (or label (name param-key))
        label-w (or label-width 80)]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type :label
                 :text display-label
                 :pref-width label-w
                 :style-class ["label-secondary"]}
                {:fx/type :slider
                 :min min
                 :max max
                 :value (double value)
                 :pref-width 150
                 :snap-to-ticks is-int?
                 :major-tick-unit (if is-int? 1.0 (/ (- max min) 10.0))
                 :on-value-changed (assoc on-change-event :param-key param-key)}
                {:fx/type :text-field
                 :text (if is-int?
                         (str (int value))
                         (format "%.2f" (double value)))
                 :pref-width 55
                 :style-class ["text-field"]
                 :style "-fx-font-size: 11; -fx-padding: 2 4;"
                 :on-action (assoc on-text-event
                                   :param-key param-key
                                   :min min
                                   :max max
                                   :is-int? is-int?)}]}))

(defn param-choice
  "Combo-box for choice parameters.
   
   Props:
   - :param-key - Parameter key
   - :param-spec - Parameter specification with :choices vector and optional :label
   - :current-value - Current parameter value
   - :on-change-event - Event template with :param-key added
   - :label-width - Optional label width (default 80)"
  [{:keys [param-key param-spec current-value on-change-event label-width]}]
  (let [{:keys [choices label]} param-spec
        value (or current-value (:default param-spec))
        display-label (or label (name param-key))
        label-w (or label-width 80)]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type :label
                 :text display-label
                 :pref-width label-w
                 :style-class ["label-secondary"]}
                {:fx/type :combo-box
                 :items (vec (or choices []))
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
   - :param-spec - Parameter specification with :default boolean and optional :label
   - :current-value - Current parameter value
   - :on-change-event - Event template with :param-key added"
  [{:keys [param-key param-spec current-value on-change-event]}]
  (let [{:keys [label]} param-spec
        value (if (some? current-value) current-value (:default param-spec false))
        display-label (or label (name param-key))]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type :check-box
                 :text display-label
                 :selected value
                 :on-selected-changed (assoc on-change-event :param-key param-key)}]}))

(defn param-color-picker
  "Color picker for RGB color parameters.
   
   Reads color from separate :red, :green, :blue values in current-params map
   with NORMALIZED 0.0-1.0 components.
   The color picker displays/edits using the native JavaFX color picker.
   
   Props:
   - :current-params - Map containing :red, :green, :blue values (0.0-1.0)
   - :on-change-event - Event template for color changes (no :param-key needed)
   - :label-width - Optional label width (default 80)"
  [{:keys [current-params on-change-event label-width]}]
  (let [;; Read separate RGB values from params map
        r (get current-params :red 1.0)
        g (get current-params :green 1.0)
        b (get current-params :blue 1.0)
        label-w (or label-width 80)
        ;; Create JavaFX Color object from normalized values
        color-value (javafx.scene.paint.Color/color
                     (max 0.0 (min 1.0 (double r)))
                     (max 0.0 (min 1.0 (double g)))
                     (max 0.0 (min 1.0 (double b))))]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type :label
                 :text "Color"
                 :pref-width label-w
                 :style-class ["label-secondary"]}
                {:fx/type :color-picker
                 :value color-value
                 :pref-width 100
                 :on-action on-change-event}]}))


;; Parameter Control Router


(defn param-control
  "Render appropriate control based on parameter type.
   
   Routes to param-slider, param-choice, or param-checkbox
   based on the :type field in param-spec.
   
   Note: Color parameters are handled as separate :red, :green, :blue floats.
   Use param-color-picker directly for a unified color picker interface.
   
   Props:
   - :param-key - Parameter key
   - :param-spec - Parameter specification with :type (:float :int :bool :choice)
   - :current-value - Current parameter value
   - :on-change-event - Event template for value changes
   - :on-text-event - Event template for text field changes (slider only)
   - :label-width - Optional label width (default 80)"
  [{:keys [param-spec] :as props}]
  (case (:type param-spec :float)
    :choice {:fx/type param-choice
             :param-key (:param-key props)
             :param-spec param-spec
             :current-value (:current-value props)
             :on-change-event (:on-change-event props)
             :label-width (:label-width props)}
    (:bool :boolean) {:fx/type param-checkbox
                      :param-key (:param-key props)
                      :param-spec param-spec
                      :current-value (:current-value props)
                      :on-change-event (:on-change-event props)}
    ;; Default: numeric slider (:float, :int, or unspecified)
    {:fx/type param-slider
     :param-key (:param-key props)
     :param-spec param-spec
     :current-value (:current-value props)
     :on-change-event (:on-change-event props)
     :on-text-event (:on-text-event props)
     :label-width (:label-width props)}))


;; Parameter List Component


(defn param-controls-list
  "Render a list of parameter controls for all parameters in a params-map.
   
   Props:
   - :params-map - Map of param-key -> param-spec (from params-vector->map)
   - :current-params - Map of param-key -> current-value
   - :on-change-event - Event template for value changes
   - :on-text-event - Event template for text field changes
   - :label-width - Optional label width (default 80)
   - :spacing - Optional spacing between controls (default 6)"
  [{:keys [params-map current-params on-change-event on-text-event label-width spacing]}]
  {:fx/type :v-box
   :spacing (or spacing 6)
   :children (vec
              (for [[param-key param-spec] params-map]
                {:fx/type param-control
                 :param-key param-key
                 :param-spec param-spec
                 :current-value (get current-params param-key)
                 :on-change-event on-change-event
                 :on-text-event on-text-event
                 :label-width label-width}))})
