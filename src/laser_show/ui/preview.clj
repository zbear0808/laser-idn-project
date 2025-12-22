(ns laser-show.ui.preview
  "Animation preview panel - renders laser frames in a Swing canvas.
   
   IDN-Stream Compliance Notes:
   - LaserPoint uses X, Y coordinates (16-bit signed) and R, G, B colors (8-bit unsigned)
   - Blanking is indicated by r=g=b=0 per IDN-Stream spec Section 3.4.11
   - The preview renders points and lines between consecutive visible (non-blanked) points
   - Coordinate system: X positive = right, Y positive = up (matches IDN-Stream spec)
   
   Zone-Aware Preview:
   - Preview can show frames as they would appear after zone transformations
   - Zone viewport boundaries can be displayed as overlays
   - Blocked regions can be visualized"
  (:require [seesaw.core :as ss]
            [seesaw.graphics :as g]
            [seesaw.color :as color]
            [laser-show.animation.types :as t]
            [laser-show.ui.colors :as colors]
            [laser-show.ui.layout :as layout]
            [laser-show.backend.zones :as zones]
            [laser-show.backend.zone-transform :as transform])
  (:import [java.awt Graphics2D RenderingHints BasicStroke Color]
           [java.awt.geom Line2D$Double Ellipse2D$Double Rectangle2D$Double]
           [javax.swing Timer]))

;; ============================================================================
;; Preview Panel State
;; ============================================================================

(defonce ^:private !preview-state
  (atom {:animation nil
         :start-time 0
         :running false}))

;; ============================================================================
;; Frame Rendering
;; ============================================================================

(defn- point-visible?
  "Check if a point is visible (not blanked).
   Per IDN-Stream spec Section 3.4.11, blanking is indicated by r=g=b=0."
  [pt]
  (not (t/blanked? pt)))

(defn- render-frame
  "Render a laser frame to the graphics context.
   
   IDN-Stream Compliance:
   - Points with r=g=b=0 are blanked (invisible) per Section 3.4.11
   - Lines are drawn between consecutive visible points
   - Coordinate mapping: laser coords (-32768 to 32767) -> screen coords"
  [^Graphics2D g2d frame width height]
  (.setRenderingHint g2d RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
  (.setRenderingHint g2d RenderingHints/KEY_STROKE_CONTROL RenderingHints/VALUE_STROKE_PURE)
  
  (.setColor g2d colors/preview-background)
  (.fillRect g2d 0 0 width height)
  
  (.setColor g2d colors/preview-grid-lines)
  (.setStroke g2d (BasicStroke. 1.0))
  (let [cx (/ width 2)
        cy (/ height 2)]
    (.draw g2d (Line2D$Double. cx 0 cx height))
    (.draw g2d (Line2D$Double. 0 cy width cy)))
  
  (when frame
    (let [points (:points frame)
          point-count (count points)]
      (when (pos? point-count)
        (.setStroke g2d (BasicStroke. 2.0 BasicStroke/CAP_ROUND BasicStroke/JOIN_ROUND))
        
        (doseq [[p1 p2] (partition 2 1 points)]
          (when (and (point-visible? p1) (point-visible? p2))
            (let [x1 (layout/laser-to-screen (:x p1) width)
                  y1 (- height (layout/laser-to-screen (:y p1) height))
                  x2 (layout/laser-to-screen (:x p2) width)
                  y2 (- height (layout/laser-to-screen (:y p2) height))
                  r (bit-and (:r p2) 0xFF)
                  g (bit-and (:g p2) 0xFF)
                  b (bit-and (:b p2) 0xFF)]
              (.setColor g2d (Color. r g b))
              (.draw g2d (Line2D$Double. x1 y1 x2 y2)))))
        
        (doseq [pt points]
          (when (point-visible? pt)
            (let [x (layout/laser-to-screen (:x pt) width)
                  y (- height (layout/laser-to-screen (:y pt) height))
                  r (bit-and (:r pt) 0xFF)
                  g (bit-and (:g pt) 0xFF)
                  b (bit-and (:b pt) 0xFF)]
              (.setColor g2d (Color. r g b))
              (.fill g2d (Ellipse2D$Double. (- x 2) (- y 2) 4 4)))))))))

;; ============================================================================
;; Preview Panel Component
;; ============================================================================

(defn create-preview-panel
  "Create a preview panel for rendering laser animations.
   Returns a map with :panel (the Swing component) and control functions."
  [& {:keys [width height]
      :or {width layout/preview-default-width
           height layout/preview-default-height}}]
  (let [!state (atom {:animation nil
                      :start-time 0
                      :running false
                      :current-frame nil})
        
        canvas (ss/canvas
                :paint (fn [c g2d]
                         (let [w (.getWidth c)
                               h (.getHeight c)
                               frame (:current-frame @!state)]
                           (render-frame g2d frame w h)))
                :size [width :by height]
                :background :black)
        
        timer (Timer. 33
                      (reify java.awt.event.ActionListener
                        (actionPerformed [_ _]
                          (when (:running @!state)
                            (when-let [anim (:animation @!state)]
                              (let [elapsed (- (System/currentTimeMillis) (:start-time @!state))
                                    frame (t/get-frame anim elapsed)]
                                (swap! !state assoc :current-frame frame)
                                (ss/repaint! canvas)))))))
        
        set-animation! (fn [animation]
                         (swap! !state assoc
                                :animation animation
                                :start-time (System/currentTimeMillis)
                                :running true)
                         (when-not (.isRunning timer)
                           (.start timer)))
        
        stop! (fn []
                (swap! !state assoc :running false :animation nil :current-frame nil)
                (ss/repaint! canvas))
        
        set-frame! (fn [frame]
                     (swap! !state assoc :current-frame frame :running false)
                     (when (.isRunning timer)
                       (.stop timer))
                     (ss/repaint! canvas))]
    
    {:panel canvas
     :set-animation! set-animation!
     :set-frame! set-frame!
     :stop! stop!
     :get-state (fn [] @!state)}))

;; ============================================================================
;; Zone Overlay Rendering
;; ============================================================================

(defn- normalized-to-screen
  "Convert normalized coordinates [-1, 1] to screen coordinates."
  [norm-val screen-size]
  (* (/ (+ norm-val 1.0) 2.0) screen-size))

(defn- render-viewport-overlay
  "Render the viewport boundary as a dashed rectangle."
  [^Graphics2D g2d viewport width height]
  (when viewport
    (let [{:keys [x-min x-max y-min y-max]} viewport
          screen-x-min (normalized-to-screen x-min width)
          screen-x-max (normalized-to-screen x-max width)
          screen-y-min (- height (normalized-to-screen y-max height))
          screen-y-max (- height (normalized-to-screen y-min height))
          rect-width (- screen-x-max screen-x-min)
          rect-height (- screen-y-max screen-y-min)]
      (.setColor g2d (Color. 100 100 255 128))
      (.setStroke g2d (BasicStroke. 2.0 BasicStroke/CAP_BUTT BasicStroke/JOIN_MITER
                                    10.0 (float-array [5.0 5.0]) 0.0))
      (.draw g2d (Rectangle2D$Double. screen-x-min screen-y-min rect-width rect-height)))))

(defn- render-blocked-region
  "Render a single blocked region as a semi-transparent overlay."
  [^Graphics2D g2d region width height]
  (case (:type region)
    :rect
    (let [{:keys [x-min x-max y-min y-max]} region
          screen-x-min (normalized-to-screen x-min width)
          screen-x-max (normalized-to-screen x-max width)
          screen-y-min (- height (normalized-to-screen y-max height))
          screen-y-max (- height (normalized-to-screen y-min height))
          rect-width (- screen-x-max screen-x-min)
          rect-height (- screen-y-max screen-y-min)]
      (.setColor g2d (Color. 255 0 0 64))
      (.fill g2d (Rectangle2D$Double. screen-x-min screen-y-min rect-width rect-height))
      (.setColor g2d (Color. 255 0 0 128))
      (.setStroke g2d (BasicStroke. 1.0))
      (.draw g2d (Rectangle2D$Double. screen-x-min screen-y-min rect-width rect-height)))
    
    :circle
    (let [{:keys [center-x center-y radius]} region
          screen-cx (normalized-to-screen center-x width)
          screen-cy (- height (normalized-to-screen center-y height))
          screen-radius (* radius (/ width 2.0))
          diameter (* 2 screen-radius)]
      (.setColor g2d (Color. 255 0 0 64))
      (.fill g2d (Ellipse2D$Double. (- screen-cx screen-radius) 
                                    (- screen-cy screen-radius)
                                    diameter diameter))
      (.setColor g2d (Color. 255 0 0 128))
      (.setStroke g2d (BasicStroke. 1.0))
      (.draw g2d (Ellipse2D$Double. (- screen-cx screen-radius)
                                    (- screen-cy screen-radius)
                                    diameter diameter)))
    nil))

(defn- render-blocked-regions-overlay
  "Render all blocked regions as overlays."
  [^Graphics2D g2d blocked-regions width height]
  (doseq [region blocked-regions]
    (render-blocked-region g2d region width height)))

(defn- render-zone-overlays
  "Render zone visualization overlays (viewport and blocked regions)."
  [^Graphics2D g2d zone width height]
  (when zone
    (let [{:keys [transformations blocked-regions]} zone
          {:keys [viewport]} transformations]
      (render-viewport-overlay g2d viewport width height)
      (render-blocked-regions-overlay g2d blocked-regions width height))))

;; ============================================================================
;; Zone-Aware Frame Rendering
;; ============================================================================

(defn- render-frame-with-zone
  "Render a laser frame with zone transformations applied and overlays shown."
  [^Graphics2D g2d frame zone width height show-overlays?]
  (let [transformed-frame (if zone
                            (transform/transform-frame-for-zone frame zone)
                            frame)]
    (render-frame g2d transformed-frame width height)
    (when (and show-overlays? zone)
      (render-zone-overlays g2d zone width height))))

;; ============================================================================
;; Zone-Aware Preview Panel
;; ============================================================================

(defn create-zone-preview-panel
  "Create a preview panel that supports zone-aware rendering.
   
   Returns a map with:
   - :panel - The Swing component
   - :set-animation! - Set the animation to preview
   - :set-frame! - Set a static frame
   - :set-zone! - Set the zone for transformation preview
   - :set-show-overlays! - Toggle zone overlay visibility
   - :stop! - Stop animation
   - :get-state - Get current state"
  [& {:keys [width height zone-id show-overlays?]
      :or {width layout/preview-default-width
           height layout/preview-default-height
           zone-id nil
           show-overlays? true}}]
  (let [!state (atom {:animation nil
                      :start-time 0
                      :running false
                      :current-frame nil
                      :zone-id zone-id
                      :show-overlays? show-overlays?})
        
        get-zone (fn []
                   (when-let [zid (:zone-id @!state)]
                     (zones/get-zone zid)))
        
        canvas (ss/canvas
                :paint (fn [c g2d]
                         (let [w (.getWidth c)
                               h (.getHeight c)
                               frame (:current-frame @!state)
                               zone (get-zone)
                               show? (:show-overlays? @!state)]
                           (if zone
                             (render-frame-with-zone g2d frame zone w h show?)
                             (render-frame g2d frame w h))))
                :size [width :by height]
                :background :black)
        
        timer (Timer. 33
                      (reify java.awt.event.ActionListener
                        (actionPerformed [_ _]
                          (when (:running @!state)
                            (when-let [anim (:animation @!state)]
                              (let [elapsed (- (System/currentTimeMillis) (:start-time @!state))
                                    frame (t/get-frame anim elapsed)]
                                (swap! !state assoc :current-frame frame)
                                (ss/repaint! canvas)))))))
        
        set-animation! (fn [animation]
                         (swap! !state assoc
                                :animation animation
                                :start-time (System/currentTimeMillis)
                                :running true)
                         (when-not (.isRunning timer)
                           (.start timer)))
        
        stop! (fn []
                (swap! !state assoc :running false :animation nil :current-frame nil)
                (ss/repaint! canvas))
        
        set-frame! (fn [frame]
                     (swap! !state assoc :current-frame frame :running false)
                     (when (.isRunning timer)
                       (.stop timer))
                     (ss/repaint! canvas))
        
        set-zone! (fn [zone-id]
                    (swap! !state assoc :zone-id zone-id)
                    (ss/repaint! canvas))
        
        set-show-overlays! (fn [show?]
                             (swap! !state assoc :show-overlays? show?)
                             (ss/repaint! canvas))]
    
    {:panel canvas
     :set-animation! set-animation!
     :set-frame! set-frame!
     :set-zone! set-zone!
     :set-show-overlays! set-show-overlays!
     :stop! stop!
     :get-state (fn [] @!state)}))

;; ============================================================================
;; Mini Preview (for grid cells)
;; ============================================================================

(defn create-mini-preview
  "Create a small preview canvas for grid cells.
   Renders a static snapshot of an animation."
  [& {:keys [size]
      :or {size layout/mini-preview-size}}]
  (let [!frame (atom nil)
        canvas (ss/canvas
                :paint (fn [c g2d]
                         (let [w (.getWidth c)
                               h (.getHeight c)]
                           (render-frame g2d @!frame w h)))
                :size [size :by size]
                :background :black)]
    {:panel canvas
     :set-frame! (fn [frame]
                   (reset! !frame frame)
                   (ss/repaint! canvas))}))

;; ============================================================================
;; Zone Selector Component
;; ============================================================================

(defn create-zone-selector
  "Create a dropdown selector for choosing which zone to preview.
   
   Parameters:
   - on-zone-change: Callback function called with zone-id when selection changes
   
   Returns a map with:
   - :panel - The Swing component
   - :refresh! - Refresh the zone list
   - :get-selected - Get currently selected zone-id"
  [on-zone-change]
  (let [!zones (atom [])
        
        combo (ss/combobox :model []
                          :renderer (fn [renderer {:keys [value]}]
                                      (if value
                                        (ss/config! renderer :text (:name value))
                                        (ss/config! renderer :text "No Zone"))))
        
        refresh! (fn []
                   (let [zone-list (cons {:id nil :name "No Zone (Raw)"} 
                                         (zones/list-zones))]
                     (reset! !zones zone-list)
                     (ss/config! combo :model (vec zone-list))))
        
        get-selected (fn []
                       (when-let [selected (ss/selection combo)]
                         (:id selected)))]
    
    (ss/listen combo :selection
               (fn [_]
                 (when on-zone-change
                   (on-zone-change (get-selected)))))
    
    (refresh!)
    
    {:panel combo
     :refresh! refresh!
     :get-selected get-selected}))
