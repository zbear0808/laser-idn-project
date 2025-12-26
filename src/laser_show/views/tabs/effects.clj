(ns laser-show.views.tabs.effects
  "Effects grid tab - for managing effect chains per cell.
   
   Similar to the cue grid but for effect modifiers."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.subs :as subs]))

;; ============================================================================
;; Cell Colors
;; ============================================================================

(def effect-cell-colors
  "Color scheme for effect cell states."
  {:empty "#353535"
   :has-effects "#5C6BC0"  ;; Purple-ish for effects
   :active "#7E57C2"
   :inactive "#424242"})

(defn effect-cell-background-color
  "Determine background color based on effect cell state."
  [{:keys [has-effects? active?]}]
  (cond
    (and has-effects? active?) (:active effect-cell-colors)
    has-effects? (:inactive effect-cell-colors)
    :else (:empty effect-cell-colors)))

;; ============================================================================
;; Effect Cell Component
;; ============================================================================

(defn effect-cell
  "A single effect grid cell."
  [{:keys [fx/context col row]}]
  (let [{:keys [effect-count first-effect-id active? has-effects?] :as display-data}
        (fx/sub-ctx context subs/effect-cell-display-data col row)
        bg-color (effect-cell-background-color display-data)
        label-text (if has-effects?
                     (str (when first-effect-id (-> first-effect-id name (str/replace "-" " ")))
                          (when (> effect-count 1)
                            (str " +" (dec effect-count))))
                     "")]
    {:fx/type :button
     :pref-width 80
     :pref-height 60
     :text label-text
     :style (str "-fx-background-color: " bg-color "; "
                 "-fx-text-fill: white; "
                 "-fx-font-size: 9; "
                 "-fx-background-radius: 4; "
                 "-fx-cursor: hand; "
                 (when active?
                   "-fx-effect: dropshadow(gaussian, #7E57C2, 8, 0.5, 0, 0);"))
     :on-action {:event/type :effects/toggle-cell :col col :row row}
     :on-mouse-clicked (fn [^javafx.scene.input.MouseEvent e]
                         (when (.isSecondaryButtonDown e)
                           ;; Right click - open editor
                           {:event/type :ui/open-dialog 
                            :dialog-id :effect-editor 
                            :data {:col col :row row}}))}))

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
                 {:fx/type effect-cell
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
  "List of available effects."
  [{:id :scale :name "Scale" :category :transform}
   {:id :rotate :name "Rotate" :category :transform}
   {:id :offset :name "Offset" :category :transform}
   {:id :mirror-x :name "Mirror X" :category :transform}
   {:id :mirror-y :name "Mirror Y" :category :transform}
   {:id :color-shift :name "Color Shift" :category :color}
   {:id :rainbow :name "Rainbow" :category :color}
   {:id :intensity :name "Intensity" :category :color}
   {:id :strobe :name "Strobe" :category :modulation}
   {:id :beat-pulse :name "Beat Pulse" :category :modulation}])

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
