(ns laser-show.views.dialogs.effect-chain-editor
  "Effects chain editor dialog.
   
   Two-column layout:
   - Left sidebar: Current effect chain with drag-and-drop reordering
   - Right section: Effect bank (tabbed by category) + parameter editor
   
   Effects are dynamically loaded from the effect registry.
   
   Features:
   - Multi-select with Click, Ctrl+Click, Shift+Click
   - Keyboard shortcuts: Ctrl+C (copy), Ctrl+V (paste), Ctrl+A (select all), Delete
   - Drag-and-drop reordering
   - Copy/paste across cells"
(:require [cljfx.api :as fx]
            [laser-show.subs :as subs]
            [laser-show.animation.effects :as effects]
            [laser-show.animation.chains :as chains]
            [laser-show.events.core :as events]
            [laser-show.state.clipboard :as clipboard]
            [laser-show.css.core :as css]
            [laser-show.views.components.tabs :as tabs]
            [laser-show.views.components.effect-param-ui :as effect-param-ui]
            [laser-show.views.components.list :as list])
  (:import [javafx.scene.input KeyCode KeyEvent]))


;; Effect Registry Access


(defn- effects-by-category
  "Get effects filtered by category (excludes :calibration)."
  [category]
  (effects/list-effects-by-category category))

;; Use shared params-vector->map from effect-param-ui
(def params-vector->map effect-param-ui/params-vector->map)


;; Right Top: Tabbed Effect Bank


(def effect-bank-tab-definitions
  "Tab definitions for the effect bank categories."
  [{:id :shape :label "Shape"}
   {:id :color :label "Color"}
   {:id :intensity :label "Intensity"}
   {:id :zone :label "Zone"}])

(defn- add-effect-button
  "Button to add a specific effect to the chain."
  [{:keys [col row effect-def]}]
  (let [params-map (params-vector->map (:parameters effect-def))]
    {:fx/type :button
     :text (:name effect-def)
     :style "-fx-background-color: #505050; -fx-text-fill: white; -fx-font-size: 10; -fx-padding: 4 8;"
     :on-action {:event/type :effects/add-effect
                 :col col :row row
                 :effect {:effect-id (:id effect-def)
                          :params (into {}
                                        (for [[k v] params-map]
                                          [k (:default v)]))}}}))

(defn- effect-bank-tab-content
  "Content for a single category tab in the effect bank."
  [{:keys [col row category]}]
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
                         :effect-def effect}))
                 [{:fx/type :label
                   :text "No effects"
                   :style "-fx-text-fill: #606060;"}])}))

(defn- effect-bank-content-router
  "Routes to the correct effect bank content based on active tab."
  [{:keys [col row active-bank-tab]}]
  (case active-bank-tab
    :color {:fx/type effect-bank-tab-content :col col :row row :category :color}
    :shape {:fx/type effect-bank-tab-content :col col :row row :category :shape}
    :intensity {:fx/type effect-bank-tab-content :col col :row row :category :intensity}
    {:fx/type effect-bank-tab-content :col col :row row :category :shape}))

(defn- effect-bank-tabs
  "Tabbed effect bank using shared styled-tab-bar - Color, Shape, Intensity."
  [{:keys [col row active-bank-tab]}]
  (let [active-tab (or active-bank-tab :shape)]
    {:fx/type :v-box
     :pref-height 150
     :children [{:fx/type tabs/styled-tab-bar
                 :tabs effect-bank-tab-definitions
                 :active-tab active-tab
                 :on-tab-change {:event/type :ui/update-dialog-data
                                 :dialog-id :effect-chain-editor}}
                {:fx/type :scroll-pane
                 :fit-to-width true
                 :v-box/vgrow :always
                 :style "-fx-background-color: #1E1E1E; -fx-background: #1E1E1E;"
                 :content {:fx/type effect-bank-content-router
                           :col col :row row
                           :active-bank-tab active-tab}}]}))


;; Right Bottom: Parameter Editor


(defn- custom-param-renderer
  "Renders parameters with custom UI and mode toggle using shared effect-param-ui.
   
   Props:
   - :col, :row - Grid cell coordinates
   - :effect-path - Path to effect
   - :effect-def - Effect definition with :ui-hints
   - :current-params - Current parameter values
   - :ui-mode - Current UI mode (:visual or :numeric)
   - :params-map - Parameter specifications map
   - :dialog-data - Dialog state data (for curve editor channel tab tracking)"
  [{:keys [col row effect-path effect-def current-params ui-mode params-map dialog-data]}]
  {:fx/type effect-param-ui/custom-param-renderer
   :effect-def effect-def
   :current-params current-params
   :ui-mode ui-mode
   :params-map params-map
   :dialog-data dialog-data
   
   ;; Event templates for spatial editors (translate, corner-pin)
   :spatial-event-template {:event/type :chain/update-spatial-params
                           :domain :effect-chains
                           :entity-key [col row]
                           :effect-path effect-path}
   :spatial-event-keys {:col col :row row :effect-path effect-path}
   
   ;; Event templates for param controls
   :on-change-event {:event/type :effects/update-param
                     :col col :row row
                     :effect-path effect-path}
   :on-text-event {:event/type :effects/update-param-from-text
                   :col col :row row
                   :effect-path effect-path}
   :on-mode-change-event {:event/type :effects/set-param-ui-mode
                         :effect-path effect-path}
   
   ;; RGB curves props
   :rgb-domain :effect-chains
   :rgb-entity-key [col row]
   :rgb-effect-path effect-path})

(defn- parameter-editor
  "Parameter editor for the selected effect."
  [{:keys [col row selected-effect-path effect-chain dialog-data]}]
  (let [selected-effect (when selected-effect-path
                          (get-in effect-chain (vec selected-effect-path)))
        effect-def (when selected-effect
                     (effects/get-effect (:effect-id selected-effect)))
        current-params (:params selected-effect {})
        params-map (params-vector->map (:parameters effect-def []))
        ui-mode (get-in dialog-data [:ui-modes selected-effect-path])]
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
                     :style "-fx-background-color: transparent; -fx-background: #2A2A2A;"
                     :content {:fx/type custom-param-renderer
                              :col col :row row
                              :effect-path selected-effect-path
                              :effect-def effect-def
                              :current-params current-params
                              :ui-mode ui-mode
                              :params-map params-map
                              :dialog-data dialog-data}}
                    
                    ;; Standard parameters - use shared param controls
                    {:fx/type :scroll-pane
                     :fit-to-width true
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
                                            :on-change-event {:event/type :effects/update-param
                                                             :col col :row row
                                                             :effect-path selected-effect-path}
                                            :on-text-event {:event/type :effects/update-param-from-text
                                                           :col col :row row
                                                           :effect-path selected-effect-path}}))}})
                  
                  {:fx/type :label
                   :text "Select an effect from the chain"
                   :style "-fx-text-fill: #606060; -fx-font-style: italic; -fx-font-size: 11;"})]}))


;; Main Dialog Content


(defn- effect-chain-editor-content
  "Main content of the effect chain editor dialog."
  [{:keys [fx/context]}]
  (let [dialog-data (fx/sub-ctx context subs/dialog-data :effect-chain-editor)
        {:keys [col row selected-ids active-bank-tab]} dialog-data
        chains-state (fx/sub-val context :chains)
        cell-data (get-in chains-state [:effect-chains [col row]])
        effect-chain (:items cell-data [])
        active? (:active cell-data false)
        clipboard-items (clipboard/get-effects-to-paste)
        ;; For parameter editor - use first selected ID if single select
        first-selected-id (when (= 1 (count selected-ids))
                            (first selected-ids))
        first-selected-path (when first-selected-id
                              (chains/find-path-by-id effect-chain first-selected-id))]
    {:fx/type :v-box
     :spacing 0
     :style "-fx-background-color: #2D2D2D;"
     :pref-width 600
     :pref-height 550
     :children [;; Main content area
               {:fx/type :h-box
                :spacing 0
                :v-box/vgrow :always
                :children [;; Left sidebar - chain list with drag-and-drop
                           ;; Using list-editor with built-in keyboard handling
                           {:fx/type list/list-editor
                            :fx/context context
                            :h-box/hgrow :always
                            :items effect-chain
                            :component-id [:effect-chain col row]
                            :item-id-key :effect-id
                            :item-registry-fn effects/get-effect
                            :item-name-key :name
                            :fallback-label "Unknown Effect"
                            :on-change-event :chain/set-items
                            :on-change-params {:domain :effect-chains :entity-key [col row]}
                            :on-selection-event :chain/update-selection
                            :on-selection-params {:domain :effect-chains :entity-key [col row]}
                            :selection-key :selected-ids
                            :on-copy-fn (fn [items]
                                          (clipboard/copy-effect-chain! {:effects items}))
                            :clipboard-items clipboard-items
                            :header-label "CHAIN"
                            :empty-text "No effects\nAdd from bank →"}
                                   
                           ;; Right section
                           {:fx/type :v-box
                            :spacing 0
                            :h-box/hgrow :always
                            :children [;; Effect bank tabs (top)
                                       {:fx/type effect-bank-tabs
                                        :col col :row row
                                        :active-bank-tab active-bank-tab}
                                       
                                       ;; Parameter editor (bottom) - shows first selected
                                       {:fx/type parameter-editor
                                        :col col :row row
                                        :selected-effect-path first-selected-path
                                        :effect-chain effect-chain
                                        :dialog-data dialog-data}]}]}
               
               ;; Footer with Active checkbox and close button
               {:fx/type :h-box
                :alignment :center-left
                :spacing 8
                :padding 12
                :style "-fx-background-color: #252525;"
                :children [{:fx/type :check-box
                            :text "Active"
                            :selected active?
                            :style "-fx-text-fill: white;"
                            :on-selected-changed {:event/type :effects/toggle-cell
                                                  :col col :row row}}
                           {:fx/type :region :h-box/hgrow :always}
                           {:fx/type :button
                            :text "Close"
                            :style "-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-padding: 6 20;"
                            :on-action {:event/type :ui/close-dialog
                                        :dialog-id :effect-chain-editor}}]}]}))


;; Dialog Window


;; NOTE: Dialog CSS is now defined in laser-show.css.dialogs
;; The CSS string literal has been moved to the centralized CSS system

(defn- effect-chain-editor-scene
  "Scene component for the effect chain editor.
   Keyboard shortcuts are handled by the list component."
  [{:keys [stylesheets]}]
  {:fx/type :scene
   :stylesheets stylesheets
   :root {:fx/type effect-chain-editor-content}})

(defn effect-chain-editor-dialog
  "The effect chain editor dialog window."
  [{:keys [fx/context]}]
  (let [open? (fx/sub-ctx context subs/dialog-open? :effect-chain-editor)
        dialog-data (fx/sub-ctx context subs/dialog-data :effect-chain-editor)
        {:keys [col row]} dialog-data
        ;; Use centralized CSS system - dialogs includes all needed styles
        stylesheets (css/dialog-stylesheet-urls)
        ;; Generate window title with cell identifier
        window-title (str "Effects Chain - Cell "
                         (char (+ 65 row))
                         (inc col))]
    {:fx/type :stage
     :showing open?
     :title window-title
     :modality :none
     :on-close-request {:event/type :ui/close-dialog :dialog-id :effect-chain-editor}
     :scene {:fx/type effect-chain-editor-scene
             :stylesheets stylesheets}}))
