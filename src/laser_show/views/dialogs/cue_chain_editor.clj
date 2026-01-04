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
            [laser-show.views.components.cue-chain-sidebar :as sidebar]
            [laser-show.views.components.preset-bank :as preset-bank]
            [laser-show.views.components.preset-param-editor :as param-editor]
            [laser-show.views.components.effect-chain-sidebar :as effect-sidebar]
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


;; Keyboard Handler Setup


(defn- setup-keyboard-handler!
  "Setup keyboard handler on root node for shortcuts.
   
   - Ctrl+C: Copy selected items
   - Ctrl+V: Paste items
   - Ctrl+A: Select all items
   - Ctrl+G: Group selected items
   - Delete/Backspace: Delete selected items
   - Escape: Clear selection"
  [^javafx.scene.Node node col row]
  (.addEventFilter
    node
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
                (.consume event)))))))
  
  (.setOnKeyPressed
    node
    (reify javafx.event.EventHandler
      (handle [_ event]
        (let [code (.getCode event)
              ctrl? (.isControlDown event)]
          (cond
            ;; Ctrl+A - Select all
            (and ctrl? (= code KeyCode/A))
            (do (events/dispatch! {:event/type :cue-chain/select-all
                                   :col col :row row})
                (.consume event))
            
            ;; Ctrl+G - Group selected
            (and ctrl? (= code KeyCode/G))
            (do (events/dispatch! {:event/type :cue-chain/group-selected
                                   :col col :row row})
                (.consume event))
            
            ;; Delete or Backspace - Delete selected
            (or (= code KeyCode/DELETE) (= code KeyCode/BACK_SPACE))
            (do (events/dispatch! {:event/type :cue-chain/delete-selected
                                   :col col :row row})
                (.consume event))
            
            ;; Escape - Clear selection
            (= code KeyCode/ESCAPE)
            (do (events/dispatch! {:event/type :cue-chain/clear-selection})
                (.consume event))))))))


;; Main Dialog Content


(defn- cue-chain-editor-content
  "Main content of the cue chain editor dialog."
  [{:keys [fx/context]}]
  (let [;; Get cue-chain-editor state
        editor-state (fx/sub-val context :cue-chain-editor)
        {:keys [cell selected-paths last-selected-path active-preset-tab
                selected-effect-path]} editor-state
        [col row] cell
        
        ;; Get grid cell data
        grid-state (fx/sub-val context :grid)
        cell-data (get-in grid-state [:cells [col row]])
        cue-chain (or (:cue-chain cell-data) {:items []})
        
        ;; Selection state
        selected-paths-set (or selected-paths #{})
        
        ;; For parameter editor - need single selection
        first-selected-path (when (= 1 (count selected-paths-set))
                              (first selected-paths-set))
        
        ;; Clipboard check
        clipboard (:clipboard editor-state)
        can-paste? (boolean (seq (:items clipboard)))]
    {:fx/type fx/ext-on-instance-lifecycle
     :on-created (fn [node]
                   (setup-keyboard-handler! node col row)
                   (.setFocusTraversable node true)
                   (.requestFocus node))
     :desc {:fx/type :v-box
            :spacing 0
            :style "-fx-background-color: #2D2D2D;"
            :pref-width 700
            :pref-height 600
            :children [;; Main content area
                       {:fx/type :h-box
                        :spacing 0
                        :v-box/vgrow :always
                        :children [;; Left sidebar - cue chain list (wrapped in v-box for width control)
                                   {:fx/type :v-box
                                    :pref-width 260
                                    :max-width 260
                                    :children [{:fx/type sidebar/cue-chain-sidebar
                                                :v-box/vgrow :always
                                                :col col :row row
                                                :cue-chain cue-chain
                                                :selected-paths selected-paths-set
                                                :dragging-paths nil  ;; TODO: Add drag state
                                                :drop-target-path nil
                                                :drop-position nil
                                                :renaming-path nil
                                                :can-paste? can-paste?}]}
                                   
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
                                                :dialog-id :cue-chain-editor}}]}]}}))


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
