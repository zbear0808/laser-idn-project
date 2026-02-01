(ns laser-show.views.components.preview
  "Preview panel component for displaying laser frame output.
   
   This component renders the current laser frame to a JavaFX Canvas.
   The :draw prop is a function that receives the Canvas and renders to it.
   
   Features:
   - Zone group filtering for preview (dropdown in header)
   - Shows content based on routing destination
   
   NOTE: LaserPoints now use NORMALIZED colors (0.0-1.0).
   This module converts normalized values to 8-bit for JavaFX Color display."
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs]
            [laser-show.common.util :as u])
  (:import [javafx.scene.canvas Canvas GraphicsContext]
           [javafx.scene.paint Color]
           [javafx.util StringConverter]))


;; Drawing Helpers


(defn- normalize-coord
  "Convert normalized coordinate (-1 to 1) to pixel coordinate."
  [normalized size]
  (* (+ normalized 1.0) 0.5 size))

(defn- color-from-normalized
  "Create JavaFX Color from normalized RGB values (0.0-1.0).
   Clamps values to valid range."
  [r g b]
  (Color/color (u/clamp (double (or r 1.0)) 0.0 1.0)
               (u/clamp (double (or g 1.0)) 0.0 1.0)
               (u/clamp (double (or b 1.0)) 0.0 1.0)))

(defn- color-from-rgb
  "Create JavaFX Color from normalized RGB values (0.0-1.0)."
  [r g b]
  (color-from-normalized (or r 1.0) (or g 1.0) (or b 1.0)))


;; Frame Drawing


(defn- draw-background
  "Draw the preview background."
  [^GraphicsContext gc width height]
  (.setFill gc Color/BLACK)
  (.fillRect gc 0 0 width height)
  ;; Draw grid lines
  (.setStroke gc (Color/rgb 40 40 40))
  (.setLineWidth gc 0.5)
  ;; Horizontal center line
  (.strokeLine gc 0 (/ height 2) width (/ height 2))
  ;; Vertical center line
  (.strokeLine gc (/ width 2) 0 (/ width 2) height))

(defn- point-blanked?
  "Check if a point is blanked (preview-friendly format).
   Preview points already have :blanked? set, or we can check RGB values."
  [{:keys [blanked? r g b]}]
  (if (some? blanked?)
    blanked?
    (let [epsilon 1e-6]
      (and (< (or r 0) epsilon)
           (< (or g 0) epsilon)
           (< (or b 0) epsilon)))))

(defn- draw-frame-points
  "Draw frame points as dots."
  [^GraphicsContext gc width height frame]
  (when-let [points (:points frame)]
    (doseq [{:keys [x y r g b] :as pt} points]
      (when-not (point-blanked? pt)
        (let [px (normalize-coord x width)
              py (normalize-coord (- y) height)  ;; Flip Y for screen coords
              color (color-from-rgb r g b)]
          (.setFill gc color)
          (.fillOval gc (- px 2) (- py 2) 4 4))))))

(defn- draw-frame-lines
  "Draw frame as connected lines (more like actual laser output)."
  [^GraphicsContext gc width height frame]
  (when-let [points (:points frame)]
    (let [point-pairs (partition 2 1 points)]
      (doseq [[p1 p2] point-pairs]
        (when (and (not (point-blanked? p1)) (not (point-blanked? p2)))
          (let [x1 (normalize-coord (:x p1) width)
                y1 (normalize-coord (- (:y p1)) height)
                x2 (normalize-coord (:x p2) width)
                y2 (normalize-coord (- (:y p2)) height)
                color (color-from-rgb (:r p1) (:g p1) (:b p1))]
            (.setStroke gc color)
            (.setLineWidth gc 2)
            (.strokeLine gc x1 y1 x2 y2)))))))

(defn- draw-no-content
  "Draw placeholder when no frame is available."
  [^GraphicsContext gc width height]
  (.setFill gc (Color/rgb 80 80 80))
  (.setFont gc (javafx.scene.text.Font. "System" 14))
  (let [text "No Preview"
        text-width 80
        x (- (/ width 2) (/ text-width 2))
        y (/ height 2)]
    (.fillText gc text x y)))


;; Draw Function (called by cljfx Canvas :draw prop)


(defn draw-preview
  "Main draw function for the preview canvas.
   
   This is passed to the Canvas :draw prop and called whenever the canvas
   needs to be redrawn.
   
   Parameters:
   - canvas: The JavaFX Canvas
   - frame: The frame data to render (or nil)"
  [^Canvas canvas frame]
  (let [gc (.getGraphicsContext2D canvas)
        width (.getWidth canvas)
        height (.getHeight canvas)]
    ;; Clear and draw background
    (draw-background gc width height)
    ;; Draw frame content
    (if frame
      (do
        ;; Draw lines first (underneath)
        (draw-frame-lines gc width height frame)
        ;; Draw points on top so they're visible
        (draw-frame-points gc width height frame))
      (draw-no-content gc width height))))


;; Zone Group Dropdown Helpers


(defn- zone-filter-converter
  "StringConverter for zone filter combo-box items."
  []
  (proxy [StringConverter] []
    (toString [item]
      (cond
        (nil? item) "Select Zone"
        (nil? (:id item)) "All Content"
        (keyword? (:id item)) (str "Zone: " (name (:id item)))
        :else (str item)))
    (fromString [_s] nil)))

(defn- build-zone-filter-items
  "Build items for zone filter combo-box.
   Returns vector of maps with :id and :name keys."
  [zone-groups]
  (into [{:id nil :name "All Content"}]  ;; nil = master view (show all)
        (mapv (fn [zg]
                {:id (:id zg)
                 :name (str "Zone: " (:name zg))})
              zone-groups)))

(defn- find-selected-item
  "Find the selected item in the list based on zone-filter value."
  [items zone-filter]
  (or (first (filter #(= (:id %) zone-filter) items))
      (first items)))


;; Zone Selector Components


(defn zone-selector-popup-item
  "Single item in the zone selector popup menu."
  [{:keys [cell-index zone-id zone-name zone-color selected?]}]
  {:fx/type :h-box
   :spacing 6
   :alignment :center-left
   :padding {:left 8 :right 8 :top 6 :bottom 6}
   :style (str "-fx-cursor: hand;"
               (when selected? " -fx-background-color: #404040;"))
   :on-mouse-clicked {:event/type :preview/set-cell-zone
                      :cell-index cell-index
                      :zone-group-id zone-id}
   :children (filterv some?
                      [{:fx/type :circle
                        :radius 5
                        :fill zone-color}
                       {:fx/type :label
                        :text zone-name
                        :style "-fx-text-fill: white; -fx-font-size: 11;"}
                       (when selected?
                         {:fx/type :label
                          :text "✓"
                          :style "-fx-text-fill: #4AD94A; -fx-font-size: 11;"})])})

(defn zone-selector-popup
  "Popup menu for selecting zone group for a preview cell.
   Positioned below the label in a stack-pane."
  [{:keys [fx/context cell-index]}]
  (let [zone-groups (fx/sub-ctx context subs/zone-groups-list)
        cell-config (fx/sub-ctx context subs/preview-cell-config cell-index)
        current-zone-id (:zone-group-id cell-config)
        ;; Build items: Master (nil) first, then all zone groups
        items (into [{:id nil :name "Master (All)" :color "#808080"}]
                    zone-groups)]
    {:fx/type :v-box
     ;; Use translate-y to position below the label
     :style "-fx-background-color: #2D2D2D; -fx-padding: 4; -fx-background-radius: 4; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 8, 0, 0, 2); -fx-translate-y: 22;"
     :on-mouse-exited {:event/type :preview/close-zone-selector}
     :children (vec (for [{:keys [id name color]} items]
                      {:fx/type zone-selector-popup-item
                       :cell-index cell-index
                       :zone-id id
                       :zone-name name
                       :zone-color (or color "#808080")
                       :selected? (= id current-zone-id)}))}))

(defn zone-selector-label
  "Clickable zone label that opens the zone selector popup."
  [{:keys [fx/context cell-index zone-name zone-color]}]
  {:fx/type :h-box
   :alignment :center-left
   :spacing 4
   :padding {:left 4 :right 4 :top 2 :bottom 2}
   :style (str "-fx-background-color: " zone-color "40; -fx-cursor: hand;")
   :on-mouse-clicked {:event/type :preview/open-zone-selector
                      :cell-index cell-index}
   :children [{:fx/type :circle
               :radius 4
               :fill zone-color}
              {:fx/type :label
               :text zone-name
               :style "-fx-text-fill: white; -fx-font-size: 10;"}
              {:fx/type :label
               :text "▼"
               :style "-fx-text-fill: #808080; -fx-font-size: 8;"}]})


;; Preview Grid Helper Functions


(defn- any-cue-targets-zone?
  "Check if ANY active cue targets the given zone-group-id.
   If zone-group-id is nil, always returns true (master view shows all)."
  [cue-destinations zone-group-id]
  (or (nil? zone-group-id)
      (some (fn [[_cell targets]]
              (contains? targets zone-group-id))
            cue-destinations)))


;; Preview Grid Components


(defn preview-cell
  "Single preview cell in the grid.
   Shows frame if any cue targets this cell's zone filter.
   Includes clickable zone label that opens zone selector dropdown."
  [{:keys [fx/context cell-index width height]}]
  (let [frame-data (fx/sub-ctx context subs/preview-frame-data)
        cell-config (fx/sub-ctx context subs/preview-cell-config cell-index)
        zone-id (:zone-group-id cell-config)
        zone-groups (fx/sub-ctx context subs/zone-groups-list)
        zone-group (first (filter #(= (:id %) zone-id) zone-groups))
        zone-name (or (:name zone-group)
                      (if zone-id (name zone-id) "Master"))
        zone-color (or (:color zone-group) "#808080")
        show-labels? (fx/sub-ctx context subs/preview-show-labels?)
        popup-open-cell (fx/sub-ctx context subs/preview-zone-selector-open)
        popup-open? (= popup-open-cell cell-index)
        cue-destinations (:cue-destinations frame-data {})
        matches? (any-cue-targets-zone? cue-destinations zone-id)
        ;; Only show points if any cue targets this cell's zone
        display-frame (when matches?
                        {:points (:points frame-data)})
        label-height (if show-labels? 20 0)
        canvas-height (- height label-height 4)]
    {:fx/type :v-box
     :style (str "-fx-border-color: " zone-color "; -fx-border-width: 2; -fx-background-color: #121212;")
     :children (filterv some?
                        [(when show-labels?
                           {:fx/type :stack-pane
                            :alignment :top-left
                            :children (filterv some?
                                               [{:fx/type zone-selector-label
                                                 :cell-index cell-index
                                                 :zone-name zone-name
                                                 :zone-color zone-color}
                                                ;; Overlay dropdown menu when open
                                                (when popup-open?
                                                  {:fx/type zone-selector-popup
                                                   :cell-index cell-index})])})
                         {:fx/type :canvas
                          :width (- width 4)
                          :height canvas-height
                          :draw #(draw-preview % display-frame)}])}))

(defn preview-grid
  "Grid of preview cells."
  [{:keys [fx/context]}]
  (let [[cols rows] (fx/sub-ctx context subs/preview-grid-layout)
        preview-cfg (fx/sub-ctx context subs/preview-config)
        total-width (:width preview-cfg 400)
        total-height (:height preview-cfg 400)
        ;; Account for spacing between cells
        spacing 2
        cell-width (/ (- total-width (* spacing (dec cols))) cols)
        cell-height (/ (- total-height (* spacing (dec rows))) rows)]
    {:fx/type :v-box
     :spacing spacing
     :children (vec
                (for [row (range rows)]
                  {:fx/type :h-box
                   :spacing spacing
                   :children (vec
                              (for [col (range cols)]
                                (let [idx (+ col (* row cols))]
                                  {:fx/type preview-cell
                                   :fx/key idx
                                   :cell-index idx
                                   :width cell-width
                                   :height cell-height})))}))}))

(defn preview-grid-header
  "Header for the preview grid panel with layout selector."
  [{:keys [fx/context]}]
  (let [stats (fx/sub-ctx context subs/frame-stats)
        [cols rows] (fx/sub-ctx context subs/preview-grid-layout)]
    {:fx/type :h-box
     :alignment :center-left
     :spacing 8
     :padding {:left 8 :right 8 :top 4 :bottom 4}
     :children [{:fx/type :label
                 :text "Preview Grid"
                 :style "-fx-text-fill: white; -fx-font-size: 12; -fx-font-weight: bold;"}
                {:fx/type :region :h-box/hgrow :always}
                {:fx/type :combo-box
                 :value (str cols "x" rows)
                 :items ["1x1" "2x1" "1x2" "2x2" "3x2"]
                 :on-value-changed {:event/type :preview/set-grid-layout}
                 :pref-width 80
                 :style "-fx-font-size: 10;"}
                {:fx/type :label
                 :text (str (:fps stats 0) " FPS")
                 :style "-fx-text-fill: #808080; -fx-font-size: 10;"}]}))

(defn preview-grid-panel
  "Complete preview panel with grid header and cells."
  [{:keys [fx/context]}]
  {:fx/type :v-box
   :style "-fx-background-color: #121212;"
   :children [{:fx/type preview-grid-header}
              {:fx/type :border-pane
               :padding 8
               :center {:fx/type preview-grid}}]})
