(ns laser-show.ui.components.corner-pin-editor
  "Visual editor for corner pin effect parameters.
   Provides an interactive canvas where users can drag corner handles
   to adjust the quadrilateral transformation."
  (:require [seesaw.core :as ss]
            [seesaw.graphics :as g])
  (:import [java.awt Color Font BasicStroke RenderingHints Cursor]
           [java.awt.geom Ellipse2D$Double Line2D$Double Rectangle2D$Double]
           [javax.swing JPanel]))

;; ============================================================================
;; Constants
;; ============================================================================

(def ^:private default-width 400)
(def ^:private default-height 400)
(def ^:private default-margin 30)
(def ^:private handle-radius 6)
(def ^:private handle-diameter (* 2 handle-radius))

;; Colors
(def ^:private grid-color (Color. 64 64 64))
(def ^:private origin-color (Color. 96 96 96))
(def ^:private handle-normal-color (Color. 100 181 246))
(def ^:private handle-hover-color (Color. 66 165 245))
(def ^:private handle-drag-color (Color. 0 229 255))
(def ^:private handle-border-color Color/WHITE)
(def ^:private box-color (Color. 100 181 246))
(def ^:private text-color Color/WHITE)
(def ^:private text-bg-color (Color. 0 0 0 180))

;; Default corner positions (unit square)
(def ^:private default-corners
  {:tl {:x -1.0 :y 1.0}
   :tr {:x 1.0 :y 1.0}
   :bl {:x -1.0 :y -1.0}
   :br {:x 1.0 :y -1.0}})

;; ============================================================================
;; Coordinate Mapping
;; ============================================================================

(defn- clamp
  "Clamp value between min and max."
  [v min-v max-v]
  (max min-v (min max-v v)))

(defn- normalized-to-screen
  "Convert normalized [-2, 2] coordinates to screen coordinates.
   
   Parameters:
   - norm-x, norm-y: Normalized coordinates in range [-2, 2]
   - width, height: Canvas dimensions
   - margin: Canvas margin in pixels
   
   Returns: [screen-x screen-y]"
  [norm-x norm-y width height margin]
  (let [effective-width (- width (* 2 margin))
        effective-height (- height (* 2 margin))
        ;; Map [-2, 2] to [0, effective-size]
        x-ratio (/ (+ norm-x 2.0) 4.0)
        y-ratio (/ (+ norm-y 2.0) 4.0)
        screen-x (+ margin (* x-ratio effective-width))
        ;; Y is inverted (screen Y grows downward, but normalized Y grows upward)
        screen-y (+ margin (- effective-height (* y-ratio effective-height)))]
    [screen-x screen-y]))

(defn- screen-to-normalized
  "Convert screen coordinates to normalized [-2, 2] coordinates.
   
   Parameters:
   - screen-x, screen-y: Screen pixel coordinates
   - width, height: Canvas dimensions
   - margin: Canvas margin in pixels
   
   Returns: [norm-x norm-y] clamped to [-2, 2]"
  [screen-x screen-y width height margin]
  (let [effective-width (- width (* 2 margin))
        effective-height (- height (* 2 margin))
        ;; Map screen coords to [0, 1] ratio
        x-ratio (/ (- screen-x margin) effective-width)
        y-ratio (/ (- screen-y margin) effective-height)
        ;; Map to [-2, 2], with Y inverted
        norm-x (- (* x-ratio 4.0) 2.0)
        norm-y (- 2.0 (* y-ratio 4.0))]
    [(clamp norm-x -2.0 2.0)
     (clamp norm-y -2.0 2.0)]))

(defn- snap-to-grid
  "Snap coordinate to nearest grid position.
   
   Parameters:
   - coord: The coordinate value to snap
   - grid-spacing: Grid spacing (e.g., 0.25 for quarter units)
   
   Returns: Snapped coordinate value"
  [coord grid-spacing]
  (let [snapped (* (Math/round (/ coord grid-spacing)) grid-spacing)]
    (clamp snapped -2.0 2.0)))

;; ============================================================================
;; Hit Detection
;; ============================================================================

(defn- point-distance
  "Calculate Euclidean distance between two points."
  [x1 y1 x2 y2]
  (Math/sqrt (+ (* (- x2 x1) (- x2 x1))
                (* (- y2 y1) (- y2 y1)))))

(defn- point-in-handle?
  "Check if screen point is within a handle's hit area.
   
   Parameters:
   - sx, sy: Screen coordinates to test
   - handle-screen-x, handle-screen-y: Handle center in screen coords
   - radius: Hit detection radius (slightly larger than visual radius)
   
   Returns: true if point is within handle"
  [sx sy handle-screen-x handle-screen-y radius]
  (<= (point-distance sx sy handle-screen-x handle-screen-y) radius))

(defn- find-handle-at-point
  "Find which corner handle (if any) is at the given screen point.
   
   Parameters:
   - sx, sy: Screen coordinates to test
   - corners: Map of corner positions {:tl {:x .. :y ..} ...}
   - width, height, margin: Canvas parameters
   
   Returns: Corner key (:tl, :tr, :bl, :br) or nil"
  [sx sy corners width height margin]
  (let [hit-radius (* handle-radius 1.5)] ; Slightly larger hit area
    (some (fn [[corner-key corner-pos]]
            (let [[hx hy] (normalized-to-screen (:x corner-pos) (:y corner-pos)
                                                 width height margin)]
              (when (point-in-handle? sx sy hx hy hit-radius)
                corner-key)))
          corners)))

;; ============================================================================
;; Canvas Rendering
;; ============================================================================

(defn- draw-grid
  "Draw coordinate grid on canvas."
  [^java.awt.Graphics2D g2d width height margin]
  (.setColor g2d grid-color)
  (.setStroke g2d (BasicStroke. 1.0))
  
  ;; Draw grid lines at integer values: -2, -1, 0, 1, 2
  (doseq [i (range -2 3)]
    ;; Vertical lines
    (let [[x1 y1] (normalized-to-screen (double i) -2.0 width height margin)
          [x2 y2] (normalized-to-screen (double i) 2.0 width height margin)]
      (.draw g2d (Line2D$Double. x1 y1 x2 y2)))
    ;; Horizontal lines
    (let [[x1 y1] (normalized-to-screen -2.0 (double i) width height margin)
          [x2 y2] (normalized-to-screen 2.0 (double i) width height margin)]
      (.draw g2d (Line2D$Double. x1 y1 x2 y2))))
  
  ;; Highlight origin (0, 0)
  (.setColor g2d origin-color)
  (.setStroke g2d (BasicStroke. 2.0))
  (let [[ox oy] (normalized-to-screen 0.0 0.0 width height margin)
        cross-size 10]
    ;; Vertical crosshair
    (.draw g2d (Line2D$Double. ox (- oy cross-size) ox (+ oy cross-size)))
    ;; Horizontal crosshair
    (.draw g2d (Line2D$Double. (- ox cross-size) oy (+ ox cross-size) oy))))

(defn- draw-bounding-box
  "Draw lines connecting the four corners."
  [^java.awt.Graphics2D g2d corners width height margin]
  (.setColor g2d box-color)
  (.setStroke g2d (BasicStroke. 2.0))
  
  (let [tl (:tl corners)
        tr (:tr corners)
        bl (:bl corners)
        br (:br corners)
        [tl-x tl-y] (normalized-to-screen (:x tl) (:y tl) width height margin)
        [tr-x tr-y] (normalized-to-screen (:x tr) (:y tr) width height margin)
        [bl-x bl-y] (normalized-to-screen (:x bl) (:y bl) width height margin)
        [br-x br-y] (normalized-to-screen (:x br) (:y br) width height margin)]
    ;; Draw quadrilateral: TL -> TR -> BR -> BL -> TL
    (.draw g2d (Line2D$Double. tl-x tl-y tr-x tr-y))
    (.draw g2d (Line2D$Double. tr-x tr-y br-x br-y))
    (.draw g2d (Line2D$Double. br-x br-y bl-x bl-y))
    (.draw g2d (Line2D$Double. bl-x bl-y tl-x tl-y))))

(defn- draw-corner-handle
  "Draw a single corner handle with label."
  [^java.awt.Graphics2D g2d corner-key corner-pos width height margin hover? dragging?]
  (let [[sx sy] (normalized-to-screen (:x corner-pos) (:y corner-pos) 
                                       width height margin)
        color (cond
                dragging? handle-drag-color
                hover? handle-hover-color
                :else handle-normal-color)
        label (case corner-key
                :tl "TL"
                :tr "TR"
                :bl "BL"
                :br "BR"
                "")]
    
    ;; Draw handle circle
    (.setColor g2d color)
    (.fill g2d (Ellipse2D$Double. (- sx handle-radius) (- sy handle-radius)
                                  handle-diameter handle-diameter))
    
    ;; Draw border
    (.setColor g2d handle-border-color)
    (.setStroke g2d (BasicStroke. 2.0))
    (.draw g2d (Ellipse2D$Double. (- sx handle-radius) (- sy handle-radius)
                                  handle-diameter handle-diameter))
    
    ;; Draw label
    (.setColor g2d text-color)
    (.setFont g2d (Font. "SansSerif" Font/BOLD 10))
    (let [fm (.getFontMetrics g2d)
          label-width (.stringWidth fm label)
          label-height (.getHeight fm)
          label-x (- sx (/ label-width 2))
          label-y (+ sy (/ label-height 4))]
      (.drawString g2d label (int label-x) (int label-y)))))

(defn- draw-coordinate-label
  "Draw coordinate label near a corner handle."
  [^java.awt.Graphics2D g2d corner-pos width height margin]
  (let [[sx sy] (normalized-to-screen (:x corner-pos) (:y corner-pos)
                                       width height margin)
        label (format "(%.2f, %.2f)" (:x corner-pos) (:y corner-pos))
        font (Font. "Monospaced" Font/PLAIN 10)
        _ (.setFont g2d font)
        fm (.getFontMetrics g2d)
        label-width (.stringWidth fm label)
        label-height (.getHeight fm)
        padding 4
        ;; Position label to the right and below the handle
        label-x (+ sx handle-radius 8)
        label-y (+ sy handle-radius 8)
        bg-x (- label-x padding)
        bg-y (- label-y label-height)
        bg-width (+ label-width (* 2 padding))
        bg-height (+ label-height (* 2 padding))]
    
    ;; Draw background
    (.setColor g2d text-bg-color)
    (.fill g2d (Rectangle2D$Double. bg-x bg-y bg-width bg-height))
    
    ;; Draw text
    (.setColor g2d text-color)
    (.drawString g2d label (int label-x) (int label-y))))

(defn- render-canvas
  "Main canvas rendering function."
  [^java.awt.Graphics2D g2d state width height]
  ;; Enable antialiasing
  (.setRenderingHint g2d RenderingHints/KEY_ANTIALIASING 
                     RenderingHints/VALUE_ANTIALIAS_ON)
  (.setRenderingHint g2d RenderingHints/KEY_TEXT_ANTIALIASING
                     RenderingHints/VALUE_TEXT_ANTIALIAS_ON)
  
  (let [{:keys [corners hover-corner drag-state margin]} state
        dragging-corner (when (:dragging? drag-state) (:corner drag-state))]
    
    ;; Clear background
    (.setColor g2d Color/BLACK)
    (.fillRect g2d 0 0 width height)
    
    ;; Draw grid
    (draw-grid g2d width height margin)
    
    ;; Draw bounding box
    (draw-bounding-box g2d corners width height margin)
    
    ;; Draw corner handles
    (doseq [[corner-key corner-pos] corners]
      (let [is-hover? (= corner-key hover-corner)
            is-dragging? (= corner-key dragging-corner)]
        (draw-corner-handle g2d corner-key corner-pos width height margin 
                           is-hover? is-dragging?)))
    
    ;; Draw coordinate labels
    (doseq [[_ corner-pos] corners]
      (draw-coordinate-label g2d corner-pos width height margin))))

;; ============================================================================
;; Component Creation
;; ============================================================================

(defn create-corner-pin-editor
  "Create a visual editor for corner pin parameters.
   
   Options:
   - :width, :height - Canvas dimensions (default 400x400)
   - :margin - Canvas margin in pixels (default 30)
   - :initial-corners - Initial corner positions map (default unit square)
   - :on-change - Callback (fn [corners-map]) called when corners move
   - :snap-to-grid - Enable grid snapping (default false)
   - :grid-spacing - Grid spacing in normalized units (default 0.25)
   
   Returns map with:
   - :panel - The Swing component
   - :set-corners! - (fn [corners-map]) Update corner positions
   - :get-corners - (fn []) Get current corner positions
   - :set-snap! - (fn [enabled?]) Enable/disable snapping"
  [& {:keys [width height margin initial-corners on-change snap-to-grid grid-spacing]
      :or {width default-width
           height default-height
           margin default-margin
           initial-corners default-corners
           snap-to-grid false
           grid-spacing 0.25}}]
  
  (let [!state (atom {:corners initial-corners
                      :hover-corner nil
                      :drag-state {:dragging? false
                                   :corner nil
                                   :start-pos nil}
                      :snap-to-grid? snap-to-grid
                      :grid-spacing grid-spacing
                      :margin margin
                      :on-change on-change})
        
        canvas (proxy [JPanel] []
                 (paintComponent [g]
                   (proxy-super paintComponent g)
                   (render-canvas g @!state (.getWidth this) (.getHeight this))))
        
        ;; Mouse motion listener for hover and drag
        mouse-motion-listener
        (reify java.awt.event.MouseMotionListener
          (mouseMoved [_ e]
            (let [mx (.getX e)
                  my (.getY e)
                  w (.getWidth canvas)
                  h (.getHeight canvas)
                  state @!state
                  hover-handle (find-handle-at-point mx my (:corners state) 
                                                    w h (:margin state))]
              (when (not= hover-handle (:hover-corner state))
                (swap! !state assoc :hover-corner hover-handle)
                (.repaint canvas))
              ;; Update cursor
              (.setCursor canvas 
                         (if hover-handle 
                           (Cursor/getPredefinedCursor Cursor/HAND_CURSOR)
                           (Cursor/getDefaultCursor)))))
          
          (mouseDragged [_ e]
            (when (:dragging? (:drag-state @!state))
              (let [mx (.getX e)
                    my (.getY e)
                    w (.getWidth canvas)
                    h (.getHeight canvas)
                    state @!state
                    corner-key (get-in state [:drag-state :corner])
                    [norm-x norm-y] (screen-to-normalized mx my w h (:margin state))
                    ;; Apply snapping if enabled
                    final-x (if (:snap-to-grid? state)
                             (snap-to-grid norm-x (:grid-spacing state))
                             norm-x)
                    final-y (if (:snap-to-grid? state)
                             (snap-to-grid norm-y (:grid-spacing state))
                             norm-y)]
                ;; Update corner position
                (swap! !state assoc-in [:corners corner-key] {:x final-x :y final-y})
                (.repaint canvas)
                ;; Trigger on-change callback
                (when-let [on-change-fn (:on-change state)]
                  (on-change-fn (:corners @!state)))))))
        
        ;; Mouse listener for press/release
        mouse-listener
        (reify java.awt.event.MouseListener
          (mousePressed [_ e]
            (let [mx (.getX e)
                  my (.getY e)
                  w (.getWidth canvas)
                  h (.getHeight canvas)
                  state @!state
                  hit-handle (find-handle-at-point mx my (:corners state)
                                                  w h (:margin state))]
              (when hit-handle
                (swap! !state assoc :drag-state {:dragging? true
                                                  :corner hit-handle
                                                  :start-pos [mx my]})
                (.setCursor canvas (Cursor/getPredefinedCursor Cursor/MOVE_CURSOR))
                (.repaint canvas))))
          
          (mouseReleased [_ e]
            (when (:dragging? (:drag-state @!state))
              (swap! !state assoc :drag-state {:dragging? false
                                                :corner nil
                                                :start-pos nil})
              ;; Restore cursor based on current position
              (let [mx (.getX e)
                    my (.getY e)
                    w (.getWidth canvas)
                    h (.getHeight canvas)
                    state @!state
                    hover-handle (find-handle-at-point mx my (:corners state)
                                                      w h (:margin state))]
                (.setCursor canvas 
                           (if hover-handle
                             (Cursor/getPredefinedCursor Cursor/HAND_CURSOR)
                             (Cursor/getDefaultCursor))))
              (.repaint canvas)))
          
          (mouseClicked [_ e])
          (mouseEntered [_ e])
          (mouseExited [_ e]
            (swap! !state assoc :hover-corner nil)
            (.repaint canvas)))]
    
    ;; Configure canvas
    (.setPreferredSize canvas (java.awt.Dimension. width height))
    (.setBackground canvas Color/BLACK)
    (.setOpaque canvas true)
    (.addMouseMotionListener canvas mouse-motion-listener)
    (.addMouseListener canvas mouse-listener)
    
    ;; Return component API
    {:panel canvas
     :set-corners! (fn [corners]
                     (swap! !state assoc :corners corners)
                     (.repaint canvas))
     :get-corners (fn []
                    (:corners @!state))
     :set-snap! (fn [enabled?]
                  (swap! !state assoc :snap-to-grid? enabled?))}))
