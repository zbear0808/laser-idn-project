(ns laser-show.views.dialogs.cue-chain-editor
  "Cue chain editor dialog.
   
   Two-column layout:
   - Left sidebar: Current cue chain (presets/groups) with drag-and-drop reordering
   - Right section: Preset bank (tabbed by category) + Preset param editor + Full effect chain editor
   
   Features:
   - Multi-select with Click, Ctrl+Click, Shift+Click
   - Keyboard shortcuts: Ctrl+C (copy), Ctrl+V (paste), Ctrl+A (select all), Delete
   - Drag-and-drop reordering
   - Copy/paste across cells
   - Full effect chain editor for presets and groups"
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
     :pref-height 150
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


;; Parameter Editor for Effects


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
                 :on-value-changed {:event/type :cue-chain/update-effect-param
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
                 :on-value-changed {:event/type :cue-chain/update-effect-param
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
                 :on-selected-changed {:event/type :cue-chain/update-effect-param
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

(defn- effect-parameter-editor
  "Parameter editor for the selected effect within an item."
  [{:keys [col row item-path item selected-effect-id]}]
  (let [effects (:effects item [])
        selected-effect (when selected-effect-id
                          (first (filter #(= (:id %) selected-effect-id) effects)))
        effect-def (when selected-effect
                     (effects/get-effect (:effect-id selected-effect)))
        current-params (:params selected-effect {})
        params-map (params-vector->map (:parameters effect-def []))]
    {:fx/type :v-box
     :spacing 8
     :style "-fx-background-color: #2A2A2A; -fx-padding: 8;"
     :children [{:fx/type :label
                 :text (if effect-def
                         (str "PARAMETERS: " (:name effect-def))
                         "PARAMETERS")
                 :style "-fx-text-fill: #808080; -fx-font-size: 11; -fx-font-weight: bold;"}
                (if effect-def
                  {:fx/type :scroll-pane
                   :fit-to-width true
                   :style "-fx-background-color: transparent; -fx-background: #2A2A2A;"
                   :content {:fx/type :v-box
                            :spacing 6
                            :padding {:top 4}
                            :children (vec
                                       (for [[param-key param-spec] params-map
                                             :let [effect-idx (.indexOf effects selected-effect)
                                                   effect-path [effect-idx]]]
                                         {:fx/type param-control
                                          :col col :row row
                                          :item-path item-path
                                          :effect-path effect-path
                                          :param-key param-key
                                          :param-spec param-spec
                                          :current-value (get current-params param-key)}))}}
                  {:fx/type :label
                   :text "Select an effect to edit its parameters"
                   :style "-fx-text-fill: #606060; -fx-font-style: italic; -fx-font-size: 11;"})]}))

(defn- item-effect-chain-list
  "Hierarchical list of effects for the selected item."
  [{:keys [col row item-path item selected-effect-id]}]
  (let [effects (:effects item [])]
    {:fx/type :v-box
     :style "-fx-background-color: #252525; -fx-padding: 8;"
     :children [{:fx/type :label
                 :text "ITEM EFFECTS"
                 :style "-fx-text-fill: #808080; -fx-font-size: 11; -fx-font-weight: bold;"}
                (if (empty? effects)
                  {:fx/type :label
                   :text "No effects on this item"
                   :style "-fx-text-fill: #505050; -fx-font-size: 10; -fx-padding: 8 0 0 0;"}
                  {:fx/type :v-box
                   :spacing 2
                   :padding {:top 4}
                   :children (vec
                              (for [effect effects
                                    :let [effect-id (:id effect)
                                          selected? (= effect-id selected-effect-id)]]
                                {:fx/type :h-box
                                 :alignment :center-left
                                 :spacing 4
                                 :style (str "-fx-background-color: "
                                            (if selected? "#3A3A5A" "#2A2A2A")
                                            "; -fx-padding: 4; -fx-cursor: hand;")
                                 :on-mouse-clicked {:event/type :cue-chain/select-item-effect
                                                    :col col :row row
                                                    :item-path item-path
                                                    :effect-id effect-id}
                                 :children [{:fx/type :check-box
                                            :selected (:enabled? effect true)
                                            :on-selected-changed {:event/type :cue-chain/set-item-effect-enabled
                                                                  :col col :row row
                                                                  :item-path item-path
                                                                  :effect-id effect-id}}
                                           {:fx/type :label
                                             :text (if-let [eid (:effect-id effect)]
                                                     (name eid)
                                                     "Unknown")
                                             :style "-fx-text-fill: #B0B0B0; -fx-font-size: 10;"}
                                           {:fx/type :region :h-box/hgrow :always}
                                           {:fx/type :button
                                            :text "×"
                                            :style "-fx-background-color: transparent; -fx-text-fill: #808080; -fx-font-size: 10; -fx-padding: 0 4;"
                                            :on-action {:event/type :cue-chain/remove-effect-from-item
                                                        :col col :row row
                                                        :item-path item-path
                                                        :effect-id effect-id}}]}))})]}))


;; Right Section


(defn- right-section
  "Right section with preset bank, parameter editor, and full effect chain editor."
  [{:keys [col row active-preset-tab selected-item-path cue-chain active-effect-tab selected-effect-id]}]
  (let [;; Get selected item instance if single selection
        selected-item (when selected-item-path
                        (chains/get-item-at-path (:items cue-chain) selected-item-path))
        is-preset? (and selected-item (= :preset (:type selected-item)))
        is-group? (and selected-item (chains/group? selected-item))
        has-item? (boolean selected-item)]
    {:fx/type :v-box
     :spacing 0
     :children (filterv
                 some?
                 [;; Preset bank tabs (top)
                  {:fx/type preset-bank/preset-bank
                   :cell [col row]
                   :active-tab active-preset-tab
                   :on-tab-change {:event/type :cue-chain/set-preset-tab}}
                  
                  ;; Preset/Group parameter editor (middle) - shows when item selected
                  (if is-preset?
                    {:fx/type param-editor/preset-param-editor
                     :cell [col row]
                     :preset-path selected-item-path
                     :preset-instance selected-item}
                    (when is-group?
                      {:fx/type :v-box
                       :style "-fx-background-color: #2A2A2A; -fx-padding: 8;"
                       :children [{:fx/type :label
                                   :text "GROUP PROPERTIES"
                                   :style "-fx-text-fill: #808080; -fx-font-size: 11; -fx-font-weight: bold;"}
                                  {:fx/type :label
                                   :text (str "Name: " (:name selected-item "Unnamed Group"))
                                   :style "-fx-text-fill: #B0B0B0; -fx-font-size: 10;"}
                                  {:fx/type :label
                                   :text (str "Items: " (count (:items selected-item [])))
                                   :style "-fx-text-fill: #B0B0B0; -fx-font-size: 10;"}]}))
                  
                  (when (not has-item?)
                    {:fx/type :v-box
                     :v-box/vgrow :always
                     :style "-fx-background-color: #2A2A2A; -fx-padding: 8;"
                     :children [{:fx/type :label
                                 :text "ITEM PROPERTIES"
                                 :style "-fx-text-fill: #808080; -fx-font-size: 11; -fx-font-weight: bold;"}
                                {:fx/type :label
                                 :text "Select a preset or group from the chain"
                                 :style "-fx-text-fill: #606060; -fx-font-style: italic; -fx-font-size: 11;"}]})
                  
                  ;; Effect bank for selected item (when item is selected)
                  (when has-item?
                    {:fx/type effect-bank-tabs
                     :col col :row row
                     :item-path selected-item-path
                     :active-effect-tab active-effect-tab})
                  
                  ;; Effect chain list for selected item
                  (when has-item?
                    {:fx/type item-effect-chain-list
                     :col col :row row
                     :item-path selected-item-path
                     :item selected-item
                     :selected-effect-id selected-effect-id})
                  
                  ;; Effect parameter editor (bottom)
                  (when has-item?
                    {:fx/type effect-parameter-editor
                     :col col :row row
                     :item-path selected-item-path
                     :item selected-item
                     :selected-effect-id selected-effect-id})])}))


;; Main Dialog Content


(defn- cue-chain-editor-content
  "Main content of the cue chain editor dialog."
  [{:keys [fx/context]}]
  (let [;; Get cue-chain-editor state
        editor-state (fx/sub-val context :cue-chain-editor)
        {:keys [cell selected-ids last-selected-id active-preset-tab
                active-effect-tab selected-effect-id clipboard]} editor-state
        [col row] cell
        
        ;; Get grid cell data
        grid-state (fx/sub-val context :grid)
        cell-data (get-in grid-state [:cells [col row]])
        cue-chain (or (:cue-chain cell-data) {:items []})
        
        ;; For parameter editor - resolve first selected ID to path
        first-selected-id (when (= 1 (count selected-ids))
                            (first selected-ids))
        first-selected-path (when first-selected-id
                              (chains/find-path-by-id (:items cue-chain) first-selected-id))
        
        ;; Clipboard check
        clipboard-items (:items clipboard)]
   {:fx/type :v-box
    :spacing 0
    :style "-fx-background-color: #2D2D2D;"
    :pref-width 700
    :pref-height 600
    :children [;; Main content area
              {:fx/type :h-box
               :spacing 0
               :v-box/vgrow :always
               :children [;; Left sidebar - cue chain list (wrapped in v-box for width control)
                          ;; Using hierarchical-list-editor with built-in keyboard handling
                          {:fx/type :v-box
                           :pref-width 260
                           :max-width 260
                           :children [{:fx/type hierarchical-list/hierarchical-list-editor
                                       :v-box/vgrow :always
                                       :items (:items cue-chain)
                                       :component-id [:cue-chain col row]
                                       :item-id-key :preset-id
                                       :item-registry-fn presets/get-preset
                                       :item-name-key :name
                                       :fallback-label "Unknown Preset"
                                       :on-change-event :cue-chain/set-items
                                       :on-change-params {:col col :row row}
                                       :on-selection-event :cue-chain/update-selection
                                       :on-selection-params {:col col :row row}
                                       :selection-key :selected-ids
                                       :on-copy-fn (fn [items]
                                                     (events/dispatch! {:event/type :cue-chain/set-clipboard
                                                                        :items items}))
                                       :clipboard-items clipboard-items
                                       :header-label "CUE CHAIN"
                                       :empty-text "No presets\nAdd from bank →"}]}
                                   
                                   ;; Right section (wrapped in v-box for h-box/hgrow)
                                   {:fx/type :v-box
                                    :h-box/hgrow :always
                                    :children [{:fx/type right-section
                                                :v-box/vgrow :always
                                                :col col :row row
                                                :active-preset-tab active-preset-tab
                                                :selected-item-path first-selected-path
                                                :cue-chain cue-chain
                                                :active-effect-tab active-effect-tab
                                                :selected-effect-id selected-effect-id}]}]}
               
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


;; Scene-level Event Filter


(defn- setup-scene-key-filter!
  "Setup Scene-level event filter for Ctrl+C and Ctrl+V."
  [^javafx.scene.Scene scene col row]
  (.addEventFilter
    scene
    KeyEvent/KEY_PRESSED
    (reify javafx.event.EventHandler
      (handle [_ event]
        (let [code (.getCode event)
              ctrl? (.isShortcutDown event)]
          (cond
            ;; Ctrl+C - Copy
            (and ctrl? (= code KeyCode/C))
            (do (events/dispatch! {:event/type :cue-chain/copy-selected
                                   :col col :row row})
                (.consume event))
            
            ;; Ctrl+V - Paste
            (and ctrl? (= code KeyCode/V))
            (do (events/dispatch! {:event/type :cue-chain/paste-items
                                   :col col :row row})
                (.consume event))))))))


;; Dialog Window


(defn- cue-chain-editor-scene
  "Scene component with event filter for Ctrl+C/V."
  [{:keys [col row stylesheets]}]
  {:fx/type fx/ext-on-instance-lifecycle
   :on-created (fn [^javafx.scene.Scene scene]
                 (setup-scene-key-filter! scene col row))
   :desc {:fx/type :scene
          :stylesheets stylesheets
          :root {:fx/type cue-chain-editor-content}}})

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
             :col col
             :row row
             :stylesheets stylesheets}}))
