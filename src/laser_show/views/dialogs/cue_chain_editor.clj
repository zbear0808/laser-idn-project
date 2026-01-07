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
            [laser-show.views.components.tabs :as tabs]
            [laser-show.common.util :as u])
  (:import [javafx.scene.input KeyCode KeyEvent]))


;; Effect Bank and Effect Editor Components


(def effect-bank-tab-definitions
  "Tab definitions for the effect bank categories."
  [{:id :shape :label "Shape"}
   {:id :color :label "Color"}
   {:id :intensity :label "Intensity"}])

(defn- effects-by-category
  "Get effects filtered by category."
  [category]
  (effects/list-effects-by-category category))

;; Use shared params-vector->map from effect-param-ui
(def params-vector->map effect-param-ui/params-vector->map)

(defn- add-effect-button
  "Button to add a specific effect to the item's effect chain."
  [{:keys [col row item-path effect-def]}]
  (let [params-map (params-vector->map (:parameters effect-def))]
    {:fx/type :button
     :text (:name effect-def)
     :style "-fx-background-color: #505050; -fx-text-fill: white; -fx-font-size: 10; -fx-padding: 4 8;"
     :on-action {:event/type :cue-chain/add-effect-to-item
                 :col col :row row
                 :item-path item-path
                 :effect {:effect-id (:id effect-def)
                         :params (into {}
                                       (for [[k v] params-map]
                                         [k (:default v)]))}}}))

(defn- effect-bank-tab-content
  "Content for a single category tab in the effect bank."
  [{:keys [col row item-path category]}]
  (let [category-effects (effects-by-category category)]
    {:fx/type :flow-pane
     :hgap 4
     :vgap 4
     :padding 8
     :style "-fx-background-color: #1E1E1E;"
     :children (if (seq category-effects)
                 (vec (for [effect category-effects]
                        {:fx/type add-effect-button
                         :col col :row row
                         :item-path item-path
                         :effect-def effect}))
                 [{:fx/type :label
                   :text "No effects"
                   :style "-fx-text-fill: #606060;"}])}))

(defn- effect-bank-content-router
  "Routes to the correct effect bank content based on active tab."
  [{:keys [col row item-path active-effect-tab]}]
  (case active-effect-tab
    :color {:fx/type effect-bank-tab-content :col col :row row :item-path item-path :category :color}
    :shape {:fx/type effect-bank-tab-content :col col :row row :item-path item-path :category :shape}
    :intensity {:fx/type effect-bank-tab-content :col col :row row :item-path item-path :category :intensity}
    {:fx/type effect-bank-tab-content :col col :row row :item-path item-path :category :shape}))

(defn- effect-bank-tabs
  "Tabbed effect bank - Shape, Color, Intensity."
  [{:keys [col row item-path active-effect-tab]}]
  (let [active-tab (or active-effect-tab :shape)]
    {:fx/type :v-box
     :pref-height 140
     :children [{:fx/type tabs/styled-tab-bar
                 :tabs effect-bank-tab-definitions
                 :active-tab active-tab
                 :on-tab-change {:event/type :cue-chain/set-effect-tab
                                 :tab-id nil}}
                {:fx/type :scroll-pane
                 :fit-to-width true
                 :v-box/vgrow :always
                 :style "-fx-background-color: #1E1E1E; -fx-background: #1E1E1E;"
                 :content {:fx/type effect-bank-content-router
                           :col col :row row
                           :item-path item-path
                           :active-effect-tab active-tab}}]}))


;; Parameter Editor for Effects (with Custom Renderers)


(defn- custom-param-renderer
  "Renders parameters with custom UI and mode toggle using shared effect-param-ui.
   
   Props:
   - :col, :row - Grid cell coordinates
   - :item-path - Path to the item within cue chain
   - :effect-path - Path to effect within item's effect chain
   - :effect-def - Effect definition with :ui-hints
   - :current-params - Current parameter values
   - :ui-mode - Current UI mode (:visual or :numeric)
   - :params-map - Parameter specifications map
   - :dialog-data - Dialog state data (for curve editor channel tab tracking)"
  [{:keys [col row item-path effect-path effect-def current-params ui-mode params-map dialog-data]}]
  {:fx/type effect-param-ui/custom-param-renderer
   :effect-def effect-def
   :current-params current-params
   :ui-mode ui-mode
   :params-map params-map
   :dialog-data dialog-data
   
   ;; Event templates for spatial editors (translate, corner-pin)
   :spatial-event-template {:event/type :cue-chain/update-item-effect-spatial-params
                           :col col :row row
                           :item-path item-path
                           :effect-path effect-path}
   :spatial-event-keys {:col col :row row :item-path item-path :effect-path effect-path}
   
   ;; Event templates for param controls
   :on-change-event {:event/type :cue-chain/update-item-effect-param
                     :col col :row row
                     :item-path item-path
                     :effect-path effect-path}
   :on-text-event {:event/type :cue-chain/update-effect-param-from-text
                   :col col :row row
                   :item-path item-path
                   :effect-path effect-path}
   :on-mode-change-event {:event/type :cue-chain/set-item-effect-param-ui-mode
                         :col col :row row
                         :item-path item-path
                         :effect-path effect-path}
   
   ;; RGB curves props - for item effects, we need custom domain handling
   ;; Since RGB curves for item effects doesn't use :domain/:entity-key pattern yet,
   ;; we'll handle it with cue-chain specific events
   :rgb-domain :item-effects
   :rgb-entity-key {:col col :row row :item-path item-path}
   :rgb-effect-path effect-path})

(defn- effect-parameter-editor
  "Parameter editor for the selected effect within an item."
  [{:keys [col row item-path item selected-effect-ids dialog-data]}]
  (let [effects (:effects item [])
        ;; Get first selected effect
        first-selected-id (first selected-effect-ids)
        selected-effect (when first-selected-id
                          (first (filter #(= (:id %) first-selected-id) effects)))
        effect-def (when selected-effect
                     (effects/get-effect (:effect-id selected-effect)))
        current-params (:params selected-effect {})
        params-map (params-vector->map (:parameters effect-def []))
        effect-idx (.indexOf (mapv :id effects) first-selected-id)
        effect-path (when (>= effect-idx 0) [effect-idx])
        ;; For UI mode lookup, use effect path
        ui-mode (get-in dialog-data [:ui-modes effect-path])]
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
                              :col col :row row
                              :item-path item-path
                              :effect-path effect-path
                              :effect-def effect-def
                              :current-params current-params
                              :ui-mode ui-mode
                              :params-map params-map
                              :dialog-data dialog-data}}
                    
                    ;; Standard parameters - use shared param controls
                    {:fx/type :scroll-pane
                     :fit-to-width true
                     :v-box/vgrow :always
                     :style "-fx-background-color: transparent; -fx-background: #2A2A2A;"
                     :content {:fx/type :v-box
                              :spacing 6
                              :padding {:top 4}
                              :children (vec
                                         (for [[param-key param-spec] params-map]
                                           {:fx/type effect-param-ui/param-control
                                            :param-key param-key
                                            :param-spec param-spec
                                            :current-value (get current-params param-key)
                                            :on-change-event {:event/type :cue-chain/update-item-effect-param
                                                             :col col :row row
                                                             :item-path item-path
                                                             :effect-path effect-path}
                                            :on-text-event {:event/type :cue-chain/update-effect-param-from-text
                                                           :col col :row row
                                                           :item-path item-path
                                                           :effect-path effect-path}}))}})
                  
                  {:fx/type :label
                   :text "Select an effect from the chain"
                   :style "-fx-text-fill: #606060; -fx-font-style: italic; -fx-font-size: 11;"})]}))


;; Middle Section (Preset Config)


(defn- middle-section
  "Middle section with preset bank and parameter editor."
  [{:keys [col row active-preset-tab selected-item-path cue-chain]}]
  (let [;; Get selected item instance if single selection
        selected-item (when selected-item-path
                        (chains/get-item-at-path (:items cue-chain) selected-item-path))
        is-preset? (and selected-item (= :preset (:type selected-item)))
        is-group? (and selected-item (chains/group? selected-item))]
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
                    {:fx/type param-editor/preset-param-editor
                     :cell [col row]
                     :preset-path selected-item-path
                     :preset-instance selected-item}
                    (when is-group?
                      {:fx/type :v-box
                       :style "-fx-background-color: #2A2A2A; -fx-padding: 8;"
                       :v-box/vgrow :always
                       :children [{:fx/type :label
                                   :text "GROUP PROPERTIES"
                                   :style "-fx-text-fill: #808080; -fx-font-size: 11; -fx-font-weight: bold;"}
                                  {:fx/type :label
                                   :text (str "Name: " (:name selected-item "Unnamed Group"))
                                   :style "-fx-text-fill: #B0B0B0; -fx-font-size: 10;"}
                                  {:fx/type :label
                                   :text (str "Items: " (count (:items selected-item [])))
                                   :style "-fx-text-fill: #B0B0B0; -fx-font-size: 10;"}]}))
                  
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

(defn- get-item-effects-selected-ids
  "Get selected effect IDs for the item's effects list."
  [item-effects-ui item-path]
  (get-in item-effects-ui [(vec item-path) :selected-ids] #{}))


;; Main Dialog Content


(defn- cue-chain-editor-content
  "Main content of the cue chain editor dialog with 2-column layout.
   Left column: Cue chain list (top) + Item effects list (bottom).
   Right column: Preset config + Effect bank + Effect params."
  [{:keys [fx/context]}]
  (let [;; Get cue-chain-editor state
        editor-state (fx/sub-val context :cue-chain-editor)
        {:keys [cell selected-ids last-selected-id active-preset-tab
                active-effect-tab clipboard item-effects-ui]} editor-state
        [col row] cell
        
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
        
        ;; Get UI state for item effects
        item-effects-clipboard (when has-item?
                                 (get-item-effects-clipboard item-effects-ui first-selected-path))
        selected-effect-ids (when has-item?
                              (get-item-effects-selected-ids item-effects-ui first-selected-path))
        
        ;; Dialog data for custom renderers (UI modes, etc.)
        dialog-data (merge item-effects-ui {:item-effects-ui item-effects-ui})]
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
                                                   :on-selection-event :chain/update-selection
                                                   :on-selection-params {:domain :cue-chains :entity-key [col row]}
                                                   :selection-key :selected-ids
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
                                                     :component-id [:item-effects col row first-selected-path]
                                                     :item-id-key :effect-id
                                                     :item-registry-fn effects/get-effect
                                                     :item-name-key :name
                                                     :fallback-label "Unknown Effect"
                                                     :on-change-event :cue-chain/set-item-effects
                                                     :on-change-params {:col col :row row :item-path first-selected-path}
                                                     :items-key :effects
                                                     :on-selection-event :cue-chain/update-item-effect-selection
                                                     :on-selection-params {:col col :row row :item-path first-selected-path}
                                                     :selection-key :selected-ids
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
                                           :v-box/vgrow :always
                                           :col col :row row
                                           :item-path first-selected-path
                                           :item selected-item
                                           :selected-effect-ids selected-effect-ids
                                           :dialog-data dialog-data})])}]}
               
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
        editor-state (fx/sub-val context :cue-chain-editor)
        cell (:cell editor-state)
        [col row] (or cell [0 0])
        stylesheets (css/dialog-stylesheet-urls)
        window-title (str "Cue Chain Editor - Cell "
                         (char (+ 65 row))
                         (inc col))]
    {:fx/type :stage
     :showing (and open? (some? cell))
     :title window-title
     :modality :none
     :on-close-request {:event/type :ui/close-dialog :dialog-id :cue-chain-editor}
     :scene {:fx/type cue-chain-editor-scene
             :stylesheets stylesheets}}))
