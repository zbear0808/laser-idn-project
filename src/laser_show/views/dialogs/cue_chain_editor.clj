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
            [laser-show.views.components.hierarchical-list :as hierarchical-list]
            [laser-show.views.components.preset-bank :as preset-bank]
            [laser-show.views.components.preset-param-editor :as param-editor]
            [laser-show.views.components.custom-param-renderers :as custom-renderers]
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

(defn- params-vector->map
  "Convert effect parameters from vector format (registry) to map format (UI).
   [{:key :x-scale :default 1.0 ...}] -> {:x-scale {:default 1.0 ...}}"
  [params-vector]
  (into {}
        (mapv (fn [p] [(:key p) (dissoc p :key)])
              params-vector)))

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


(defn- param-slider
  "Slider control for numeric parameters with editable text field."
  [{:keys [col row item-path effect-path param-key param-spec current-value]}]
  (let [{:keys [min max]} param-spec
        value (or current-value (:default param-spec) 0)]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type :label
                 :text (name param-key)
                 :pref-width 80
                 :style "-fx-text-fill: #B0B0B0; -fx-font-size: 11;"}
                {:fx/type :slider
                 :min min
                 :max max
                 :value (double value)
                 :pref-width 150
                 :on-value-changed {:event/type :cue-chain/update-item-effect-param
                                    :col col :row row
                                    :item-path item-path
                                    :effect-path effect-path
                                    :param-key param-key}}
                {:fx/type :text-field
                 :text (format "%.2f" (double value))
                 :pref-width 55
                 :style "-fx-background-color: #404040; -fx-text-fill: white; -fx-font-size: 11; -fx-padding: 2 4;"
                 :on-action {:event/type :cue-chain/update-effect-param-from-text
                             :col col :row row
                             :item-path item-path
                             :effect-path effect-path
                             :param-key param-key
                             :min min :max max}}]}))

(defn- param-choice
  "Combo-box for choice parameters."
  [{:keys [col row item-path effect-path param-key param-spec current-value]}]
  (let [choices (:choices param-spec [])
        value (or current-value (:default param-spec))]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type :label
                 :text (name param-key)
                 :pref-width 80
                 :style "-fx-text-fill: #B0B0B0; -fx-font-size: 11;"}
                {:fx/type :combo-box
                 :items choices
                 :value value
                 :pref-width 150
                 :button-cell (fn [item] {:text (if item (name item) "")})
                 :cell-factory {:fx/cell-type :list-cell
                                :describe (fn [item] {:text (if item (name item) "")})}
                 :on-value-changed {:event/type :cue-chain/update-item-effect-param
                                    :col col :row row
                                    :item-path item-path
                                    :effect-path effect-path
                                    :param-key param-key}}]}))

(defn- param-checkbox
  "Checkbox for boolean parameters."
  [{:keys [col row item-path effect-path param-key param-spec current-value]}]
  (let [value (if (some? current-value) current-value (:default param-spec false))]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type :check-box
                 :text (name param-key)
                 :selected value
                 :style "-fx-text-fill: #B0B0B0; -fx-font-size: 11;"
                 :on-selected-changed {:event/type :cue-chain/update-item-effect-param
                                       :col col :row row
                                       :item-path item-path
                                       :effect-path effect-path
                                       :param-key param-key}}]}))

(defn- param-control
  "Render appropriate control based on parameter type."
  [{:keys [param-spec] :as props}]
  (case (:type param-spec :float)
    :choice {:fx/type param-choice
             :col (:col props) :row (:row props)
             :item-path (:item-path props)
             :effect-path (:effect-path props)
             :param-key (:param-key props)
             :param-spec param-spec
             :current-value (:current-value props)}
    :bool {:fx/type param-checkbox
           :col (:col props) :row (:row props)
           :item-path (:item-path props)
           :effect-path (:effect-path props)
           :param-key (:param-key props)
           :param-spec param-spec
           :current-value (:current-value props)}
    ;; Default: numeric slider
    {:fx/type param-slider
     :col (:col props) :row (:row props)
     :item-path (:item-path props)
     :effect-path (:effect-path props)
     :param-key (:param-key props)
     :param-spec param-spec
     :current-value (:current-value props)}))

(defn- custom-param-renderer
  "Renders parameters with custom UI and mode toggle.
   
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
  (let [ui-hints (:ui-hints effect-def)
        renderer-type (:renderer ui-hints)
        actual-mode (or ui-mode (:default-mode ui-hints :visual))]
    ;; RGB Curves is visual-only, no mode toggle - use the public rgb-curves-visual-editor
    (if (= renderer-type :rgb-curves)
      {:fx/type custom-renderers/rgb-curves-visual-editor
       :col col :row row
       :item-path item-path
       :effect-path effect-path
       :current-params current-params
       :dialog-data dialog-data}
      
      ;; Other custom renderers have mode toggle
      {:fx/type :v-box
       :spacing 8
       :children [;; Mode toggle buttons
                  {:fx/type :h-box
                   :spacing 6
                   :alignment :center-left
                   :padding {:bottom 8}
                   :children [{:fx/type :label
                              :text "Edit Mode:"
                              :style "-fx-text-fill: #808080; -fx-font-size: 10;"}
                             {:fx/type :button
                              :text "👁 Visual"
                              :style (str "-fx-font-size: 9; -fx-padding: 3 10; "
                                         (if (= actual-mode :visual)
                                           "-fx-background-color: #4A6FA5; -fx-text-fill: white;"
                                           "-fx-background-color: #404040; -fx-text-fill: #B0B0B0;"))
                              :on-action {:event/type :cue-chain/set-item-effect-param-ui-mode
                                         :col col :row row
                                         :item-path item-path
                                         :effect-path effect-path
                                         :mode :visual}}
                             {:fx/type :button
                              :text "🔢 Numeric"
                              :style (str "-fx-font-size: 9; -fx-padding: 3 10; "
                                         (if (= actual-mode :numeric)
                                           "-fx-background-color: #4A6FA5; -fx-text-fill: white;"
                                           "-fx-background-color: #404040; -fx-text-fill: #B0B0B0;"))
                              :on-action {:event/type :cue-chain/set-item-effect-param-ui-mode
                                         :col col :row row
                                         :item-path item-path
                                         :effect-path effect-path
                                         :mode :numeric}}]}
                  
                  ;; Render based on mode
                  (if (= actual-mode :visual)
                    ;; Custom visual renderer
                    (case renderer-type
                      :spatial-2d {:fx/type :v-box
                                  :spacing 8
                                  :padding 8
                                  :style "-fx-background-color: #2A2A2A; -fx-background-radius: 4;"
                                  :children [{:fx/type :label
                                             :text "Drag the point to adjust translation"
                                             :style "-fx-text-fill: #808080; -fx-font-size: 10; -fx-font-style: italic;"}
                                            {:fx/type :label
                                             :text "TODO: Spatial canvas for translate"
                                             :style "-fx-text-fill: #606060;"}]}
                      
                      :corner-pin-2d {:fx/type :v-box
                                     :spacing 8
                                     :padding 8
                                     :style "-fx-background-color: #2A2A2A; -fx-background-radius: 4;"
                                     :children [{:fx/type :label
                                                :text "Drag corners to adjust perspective mapping"
                                                :style "-fx-text-fill: #808080; -fx-font-size: 10; -fx-font-style: italic;"}
                                               {:fx/type :label
                                                :text "TODO: Spatial canvas for corner pin"
                                                :style "-fx-text-fill: #606060;"}]}
                      
                      ;; Fallback to standard params
                      {:fx/type :v-box
                       :spacing 6
                       :children (vec
                                  (for [[param-key param-spec] params-map]
                                    {:fx/type param-control
                                     :col col :row row
                                     :item-path item-path
                                     :effect-path effect-path
                                     :param-key param-key
                                     :param-spec param-spec
                                     :current-value (get current-params param-key)}))})
                    
                    ;; Numeric mode - standard sliders
                    {:fx/type :v-box
                     :spacing 6
                     :children (vec
                                (for [[param-key param-spec] params-map]
                                  {:fx/type param-control
                                   :col col :row row
                                   :item-path item-path
                                   :effect-path effect-path
                                   :param-key param-key
                                   :param-spec param-spec
                                   :current-value (get current-params param-key)}))})]})))

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
                    
                    ;; Standard parameters - use existing controls
                    {:fx/type :scroll-pane
                     :fit-to-width true
                     :v-box/vgrow :always
                     :style "-fx-background-color: transparent; -fx-background: #2A2A2A;"
                     :content {:fx/type :v-box
                              :spacing 6
                              :padding {:top 4}
                              :children (vec
                                         (for [[param-key param-spec] params-map]
                                           {:fx/type param-control
                                            :col col :row row
                                            :item-path item-path
                                            :effect-path effect-path
                                            :param-key param-key
                                            :param-spec param-spec
                                            :current-value (get current-params param-key)}))}})
                  
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
                                       :children [{:fx/type hierarchical-list/hierarchical-list-editor
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
                                                   [{:fx/type hierarchical-list/hierarchical-list-editor
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
   Keyboard shortcuts are handled by the hierarchical-list components."
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
