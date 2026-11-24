(ns laser-show.views.components.zone-maps-panel
  "ZONES AND MAPS, under the Zone Groups tab's three columns (zones plan Z4,
   06 §7.5): the show's default target, the zones' labels, and the maps: the
   built-ins (read only, they follow the rig) and yours, each with its lanes
   (All or a group each, never a zone), where each zone goes, its overrides
   and what is wrong with it (routing.zone-maps/map-warnings). Use now
   switches at once; Delete asks first (:zone-maps/request-delete)."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.routing.targets :as targets]
            [laser-show.subs :as subs]
            [laser-show.views.components.applied-value :as applied-value]
            [laser-show.views.components.commit-field :as commit-field]
            [laser-show.views.components.target-control :as target-control]))


(defn- id-text
  "The part of a map's or a zone's id that names its nodes: :map/odd-even
   \"odd-even\", :zone/a \"a\"."
  [id]
  (name id))


;; The show's default and the zones' labels

(defn- show-default
  "Show default target: where a cue goes when nothing in it, nor its
   column, says (:zones/set-default-target)."
  [{:keys [fx/context]}]
  {:fx/type :h-box
   :spacing 6
   :alignment :center-left
   :children [{:fx/type :label :text "Show default target:" :style-class ["label-secondary"]}
              {:fx/type target-control/target-chip
               :id "target-chip-show"
               :scope :show
               :own (fx/sub-ctx context subs/default-target)
               :on-set {:event/type :zones/set-default-target}
               :tooltip "Where a cue goes when nothing in it, nor its column, says where"}]})

(defn- zone-labels
  "A field per zone the tab lists (A, B and the show's), applied on Enter
   or when it loses focus; a blank one takes the label away."
  [{:keys [fx/context]}]
  (let [labels (fx/sub-ctx context subs/zone-labels)]
    {:fx/type :h-box
     :spacing 6
     :alignment :center-left
     :children (into [{:fx/type :label :text "Zone labels:" :style-class ["label-secondary"]}]
                     (mapcat (fn [zone]
                               [{:fx/type :label :text (targets/zone-letter zone) :style-class ["label-bold"]}
                                {:fx/type commit-field/commit-field
                                 :id (str "zone-label-" (id-text zone))
                                 :text (get labels zone "")
                                 :pref-width 90
                                 :on-commit (fn [typed] {:event/type :zones/set-label :zone zone :label typed})}]))
                     (fx/sub-ctx context subs/map-zones))}))


;; The maps

(defn- map-item
  "A map in the list: ● when it is the one in use, and whether it is built
   in."
  [{:keys [map selected? active?]}]
  {:fx/type :h-box
   :id (str "zone-map-item-" (id-text (:id map)))
   :style-class ["zone-map-item" (if selected? "list-item-selected" "list-item")]
   :spacing 6
   :alignment :center-left
   :padding {:top 4 :bottom 4 :left 6 :right 6}
   :on-mouse-clicked {:event/type :zone-maps/select :map (:id map)}
   :children [{:fx/type :label :text (if active? "●" "") :min-width 10 :style-class ["label" "zone-map-item-dot"]}
              {:fx/type :label :text (:name map) :style-class ["label" "zone-map-item-name"]}
              {:fx/type :region :h-box/hgrow :always}
              {:fx/type :label :text (if (:built-in? map) "built-in" "") :style-class ["label-hint"]}]})

(defn- map-list
  "MAPS, with New map: the built-ins, then yours."
  [{:keys [fx/context]}]
  (let [selected (fx/sub-ctx context subs/selected-zone-map-id)
        active (:id (fx/sub-ctx context subs/active-zone-map))]
    {:fx/type :v-box
     :spacing 3
     :min-width 210
     :pref-width 210
     :children (into [{:fx/type :h-box
                       :alignment :center-left
                       :children [{:fx/type :label :text "MAPS" :style-class ["label" "zone-maps-section-title"]}
                                  {:fx/type :region :h-box/hgrow :always}
                                  {:fx/type :button
                                   :id "zone-maps-new"
                                   :text "New map"
                                   :style-class ["button-secondary"]
                                   :tooltip {:fx/type :tooltip :text "A map of your own, Left / Right's lanes to start"}
                                   :on-action {:event/type :zone-maps/create}}]}]
                     (map (fn [m] {:fx/type map-item :fx/key (:id m) :map m
                                   :selected? (= selected (:id m)) :active? (= active (:id m))}))
                     (fx/sub-ctx context subs/zone-maps-list))}))

(defn zones-line
  "Where each zone goes under map m (06 §7.5): \"A · Beams → Left · B →
   Right · C → Left (wraps)\", an override marked \"(override)\"."
  [names m zones]
  (let [n (count (:lanes m))]
    (str/join " · " (for [zone zones]
                      (str (targets/target-text names m zone)
                           (cond
                             (contains? (:overrides m) zone) " (override)"
                             (and (pos? n) (>= (targets/zone-index zone) n)) " (wraps)"))))))

(defn- field-label [text]
  {:fx/type :label :text text :min-width 70 :style-class ["label-secondary"]})

(defn- row [label & children]
  {:fx/type :h-box
   :spacing 6
   :alignment :center-left
   :children (into [(field-label label)] children)})

(defn- remove-button [id event]
  {:fx/type :button
   :id id
   :text "×"
   :style-class ["button-secondary"]
   :focus-traversable false
   :on-action event})

(defn- lanes-row
  "Lanes: a chip each (All or a group, :lane scope), × to take it out, and
   + Lane."
  [{:keys [id lanes]}]
  (apply row "Lanes"
         (concat
          (mapcat (fn [i lane]
                    [{:fx/type :label :text (str (inc i)) :style-class ["label-bold"]}
                     {:fx/type target-control/target-chip
                      :id (str "zone-map-lane-" i)
                      :scope :lane
                      :own lane
                      :on-set {:event/type :zone-maps/set-lane :map id :index i}
                      :tooltip (str "Where lane " (inc i) "'s zones go")}
                     (remove-button (str "zone-map-remove-lane-" i) {:event/type :zone-maps/remove-lane :map id :index i})])
                  (range) lanes)
          [{:fx/type :button
            :id "zone-map-add-lane"
            :text "+ Lane"
            :style-class ["button-secondary"]
            :on-action {:event/type :zone-maps/add-lane :map id}}])))

(defn- override-zone-combo
  "The zone an override is for: the zones listed, less those another
   override has. A zone the app refused (the show lock) goes back to the
   one the override has (applied-value)."
  [{:keys [id zone ref choices]}]
  {:fx/type applied-value/applied-control
   :property :value
   :address [id :override zone]
   :value zone
   :on-change (fn [z]
                (when (and z (not= z zone))
                  {:event/type :zone-maps/set-override :map id :from zone :zone z :target ref}))
   :desc {:fx/type :combo-box
          :id (str "zone-map-override-zone-" (id-text zone))
          :items choices
          :button-cell (fn [z] {:text (targets/zone-letter z)})
          :cell-factory {:fx/cell-type :list-cell :describe (fn [z] {:text (targets/zone-letter z)})}}})

(defn- overrides-row
  "Overrides: a zone, the group it goes to whatever its lane, ×; and +
   Override, for the first zone listed without one."
  [{:keys [id overrides zones]}]
  (let [free (remove #(contains? overrides %) zones)]
    (apply row "Overrides"
           (concat
            (mapcat (fn [[zone ref]]
                      [{:fx/type override-zone-combo :id id :zone zone :ref ref
                        :choices (vec (sort-by targets/zone-index (cons zone free)))}
                       {:fx/type :label :text "→"}
                       {:fx/type target-control/target-chip
                        :id (str "zone-map-override-" (id-text zone))
                        :scope :lane
                        :own ref
                        :on-set {:event/type :zone-maps/set-override :map id :zone zone}
                        :tooltip (str "Where zone " (targets/zone-letter zone) " goes, whatever its lane")}
                       (remove-button (str "zone-map-remove-override-" (id-text zone))
                                      {:event/type :zone-maps/set-override :map id :zone zone :target nil})])
                    (sort-by (comp targets/zone-index key) overrides))
            [{:fx/type :button
              :id "zone-map-add-override"
              :text "+ Override"
              :style-class ["button-secondary"]
              :disable (empty? free)
              :on-action {:event/type :zone-maps/set-override :map id :zone (first free) :target :all}}]))))

(defn- warnings
  "What is wrong with the map, one line each."
  [{:keys [fx/context id]}]
  (let [ws (fx/sub-ctx context subs/map-warnings id)]
    {:fx/type :v-box
     :id "zone-map-warnings"
     :spacing 2
     :visible (boolean (seq ws))
     :managed (boolean (seq ws))
     :children (mapv (fn [w] {:fx/type :label
                              :text w
                              :wrap-text true
                              :graphic {:fx/type :label :text "⚠" :style-class ["label" "zone-map-warning"]}
                              :style-class ["label" "zone-map-warning"]})
                     ws)}))

(defn- map-editor
  "The selected map: its name, lanes, where each zone goes, its overrides
   and warnings; Use now, Duplicate, and for yours Delete."
  [{:keys [fx/context]}]
  (let [id (fx/sub-ctx context subs/selected-zone-map-id)
        m (fx/sub-ctx context subs/zone-map id)
        names (fx/sub-ctx context subs/names-ctx)
        zones (fx/sub-ctx context subs/map-zones)
        active? (= id (:id (fx/sub-ctx context subs/active-zone-map)))
        built-in? (:built-in? m)]
    {:fx/type :v-box
     :id "zone-map-editor"
     :spacing 6
     :children
     (cond-> [{:fx/type :h-box
               :spacing 8
               :alignment :center-left
               :children (cond-> [{:fx/type :label :id "zone-map-title" :text (:name m) :style-class ["label-bold"]}
                                  {:fx/type :label :text (if active? "in use" "") :style-class ["label-hint"]}
                                  {:fx/type :region :h-box/hgrow :always}
                                  {:fx/type :button
                                   :id "zone-map-use-now"
                                   :text "Use now"
                                   :disable active?
                                   :style-class ["button-secondary"]
                                   :tooltip {:fx/type :tooltip :text "Switch to this map at once"}
                                   :on-action {:event/type :zone-maps/use-now :map id}}
                                  {:fx/type :button
                                   :id "zone-map-duplicate"
                                   :text "Duplicate"
                                   :style-class ["button-secondary"]
                                   :on-action {:event/type :zone-maps/duplicate :map id}}]
                           (not built-in?) (conj {:fx/type :button
                                                  :id "zone-map-delete"
                                                  :text "Delete"
                                                  :style-class ["button-danger"]
                                                  :on-action {:event/type :zone-maps/request-delete :map id}}))}]
       built-in?
       (conj (row "Lanes" {:fx/type :label
                           :id "zone-map-lanes-text"
                           :text (str/join " · " (map-indexed (fn [i lane] (str (inc i) " " (targets/ref-name names lane))) (:lanes m)))})
             {:fx/type :label
              :id "zone-map-built-in"
              :text "Built-in maps follow the rig. Duplicate one to change it."
              :style-class ["label-hint"]})
       (not built-in?)
       (conj (row "Name" {:fx/type commit-field/commit-field
                          :id "zone-map-name"
                          :text (:name m "")
                          :pref-width 200
                          :on-commit (fn [typed] (when-not (str/blank? typed)
                                                   {:event/type :zone-maps/rename :map id :name typed}))})
             {:fx/type lanes-row :id id :lanes (vec (:lanes m))})
       true
       (conj (row "Zones" {:fx/type :label
                           :id "zone-map-zones"
                           :wrap-text true
                           :text (zones-line names m zones)}))
       (not built-in?)
       (conj {:fx/type overrides-row :id id :overrides (or (:overrides m) {}) :zones zones})
       true
       (conj {:fx/type warnings :id id}))}))


;; The section

(defn zone-maps-panel
  "ZONES AND MAPS: the show's default and the zones' labels on one line,
   then the maps and the selected one's editor."
  [_]
  {:fx/type :v-box
   :id "zone-maps-panel"
   :style-class ["zone-maps-panel"]
   :spacing 8
   :children [{:fx/type :h-box
               :spacing 24
               :alignment :center-left
               :children [{:fx/type :label :text "ZONES AND MAPS" :style-class ["header-section"]}
                          {:fx/type show-default}
                          {:fx/type zone-labels}]}
              {:fx/type :h-box
               :spacing 16
               :children [{:fx/type map-list}
                          {:fx/type map-editor :h-box/hgrow :always}]}]})
