(ns laser-show.views.components.rotate-canvas
  "Circular dial canvas for rotation angle editing.
   
   Features:
   - Draggable handle rotating around center
   - Degree markings at 0°, 90°, 180°, 270°
   - Grid lines for major angles (every 30°)
   - Keyboard arrow keys for fine adjustment
   - Right-click to reset to default angle (0°)
   
   Coordinate System:
   - 0° at right (3 o'clock position)
   - Positive angles rotate counter-clockwise
   - Range: -360° to 360° (matches effect definition)
   
   Usage:
   {:fx/type rotate-canvas
    :fx/key [unique-id]
    :width 280
    :height 280
    :angle 45.0
    :on-angle-change {:event/type :chain/update-param ...}
    :on-reset {:event/type :chain/reset-params ...}}"
  (:require [cljfx.api :as fx]
            [laser-show.events.core :as events]
            [laser-show.common.util :as u])
  (:import [javafx.scene.canvas Canvas GraphicsContext]
           [javafx.scene.input MouseEvent MouseButton KeyEvent KeyCode]
           [javafx.scene.paint Color]
           [javafx.scene.text Font FontWeight TextAlignment]
           [javafx.event EventHandler]
           [javafx.application Platform]))


;; Constants


(def ^:private handle-radius 8)
(def ^:private handle-distance-ratio 0.70)  ; Handle at 70% of dial radius


;; Angle Calculations


(defn- mouse-to-angle
  "Calculate angle from mouse position relative to center.
   Returns angle in degrees, 0° at right, positive counter-clockwise."
  [mx my cx cy]
  (let [dx (- mx cx)
        dy (- my cy)
        ;; atan2 gives angle from positive X axis, clockwise positive
        ;; We want counter-clockwise positive, so negate dy
        radians (Math/atan2 (- dy) dx)
        degrees (Math/toDegrees radians)]
    degrees))

(defn- angle-to-handle-pos
  "Convert angle to handle position on canvas."
  [angle cx cy radius]
  (let [radians (Math/toRadians angle)
        handle-dist (* radius handle-distance-ratio)
        ;; Positive angles counter-clockwise means negative dy
        hx (+ cx (* handle-dist (Math/cos radians)))
        hy (- cy (* handle-dist (Math/sin radians)))]
    [hx hy]))

(defn- clamp-angle
  "Clamp angle to -360 to 360 range."
  [angle]
  (u/clamp angle -360.0 360.0))


;; Drawing Functions


(defn- draw-background
  "Draw the dark background."
  [^GraphicsContext gc width height]
  (.setFill gc Color/BLACK)
  (.fillRect gc 0 0 width height))

(defn- draw-dial-circle
  "Draw the main dial circle."
  [^GraphicsContext gc cx cy radius]
  ;; Outer circle
  (.setStroke gc (Color/web "#404040"))
  (.setLineWidth gc 2.0)
  (.strokeOval gc (- cx radius) (- cy radius) (* 2 radius) (* 2 radius))
  
  ;; Inner circle (slightly smaller)
  (let [inner-radius (* radius 0.95)]
    (.setStroke gc (Color/web "#303030"))
    (.setLineWidth gc 1.0)
    (.strokeOval gc (- cx inner-radius) (- cy inner-radius) 
                 (* 2 inner-radius) (* 2 inner-radius))))

(defn- draw-angle-marks
  "Draw tick marks and labels at major angles."
  [^GraphicsContext gc cx cy radius]
  ;; Draw minor ticks every 15°
  (.setStroke gc (Color/web "#303030"))
  (.setLineWidth gc 1.0)
  (doseq [deg (range 0 360 15)]
    (when-not (zero? (mod deg 30))
      (let [radians (Math/toRadians deg)
            inner-r (* radius 0.85)
            outer-r (* radius 0.90)
            x1 (+ cx (* inner-r (Math/cos radians)))
            y1 (- cy (* inner-r (Math/sin radians)))
            x2 (+ cx (* outer-r (Math/cos radians)))
            y2 (- cy (* outer-r (Math/sin radians)))]
        (.strokeLine gc x1 y1 x2 y2))))
  
  ;; Draw major ticks every 30°
  (.setStroke gc (Color/web "#505050"))
  (.setLineWidth gc 1.5)
  (doseq [deg (range 0 360 30)]
    (let [radians (Math/toRadians deg)
          inner-r (* radius 0.80)
          outer-r (* radius 0.92)
          x1 (+ cx (* inner-r (Math/cos radians)))
          y1 (- cy (* inner-r (Math/sin radians)))
          x2 (+ cx (* outer-r (Math/cos radians)))
          y2 (- cy (* outer-r (Math/sin radians)))]
      (.strokeLine gc x1 y1 x2 y2)))
  
  ;; Draw cardinal direction labels (0°, 90°, 180°, 270°)
  (.setFill gc (Color/web "#808080"))
  (.setFont gc (Font/font "System" FontWeight/BOLD 10.0))
  (.setTextAlign gc TextAlignment/CENTER)
  (let [label-r (* radius 0.68)]
    ;; 0° at right
    (.fillText gc "0°" (+ cx label-r) (+ cy 4))
    ;; 90° at top
    (.fillText gc "90°" cx (- cy label-r -4))
    ;; 180° at left
    (.fillText gc "180°" (- cx label-r) (+ cy 4))
    ;; 270° at bottom
    (.fillText gc "270°" cx (+ cy label-r 4))))

(defn- draw-angle-arc
  "Draw an arc showing the current angle from 0°."
  [^GraphicsContext gc cx cy radius angle]
  (when-not (zero? angle)
    (let [arc-radius (* radius 0.35)
          ;; JavaFX arc: startAngle is from positive X axis, counter-clockwise positive
          ;; Our angle is also counter-clockwise positive, so they match
          start-angle 0
          arc-extent angle]  ; Positive angle = counter-clockwise arc (same as handle)
      (.setStroke gc (Color/web "#4A6FA5" 0.6))
      (.setLineWidth gc 3.0)
      (.strokeArc gc (- cx arc-radius) (- cy arc-radius)
                  (* 2 arc-radius) (* 2 arc-radius)
                  start-angle arc-extent
                  javafx.scene.shape.ArcType/OPEN))))

(defn- draw-center-point
  "Draw the center origin point."
  [^GraphicsContext gc cx cy]
  (.setFill gc (Color/web "#606060"))
  (.fillOval gc (- cx 4) (- cy 4) 8 8)
  (.setStroke gc (Color/web "#808080"))
  (.setLineWidth gc 1.0)
  (.strokeOval gc (- cx 4) (- cy 4) 8 8))

(defn- draw-handle-line
  "Draw line from center to handle."
  [^GraphicsContext gc cx cy hx hy]
  (.setStroke gc (Color/web "#4A6FA5"))
  (.setLineWidth gc 2.0)
  (.strokeLine gc cx cy hx hy))

(defn- draw-handle
  "Draw the draggable handle."
  [^GraphicsContext gc hx hy hover? dragging?]
  (let [radius (if (or hover? dragging?) (+ handle-radius 2) handle-radius)
        color (if dragging? "#6A9FD5" "#4CAF50")]
    ;; Outer glow when hovering
    (when (or hover? dragging?)
      (.setFill gc (Color/web color 0.3))
      (.fillOval gc (- hx radius 3) (- hy radius 3)
                 (* 2 (+ radius 3)) (* 2 (+ radius 3))))
    ;; Handle fill
    (.setFill gc (Color/web color))
    (.fillOval gc (- hx radius) (- hy radius)
               (* 2 radius) (* 2 radius))
    ;; Handle border
    (.setStroke gc (Color/web "#FFFFFF" 0.8))
    (.setLineWidth gc 1.5)
    (.strokeOval gc (- hx radius) (- hy radius)
                 (* 2 radius) (* 2 radius))))


;; Hit Testing


(defn- hit-handle?
  "Check if mouse position is over the handle."
  [mx my hx hy]
  (let [threshold (+ handle-radius 5)
        dx (- mx hx)
        dy (- my hy)
        dist (Math/sqrt (+ (* dx dx) (* dy dy)))]
    (< dist threshold)))


;; Main Canvas Component


(defn rotate-canvas
  "Circular dial canvas for rotation angle editing.
   
   Props:
   - :width - Canvas width in pixels (default 280)
   - :height - Canvas height in pixels (default 280)
   - :angle - Current angle in degrees (-360 to 360)
   - :on-angle-change - Event map to dispatch when angle changes
   - :on-reset - Event map to dispatch on right-click reset"
  [{:keys [width height angle on-angle-change on-reset]
    :or {width 280 height 280 angle 0.0}}]
  
  {:fx/type fx/ext-on-instance-lifecycle
   :on-created
   (fn [^Canvas canvas]
     (let [gc (.getGraphicsContext2D canvas)
           cx (/ width 2.0)
           cy (/ height 2.0)
           radius (- (min cx cy) 20)  ; Leave margin for labels
           
           ;; Internal state
           drag-state (atom {:dragging? false
                             :hover? false
                             :mouse-over? false})
           angle-atom (atom (or angle 0.0))
           
           fine-step 1.0    ; 1° for arrow keys
           coarse-step 10.0 ; 10° for Shift+arrow keys
           
           scene-filter (atom nil)
           
           ;; Render function
           render! (fn []
                     (let [current-angle @angle-atom
                           [hx hy] (angle-to-handle-pos current-angle cx cy radius)
                           hover? (:hover? @drag-state)
                           dragging? (:dragging? @drag-state)]
                       (draw-background gc width height)
                       (draw-dial-circle gc cx cy radius)
                       (draw-angle-marks gc cx cy radius)
                       (draw-angle-arc gc cx cy radius current-angle)
                       (draw-handle-line gc cx cy hx hy)
                       (draw-center-point gc cx cy)
                       (draw-handle gc hx hy hover? dragging?)))
           
           ;; Arrow key handler
           handle-arrow-key! (fn [^KeyEvent e]
                               (when (:mouse-over? @drag-state)
                                 (let [code (.getCode e)
                                       shift? (.isShiftDown e)
                                       step (if shift? coarse-step fine-step)]
                                   (when (or (= code KeyCode/LEFT)
                                             (= code KeyCode/RIGHT))
                                     (let [delta (if (= code KeyCode/RIGHT) step (- step))
                                           new-angle (clamp-angle (+ @angle-atom delta))]
                                       (reset! angle-atom new-angle)
                                       (render!)
                                       (when on-angle-change
                                         (events/dispatch! (assoc on-angle-change
                                                                  :param-key :angle
                                                                  :value new-angle)))
                                       (.consume e))))))]
       
       ;; Mouse pressed - start drag or reset
       (.setOnMousePressed
        canvas
        (reify EventHandler
          (handle [_ e]
            (let [mx (.getX e)
                  my (.getY e)
                  button (.getButton e)
                  [hx hy] (angle-to-handle-pos @angle-atom cx cy radius)]
              (cond
                ;; Right-click - reset to 0°
                (= button MouseButton/SECONDARY)
                (do
                  (reset! angle-atom 0.0)
                  (render!)
                  (when on-reset
                    (events/dispatch! on-reset)))
                
                ;; Left-click on handle or anywhere - start drag
                (= button MouseButton/PRIMARY)
                (do
                  (swap! drag-state assoc :dragging? true)
                  ;; Set initial angle from click position
                  (let [new-angle (clamp-angle (mouse-to-angle mx my cx cy))]
                    (reset! angle-atom new-angle)
                    (render!)
                    (when on-angle-change
                      (events/dispatch! (assoc on-angle-change
                                               :param-key :angle
                                               :value new-angle))))))))))
       
       ;; Mouse dragged - update angle
       (.setOnMouseDragged
        canvas
        (reify EventHandler
          (handle [_ e]
            (when (:dragging? @drag-state)
              (let [mx (.getX e)
                    my (.getY e)
                    new-angle (clamp-angle (mouse-to-angle mx my cx cy))]
                (reset! angle-atom new-angle)
                (render!)
                (when on-angle-change
                  (events/dispatch! (assoc on-angle-change
                                           :param-key :angle
                                           :value new-angle))))))))
       
       ;; Mouse released - end drag
       (.setOnMouseReleased
        canvas
        (reify EventHandler
          (handle [_ e]
            (swap! drag-state assoc :dragging? false)
            (render!))))
       
       ;; Mouse moved - update hover state
       (.setOnMouseMoved
        canvas
        (reify EventHandler
          (handle [_ e]
            (let [mx (.getX e)
                  my (.getY e)
                  [hx hy] (angle-to-handle-pos @angle-atom cx cy radius)
                  over-handle? (hit-handle? mx my hx hy)
                  current-hover (:hover? @drag-state)]
              (when (not= over-handle? current-hover)
                (swap! drag-state assoc :hover? over-handle?)
                (render!))
              (if over-handle?
                (.setStyle canvas "-fx-cursor: hand;")
                (.setStyle canvas "-fx-cursor: crosshair;"))))))
       
       ;; Mouse entered - track mouse over and register key filter
       (.setOnMouseEntered
        canvas
        (reify EventHandler
          (handle [_ e]
            (swap! drag-state assoc :mouse-over? true)
            (when-let [scene (.getScene canvas)]
              (when-not @scene-filter
                (let [filter (reify EventHandler
                               (handle [_ e]
                                 (when (instance? KeyEvent e)
                                   (handle-arrow-key! e))))]
                  (reset! scene-filter filter)
                  (.addEventFilter scene KeyEvent/KEY_PRESSED filter)))))))
       
       ;; Mouse exited - clear hover state
       (.setOnMouseExited
        canvas
        (reify EventHandler
          (handle [_ e]
            (swap! drag-state assoc :hover? false :mouse-over? false)
            (render!))))
       
       ;; Initial render
       (render!)
       (.setFocusTraversable canvas true)))
   
   :desc {:fx/type :canvas
          :width width
          :height height
          :style "-fx-cursor: crosshair;"}})
