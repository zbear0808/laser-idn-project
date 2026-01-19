(ns laser-show.views.tabs.zones
  "Zone Groups tab - for managing zone groups (routing targets).
   
   SIMPLIFIED ARCHITECTURE (v2):
   - Projectors are directly assigned to zone groups (no intermediate 'zone' abstraction)
   - This tab manages zone groups and shows which projectors/VPs are assigned
   - Projector corner-pin and zone group assignment is done in the Projectors tab
   
   Features:
   - View and edit zone groups (left panel)
   - View projectors and VPs in selected group (center panel)
   - Zone group details (right panel)"
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs]
            [laser-show.css.core :as css]))


;; Output Type Badge


(defn- output-type-badge
  "Badge showing output type (projector vs virtual projector)."
  [{:keys [type]}]
  (let [color (case type
                :projector "#4A6FA5"
                :virtual-projector "#9B59B6"
                "#808080")
        label (case type
                :projector "Projector"
                :virtual-projector "Virtual"
                "Unknown")]
    {:fx/type :label
     :text label
     :style-class ["badge"]
     :style (str "-fx-background-color: " color ";")}))


;; Tag Chips


(defn- tag-chip
  "Small chip showing a tag."
  [{:keys [tag]}]
  (let [color (case tag
                :graphics "#9B59B6"
                :crowd-scanning "#E67E22"
                "#808080")]
    {:fx/type :label
     :text (name tag)
     :style-class ["chip"]
     :style (str "-fx-background-color: " color "50; "
                "-fx-border-color: " color "; "
                "-fx-border-width: 1px; "
                "-fx-border-radius: 3px; "
                "-fx-background-radius: 3px; "
                "-fx-padding: 1px 4px; "
                "-fx-font-size: 9px;")}))


;; Zone Groups Panel (Left)


(defn- zone-group-item
  "Single zone group in the list."
  [{:keys [group selected? output-count]}]
  (let [{:keys [id name description color]} group]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :padding 8
     :style-class [(if selected? "list-item-selected" "list-item")]
     :on-mouse-clicked {:event/type :zone-groups/select
                        :group-id id}
     :children [{:fx/type :circle
                 :radius 6
                 :fill color}
                {:fx/type :v-box
                 :h-box/hgrow :always
                 :children [{:fx/type :label
                             :text name
                             :style-class ["label-bold"]}
                            {:fx/type :label
                             :text (or description "")
                             :style-class ["text-description"]}]}
                {:fx/type :label
                 :text (str output-count)
                 :style-class ["text-count"]}]}))

(defn- zone-groups-panel
  "Panel listing all zone groups."
  [{:keys [fx/context]}]
  (let [groups (fx/sub-ctx context subs/zone-groups-list)
        selected-id (fx/sub-ctx context subs/selected-zone-group-id)]
    {:fx/type :v-box
     :spacing 8
     :pref-width 220
     :style (str "-fx-border-color: " (css/border-default) "; -fx-border-width: 0 1px 0 0;")
     :children [{:fx/type :h-box
                 :alignment :center-left
                 :children [{:fx/type :label
                             :text "ZONE GROUPS"
                             :style-class ["header-section"]}
                            {:fx/type :region :h-box/hgrow :always}
                            {:fx/type :button
                             :text "+"
                             :style-class ["button-add"]
                             :on-action {:event/type :zone-groups/add}}]}
                {:fx/type :scroll-pane
                 :fit-to-width true
                 :v-box/vgrow :always
                 :style-class ["scroll-pane-dark"]
                 :content {:fx/type :v-box
                           :spacing 4
                           :padding 4
                           :children (if (seq groups)
                                       (vec (for [group groups]
                                              (let [usage (fx/sub-ctx context subs/zone-group-usage (:id group))]
                                                {:fx/type zone-group-item
                                                 :fx/key (:id group)
                                                 :group group
                                                 :selected? (= (:id group) selected-id)
                                                 :output-count (+ (:projector-count usage 0)
                                                                  (:vp-count usage 0))})))
                                       [{:fx/type :label
                                         :text "No zone groups defined"
                                         :style-class ["label-hint"]
                                         :style "-fx-padding: 16;"}])}}]}))


;; Outputs Panel (Center) - Shows projectors and VPs in selected group


(defn- output-item
  "Single projector or virtual projector in the list.
   Can toggle zone group membership."
  [{:keys [output group-id]}]
  (let [{:keys [id name output-type enabled? tags zone-groups]} output
        in-group? (some #{group-id} zone-groups)
        ;; Build children, filtering out nil values
        v-box-children (filterv some?
                                [{:fx/type :h-box
                                  :spacing 6
                                  :alignment :center-left
                                  :children [{:fx/type :label
                                              :text (or name (str id))
                                              :style-class ["label-bold"]}
                                             {:fx/type output-type-badge
                                              :type output-type}]}
                                 (when (seq tags)
                                   {:fx/type :h-box
                                    :spacing 4
                                    :children (vec (for [tag tags]
                                                     {:fx/type tag-chip
                                                      :fx/key tag
                                                      :tag tag}))})])]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :padding 8
     :style-class [(if in-group? "list-item-selected" "list-item")]
     :style "-fx-cursor: hand;"
     :on-mouse-clicked (if (= output-type :projector)
                         {:event/type :projectors/toggle-zone-group
                          :projector-id id
                          :zone-group-id group-id}
                         {:event/type :projectors/vp-toggle-zone-group
                          :vp-id id
                          :zone-group-id group-id})
     :children [{:fx/type :check-box
                 :selected (boolean in-group?)
                 :on-selected-changed (if (= output-type :projector)
                                        {:event/type :projectors/toggle-zone-group
                                         :projector-id id
                                         :zone-group-id group-id}
                                        {:event/type :projectors/vp-toggle-zone-group
                                         :vp-id id
                                         :zone-group-id group-id})}
                {:fx/type :v-box
                 :h-box/hgrow :always
                 :spacing 2
                 :children v-box-children}
                {:fx/type :label
                 :text (if enabled? "" "(disabled)")
                 :style "-fx-text-fill: #606060; -fx-font-size: 9;"}]}))


(defn- all-outputs-list
  "List of ALL projectors and VPs with toggle for zone group membership."
  [{:keys [fx/context group-id]}]
  (let [all-projectors (fx/sub-ctx context subs/projectors-list)
        all-vps (fx/sub-ctx context subs/virtual-projectors-list)
        all-outputs (into (mapv #(assoc % :output-type :projector) all-projectors)
                          (mapv #(assoc % :output-type :virtual-projector) all-vps))]
    {:fx/type :v-box
     :spacing 4
     :children (if (seq all-outputs)
                 (vec (for [output all-outputs]
                        {:fx/type output-item
                         :fx/key (:id output)
                         :output output
                         :group-id group-id}))
                 [{:fx/type :label
                   :text "No projectors configured.\nAdd projectors in the Projectors tab."
                   :style-class ["label-hint"]
                   :style "-fx-padding: 16;"}])}))

(defn- outputs-panel
  "Panel showing ALL projectors and VPs with toggles for zone group membership."
  [{:keys [fx/context]}]
  (let [selected-group-id (fx/sub-ctx context subs/selected-zone-group-id)]
    {:fx/type :v-box
     :spacing 8
     :style (str "-fx-border-color: " (css/border-default) "; -fx-border-width: 0 1px 0 0;")
     :children [{:fx/type :label
                 :text "ASSIGN PROJECTORS"
                 :style-class ["header-section"]}
                {:fx/type :scroll-pane
                 :fit-to-width true
                 :v-box/vgrow :always
                 :style-class ["scroll-pane-dark"]
                 :content (if selected-group-id
                            {:fx/type all-outputs-list
                             :fx/context context
                             :group-id selected-group-id}
                            {:fx/type :v-box
                             :spacing 4
                             :padding 8
                             :children [{:fx/type :label
                                         :text "Select a zone group to assign projectors"
                                         :style-class ["label-hint"]
                                         :style "-fx-padding: 16;"}]})}]}))


;; Details Panel (Right)


(defn- zone-group-details-panel
  "Details panel for the selected zone group."
  [{:keys [fx/context]}]
  (let [group (fx/sub-ctx context subs/selected-zone-group)
        usage (when group (fx/sub-ctx context subs/zone-group-usage (:id group)))]
    (if group
      {:fx/type :v-box
       :spacing 12
       :children [{:fx/type :h-box
                   :spacing 8
                   :alignment :center-left
                   :children [{:fx/type :circle
                               :radius 8
                               :fill (:color group)}
                              {:fx/type :label
                               :text (:name group)
                               :style-class ["header-primary"]}]}
                  {:fx/type :label
                   :text (or (:description group) "No description")
                   :style-class ["label-hint"]}
                  {:fx/type :separator}
                  ;; Usage stats
                  {:fx/type :v-box
                   :spacing 4
                   :children [{:fx/type :label
                               :text (str "Projectors: " (:projector-count usage 0))
                               :style-class ["label-secondary"]}
                              {:fx/type :label
                               :text (str "Virtual Projectors: " (:vp-count usage 0))
                               :style-class ["label-secondary"]}
                              {:fx/type :label
                               :text (str "Cues targeting: " (:cue-count usage 0))
                               :style-class ["label-secondary"]}]}
                  {:fx/type :separator}
                  ;; Info about assignment
                  {:fx/type :v-box
                   :spacing 4
                   :children [{:fx/type :label
                               :text "HOW TO ASSIGN"
                               :style-class ["header-section"]
                               :style "-fx-font-size: 10;"}
                              {:fx/type :label
                               :text "Use the checkboxes in the center panel\nto toggle projector membership."
                               :style-class ["label-hint"]
                               :wrap-text true}]}
                  {:fx/type :region :v-box/vgrow :always}
                  ;; Edit/Delete buttons
                  {:fx/type :h-box
                   :spacing 8
                   :children [{:fx/type :button
                               :text "Edit..."
                               :style-class ["button-secondary"]
                               :on-action {:event/type :zone-groups/edit
                                           :group-id (:id group)}}
                              {:fx/type :button
                               :text "Delete"
                               :style-class ["button-danger"]
                               :on-action {:event/type :zone-groups/remove
                                           :group-id (:id group)}}]}]}
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
               :v-box/vgrow :always
               :style-class ["scroll-pane-dark"]
               :content {:fx/type :v-box
                         :padding 12
                         :children [{:fx/type zone-group-details-panel}]}}]})


;; Main Tab


(defn zones-tab
  "Zone Groups management tab.
   Simplified: just manages zone groups, projector assignment is in Projectors tab."
  [{:keys [fx/context]}]
  {:fx/type :v-box
   :style-class ["container-primary"]
   :padding 16
   :spacing 8
   :children [{:fx/type :label
               :text "Zone Groups"
               :style-class ["header-primary"]}
              {:fx/type :label
               :text "Routing targets for cues • Assign projectors to groups in the Projectors tab"
               :style-class ["label-secondary"]}
              {:fx/type :h-box
               :spacing 16
               :v-box/vgrow :always
               :children [{:fx/type zone-groups-panel}
                          {:fx/type :v-box
                           :h-box/hgrow :always
                           :children [{:fx/type outputs-panel}]}
                          {:fx/type details-panel}]}]})
