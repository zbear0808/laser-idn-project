(ns laser-show.views.components.visual-editors.scale-canvas
  "Scale editor canvas with edge/corner handles.
   
   Features:
   - Centered rectangle that scales from center
   - 8 handles: 4 corners + 4 edge midpoints
   - Uniform scale mode (external prop controls behavior)
   - Supports negative values for mirroring
   - Right-click to reset to default (1.0, 1.0)
   - Keyboard arrow keys for fine adjustment
   
   Handle Behaviors:
   - Corner handles: scale both X and Y
   - Edge handles: scale only the corresponding axis
   - When uniform mode: all handles scale both axes equally
   
   Usage:
   {:fx/type scale-canvas
    :fx/key [unique-id]
    :width 280
    :height 280
    :x-scale 1.5
    :y-scale 1.5
    :uniform? false
    :on-scale-change {:event/type :chain/update-param ...}
    :on-reset {:event/type :chain/reset-params ...}}"
  (:require [cljfx.api :as fx]
            [laser-show.events.core :as events]
            [laser-show.common.util :as u])
  (:import [javafx.scene.canvas Canvas GraphicsContext]
           [javafx.scene.input MouseEvent MouseButton KeyEvent KeyCode]
           [javafx.scene.paint Color]
           [javafx.scene.text Font FontWeight]
           [javafx.event EventHandler]))


;; Constants


(def ^:private handle-radius 6)
(def ^:private base-rect-size 0.4)  ; Base rectangle size as fraction of canvas (at scale 1.0)
(def ^:private min-scale -5.0)
(def ^:private max-scale 5.0)


;; Handle Definitions
;; Each handle has :id, :axis (which axes it affects), and position calculation


(def ^:private handle-defs
  "Definition of the 8 handles around the rectangle."
  [{:id :tl :axis :both :pos-fn (fn [x-half y-half] [(- x-half) y-half])}        ; top-left
   {:id :t  :axis :y    :pos-fn (fn [_x-half y-half] [0 y-half])}                ; top
   {:id :tr :axis :both :pos-fn (fn [x-half y-half] [x-half y-half])}            ; top-right
   {:id :l  :axis :x    :pos-fn (fn [x-half _y-half] [(- x-half) 0])}            ; left
   {:id :r  :axis :x    :pos-fn (fn [x-half _y-half] [x-half 0])}                ; right
   {:id :bl :axis :both :pos-fn (fn [x-half y-half] [(- x-half) (- y-half)])}    ; bottom-left
   {:id :b  :axis :y    :pos-fn (fn [_x-half y-half] [0 (- y-half)])}            ; bottom
   {:id :br :axis :both :pos-fn (fn [x-half y-half] [x-half (- y-half)])}])      ; bottom-right


;; Coordinate Transformations


(defn- scale-to-canvas
  "Convert scale value to canvas rectangle half-size.
   Negative scales are supported (flip the rectangle)."
  [scale base-size max-size]
  (* (Math/abs scale) base-size max-size))

(defn- world-to-canvas
  "Convert world coordinates (centered at 0,0) to canvas coordinates."
  [wx wy cx cy]
  [(+ cx wx) (- cy wy)])

(defn- canvas-to-world
  "Convert canvas coordinates to world coordinates (centered at 0,0)."
  [px py cx cy]
  [(- px cx) (- cy py)])

(defn- clamp-scale
  "Clamp scale to valid range."
  [s]
  (u/clamp s min-scale max-scale))


;; Handle Position Calculations


(defn- calculate-handle-positions
  "Calculate canvas positions for all 8 handles based on current scale."
  [x-scale y-scale cx cy base-size max-size]
  (let [x-half (scale-to-canvas x-scale base-size max-size)
        y-half (scale-to-canvas y-scale base-size max-size)
        ;; Account for negative scales (flip sign)
        x-sign (if (neg? x-scale) -1 1)
        y-sign (if (neg? y-scale) -1 1)]
    (u/map-into
     :id
     (fn [{:keys [id pos-fn]}]
       (let [[wx wy] (pos-fn (* x-sign x-half) (* y-sign y-half))
             [px py] (world-to-canvas wx wy cx cy)]
         {:x px :y py}))
     handle-defs)))


;; Drawing Functions


(defn- draw-background
  "Draw the dark background."
  [^GraphicsContext gc width height]
  (.setFill gc Color/BLACK)
  (.fillRect gc 0 0 width height))

(defn- draw-grid
  "Draw grid lines on the canvas."
  [^GraphicsContext gc width height cx cy]
  (.setStroke gc (Color/web "#303030"))
  (.setLineWidth gc 0.5)
  ;; Vertical grid lines
  (let [step 30]
    (doseq [offset (range step (/ width 2) step)]
      (.strokeLine gc (+ cx offset) 0 (+ cx offset) height)
      (.strokeLine gc (- cx offset) 0 (- cx offset) height))
    ;; Horizontal grid lines
    (doseq [offset (range step (/ height 2) step)]
      (.strokeLine gc 0 (+ cy offset) width (+ cy offset))
      (.strokeLine gc 0 (- cy offset) width (- cy offset)))))

(defn- draw-axes
  "Draw X and Y axes at center."
  [^GraphicsContext gc width height cx cy]
  (.setStroke gc (Color/web "#505050"))
  (.setLineWidth gc 1.5)
  ;; X axis
  (.strokeLine gc 0 cy width cy)
  ;; Y axis
  (.strokeLine gc cx 0 cx height)
  ;; Center point
  (.setFill gc (Color/web "#707070"))
  (.fillOval gc (- cx 3) (- cy 3) 6 6))

(defn- draw-rectangle
  "Draw the scaled rectangle."
  [^GraphicsContext gc cx cy x-half y-half x-scale y-scale]
  ;; Account for negative scales (flip rectangle)
  (let [x-sign (if (neg? x-scale) -1 1)
        y-sign (if (neg? y-scale) -1 1)
        ;; Calculate actual corners
        left (- cx (* x-sign x-half))
        right (+ cx (* x-sign x-half))
        top (- cy (* y-sign y-half))
        bottom (+ cy (* y-sign y-half))
        ;; Normalize for drawing
        x (min left right)
        y (min top bottom)
        w (Math/abs (- right left))
        h (Math/abs (- bottom top))]
    ;; Fill
    (.setFill gc (Color/web "#4A6FA5" 0.15))
    (.fillRect gc x y w h)
    ;; Stroke
    (.setStroke gc (Color/web "#7AB8FF"))
    (.setLineWidth gc 2.0)
    (.strokeRect gc x y w h)
    ;; Draw diagonal lines if scale is negative (indicating mirror)
    (when (neg? x-scale)
      (.setStroke gc (Color/web "#FF6B6B" 0.5))
      (.setLineWidth gc 1.0)
      (.strokeLine gc cx cy (- cx 15) (- cy 10))
      (.strokeLine gc (- cx 15) (- cy 10) (- cx 15) (- cy 5)))
    (when (neg? y-scale)
      (.setStroke gc (Color/web "#FF6B6B" 0.5))
      (.setLineWidth gc 1.0)
      (.strokeLine gc cx cy (+ cx 10) (- cy 15))
      (.strokeLine gc (+ cx 10) (- cy 15) (+ cx 5) (- cy 15)))))

(defn- draw-handle
  "Draw a single handle."
  [^GraphicsContext gc x y handle-id axis hover-id dragging-id]
  (let [is-hover? (= handle-id hover-id)
        is-dragging? (= handle-id dragging-id)
        radius (if (or is-hover? is-dragging?) (+ handle-radius 2) handle-radius)
        ;; Color based on axis
        base-color (case axis
                     :x "#4CAF50"     ; Green for X-only
                     :y "#2196F3"     ; Blue for Y-only
                     :both "#FFC107")] ; Yellow for both
    ;; Outer glow when hovering
    (when (or is-hover? is-dragging?)
      (.setFill gc (Color/web base-color 0.3))
      (.fillOval gc (- x radius 3) (- y radius 3)
                 (* 2 (+ radius 3)) (* 2 (+ radius 3))))
    ;; Handle fill
    (.setFill gc (Color/web base-color (if is-dragging? 1.0 0.9)))
    (.fillOval gc (- x radius) (- y radius) (* 2 radius) (* 2 radius))
    ;; Handle border
    (.setStroke gc (Color/web "#FFFFFF" 0.8))
    (.setLineWidth gc 1.5)
    (.strokeOval gc (- x radius) (- y radius) (* 2 radius) (* 2 radius))))

(defn- draw-handles
  "Draw all 8 handles."
  [^GraphicsContext gc handle-positions hover-id dragging-id]
  (doseq [{:keys [id axis]} handle-defs]
    (when-let [{:keys [x y]} (get handle-positions id)]
      (draw-handle gc x y id axis hover-id dragging-id))))


;; Hit Testing


(defn- find-closest-handle
  "Find the closest handle to mouse position within threshold."
  [mx my handle-positions threshold]
  (->> handle-defs
       (map (fn [{:keys [id]}]
              (when-let [{:keys [x y]} (get handle-positions id)]
                (let [dx (- mx x)
                      dy (- my y)
                      dist (Math/sqrt (+ (* dx dx) (* dy dy)))]
                  {:id id :dist dist}))))
       (filter some?)
       (filter #(< (:dist %) threshold))
       (sort-by :dist)
       first
       :id))


;; Scale Calculation from Drag


(defn- calculate-new-scale
  "Calculate new scale values based on handle being dragged and mouse position."
  [handle-id mx my cx cy base-size max-size x-scale y-scale uniform?]
  (let [;; Get world coords of mouse
        [wx wy] (canvas-to-world mx my cx cy)
        ;; Find the handle definition
        handle-def (first (filter #(= (:id %) handle-id) handle-defs))
        axis (:axis handle-def)
        ;; Calculate new scale from mouse position
        base-pixel-size (* base-size max-size)
        ;; New scale based on distance from center
        raw-x-scale (if (pos? base-pixel-size)
                      (/ (Math/abs wx) base-pixel-size)
                      1.0)
        raw-y-scale (if (pos? base-pixel-size)
                      (/ (Math/abs wy) base-pixel-size)
                      1.0)
        ;; Preserve sign based on which quadrant the handle is in
        x-sign (if (neg? wx) -1 1)
        y-sign (if (pos? wy) 1 -1)  ; Y is inverted in canvas coords
        ;; Apply constraints based on handle axis and uniform mode
        [new-x new-y]
        (if uniform?
          ;; Uniform mode: use average and apply to both
          (let [avg-scale (/ (+ raw-x-scale raw-y-scale) 2.0)]
            [(* x-sign (clamp-scale avg-scale))
             (* y-sign (clamp-scale avg-scale))])
          ;; Non-uniform mode: respect axis constraints
          (case axis
            :x [(* x-sign (clamp-scale raw-x-scale)) y-scale]
            :y [x-scale (* y-sign (clamp-scale raw-y-scale))]
            :both [(* x-sign (clamp-scale raw-x-scale))
                   (* y-sign (clamp-scale raw-y-scale))]))]
    [new-x new-y]))


;; Main Canvas Component


(defn scale-canvas
  "Scale editor canvas with edge/corner handles.
   
   Props:
   - :width - Canvas width in pixels (default 280)
   - :height - Canvas height in pixels (default 280)
   - :x-scale - Current X scale (-5.0 to 5.0)
   - :y-scale - Current Y scale (-5.0 to 5.0)
   - :uniform? - When true, all handles scale both axes equally
   - :on-scale-change - Event map to dispatch when scale changes (receives :x-scale, :y-scale)
   - :on-reset - Event map to dispatch on right-click reset"
  [{:keys [width height x-scale y-scale uniform? on-scale-change on-reset]
    :or {width 280 height 280 x-scale 1.0 y-scale 1.0 uniform? false}}]
  
  {:fx/type fx/ext-on-instance-lifecycle
   :on-created
   (fn [^Canvas canvas]
     (let [gc (.getGraphicsContext2D canvas)
           cx (/ width 2.0)
           cy (/ height 2.0)
           max-size (- (min cx cy) 30)  ; Leave margin
           base-size base-rect-size
           
           ;; Internal state
           drag-state (atom {:dragging? false
                             :dragging-handle nil
                             :hover-handle nil
                             :mouse-over? false})
           scale-atom (atom {:x (or x-scale 1.0) :y (or y-scale 1.0)})
           
           fine-step 0.05   ; 0.05 for arrow keys
           coarse-step 0.25 ; 0.25 for Shift+arrow keys
           
           scene-filter (atom nil)
           
           ;; Render function
           render! (fn []
                     (let [{:keys [x y]} @scale-atom
                           x-half (scale-to-canvas x base-size max-size)
                           y-half (scale-to-canvas y base-size max-size)
                           handle-positions (calculate-handle-positions x y cx cy base-size max-size)
                           hover-id (:hover-handle @drag-state)
                           dragging-id (:dragging-handle @drag-state)]
                       (draw-background gc width height)
                       (draw-grid gc width height cx cy)
                       (draw-axes gc width height cx cy)
                       (draw-rectangle gc cx cy x-half y-half x y)
                       (draw-handles gc handle-positions hover-id dragging-id)))
           
           ;; Arrow key handler
           handle-arrow-key! (fn [^KeyEvent e uniform-mode?]
                               (when (:mouse-over? @drag-state)
                                 (let [code (.getCode e)
                                       shift? (.isShiftDown e)
                                       step (if shift? coarse-step fine-step)
                                       {:keys [x y]} @scale-atom]
                                   (when (or (= code KeyCode/LEFT)
                                             (= code KeyCode/RIGHT)
                                             (= code KeyCode/UP)
                                             (= code KeyCode/DOWN))
                                     (let [[dx dy] (condp = code
                                                     KeyCode/LEFT  [(- step) 0]
                                                     KeyCode/RIGHT [step 0]
                                                     KeyCode/UP    [0 step]
                                                     KeyCode/DOWN  [0 (- step)])
                                           ;; Apply uniformly if uniform mode
                                           [new-x new-y]
                                           (if uniform-mode?
                                             (let [delta (if (not= dx 0) dx dy)]
                                               [(clamp-scale (+ x delta))
                                                (clamp-scale (+ y delta))])
                                             [(clamp-scale (+ x dx))
                                              (clamp-scale (+ y dy))])]
                                       (reset! scale-atom {:x new-x :y new-y})
                                       (render!)
                                       (when on-scale-change
                                         (events/dispatch! (assoc on-scale-change
                                                                  :x-scale new-x
                                                                  :y-scale new-y)))
                                       (.consume e))))))]
       
       ;; Mouse pressed - start drag or reset
       (.setOnMousePressed
        canvas
        (reify EventHandler
          (handle [_ e]
            (let [mx (.getX e)
                  my (.getY e)
                  button (.getButton e)
                  {:keys [x y]} @scale-atom
                  handle-positions (calculate-handle-positions x y cx cy base-size max-size)
                  hit-handle (find-closest-handle mx my handle-positions 15)]
              (cond
                ;; Right-click - reset to 1.0, 1.0
                (= button MouseButton/SECONDARY)
                (do
                  (reset! scale-atom {:x 1.0 :y 1.0})
                  (render!)
                  (when on-reset
                    (events/dispatch! on-reset)))
                
                ;; Left-click on handle - start drag
                (and (= button MouseButton/PRIMARY) hit-handle)
                (do
                  (swap! drag-state assoc 
                         :dragging? true 
                         :dragging-handle hit-handle)
                  (render!)))))))
       
       ;; Mouse dragged - update scale
       (.setOnMouseDragged
        canvas
        (reify EventHandler
          (handle [_ e]
            (when (:dragging? @drag-state)
              (let [mx (.getX e)
                    my (.getY e)
                    handle-id (:dragging-handle @drag-state)
                    {:keys [x y]} @scale-atom
                    [new-x new-y] (calculate-new-scale handle-id mx my cx cy 
                                                       base-size max-size x y uniform?)]
                (reset! scale-atom {:x new-x :y new-y})
                (render!)
                (when on-scale-change
                  (events/dispatch! (assoc on-scale-change
                                           :x-scale new-x
                                           :y-scale new-y))))))))
       
       ;; Mouse released - end drag
       (.setOnMouseReleased
        canvas
        (reify EventHandler
          (handle [_ e]
            (swap! drag-state assoc :dragging? false :dragging-handle nil)
            (render!))))
       
       ;; Mouse moved - update hover state
       (.setOnMouseMoved
        canvas
        (reify EventHandler
          (handle [_ e]
            (let [mx (.getX e)
                  my (.getY e)
                  {:keys [x y]} @scale-atom
                  handle-positions (calculate-handle-positions x y cx cy base-size max-size)
                  hit-handle (find-closest-handle mx my handle-positions 15)
                  current-hover (:hover-handle @drag-state)]
              (when (not= hit-handle current-hover)
                (swap! drag-state assoc :hover-handle hit-handle)
                (render!))
              (if hit-handle
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
                                   (handle-arrow-key! e uniform?))))]
                  (reset! scene-filter filter)
                  (.addEventFilter scene KeyEvent/KEY_PRESSED filter)))))))
       
       ;; Mouse exited - clear hover state
       (.setOnMouseExited
        canvas
        (reify EventHandler
          (handle [_ e]
            (swap! drag-state assoc :hover-handle nil :mouse-over? false)
            (render!))))
       
       ;; Initial render
       (render!)
       (.setFocusTraversable canvas true)))
   
   :desc {:fx/type :canvas
          :width width
          :height height
          :style "-fx-cursor: crosshair;"}})
