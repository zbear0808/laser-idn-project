(ns laser-show.views.tabs.effects
  "Effects grid tab - for managing effect chains per cell.
   
   Similar to the cue grid but for effect modifiers."
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs]
            [laser-show.views.components.grid-cell :as cell]))

;; Effect cell is now imported from laser-show.views.components.grid-cell
;; It has full drag/drop and context menu support


;; Effects Grid Layout


(defn effects-grid-row
  "A single row of effect cells."
  [{:keys [fx/context row cols]}]
  {:fx/type :h-box
   :spacing 4
   :children (vec
               (for [col (range cols)]
                 {:fx/type cell/effects-cell
                  :fx/key [col row]
                  :col col
                  :row row}))})

(defn effects-grid
  "The effects grid."
  [{:keys [fx/context]}]
  (let [[cols rows] (fx/sub-ctx context subs/grid-size)]
    {:fx/type :v-box
     :spacing 4
     :padding 8
     :alignment :center
     :children (vec
                 (for [row (range rows)]
                   {:fx/type effects-grid-row
                    :fx/key row
                    :row row
                    :cols cols}))}))


;; Grid Header


(defn effects-grid-header
  "Header above the effects grid with column numbers."
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


(defn effects-grid-with-labels
  "Effects grid with row labels on the left."
  [{:keys [fx/context]}]
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
                                :style "-fx-text-fill: #808080; -fx-font-size: 11;"}))}
                {:fx/type effects-grid}]}))


(defn effects-tab
  "Complete effects tab with grid and palette."
  [{:keys [fx/context]}]
  {:fx/type :v-box
   :style "-fx-background-color: #1E1E1E;"
   :padding 16
   :spacing 8
   :children [{:fx/type :label
               :text "Effects Grid"
               :style "-fx-text-fill: white; -fx-font-size: 16; -fx-font-weight: bold;"}
              {:fx/type :label
               :text "Click to toggle • Right-click to edit chain"
               :style "-fx-text-fill: #808080; -fx-font-size: 11;"}
              {:fx/type :h-box
               :spacing 16
               :children [{:fx/type :v-box
                           :children [{:fx/type effects-grid-header}
                                      {:fx/type effects-grid-with-labels}]}]}]})
