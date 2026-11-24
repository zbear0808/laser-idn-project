(ns laser-show.views.tabs.zones
  "Zone Groups tab, group first (plans/2026-11-19-zones/04 §7).
   
   - The list (left): All, the automatic groups (Left, Right, Center, Odd,
     Even), each laser's own group in rig order, then your groups by name,
     each with how many lasers it reaches. All and the automatic groups
     follow the order on the Projectors tab (laser-show.routing.rig).
   - The middle: the selected group's lasers in rig order, with the marks
     the rows under the cards have; for your groups each has a box (the same
     membership those rows show, and the same event), for All a click keeps
     a laser out of All or puts it back, and the automatic groups are read
     only, with a sentence that says why. Virtual projectors are listed
     under, with boxes for your groups.
   - The details (right): its colour, name and description, how many cues
     use it (and how many more through a zone the map in use sends there,
     zones plan Z4), and for your groups Edit... and Delete...; the others
     can't be renamed or deleted.
   - Under them, the zones and the zone maps (views/components/
     zone_maps_panel.clj)."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.css.core :as css]
            [laser-show.routing.targets :as targets]
            [laser-show.subs :as subs]
            [laser-show.views.components.applied-value :as applied-value]
            [laser-show.views.components.group-matrix :as group-matrix]
            [laser-show.views.components.zone-chips :as zone-chips]
            [laser-show.views.components.zone-maps-panel :as zone-maps-panel]))


(defn- id-text [id]
  (if (keyword? id) (name id) (str id)))


;; The list (left)


(defn- group-item
  "A group in the list: its colour, name (a lock on the built-in ones),
   description, and how many lasers it reaches."
  [{:keys [group selected?]}]
  (let [{:keys [id color description] reach :count} group
        text-id (group-matrix/ref-text id)]
    {:fx/type :h-box
     :id (str "zone-group-item-" text-id)
     :style-class ["zone-group-item" (if selected? "list-item-selected" "list-item")]
     :spacing 8
     :alignment :center-left
     :padding 8
     :on-mouse-clicked {:event/type :zone-groups/select :group-id id}
     :children [{:fx/type :circle :radius 6 :fill (or color "#808080")}
                {:fx/type :v-box
                 :h-box/hgrow :always
                 :children (cond-> [{:fx/type :h-box
                                     :spacing 4
                                     :alignment :center-left
                                     :children (cond-> [{:fx/type :label
                                                         :text (:name group)
                                                         :style-class ["label-bold"]}]
                                                 (zone-chips/locked? group) (conj (zone-chips/lock-graphic group)))}]
                             (and description (= :custom (:kind group)))
                             (conj {:fx/type :label :text description :style-class ["text-description"]}))}
                {:fx/type :label
                 :id (str "zone-group-count-" text-id)
                 :text (str reach)
                 :style-class ["text-count"]}]}))

(defn- section-header [text]
  {:fx/type :label
   :text text
   :style-class ["header-section"]
   :style "-fx-font-size: 10; -fx-padding: 6 0 0 4;"})

(defn- zone-groups-panel
  "The groups: All and the automatic ones, LASERS, YOUR GROUPS."
  [{:keys [fx/context]}]
  (let [groups (fx/sub-ctx context subs/zone-groups-tab-list)
        selected-id (fx/sub-ctx context subs/selected-zone-group-id)
        item (fn [g] {:fx/type group-item :fx/key (:id g) :group g :selected? (= (:id g) selected-id)})
        of-kind (fn [& kinds] (filterv #((set kinds) (:kind %)) groups))
        lasers (of-kind :laser)
        yours (of-kind :custom)]
    {:fx/type :v-box
     :spacing 8
     :pref-width 230
     :style (str "-fx-border-color: " (css/border-default) "; -fx-border-width: 0 1px 0 0;")
     :children [{:fx/type :h-box
                 :alignment :center-left
                 :padding {:right 8}
                 :children [{:fx/type :label
                             :text "GROUPS"
                             :style-class ["header-section"]}
                            {:fx/type :region :h-box/hgrow :always}
                            {:fx/type :button
                             :id "zone-groups-add-button"
                             :text "+"
                             :style-class ["button-primary"]
                             :tooltip {:fx/type :tooltip :text "New zone group"}
                             :on-action {:event/type :zone-groups/add}}]}
                {:fx/type :scroll-pane
                 :fit-to-width true
                 :v-box/vgrow :always
                 :style-class ["scroll-pane-dark"]
                 :content {:fx/type :v-box
                           :spacing 4
                           :padding 4
                           :children (vec (concat (map item (of-kind :all :auto))
                                                  (when (seq lasers) (cons (section-header "LASERS") (map item lasers)))
                                                  [(section-header "YOUR GROUPS")]
                                                  (if (seq yours)
                                                    (map item yours)
                                                    [{:fx/type :label
                                                      :text "None yet: + makes one"
                                                      :style-class ["label-hint"]}])))}}]}))


;; The middle: the group's lasers


(defn group-explanation
  "The sentence under a group's lasers, saying why they are in it (04 §7).
   rig is subs/rig-settings ({:n :middle :split-width}); middle-name the
   middle laser's name when there is one."
  [{:keys [id kind description] group-name :name} {:keys [n middle split-width]} middle-name]
  (let [middle? (and (odd? n) (<= 3 n))
        half (fn [side group]
               (cond
                 (= 1 n) (str "With one laser, " group " is its " side " " (Math/round (* 100.0 (double split-width))) "%.")
                 middle? (str "The " side " half of the rig. The middle laser, " middle-name ", "
                              (case middle
                                :both "is in both, whole."
                                :left "is in Left only."
                                :right "is in Right only."
                                :none "is in neither."
                                (str "is split: its " side " half is in " group ".")))
                 :else (str "The " side " half of the rig.")))]
    (case kind
      :all "Every laser that's on, except those set to Exclude from All."
      :laser description
      :custom (str "Click a laser to add it to " group-name " or take it out. The boxes under the cards on the Projectors tab are the same ones.")
      (case id
        :auto/left (half "left" "Left")
        :auto/right (half "right" "Right")
        :auto/center "The middle laser, or the middle two."
        :auto/odd (if (= 1 n)
                    "With one laser, Odd is its left half and Even its right half."
                    "Every other laser from the left: 1st, 3rd, 5th…")
        :auto/even (if (= 1 n)
                     "With one laser, Odd is its left half and Even its right half."
                     "Every other laser from the left: 2nd, 4th…")
        ""))))

(defn- member-event
  "What a click on a laser does in group: your groups toggle it, All keeps
   it out or puts it back; the automatic groups are read only."
  [group {:keys [id mark]}]
  (case (:kind group)
    :custom {:event/type :projectors/toggle-zone-group :projector-id id :zone-group-id (:id group)}
    :all (when (#{:whole :excluded} mark)
           {:event/type :projectors/set-exclude-from-all :projector-id id :exclude? (= :whole mark)})
    nil))

(defn- laser-item
  "One laser of the group, in rig order: its place, its name, its mark, and
   a box where a click changes something. A box shows what applies after a
   refused click too (applied-value)."
  [{:keys [group laser]}]
  (let [{:keys [id position mark]} laser
        event (member-event group laser)]
    (cond-> {:fx/type :v-box
             :id (str "zone-output-" (id-text id))
             :style-class ["zone-output" "list-item"]
             :spacing 4
             :padding 8
             :min-width 110
             :alignment :top-center
             :style (if event "-fx-cursor: hand;" "")
             :children (cond-> [{:fx/type :h-box
                                 :spacing 4
                                 :alignment :center
                                 :children [{:fx/type :label
                                             :text (if position (str position) "–")
                                             :style "-fx-text-fill: #A0A0A0; -fx-font-weight: bold;"}
                                            {:fx/type :label
                                             :text (:name laser)
                                             :style-class ["label-bold"]}]}
                                {:fx/type :label
                                 :id (str "zone-mark-" (id-text id))
                                 :text (zone-chips/member-mark mark)
                                 :style (str "-fx-font-size: 15; -fx-text-fill: "
                                             (if (#{:whole :left-half :right-half :member} mark) (:color group) "#808080") ";")}]
                         event (conj (if (= :all (:kind group))
                                       ;; ticked: in All
                                       {:fx/type applied-value/applied-control
                                        :property :selected
                                        :address [:all id]
                                        :value (= :whole mark)
                                        :on-change (fn [in-all?] {:event/type :projectors/set-exclude-from-all
                                                                  :projector-id id :exclude? (not in-all?)})
                                        :desc {:fx/type :check-box}}
                                       ;; a click toggles it, once
                                       {:fx/type applied-value/applied-control
                                        :property :selected
                                        :address [(:id group) id]
                                        :value (= :member mark)
                                        :on-change (fn [_] event)
                                        :desc {:fx/type :check-box}})))}
      event (assoc :on-mouse-clicked event))))

(defn- vp-item
  "A virtual projector in your group: a box, as before."
  [{:keys [group vp]}]
  (let [event {:event/type :projectors/vp-toggle-zone-group :vp-id (:id vp) :zone-group-id (:id group)}]
    {:fx/type :h-box
     :id (str "zone-output-" (id-text (:id vp)))
     :style-class ["list-item"]
     :spacing 8
     :alignment :center-left
     :padding 6
     :style "-fx-cursor: hand;"
     :on-mouse-clicked event
     :children [{:fx/type applied-value/applied-control
                 :property :selected
                 :address [(:id group) (:id vp)]
                 :value (:member? vp)
                 :on-change (fn [_] event)
                 :desc {:fx/type :check-box}}
                {:fx/type :label :text (:name vp) :style-class ["label-bold"]}]}))

(defn- members-panel
  "The selected group's lasers in rig order, and why they are in it."
  [{:keys [fx/context]}]
  (let [group (fx/sub-ctx context subs/selected-target-group)]
    (if-not group
      {:fx/type :v-box
       :padding 16
       :children [{:fx/type :label
                   :text "Select a group to see its lasers"
                   :style-class ["label-hint"]}]}
      (let [{:keys [lasers vps]} (fx/sub-ctx context subs/group-members (:id group))
            settings (fx/sub-ctx context subs/rig-settings)]
        {:fx/type :v-box
         :spacing 8
         :padding 8
         :children (cond-> [{:fx/type :label
                             :id "zone-group-members-title"
                             :text (str "LASERS IN " (.toUpperCase ^String (str (:name group))))
                             :style-class ["header-section"]}
                            (if (seq lasers)
                              {:fx/type :scroll-pane
                               :fit-to-height true
                               :vbar-policy :never
                               :style-class ["scroll-pane-dark"]
                               :content {:fx/type :h-box
                                         :spacing 8
                                         :padding 4
                                         :children (mapv (fn [laser] {:fx/type laser-item :fx/key (:id laser) :group group :laser laser})
                                                         lasers)}}
                              {:fx/type :label
                               :text "No lasers yet: Scan on the Projectors tab finds them."
                               :style-class ["label-hint"]})
                            {:fx/type :label
                             :id "zone-group-explanation"
                             :text (group-explanation group settings (:middle-name settings))
                             :wrap-text true
                             :style-class ["label-secondary"]}]
                     (not= :custom (:kind group))
                     (conj {:fx/type :h-box
                            :spacing 6
                            :alignment :center-left
                            :children [{:fx/type :button
                                        :id "zone-group-projectors-link"
                                        :text "Projectors tab"
                                        :style-class ["button-secondary"]
                                        :on-action {:event/type :ui/set-active-tab :tab-id :projectors}}
                                       {:fx/type :label
                                        :text "to change the order or the middle laser."
                                        :style-class ["label-hint"]}]})
                     (and (= :custom (:kind group)) (seq vps))
                     (into (cons {:fx/type :label
                                  :text "Virtual projectors (not sent to any laser yet)"
                                  :style-class ["header-section"]
                                  :style "-fx-font-size: 10;"}
                                 (map (fn [vp] {:fx/type vp-item :fx/key (:id vp) :group group :vp vp}) vps))))}))))

(defn- outputs-panel
  [{:keys [fx/context]}]
  {:fx/type :v-box
   :spacing 8
   :style (str "-fx-border-color: " (css/border-default) "; -fx-border-width: 0 1px 0 0;")
   :children [{:fx/type :scroll-pane
               :fit-to-width true
               :v-box/vgrow :always
               :style-class ["scroll-pane-dark"]
               :content {:fx/type members-panel}}]})


;; Details Panel (Right)


(defn cue-usage-text
  "\"Used by 3 cues\", \"Used by 1 cue\" or \"Used by no cues\"."
  [n]
  (case (long n)
    0 "Used by no cues"
    1 "Used by 1 cue"
    (str "Used by " n " cues")))

(defn usage-text
  "A group's details line (zones plan Z4, 06 §7.5, §8.7): \"Used by 3
   cues\", \"Used by 3 cues · 9 more through Zone A\", \"Used by 9 cues
   through Zones A, C\", \"Used by no cues\"; usage is subs/ref-usage."
  [{:keys [direct through zones]}]
  (let [direct (long (or direct 0))
        through (long (or through 0))
        via (str (if (next zones) "Zones " "Zone ") (str/join ", " (map targets/zone-letter zones)))]
    (cond
      (zero? through) (cue-usage-text direct)
      (zero? direct) (str (cue-usage-text through) " through " via)
      :else (str (cue-usage-text direct) " · " through " more through " via))))

(def ^:private kind-texts
  {:all "built in" :auto "automatic" :laser "one laser" :custom "your group"})

(defn- zone-group-details-panel
  "Details panel for the selected zone group."
  [{:keys [fx/context]}]
  (let [group (fx/sub-ctx context subs/selected-target-group)
        usage (when group (fx/sub-ctx context subs/ref-usage (:id group)))]
    (if group
      {:fx/type :v-box
       :spacing 12
       :children [{:fx/type :h-box
                   :spacing 8
                   :alignment :center-left
                   :children [{:fx/type :circle
                               :radius 8
                               :fill (or (:color group) "#808080")}
                              {:fx/type :label
                               :text (:name group)
                               :style-class ["header-primary"]}]}
                  {:fx/type :label
                   :text (kind-texts (:kind group) "")
                   :style-class ["label-hint"]}
                  {:fx/type :label
                   :text (if (= :custom (:kind group)) (or (not-empty (:description group)) "No description") (str (:description group)))
                   :wrap-text true
                   :style-class ["label-hint"]}
                  {:fx/type :separator}
                  {:fx/type :label
                   :id "zone-group-cue-usage"
                   :text (usage-text usage)
                   :style-class ["label-secondary"]}
                  {:fx/type :region :v-box/vgrow :always}
                  (if (= :custom (:kind group))
                    {:fx/type :h-box
                     :spacing 8
                     :alignment :center-left
                     :children [{:fx/type :button
                                 :id "zone-group-edit-button"
                                 :text "Edit..."
                                 :style-class ["button-secondary"]
                                 :on-action {:event/type :zone-groups/edit
                                             :group-id (:id group)}}
                                {:fx/type :button
                                 :id "zone-group-delete-button"
                                 :text "Delete..."
                                 :style-class ["button-danger"]
                                 :on-action {:event/type :zone-groups/request-remove
                                             :group-id (:id group)}}]}
                    {:fx/type :label
                     :id "zone-group-cannot-delete"
                     :text "Automatic: can't be renamed or deleted."
                     :wrap-text true
                     :style-class ["label-hint"]})]}
      ;; No group selected
      {:fx/type :v-box
       :children [{:fx/type :label
                   :text "Select a zone group to view details"
                   :style-class ["label-hint"]}]})))

(defn- details-panel
  "Right panel showing details of selected zone group."
  [{:keys [fx/context]}]
  {:fx/type :v-box
   :spacing 8
   :pref-width 260
   :children [{:fx/type :label
               :text "DETAILS"
               :style-class ["header-section"]}
              {:fx/type :scroll-pane
               :fit-to-width true
               :fit-to-height true
               :v-box/vgrow :always
               :style-class ["scroll-pane-dark"]
               :content {:fx/type :v-box
                         :padding 12
                         :children [{:fx/type zone-group-details-panel}]}}]})


;; Main Tab


(defn zones-tab
  "Zone Groups tab: the groups, the selected one's lasers in rig order, and
   its details; under them the zones and the zone maps (zones plan Z4)."
  [{:keys [fx/context]}]
  {:fx/type :v-box
   :style-class ["container-primary"]
   :padding 16
   :spacing 8
   :children [{:fx/type :label
               :text "Zone Groups"
               :style-class ["header-primary"]}
              {:fx/type :label
               :id "zones-subtitle"
               :text "Groups are where cues draw. All and the automatic groups follow the order on the Projectors tab."
               :style-class ["label-secondary"]}
              ;; The groups' list keeps the full height; the zones and maps
              ;; sit under the lasers and the details
              {:fx/type :h-box
               :spacing 16
               :v-box/vgrow :always
               :children [{:fx/type zone-groups-panel}
                          {:fx/type :v-box
                           :h-box/hgrow :always
                           :spacing 8
                           :children [{:fx/type :h-box
                                       :spacing 16
                                       :v-box/vgrow :always
                                       :min-height 140
                                       :children [{:fx/type :v-box
                                                   :h-box/hgrow :always
                                                   :children [{:fx/type outputs-panel
                                                               :v-box/vgrow :always}]}
                                                  {:fx/type details-panel}]}
                                      {:fx/type :scroll-pane
                                       :id "zone-maps-scroll"
                                       :fit-to-width true
                                       :hbar-policy :never
                                       :min-height 120
                                       :pref-height 290
                                       :max-height 290
                                       :style-class ["scroll-pane-dark"]
                                       :content {:fx/type zone-maps-panel/zone-maps-panel}}]}]}]})
