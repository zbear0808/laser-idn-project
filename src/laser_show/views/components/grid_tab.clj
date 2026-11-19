(ns laser-show.views.components.grid-tab
  "Generic grid tab component for cell-based grids.
   
   Used for both the Cue Grid and Effects Grid tabs.
   Provides consistent grid layout with header, row labels, and cell components.
   The cells grow with the space the tab gives them, and so do their labels,
   the column numbers and the row letters (cell-size, label-size).
   
   Usage:
   ```clojure
   {:fx/type generic-grid-tab
    :cell-component grid-cell/grid-cell
    :header-text \"Cue Grid\"
    :hint-text \"Click: play or stop · Double-click or right-click: edit\"}
   ```"
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs]
            [laser-show.css.core :as css]
            [laser-show.views.components.measured :as measured]))


;; Sizes
;;
;; The columns share the grid's width and the rows its height, but a cell is
;; never taller than wide (a pad, not a column) and never smaller than 64×48.
;; The area keeps what the smallest cells need as its minimum, so the split
;; pane can't make the tab too narrow to show all the columns.


(def ^:private gap
  "Space between cells."
  4)

(def ^:private padding
  "Space around the cells."
  8)

(def ^:private letters-width
  "Width of the row letters' column."
  24)

(def ^:private numbers-height
  "Height of the column numbers' row."
  22)

(def ^:private min-cell [64 48])

(defn cell-size
  "[width height] of each cell of a cols × rows grid in an area of width ×
   height, row letters and column numbers included: the columns share the
   width; the rows share the height, but a cell is never taller than wide,
   nor smaller than 64×48. [80 60] before the area is laid out (no sizes)."
  [cols rows width height]
  (if (and width height)
    (let [[min-w min-h] min-cell
          share (fn [space n] (long (Math/floor (/ (double space) n))))
          w (max min-w (share (- width letters-width (* 2 padding) (* (dec cols) gap)) cols))
          h (-> (share (- height numbers-height (* 2 padding) (* (dec rows) gap)) rows)
                (min w)
                (max min-h))]
      [w h])
    [80 60]))

(defn label-size
  "Font size in px of a w × h cell's label: a sixth of its width (of the
   width a 4:3 cell of its height has, when it is wider), 12 to 16."
  [w h]
  (-> (min w (* 4/3 h)) double (/ 6.0) Math/round (max 12) (min 16)))

(defn- min-area-size
  "[width height] a cols × rows grid of the smallest cells needs."
  [cols rows]
  (let [[w h] min-cell]
    [(+ letters-width (* 2 padding) (* cols w) (* (dec cols) gap))
     (+ numbers-height (* 2 padding) (* rows h) (* (dec rows) gap))]))

(defn- font-style [px]
  (str "-fx-font-size: " px "px;"))


;; Grid Row Component


(defn grid-row
  "A single row of grid cells.
   
   Props:
   - :row - Row index
   - :cols - Number of columns
   - :cell-component - Component to render for each cell
   - :size, :font-size - Each cell's [width height], and its label's size"
  [{:keys [row cols cell-component size font-size]}]
  {:fx/type :h-box
   :spacing gap
   :children (vec
               (for [col (range cols)]
                 {:fx/type cell-component
                  :fx/key [col row]
                  :col col
                  :row row
                  :size size
                  :font-size font-size}))})


;; Grid Component


(defn grid
  "Grid of cells.
   
   Props:
   - :cell-component - Component to render for each cell
   - :size, :font-size - Each cell's [width height], and its label's size"
  [{:keys [fx/context cell-component size font-size]}]
  (let [[cols rows] (fx/sub-ctx context subs/grid-size)]
    {:fx/type :v-box
     :spacing gap
     :padding padding
     :alignment :center
     :children (vec
                 (for [row (range rows)]
                   {:fx/type grid-row
                    :fx/key row
                    :row row
                    :cols cols
                    :cell-component cell-component
                    :size size
                    :font-size font-size}))}))


;; Grid Header


(defn grid-header
  "Header above the grid with column numbers, each as wide as its column.
   
   Props:
   - :size, :font-size - The cells' [width height], and their labels' size"
  [{:keys [fx/context size font-size]}]
  (let [[cols _] (fx/sub-ctx context subs/grid-size)
        [w _] size]
    {:fx/type :h-box
     :spacing gap
     :padding {:left (+ letters-width padding) :right padding}
     :children (vec
                 (for [col (range cols)]
                   {:fx/type :label
                    :id (str "grid-column-number-" col)
                    :text (str (inc col))
                    :min-width w
                    :pref-width w
                    :min-height numbers-height
                    :pref-height numbers-height
                    :alignment :center
                    :style-class ["label-secondary"]
                    :style (font-style font-size)}))}))


;; Grid with Row Labels


(defn grid-with-labels
  "Grid with row labels on the left, each as high as its row.
   
   Props:
   - :cell-component - Component to render for each cell
   - :size, :font-size - Each cell's [width height], and its label's size"
  [{:keys [fx/context cell-component size font-size]}]
  (let [[_cols rows] (fx/sub-ctx context subs/grid-size)
        [_ h] size]
    {:fx/type :h-box
     :children [{:fx/type :v-box
                 :spacing gap
                 :padding {:top padding}
                 :children (vec
                             (for [row (range rows)]
                               {:fx/type :label
                                :id (str "grid-row-letter-" row)
                                :text (str (char (+ 65 row))) ;; A, B, C, D...
                                :pref-width letters-width
                                :min-height h
                                :pref-height h
                                :alignment :center
                                :style-class ["label-secondary"]
                                :style (font-style font-size)}))}
                {:fx/type grid
                 :cell-component cell-component
                 :size size
                 :font-size font-size}]}))


;; Grid Area


(defn- grid-area
  "The column numbers, the row letters and the cells, sized for the area
   the tab gives them (:width and :height, from measured/measured)."
  [{:keys [fx/context cell-component width height]}]
  (let [[cols rows] (fx/sub-ctx context subs/grid-size)
        [w h :as size] (cell-size cols rows width height)
        font-size (label-size w h)
        [min-width min-height] (min-area-size cols rows)]
    {:fx/type :v-box
     :id "grid-area"
     :min-width min-width
     :min-height min-height
     :children [{:fx/type grid-header
                 :size size
                 :font-size font-size}
                {:fx/type grid-with-labels
                 :cell-component cell-component
                 :size size
                 :font-size font-size}]}))


;; Generic Grid Tab


(defn generic-grid-tab
  "Complete grid tab with header, hint text, and cell grid.
   
   Props:
   - :cell-component - Component to render for each cell
   - :header-text - Header text for the tab
   - :hint-text - Hint text below the header
   - :empty-hint - Optional line under the hint (nil: none), e.g. for an
     empty grid"
  [{:keys [fx/context cell-component header-text hint-text empty-hint]}]
  {:fx/type :v-box
   :style (str "-fx-background-color: " (css/bg-primary) ";")
   :padding 16
   :spacing 8
   :children [{:fx/type :label
               :text header-text
               :style "-fx-text-fill: white; -fx-font-size: 16; -fx-font-weight: bold;"}
              {:fx/type :label
               :id "grid-hint"
               :text hint-text
               :style (str "-fx-text-fill: " (css/text-muted) "; -fx-font-size: 11;")}
              ;; Always there, shown or not: the measured area keeps its place
              {:fx/type :label
               :id "grid-empty-hint"
               :text (or empty-hint "")
               :visible (some? empty-hint)
               :managed (some? empty-hint)
               :style (str "-fx-text-fill: " (css/text-secondary) "; -fx-font-size: 11;")}
              {:fx/type measured/measured
               :v-box/vgrow :always
               :desc {:fx/type grid-area
                      :cell-component cell-component}}]})
