(ns laser-show.ui.components.translate-editor
  "Visual editor for translate effect position.
   Provides an interactive canvas where users can drag a handle
   to set translation offset, with bidirectional sync to sliders."
  (:require [seesaw.core :as ss])
  (:import [java.awt Color Font BasicStroke RenderingHints Cursor]
           [java.awt.geom Ellipse2D$Double Line2D$Double Rectangle2D$Double]
           [javax.swing JPanel]))

;; ============================================================================
;; Constants
;; ============================================================================

(def ^:private default-width 400)
(def ^:private default-height 400)
(def ^:private default-margin 30)
(def ^:private handle-radius 8)
(def ^:private handle-diameter (* 2 handle-radius))

;; Colors (consistent with corner-pin editor)
(def ^:private grid-color (Color. 64 64 64))
(def ^:private grid-major-color (Color. 80 80 80))
(def ^:private origin-color (Color. 128 128 128))
(def ^:private handle-normal-color (Color. 100 181 246))
(def ^:private handle-hover-color (Color. 66 165 245))
(def ^:private handle-drag-color (Color. 0 229 255))
(def ^:private handle-modulated-color (Color. 128 128 128))
(def ^:private handle-border-color Color/WHITE)
(def ^:private vector-color (Color. 100 181 246 180))
(def ^:private text-color Color/WHITE)
(def ^:private text-bg-color (Color. 0 0 0 180))
(def ^:private modulated-text-color (Color. 255 200 100))

;; ============================================================================
;; Coordinate Mapping (same as corner-pin)
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

;; ============================================================================
;; Hit Detection
;; ============================================================================

(defn- point-distance
  "Calculate Euclidean distance between two points."
  [x1 y1 x2 y2]
  (Math/sqrt (+ (* (- x2 x1) (- x2 x1))
                (* (- y2 y1) (- y2 y1)))))

(defn- point-in-handle?
  "Check if screen point is within the handle's hit area."
  [sx sy handle-screen-x handle-screen-y radius]
  (<= (point-distance sx sy handle-screen-x handle-screen-y) radius))

;; ============================================================================
;; Canvas Rendering
;; ============================================================================

(defn- draw-grid
  "Draw coordinate grid on canvas."
  [^java.awt.Graphics2D g2d width height margin]
  ;; Draw minor grid lines (at 0.5 intervals)
  (.setColor g2d grid-color)
  (.setStroke g2d (BasicStroke. 1.0))
  (doseq [i (range -4 5)]
    (let [coord (/ i 2.0)]
      ;; Vertical lines
      (let [[x1 y1] (normalized-to-screen coord -2.0 width height margin)
            [x2 y2] (normalized-to-screen coord 2.0 width height margin)]
        (.draw g2d (Line2D$Double. x1 y1 x2 y2)))
      ;; Horizontal lines
      (let [[x1 y1] (normalized-to-screen -2.0 coord width height margin)
            [x2 y2] (normalized-to-screen 2.0 coord width height margin)]
        (.draw g2d (Line2D$Double. x1 y1 x2 y2)))))

  ;; Draw major grid lines (at integer values: -2, -1, 0, 1, 2)
  (.setColor g2d grid-major-color)
  (.setStroke g2d (BasicStroke. 1.5))
  (doseq [i (range -2 3)]
    ;; Vertical lines
    (let [[x1 y1] (normalized-to-screen (double i) -2.0 width height margin)
          [x2 y2] (normalized-to-screen (double i) 2.0 width height margin)]
      (.draw g2d (Line2D$Double. x1 y1 x2 y2)))
    ;; Horizontal lines
    (let [[x1 y1] (normalized-to-screen -2.0 (double i) width height margin)
          [x2 y2] (normalized-to-screen 2.0 (double i) width height margin)]
      (.draw g2d (Line2D$Double. x1 y1 x2 y2)))))

(defn- draw-origin
  "Draw origin marker (crosshair at 0,0)."
  [^java.awt.Graphics2D g2d width height margin]
  (.setColor g2d origin-color)
  (.setStroke g2d (BasicStroke. 2.0))
  (let [[ox oy] (normalized-to-screen 0.0 0.0 width height margin)
        cross-size 12]
    ;; Vertical crosshair
    (.draw g2d (Line2D$Double. ox (- oy cross-size) ox (+ oy cross-size)))
    ;; Horizontal crosshair
    (.draw g2d (Line2D$Double. (- ox cross-size) oy (+ ox cross-size) oy))
    ;; Center dot
    (.fill g2d (Ellipse2D$Double. (- ox 3) (- oy 3) 6 6))))

(defn- draw-vector-arrow
  "Draw vector arrow from origin to handle position."
  [^java.awt.Graphics2D g2d x y width height margin x-mod? y-mod?]
  (when-not (and (zero? x) (zero? y))
    (let [[ox oy] (normalized-to-screen 0.0 0.0 width height margin)
          [hx hy] (normalized-to-screen x y width height margin)
          color (cond
                  (and x-mod? y-mod?) handle-modulated-color
                  :else vector-color)]
      (.setColor g2d color)
      (.setStroke g2d (BasicStroke. 2.0 BasicStroke/CAP_ROUND BasicStroke/JOIN_ROUND
                                    0.0 (float-array [6.0 4.0]) 0.0))
      (.draw g2d (Line2D$Double. ox oy hx hy)))))

(defn- draw-handle
  "Draw the translation handle."
  [^java.awt.Graphics2D g2d x y width height margin hover? dragging? x-mod? y-mod?]
  (let [[sx sy] (normalized-to-screen x y width height margin)
        fully-modulated? (and x-mod? y-mod?)
        color (cond
                fully-modulated? handle-modulated-color
                dragging? handle-drag-color
                hover? handle-hover-color
                :else handle-normal-color)]

    ;; Draw handle circle
    (.setColor g2d color)
    (.fill g2d (Ellipse2D$Double. (- sx handle-radius) (- sy handle-radius)
                                  handle-diameter handle-diameter))

    ;; Draw border
    (.setColor g2d handle-border-color)
    (.setStroke g2d (BasicStroke. 2.0))
    (.draw g2d (Ellipse2D$Double. (- sx handle-radius) (- sy handle-radius)
                                  handle-diameter handle-diameter))

    ;; Draw lock icon if fully modulated
    (when fully-modulated?
      (.setColor g2d text-color)
      (.setFont g2d (Font. "SansSerif" Font/BOLD 10))
      (.drawString g2d "🔒" (int (- sx 6)) (int (+ sy 4))))))

(defn- draw-coordinate-label
  "Draw coordinate label near the handle."
  [^java.awt.Graphics2D g2d x y width height margin]
  (let [[sx sy] (normalized-to-screen x y width height margin)
        label (format "(%.2f, %.2f)" x y)
        font (Font. "Monospaced" Font/PLAIN 11)
        _ (.setFont g2d font)
        fm (.getFontMetrics g2d)
        label-width (.stringWidth fm label)
        label-height (.getHeight fm)
        padding 4
        ;; Position label to the right and below the handle
        label-x (+ sx handle-radius 10)
        label-y (+ sy handle-radius 10)
        bg-x (- label-x padding)
        bg-y (- label-y label-height)
        bg-width (+ label-width (* 2 padding))
        bg-height (+ label-height (* 2 padding))
        ;; Clamp label position to stay within canvas
        label-x (min label-x (- width margin label-width padding))
        label-y (min label-y (- height margin))]

    ;; Draw background
    (.setColor g2d text-bg-color)
    (.fill g2d (Rectangle2D$Double. (- label-x padding) (- label-y label-height)
                                    bg-width bg-height))
    ;; Draw text
    (.setColor g2d text-color)
    (.drawString g2d label (int label-x) (int label-y))))

(defn- draw-modulation-indicators
  "Draw text indicators for modulated axes."
  [^java.awt.Graphics2D g2d width height margin x-mod? y-mod?]
  (.setFont g2d (Font. "SansSerif" Font/BOLD 12))
  (.setColor g2d modulated-text-color)

  (cond
    (and x-mod? y-mod?)
    (let [text "FULLY MODULATED"
          fm (.getFontMetrics g2d)
          text-width (.stringWidth fm text)
          x (/ (- width text-width) 2)
          y (+ margin 20)]
      (.drawString g2d text (int x) (int y)))

    x-mod?
    (let [text "X: MODULATED"
          y (+ margin 20)]
      (.drawString g2d text (int (+ margin 5)) (int y)))

    y-mod?
    (let [text "Y: MODULATED"
          y (+ margin 20)]
      (.drawString g2d text (int (+ margin 5)) (int y)))))

(defn- render-canvas
  "Main canvas rendering function."
  [^java.awt.Graphics2D g2d state width height]
  ;; Enable antialiasing
  (.setRenderingHint g2d RenderingHints/KEY_ANTIALIASING
                     RenderingHints/VALUE_ANTIALIAS_ON)
  (.setRenderingHint g2d RenderingHints/KEY_TEXT_ANTIALIASING
                     RenderingHints/VALUE_TEXT_ANTIALIAS_ON)

  (let [{:keys [x y hover? dragging? x-modulated? y-modulated? margin]} state]

    ;; Clear background
    (.setColor g2d Color/BLACK)
    (.fillRect g2d 0 0 width height)

    ;; Draw grid
    (draw-grid g2d width height margin)

    ;; Draw origin
    (draw-origin g2d width height margin)

    ;; Draw vector arrow from origin to handle
    (draw-vector-arrow g2d x y width height margin x-modulated? y-modulated?)

    ;; Draw handle
    (draw-handle g2d x y width height margin hover? dragging?
                 x-modulated? y-modulated?)

    ;; Draw coordinate label
    (draw-coordinate-label g2d x y width height margin)

    ;; Draw modulation indicators
    (when (or x-modulated? y-modulated?)
      (draw-modulation-indicators g2d width height margin
                                  x-modulated? y-modulated?))))

;; ============================================================================
;; Component Creation
;; ============================================================================

(defn create-translate-editor
  "Create a visual editor for translate effect position.
   
   Options:
   - :width, :height - Canvas dimensions (default 400x400)
   - :margin - Canvas margin in pixels (default 30)
   - :initial-x, :initial-y - Initial position (default 0, 0)
   - :on-change - Callback (fn [x y]) called when position changes via drag
   
   Returns map with:
   - :panel - The Swing component
   - :set-x! - (fn [x]) Update X position externally
   - :set-y! - (fn [y]) Update Y position externally
   - :set-position! - (fn [x y]) Update both positions
   - :get-position - (fn []) Get current {:x :y}
   - :set-x-modulated! - (fn [bool]) Set X axis modulation state
   - :set-y-modulated! - (fn [bool]) Set Y axis modulation state"
  [& {:keys [width height margin initial-x initial-y on-change]
      :or {width default-width
           height default-height
           margin default-margin
           initial-x 0.0
           initial-y 0.0}}]

  (let [!state (atom {:x (double initial-x)
                      :y (double initial-y)
                      :hover? false
                      :dragging? false
                      :x-modulated? false
                      :y-modulated? false
                      :margin margin
                      :on-change on-change})

        canvas (proxy [JPanel] []
                 (paintComponent [g]
                   (proxy-super paintComponent g)
                   (render-canvas g @!state (.getWidth this) (.getHeight this))))

        ;; Check if handle is under cursor
        handle-hit? (fn [mx my]
                      (let [state @!state
                            w (.getWidth canvas)
                            h (.getHeight canvas)
                            [hx hy] (normalized-to-screen (:x state) (:y state)
                                                          w h (:margin state))]
                        (point-in-handle? mx my hx hy (* handle-radius 1.5))))

        ;; Mouse motion listener
        mouse-motion-listener
        (reify java.awt.event.MouseMotionListener
          (mouseMoved [_ e]
            (let [mx (.getX e)
                  my (.getY e)
                  is-hover? (handle-hit? mx my)
                  state @!state]
              (when (not= is-hover? (:hover? state))
                (swap! !state assoc :hover? is-hover?)
                (.repaint canvas))
              ;; Update cursor (only if not dragging and can drag)
              (let [fully-mod? (and (:x-modulated? state) (:y-modulated? state))]
                (.setCursor canvas
                            (cond
                              fully-mod? (Cursor/getDefaultCursor)
                              is-hover? (Cursor/getPredefinedCursor Cursor/HAND_CURSOR)
                              :else (Cursor/getDefaultCursor))))))

          (mouseDragged [_ e]
            (when (:dragging? @!state)
              (let [mx (.getX e)
                    my (.getY e)
                    w (.getWidth canvas)
                    h (.getHeight canvas)
                    state @!state
                    x-mod? (:x-modulated? state)
                    y-mod? (:y-modulated? state)
                    [new-x new-y] (screen-to-normalized mx my w h (:margin state))
                    ;; Constrain to non-modulated axes
                    final-x (if x-mod? (:x state) new-x)
                    final-y (if y-mod? (:y state) new-y)]
                ;; Update position
                (swap! !state assoc :x final-x :y final-y)
                (.repaint canvas)
                ;; Trigger on-change callback
                (when-let [on-change-fn (:on-change state)]
                  (on-change-fn final-x final-y))))))

        ;; Mouse listener
        mouse-listener
        (reify java.awt.event.MouseListener
          (mousePressed [_ e]
            (let [mx (.getX e)
                  my (.getY e)
                  state @!state
                  x-mod? (:x-modulated? state)
                  y-mod? (:y-modulated? state)
                  fully-mod? (and x-mod? y-mod?)]
              ;; Only start drag if not fully modulated and hit the handle
              (when (and (not fully-mod?) (handle-hit? mx my))
                (swap! !state assoc :dragging? true)
                (.setCursor canvas (Cursor/getPredefinedCursor Cursor/MOVE_CURSOR))
                (.repaint canvas))))

          (mouseReleased [_ e]
            (when (:dragging? @!state)
              (swap! !state assoc :dragging? false)
              ;; Restore cursor
              (let [mx (.getX e)
                    my (.getY e)
                    is-hover? (handle-hit? mx my)]
                (.setCursor canvas
                            (if is-hover?
                              (Cursor/getPredefinedCursor Cursor/HAND_CURSOR)
                              (Cursor/getDefaultCursor))))
              (.repaint canvas)))

          (mouseClicked [_ e])
          (mouseEntered [_ e])
          (mouseExited [_ e]
            (swap! !state assoc :hover? false)
            (.repaint canvas)))]

    ;; Configure canvas
    (.setPreferredSize canvas (java.awt.Dimension. width height))
    (.setBackground canvas Color/BLACK)
    (.setOpaque canvas true)
    (.addMouseMotionListener canvas mouse-motion-listener)
    (.addMouseListener canvas mouse-listener)

    ;; Return component API
    {:panel canvas

     :set-x! (fn [x]
               (swap! !state assoc :x (double x))
               (.repaint canvas))

     :set-y! (fn [y]
               (swap! !state assoc :y (double y))
               (.repaint canvas))

     :set-position! (fn [x y]
                      (swap! !state assoc :x (double x) :y (double y))
                      (.repaint canvas))

     :get-position (fn []
                     (let [state @!state]
                       {:x (:x state) :y (:y state)}))

     :set-x-modulated! (fn [modulated?]
                         (swap! !state assoc :x-modulated? (boolean modulated?))
                         (.repaint canvas))

     :set-y-modulated! (fn [modulated?]
                         (swap! !state assoc :y-modulated? (boolean modulated?))
                         (.repaint canvas))}))
