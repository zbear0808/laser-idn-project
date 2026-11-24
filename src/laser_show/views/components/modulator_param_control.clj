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
   ```
   
   Also provides visual-editor-modulator-header for embedding modulator controls
   directly in single-parameter visual editors (rotation dial, hue slider, etc).
   
   A control that edits one field of a parameter's modulator (its type, a
   field, or the static value while it is off) sends that field only, for
   the effect named by its instance (field-event): the handler edits what is
   stored now, so two edits from one render both stay (reactivity plan 07).
   :current-value is what the controls show. After a change the app refused
   (the show lock) or that changed nothing, each control shows the value
   that applies again (applied-value; the text fields, commit-field)."
  (:require [clj-font-awesome.core :as fa]
            [clojure.string :as str]
            [laser-show.animation.modulator-registry :as reg]
            [laser-show.views.components.applied-value :as applied-value]
            [laser-show.views.components.commit-field :as commit-field]
            [laser-show.views.components.gesture :as gesture]
            [laser-show.views.components.parameter-controls :as param-controls]))


;; UI Components


(defn- field-event
  "What a control that edits one field of a parameter's modulator sends
   (event-type :modulator/set-type, :modulator/update-param or
   :modulator/update-static-value) for param-key: base's address of the
   effect, its :domain, :entity-key and instance (:effect-instance-id, and
   :owner-id for an effect an item, a clip or a track owns), and :dialog-id
   when the controls are a window's. Never the modulator as rendered, nor
   the effect's place: another edit may change either before this one is
   handled."
  [base event-type param-key]
  (assoc (select-keys base [:domain :entity-key :effect-instance-id :owner-id :dialog-id])
         :event/type event-type
         :param-key param-key))

(defn- modulator-cell-desc
  "Build cell description for a modulator item.
   Handles both string icons (emoji) and FontAwesome icon-name keywords."
  [item]
  (when item
    (if-let [icon-name (:icon-name item)]
      ;; FontAwesome icon-name keyword - create graphic with optional style
      (let [icon-style (or (:icon-style item) :solid)]
        {:text (:name item)
         :graphic {:fx/type fa/icon :name icon-name :style icon-style :size 12}})
      ;; String icon (emoji) - include in text
      {:text (str (:icon item) " " (:name item))})))

(defn modulator-choices
  "The modulator types the dropdown offers in an editor of domain: every UI
   type, but the :pulse ones (Envelope, Step, Energy) for calibration
   (:projector-effects), which never sees a press."
  [domain]
  (cond->> (reg/all-ui-modulators)
    (= :projector-effects domain) (remove #(= :pulse (:category %)))))

(defn- modulator-type-selector
  "Dropdown to select modulator type; after a type the app refused (the
   show lock), it shows the one that applies (applied-value).
   
   Props:
   - :current-type - Current modulator type keyword
   - :on-select-event - Event template for type selection, sent with the
     selected type's id as :mod-type; its :domain picks the types
     (modulator-choices), its :param-key names it (id modulator-type-<param>)"
  [{:keys [current-type on-select-event]}]
  (let [all-types (modulator-choices (:domain on-select-event))
        current-info (reg/get-modulator current-type)]
    {:fx/type applied-value/applied-control
     :property :value
     :address [on-select-event :type]
     :value (or current-info (first all-types))
     ;; The item is the registry's entry (its evaluator too): send its id
     :on-change (fn [item] (when-let [id (:id item)] (assoc on-select-event :mod-type id)))
     :desc {:fx/type :combo-box
            :id (str "modulator-type-" (some-> (:param-key on-select-event) name))
            :pref-width 140
            :items (vec all-types)
            :button-cell (fn [item] (modulator-cell-desc item))
            :cell-factory {:fx/cell-type :list-cell
                           :describe (fn [item] (modulator-cell-desc item))}}}))

(defn- param-id
  "A modulator param control's id: modulator-<param>-<modulator param>."
  [on-change-event key]
  (str "modulator-" (some-> (:param-key on-change-event) name) "-" (name key)))

(defn- modulator-param-slider
  "Slider for a single modulator parameter with editable text field; both
   show the value that applies after a change the app refused (the show
   lock) too: applied-value, commit-field.
   
   Uses same pattern as parameter-controls/param-slider for consistency.
   
   Props:
   - :param-def - Parameter definition map with :key, :label, :min, :max, :default
   - :current-value - Current value for this parameter
   - :on-change-event - Event template for slider value changes (receives :mod-param-key)
   - :on-text-event - Event template for the text field (receives
     :mod-param-key, :mod-text-field? true, its bounds and the typed :text:
     param-controls/number-field)"
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
                ;; A drag is one press, one undo step (reactivity plan 16):
                ;; gesture/slider sends its value events (the template with
                ;; mod-param-key); after one the app refused (the show lock),
                ;; it shows the value that applies (applied-value)
                {:fx/type applied-value/applied-control
                 :property :value
                 :address [on-change-event key]
                 :value (double value)
                 :desc {:fx/type gesture/slider
                        :id (param-id on-change-event key)
                        :min min
                        :max max
                        :pref-width 80
                        :gesture {:label (str "Adjust " (or label (name key)))
                                  :source-id [:modulator-slider key]
                                  :on-value-changed (assoc on-change-event :mod-param-key key)}}}
                {:fx/type param-controls/number-field
                 :id (str (param-id on-change-event key) "-field")
                 :text (format "%.2f" (double value))
                 :pref-width 50
                 :style "-fx-font-size: 10; -fx-padding: 2 4;"
                 ;; Text field event includes mod-param-key and text field marker
                 :event (assoc text-event
                               :mod-param-key key
                               :mod-param-min min
                               :mod-param-max max)}]}))

(defn- modulator-param-choice
  "Choice dropdown for a modulator parameter; after a choice the app
   refused, it shows the one that applies (applied-value).
   
   Props:
   - :param-def - Parameter definition map with :key, :label, :choices, :default,
     and optionally :labels {choice text} (else the choice's name)
   - :current-value - Current value for this parameter
   - :on-change-event - Event template for value changes (receives :mod-param-key)"
  [{:keys [param-def current-value on-change-event]}]
  (let [{:keys [key label choices default labels]} param-def
        value (or current-value default)
        text (fn [item] (when item (get labels item (name item))))]
    {:fx/type :h-box
     :spacing 6
     :alignment :center-left
     :children [{:fx/type :label
                 :text label
                 :pref-width 90
                 :style-class ["text-small"]
                 :style "-fx-text-fill: #909090;"}
                {:fx/type applied-value/applied-control
                 :property :value
                 :address [on-change-event key]
                 :value value
                 :on-change (fn [v] (when (some? v) (assoc on-change-event :mod-param-key key :fx/event v)))
                 :desc {:fx/type :combo-box
                        :id (param-id on-change-event key)
                        :items (vec choices)
                        :pref-width (if labels 170 100)
                        :button-cell (fn [item] {:text (text item)})
                        :cell-factory {:fx/cell-type :list-cell
                                       :describe (fn [item] {:text (text item)})}}}]}))

(defn- number-list-text
  "A palette as it is typed: \"200 215 320\"."
  [values]
  (str/join " " (map (fn [v] (let [d (double v)]
                               (if (== d (Math/rint d)) (str (long d)) (str d))))
                     values)))

(defn- modulator-param-number-list
  "Text field for a list of numbers (the Step's palette), typed as \"200 215
   320\" (commas too). Enter, or leaving the field, sends what was typed as
   :text (commit-field reads it on the FX thread); empty clears it.
   
   Props:
   - :param-def - Parameter definition map with :key, :label
   - :current-value - The numbers
   - :on-change-event - Event template for value changes (receives :mod-param-key)"
  [{:keys [param-def current-value on-change-event]}]
  (let [{:keys [key label]} param-def
        event (assoc on-change-event :mod-param-key key :text-field? true)]
    {:fx/type :h-box
     :spacing 6
     :alignment :center-left
     :children [{:fx/type :label
                 :text label
                 :pref-width 90
                 :style-class ["text-small"]
                 :style "-fx-text-fill: #909090;"}
                {:fx/type commit-field/commit-field
                 :id (param-id on-change-event key)
                 :text (number-list-text current-value)
                 :prompt-text "200 215 320"
                 :pref-width 140
                 :style-class ["text-field" "param-text-field"]
                 :style "-fx-font-size: 10; -fx-padding: 2 4;"
                 :on-commit (fn [typed] (assoc event :text typed))}]}))

(defn- modulator-param-text
  "Text field for a modulator parameter (e.g., step values). Enter, or
   leaving the field, sends what was typed as :text (commit-field reads it
   on the FX thread).
   
   Props:
   - :param-def - Parameter definition map with :key, :label, :default
   - :current-value - Current value for this parameter
   - :on-change-event - Event template for value changes (receives :mod-param-key)"
  [{:keys [param-def current-value on-change-event]}]
  (let [{:keys [key label default]} param-def
        value (or current-value default)
        event (assoc on-change-event :mod-param-key key :text-field? true)]
    {:fx/type :h-box
     :spacing 6
     :alignment :center-left
     :children [{:fx/type :label
                 :text label
                 :pref-width 90
                 :style "-fx-text-fill: #909090; -fx-font-size: 10;"}
                {:fx/type commit-field/commit-field
                 :id (param-id on-change-event key)
                 :text (str value)
                 :pref-width 100
                 :style-class ["text-field" "param-text-field"]
                 :style "-fx-background-color: #404040; -fx-text-fill: white; -fx-font-size: 10;"
                 :on-commit (fn [typed] (assoc event :text typed))}]}))

(defn- modulator-param-boolean
  "Checkbox for a boolean modulator parameter; after a change the app
   refused, it shows the value that applies (applied-value).
   
   Props:
   - :param-def - Parameter definition map with :key, :label, :default
   - :current-value - Current value for this parameter
   - :on-change-event - Event template for value changes (receives :mod-param-key)"
  [{:keys [param-def current-value on-change-event]}]
  (let [{:keys [key label default]} param-def
        value (if (some? current-value) current-value default)]
    {:fx/type :h-box
     :spacing 6
     :alignment :center-left
     :children [{:fx/type :label
                 :text label
                 :pref-width 90
                 :style-class ["text-small"]
                 :style "-fx-text-fill: #909090;"}
                {:fx/type applied-value/applied-control
                 :property :selected
                 :address [on-change-event key]
                 :value value
                 :on-change (fn [on?] (assoc on-change-event :mod-param-key key :fx/event on?))
                 :desc {:fx/type :check-box
                        :id (param-id on-change-event key)}}]}))

(defn- modulator-params-editor
  "Editor for modulator-specific parameters.
   
   Props:
   - :modulator-config - Current modulator configuration map
   - :on-param-change-event - Event template for parameter changes; for
     calibration (:domain :projector-effects), which never sees a press,
     there is no Restart on
   - :on-retrigger-event - Event template for retrigger button (optional)
   - :param-spec - The original param spec (for getting min/max bounds)"
  [{:keys [modulator-config on-param-change-event on-retrigger-event param-spec]}]
  (let [mod-type (:type modulator-config)
        base-params (cond->> (reg/get-params mod-type)
                      (= :projector-effects (:domain on-param-change-event))
                      (remove #(= :restart-on (:key %))))
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
                            (reg/retrigger? mod-type))
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
                     :number-list {:fx/type modulator-param-number-list
                                   :param-def param-def
                                   :current-value (get modulator-config (:key param-def))
                                   :on-change-event on-param-change-event}
                     :boolean {:fx/type modulator-param-boolean
                               :param-def param-def
                               :current-value (get modulator-config (:key param-def))
                               :on-change-event on-param-change-event}
                     ;; Default: float slider with text field
                     {:fx/type modulator-param-slider
                      :param-def param-def
                      :current-value (get modulator-config (:key param-def))
                      :on-change-event on-param-change-event
                      :on-text-event on-param-change-event}))))}))


;; Visual Editor Modulator Header
;; Reusable component for embedding modulator controls in single-param visual editors


(defn- modulate-toggle
  "The ∿ toggle of param-key (#modulate-<param>): desc's toggle button
   (text, tooltip, style) on while modulated?, sending toggle-event with
   the state it was clicked to (:fx/event). After a click the app refused
   (the show lock) it shows the state that applies (applied-value)."
  [desc modulator-event-base param-key modulated? toggle-event]
  {:fx/type applied-value/applied-control
   :property :selected
   :address [modulator-event-base param-key :modulate]
   :value modulated?
   :on-change (fn [on?] (assoc toggle-event :fx/event on?))
   :desc (assoc desc :fx/type :toggle-button :id (str "modulate-" (name param-key)))})

(defn visual-editor-modulator-header
  "Modulator toggle header for visual editors.
  
  This component provides a compact modulator toggle and type selector
  that can be embedded in visual editors (rotation dial, hue slider, etc)
  so users don't need to switch to numeric mode to enable modulation.
  
  Props:
  - :param-key - Parameter key (e.g., :angle, :hue, :degrees)
  - :param-spec - Parameter specification map with :min, :max, :default, :label
  - :current-value - Current param value (number or modulator config map)
  - :modulator-event-base - Base event template for modulator operations
                            (must have :domain, :entity-key, :effect-path, and
                            the effect's :effect-instance-id for the type)"
  [{:keys [param-key param-spec current-value modulator-event-base]}]
  (let [is-modulated? (reg/active-modulator? current-value)
        display-label (or (:label param-spec) (name param-key))
        ;; Build modulator event templates
        toggle-event (assoc modulator-event-base
                            :event/type :modulator/toggle
                            :param-key param-key
                            :param-spec param-spec)
        set-type-event (assoc (field-event modulator-event-base :modulator/set-type param-key)
                              :param-spec param-spec)]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :style-class ["visual-editor-modulator-header"]
     :children (filterv some?
                        [;; Modulate toggle button
                         (modulate-toggle {:text (if is-modulated? "∿ Modulating" "∿ Modulate")
                                           :style-class [(if is-modulated?
                                                           "visual-editor-modulator-active"
                                                           "visual-editor-modulator-toggle")]}
                                          modulator-event-base param-key is-modulated? toggle-event)

                         ;; Type selector (only shown when modulated)
                         (when is-modulated?
                           {:fx/type modulator-type-selector
                            :current-type (:type current-value)
                            :on-select-event set-type-event})])}))


(defn visual-editor-modulator-params
  "Modulator parameters editor for visual editors.
  
  Shows the modulator-specific parameters (min, max, period, etc) when
  a visual editor's parameter has an active modulator.
  
  Props:
  - :param-key - Parameter key
  - :param-spec - Parameter specification map
  - :current-value - Current modulator config map
  - :modulator-event-base - Base event template for modulator operations"
  [{:keys [param-key param-spec current-value modulator-event-base]}]
  (let [update-mod-param-event (field-event modulator-event-base :modulator/update-param param-key)
        retrigger-event (assoc modulator-event-base
                               :event/type :modulator/retrigger
                               :param-key param-key)]
    {:fx/type modulator-params-editor
     :modulator-config current-value
     :on-param-change-event update-mod-param-event
     :on-retrigger-event retrigger-event
     :param-spec param-spec}))


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
   - :on-text-event - Event template for the text field, which adds
     :param-key, :min, :max, :is-int? and the typed :text
     (param-controls/number-field). While a modulator is off, the slider and
     the field set its static value instead (:modulator/update-static-value)
   - :modulator-event-base - Base event template for modulator events
     (contains :domain, :entity-key, :effect-path for the toggle, and the
     effect's :effect-instance-id, with :owner-id for an owned effect, for
     its fields: field-event)
   - :label-width - Optional label width (default 80)"
  [{:keys [param-key param-spec current-value on-change-event on-text-event modulator-event-base label-width]}]
  (let [{:keys [min max label]} param-spec
        is-modulated? (reg/active-modulator? current-value)
        ;; Check if there's an inactive modulator config we need to preserve
        has-inactive-modulator? (and (reg/modulated? current-value)
                                     (not (reg/active-modulator? current-value)))
        static-value (reg/get-static-value current-value (:default param-spec))
        display-label (or label (name param-key))
        label-w (or label-width 80)
        is-int? (= :int (:type param-spec))
        ;; Build modulator event templates with full context
        toggle-event (assoc modulator-event-base
                            :event/type :modulator/toggle
                            :param-key param-key
                            :param-spec param-spec)
        set-type-event (assoc (field-event modulator-event-base :modulator/set-type param-key)
                              :param-spec param-spec)
        update-mod-param-event (field-event modulator-event-base :modulator/update-param param-key)
        retrigger-event (assoc modulator-event-base
                               :event/type :modulator/retrigger
                               :param-key param-key)
        ;; When there's an inactive modulator, use the special event that preserves the config
        static-change-event (if has-inactive-modulator?
                              (field-event modulator-event-base :modulator/update-static-value param-key)
                              (assoc on-change-event :param-key param-key))
        ;; The field's typed text goes to the same inactive modulator, else on-text-event
        static-text-event (assoc (if has-inactive-modulator? static-change-event on-text-event)
                                 :param-key param-key
                                 :min min
                                 :max max
                                 :is-int? is-int?)]
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

                                     ;; Modulate toggle button
                                     (modulate-toggle {:text "∿"
                                                       :style-class [(if is-modulated?
                                                                       "modulator-toggle-active"
                                                                       "modulator-toggle")]
                                                       :tooltip {:fx/type :tooltip
                                                                 :text (if is-modulated? "Stop modulating" "Modulate")}}
                                                      modulator-event-base param-key is-modulated? toggle-event)

                                     ;; Value display (static) or type selector (modulated)
                                     (if is-modulated?
                                       {:fx/type modulator-type-selector
                                        :current-type (:type current-value)
                                        :on-select-event set-type-event}
                                       ;; Static mode: slider + text field, which show the
                                       ;; value that applies after a refused change too
                                       {:fx/type :h-box
                                        :spacing 6
                                        :alignment :center-left
                                        :children [{:fx/type applied-value/applied-control
                                                    :property :value
                                                    :address [static-change-event :static]
                                                    :value (double static-value)
                                                    ;; static-change-event preserves an inactive modulator config
                                                    :on-change (fn [v] (assoc static-change-event :fx/event v))
                                                    :desc {:fx/type :slider
                                                           :id (str "param-" (name param-key) "-slider")
                                                           :min min
                                                           :max max
                                                           :pref-width 120
                                                           :snap-to-ticks is-int?
                                                           :major-tick-unit (if is-int? 1.0 (/ (- max min) 10.0))}}
                                                   {:fx/type param-controls/number-field
                                                    :id (str "param-" (name param-key) "-field")
                                                    :text (if is-int?
                                                            (str (int static-value))
                                                            (format "%.2f" (double static-value)))
                                                    :pref-width 50
                                                    :style "-fx-background-color: #404040; -fx-text-fill: white; -fx-font-size: 10; -fx-padding: 2 4;"
                                                    :event static-text-event}]})]}

                         ;; Modulator parameters (only shown when modulated)
                         (when is-modulated?
                           {:fx/type modulator-params-editor
                            :modulator-config current-value
                            :on-param-change-event update-mod-param-event
                            :on-retrigger-event retrigger-event
                            :param-spec param-spec})])}))


;; A choice that follows a modulator (zones plan Z5, 07 S-8)


(defn- choice-value
  "What a choice param shows while static: its keyword, or for a modulator
   that is off, its static value, a choice or an index (as a modulator's
   value picks one: ⌊v + 1e-9⌋, clamped)."
  [{:keys [choices default]} v]
  (let [s (reg/get-static-value v default)
        n (count choices)]
    (cond
      (keyword? s) s
      (and (number? s) (pos? n)) (nth (vec choices) (max 0 (min (dec n) (long (Math/floor (+ (double s) 1e-9))))))
      :else default)))

(defn index-spec
  "A modulatable choice as a number spec: its indices 0 to n − 1, so a new
   modulator spans every choice, and Min and Max stay in range."
  [{:keys [choices] :as param-spec} v]
  (let [choices (vec choices)
        i (max 0 (.indexOf ^java.util.List choices (choice-value param-spec v)))]
    (assoc param-spec :type :float :min 0.0 :max (double (max 0 (dec (count choices)))) :default (double i))))

(defn index-labels
  "\"0 Alternate · 1 Chase · …\": the choice each index picks."
  [param-spec]
  (str/join " · " (map-indexed (fn [i c] (str i " " (param-controls/choice-text param-spec c))) (:choices param-spec))))

(defn modulator-choice-control
  "A :choice that says :modulatable? (zones plan Z5): a combo box while
   static, and ∿ to follow a modulator, whose value picks a choice by index
   (animation.effects/choice-at): its Min and Max are indices, labelled
   \"0 Alternate · 1 Chase …\", and a new one spans every choice. Props:
   modulator-param-control's."
  [{:keys [param-key param-spec current-value on-change-event modulator-event-base label-width]}]
  (let [modulated? (reg/active-modulator? current-value)
        inactive? (and (reg/modulated? current-value) (not modulated?))
        spec (index-spec param-spec current-value)
        event (fn [type & {:as more}] (merge modulator-event-base {:event/type type :param-key param-key} more))
        text #(param-controls/choice-text param-spec %)]
    {:fx/type :v-box
     :spacing 4
     :children (filterv some?
                        [{:fx/type :h-box
                          :spacing 8
                          :alignment :center-left
                          :children [{:fx/type :label
                                      :text (or (:label param-spec) (name param-key))
                                      :pref-width (or label-width 80)
                                      :style "-fx-text-fill: #B0B0B0; -fx-font-size: 11;"}
                                     (modulate-toggle {:text "∿"
                                                       :focus-traversable false
                                                       :style-class [(if modulated? "modulator-toggle-active" "modulator-toggle")]
                                                       :tooltip {:fx/type :tooltip :text (if modulated? "Stop modulating" "Follow a modulator")}}
                                                      modulator-event-base param-key modulated?
                                                      (event :modulator/toggle :param-spec spec))
                                     (if modulated?
                                       {:fx/type modulator-type-selector
                                        :current-type (:type current-value)
                                        :on-select-event (assoc (field-event modulator-event-base :modulator/set-type param-key)
                                                                :param-spec spec)}
                                       (let [choose (if inactive?
                                                      (field-event modulator-event-base :modulator/update-static-value param-key)
                                                      (assoc on-change-event :param-key param-key))]
                                         ;; after a choice the app refused, the one that applies
                                         {:fx/type applied-value/applied-control
                                          :property :value
                                          :address [choose :choice]
                                          :value (choice-value param-spec current-value)
                                          :on-change (fn [c] (when (some? c) (assoc choose :fx/event c)))
                                          :desc {:fx/type :combo-box
                                                 :id (str "choice-" (name param-key))
                                                 :items (vec (:choices param-spec))
                                                 :pref-width 150
                                                 :button-cell (fn [item] {:text (text item)})
                                                 :cell-factory {:fx/cell-type :list-cell :describe (fn [item] {:text (text item)})}}}))]}
                         (when modulated?
                           {:fx/type :label
                            :text (index-labels param-spec)
                            :wrap-text true
                            :padding {:left 12}
                            :style-class ["label" "label-hint" "choice-index-labels"]})
                         (when modulated?
                           {:fx/type modulator-params-editor
                            :modulator-config current-value
                            :on-param-change-event (field-event modulator-event-base :modulator/update-param param-key)
                            :on-retrigger-event (event :modulator/retrigger)
                            :param-spec spec})])}))


;; Integration with param-controls-list


(defn modulatable-param-control
  "Like param-control but with modulator support for numeric types.
   
   Routes to modulator-param-control for :float/:int and to
   modulator-choice-control for a :modulatable? :choice, otherwise delegates
   to the standard param-control from parameter-controls namespace.
   
   Props: same as modulator-param-control plus support for non-numeric types."
  [{:keys [param-spec] :as props}]
  (case (if (and (= :choice (:type param-spec)) (:modulatable? param-spec)) :modulatable-choice (:type param-spec :float))
    ;; A choice that may follow a modulator (zones plan Z5), given the props
    ;; without this component's context: cljfx gives it its own
    :modulatable-choice (assoc (dissoc props :fx/context) :fx/type modulator-choice-control)
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
