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
            [laser-show.views.components.preset-bank :as preset-bank]
            [laser-show.views.components.preset-param-editor :as param-editor]
            [laser-show.views.components.effect-param-ui :as effect-param-ui]
            [laser-show.views.components.effect-bank :as effect-bank]
            [laser-show.common.util :as u]
            [clojure.tools.logging :as log])
  (:import [javafx.scene.input KeyCode KeyEvent]))


;; Effect Bank (using data-driven component)


;; Use shared params-vector->map from effect-param-ui
(def params-vector->map effect-param-ui/params-vector->map)

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
     :include-zone? true
     :pref-height 140}))


;; Parameter Editor for Effects (with Custom Renderers)


(defn- custom-param-renderer
  "Renders parameters with custom UI and mode toggle using shared effect-param-ui.
   
   Props:
   - :fx/context - cljfx context (required for zone effects)
   - :col, :row - Grid cell coordinates
   - :item-path - Path to the item within cue chain
   - :effect-path - Path to effect within item's effect chain
   - :effect-def - Effect definition with :ui-hints
   - :current-params - Current parameter values
   - :ui-mode - Current UI mode (:visual or :numeric)
   - :params-map - Parameter specifications map
   - :dialog-data - Dialog state data (for curve editor channel tab tracking)"
  [{:keys [fx/context col row item-path effect-path effect-def current-params ui-mode params-map dialog-data]}]
  ;; Build the full effect path from item-path to effect within item's :effects
  (let [full-effect-path (vec (concat item-path [:effects] effect-path))]
    {:fx/type effect-param-ui/custom-param-renderer
     :fx/context context
     :effect-def effect-def
     :current-params current-params
     :ui-mode ui-mode
     :params-map params-map
     :dialog-data dialog-data
     
     ;; Enable modulator support for numeric parameters
     :enable-modulators? true
     
     ;; Event templates for spatial editors (translate, corner-pin)
     :spatial-event-template {:event/type :chain/update-spatial-params
                              :domain :cue-chains
                              :entity-key [col row]
                              :effect-path full-effect-path}
     :spatial-event-keys {:domain :cue-chains :entity-key [col row] :effect-path full-effect-path}
     
     ;; Event templates for param controls
     :on-change-event {:event/type :chain/update-param
                       :domain :cue-chains
                       :entity-key [col row]
                       :effect-path full-effect-path}
     :on-text-event {:event/type :chain/update-param-from-text
                     :domain :cue-chains
                     :entity-key [col row]
                     :effect-path full-effect-path}
     :on-mode-change-event {:event/type :chain/set-ui-mode
                            :domain :cue-chains
                            :entity-key [col row]
                            :effect-path full-effect-path}
     ;; Modulator event base for modulator toggle/type/param operations
     :modulator-event-base {:domain :cue-chains
                            :entity-key [col row]
                            :effect-path full-effect-path}
     
     ;; RGB curves props - use :cue-chains domain with full effect path
     :rgb-domain :cue-chains
     :rgb-entity-key [col row]
     :rgb-effect-path full-effect-path}))

(defn- effect-parameter-editor
  "Parameter editor for the selected effect within an item."
  [{:keys [fx/context col row item-path item selected-effect-ids dialog-data]}]
  (let [effects (:effects item [])
        first-selected-id (first selected-effect-ids)
        ;; Use hierarchical path finding for nested effects
        effect-path (when first-selected-id
                      (chains/find-path-by-id effects first-selected-id))
        ;; Build full effect path: item-path + :effects + effect-path
        ;; IMPORTANT: This must be computed before ui-mode lookup since ui-mode is stored by full-effect-path
        full-effect-path (when effect-path
                           (vec (concat item-path [:effects] effect-path)))
        ;; Use path to retrieve the effect from nested structure
        selected-effect (when effect-path
                          (chains/get-item-at-path effects effect-path))
        effect-def (when selected-effect
                     (effects/get-effect (:effect-id selected-effect)))
        current-params (:params selected-effect {})
        params-map (params-vector->map (:parameters effect-def []))
        ;; IMPORTANT: ui-mode is stored by full-effect-path (item-path + :effects + effect-path)
        ;; so we must read it using the same key
        ui-mode (get-in dialog-data [:ui-modes full-effect-path])]
    {:fx/type :v-box
     :spacing 8
     :style "-fx-background-color: #2A2A2A; -fx-padding: 8;"
     :children [{:fx/type :label
                 :text (if effect-def
                         (str "PARAMETERS: " (:name effect-def))
                         "PARAMETERS")
                 :style "-fx-text-fill: #808080; -fx-font-size: 11; -fx-font-weight: bold;"}
                (if effect-def
                  (if (:ui-hints effect-def)
                    ;; Has custom UI - use custom renderer with mode toggle
                    {:fx/type :scroll-pane
                     :fit-to-width true
                     :v-box/vgrow :always
                     :style "-fx-background-color: transparent; -fx-background: #2A2A2A;"
                     :content {:fx/type custom-param-renderer
                              :fx/context context
                              :col col :row row
                              :item-path item-path
                              :effect-path effect-path
                              :effect-def effect-def
                              :current-params current-params
                              :ui-mode ui-mode
                              :params-map params-map
                              :dialog-data dialog-data}}
                    
                    ;; Standard parameters - use modulatable param controls
                    {:fx/type :scroll-pane
                     :fit-to-width true
                     :v-box/vgrow :always
                     :style "-fx-background-color: transparent; -fx-background: #2A2A2A;"
                     :content {:fx/type effect-param-ui/modulatable-param-controls-list
                              :params-map params-map
                              :current-params current-params
                              :on-change-event {:event/type :chain/update-param
                                                :domain :cue-chains
                                                :entity-key [col row]
                                                :effect-path full-effect-path}
                              :on-text-event {:event/type :chain/update-param-from-text
                                              :domain :cue-chains
                                              :entity-key [col row]
                                              :effect-path full-effect-path}
                              :modulator-event-base {:domain :cue-chains
                                                     :entity-key [col row]
                                                     :effect-path full-effect-path}}})
                  
                  {:fx/type :label
                   :text "Select an effect from the chain"
                   :style "-fx-text-fill: #606060; -fx-font-style: italic; -fx-font-size: 11;"})]}))


;; Destination Zone Picker


(defn- zone-group-chip
  "Small colored chip for a zone group selection."
  [{:keys [group selected? on-click]}]
  (let [{:keys [id name color]} group]
    {:fx/type :label
     :text name
     :style (str "-fx-background-color: " (if selected? color (str color "60")) "; "
                "-fx-text-fill: white; "
                "-fx-padding: 3 8; "
                "-fx-background-radius: 10; "
                "-fx-font-size: 10;"
                (when on-click " -fx-cursor: hand;"))
     :on-mouse-clicked on-click}))

(defn- destination-zone-picker
  "UI for selecting destination zone group for a cue item.
   
   Props:
   - :col, :row - Grid coordinates
   - :item-path - Path to the item
   - :destination-zone - Current destination-zone config
   - :zone-groups - List of available zone groups"
  [{:keys [col row item-path destination-zone zone-groups]}]
  (let [current-mode (or (:mode destination-zone) :zone-group)
        current-group-id (:zone-group-id destination-zone :all)
        preferred-type (or (:preferred-zone-type destination-zone) :default)]
    {:fx/type :v-box
     :spacing 6
     :style "-fx-background-color: #333333; -fx-padding: 8; -fx-background-radius: 4;"
     :children [{:fx/type :label
                 :text "DESTINATION ZONE"
                 :style "-fx-text-fill: #808080; -fx-font-size: 10; -fx-font-weight: bold;"}
                ;; Zone group selection
                {:fx/type :flow-pane
                 :hgap 4
                 :vgap 4
                 :children (vec (for [group zone-groups]
                                  {:fx/type zone-group-chip
                                   :fx/key (:id group)
                                   :group group
                                   :selected? (= (:id group) current-group-id)
                                   :on-click {:event/type :cue-chain/set-destination-zone-group
                                              :col col
                                              :row row
                                              :item-path item-path
                                              :group-id (:id group)}}))}
                ;; Preferred zone type selector
                {:fx/type :h-box
                 :spacing 6
                 :alignment :center-left
                 :children [{:fx/type :label
                             :text "Prefer:"
                             :style "-fx-text-fill: #707070; -fx-font-size: 10;"}
                            {:fx/type :combo-box
                             :value preferred-type
                             :pref-width 100
                             :items [:default :graphics :crowd-scanning]
                             :style "-fx-font-size: 10;"
                             :button-cell (fn [type]
                                            {:text (case type
                                                     :default "Default"
                                                     :graphics "Graphics"
                                                     :crowd-scanning "Crowd"
                                                     "Default")})
                             :cell-factory {:fx/cell-type :list-cell
                                            :describe (fn [type]
                                                        {:text (case type
                                                                 :default "Default"
                                                                 :graphics "Graphics"
                                                                 :crowd-scanning "Crowd"
                                                                 "Default")})}
                             :on-value-changed {:event/type :cue-chain/set-preferred-zone-type
                                                :col col
                                                :row row
                                                :item-path item-path}}]}]}))


;; Middle Section (Preset Config)


(defn- middle-section
  "Middle section with preset bank and parameter editor."
  [{:keys [fx/context col row active-preset-tab selected-item-path cue-chain]}]
  (let [;; Get selected item instance if single selection
        selected-item (when selected-item-path
                        (chains/get-item-at-path (:items cue-chain) selected-item-path))
        is-preset? (and selected-item (= :preset (:type selected-item)))
        is-group? (and selected-item (chains/group? selected-item))
        ;; Get zone groups for destination picker
        zone-groups (fx/sub-ctx context subs/zone-groups-list)]
    {:fx/type :v-box
     :spacing 0
     :children (filterv
                 some?
                 [;; Preset bank tabs (top)
                  {:fx/type preset-bank/preset-bank
                   :cell [col row]
                   :active-tab active-preset-tab
                   :on-tab-change {:event/type :cue-chain/set-preset-tab}}
                  
                  ;; Preset/Group parameter editor (bottom)
                  (if is-preset?
                    {:fx/type :v-box
                     :spacing 8
                     :children [{:fx/type param-editor/preset-param-editor
                                 :cell [col row]
                                 :preset-path selected-item-path
                                 :preset-instance selected-item}
                                ;; Destination zone picker for presets
                                {:fx/type destination-zone-picker
                                 :col col
                                 :row row
                                 :item-path selected-item-path
                                 :destination-zone (:destination-zone selected-item)
                                 :zone-groups zone-groups}]}
                    (when is-group?
                      {:fx/type :v-box
                       :style "-fx-background-color: #2A2A2A; -fx-padding: 8;"
                       :v-box/vgrow :always
                       :spacing 8
                       :children [{:fx/type :label
                                   :text "GROUP PROPERTIES"
                                   :style "-fx-text-fill: #808080; -fx-font-size: 11; -fx-font-weight: bold;"}
                                  {:fx/type :label
                                   :text (str "Name: " (:name selected-item "Unnamed Group"))
                                   :style "-fx-text-fill: #B0B0B0; -fx-font-size: 10;"}
                                  {:fx/type :label
                                   :text (str "Items: " (count (:items selected-item [])))
                                   :style "-fx-text-fill: #B0B0B0; -fx-font-size: 10;"}
                                  ;; Destination zone picker for groups
                                  {:fx/type destination-zone-picker
                                   :col col
                                   :row row
                                   :item-path selected-item-path
                                   :destination-zone (:destination-zone selected-item)
                                   :zone-groups zone-groups}]}))
                  
                  (when (not selected-item)
                    {:fx/type :v-box
                     :v-box/vgrow :always
                     :style "-fx-background-color: #2A2A2A; -fx-padding: 8;"
                     :children [{:fx/type :label
                                 :text "ITEM PROPERTIES"
                                 :style "-fx-text-fill: #808080; -fx-font-size: 11; -fx-font-weight: bold;"}
                                {:fx/type :label
                                 :text "Select a preset or group from the chain"
                                 :style "-fx-text-fill: #606060; -fx-font-style: italic; -fx-font-size: 11;"}]})])}))


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
        effect-dialog-data (merge dialog-data item-effects-ui {:item-effects-ui item-effects-ui})]
   {:fx/type :v-box
    :spacing 0
    :style "-fx-background-color: #2D2D2D;"
    :pref-width 800
    :pref-height 700
    :children [;; Main content area - 2 columns
              {:fx/type :h-box
               :spacing 0
               :v-box/vgrow :always
               :children [;; Left column - cue chain list (top) + item effects (bottom)
                          {:fx/type :v-box
                           :pref-width 280
                           :max-width 280
                           :spacing 0
                           :children [;; Cue chain list (top)
                                      {:fx/type :v-box
                                       :pref-height 300
                                       :children [{:fx/type list/list-editor
                                                   :fx/context context
                                                   :v-box/vgrow :always
                                                   :items (:items cue-chain)
                                                   :component-id [:cue-chain col row]
                                                   :item-id-key :preset-id
                                                   :item-registry-fn presets/get-preset
                                                   :item-name-key :name
                                                   :fallback-label "Unknown Preset"
                                                   :on-change-event :chain/set-items
                                                   :on-change-params {:domain :cue-chains :entity-key [col row]}
                                                   :on-copy-fn (fn [items]
                                                                 (events/dispatch! {:event/type :cue-chain/set-clipboard
                                                                                    :items items}))
                                                   :clipboard-items clipboard-items
                                                   :header-label "CUE CHAIN"
                                                   :empty-text "No presets\nAdd from bank →"}]}
                                      
                                      ;; Item effects list (bottom) - always present, shows placeholder if no selection
                                      {:fx/type :v-box
                                       :v-box/vgrow :always
                                       :style "-fx-border-color: #404040; -fx-border-width: 1 0 0 0;"
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
                                                       :item-name-key :name
                                                       :fallback-label "Unknown Effect"
                                                       :on-change-event :cue-chain/set-item-effects
                                                       :on-change-params {:col col :row row :item-path first-selected-path}
                                                       :items-key :effects
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
                                                     :style "-fx-background-color: #252525;"
                                                     :alignment :center
                                                     :children [{:fx/type :label
                                                                 :text "Select an item above\nto edit its effects"
                                                                 :style "-fx-text-fill: #606060; -fx-font-style: italic; -fx-font-size: 11; -fx-text-alignment: center;"}]}])}]}
                          
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
                                         :cue-chain cue-chain}
                                        
                                        ;; Effect bank (when item selected)
                                        (when has-item?
                                          {:fx/type effect-bank-tabs
                                           :col col :row row
                                           :item-path first-selected-path
                                           :active-effect-tab active-effect-tab})
                                        
                                        ;; Effect parameter editor (when effect selected)
                                        (when (and has-item? (seq selected-effect-ids))
                                          {:fx/type effect-parameter-editor
                                           :fx/context context
                                           :v-box/vgrow :always
                                           :col col :row row
                                           :item-path first-selected-path
                                           :item selected-item
                                           :selected-effect-ids selected-effect-ids
                                           :dialog-data effect-dialog-data})])}]}
               
               ;; Footer with close button
               {:fx/type :h-box
                :alignment :center-right
                :spacing 8
                :padding 12
                :style "-fx-background-color: #252525;"
                :children [{:fx/type :button
                            :text "Close"
                            :style "-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-padding: 6 20;"
                            :on-action {:event/type :ui/close-dialog
                                        :dialog-id :cue-chain-editor}}]}]}))


;; Dialog Window


(defn- cue-chain-editor-scene
  "Scene component for the cue chain editor.
   Keyboard shortcuts are handled by the list components."
  [{:keys [stylesheets]}]
  {:fx/type :scene
   :stylesheets stylesheets
   :root {:fx/type cue-chain-editor-content}})

(defn cue-chain-editor-dialog
  "The cue chain editor dialog window."
  [{:keys [fx/context]}]
  (let [open? (fx/sub-ctx context subs/dialog-open? :cue-chain-editor)
        dialog-data (fx/sub-ctx context subs/dialog-data :cue-chain-editor)
        {:keys [col row]} dialog-data
        stylesheets (css/dialog-stylesheet-urls)
        window-title (str "Cue Chain Editor - Cell "
                         (char (+ 65 (or row 0)))
                         (inc (or col 0)))]
    {:fx/type :stage
     :showing open?
     :title window-title
     :modality :none
     :on-close-request {:event/type :ui/close-dialog :dialog-id :cue-chain-editor}
     :scene {:fx/type cue-chain-editor-scene
             :stylesheets stylesheets}}))
