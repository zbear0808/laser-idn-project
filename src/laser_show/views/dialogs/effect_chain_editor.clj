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
            [clojure.tools.logging :as log]
            [laser-show.subs :as subs]
            [laser-show.animation.effects :as effects]
            [laser-show.animation.chains :as chains]
            [laser-show.events.core :as events]
            [laser-show.state.clipboard :as clipboard]
            [laser-show.css.core :as css]
            [laser-show.views.components.effect-bank :as effect-bank]
            [laser-show.views.components.effect-param-ui :as effect-param-ui]
            [laser-show.views.components.list :as list])
  (:import [javafx.scene.input KeyCode KeyEvent]))


;; Effect Registry Access


;; Use shared params-vector->map from effect-param-ui
(def params-vector->map effect-param-ui/params-vector->map)


;; Right Top: Tabbed Effect Bank (using data-driven component)


(defn- effect-bank-tabs
  "Tabbed effect bank using data-driven effect-bank component."
  [{:keys [col row active-bank-tab]}]
  {:fx/type effect-bank/effect-bank
   :active-tab (or active-bank-tab :shape)
   :on-tab-change {:event/type :ui/update-dialog-data
                   :dialog-id :effect-chain-editor}
   ;; Data-driven event template - handler will receive :item-id and :item
   :item-event-template {:event/type :effect-chain/add-effect-from-bank
                         :col col
                         :row row}
   :include-zone? true
   :pref-height 150})


;; Right Bottom: Parameter Editor


(defn- custom-param-renderer
  "Renders parameters with custom UI and mode toggle using shared effect-param-ui.
   
   Props:
   - :fx/context - cljfx context (required for zone effects)
   - :col, :row - Grid cell coordinates
   - :effect-path - Path to effect
   - :effect-def - Effect definition with :ui-hints
   - :current-params - Current parameter values
   - :ui-mode - Current UI mode (:visual or :numeric)
   - :params-map - Parameter specifications map
   - :dialog-data - Dialog state data (for curve editor channel tab tracking)"
  [{:keys [fx/context col row effect-path effect-def current-params ui-mode params-map dialog-data]}]
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
                          :domain :effect-chains
                          :entity-key [col row]
                          :effect-path effect-path}
  :spatial-event-keys {:col col :row row :effect-path effect-path}
  
  ;; Event templates for param controls
  :on-change-event {:event/type :chain/update-param
                    :domain :effect-chains
                    :entity-key [col row]
                    :effect-path effect-path}
  :on-text-event {:event/type :chain/update-param-from-text
                  :domain :effect-chains
                  :entity-key [col row]
                  :effect-path effect-path}
  :on-mode-change-event {:event/type :chain/set-ui-mode
                         :domain :effect-chains
                         :entity-key [col row]
                         :effect-path effect-path}
  ;; Modulator event base for modulator toggle/type/param operations
  :modulator-event-base {:domain :effect-chains
                         :entity-key [col row]
                         :effect-path effect-path}
  
  ;; RGB curves props
  :rgb-domain :effect-chains
  :rgb-entity-key [col row]
  :rgb-effect-path effect-path})

(defn- parameter-editor
  "Parameter editor for the selected effect."
  [{:keys [fx/context col row selected-effect-path effect-chain dialog-data]}]
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
                              :fx/context context
                              :col col :row row
                              :effect-path selected-effect-path
                              :effect-def effect-def
                              :current-params current-params
                              :ui-mode ui-mode
                              :params-map params-map
                              :dialog-data dialog-data}}
                    
                    ;; Standard parameters - use modulatable param controls
                    {:fx/type :scroll-pane
                     :fit-to-width true
                     :style "-fx-background-color: transparent; -fx-background: #2A2A2A;"
                     :content {:fx/type effect-param-ui/modulatable-param-controls-list
                              :params-map params-map
                              :current-params current-params
                              :on-change-event {:event/type :chain/update-param
                                                :domain :effect-chains
                                                :entity-key [col row]
                                                :effect-path selected-effect-path}
                              :on-text-event {:event/type :chain/update-param-from-text
                                              :domain :effect-chains
                                              :entity-key [col row]
                                              :effect-path selected-effect-path}
                              :modulator-event-base {:domain :effect-chains
                                                     :entity-key [col row]
                                                     :effect-path selected-effect-path}}})
                  
                  {:fx/type :label
                   :text "Select an effect from the chain"
                   :style "-fx-text-fill: #606060; -fx-font-style: italic; -fx-font-size: 11;"})]}))


;; Main Dialog Content


(defn- effect-chain-editor-content
  "Main content of the effect chain editor dialog."
  [{:keys [fx/context]}]
  (let [dialog-data (fx/sub-ctx context subs/dialog-data :effect-chain-editor)
        {:keys [col row active-bank-tab]} dialog-data
        ;; Read selection from canonical list-ui state instead of dialog-data
        list-state (fx/sub-ctx context subs/list-ui-state [:effect-chain col row])
        selected-ids (:selected-ids list-state #{})
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
                                        :fx/context context
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
                         (char (+ 65 (or row 0)))
                         (inc (or col 0)))]
    ;; Log visibility check for debugging
    (when open?
      (log/debug "Effect chain editor visibility check"
                 {:open? open?
                  :dialog-data dialog-data
                  :col col
                  :row row
                  :showing? open?}))
    {:fx/type :stage
     :showing open?
     :title window-title
     :modality :none
     :on-close-request {:event/type :ui/close-dialog :dialog-id :effect-chain-editor}
     :scene {:fx/type effect-chain-editor-scene
             :stylesheets stylesheets}}))
