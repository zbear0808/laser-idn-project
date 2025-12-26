(ns laser-show.views.components.preview
  "Preview panel component for displaying laser frame output.
   
   This component renders the current laser frame to a JavaFX Canvas.
   The :draw prop is a function that receives the Canvas and renders to it."
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs])
  (:import [javafx.scene.canvas Canvas GraphicsContext]
           [javafx.scene.paint Color]))

;; ============================================================================
;; Drawing Helpers
;; ============================================================================

(defn- normalize-coord
  "Convert normalized coordinate (-1 to 1) to pixel coordinate."
  [normalized size]
  (* (+ normalized 1.0) 0.5 size))

(defn- color-from-rgb
  "Create JavaFX Color from RGB values (0-255)."
  [r g b]
  (Color/rgb (int r) (int g) (int b)))

;; ============================================================================
;; Frame Drawing
;; ============================================================================

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

(defn- draw-frame-points
  "Draw frame points as dots."
  [^GraphicsContext gc width height frame]
  (when-let [points (:points frame)]
    (doseq [{:keys [x y r g b blanked?]} points]
      (when-not blanked?
        (let [px (normalize-coord x width)
              py (normalize-coord (- y) height)  ;; Flip Y for screen coords
              color (color-from-rgb (or r 255) (or g 255) (or b 255))]
          (.setFill gc color)
          (.fillOval gc (- px 2) (- py 2) 4 4))))))

(defn- draw-frame-lines
  "Draw frame as connected lines (more like actual laser output)."
  [^GraphicsContext gc width height frame]
  (when-let [points (:points frame)]
    (let [point-pairs (partition 2 1 points)]
      (doseq [[p1 p2] point-pairs]
        (when (and (not (:blanked? p1)) (not (:blanked? p2)))
          (let [x1 (normalize-coord (:x p1) width)
                y1 (normalize-coord (- (:y p1)) height)
                x2 (normalize-coord (:x p2) width)
                y2 (normalize-coord (- (:y p2)) height)
                color (color-from-rgb (or (:r p1) 255) 
                                      (or (:g p1) 255) 
                                      (or (:b p1) 255))]
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

;; ============================================================================
;; Draw Function (called by cljfx Canvas :draw prop)
;; ============================================================================

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

;; ============================================================================
;; Preview Canvas Component
;; ============================================================================

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

;; ============================================================================
;; Preview Panel (with controls)
;; ============================================================================

(defn preview-header
  "Header for the preview panel."
  [{:keys [fx/context]}]
  (let [stats (fx/sub-ctx context subs/frame-stats)]
    {:fx/type :h-box
     :alignment :center-left
     :padding {:left 8 :right 8 :top 4 :bottom 4}
     :style "-fx-background-color: #252525;"
     :children [{:fx/type :label
                 :text "Preview"
                 :style "-fx-text-fill: white; -fx-font-size: 12; -fx-font-weight: bold;"}
                {:fx/type :region :h-box/hgrow :always}
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
     :style "-fx-background-color: #2D2D2D;"
     :children [{:fx/type preview-header}
                {:fx/type :border-pane
                 :padding 8
                 :center {:fx/type preview-canvas
                          :width width
                          :height height}}]}))
