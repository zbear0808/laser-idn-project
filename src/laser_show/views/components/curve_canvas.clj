(ns laser-show.views.components.curve-canvas
  "Canvas component for editing 1D brightness transfer curves.
   
   Features:
   - 280x280 pixel canvas with grid display
   - 0-255 input (X) and output (Y) axes
   - Diagonal reference line (identity mapping)
   - Draggable control points with constraints:
     - Corner points (x=0, x=255): Y-axis only, cannot be deleted
     - Middle points: fully draggable, can be added/removed
   - Click empty space to add new point
   - Right-click to remove points
   - Hover states with coordinate display
   - Color-coded rendering based on channel (R/G/B)
   - Smooth curve rendering using spline interpolation
   
   Usage:
   {:fx/type curve-canvas
    :fx/key [unique-id]
    :width 280
    :height 280
    :color \"#FF5555\"  ; channel color
    :control-points [[0 0] [64 80] [255 255]]  ; sorted [x y] pairs
    :on-add-point {:event/type :effects/add-curve-point ...}
    :on-update-point {:event/type :effects/update-curve-point ...}
    :on-remove-point {:event/type :effects/remove-curve-point ...}}"
  (:require [cljfx.api :as fx]
            [laser-show.events.core :as events]
            [laser-show.animation.effects.curves :as curves])
  (:import [javafx.scene.canvas Canvas GraphicsContext]
           [javafx.scene.input MouseButton MouseEvent]
           [javafx.scene.paint Color]
           [javafx.scene.text Font FontWeight]))


;; Coordinate Transformations


(defn- value-to-canvas
  "Convert curve value (0-255) to canvas pixel coordinate.
   Note: Y is inverted (0 at bottom, 255 at top)."
  [value canvas-size padding]
  (let [usable-size (- canvas-size (* 2 padding))
        normalized (/ value 255.0)]
    (+ padding (* normalized usable-size))))

(defn- canvas-to-value
  "Convert canvas pixel coordinate to curve value (0-255).
   Note: Y is inverted (0 at bottom, 255 at top)."
  [pixel canvas-size padding]
  (let [usable-size (- canvas-size (* 2 padding))
        clamped (max padding (min (- canvas-size padding) pixel))
        normalized (/ (- clamped padding) usable-size)]
    (* normalized 255.0)))

(defn- point-to-canvas
  "Convert a curve control point [x y] to canvas coordinates [cx cy].
   Y is inverted for display (0 at bottom)."
  [[x y] width height padding]
  (let [cx (value-to-canvas x width padding)
        cy (- height (value-to-canvas y height padding))]
    [cx cy]))

(defn- canvas-to-point
  "Convert canvas coordinates [cx cy] to curve control point [x y].
   Y is inverted from display (0 at bottom)."
  [cx cy width height padding]
  (let [x (canvas-to-value cx width padding)
        y (canvas-to-value (- height cy) height padding)]
    [(curves/clamp x 0.0 255.0)
     (curves/clamp y 0.0 255.0)]))


;; Drawing Functions


(defn- draw-background
  "Draw the dark background."
  [^GraphicsContext gc width height]
  (.setFill gc (Color/web "#1E1E1E"))
  (.fillRect gc 0 0 width height))

(defn- draw-grid
  "Draw grid lines on the canvas."
  [^GraphicsContext gc width height padding]
  (.setStroke gc (Color/web "#303030"))
  (.setLineWidth gc 0.5)
  
  (let [usable-size (- width (* 2 padding))
        grid-count 8
        step (/ usable-size grid-count)]
    ;; Vertical grid lines
    (doseq [i (range (inc grid-count))]
      (let [x (+ padding (* i step))]
        (.strokeLine gc x padding x (- height padding))))
    ;; Horizontal grid lines
    (doseq [i (range (inc grid-count))]
      (let [y (+ padding (* i step))]
        (.strokeLine gc padding y (- width padding) y)))))

(defn- draw-diagonal
  "Draw the identity reference line (diagonal)."
  [^GraphicsContext gc width height padding]
  (.setStroke gc (Color/web "#404040"))
  (.setLineWidth gc 1.0)
  (.setLineDashes gc (double-array [4.0 4.0]))
  ;; Diagonal from bottom-left (0,0) to top-right (255,255)
  (.strokeLine gc padding (- height padding)
               (- width padding) padding)
  (.setLineDashes gc nil))

(defn- draw-axis-labels
  "Draw the axis labels (0, 255)."
  [^GraphicsContext gc width height padding]
  (.setFill gc (Color/web "#606060"))
  (.setFont gc (Font/font "System" 9.0))
  ;; X-axis labels
  (.fillText gc "0" (- padding 5) (+ (- height padding) 12))
  (.fillText gc "255" (- width padding 10) (+ (- height padding) 12))
  ;; Y-axis labels
  (.fillText gc "0" (- padding 15) (- height padding))
  (.fillText gc "255" (- padding 20) (+ padding 4)))

(defn- draw-curve
  "Draw the smooth curve through control points."
  [^GraphicsContext gc width height padding control-points color]
  (when (seq control-points)
    (let [samples (curves/sample-curve-for-display control-points 100)
          canvas-points (mapv #(point-to-canvas % width height padding) samples)]
      (.setStroke gc (Color/web color))
      (.setLineWidth gc 2.0)
      (.beginPath gc)
      (when-let [[first-x first-y] (first canvas-points)]
        (.moveTo gc first-x first-y)
        (doseq [[x y] (rest canvas-points)]
          (.lineTo gc x y)))
      (.stroke gc))))

(defn- draw-control-point
  "Draw a single control point."
  [^GraphicsContext gc x y radius color hover? is-corner?]
  (let [actual-radius (if hover? (+ radius 2) radius)]
    ;; Outer glow when hovering
    (when hover?
      (.setFill gc (Color/web color 0.3))
      (.fillOval gc (- x actual-radius 3) (- y actual-radius 3)
                 (* 2 (+ actual-radius 3)) (* 2 (+ actual-radius 3))))
    
    ;; Point fill
    (.setFill gc (Color/web color (if is-corner? 0.8 1.0)))
    (.fillOval gc (- x actual-radius) (- y actual-radius)
               (* 2 actual-radius) (* 2 actual-radius))
    
    ;; Border
    (.setStroke gc (Color/web "#FFFFFF" 0.8))
    (.setLineWidth gc (if is-corner? 2.0 1.5))
    (.strokeOval gc (- x actual-radius) (- y actual-radius)
                 (* 2 actual-radius) (* 2 actual-radius))))

(defn- draw-control-points
  "Draw all control points."
  [^GraphicsContext gc width height padding control-points color hover-idx]
  (doseq [[idx [x y]] (map-indexed vector control-points)]
    (let [[cx cy] (point-to-canvas [x y] width height padding)
          is-corner? (or (= idx 0) (= idx (dec (count control-points))))
          hover? (= idx hover-idx)]
      (draw-control-point gc cx cy 5 color hover? is-corner?))))

(defn- draw-coordinate-tooltip
  "Draw coordinate tooltip near hover point."
  [^GraphicsContext gc width height padding control-points hover-idx]
  (when hover-idx
    (when-let [[x y] (nth control-points hover-idx nil)]
      (let [[cx cy] (point-to-canvas [x y] width height padding)
            text (format "(%d, %d)" (int x) (int y))
            ;; Position tooltip above and right of point
            tx (+ cx 10)
            ty (- cy 10)]
        (.setFill gc (Color/web "#000000" 0.8))
        (.fillRoundRect gc (- tx 4) (- ty 12) 60 16 4 4)
        (.setFill gc (Color/web "#FFFFFF"))
        (.setFont gc (Font/font "Consolas" FontWeight/NORMAL 10.0))
        (.fillText gc text tx ty)))))


;; Hit Testing


(defn- find-closest-point
  "Find the closest control point to mouse coordinates within threshold."
  [mx my control-points width height padding threshold]
  (when (seq control-points)
    (let [results
          (for [[idx [x y]] (map-indexed vector control-points)]
            (let [[cx cy] (point-to-canvas [x y] width height padding)
                  dist (Math/sqrt (+ (Math/pow (- mx cx) 2)
                                     (Math/pow (- my cy) 2)))]
              {:idx idx :dist dist}))]
      (->> results
           (filter #(< (:dist %) threshold))
           (sort-by :dist)
           first
           :idx))))


;; Main Canvas Component


(defn curve-canvas
  "Canvas component for editing 1D brightness transfer curves.
   
   Props:
   - :width - Canvas width in pixels (default 280)
   - :height - Canvas height in pixels (default 280)
   - :color - Color for curve and points (e.g., \"#FF5555\" for red)
   - :control-points - Vector of [x y] pairs (must be sorted by x)
   - :on-add-point - Event map to dispatch when adding a point
   - :on-update-point - Event map for point updates
   - :on-remove-point - Event map for point removal"
  [{:keys [width height color control-points on-add-point on-update-point on-remove-point]
    :or {width 280 height 280 color "#FFFFFF"}}]
  
  (let [padding 25]  ;; Padding for axis labels
    {:fx/type fx/ext-on-instance-lifecycle
     :on-created
     (fn [^Canvas canvas]
       (let [gc (.getGraphicsContext2D canvas)
             ;; Internal state for interaction
             drag-state (atom {:dragging? false
                               :point-idx nil
                               :hover-idx nil})
             points-atom (atom (or control-points [[0 0] [255 255]]))]
         
         ;; Render function
         (letfn [(render! []
                   (let [points @points-atom
                         hover-idx (:hover-idx @drag-state)]
                     (draw-background gc width height)
                     (draw-grid gc width height padding)
                     (draw-diagonal gc width height padding)
                     (draw-axis-labels gc width height padding)
                     (draw-curve gc width height padding points color)
                     (draw-control-points gc width height padding points color hover-idx)
                     (draw-coordinate-tooltip gc width height padding points hover-idx)))]
           
           ;; Mouse pressed - start drag or add point
           (.setOnMousePressed
            canvas
            (reify javafx.event.EventHandler
              (handle [_ e]
                (let [mx (.getX e)
                      my (.getY e)
                      button (.getButton e)
                      points @points-atom
                      hit-idx (find-closest-point mx my points width height padding 12)]
                  (cond
                    ;; Right-click on point - remove it
                    (and (= button MouseButton/SECONDARY) hit-idx)
                    (let [n (count points)
                          is-corner? (or (= hit-idx 0) (= hit-idx (dec n)))]
                      (when (and (not is-corner?) on-remove-point)
                        (events/dispatch! (assoc on-remove-point :point-idx hit-idx))
                        (swap! points-atom curves/remove-point hit-idx)
                        (render!)))
                    
                    ;; Left-click on point - start drag
                    (and (= button MouseButton/PRIMARY) hit-idx)
                    (do
                      (swap! drag-state assoc
                             :dragging? true
                             :point-idx hit-idx)
                      (render!))
                    
                    ;; Left-click on empty space - add new point
                    (and (= button MouseButton/PRIMARY) (nil? hit-idx))
                    (let [[x y] (canvas-to-point mx my width height padding)]
                      (when on-add-point
                        (events/dispatch! (assoc on-add-point
                                                 :x (int x)
                                                 :y (int y)))
                        (swap! points-atom curves/add-point x y)
                        (render!))))))))
           
           ;; Mouse dragged - update point position
           (.setOnMouseDragged
            canvas
            (reify javafx.event.EventHandler
              (handle [_ e]
                (when (:dragging? @drag-state)
                  (let [point-idx (:point-idx @drag-state)
                        points @points-atom
                        n (count points)
                        is-corner? (or (= point-idx 0) (= point-idx (dec n)))
                        [x y] (canvas-to-point (.getX e) (.getY e) width height padding)
                        ;; Corner points: keep X fixed
                        final-x (if is-corner?
                                  (first (nth points point-idx))
                                  x)]
                    ;; Update local state for immediate feedback
                    (swap! points-atom curves/update-point point-idx final-x y)
                    (render!)
                    ;; Dispatch event for state update
                    (when on-update-point
                      (events/dispatch! (assoc on-update-point
                                               :point-idx point-idx
                                               :x (int final-x)
                                               :y (int y)))))))))
           
           ;; Mouse released - end drag
           (.setOnMouseReleased
            canvas
            (reify javafx.event.EventHandler
              (handle [_ e]
                (swap! drag-state assoc
                       :dragging? false
                       :point-idx nil)
                (render!))))
           
           ;; Mouse moved - update hover state
           (.setOnMouseMoved
            canvas
            (reify javafx.event.EventHandler
              (handle [_ e]
                (let [mx (.getX e)
                      my (.getY e)
                      points @points-atom
                      hover-idx (find-closest-point mx my points width height padding 12)
                      current-hover (:hover-idx @drag-state)]
                  (when (not= hover-idx current-hover)
                    (swap! drag-state assoc :hover-idx hover-idx)
                    (render!))
                  ;; Update cursor
                  (if hover-idx
                    (.setStyle canvas "-fx-cursor: hand;")
                    (.setStyle canvas "-fx-cursor: crosshair;"))))))
           
           ;; Mouse exited - clear hover
           (.setOnMouseExited
            canvas
            (reify javafx.event.EventHandler
              (handle [_ e]
                (when (:hover-idx @drag-state)
                  (swap! drag-state assoc :hover-idx nil)
                  (render!)))))
           
           ;; Initial render
           (render!))))
     
     :desc {:fx/type :canvas
            :width width
            :height height
            :style "-fx-cursor: crosshair;"}}))
