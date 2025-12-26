(ns laser-show.views.components.grid-cell
  "Grid cell component - a single cue trigger button.
   
   Each cell displays:
   - Preset name if assigned
   - Visual state (active, selected, empty)
   - Responds to clicks for triggering"
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.subs :as subs]))

;; ============================================================================
;; Cell Colors
;; ============================================================================

(def cell-colors
  "Color scheme for cell states."
  {:empty "#424242"
   :content "#616161"
   :active "#4CAF50"
   :selected "#2196F3"
   :hover "#505050"})

(defn cell-background-color
  "Determine background color based on cell state."
  [{:keys [active? selected? has-content?]}]
  (cond
    active? (:active cell-colors)
    selected? (:selected cell-colors)
    has-content? (:content cell-colors)
    :else (:empty cell-colors)))

;; ============================================================================
;; Cell Content
;; ============================================================================

(defn cell-label
  "Display text for a cell."
  [{:keys [preset-id]}]
  (if preset-id
    (-> preset-id name (str/replace "-" " "))
    ""))

;; ============================================================================
;; Grid Cell Component
;; ============================================================================

(defn grid-cell
  "A single grid cell button.
   
   Props:
   - col: Column index
   - row: Row index
   
   Behavior:
   - Left-click: Trigger the cell
   - Right-click: Select the cell for editing"
  [{:keys [fx/context col row]}]
  (let [{:keys [preset-id active? selected? has-content?] :as display-data}
        (fx/sub-ctx context subs/cell-display-data col row)
        bg-color (cell-background-color display-data)
        label-text (cell-label display-data)]
    {:fx/type :button
     :pref-width 80
     :pref-height 60
     :text label-text
     :style (str "-fx-background-color: " bg-color "; "
                 "-fx-text-fill: white; "
                 "-fx-font-size: 10; "
                 "-fx-background-radius: 4; "
                 "-fx-cursor: hand; "
                 (when active?
                   "-fx-effect: dropshadow(gaussian, #4CAF50, 10, 0.5, 0, 0);")
                 (when selected?
                   "-fx-border-color: #2196F3; -fx-border-width: 2; -fx-border-radius: 4;"))
     :on-action {:event/type :grid/trigger-cell :col col :row row}
     :on-mouse-clicked (fn [^javafx.scene.input.MouseEvent e]
                         (when (.isSecondaryButtonDown e)
                           ;; Right click - select
                           {:event/type :grid/select-cell :col col :row row}))}))

;; ============================================================================
;; Alternative: Pane-based Cell (for more control)
;; ============================================================================

(defn grid-cell-pane
  "Alternative cell implementation using a pane for more visual control.
   
   This version gives more flexibility for complex cell rendering."
  [{:keys [fx/context col row]}]
  (let [{:keys [preset-id active? selected? has-content?] :as display-data}
        (fx/sub-ctx context subs/cell-display-data col row)
        bg-color (cell-background-color display-data)
        label-text (cell-label display-data)
        children (cond-> [{:fx/type :label
                           :text label-text
                           :style "-fx-text-fill: white; -fx-font-size: 10;"}]
                   active?
                   (conj {:fx/type :region
                          :pref-width 8
                          :pref-height 8
                          :style "-fx-background-color: white; -fx-background-radius: 4;"}))]
    {:fx/type :stack-pane
     :pref-width 80
     :pref-height 60
     :style (str "-fx-background-color: " bg-color "; "
                 "-fx-background-radius: 4; "
                 "-fx-cursor: hand; "
                 (if selected?
                   "-fx-border-color: #2196F3; -fx-border-width: 2; -fx-border-radius: 4;"
                   ""))
     :on-mouse-clicked (fn [^javafx.scene.input.MouseEvent e]
                         (if (.isSecondaryButtonDown e)
                           {:event/type :grid/select-cell :col col :row row}
                           {:event/type :grid/trigger-cell :col col :row row}))
     :children [{:fx/type :v-box
                 :alignment :center
                 :children children}]}))
