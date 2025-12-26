(ns laser-show.views.tabs.effects
  "Effects grid tab - for managing effect chains per cell.
   
   Similar to the cue grid but for effect modifiers."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.subs :as subs]
            [laser-show.views.components.grid-cell :as cell]))

;; Effect cell is now imported from laser-show.views.components.grid-cell
;; It has full drag/drop and context menu support

;; ============================================================================
;; Effects Grid Layout
;; ============================================================================

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

;; ============================================================================
;; Grid Header
;; ============================================================================

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

;; ============================================================================
;; Grid with Row Labels
;; ============================================================================

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

;; ============================================================================
;; Effect Palette
;; ============================================================================

(def available-effects
  "List of available effects.
   IDs must match registered effects in laser-show.animation.effects.*"
  [;; Shape effects
   {:id :scale :name "Scale" :category :shape}
   {:id :rotation :name "Rotate" :category :shape}
   {:id :translate :name "Translate" :category :shape}
   {:id :viewport :name "Viewport" :category :shape}
   {:id :pinch-bulge :name "Pinch/Bulge" :category :shape}
   {:id :corner-pin :name "Corner Pin" :category :shape}
   {:id :lens-distortion :name "Lens Distort" :category :shape}
   {:id :wave-distort :name "Wave Distort" :category :shape}
   ;; Color effects
   {:id :hue-shift :name "Hue Shift" :category :color}
   {:id :saturation :name "Saturation" :category :color}
   {:id :color-filter :name "Color Filter" :category :color}
   {:id :invert :name "Invert" :category :color}
   {:id :set-color :name "Set Color" :category :color}
   {:id :rainbow-position :name "Rainbow" :category :color}
   ;; Intensity effects
   {:id :intensity :name "Intensity" :category :intensity}
   {:id :blackout :name "Blackout" :category :intensity}
   {:id :threshold :name "Threshold" :category :intensity}
   {:id :gamma :name "Gamma" :category :intensity}])

(defn effect-palette-item
  "A single effect in the palette."
  [{:keys [effect]}]
  {:fx/type :button
   :text (:name effect)
   :style "-fx-background-color: #3D3D3D; -fx-text-fill: white; -fx-font-size: 10; -fx-padding: 4 8; -fx-cursor: hand;"
   :tooltip {:fx/type :tooltip :text (str "Drag to add " (:name effect))}
   :on-action {:event/type :ui/select-preset :preset-id (:id effect)}})

(defn effect-palette
  "Palette of available effects."
  [{:keys [fx/context]}]
  {:fx/type :v-box
   :spacing 8
   :padding 8
   :style "-fx-background-color: #252525; -fx-background-radius: 4;"
   :children [{:fx/type :label
               :text "Effects"
               :style "-fx-text-fill: white; -fx-font-size: 12; -fx-font-weight: bold;"}
              {:fx/type :flow-pane
               :hgap 4
               :vgap 4
               :children (vec
                           (for [effect available-effects]
                             {:fx/type effect-palette-item
                              :effect effect}))}]})

;; ============================================================================
;; Effects Tab
;; ============================================================================

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
                                      {:fx/type effects-grid-with-labels}]}
                          {:fx/type effect-palette}]}]})
