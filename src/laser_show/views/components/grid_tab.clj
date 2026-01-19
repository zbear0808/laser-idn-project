(ns laser-show.views.components.grid-tab
  "Generic grid tab component for cell-based grids.
   
   Used for both the Cue Grid and Effects Grid tabs.
   Provides consistent grid layout with header, row labels, and cell components.
   
   Usage:
   ```clojure
   {:fx/type generic-grid-tab
    :cell-component grid-cell/grid-cell
    :header-text \"Cue Grid\"
    :hint-text \"Click to trigger • Right-click to select\"}
   ```"
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs]
            [laser-show.css.core :as css]))


;; Grid Row Component


(defn grid-row
  "A single row of grid cells.
   
   Props:
   - :row - Row index
   - :cols - Number of columns
   - :cell-component - Component to render for each cell"
  [{:keys [row cols cell-component]}]
  {:fx/type :h-box
   :spacing 4
   :children (vec
               (for [col (range cols)]
                 {:fx/type cell-component
                  :fx/key [col row]
                  :col col
                  :row row}))})


;; Grid Component


(defn grid
  "Grid of cells.
   
   Props:
   - :cell-component - Component to render for each cell"
  [{:keys [fx/context cell-component]}]
  (let [[cols rows] (fx/sub-ctx context subs/grid-size)]
    {:fx/type :v-box
     :spacing 4
     :padding 8
     :alignment :center
     :children (vec
                 (for [row (range rows)]
                   {:fx/type grid-row
                    :fx/key row
                    :row row
                    :cols cols
                    :cell-component cell-component}))}))


;; Grid Header


(defn grid-header
  "Header above the grid with column numbers."
  [{:keys [fx/context]}]
  (let [[cols _] (fx/sub-ctx context subs/grid-size)]
    {:fx/type :h-box
     :spacing 4
     :padding {:left 32 :right 8}
     :children (vec
                 (for [col (range cols)]
                   {:fx/type :label
                    :text (str (inc col))
                    :pref-width 80
                    :alignment :center
                    :style-class ["label-secondary"]}))}))


;; Grid with Row Labels


(defn grid-with-labels
  "Grid with row labels on the left.
   
   Props:
   - :cell-component - Component to render for each cell"
  [{:keys [fx/context cell-component]}]
  (let [[_cols rows] (fx/sub-ctx context subs/grid-size)]
    {:fx/type :h-box
     :children [{:fx/type :v-box
                 :spacing 4
                 :padding {:top 8}
                 :children (vec
                             (for [row (range rows)]
                               {:fx/type :label
                                :text (str (char (+ 65 row))) ;; A, B, C, D...
                                :pref-width 24
                                :pref-height 60
                                :alignment :center
                                :style-class ["label-secondary"]}))}
                {:fx/type grid
                 :cell-component cell-component}]}))


;; Generic Grid Tab


(defn generic-grid-tab
  "Complete grid tab with header, hint text, and cell grid.
   
   Props:
   - :cell-component - Component to render for each cell
   - :header-text - Header text for the tab
   - :hint-text - Hint text below the header"
  [{:keys [fx/context cell-component header-text hint-text]}]
  {:fx/type :v-box
   :style (str "-fx-background-color: " (css/bg-primary) ";")
   :padding 16
   :spacing 8
   :children [{:fx/type :label
               :text header-text
               :style "-fx-text-fill: white; -fx-font-size: 16; -fx-font-weight: bold;"}
              {:fx/type :label
               :text hint-text
               :style (str "-fx-text-fill: " (css/text-muted) "; -fx-font-size: 11;")}
              {:fx/type grid-header}
              {:fx/type grid-with-labels
               :cell-component cell-component}]})
