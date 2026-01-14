(ns laser-show.views.tabs.zones
  "Zones tab - for managing zones and zone groups.
   
   Features:
   - View and edit zone groups (left panel)
   - View zones grouped by projector (center panel)
   - View zone/zone group usage (right panel)
   - Zone geometry effects editor"
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs]
            [laser-show.css.core :as css]))


;; Zone Type Badge


(defn- zone-type-badge
  "Badge showing zone type with appropriate color."
  [{:keys [type]}]
  (let [color (case type
                :default "#4A6FA5"
                :graphics "#9B59B6"
                :crowd-scanning "#E67E22"
                "#808080")
        label (case type
                :default "Default"
                :graphics "Graphics"
                :crowd-scanning "Crowd"
                "Unknown")]
    {:fx/type :label
     :text label
     :style (str "-fx-background-color: " color "; "
                "-fx-text-fill: white; "
                "-fx-padding: 2 6; "
                "-fx-background-radius: 3; "
                "-fx-font-size: 9;")}))


;; Zone Group Chip


(defn- zone-group-chip
  "Small colored chip for a zone group."
  [{:keys [group selected? on-click]}]
  (let [{:keys [name color]} group]
    {:fx/type :label
     :text name
     :style (str "-fx-background-color: " (if selected? color (str color "80")) "; "
                "-fx-text-fill: white; "
                "-fx-padding: 2 6; "
                "-fx-background-radius: 8; "
                "-fx-font-size: 10;"
                (when on-click " -fx-cursor: hand;"))
     :on-mouse-clicked on-click}))


;; Zone Groups Panel (Left)


(defn- zone-group-item
  "Single zone group in the list."
  [{:keys [group selected? zone-count]}]
  (let [{:keys [id name description color]} group]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :padding 8
     :style (str "-fx-background-color: " (if selected? "#4A6FA5" "#3D3D3D") "; "
                "-fx-background-radius: 4;")
     :on-mouse-clicked {:event/type :zone-groups/select
                        :group-id id}
     :children [{:fx/type :circle
                 :radius 6
                 :fill color}
                {:fx/type :v-box
                 :h-box/hgrow :always
                 :children [{:fx/type :label
                             :text name
                             :style "-fx-text-fill: white; -fx-font-weight: bold;"}
                            {:fx/type :label
                             :text (or description "")
                             :style "-fx-text-fill: #808080; -fx-font-size: 10;"}]}
                {:fx/type :label
                 :text (str zone-count)
                 :style "-fx-text-fill: #606060; -fx-font-size: 11;"}]}))

(defn- zone-groups-panel
  "Panel listing all zone groups."
  [{:keys [fx/context]}]
  (let [groups (fx/sub-ctx context subs/zone-groups-list)
        selected-id (fx/sub-ctx context subs/selected-zone-group-id)]
    {:fx/type :v-box
     :spacing 8
     :pref-width 220
     :children [{:fx/type :h-box
                 :alignment :center-left
                 :children [{:fx/type :label
                             :text "ZONE GROUPS"
                             :style "-fx-text-fill: #808080; -fx-font-size: 11; -fx-font-weight: bold;"}
                            {:fx/type :region :h-box/hgrow :always}
                            {:fx/type :button
                             :text "+"
                             :style "-fx-background-color: #505050; -fx-text-fill: white; -fx-font-size: 12; -fx-padding: 2 8;"
                             :on-action {:event/type :zone-groups/add}}]}
                {:fx/type :scroll-pane
                 :fit-to-width true
                 :v-box/vgrow :always
                 :style "-fx-background-color: #2D2D2D; -fx-background: #2D2D2D;"
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
                                                 :zone-count (:zone-count usage 0)})))
                                       [{:fx/type :label
                                         :text "No zone groups defined"
                                         :style "-fx-text-fill: #606060; -fx-font-style: italic; -fx-padding: 16;"}])}}]}))


;; Zones Panel (Center)


(defn- zone-item
  "Single zone in the list."
  [{:keys [zone selected?]}]
  (let [{:keys [id name type enabled? zone-groups]} zone]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :padding 8
     :style (str "-fx-background-color: " (if selected? "#4A6FA5" "#3D3D3D") "; "
                "-fx-background-radius: 4;"
                (when-not enabled? " -fx-opacity: 0.5;"))
     :on-mouse-clicked {:event/type :zones/select-zone
                        :zone-id id}
     :children [{:fx/type :check-box
                 :selected enabled?
                 :on-selected-changed {:event/type :zones/toggle-enabled
                                       :zone-id id}}
                {:fx/type :v-box
                 :h-box/hgrow :always
                 :spacing 2
                 :children [{:fx/type :h-box
                             :spacing 6
                             :alignment :center-left
                             :children [{:fx/type :label
                                         :text name
                                         :style "-fx-text-fill: white; -fx-font-weight: bold;"}
                                        {:fx/type zone-type-badge
                                         :type type}]}
                            {:fx/type :h-box
                             :spacing 4
                             :children (vec (for [gid zone-groups]
                                              {:fx/type :label
                                               :fx/key gid
                                               :text (clojure.core/name gid)
                                               :style "-fx-text-fill: #808080; -fx-font-size: 9;"}))}]}]}))

(defn- projector-zone-section
  "Section showing zones for a single projector.
   Note: Uses plain props (not context) for projector lookup to avoid subscription cascade."
  [{:keys [projector-name zones selected-zone-id]}]
  {:fx/type :v-box
   :spacing 4
   :children [{:fx/type :label
               :text (str "📽 " projector-name)
               :style "-fx-text-fill: #B0B0B0; -fx-font-weight: bold; -fx-font-size: 11;"}
              {:fx/type :v-box
               :spacing 2
               :padding {:left 12}
               :children (vec (for [zone zones]
                                {:fx/type zone-item
                                 :fx/key (:id zone)
                                 :zone zone
                                 :selected? (= (:id zone) selected-zone-id)}))}]})

(defn- zones-panel
  "Panel listing all zones grouped by projector."
  [{:keys [fx/context]}]
  (let [zones-by-proj (fx/sub-ctx context subs/zones-by-projector)
        selected-zone-id (fx/sub-ctx context subs/selected-zone-id)
        ;; Get projector names up front to avoid subscription in child components
        projectors-data (fx/sub-val context :projectors)]
    {:fx/type :v-box
     :spacing 8
     :children [{:fx/type :label
                 :text "ZONES"
                 :style "-fx-text-fill: #808080; -fx-font-size: 11; -fx-font-weight: bold;"}
                {:fx/type :scroll-pane
                 :fit-to-width true
                 :v-box/vgrow :always
                 :style "-fx-background-color: #2D2D2D; -fx-background: #2D2D2D;"
                 :content {:fx/type :v-box
                           :spacing 12
                           :padding 8
                           :children (if (seq zones-by-proj)
                                       (vec (for [[proj-id zones] zones-by-proj
                                                  :let [proj-name (get-in projectors-data [:items proj-id :name] (str proj-id))]]
                                              {:fx/type projector-zone-section
                                               :fx/key proj-id
                                               :projector-name proj-name
                                               :zones zones
                                               :selected-zone-id selected-zone-id}))
                                       [{:fx/type :label
                                         :text "No zones configured.\nAdd projectors to create zones."
                                         :style "-fx-text-fill: #606060; -fx-font-style: italic; -fx-padding: 16;"}])}}]}))


;; Usage/Details Panel (Right)


(defn- zone-details-panel
  "Details panel for the selected zone."
  [{:keys [fx/context]}]
  (let [zone (fx/sub-ctx context subs/selected-zone)
        zone-groups-list (fx/sub-ctx context subs/zone-groups-list)]
    (if zone
      {:fx/type :v-box
       :spacing 12
       :children [{:fx/type :label
                   :text (str "Zone: " (:name zone))
                   :style "-fx-text-fill: white; -fx-font-size: 14; -fx-font-weight: bold;"}
                  {:fx/type :separator}
                  ;; Zone info
                  {:fx/type :v-box
                   :spacing 4
                   :children [{:fx/type :h-box
                               :spacing 8
                               :children [{:fx/type :label
                                           :text "Type:"
                                           :style "-fx-text-fill: #808080;"}
                                          {:fx/type zone-type-badge
                                           :type (:type zone)}]}
                              {:fx/type :h-box
                               :spacing 8
                               :children [{:fx/type :label
                                           :text "Enabled:"
                                           :style "-fx-text-fill: #808080;"}
                                          {:fx/type :label
                                           :text (if (:enabled? zone) "Yes" "No")
                                           :style (str "-fx-text-fill: "
                                                      (if (:enabled? zone) "#4CAF50" "#F44336") ";")}]}]}
                  {:fx/type :separator}
                  ;; Zone group membership
                  {:fx/type :label
                   :text "ZONE GROUPS"
                   :style "-fx-text-fill: #808080; -fx-font-size: 10; -fx-font-weight: bold;"}
                  {:fx/type :flow-pane
                   :hgap 4
                   :vgap 4
                   :children (vec (for [group zone-groups-list]
                                    (let [member? (some #{(:id group)} (:zone-groups zone []))]
                                      {:fx/type zone-group-chip
                                       :fx/key (:id group)
                                       :group group
                                       :selected? member?
                                       :on-click {:event/type :zones/toggle-zone-group
                                                  :zone-id (:id zone)
                                                  :group-id (:id group)}})))}
                  {:fx/type :region :v-box/vgrow :always}
                  ;; Edit effects button
                  {:fx/type :button
                   :text "Edit Zone Effects..."
                   :style "-fx-background-color: #505050; -fx-text-fill: white; -fx-padding: 8 16;"
                   :on-action {:event/type :zones/edit-effects
                               :zone-id (:id zone)}}]}
      ;; No zone selected
      {:fx/type :v-box
       :children [{:fx/type :label
                   :text "Select a zone to view details"
                   :style "-fx-text-fill: #606060; -fx-font-style: italic;"}]})))

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
                               :style "-fx-text-fill: white; -fx-font-size: 14; -fx-font-weight: bold;"}]}
                  {:fx/type :label
                   :text (or (:description group) "No description")
                   :style "-fx-text-fill: #808080; -fx-font-style: italic;"}
                  {:fx/type :separator}
                  ;; Usage stats
                  {:fx/type :v-box
                   :spacing 4
                   :children [{:fx/type :label
                               :text (str "Zones in group: " (:zone-count usage 0))
                               :style "-fx-text-fill: #B0B0B0;"}
                              {:fx/type :label
                               :text (str "Cues targeting: " (:cue-count usage 0))
                               :style "-fx-text-fill: #B0B0B0;"}]}
                  {:fx/type :separator}
                  ;; Zones in this group
                  {:fx/type :label
                   :text "ZONES IN THIS GROUP"
                   :style "-fx-text-fill: #808080; -fx-font-size: 10; -fx-font-weight: bold;"}
                  {:fx/type :v-box
                   :spacing 2
                   :children (if (seq (:zones usage))
                               (vec (for [zone (:zones usage)]
                                      {:fx/type :label
                                       :fx/key (:id zone)
                                       :text (str "• " (:name zone))
                                       :style "-fx-text-fill: #B0B0B0; -fx-font-size: 11;"}))
                               [{:fx/type :label
                                 :text "No zones in this group"
                                 :style "-fx-text-fill: #606060; -fx-font-style: italic;"}])}
                  {:fx/type :region :v-box/vgrow :always}
                  ;; Edit/Delete buttons
                  {:fx/type :h-box
                   :spacing 8
                   :children [{:fx/type :button
                               :text "Edit..."
                               :style "-fx-background-color: #505050; -fx-text-fill: white; -fx-padding: 6 12;"
                               :on-action {:event/type :zone-groups/edit
                                           :group-id (:id group)}}
                              {:fx/type :button
                               :text "Delete"
                               :style "-fx-background-color: #663333; -fx-text-fill: white; -fx-padding: 6 12;"
                               :on-action {:event/type :zone-groups/remove
                                           :group-id (:id group)}}]}]}
      ;; No group selected
      {:fx/type :v-box
       :children [{:fx/type :label
                   :text "Select a zone group to view details"
                   :style "-fx-text-fill: #606060; -fx-font-style: italic;"}]})))

(defn- details-panel
  "Right panel showing details of selected zone or zone group.
   Uses conditional rendering based on selection state.
   Child panels receive context automatically via fn->lifecycle-with-context."
  [{:keys [fx/context]}]
  (let [selected-zone-id (fx/sub-ctx context subs/selected-zone-id)
        selected-group-id (fx/sub-ctx context subs/selected-zone-group-id)]
    {:fx/type :v-box
     :spacing 8
     :pref-width 260
     :children [{:fx/type :label
                 :text "DETAILS"
                 :style "-fx-text-fill: #808080; -fx-font-size: 11; -fx-font-weight: bold;"}
                {:fx/type :scroll-pane
                 :fit-to-width true
                 :v-box/vgrow :always
                 :style "-fx-background-color: #2D2D2D; -fx-background: #2D2D2D;"
                 :content {:fx/type :v-box
                           :padding 12
                           :children [(cond
                                        ;; Context is automatically passed to function fx-types
                                        selected-zone-id
                                        {:fx/type zone-details-panel}
                                        
                                        selected-group-id
                                        {:fx/type zone-group-details-panel}
                                        
                                        :else
                                        {:fx/type :label
                                         :text "Select a zone or zone group\nto view details"
                                         :style "-fx-text-fill: #606060; -fx-font-style: italic;"})]}}]}))


;; Main Tab


(defn zones-tab
  "Complete zones tab with zone groups, zones, and details panels."
  [{:keys [fx/context]}]
  {:fx/type :v-box
   :style (str "-fx-background-color: " (css/bg-primary) ";")
   :padding 16
   :spacing 8
   :children [{:fx/type :label
               :text "Zone Configuration"
               :style "-fx-text-fill: white; -fx-font-size: 16; -fx-font-weight: bold;"}
              {:fx/type :label
               :text "Manage zone groups • Configure zone geometry • Assign zones to groups"
               :style "-fx-text-fill: #808080; -fx-font-size: 11;"}
              {:fx/type :h-box
               :spacing 16
               :v-box/vgrow :always
               :children [{:fx/type zone-groups-panel}
                          {:fx/type :v-box
                           :h-box/hgrow :always
                           :children [{:fx/type zones-panel}]}
                          {:fx/type details-panel}]}]})
