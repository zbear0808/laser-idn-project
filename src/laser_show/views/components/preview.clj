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


;; Preview Canvas Component


(defn preview-canvas
  "Canvas component that displays the current laser frame.
   
   Uses the :draw prop pattern for Canvas rendering."
  [{:keys [fx/context width height]}]
  (let [frame (fx/sub-ctx context subs/current-frame)
        w (or width 400)
        h (or height 400)]
    {:fx/type :canvas
     :width w
     :height h
     :draw #(draw-preview % frame)}))


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


;; Preview Panel (with controls)


(defn preview-header
  "Header for the preview panel with zone group filter dropdown."
  [{:keys [fx/context]}]
  (let [stats (fx/sub-ctx context subs/frame-stats)
        zone-filter (fx/sub-ctx context subs/preview-zone-filter)
        zone-groups (fx/sub-ctx context subs/zone-groups-list)
        filter-items (build-zone-filter-items zone-groups)
        selected-item (find-selected-item filter-items zone-filter)]
    {:fx/type :h-box
     :alignment :center-left
     :spacing 8
     :padding {:left 8 :right 8 :top 4 :bottom 4}
     :children [{:fx/type :label
                 :text "Preview"
                 :style "-fx-text-fill: white; -fx-font-size: 12; -fx-font-weight: bold;"}
                {:fx/type :region :h-box/hgrow :always}
                ;; Zone group filter dropdown
                {:fx/type :combo-box
                 :value selected-item
                 :items filter-items
                 :converter (zone-filter-converter)
                 :on-value-changed {:event/type :preview/set-zone-filter}
                 :pref-width 130
                 :style "-fx-font-size: 10;"}
                {:fx/type :label
                 :text (str (:fps stats 0) " FPS")
                 :style "-fx-text-fill: #808080; -fx-font-size: 10;"}]}))

(defn preview-panel
  "Complete preview panel with header and canvas."
  [{:keys [fx/context]}]
  (let [preview-cfg (fx/sub-ctx context subs/preview-config)
        width (:width preview-cfg 400)
        height (:height preview-cfg 400)]
    {:fx/type :v-box
     :style "-fx-background-color: #121212;"
     :children [{:fx/type preview-header}
                {:fx/type :border-pane
                 :padding 8
                 :center {:fx/type preview-canvas
                          :width width
                          :height height}}]}))
