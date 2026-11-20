(ns laser-show.views.components.group-matrix
  "The group rows under the rig strip's cards (plans/2026-11-19-zones/04 §7),
   in the strip's grid, so they scroll with the cards: All, Left, Right,
   Center, Odd, Even, Each laser, then your groups, a mark per card
   (zone-chips/member-mark). The automatic rows are locked. A click in the
   All row keeps that laser out of All or puts it back; a click in one of
   your rows puts the laser in the group or takes it out (the same event the
   Zone Groups tab sends). A row's name opens the group in the Zone Groups
   tab. Also the middle laser and split width settings beside them."
  (:require [clojure.string :as str]
            [laser-show.css.core :as css]
            [laser-show.routing.rig :as rig]
            [laser-show.views.components.zone-chips :as zone-chips]))


(defn ref-text
  "A ref as part of a node id: :all is all, :auto/left auto-left, :graphics
   graphics, :auto.laser/p1 auto-laser-p1."
  [ref]
  (if (keyword? ref)
    (str/replace (str (when-let [space (namespace ref)] (str space "-")) (name ref)) "." "-")
    (str ref)))

(defn- id-text [id]
  (if (keyword? id) (name id) (str id)))


;; The middle laser and the split width


(def ^:private middle-texts
  {:split "Split between Left and Right"
   :both "In both, whole"
   :left "Left only"
   :right "Right only"
   :none "In neither"})

(defn rig-settings
  "Middle laser (on with 3, 5, 7… lasers) and Split width (on with one
   laser, or the middle split or in both): stored as they are set; Z2 draws
   whole lasers, so the split width shows in the rows only until Z3."
  [{:keys [settings]}]
  (let [{:keys [middle split-width middle-on? split-on?]} settings]
    {:fx/type :v-box
     :spacing 4
     :children [{:fx/type :h-box
                 :spacing 6
                 :alignment :center-left
                 :children (cond-> [{:fx/type :label
                                     :text "Middle laser:"
                                     :min-width :use-pref-size
                                     :style-class ["label-secondary"]}
                                    {:fx/type :combo-box
                                     :id "rig-middle-choice"
                                     :items [:split :both :left :right :none]
                                     :value middle
                                     :disable (not middle-on?)
                                     :button-cell (fn [m] {:text (get middle-texts m "")})
                                     :cell-factory {:fx/cell-type :list-cell
                                                    :describe (fn [m] {:text (get middle-texts m "")})}
                                     :on-value-changed {:event/type :rig/set-middle :middle :fx/event}}]
                            (not middle-on?) (conj {:fx/type :label
                                                    :id "rig-middle-hint"
                                                    :text "Used with 3, 5, 7… lasers"
                                                    :style-class ["label-hint"]}))}
                {:fx/type :h-box
                 :spacing 6
                 :alignment :center-left
                 :children [{:fx/type :label
                             :text "Split width:"
                             :min-width :use-pref-size
                             :style-class ["label-secondary"]}
                            {:fx/type :slider
                             :id "rig-split-width-slider"
                             :min 0.5
                             :max 1.0
                             :value split-width
                             :pref-width 140
                             :disable (not split-on?)
                             :tooltip {:fx/type :tooltip
                                       :text "How wide each half is when one laser draws Left and Right"}
                             :on-value-changed {:event/type :rig/set-split-width :split-width :fx/event}}
                            {:fx/type :label
                             :id "rig-split-width-label"
                             :text (format "%.0f%%" (* 100.0 (double split-width)))
                             :min-width :use-pref-size
                             :style-class ["label-secondary"]}]}]}))


;; The rows


(defn- mark-color [row mark]
  (case mark
    (:whole :left-half :right-half :member) (:color row)
    :excluded (css/red)
    "#707070"))

(defn- cell-event
  "What a click on a cell does: the All row excludes or includes (not for a
   laser that is off), your rows toggle; the automatic rows are locked."
  [row id mark]
  (case (:kind row)
    :all (when (#{:whole :excluded} mark)
           {:event/type :projectors/set-exclude-from-all :projector-id id :exclude? (= :whole mark)})
    :custom {:event/type :projectors/toggle-zone-group :projector-id id :zone-group-id (:id row)}
    nil))

(defn- cell-tooltip [row laser mark]
  (case (:kind row)
    :all (case mark
           :whole (str "Click to keep " laser " out of All")
           :excluded "Excluded from All: click to include"
           (str laser " is off"))
    :custom (str "Click to put " laser " in " (:name row) " or take it out")
    (case mark
      :left-half (str "Left half of " laser ". For now the whole laser draws it.")
      :right-half (str "Right half of " laser ". For now the whole laser draws it.")
      :excluded (str laser " is out of All, so out of the automatic groups too")
      :off (str laser " is off")
      "Automatic: follows the order of the cards")))

(defn- row-name
  "A row's name in the first column: its colour, its name and, for the
   locked rows, a lock. A click opens the group in the Zone Groups tab
   (Each laser opens the selected laser's, or the first's)."
  [row r show-group]
  (let [locked? (not= :custom (:kind row))]
    {:fx/type :h-box
     :id (str "rig-row-" (ref-text (:id row)))
     :grid-pane/column 0
     :grid-pane/row r
     :spacing 6
     :alignment :center-left
     :style "-fx-cursor: hand;"
     :on-mouse-clicked {:event/type :zone-groups/show :group-id show-group}
     :children (cond-> [{:fx/type :circle :radius 5 :fill (:color row)}
                        (cond-> {:fx/type :label
                                 :text (:name row)
                                 :min-width :use-pref-size
                                 :style-class ["label-secondary"]}
                          locked? (assoc :tooltip {:fx/type :tooltip :text "Automatic: follows the order of the cards"}))]
                 locked? (conj (zone-chips/lock-graphic {:kind (:kind row)})))}))

(defn rows
  "The grid-pane descriptions of the rows under the cards, from grid row 1:
   a row's name in column 0, card i's mark in column i + 1, then + Group.
   matrix is subs/rig-matrix; cards subs/rig-cards (for the names), in the
   same order."
  [matrix cards]
  (let [names (into {} (map (juxt :id :name)) cards)
        first-laser (some-> (first (:columns matrix)) rig/laser-ref)
        selected-laser (some #(when (:selected? %) (rig/laser-ref (:id %))) cards)]
    (-> (vec (mapcat (fn [r row]
                        (cons (row-name row (inc r) (if (= :each-laser (:id row))
                                                      (or selected-laser first-laser)
                                                      (:id row)))
                              (map-indexed
                               (fn [k id]
                                 (let [mark (get-in row [:cells id])
                                       event (cell-event row id mark)]
                                   (cond-> {:fx/type :label
                                            :id (str "rig-cell-" (ref-text (:id row)) "-" (id-text id))
                                            :grid-pane/column (inc k)
                                            :grid-pane/row (inc r)
                                            :grid-pane/halignment :center
                                            :text (zone-chips/member-mark mark)
                                            :tooltip {:fx/type :tooltip :text (cell-tooltip row (get names id (id-text id)) mark)}
                                            :style (str "-fx-text-fill: " (mark-color row mark) "; -fx-font-size: 13;"
                                                        (when event " -fx-cursor: hand;"))}
                                     event (assoc :on-mouse-clicked event))))
                               (:columns matrix))))
                     (range)
                     (:rows matrix)))
        (conj {:fx/type :button
               :id "rig-add-group-button"
               :text "+ Group"
               :grid-pane/column 0
               :grid-pane/row (inc (count (:rows matrix)))
               :style-class ["button-secondary"]
               :tooltip {:fx/type :tooltip :text "A group of your own: tick the lasers in it"}
               :on-action {:event/type :zone-groups/add}}))))
