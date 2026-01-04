(ns laser-show.views.dialogs.cue-chain-editor
  "Cue chain editor dialog.
   
   Two-column layout:
   - Left sidebar: Current cue chain (presets/groups) with drag-and-drop reordering
   - Right section: Preset bank (tabbed by category) + Preset param editor + Effect chain editor
   
   Features:
   - Multi-select with Click, Ctrl+Click, Shift+Click
   - Keyboard shortcuts: Ctrl+C (copy), Ctrl+V (paste), Ctrl+A (select all), Delete
   - Drag-and-drop reordering
   - Copy/paste across cells
   - Per-preset effect chains"
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs]
            [laser-show.animation.presets :as presets]
            [laser-show.animation.chains :as chains]
            [laser-show.events.core :as events]
            [laser-show.css.core :as css]
            [laser-show.views.components.hierarchical-list :as hierarchical-list]
            [laser-show.views.components.preset-bank :as preset-bank]
            [laser-show.views.components.preset-param-editor :as param-editor]
            [laser-show.views.components.tabs :as tabs])
  (:import [javafx.scene.input KeyCode KeyEvent]))


;; Effect Categories for Preset's Effects


(def effect-category-definitions
  "Category definitions for the mini effect bank."
  [{:id :shape :label "Shape"}
   {:id :color :label "Color"}
   {:id :intensity :label "Intensity"}])


;; Mini Effect Chain Editor for Preset


(defn- preset-effect-chain-section
  "Mini effect chain editor for the selected preset's effects.
   Shows a compact effect chain list and add-effect buttons."
  [{:keys [col row preset-path preset-instance selected-effect-path]}]
  (let [effects (:effects preset-instance [])]
    {:fx/type :v-box
     :spacing 4
     :style "-fx-background-color: #252525; -fx-padding: 8;"
     :children [{:fx/type :label
                 :text "EFFECTS"
                 :style "-fx-text-fill: #808080; -fx-font-size: 11; -fx-font-weight: bold;"}
                (if (empty? effects)
                  {:fx/type :label
                   :text "No effects on this preset"
                   :style "-fx-text-fill: #505050; -fx-font-size: 10;"}
                  {:fx/type :v-box
                   :spacing 2
                   :children (vec
                              (map-indexed
                                (fn [idx effect]
                                  (let [effect-path [idx]
                                        selected? (= effect-path selected-effect-path)]
                                    {:fx/type :h-box
                                     :alignment :center-left
                                     :spacing 4
                                     :style (str "-fx-background-color: "
                                                 (if selected? "#3A3A5A" "#2A2A2A")
                                                 "; -fx-padding: 4; -fx-cursor: hand;")
                                     :on-mouse-clicked {:event/type :cue-chain/select-effect
                                                        :col col :row row
                                                        :preset-path preset-path
                                                        :effect-path effect-path}
                                     :children [{:fx/type :check-box
                                                 :selected (:enabled? effect true)
                                                 :on-selected-changed {:event/type :cue-chain/set-effect-enabled
                                                                       :col col :row row
                                                                       :preset-path preset-path
                                                                       :effect-path effect-path}}
                                                {:fx/type :label
                                                  :text (if-let [eid (:effect-id effect)]
                                                          (name eid)
                                                          "Unknown")
                                                  :style "-fx-text-fill: #B0B0B0; -fx-font-size: 10;"}
                                                {:fx/type :region :h-box/hgrow :always}
                                                {:fx/type :button
                                                 :text "×"
                                                 :style "-fx-background-color: transparent; -fx-text-fill: #808080; -fx-font-size: 10; -fx-padding: 0 4;"
                                                 :on-action {:event/type :cue-chain/remove-effect
                                                             :col col :row row
                                                             :preset-path preset-path
                                                             :effect-path effect-path}}]}))
                                effects))})
                ;; Quick add effect buttons
                {:fx/type :h-box
                 :spacing 4
                 :children [{:fx/type :button
                             :text "+ Scale"
                             :style "-fx-background-color: #404040; -fx-text-fill: white; -fx-font-size: 9; -fx-padding: 2 6;"
                             :on-action {:event/type :cue-chain/add-effect-to-preset
                                         :col col :row row
                                         :preset-path preset-path
                                         :effect-id :scale}}
                            {:fx/type :button
                             :text "+ Rotate"
                             :style "-fx-background-color: #404040; -fx-text-fill: white; -fx-font-size: 9; -fx-padding: 2 6;"
                             :on-action {:event/type :cue-chain/add-effect-to-preset
                                         :col col :row row
                                         :preset-path preset-path
                                         :effect-id :rotate}}
                            {:fx/type :button
                             :text "+ Offset"
                             :style "-fx-background-color: #404040; -fx-text-fill: white; -fx-font-size: 9; -fx-padding: 2 6;"
                             :on-action {:event/type :cue-chain/add-effect-to-preset
                                         :col col :row row
                                         :preset-path preset-path
                                         :effect-id :translate}}]}]}))


;; Right Section


(defn- right-section
  "Right section with preset bank, parameter editor, and effect chain."
  [{:keys [col row active-preset-tab selected-preset-path cue-chain selected-effect-path]}]
  (let [;; Get selected preset instance if single selection
        selected-preset (when selected-preset-path
                          (chains/get-item-at-path (:items cue-chain) selected-preset-path))
        is-preset? (and selected-preset (= :preset (:type selected-preset)))]
    {:fx/type :v-box
     :spacing 0
     :children (filterv
                 some?
                 [;; Preset bank tabs (top)
                  {:fx/type preset-bank/preset-bank
                   :cell [col row]
                   :active-tab active-preset-tab
                   :on-tab-change {:event/type :cue-chain/set-preset-tab}}
                  
                  ;; Preset parameter editor (middle) - shows when preset selected
                  (if is-preset?
                    {:fx/type param-editor/preset-param-editor
                     :v-box/vgrow :always
                     :cell [col row]
                     :preset-path selected-preset-path
                     :preset-instance selected-preset}
                    {:fx/type :v-box
                     :v-box/vgrow :always
                     :style "-fx-background-color: #2A2A2A; -fx-padding: 8;"
                     :children [{:fx/type :label
                                 :text "PRESET PARAMETERS"
                                 :style "-fx-text-fill: #808080; -fx-font-size: 11; -fx-font-weight: bold;"}
                                {:fx/type :label
                                 :text "Select a preset from the chain"
                                 :style "-fx-text-fill: #606060; -fx-font-style: italic; -fx-font-size: 11;"}]})
                  
                  ;; Effect chain for selected preset (bottom)
                  (when is-preset?
                    {:fx/type preset-effect-chain-section
                     :col col :row row
                     :preset-path selected-preset-path
                     :preset-instance selected-preset
                     :selected-effect-path selected-effect-path})])}))


;; Main Dialog Content


(defn- cue-chain-editor-content
  "Main content of the cue chain editor dialog."
  [{:keys [fx/context]}]
  (let [;; Get cue-chain-editor state
        editor-state (fx/sub-val context :cue-chain-editor)
        {:keys [cell selected-ids last-selected-id active-preset-tab
                selected-effect-path clipboard]} editor-state
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
                                                :selected-preset-path first-selected-path
                                                :cue-chain cue-chain
                                            :selected-effect-path selected-effect-path}]}]}
               
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
              ctrl? (.isControlDown event)]
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
