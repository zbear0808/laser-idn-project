(ns laser-show.ui.preview
  "Animation preview panel - renders laser frames in a Swing canvas."
  (:require [seesaw.core :as ss]
            [seesaw.graphics :as g]
            [seesaw.color :as color]
            [laser-show.animation.types :as t])
  (:import [java.awt Graphics2D RenderingHints BasicStroke Color]
           [java.awt.geom Line2D$Double Ellipse2D$Double]
           [javax.swing Timer]))

;; ============================================================================
;; Preview Panel State
;; ============================================================================

(defonce ^:private preview-state (atom {:animation nil
                                        :start-time 0
                                        :running false}))

;; ============================================================================
;; Coordinate Mapping
;; ============================================================================

(defn- laser-to-screen
  "Convert laser coordinates (-32768 to 32767) to screen coordinates."
  [laser-coord screen-size]
  (let [normalized (/ laser-coord 32767.0)]  ; -1 to 1
    (int (* (+ normalized 1) (/ screen-size 2)))))

(defn- normalized-to-screen
  "Convert normalized coordinates (-1 to 1) to screen coordinates."
  [normalized screen-size]
  (int (* (+ normalized 1) (/ screen-size 2))))

;; ============================================================================
;; Frame Rendering
;; ============================================================================

(defn- render-frame
  "Render a laser frame to the graphics context."
  [^Graphics2D g2d frame width height]
  ;; Set up rendering hints for smooth drawing
  (.setRenderingHint g2d RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
  (.setRenderingHint g2d RenderingHints/KEY_STROKE_CONTROL RenderingHints/VALUE_STROKE_PURE)
  
  ;; Draw black background
  (.setColor g2d Color/BLACK)
  (.fillRect g2d 0 0 width height)
  
  ;; Draw coordinate grid (subtle)
  (.setColor g2d (Color. 30 30 30))
  (.setStroke g2d (BasicStroke. 1.0))
  (let [cx (/ width 2)
        cy (/ height 2)]
    (.draw g2d (Line2D$Double. cx 0 cx height))
    (.draw g2d (Line2D$Double. 0 cy width cy)))
  
  (when frame
    (let [points (:points frame)
          point-count (count points)]
      (when (pos? point-count)
        ;; Draw lines between consecutive non-blanked points
        (.setStroke g2d (BasicStroke. 2.0 BasicStroke/CAP_ROUND BasicStroke/JOIN_ROUND))
        
        (doseq [[p1 p2] (partition 2 1 points)]
          (let [x1 (laser-to-screen (:x p1) width)
                y1 (- height (laser-to-screen (:y p1) height))  ; Flip Y
                x2 (laser-to-screen (:x p2) width)
                y2 (- height (laser-to-screen (:y p2) height))
                i1 (bit-and (:intensity p1) 0xFF)
                i2 (bit-and (:intensity p2) 0xFF)]
            ;; Only draw if both points are not blanked
            (when (and (pos? i1) (pos? i2))
              (let [r (bit-and (:r p2) 0xFF)
                    g (bit-and (:g p2) 0xFF)
                    b (bit-and (:b p2) 0xFF)]
                (.setColor g2d (Color. r g b))
                (.draw g2d (Line2D$Double. x1 y1 x2 y2))))))
        
        ;; Draw points (small circles)
        (doseq [pt points]
          (let [x (laser-to-screen (:x pt) width)
                y (- height (laser-to-screen (:y pt) height))
                intensity (bit-and (:intensity pt) 0xFF)]
            (when (pos? intensity)
              (let [r (bit-and (:r pt) 0xFF)
                    g (bit-and (:g pt) 0xFF)
                    b (bit-and (:b pt) 0xFF)]
                (.setColor g2d (Color. r g b))
                (.fill g2d (Ellipse2D$Double. (- x 2) (- y 2) 4 4))))))))))

;; ============================================================================
;; Preview Panel Component
;; ============================================================================

(defn create-preview-panel
  "Create a preview panel for rendering laser animations.
   Returns a map with :panel (the Swing component) and control functions."
  [& {:keys [width height]
      :or {width 400 height 400}}]
  (let [state (atom {:animation nil
                     :start-time 0
                     :running false
                     :current-frame nil})
        
        canvas (ss/canvas
                :paint (fn [c g2d]
                         (let [w (.getWidth c)
                               h (.getHeight c)
                               frame (:current-frame @state)]
                           (render-frame g2d frame w h)))
                :size [width :by height]
                :background :black)
        
        timer (Timer. 33  ; ~30 fps
                      (reify java.awt.event.ActionListener
                        (actionPerformed [_ _]
                          (when (:running @state)
                            (when-let [anim (:animation @state)]
                              (let [elapsed (- (System/currentTimeMillis) (:start-time @state))
                                    frame (t/get-frame anim elapsed)]
                                (swap! state assoc :current-frame frame)
                                (ss/repaint! canvas)))))))
        
        set-animation! (fn [animation]
                         (swap! state assoc
                                :animation animation
                                :start-time (System/currentTimeMillis)
                                :running true)
                         (when-not (.isRunning timer)
                           (.start timer)))
        
        stop! (fn []
                (swap! state assoc :running false :animation nil :current-frame nil)
                (ss/repaint! canvas))
        
        set-frame! (fn [frame]
                     (swap! state assoc :current-frame frame :running false)
                     (when (.isRunning timer)
                       (.stop timer))
                     (ss/repaint! canvas))]
    
    {:panel canvas
     :set-animation! set-animation!
     :set-frame! set-frame!
     :stop! stop!
     :get-state (fn [] @state)}))

;; ============================================================================
;; Mini Preview (for grid cells)
;; ============================================================================

(defn create-mini-preview
  "Create a small preview canvas for grid cells.
   Renders a static snapshot of an animation."
  [& {:keys [size]
      :or {size 60}}]
  (let [frame-atom (atom nil)
        canvas (ss/canvas
                :paint (fn [c g2d]
                         (let [w (.getWidth c)
                               h (.getHeight c)]
                           (render-frame g2d @frame-atom w h)))
                :size [size :by size]
                :background :black)]
    {:panel canvas
     :set-frame! (fn [frame]
                   (reset! frame-atom frame)
                   (ss/repaint! canvas))}))
