(ns laser-show.views.components.parameter-controls
  "Unified parameter control components for effects and presets.
   
   All controls use an event-template pattern for flexible event routing.
   Event templates are maps that get merged with parameter-specific keys
   like :param-key when the control fires an event.
   
   Supported parameter types:
   - :float / :int - Numeric slider with text field
   - :bool - Checkbox
   - :choice - Combo box dropdown (its :labels name the choices)
   - :ref, :ref-list, :map-list - Where a zone pattern takes its picture
     from, its places, a Map cycle's maps (zones plan Z5): chips and the
     Target menu's rows
   - :step-list - A step grid's steps (zones plan Z6): how many
   
   Note: Color parameters are now handled as separate :red, :green, :blue floats
   for modulator support. Use param-color-picker (or color-picker, without its
   label) when you need a unified color picker interface that reads/writes
   these separate RGB params; it sends the picked colour as plain data.
   
   Usage:
   ```clojure
   {:fx/type param-control
    :param-key :x-scale
    :param-spec {:min 0.0 :max 2.0 :default 1.0 :type :float}
    :current-value 1.5
    :on-change-event {:event/type :my/update-param}}
   ```"
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.animation.modulator-registry :as reg]
            [laser-show.events.core :as events]
            [laser-show.routing.targets :as targets]
            [laser-show.subs :as subs]
            [laser-show.views.components.applied-value :as applied-value]
            [laser-show.views.components.commit-field :as commit-field]
            [laser-show.views.components.gesture :as gesture]
            [laser-show.views.components.target-control :as target-control])
  (:import [javafx.event ActionEvent]
           [javafx.scene.control ColorPicker]
           [javafx.scene.paint Color]))


;; Parameter Spec Conversion


(defn params-vector->map
  "Convert effect/preset parameters from vector format (registry) to map format (UI).
   
   Registry format: [{:key :x-scale :default 1.0 :min 0.0 :max 2.0} ...]
   UI format: {:x-scale {:key :x-scale :default 1.0 :min 0.0 :max 2.0} ...}
   
   This makes it easier to look up parameter specs by key in the UI.
   Note: The :key field is preserved in the spec for convenience. An array
   map, so the lists show the parameters in their order however many there
   are (a zone pattern has 13)."
  [params-vector]
  (apply array-map (mapcat (juxt :key identity) params-vector)))


;; Basic Parameter Controls


(defn number-field
  "The text field beside a numeric parameter's slider, showing text. On
   Enter, or when it loses focus, it dispatches event with what was typed as
   :text (commit-field reads it on the FX thread), which the handler parses
   and clamps (events.helpers/parse-number-text); text that sets nothing is
   put back. Props: :id, :text, :event, :pref-width, :style."
  [{:keys [id text event pref-width style]}]
  {:fx/type commit-field/commit-field
   :id id
   :text text
   :pref-width pref-width
   :style-class ["text-field" "param-text-field"]
   :style style
   :on-commit (fn [typed] (assoc event :text typed))})

(defn param-slider
  "Slider control for numeric parameters with editable text field.
   
   Supports both :float and :int parameter types.
   Int parameters use snap-to-ticks and display as integers.
   
   Defensively handles modulator config maps by extracting static values.
   This can happen in edge cases where keyframes/modulation state gets out of sync.
   
   The slider and the field show the value that applies after a change the
   app refused (the show lock) too: applied-value, commit-field.
   
   Props:
   - :param-key - Parameter key (e.g., :x-scale)
   - :param-spec - Parameter specification {:min :max :default :type :label ...}
   - :current-value - Current parameter value (should be numeric, but handles maps defensively)
   - :on-change-event - Event template with :param-key and :fx/event added for slider changes
   - :on-text-event - Event template with :param-key, :min, :max, :is-int?
     and the typed :text added for the text field (number-field)
   - :label-width - Optional label width (default 80)"
  [{:keys [param-key param-spec current-value on-change-event on-text-event label-width]}]
  (let [{:keys [min max label]} param-spec
        ;; Defensive: extract static value if passed a modulator config map
        ;; This shouldn't happen in normal operation (keyframes normalize params),
        ;; but protects against edge cases that would cause ClassCastException
        value (cond
                (number? current-value) current-value
                (reg/modulated? current-value) (reg/get-static-value current-value (:default param-spec))
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
                ;; A drag is one press, one undo step (reactivity plan 16):
                ;; gesture/slider sends its changes; after one the app refused
                ;; (the show lock), it shows the value that applies (applied-value)
                {:fx/type applied-value/applied-control
                 :property :value
                 :address [on-change-event param-key]
                 :value (double value)
                 :desc {:fx/type gesture/slider
                        :id (str "param-" (name param-key) "-slider")
                        :min min
                        :max max
                        :pref-width 150
                        :snap-to-ticks is-int?
                        :major-tick-unit (if is-int? 1.0 (/ (- max min) 10.0))
                        :gesture {:label (str "Adjust " display-label)
                                  :source-id [:slider param-key]
                                  :on-value-changed (assoc on-change-event :param-key param-key)}}}
                {:fx/type number-field
                 :id (str "param-" (name param-key) "-field")
                 :text (if is-int?
                         (str (int value))
                         (format "%.2f" (double value)))
                 :pref-width 55
                 :style "-fx-font-size: 11; -fx-padding: 2 4;"
                 :event (assoc on-text-event
                               :param-key param-key
                               :min min
                               :max max
                               :is-int? is-int?)}]}))

(defn choice-text
  "A choice as its param says it: its :labels' text, else its name."
  [param-spec item]
  (if (some? item) (get (:labels param-spec) item (name item)) ""))

(defn param-choice
  "Combo-box for choice parameters; after a choice the app refused (the
   show lock), it shows the one that applies (applied-value).
   
   Props:
   - :param-key - Parameter key
   - :param-spec - Parameter specification with :choices vector, optional
     :label, and optional :labels {choice text} (else the choice's name)
   - :current-value - Current parameter value
   - :on-change-event - Event template with :param-key added
   - :label-width - Optional label width (default 80)
   - :id - Optional id of the combo box (default param-<key>-choice)"
  [{:keys [param-key param-spec current-value on-change-event label-width id]}]
  (let [{:keys [choices label labels]} param-spec
        value (if (keyword? current-value) current-value (:default param-spec))
        display-label (or label (name param-key))
        label-w (or label-width 80)
        text #(choice-text param-spec %)]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type :label
                 :text display-label
                 :pref-width label-w
                 :style-class ["label-secondary"]}
                {:fx/type applied-value/applied-control
                 :property :value
                 :address [on-change-event param-key]
                 :value value
                 ;; no choice (the items changed under it) is no edit
                 :on-change (fn [v] (when (some? v) (assoc on-change-event :param-key param-key :fx/event v)))
                 :desc {:fx/type :combo-box
                        :id (or id (str "param-" (name param-key) "-choice"))
                        :items (vec (or choices []))
                        :pref-width (if labels 180 150)
                        :button-cell (fn [item] {:text (text item)})
                        :cell-factory {:fx/cell-type :list-cell
                                       :describe (fn [item] {:text (text item)})}}}]}))

(defn param-checkbox
  "Checkbox for boolean parameters (#param-<key>-check, without a ?);
   after a change the app refused (the show lock), it shows the value that
   applies (applied-value).
   
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
     :children [{:fx/type applied-value/applied-control
                 :property :selected
                 :address [on-change-event param-key]
                 :value value
                 :on-change (fn [on?] (assoc on-change-event :param-key param-key :fx/event on?))
                 :desc {:fx/type :check-box
                        :id (str "param-" (str/replace (name param-key) "?" "") "-check")
                        :text display-label}}]}))

(defn- normalized-color
  "The colour of red, green and blue, each clamped to 0.0-1.0: a number, or
   a modulator's static value (reg/get-static-value; 1.0 for none)."
  ^Color [red green blue]
  (let [c (fn [v] (let [s (reg/get-static-value v 1.0)]
                    (max 0.0 (min 1.0 (if (number? s) (double s) 1.0)))))]
    (Color/color (c red) (c green) (c blue))))

(defn- send-picked-color!
  "A colour picker's action, its user's pick (a square of its palette, or
   Use or Save in its custom colour dialog): event with the picker's colour
   as plain data, :color [red green blue], read here on the FX thread."
  [event ^ActionEvent e]
  (let [^Color c (.getValue ^ColorPicker (.getSource e))]
    (when c
      (events/dispatch! (assoc event :color [(.getRed c) (.getGreen c) (.getBlue c)])))))

(defn color-picker
  "A colour picker showing the colour of :red, :green and :blue in
   :current-params (normalized, 0.0-1.0). The user's pick sends :event
   with that colour as :color [red green blue] (send-picked-color!): plain
   data, never the picker or its ActionEvent. A colour it shows while its
   palette or custom colour dialog is open is a preview, not an edit. After
   a pick the app refused, or that changed nothing, it shows the colour
   that applies again (applied-value).
   Props: :current-params, :event, and the :color-picker's own (:id,
   :style, :pref-width)."
  [{:keys [current-params event] :as props}]
  (let [{:keys [red green blue]} current-params]
    {:fx/type applied-value/applied-control
     :property :value
     :address [event :color]
     :value (normalized-color red green blue)
     :desc (-> props
               (dissoc :current-params :event :fx/context)
               (assoc :fx/type :color-picker
                      :on-action #(send-picked-color! event %)))}))

(defn param-color-picker
  "Color picker for RGB color parameters (color-picker), with its label.
   
   Reads color from separate :red, :green, :blue values in current-params map
   with NORMALIZED 0.0-1.0 components.
   
   Props:
   - :current-params - Map containing :red, :green, :blue values (0.0-1.0)
   - :on-change-event - Event template for color changes (no :param-key
     needed): sent with the picked :color [red green blue]
   - :label-width - Optional label width (default 80)"
  [{:keys [current-params on-change-event label-width]}]
  {:fx/type :h-box
   :spacing 8
   :alignment :center-left
   :children [{:fx/type :label
               :text "Color"
               :pref-width (or label-width 80)
               :style-class ["label-secondary"]}
              {:fx/type color-picker
               :id "param-color-picker"
               :current-params current-params
               :event on-change-event
               :pref-width 100}]})


;; Refs: where a zone pattern moves pictures (zones plan Z5)


(defn- send-value!
  "on-change-event with param-key and the value v. A click's event map would
   carry :fx/event, which :chain/update-param reads before :value, so a menu
   item or a × sends it from a function."
  [on-change-event param-key v]
  (events/dispatch! (assoc on-change-event :param-key param-key :value v)))

(defn- ref-menu-items
  "The Target menu's rows (target-control/menu-model) as menu items, a
   click calling (on-pick target)."
  [rows on-pick]
  (into []
        (keep (fn [{:keys [kind text checked? target]}]
                (case kind
                  :header {:fx/type :menu-item :text text :disable true :style-class ["menu-item" "target-menu-header"]}
                  :separator {:fx/type :separator-menu-item}
                  (:ref :zone :group) {:fx/type :menu-item
                                       :text (str (if checked? "✓ " "   ") text)
                                       :on-action (fn [_] (on-pick target))}
                  nil)))
        rows))

(defn place-text
  "A place of a lane, as its chip says it: \"Every laser\", a zone and
   where it goes under zone-map (\"A → Left\"), a group by name."
  [names zone-map ref]
  (cond
    (= :lane/lasers ref) "Every laser"
    (= :any ref) "Everything the cue draws"
    (targets/zone-ref? ref) (targets/target-text names zone-map ref)
    :else (targets/ref-name names ref)))

(defn- refs-of [value default]
  (vec (distinct (filter keyword? (if (sequential? value) value default)))))

(defn param-ref-list
  "A zone pattern's places, in order (:ref-list): a chip per place, its ×
   taking it out, then + ▾, the Target menu's :pattern rows (Every laser,
   All, the zones, the groups), a click putting a place in or taking it
   out. With :pair? (Swap) two pickers instead, the lane's first two places.
   Props: param-control's, and :pair?."
  [{:keys [fx/context param-key param-spec current-value on-change-event label-width pair?]}]
  (let [refs (refs-of current-value (:default param-spec))
        {:keys [names zone-map] :as ctx} (target-control/menu-context context)
        rows #(target-control/menu-model (assoc ctx :scope :pattern :lane %))
        send! #(send-value! on-change-event param-key %)
        text #(place-text names zone-map %)
        label {:fx/type :label
               :text (or (:label param-spec) (name param-key))
               :pref-width (or label-width 80)
               :min-width :use-pref-size
               :style-class ["label-secondary"]}]
    {:fx/type :h-box
     :id "zone-pattern-lane"
     :spacing 8
     :alignment :center-left
     :children
     (if pair?
       (let [[a b] [(get refs 0 :zone/a) (get refs 1 :zone/b)]
             picker (fn [i ref]
                      {:fx/type :menu-button
                       :id (str "zone-pattern-pair-" i)
                       :text (text ref)
                       :focus-traversable false
                       :min-width :use-pref-size
                       :style-class ["menu-button" "target-chip" "zone-pattern-place"]
                       :items (ref-menu-items (rows [ref]) #(send! (assoc (into refs (drop (count refs)) [:zone/a :zone/b]) i %)))})]
         [label (picker 0 a) {:fx/type :label :text "⇄" :style-class ["label" "zone-pattern-arrow"]} (picker 1 b)])
       [label
        {:fx/type :flow-pane
         :h-box/hgrow :always
         :hgap 6
         :vgap 4
         :alignment :center-left
         :children (conj (vec (map-indexed
                               (fn [i ref]
                                 {:fx/type :h-box
                                  :alignment :center-left
                                  :spacing 2
                                  :style-class ["zone-pattern-place"]
                                  :children [{:fx/type :label :text (text ref) :style-class ["label" "zone-pattern-place-name"]}
                                             {:fx/type :button
                                              :id (str "zone-pattern-place-remove-" i)
                                              :text "×"
                                              :focus-traversable false
                                              :style-class ["button" "zone-pattern-place-remove"]
                                              :tooltip {:fx/type :tooltip :text (str "Take " (text ref) " out")}
                                              :on-action (fn [_] (send! (into (subvec refs 0 i) (subvec refs (inc i)))))}]})
                               refs))
                         {:fx/type :menu-button
                          :id "zone-pattern-lane-add"
                          :text "+"
                          :focus-traversable false
                          :style-class ["menu-button" "target-chip" "zone-pattern-place-add"]
                          :tooltip {:fx/type :tooltip :text "Put a place in, or take one out"}
                          :items (ref-menu-items (rows refs)
                                                 #(send! (if (some #{%} refs) (filterv (fn [r] (not= r %)) refs) (conj refs %))))})}])}))

(defn param-ref
  "Where a pattern takes its picture from (:ref): everything the cue draws
   (:any), All, a zone or a group, from a menu shaped like the Target chip."
  [{:keys [fx/context param-key param-spec current-value on-change-event label-width]}]
  (let [ref (if (keyword? current-value) current-value (:default param-spec))
        {:keys [names zone-map] :as ctx} (target-control/menu-context context)
        rows (into [{:kind :ref :ref :any :text "Everything the cue draws" :checked? (= :any ref) :target :any}]
                   (remove #(= :lane/lasers (:ref %)))
                   (target-control/menu-model (assoc ctx :scope :pattern :lane [ref])))]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type :label
                 :text (or (:label param-spec) (name param-key))
                 :pref-width (or label-width 80)
                 :style-class ["label-secondary"]}
                {:fx/type :menu-button
                 :id (str "zone-pattern-" (name param-key))
                 :text (place-text names zone-map ref)
                 :focus-traversable false
                 :min-width :use-pref-size
                 :style-class ["menu-button" "target-chip"]
                 :items (ref-menu-items rows #(send-value! on-change-event param-key %))}]}))

(defn param-map-list
  "A Map cycle's maps, in order (:map-list): a chip per map, its × taking it
   out, then + ▾, every map of the show, a click putting one in or taking
   it out."
  [{:keys [fx/context param-key param-spec current-value on-change-event label-width]}]
  (let [ids (refs-of current-value (:default param-spec))
        maps (fx/sub-ctx context subs/zone-maps-list)
        by-id (into {} (map (juxt :id identity)) maps)
        send! #(send-value! on-change-event param-key %)
        map-name #(or (:name (by-id %)) (str (name %) " (missing)"))]
    {:fx/type :h-box
     :id "map-cycle-maps"
     :spacing 8
     :alignment :center-left
     :children [{:fx/type :label
                 :text (or (:label param-spec) (name param-key))
                 :pref-width (or label-width 80)
                 :min-width :use-pref-size
                 :style-class ["label-secondary"]}
                {:fx/type :flow-pane
                 :h-box/hgrow :always
                 :hgap 6
                 :vgap 4
                 :alignment :center-left
                 :children (conj (vec (map-indexed
                                       (fn [i id]
                                         {:fx/type :h-box
                                          :alignment :center-left
                                          :spacing 2
                                          :style-class ["zone-pattern-place"]
                                          :children [{:fx/type :label :text (map-name id) :style-class ["label" "zone-pattern-place-name"]}
                                                     {:fx/type :button
                                                      :id (str "map-cycle-remove-" i)
                                                      :text "×"
                                                      :focus-traversable false
                                                      :style-class ["button" "zone-pattern-place-remove"]
                                                      :on-action (fn [_] (send! (into (subvec ids 0 i) (subvec ids (inc i)))))}]})
                                       ids))
                                 {:fx/type :menu-button
                                  :id "map-cycle-add"
                                  :text "+"
                                  :focus-traversable false
                                  :style-class ["menu-button" "target-chip" "zone-pattern-place-add"]
                                  :items (mapv (fn [{:keys [id name]}]
                                                 (let [in? (some #{id} ids)]
                                                   {:fx/type :menu-item
                                                    :text (str (if in? "✓ " "   ") name)
                                                    :on-action (fn [_] (send! (if in? (filterv #(not= id %) ids) (conj ids id))))}))
                                               maps)})}]}))


;; A step grid's steps (zones plan Z6)


(defn step-count-text
  "How many steps a step grid has, as the numeric list says it: \"8
   steps\", \"1 step\", \"No steps yet\"."
  [steps]
  (let [n (if (sequential? steps) (count steps) 0)]
    (case n
      0 "No steps yet"
      1 "1 step"
      (str n " steps"))))

(defn param-step-list
  "A step grid's steps (:step-list), in the numeric list: how many there
   are, and Edit grid… to draw them in the step grid window (its target is
   on-change-event's :domain, :entity-key and :effect-instance-id)."
  [{:keys [param-key param-spec current-value on-change-event label-width]}]
  {:fx/type :h-box
   :spacing 8
   :alignment :center-left
   :children [{:fx/type :label
               :text (or (:label param-spec) (name param-key))
               :pref-width (or label-width 80)
               :min-width :use-pref-size
               :style-class ["label-secondary"]}
              {:fx/type :label
               :id "zone-pattern-step-count"
               :text (step-count-text current-value)
               :style-class ["label"]}
              {:fx/type :button
               :id "zone-pattern-edit-grid"
               :text "Edit grid…"
               :focus-traversable false
               :style-class ["button-secondary"]
               :on-action {:event/type :step-grid/open
                           :target (select-keys on-change-event [:domain :entity-key :effect-instance-id])}}]})


;; Parameter Control Router


(defn param-control
  "Render appropriate control based on parameter type.
   
   Routes to param-slider, param-choice, or param-checkbox
   based on the :type field in param-spec.
   
   Note: Color parameters are handled as separate :red, :green, :blue floats.
   Use param-color-picker directly for a unified color picker interface.
   
   Props:
   - :param-key - Parameter key
   - :param-spec - Parameter specification with :type (:float :int :bool
     :choice, zones plan Z5's :ref :ref-list :map-list, and Z6's
     :step-list)
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
    (:ref :ref-list :map-list) {:fx/type (case (:type param-spec)
                                           :ref param-ref
                                           :ref-list param-ref-list
                                           :map-list param-map-list)
                                :param-key (:param-key props)
                                :param-spec param-spec
                                :current-value (:current-value props)
                                :on-change-event (:on-change-event props)
                                :label-width (:label-width props)}
    :step-list {:fx/type param-step-list
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
