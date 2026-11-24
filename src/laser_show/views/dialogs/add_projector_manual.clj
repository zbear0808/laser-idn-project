(ns laser-show.views.dialogs.add-projector-manual
  "Dialog for adding a projector by hand: its Type (the installed output
   drivers, IDN first), then IDN's own Host and Port, or the chosen driver's
   fields (views/components/driver_fields). Add stays off until they can be
   used (subs/add-manual-form). Add sends no values: its handler
   (:projectors/submit-manual-form) adds what the form holds when it runs."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.subs :as subs]
            [laser-show.views.components.driver-fields :as driver-fields]
            [laser-show.views.dialogs.common :as common]))


;; Dialog Content


(defn- label-cell [row text]
  {:fx/type :label
   :fx/key [:label row]
   :grid-pane/row row
   :grid-pane/column 0
   :text text
   :min-width :use-pref-size
   :style-class ["label-secondary"]})

(defn- type-combo
  "The Type combo: every installed driver, by name."
  [types driver]
  (let [names (into {} (map (juxt :id :name)) types)
        text #(get names % (str %))]
    {:fx/type :combo-box
     :id "add-projector-driver-combo"
     :fx/key :type
     :grid-pane/row 0
     :grid-pane/column 1
     :items (mapv :id types)
     :value driver
     :pref-width 280
     :button-cell (fn [id] {:text (if id (text id) "")})
     :cell-factory {:fx/cell-type :list-cell
                    :describe (fn [id] {:text (if id (text id) "")})}
     :on-value-changed {:event/type :projectors/set-manual-driver :driver :fx/event}}))

(defn- idn-cells
  "IDN's Host and Port, as they always were, on grid rows row and row + 2."
  [row host-value port-value]
  [(label-cell row "Host:")
   {:fx/type :text-field
    :id "add-projector-host-field"
    :fx/key :idn-host
    :grid-pane/row row
    :grid-pane/column 1
    :text host-value
    :prompt-text "192.168.1.100"
    :pref-width 180
    :max-width 180
    :style-class ["text-field"]
    :on-text-changed {:event/type :ui/update-dialog-data
                      :dialog-id :add-projector-manual
                      :updates {:host :fx/event}}}
   (label-cell (+ 2 row) "Port:")
   {:fx/type :text-field
    :id "add-projector-port-field"
    :fx/key :idn-port
    :grid-pane/row (+ 2 row)
    :grid-pane/column 1
    :text port-value
    :prompt-text "7255"
    :pref-width 80
    :max-width 80
    :style-class ["text-field"]
    :on-text-changed {:event/type :ui/update-dialog-data
                      :dialog-id :add-projector-manual
                      :updates {:port :fx/event}}}])

(defn- shown-values
  "What each driver field shows: what was typed, else its default."
  [fields inputs]
  (into {} (map (fn [{k :key :as spec}]
                  [k (if (contains? inputs k) (get inputs k) (:default spec))]))
        fields))

(defn- add-projector-form
  "Content of the add projector dialog."
  [{:keys [fx/context]}]
  (let [dialog-data (fx/sub-ctx context subs/dialog-data :add-projector-manual)
        types (fx/sub-ctx context subs/add-manual-types)
        {:keys [driver fields inputs errors can-add? default-name]} (fx/sub-ctx context subs/add-manual-form)
        idn? (= :idn driver)
        name-value (or (:name dialog-data) "")
        host-value (or (:host dialog-data) "")
        port-value (or (:port dialog-data) "7255")]
    {:fx/type :v-box
     :spacing 16
     :padding 20
     :style-class ["container-primary"]
     :pref-width 400
     :children [{:fx/type :label
                 :text "Add Projector Manually"
                 :style-class ["header-primary"]}
                {:fx/type :label
                 :id "add-projector-intro"
                 :text "Choose the output type and enter its connection details."
                 :style-class ["label-secondary"]}
                {:fx/type :grid-pane
                 :id "add-projector-grid"
                 :hgap 8
                 ;; two rows each (as driver-fields/grid-cells, whose second is a
                 ;; field's problem), so every row is as far from the next
                 :vgap 6
                 :children (-> [(label-cell 0 "Type:")
                                (type-combo types driver)
                                (label-cell 2 "Name:")
                                {:fx/type :text-field
                                 :id "add-projector-name-field"
                                 :fx/key :name
                                 :grid-pane/row 2
                                 :grid-pane/column 1
                                 :text name-value
                                 :prompt-text (or default-name "My Projector")
                                 :pref-width 280
                                 :style-class ["text-field"]
                                 :on-text-changed {:event/type :ui/update-dialog-data
                                                   :dialog-id :add-projector-manual
                                                   :updates {:name :fx/event}}}]
                               (into (if idn?
                                       (idn-cells 4 host-value port-value)
                                       (driver-fields/grid-cells
                                        {:start-row 4
                                         ;; another type's fields are new controls, never this one's reused
                                         :key driver
                                         :fields fields
                                         :values (shown-values fields inputs)
                                         ;; a problem shows once something is typed into the field
                                         :errors (select-keys errors (keep (fn [[k v]] (when-not (str/blank? (str v)) k)) inputs))
                                         :mode :live
                                         :event {:event/type :projectors/set-manual-field}}))))}
                {:fx/type :h-box
                 :spacing 8
                 :alignment :center-right
                 :children [{:fx/type :button
                             :id "add-projector-cancel-button"
                             :text "Cancel"
                             :style-class ["button-secondary"]
                             :on-action {:event/type :ui/close-dialog
                                         :dialog-id :add-projector-manual}}
                            {:fx/type :button
                             :id "add-projector-confirm-button"
                             :text "Add Projector"
                             :style-class ["button-primary"]
                             :disable (not can-add?)
                             ;; No values: the handler adds the form as it is then
                             :on-action {:event/type :projectors/submit-manual-form}}]}]}))

(defn- add-projector-content
  "The form, in a window sized to it (common/fit-window-to-content!): another
   type has other fields, and a field's problem adds a line."
  [_]
  {:fx/type fx/ext-on-instance-lifecycle
   :on-created common/fit-window-to-content!
   :desc {:fx/type add-projector-form}})


;; Dialog Window


(defn add-projector-manual-dialog
  "The add projector dialog window."
  [_]
  {:fx/type common/dialog-stage
   :dialog-id :add-projector-manual
   :title "Add Projector"
   :content {:fx/type add-projector-content}})
