(ns laser-show.views.dialogs.cue-chain-editor
  "Cue chain editor dialog.
   
   Two-column layout:
   - Left column (280px): Cue chain list (top) + Item effects list (bottom) with drag-and-drop reordering
   - Right column: Preset bank + Preset params + Effect bank + Effect params with custom renderers
   
   Features:
   - Multi-select with Click, Ctrl+Click, Shift+Click for both cue items and effects
   - Keyboard shortcuts: Ctrl+C (copy), Ctrl+V (paste), Ctrl+A (select all), Delete
   - Drag-and-drop reordering in both cue chain and effects
   - Copy/paste with separate clipboards for cue chain vs item effects
   - Effect grouping within items
   - Custom parameter renderers (RGB curves, spatial editors)"
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs]
            [laser-show.animation.presets :as presets]
            [laser-show.animation.effects :as effects]
            [laser-show.animation.chains :as chains]
            [laser-show.events.core :as events]
            [laser-show.css.core :as css]
            [laser-show.views.components.list :as list]
            [laser-show.views.components.inline-edit :as inline-edit]
            [laser-show.views.components.preset-bank :as preset-bank]
            [laser-show.views.components.preset-param-editor :as preset-param-editor]
            [laser-show.views.components.effect-parameter-editor :as effect-param-editor]
            [laser-show.views.components.effect-bank :as effect-bank]
            [laser-show.views.components.trigger-bank :as trigger-bank]
            [laser-show.routing.zone-effects :as zone-effects]
            [laser-show.common.util :as u])
  (:import [javafx.scene.input KeyCode KeyEvent]))


;; Effect Bank (using data-driven component)


(defn- effect-bank-tabs
  "Tabbed effect bank using data-driven effect-bank component."
  [{:keys [col row item-path active-effect-tab]}]
  (let [effects-parent-path (vec (concat item-path [:effects]))]
    {:fx/type effect-bank/effect-bank
     :active-tab (or active-effect-tab :shape)
     :on-tab-change {:event/type :cue-chain/set-effect-tab}
     ;; Data-driven event template - handler will receive :item-id and :item
     :item-event-template {:event/type :cue-chain/add-effect-from-bank
                           :col col
                           :row row
                           :parent-path effects-parent-path}
     ;; Include zone effects tab for routing control
     :include-zone? true}))


;; Parameter Editor for Effects (using shared component)


(defn- item-effect-parameter-editor
  "Parameter editor for the selected effect within an item.
   Uses the shared effect-parameter-editor component with keyframe support."
  [{:keys [fx/context col row item-path item selected-effect-ids dialog-data]}]
  (let [item-effects (:effects item [])
        first-selected-id (first selected-effect-ids)
        ;; Use hierarchical path finding for nested effects
        effect-path (when first-selected-id
                      (chains/find-path-by-id item-effects first-selected-id))
        ;; Use path to retrieve the effect from nested structure
        selected-effect (when effect-path
                          (chains/get-item-at-path item-effects effect-path))
        effect-def (when selected-effect
                     (effects/get-effect (:effect-id selected-effect)))]
    (if selected-effect
      ;; Use shared effect parameter editor component with item-path for cue chains
      {:fx/type effect-param-editor/effect-parameter-editor
       :fx/context context
       :domain :cue-chains
       :entity-key [col row]
       :effect-path effect-path
       :item-path item-path  ;; Important: pass item-path for cue chains domain
       :effect selected-effect
       :effect-def effect-def
       :dialog-data dialog-data}
      ;; No effect selected - show placeholder
      {:fx/type :v-box
       :spacing 8
       :style-class "dialog-section"
       :children [{:fx/type :label
                   :text "PARAMETERS"
                   :style-class "header-section"}
                  {:fx/type :label
                   :text "Select an effect from the chain"
                   :style-class "dialog-placeholder-text"}]})))


;; Destination Zone Picker & Item Routing


(defn- destination-zone-dropdown
  "Dropdown for selecting destination zone group for the entire cue chain.
   Renders as a horizontal row: 'Default Zone (Chain Fallback):' label + dropdown.
   
   The destination zone applies to all presets in this cue chain cell.
   
   Props:
   - :col, :row - Grid coordinates
   - :destination-zone - Current destination-zone config at cue chain level
   - :zone-groups - List of available zone groups"
  [{:keys [col row destination-zone zone-groups]}]
  (let [current-group-id (:zone-group-id destination-zone :all)
        ;; Find the currently selected group for display
        current-group (u/seek #(= (:id %) current-group-id) zone-groups)]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type :label
                 :text "Default Zone (Chain Fallback):"
                 :style-class "zone-picker-label"}
                {:fx/type :combo-box
                 :value current-group
                 :pref-width 150
                 :items zone-groups
                 :button-cell (fn [group]
                                {:text (or (:name group) "All")})
                 :cell-factory {:fx/cell-type :list-cell
                                :describe (fn [group]
                                            {:text (or (:name group) "All")})}
                 :on-value-changed {:event/type :cue-chain/set-destination-zone-group
                                    :col col
                                    :row row}}]}))

(defn- resolve-item-parent-group-zone
  "Traverse up ancestor groups of an item in items to find any inherited zone."
  [items item-id]
  (when-let [path (chains/find-path-by-id items item-id)]
    (loop [curr-path (when (> (count path) 2)
                       (subvec path 0 (- (count path) 2)))]
      (when (seq curr-path)
        (let [group (chains/get-item-at-path items curr-path)]
          (or (:zone-group-id group)
              (when (> (count curr-path) 2)
                (recur (subvec curr-path 0 (- (count curr-path) 2))))))))))

(defn- cue-chain-item-label
  "Custom list-editor label renderer for cue chain preset items.
   Displays the preset name and its effective zone destination."
  [items zone-groups-by-id default-zone-id item]
  (let [preset-def (presets/presets-by-id (:preset-id item))
        base-name (or (:name preset-def) (:name item) "Unknown Preset")
        explicit-zone (or (:zone-group-id item)
                          (when-let [fx (first (zone-effects/extract-zone-effects (:effects item)))]
                            (get-in fx [:params :target-zone])))
        parent-group-zone (when-not explicit-zone
                            (resolve-item-parent-group-zone items (:id item)))
        effective-zone (or explicit-zone parent-group-zone default-zone-id :all)
        zone-name (or (get-in zone-groups-by-id [effective-zone :name])
                      (if effective-zone (name effective-zone) "All"))]
    (cond
      explicit-zone
      (str base-name " [" zone-name "]")

      parent-group-zone
      (str base-name " [" zone-name " (Group)]")

      :else
      (str base-name " [" zone-name "]"))))

(defn- cue-chain-group-label
  "Custom list-editor label renderer for cue chain groups.
   Displays the group name and its effective zone destination."
  [items zone-groups-by-id default-zone-id group]
  (let [base-name (or (:name group) "Group")
        explicit-zone (:zone-group-id group)
        parent-group-zone (when-not explicit-zone
                            (resolve-item-parent-group-zone items (:id group)))
        effective-zone (or explicit-zone parent-group-zone default-zone-id :all)
        zone-name (or (get-in zone-groups-by-id [effective-zone :name])
                      (if effective-zone (name effective-zone) "All"))]
    (cond
      explicit-zone
      (str base-name " [" zone-name "]")

      parent-group-zone
      (str base-name " [" zone-name " (Group)]")

      :else
      (str base-name " [" zone-name "]"))))

(defn- cue-chain-context-menu
  "Builds a context menu for a cue chain item or group with zone routing options."
  [col row items zone-groups zone-groups-by-id default-zone-id item-or-group]
  (let [item-id (:id item-or-group)
        item-path (chains/find-path-by-id items item-id)]
    (when item-path
      (let [current-zone (:zone-group-id item-or-group)
            parent-group-zone (resolve-item-parent-group-zone items item-id)
            default-zone-name (or (get-in zone-groups-by-id [default-zone-id :name])
                                  (if default-zone-id (name default-zone-id) "All"))
            inherit-label (if parent-group-zone
                            (let [pname (or (get-in zone-groups-by-id [parent-group-zone :name])
                                            (name parent-group-zone))]
                              (str "Inherit (Group: " pname ")"))
                            (str "Inherit (Default: " default-zone-name ")"))
            inherit-item [{:fx/type :check-menu-item
                           :text (str (if (nil? current-zone) "✓ " "   ") inherit-label)
                           :selected (nil? current-zone)
                           :on-action {:event/type :cue-chain/set-item-zone
                                       :col col
                                       :row row
                                       :item-path item-path
                                       :zone-group-id nil}}
                          {:fx/type :separator-menu-item}]
            zone-items (mapv
                        (fn [zg]
                          (let [zid (:id zg)
                                active? (= zid current-zone)]
                            {:fx/type :check-menu-item
                             :text (str (if active? "✓ " "   ") (:name zg (name zid)))
                             :selected active?
                             :on-action {:event/type :cue-chain/set-item-zone
                                         :col col
                                         :row row
                                         :item-path item-path
                                         :zone-group-id zid}}))
                        zone-groups)
            all-zone-items (into inherit-item zone-items)]
        {:fx/type :context-menu
         :items [{:fx/type :menu
                  :text "Assign Zone"
                  :items all-zone-items}]}))))

(defn- item-zone-destination-picker
  "Dropdown for selecting destination zone for a specific preset or group.
   Renders as a horizontal row: 'Zone Destination:' label + dropdown.
   
   Supports inheriting from parent group or chain default.
   
   Props:
   - :col, :row - Grid coordinates
   - :item-path - Path vector to item in cue chain
   - :item - The item or group map
   - :inherit-label - Descriptive label for inheritance (e.g. 'Inherit (Group: Front)')
   - :zone-groups - List of available zone groups"
  [{:keys [col row item-path item inherit-label zone-groups]}]
  (let [current-zone-id (:zone-group-id item)
        inherit-option {:id :inherit
                        :name inherit-label}
        options (into [inherit-option] zone-groups)
        current-option (if current-zone-id
                         (or (u/seek #(= (:id %) current-zone-id) zone-groups)
                             inherit-option)
                         inherit-option)]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type :label
                 :text "Zone Destination:"
                 :style-class "zone-picker-label"}
                {:fx/type :combo-box
                 :value current-option
                 :pref-width 180
                 :items options
                 :button-cell (fn [opt]
                                {:text (or (:name opt) "All")})
                 :cell-factory {:fx/cell-type :list-cell
                                :describe (fn [opt]
                                            {:text (or (:name opt) "All")})}
                 :on-value-changed {:event/type :cue-chain/set-item-zone
                                    :col col
                                    :row row
                                    :item-path item-path}}]}))


;; Trigger Mode Picker


(def ^:private trigger-mode-options
  "Options for per-cue trigger mode dropdown."
  [{:id :toggle :name "Toggle"}
   {:id :retrigger :name "Retrigger"}])

(defn- trigger-mode-dropdown
  "Dropdown for selecting trigger mode for this cue chain.
   Renders as a horizontal row: 'Trigger Mode:' label + dropdown.
   
   Trigger modes:
   - :toggle (default) - Click to turn ON, click again to turn OFF
   - :retrigger - Always start/restart the cue on click
   
   Note: Global trigger mode setting can override this per-cue setting.
   
   Props:
   - :col, :row - Grid coordinates
   - :trigger-mode - Current trigger mode (default :toggle)"
  [{:keys [col row trigger-mode]}]
  (let [current-mode (or trigger-mode :toggle)
        current-option (or (u/seek #(= (:id %) current-mode) trigger-mode-options)
                           (first trigger-mode-options))]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type :label
                 :text "Trigger Mode:"
                 :style-class "zone-picker-label"}
                {:fx/type :combo-box
                 :value current-option
                 :pref-width 120
                 :items trigger-mode-options
                 :button-cell (fn [mode]
                                {:text (or (:name mode) "Toggle")})
                 :cell-factory {:fx/cell-type :list-cell
                                :describe (fn [mode]
                                            {:text (or (:name mode) "Toggle")})}
                 :on-value-changed {:event/type :cue-chain/set-trigger-mode
                                    :col col
                                    :row row}}]}))


;; Middle Section (Preset Config)


(defn- middle-section
  "Middle section with preset bank and parameter editor."
  [{:keys [fx/context col row active-preset-tab selected-item-path cue-chain zone-groups]}]
  (let [;; Get selected item instance if single selection
        items (:items cue-chain [])
        selected-item (when selected-item-path
                        (chains/get-item-at-path items selected-item-path))
        is-preset? (and selected-item (= :preset (:type selected-item)))
        is-group? (and selected-item (chains/group? selected-item))
        default-zone-id (get-in cue-chain [:destination-zone :zone-group-id] :all)
        zone-groups-by-id (into {} (map (juxt :id identity)) zone-groups)
        default-zone-name (or (get-in zone-groups-by-id [default-zone-id :name])
                              (if default-zone-id (name default-zone-id) "All"))
        parent-group-zone (when selected-item
                            (resolve-item-parent-group-zone items (:id selected-item)))
        inherit-label (if parent-group-zone
                        (let [pname (or (get-in zone-groups-by-id [parent-group-zone :name])
                                        (name parent-group-zone))]
                          (str "Inherit (Group: " pname ")"))
                        (str "Inherit (Default: " default-zone-name ")"))]
    {:fx/type :v-box
     :spacing 0
     :children (filterv
                some?
                [;; Preset bank tabs (top)
                 {:fx/type preset-bank/preset-bank
                  :cell [col row]
                  :active-tab active-preset-tab
                  :on-tab-change {:event/type :cue-chain/set-preset-tab}}

                 ;; Optional: Trigger Bank when selected
                 (when (= active-preset-tab :triggers)
                   (let [trigger-map (fx/sub-ctx context subs/input-trigger-map)
                         learning-target (fx/sub-ctx context subs/input-learning-target)]
                     {:fx/type trigger-bank/trigger-bank
                      :col col
                      :row row
                      :trigger-map trigger-map
                      :learning-target learning-target}))

                 ;; Preset/Group parameter editor (bottom)
                 (if is-preset?
                   {:fx/type :v-box
                    :v-box/vgrow :always
                    :spacing 4
                    :children [{:fx/type :h-box
                                :style-class "dialog-section"
                                :children [{:fx/type item-zone-destination-picker
                                            :col col
                                            :row row
                                            :item-path selected-item-path
                                            :item selected-item
                                            :inherit-label inherit-label
                                            :zone-groups zone-groups}]}
                               {:fx/type preset-param-editor/preset-param-editor
                                :v-box/vgrow :always
                                :cell [col row]
                                :preset-path selected-item-path
                                :preset-instance selected-item}]}
                   (when is-group?
                     {:fx/type :v-box
                      :style-class "dialog-section"
                      :v-box/vgrow :always
                      :spacing 8
                      :children [{:fx/type :label
                                  :text "GROUP PROPERTIES"
                                  :style-class "header-section"}
                                 {:fx/type :label
                                  :text (str "Name: " (:name selected-item "Unnamed Group"))
                                  :style-class "group-properties-label"}
                                 {:fx/type :label
                                  :text (str "Items: " (count (:items selected-item [])))
                                  :style-class "group-properties-label"}
                                 {:fx/type item-zone-destination-picker
                                  :col col
                                  :row row
                                  :item-path selected-item-path
                                  :item selected-item
                                  :inherit-label inherit-label
                                  :zone-groups zone-groups}
                                 {:fx/type :label
                                  :text "Items inside this group inherit this zone unless individually overridden."
                                  :style-class "dialog-placeholder-text"}]}))

                 (when (not selected-item)
                   {:fx/type :v-box
                    :v-box/vgrow :always
                    :style-class "dialog-section"
                    :children [{:fx/type :label
                                :text "ITEM PROPERTIES"
                                :style-class "header-section"}
                               {:fx/type :label
                                :text "Select a preset or group from the chain"
                                :style-class "dialog-placeholder-text"}]})])}))


;; Helper Functions for Item Effects UI State


(defn- get-item-effects-clipboard
  "Get the clipboard for item effects, specific to the selected item."
  [item-effects-ui item-path]
  (get-in item-effects-ui [(vec item-path) :clipboard] []))

;; NOTE: get-item-effects-selected-ids removed - now reading from subs/list-ui-state


;; Main Dialog Content


(defn- cue-chain-editor-content
  "Main content of the cue chain editor dialog with 2-column layout.
   Left column: Cue chain list (top) + Item effects list (bottom).
   Right column: Preset config + Effect bank + Effect params."
  [{:keys [fx/context]}]
  (let [;; Get cue-chain-editor state from unified dialogs path
        dialog-data (fx/sub-ctx context subs/dialog-data :cue-chain-editor)
        {:keys [col row active-preset-tab active-effect-tab clipboard item-effects-ui]} dialog-data

        ;; Read cue chain selection from canonical list-ui state
        cue-list-state (fx/sub-ctx context subs/list-ui-state [:cue-chain col row])
        selected-ids (:selected-ids cue-list-state #{})

        ;; Read item effects selection from canonical list-ui state
        item-effects-list-state (fx/sub-ctx context subs/list-ui-state [:item-effects col row])
        selected-effect-ids (:selected-ids item-effects-list-state #{})

        ;; Get cue chain data from new unified :chains domain
        chains-state (fx/sub-val context :chains)
        cue-chain (or (get-in chains-state [:cue-chains [col row]]) {:items []})

        ;; For parameter editor - resolve first selected ID to path
        first-selected-id (when (= 1 (count selected-ids))
                            (first selected-ids))
        first-selected-path (when first-selected-id
                              (chains/find-path-by-id (:items cue-chain) first-selected-id))

        ;; Get selected item for effects
        selected-item (when first-selected-path
                        (chains/get-item-at-path (:items cue-chain) first-selected-path))
        has-item? (boolean selected-item)
        item-effects (:effects selected-item [])

        ;; Clipboard check
        clipboard-items (:items clipboard)

        ;; Get clipboard for item effects
        item-effects-clipboard (when has-item?
                                 (get-item-effects-clipboard item-effects-ui first-selected-path))

        ;; Dialog data for custom renderers (UI modes, etc.)
        ;; Merge ui-modes and item-effects-ui together so effects can access both
        effect-dialog-data (merge dialog-data item-effects-ui {:item-effects-ui item-effects-ui})

        ;; Get zone groups for destination picker in footer and routing labels
        zone-groups (fx/sub-ctx context subs/zone-groups-list)
        zone-groups-by-id (into {} (map (juxt :id identity)) zone-groups)
        default-zone-id (get-in cue-chain [:destination-zone :zone-group-id] :all)

        ;; Get chain name for header
        chain-name (:name cue-chain)
        default-name (str "Cell " (char (+ 65 (or row 0))) (inc (or col 0)))
        editing-name? (:editing-name? dialog-data false)]
    {:fx/type :v-box
     :spacing 0
     :style-class "dialog-content"
     :pref-width 800
     :pref-height 700
     :children [;; Header with editable name (double-click to edit)
                {:fx/type :h-box
                 :alignment :center-left
                 :style-class "dialog-header"
                 :children [{:fx/type inline-edit/inline-edit-text
                             :value chain-name
                             :placeholder default-name
                             :editing? editing-name?
                             :on-start-edit {:event/type :ui/update-dialog-data
                                             :dialog-id :cue-chain-editor
                                             :editing-name? true}
                             :on-commit {:event/type :cue-chain/set-name
                                         :col col
                                         :row row}
                             :on-cancel {:event/type :ui/update-dialog-data
                                         :dialog-id :cue-chain-editor
                                         :editing-name? false}}]}
                ;; Main content area - 2 columns
                {:fx/type :h-box
                 :spacing 0
                 :v-box/vgrow :always
                 :children [;; Left column - cue chain list (top) + item effects (bottom)
                            {:fx/type :v-box
                             :style-class "cue-chain-left-column"
                             :children [;; Cue chain list (top)
                                        {:fx/type :v-box
                                         :pref-height 300
                                         :children [{:fx/type list/list-editor
                                                     :fx/context context
                                                     :v-box/vgrow :always
                                                     :items (:items cue-chain)
                                                     :component-id [:cue-chain col row]
                                                     :item-id-key :preset-id
                                                     :item-registry-fn presets/presets-by-id
                                                     :fallback-label "Unknown Preset"
                                                     :get-item-label (partial cue-chain-item-label (:items cue-chain) zone-groups-by-id default-zone-id)
                                                     :get-group-label (partial cue-chain-group-label (:items cue-chain) zone-groups-by-id default-zone-id)
                                                     :get-context-menu (partial cue-chain-context-menu col row (:items cue-chain) zone-groups zone-groups-by-id default-zone-id)
                                                     :get-group-context-menu (partial cue-chain-context-menu col row (:items cue-chain) zone-groups zone-groups-by-id default-zone-id)
                                                     :on-change-event :chain/set-items
                                                     :on-change-params {:domain :cue-chains :entity-key [col row]}
                                                     :items-path [:chains :cue-chains [col row] :items]
                                                     :on-copy-fn (fn [items]
                                                                   (events/dispatch! {:event/type :cue-chain/set-clipboard
                                                                                      :items items}))
                                                     :clipboard-items clipboard-items
                                                     :header-label "CUE CHAIN"
                                                     :empty-text "No presets\nAdd from bank →"}]}

                                        ;; Item effects list (bottom) - always present, shows placeholder if no selection
                                        {:fx/type :v-box
                                         :v-box/vgrow :always
                                         :style-class "dialog-separator"
                                         :children (if has-item?
                                                     [{:fx/type list/list-editor
                                                       :fx/context context
                                                       :v-box/vgrow :always
                                                       :items item-effects
                                                       ;; Use stable component-id without the path to avoid state fragmentation
                                                       ;; The item-path is passed via event params instead
                                                       :component-id [:item-effects col row]
                                                       :item-id-key :effect-id
                                                       :item-registry-fn effects/get-effect
                                                       :fallback-label "Unknown Effect"
                                                       :on-change-event :cue-chain/set-item-effects
                                                       :on-change-params {:col col :row row :item-path first-selected-path}
                                                       ;; Direct path to items in state for async drag-drop
                                                       ;; Path: [:chains :cue-chains [col row] :items <first-selected-path...> :effects]
                                                       :items-path (vec (concat [:chains :cue-chains [col row] :items] first-selected-path [:effects]))
                                                       :on-copy-fn (fn [items]
                                                                     (events/dispatch! {:event/type :cue-chain/set-item-effects-clipboard
                                                                                        :col col :row row
                                                                                        :item-path first-selected-path
                                                                                        :effects items}))
                                                       :clipboard-items item-effects-clipboard
                                                       :header-label "ITEM EFFECTS"
                                                       :empty-text "No effects\nAdd from bank →"
                                                       :allow-groups? true}]
                                                     ;; Placeholder when no item selected
                                                     [{:fx/type :v-box
                                                       :v-box/vgrow :always
                                                       :style-class "dialog-placeholder"
                                                       :alignment :center
                                                       :children [{:fx/type :label
                                                                   :text "Select an item above\nto edit its effects"
                                                                   :style-class "dialog-placeholder-text"}]}])}]}

                            ;; Right column - preset config + effect bank + effect params
                            {:fx/type :v-box
                             :h-box/hgrow :always
                             :spacing 0
                             :children (filterv some?
                                                [;; Preset bank + params
                                                 {:fx/type middle-section
                                                  :fx/context context
                                                  :col col :row row
                                                  :active-preset-tab active-preset-tab
                                                  :selected-item-path first-selected-path
                                                  :cue-chain cue-chain
                                                  :zone-groups zone-groups}

                                                 ;; Effect bank (when item selected)
                                                 (when has-item?
                                                   {:fx/type effect-bank-tabs
                                                    :col col :row row
                                                    :item-path first-selected-path
                                                    :active-effect-tab active-effect-tab})

                                                 ;; Effect parameter editor (when effect selected)
                                                 (when (and has-item? (seq selected-effect-ids))
                                                   {:fx/type item-effect-parameter-editor
                                                    :fx/context context
                                                    :v-box/vgrow :always
                                                    :col col :row row
                                                    :item-path first-selected-path
                                                    :item selected-item
                                                    :selected-effect-ids selected-effect-ids
                                                    :dialog-data effect-dialog-data})])}]}

                ;; Footer with destination zone dropdown, trigger mode, and close button
                {:fx/type :h-box
                 :alignment :center-left
                 :spacing 12
                 :style-class "dialog-footer"
                 :children [{:fx/type destination-zone-dropdown
                             :col col
                             :row row
                             :destination-zone (:destination-zone cue-chain)
                             :zone-groups zone-groups}
                            {:fx/type trigger-mode-dropdown
                             :col col
                             :row row
                             :trigger-mode (:trigger-mode cue-chain)}
                            {:fx/type :region
                             :h-box/hgrow :always}
                            {:fx/type :button
                             :text "Open in Timeline Editor"
                             :style-class "button-secondary"
                             :on-action (fn [_]
                                          (events/dispatch! {:event/type :ui/close-dialog
                                                             :dialog-id :cue-chain-editor})
                                          (events/dispatch! {:event/type :timeline-cue-editor/open
                                                             :col col
                                                             :row row}))}
                            {:fx/type :button
                             :text "Close"
                             :style-class "button-primary"
                             :on-action {:event/type :ui/close-dialog
                                         :dialog-id :cue-chain-editor}}]}]}))


;; Dialog Window


(defn- handle-scene-key-pressed
  "Handles undo/redo keyboard shortcuts at the scene level."
  [^KeyEvent e]
  (let [code (.getCode e)
        shortcut? (or (.isShortcutDown e) (.isControlDown e))
        shift? (.isShiftDown e)]
    (when shortcut?
      (cond
        (and (= code KeyCode/Z) shift?)
        (do (.consume e) (events/dispatch! {:event/type :edit/redo}))
        (= code KeyCode/Y)
        (do (.consume e) (events/dispatch! {:event/type :edit/redo}))
        (= code KeyCode/Z)
        (do (.consume e) (events/dispatch! {:event/type :edit/undo}))))))

(defn- cue-chain-editor-scene
  "Scene component for the cue chain editor.
   Keyboard shortcuts are handled by the list components and scene accelerators."
  [{:keys [stylesheets]}]
  {:fx/type :scene
   :stylesheets stylesheets
   :on-key-pressed handle-scene-key-pressed
   :root {:fx/type cue-chain-editor-content}})

(defn cue-chain-editor-dialog
  "The cue chain editor dialog window."
  [{:keys [fx/context]}]
  (let [open? (fx/sub-ctx context subs/dialog-open? :cue-chain-editor)
        dialog-data (fx/sub-ctx context subs/dialog-data :cue-chain-editor)
        {:keys [col row]} dialog-data
        ;; Get chain data for name in title
        chains-state (fx/sub-val context :chains)
        cue-chain (get-in chains-state [:cue-chains [col row]])
        chain-name (:name cue-chain)
        stylesheets (css/dialog-stylesheet-urls)
        cell-id (str "Cell " (char (+ 65 (or row 0))) (inc (or col 0)))
        window-title (str "Cue Chain Editor - "
                          (if (seq chain-name)
                            (str chain-name " (" cell-id ")")
                            cell-id))]
    {:fx/type :stage
     :showing open?
     :title window-title
     :modality :none
     :on-close-request {:event/type :ui/close-dialog :dialog-id :cue-chain-editor}
     :scene {:fx/type cue-chain-editor-scene
             :stylesheets stylesheets}}))
