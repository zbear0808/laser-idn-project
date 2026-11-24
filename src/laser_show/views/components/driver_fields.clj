(ns laser-show.views.components.driver-fields
  "An output driver's settings, built from its field specs
   (plans/2026-11-19-output-drivers/01 §5.4): a text field for :host,
   :string and :int, a combo box for :choice, a check box for :boolean. Each
   control's id is driver-field-<key>, and a field's problem shows under it
   as driver-field-<key>-error. The Add Projector dialog and the projector
   settings both use it.

   Two modes:
   - :live (the dialog): every keystroke goes to the event, as typed, so Add
     can say at once whether the fields are usable; a text field owns its
     text from the value it was created with;
   - :commit (the projector settings): a text field applies on Enter or when
     it loses focus (commit-field), and only text driver/coerce-field takes;
     any other is put back."
  (:require [cljfx.api :as fx]
            [laser-show.output.driver :as driver]
            [laser-show.views.components.applied-value :as applied-value]
            [laser-show.views.components.commit-field :as commit-field])
  (:import [javafx.scene.control TextField]))

(defn- field-id [k]
  (str "driver-field-" (name k)))

(defn- text-of [v]
  (if (nil? v) "" (str v)))

(defn- control
  [{:keys [spec value mode event disable]}]
  (let [{k :key type :type :keys [options hint]} spec
        id (field-id k)
        with-value (fn [v] (assoc event :key k :value v))]
    (case type
      ;; A choice or a box the app refused (the show lock) shows what
      ;; applies again (applied-value)
      :choice {:fx/type applied-value/applied-control
               :property :value
               :address [event k]
               :value value
               :on-change with-value
               :desc {:fx/type :combo-box
                      :id id
                      ;; as wide as its column, as the text fields are
                      :max-width Double/MAX_VALUE
                      :items (mapv first options)
                      :disable (boolean disable)
                      :button-cell (fn [v] {:text (str (or (some #(when (= v (first %)) (second %)) options) ""))})
                      :cell-factory {:fx/cell-type :list-cell
                                     :describe (fn [v] {:text (str (or (some #(when (= v (first %)) (second %)) options) ""))})}}}
      :boolean {:fx/type applied-value/applied-control
                :property :selected
                :address [event k]
                :value (true? value)
                :on-change with-value
                :desc {:fx/type :check-box
                       :id id
                       :disable (boolean disable)}}
      (if (= :live mode)
        ;; The field owns its text while it is typed into: a controlled
        ;; :text would put back an older value that arrives after a newer
        ;; keystroke (events run on the agent) and move the caret. It starts
        ;; with the value it was created for; another type remounts it.
        {:fx/type fx/ext-on-instance-lifecycle
         :on-created (let [initial (text-of value)]
                       (fn [^TextField field] (.setText field initial)))
         :desc {:fx/type :text-field
                :id id
                :prompt-text (or hint "")
                :pref-width 180
                :disable (boolean disable)
                :style-class ["text-field"]
                :on-text-changed (with-value :fx/event)}}
        {:fx/type commit-field/commit-field
         :id id
         :text (text-of value)
         :pref-width 150
         :disable disable
         :on-commit (fn [typed]
                      (let [result (driver/coerce-field spec typed)]
                        (when-not (contains? result :error)
                          (with-value typed))))}))))

(defn grid-cells
  "The cells of driver-fields' grid, the first field on grid row start-row:
   for a form that puts its own rows (Type, Name) in the same grid, so every
   label and control lines up. key (optional) is part of each cell's
   :fx/key, so another driver's fields are new controls. Props as
   driver-fields', and :start-row, :key."
  [{:keys [fields values errors mode event disable start-row key] :or {start-row 0}}]
  (vec
   (mapcat (fn [row {k :key :keys [label] :as spec}]
             (let [r (+ start-row (* 2 row))]
               (cond-> [{:fx/type :label
                         :fx/key [key k :label]
                         :grid-pane/row r
                         :grid-pane/column 0
                         :text (str label ":")
                         :min-width :use-pref-size
                         :style-class ["label-secondary"]}
                        {:fx/type control
                         :fx/key [key k :control]
                         :grid-pane/row r
                         :grid-pane/column 1
                         :spec spec
                         :value (get values k)
                         :mode mode
                         :event event
                         :disable disable}]
                 (get errors k)
                 (conj {:fx/type :label
                        :fx/key [key k :error]
                        :grid-pane/row (inc r)
                        :grid-pane/column 1
                        :id (str (field-id k) "-error")
                        :text (get errors k)
                        :style-class ["label-error"]}))))
           (range)
           fields)))

(defn driver-fields
  "One row per field spec: its label, its control and, when it has one, its
   problem under the control, the controls lined up in one column.

   Props:
   - :fields - the driver's field specs
   - :values - {key value} each control shows (typed text in :live mode)
   - :errors - {key text}, shown under the fields (optional)
   - :mode - :live or :commit
   - :event - the event each change dispatches, given :key and :value
     (:fx/event in :live mode, or for a combo box or check box)
   - :disable - every control off (optional)"
  [props]
  {:fx/type :grid-pane
   :hgap 8
   :vgap 4
   :children (grid-cells props)})
