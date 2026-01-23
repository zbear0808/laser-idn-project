(ns laser-show.views.components.modulator-param-control
  "Parameter control with modulator support.
   
   Allows numeric parameters to be either static values or modulator configs.
   When a modulator is selected, displays modulator-specific parameters.
   
   Uses the event-template pattern for flexible event routing. Event templates
   are maps that get merged with modulator-specific keys when controls fire events.
   
   Usage:
   ```clojure
   {:fx/type modulator-param-control
    :param-key :x-scale
    :param-spec {:min 0.0 :max 2.0 :default 1.0 :type :float}
    :current-value 1.5  ; or {:type :sine :min 0.8 :max 1.2 :period 1.0}
    :on-change-event {:event/type :chain/update-param
                      :domain :effect-chains
                      :entity-key [0 0]
                      :effect-path [0]}
    :on-text-event {:event/type :chain/update-param-from-text ...}}
   ```"
  (:require [laser-show.animation.modulator-defs :as mod-defs]
            [laser-show.views.components.parameter-controls :as param-controls]))


;; UI Components


(defn- modulator-type-selector
  "Dropdown to select modulator type.
   
   Props:
   - :current-type - Current modulator type keyword
   - :on-select-event - Event template for type selection (receives :mod-type)"
  [{:keys [current-type on-select-event]}]
  (let [all-types mod-defs/all-modulator-type-list
        current-info (get mod-defs/modulator-type->info current-type)]
    {:fx/type :combo-box
     :pref-width 140
     :value (or current-info (first mod-defs/wave-modulators))
     :items (vec all-types)
     :button-cell (fn [item]
                    {:text (when item (str (:icon item) " " (:name item)))})
     :cell-factory {:fx/cell-type :list-cell
                    :describe (fn [item]
                                {:text (when item (str (:icon item) " " (:name item)))})}
     ;; Event map pattern: merge event template with :mod-type
     :on-value-changed (assoc on-select-event :mod-type-item? true)}))

(defn- modulator-param-slider
  "Slider for a single modulator parameter with editable text field.
   
   Uses same pattern as parameter-controls/param-slider for consistency.
   
   Props:
   - :param-def - Parameter definition map with :key, :label, :min, :max, :default
   - :current-value - Current value for this parameter
   - :on-change-event - Event template for slider value changes (receives :mod-param-key)
   - :on-text-event - Event template for text field changes (receives :mod-param-key, :mod-text-field? true)"
  [{:keys [param-def current-value on-change-event on-text-event]}]
  (let [{:keys [key label min max default]} param-def
        ;; Handle invalid values defensively - only use current-value if it's a number
        value (if (number? current-value)
                current-value
                (or default 0.0))
        ;; Always ensure text event has the text field marker
        base-text-event (or on-text-event on-change-event)
        text-event (assoc base-text-event :mod-text-field? true)]
    {:fx/type :h-box
     :spacing 6
     :alignment :center-left
     :children [{:fx/type :label
                 :text label
                 :pref-width 90
                 :style-class ["text-small"]
                 :style "-fx-text-fill: #909090;"}
                {:fx/type :slider
                 :min min
                 :max max
                 :value (double value)
                 :pref-width 80
                 ;; Event map pattern: add mod-param-key to template
                 :on-value-changed (assoc on-change-event :mod-param-key key)}
                {:fx/type :text-field
                 :text (format "%.2f" (double value))
                 :pref-width 50
                 :style-class ["text-field"]
                 :style "-fx-font-size: 10; -fx-padding: 2 4;"
                 ;; Text field event includes mod-param-key and text field marker
                 :on-action (assoc text-event
                                   :mod-param-key key
                                   :mod-param-min min
                                   :mod-param-max max)}]}))

(defn- modulator-param-choice
  "Choice dropdown for a modulator parameter.
   
   Props:
   - :param-def - Parameter definition map with :key, :label, :choices, :default
   - :current-value - Current value for this parameter
   - :on-change-event - Event template for value changes (receives :mod-param-key)"
  [{:keys [param-def current-value on-change-event]}]
  (let [{:keys [key label choices default]} param-def
        value (or current-value default)]
    {:fx/type :h-box
     :spacing 6
     :alignment :center-left
     :children [{:fx/type :label
                 :text label
                 :pref-width 90
                 :style-class ["text-small"]
                 :style "-fx-text-fill: #909090;"}
                {:fx/type :combo-box
                 :items (vec choices)
                 :value value
                 :pref-width 100
                 :button-cell (fn [item] {:text (when item (name item))})
                 :cell-factory {:fx/cell-type :list-cell
                                :describe (fn [item] {:text (when item (name item))})}
                 ;; Event map pattern: add mod-param-key to template
                 :on-value-changed (assoc on-change-event :mod-param-key key)}]}))

(defn- modulator-param-text
  "Text field for a modulator parameter (e.g., step values).
   
   Props:
   - :param-def - Parameter definition map with :key, :label, :default
   - :current-value - Current value for this parameter
   - :on-change-event - Event template for value changes (receives :mod-param-key)"
  [{:keys [param-def current-value on-change-event]}]
  (let [{:keys [key label default]} param-def
        value (or current-value default)]
    {:fx/type :h-box
     :spacing 6
     :alignment :center-left
     :children [{:fx/type :label
                 :text label
                 :pref-width 90
                 :style "-fx-text-fill: #909090; -fx-font-size: 10;"}
                {:fx/type :text-field
                 :text (str value)
                 :pref-width 100
                 :style "-fx-background-color: #404040; -fx-text-fill: white; -fx-font-size: 10;"
                 ;; Event map pattern: add mod-param-key to template
                 :on-action (assoc on-change-event
                                   :mod-param-key key
                                   :text-field? true)}]}))

(defn- modulator-params-editor
  "Editor for modulator-specific parameters.
   
   Props:
   - :modulator-config - Current modulator configuration map
   - :on-param-change-event - Event template for parameter changes
   - :on-retrigger-event - Event template for retrigger button (optional)
   - :param-spec - The original param spec (for getting min/max bounds)"
  [{:keys [modulator-config on-param-change-event on-retrigger-event param-spec]}]
  (let [mod-type (:type modulator-config)
        base-params (get mod-defs/modulator-params mod-type [])
        ;; Adjust min/max param bounds to use param-spec's bounds
        params (mapv (fn [param-def]
                       (case (:key param-def)
                         ;; For modulator's min/max params, use the param-spec bounds
                         :min (assoc param-def
                                     :min (or (:min param-spec) -10.0)
                                     :max (or (:max param-spec) 10.0))
                         :max (assoc param-def
                                     :min (or (:min param-spec) -10.0)
                                     :max (or (:max param-spec) 10.0))
                         ;; Keep other params as-is
                         param-def))
                     base-params)]
    {:fx/type :v-box
     :spacing 4
     :padding {:top 6 :left 12}
     :children (vec
                (concat
                 ;; Retrigger button at top (for wave and decay modulators)
                 (when (and on-retrigger-event
                            (mod-defs/supports-retrigger? mod-type))
                   [{:fx/type :h-box
                     :spacing 6
                     :alignment :center-left
                     :children [{:fx/type :button
                                 :text "⟲ Retrigger"
                                 :style-class ["retrigger-button"]
                                 :style (str "-fx-font-size: 10; -fx-padding: 2 8; "
                                            "-fx-background-color: #505050; "
                                            "-fx-text-fill: #E0E0E0; "
                                            "-fx-cursor: hand;")
                                 :on-action on-retrigger-event}
                                {:fx/type :label
                                 :text "Reset modulator phase"
                                 :style "-fx-text-fill: #707070; -fx-font-size: 9;"}]}])
                 ;; Parameter controls
                 (for [param-def params]
                   (case (:type param-def)
                     :choice {:fx/type modulator-param-choice
                              :param-def param-def
                              :current-value (get modulator-config (:key param-def))
                              :on-change-event on-param-change-event}
                     :text {:fx/type modulator-param-text
                            :param-def param-def
                            :current-value (get modulator-config (:key param-def))
                            :on-change-event on-param-change-event}
                     ;; Default: float slider with text field
                     {:fx/type modulator-param-slider
                      :param-def param-def
                      :current-value (get modulator-config (:key param-def))
                      :on-change-event on-param-change-event
                      :on-text-event on-param-change-event}))))}))


;; Main Component


(defn modulator-param-control
  "Parameter control with modulator support.
   
   Displays either a static slider or modulator config based on current value.
   Includes toggle button to switch between static/modulated mode.
   
   Props:
   - :param-key - Parameter key (e.g., :x-scale)
   - :param-spec - Parameter specification {:min :max :default :type :label ...}
   - :current-value - Current value (number or modulator config map)
   - :on-change-event - Event template for static value changes
   - :on-text-event - Event template for text field changes
   - :modulator-event-base - Base event template for modulator events (contains :domain, :entity-key, :effect-path)
   - :label-width - Optional label width (default 80)"
  [{:keys [param-key param-spec current-value on-change-event on-text-event modulator-event-base label-width]}]
  (let [{:keys [min max label]} param-spec
         is-modulated? (mod-defs/active-modulator? current-value)
         ;; Check if there's an inactive modulator config we need to preserve
         has-inactive-modulator? (and (mod-defs/modulated? current-value)
                                      (not (mod-defs/active-modulator? current-value)))
         static-value (mod-defs/get-static-value current-value (:default param-spec))
         display-label (or label (name param-key))
         label-w (or label-width 80)
         is-int? (= :int (:type param-spec))
         ;; Build modulator event templates with full context
         toggle-event (assoc modulator-event-base
                             :event/type :modulator/toggle
                             :param-key param-key
                             :param-spec param-spec
                             :current-value current-value)
         set-type-event (assoc modulator-event-base
                               :event/type :modulator/set-type
                               :param-key param-key
                               :param-spec param-spec
                               :current-value current-value)
         update-mod-param-event (assoc modulator-event-base
                                       :event/type :modulator/update-param
                                       :param-key param-key
                                       :current-value current-value)
         retrigger-event (assoc modulator-event-base
                                :event/type :modulator/retrigger
                                :param-key param-key)
         ;; When there's an inactive modulator, use the special event that preserves the config
         static-change-event (if has-inactive-modulator?
                               (assoc modulator-event-base
                                      :event/type :modulator/update-static-value
                                      :param-key param-key
                                      :current-value current-value)
                               (assoc on-change-event :param-key param-key))]
     {:fx/type :v-box
      :spacing 4
      ;; Use filterv to remove nil values - the modulator params editor is only shown when modulated
      :children (filterv some?
                 [;; Header row with label, toggle, and value/type
                  {:fx/type :h-box
                   :spacing 8
                   :alignment :center-left
                   :children [;; Label
                              {:fx/type :label
                               :text display-label
                               :pref-width label-w
                               :style "-fx-text-fill: #B0B0B0; -fx-font-size: 11;"}
                              
                              ;; Modulate toggle button - uses event map
                               {:fx/type :toggle-button
                                :text "∿"
                                :selected is-modulated?
                                :style-class [(if is-modulated?
                                                "modulator-toggle-active"
                                                "modulator-toggle")]
                                :on-selected-changed toggle-event}
                              
                              ;; Value display (static) or type selector (modulated)
                              (if is-modulated?
                                {:fx/type modulator-type-selector
                                 :current-type (:type current-value)
                                 :on-select-event set-type-event}
                                ;; Static mode: slider + text field
                                {:fx/type :h-box
                                 :spacing 6
                                 :alignment :center-left
                                 :children [{:fx/type :slider
                                             :min min
                                             :max max
                                             :value (double static-value)
                                             :pref-width 120
                                             :snap-to-ticks is-int?
                                             :major-tick-unit (if is-int? 1.0 (/ (- max min) 10.0))
                                             ;; Use static-change-event to preserve inactive modulator config
                                             :on-value-changed static-change-event}
                                            {:fx/type :text-field
                                             :text (if is-int?
                                                     (str (int static-value))
                                                     (format "%.2f" (double static-value)))
                                             :pref-width 50
                                             :style "-fx-background-color: #404040; -fx-text-fill: white; -fx-font-size: 10; -fx-padding: 2 4;"
                                             ;; Text field also needs to preserve inactive modulator config
                                             :on-action (if has-inactive-modulator?
                                                          (assoc modulator-event-base
                                                                 :event/type :modulator/update-static-value
                                                                 :param-key param-key
                                                                 :current-value current-value)
                                                          (assoc on-text-event
                                                                 :param-key param-key
                                                                 :min min
                                                                 :max max
                                                                 :is-int? is-int?))}]})]}
                  
                  ;; Modulator parameters (only shown when modulated)
                  (when is-modulated?
                    {:fx/type modulator-params-editor
                     :modulator-config current-value
                     :on-param-change-event update-mod-param-event
                     :on-retrigger-event retrigger-event
                     :param-spec param-spec})])}))


;; Integration with param-controls-list


(defn modulatable-param-control
  "Like param-control but with modulator support for numeric types.
   
   Routes to modulator-param-control for :float/:int, otherwise delegates
   to the standard param-control from parameter-controls namespace.
   
   Props: same as modulator-param-control plus support for non-numeric types."
  [{:keys [param-spec] :as props}]
  (case (:type param-spec :float)
    ;; Numeric types: use modulator-capable control
    (:float :int) {:fx/type modulator-param-control
                   :param-key (:param-key props)
                   :param-spec param-spec
                   :current-value (:current-value props)
                   :on-change-event (:on-change-event props)
                   :on-text-event (:on-text-event props)
                   :modulator-event-base (:modulator-event-base props)
                   :label-width (:label-width props)}
    ;; Non-numeric types: delegate to standard param-control
    ;; This avoids duplicating the choice/bool/color rendering logic
    {:fx/type param-controls/param-control
     :param-key (:param-key props)
     :param-spec param-spec
     :current-value (:current-value props)
     :on-change-event (:on-change-event props)
     :on-text-event (:on-text-event props)
     :label-width (:label-width props)}))


(defn modulatable-param-controls-list
  "Like param-controls-list but with modulator support.
   
   Props:
   - :params-map - Map of param-key -> param-spec
   - :current-params - Map of param-key -> current-value (can include modulator configs)
   - :on-change-event - Event template for static value changes
   - :on-text-event - Event template for text field changes
   - :on-color-event - Event template for color picker changes (optional, auto-derived if not provided)
   - :modulator-event-base - Base event template for modulator events
   - :label-width - Optional label width
   - :spacing - Optional spacing between controls"
  [{:keys [params-map current-params on-change-event on-text-event on-color-event modulator-event-base label-width spacing]}]
  ;; Auto-derive color event from on-change-event if not explicitly provided
  ;; Color pickers use ActionEvent, so we need :chain/update-color-param handler
  (let [color-event (or on-color-event
                        (when on-change-event
                          (assoc on-change-event :event/type :chain/update-color-param)))]
    {:fx/type :v-box
     :spacing (or spacing 6)
     :children (vec
                (for [[param-key param-spec] params-map]
                  ;; Use color event for color params (ColorPicker fires ActionEvent, not color value)
                  (let [change-evt (if (= :color (:type param-spec))
                                     color-event
                                     on-change-event)]
                    {:fx/type modulatable-param-control
                     :param-key param-key
                     :param-spec param-spec
                     :current-value (get current-params param-key)
                     :on-change-event change-evt
                     :on-text-event on-text-event
                     :modulator-event-base modulator-event-base
                     :label-width label-width})))}))
