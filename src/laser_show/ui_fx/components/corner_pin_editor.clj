(ns laser-show.ui-fx.components.corner-pin-editor
  "Visual corner pin editor for cljfx.
   Interactive canvas for dragging corner handles to adjust quadrilateral transforms."
  (:require [cljfx.api :as fx]
            [laser-show.ui-fx.styles :as styles])
  (:import [javafx.scene.canvas Canvas GraphicsContext]
           [javafx.scene.paint Color]
           [javafx.scene.input MouseEvent MouseButton]
           [javafx.scene Cursor]))

;; ============================================================================
;; Constants
;; ============================================================================

(def ^:private handle-radius 8.0)
(def ^:private handle-hit-radius 12.0)

(def ^:private default-corners
  {:tl {:x -1.0 :y 1.0}
   :tr {:x 1.0 :y 1.0}
   :bl {:x -1.0 :y -1.0}
   :br {:x 1.0 :y -1.0}})

;; ============================================================================
;; Coordinate Conversion
;; ============================================================================

(defn- clamp [v min-v max-v]
  (max min-v (min max-v v)))

(defn- normalized-to-screen
  "Convert normalized [-2, 2] to screen coords."
  [norm-x norm-y width height margin]
  (let [eff-w (- width (* 2 margin))
        eff-h (- height (* 2 margin))
        x-ratio (/ (+ norm-x 2.0) 4.0)
        y-ratio (/ (+ norm-y 2.0) 4.0)
        sx (+ margin (* x-ratio eff-w))
        sy (+ margin (- eff-h (* y-ratio eff-h)))]
    [sx sy]))

(defn- screen-to-normalized
  "Convert screen coords to normalized [-2, 2]."
  [sx sy width height margin]
  (let [eff-w (- width (* 2 margin))
        eff-h (- height (* 2 margin))
        x-ratio (/ (- sx margin) eff-w)
        y-ratio (/ (- sy margin) eff-h)
        norm-x (- (* x-ratio 4.0) 2.0)
        norm-y (- 2.0 (* y-ratio 4.0))]
    [(clamp norm-x -2.0 2.0)
     (clamp norm-y -2.0 2.0)]))

(defn- snap-to-grid [coord spacing]
  (clamp (* (Math/round (/ coord spacing)) spacing) -2.0 2.0))

;; ============================================================================
;; Hit Detection
;; ============================================================================

(defn- distance [x1 y1 x2 y2]
  (Math/sqrt (+ (* (- x2 x1) (- x2 x1))
                (* (- y2 y1) (- y2 y1)))))

(defn- find-handle-at
  "Find corner handle at screen position."
  [sx sy corners width height margin]
  (some (fn [[k {:keys [x y]}]]
          (let [[hx hy] (normalized-to-screen x y width height margin)]
            (when (<= (distance sx sy hx hy) handle-hit-radius)
              k)))
        corners))

;; ============================================================================
;; Rendering
;; ============================================================================

(defn- draw-grid
  "Draw coordinate grid."
  [^GraphicsContext gc width height margin]
  (.setStroke gc (Color/web "#404040"))
  (.setLineWidth gc 1.0)
  
  ;; Draw grid lines at integer values
  (doseq [i (range -2 3)]
    (let [[x1 y1] (normalized-to-screen (double i) -2.0 width height margin)
          [x2 y2] (normalized-to-screen (double i) 2.0 width height margin)]
      (.strokeLine gc x1 y1 x2 y2))
    (let [[x1 y1] (normalized-to-screen -2.0 (double i) width height margin)
          [x2 y2] (normalized-to-screen 2.0 (double i) width height margin)]
      (.strokeLine gc x1 y1 x2 y2)))
  
  ;; Origin crosshair
  (.setStroke gc (Color/web "#606060"))
  (.setLineWidth gc 2.0)
  (let [[ox oy] (normalized-to-screen 0.0 0.0 width height margin)]
    (.strokeLine gc ox (- oy 10) ox (+ oy 10))
    (.strokeLine gc (- ox 10) oy (+ ox 10) oy)))

(defn- draw-quadrilateral
  "Draw the corner pin quadrilateral."
  [^GraphicsContext gc corners width height margin]
  (.setStroke gc (Color/web (:accent styles/colors)))
  (.setLineWidth gc 2.0)
  
  (let [{:keys [tl tr bl br]} corners
        [tl-x tl-y] (normalized-to-screen (:x tl) (:y tl) width height margin)
        [tr-x tr-y] (normalized-to-screen (:x tr) (:y tr) width height margin)
        [bl-x bl-y] (normalized-to-screen (:x bl) (:y bl) width height margin)
        [br-x br-y] (normalized-to-screen (:x br) (:y br) width height margin)]
    (.strokeLine gc tl-x tl-y tr-x tr-y)
    (.strokeLine gc tr-x tr-y br-x br-y)
    (.strokeLine gc br-x br-y bl-x bl-y)
    (.strokeLine gc bl-x bl-y tl-x tl-y)))

(defn- draw-handle
  "Draw a corner handle."
  [^GraphicsContext gc x y label hover? dragging?]
  (let [color (cond
                dragging? (Color/web "#00E5FF")
                hover? (Color/web "#42A5F5")
                :else (Color/web "#64B5F6"))]
    ;; Fill
    (.setFill gc color)
    (.fillOval gc (- x handle-radius) (- y handle-radius)
               (* 2 handle-radius) (* 2 handle-radius))
    
    ;; Border
    (.setStroke gc Color/WHITE)
    (.setLineWidth gc 2.0)
    (.strokeOval gc (- x handle-radius) (- y handle-radius)
                 (* 2 handle-radius) (* 2 handle-radius))
    
    ;; Label
    (.setFill gc Color/WHITE)
    (.fillText gc label (- x 6) (+ y 4))))

(defn- draw-coord-label
  "Draw coordinate label near handle."
  [^GraphicsContext gc corner-x corner-y width height margin]
  (let [[sx sy] (normalized-to-screen corner-x corner-y width height margin)
        label (format "(%.2f, %.2f)" corner-x corner-y)]
    (.setFill gc (Color/web "#000000B0"))
    (.fillRect gc (+ sx 12) (- sy 14) 80 18)
    (.setFill gc Color/WHITE)
    (.fillText gc label (+ sx 16) (- sy 2))))

(defn- render-canvas
  "Main render function."
  [^GraphicsContext gc width height margin corners hover-handle drag-handle]
  ;; Clear
  (.setFill gc Color/BLACK)
  (.fillRect gc 0 0 width height)
  
  ;; Grid
  (draw-grid gc width height margin)
  
  ;; Quadrilateral
  (draw-quadrilateral gc corners width height margin)
  
  ;; Handles
  (doseq [[k {:keys [x y]}] corners]
    (let [[sx sy] (normalized-to-screen x y width height margin)
          label (case k :tl "TL" :tr "TR" :bl "BL" :br "BR")]
      (draw-handle gc sx sy label (= k hover-handle) (= k drag-handle))))
  
  ;; Coord labels
  (doseq [[_ {:keys [x y]}] corners]
    (draw-coord-label gc x y width height margin)))

;; ============================================================================
;; Component State
;; ============================================================================

(defonce ^:private !editor-state
  (atom {:corners default-corners
         :hover-handle nil
         :drag-handle nil
         :snap? false
         :snap-spacing 0.25
         :margin 30
         :on-change nil
         :canvas nil}))

;; ============================================================================
;; Event Handlers
;; ============================================================================

(defn- handle-mouse-move
  "Handle mouse move for hover detection."
  [^MouseEvent e]
  (let [state @!editor-state
        canvas (:canvas state)]
    (when canvas
      (let [w (.getWidth ^Canvas canvas)
            h (.getHeight ^Canvas canvas)
            mx (.getX e)
            my (.getY e)
            hit (find-handle-at mx my (:corners state) w h (:margin state))]
        (when (not= hit (:hover-handle state))
          (swap! !editor-state assoc :hover-handle hit)
          (.setCursor ^Canvas canvas 
                      (if hit Cursor/HAND Cursor/DEFAULT))
          (let [gc (.getGraphicsContext2D ^Canvas canvas)]
            (render-canvas gc w h (:margin state) (:corners state) hit (:drag-handle state))))))))

(defn- handle-mouse-pressed
  "Handle mouse press for drag start."
  [^MouseEvent e]
  (when (= (.getButton e) MouseButton/PRIMARY)
    (let [state @!editor-state
          canvas (:canvas state)]
      (when canvas
        (let [w (.getWidth ^Canvas canvas)
              h (.getHeight ^Canvas canvas)
              mx (.getX e)
              my (.getY e)
              hit (find-handle-at mx my (:corners state) w h (:margin state))]
          (when hit
            (swap! !editor-state assoc :drag-handle hit)
            (.setCursor ^Canvas canvas Cursor/MOVE)))))))

(defn- handle-mouse-dragged
  "Handle mouse drag."
  [^MouseEvent e]
  (let [state @!editor-state
        canvas (:canvas state)
        drag-handle (:drag-handle state)]
    (when (and canvas drag-handle)
      (let [w (.getWidth ^Canvas canvas)
            h (.getHeight ^Canvas canvas)
            mx (.getX e)
            my (.getY e)
            [nx ny] (screen-to-normalized mx my w h (:margin state))
            final-x (if (:snap? state) (snap-to-grid nx (:snap-spacing state)) nx)
            final-y (if (:snap? state) (snap-to-grid ny (:snap-spacing state)) ny)]
        (swap! !editor-state assoc-in [:corners drag-handle] {:x final-x :y final-y})
        (let [gc (.getGraphicsContext2D ^Canvas canvas)
              new-state @!editor-state]
          (render-canvas gc w h (:margin new-state) (:corners new-state) 
                        (:hover-handle new-state) drag-handle))
        (when-let [on-change (:on-change state)]
          (on-change (:corners @!editor-state)))))))

(defn- handle-mouse-released
  "Handle mouse release."
  [^MouseEvent e]
  (let [state @!editor-state
        canvas (:canvas state)]
    (when canvas
      (swap! !editor-state assoc :drag-handle nil)
      (let [w (.getWidth ^Canvas canvas)
            h (.getHeight ^Canvas canvas)
            mx (.getX e)
            my (.getY e)
            hover (find-handle-at mx my (:corners state) w h (:margin state))]
        (.setCursor ^Canvas canvas 
                    (if hover Cursor/HAND Cursor/DEFAULT))
        (let [gc (.getGraphicsContext2D ^Canvas canvas)]
          (render-canvas gc w h (:margin state) (:corners state) hover nil))))))

;; ============================================================================
;; Public Component
;; ============================================================================

(defn corner-pin-canvas
  "Corner pin editor canvas component.
   Uses ext-instance-factory for Canvas lifecycle.
   
   Props:
   - :width - Canvas width
   - :height - Canvas height
   - :corners - Initial corners map
   - :on-change - Callback when corners change
   - :snap? - Enable grid snapping
   - :snap-spacing - Grid snap spacing"
  [{:keys [width height corners on-change snap? snap-spacing]
    :or {width 400 height 400 snap? false snap-spacing 0.25}}]
  {:fx/type fx/ext-instance-factory
   :create (fn []
             (let [canvas (Canvas. width height)
                   margin 30]
               ;; Initialize state
               (swap! !editor-state assoc
                      :corners (or corners default-corners)
                      :margin margin
                      :on-change on-change
                      :snap? snap?
                      :snap-spacing snap-spacing
                      :canvas canvas)
               
               ;; Set up event handlers
               (.setOnMouseMoved canvas handle-mouse-move)
               (.setOnMousePressed canvas handle-mouse-pressed)
               (.setOnMouseDragged canvas handle-mouse-dragged)
               (.setOnMouseReleased canvas handle-mouse-released)
               
               ;; Initial render
               (let [gc (.getGraphicsContext2D canvas)]
                 (render-canvas gc width height margin 
                               (or corners default-corners) nil nil))
               
               canvas))})

(defn corner-pin-editor
  "Complete corner pin editor with controls.
   
   Props:
   - :corners - Initial corners
   - :on-change - Callback when corners change
   - :width - Editor width
   - :height - Editor height"
  [{:keys [corners on-change width height]
    :or {width 400 height 400}}]
  {:fx/type :v-box
   :style (str "-fx-background-color: " (:surface styles/colors) ";"
              "-fx-padding: 8;")
   :spacing 8
   :children [{:fx/type :label
               :text "Corner Pin Editor"
               :style (str "-fx-text-fill: " (:text-primary styles/colors) ";"
                          "-fx-font-weight: bold;")}
              {:fx/type :stack-pane
               :style "-fx-border-color: #404040; -fx-border-width: 1;"
               :children [{:fx/type corner-pin-canvas
                           :width width
                           :height height
                           :corners corners
                           :on-change on-change}]}
              {:fx/type :h-box
               :spacing 8
               :alignment :center-left
               :children [{:fx/type :check-box
                           :text "Snap to grid"
                           :selected (:snap? @!editor-state)
                           :on-selected-changed (fn [selected]
                                                  (swap! !editor-state assoc :snap? selected))}
                          {:fx/type :button
                           :text "Reset"
                           :on-action (fn [_]
                                        (swap! !editor-state assoc :corners default-corners)
                                        (when-let [canvas (:canvas @!editor-state)]
                                          (let [gc (.getGraphicsContext2D canvas)
                                                s @!editor-state]
                                            (render-canvas gc width height (:margin s)
                                                          default-corners nil nil)))
                                        (when on-change
                                          (on-change default-corners)))}]}]})

;; ============================================================================
;; API
;; ============================================================================

(defn get-corners
  "Get current corner positions."
  []
  (:corners @!editor-state))

(defn set-corners!
  "Set corner positions."
  [corners]
  (swap! !editor-state assoc :corners corners)
  (when-let [canvas (:canvas @!editor-state)]
    (let [gc (.getGraphicsContext2D canvas)
          s @!editor-state]
      (render-canvas gc (.getWidth canvas) (.getHeight canvas)
                    (:margin s) corners nil nil))))
