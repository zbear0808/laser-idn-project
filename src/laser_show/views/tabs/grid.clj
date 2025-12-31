(ns laser-show.views.tabs.grid
  "Grid tab component - the main cue trigger interface.
   
   The grid displays an 8x4 matrix of cells, each representing a cue
   that can be triggered to play a preset animation."
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs]
            [laser-show.views.components.grid-cell :as grid-cell]))


;; Grid Layout


(defn grid-row
  "A single row of grid cells."
  [{:keys [fx/context row cols]}]
  {:fx/type :h-box
   :spacing 4
   :children (vec
               (for [col (range cols)]
                 {:fx/type grid-cell/grid-cell
                  :fx/key [col row]  ;; IMPORTANT: Key must be on outer description!
                  :col col
                  :row row}))})

(defn cue-grid
  "The main 8x4 cue grid."
  [{:keys [fx/context]}]
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
                    :cols cols}))}))


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
                    :style "-fx-text-fill: #808080; -fx-font-size: 11;"}))}))


;; Grid with Row Labels


(defn grid-with-labels
  "Grid with row labels on the left."
  [{:keys [fx/context]}]
  (let [[cols rows] (fx/sub-ctx context subs/grid-size)]
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
                                :style "-fx-text-fill: #808080; -fx-font-size: 11;"}))}
                {:fx/type cue-grid}]}))


;; Grid Tab


(defn grid-tab
  "Complete grid tab with header and grid."
  [{:keys [fx/context]}]
  {:fx/type :v-box
   :style "-fx-background-color: #1E1E1E;"
   :padding 16
   :spacing 8
   :children [{:fx/type :label
               :text "Cue Grid"
               :style "-fx-text-fill: white; -fx-font-size: 16; -fx-font-weight: bold;"}
              {:fx/type :label
               :text "Click to trigger • Right-click to select • Drag to move"
               :style "-fx-text-fill: #808080; -fx-font-size: 11;"}
              {:fx/type grid-header}
              {:fx/type grid-with-labels}]})
