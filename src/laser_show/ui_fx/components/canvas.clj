(ns laser-show.ui-fx.components.canvas
  "JavaFX Canvas component for rendering laser frames.
   Uses cljfx lifecycle to manage a Canvas node with custom drawing."
  (:require [cljfx.api :as fx]
            [cljfx.lifecycle :as lifecycle]
            [cljfx.mutator :as mutator]
            [cljfx.prop :as prop]
            [laser-show.animation.types :as t]
            [laser-show.ui-fx.styles :as styles])
  (:import [javafx.scene.canvas Canvas GraphicsContext]
           [javafx.scene.paint Color]
           [javafx.animation AnimationTimer]))

;; ============================================================================
;; Coordinate Conversion
;; ============================================================================

(def ^:private laser-coord-max 32767.0)

(defn- laser-to-screen
  "Convert laser coordinate (-32768 to 32767) to screen coordinate (0 to size)."
  [laser-coord screen-size]
  (* (+ (/ laser-coord laser-coord-max) 1.0) (/ screen-size 2.0)))

;; ============================================================================
;; Frame Rendering
;; ============================================================================

(defn- point-visible?
  "Check if a point is visible (not blanked).
   Per IDN-Stream spec, blanking is indicated by r=g=b=0."
  [pt]
  (not (t/blanked? pt)))

(defn- render-frame
  "Render a laser frame to the canvas graphics context."
  [^GraphicsContext gc frame width height]
  ;; Clear with background
  (.setFill gc (Color/web (:preview-background styles/colors)))
  (.fillRect gc 0 0 width height)
  
  ;; Draw crosshairs
  (.setStroke gc (Color/web (:preview-grid styles/colors)))
  (.setLineWidth gc 1.0)
  (let [cx (/ width 2)
        cy (/ height 2)]
    (.strokeLine gc cx 0 cx height)
    (.strokeLine gc 0 cy width cy))
  
  ;; Draw frame
  (when frame
    (let [points (:points frame)]
      (when (seq points)
        (.setLineWidth gc 2.0)
        
        ;; Draw lines between consecutive visible points
        (doseq [[p1 p2] (partition 2 1 points)]
          (when (and (point-visible? p1) (point-visible? p2))
            (let [x1 (laser-to-screen (:x p1) width)
                  y1 (- height (laser-to-screen (:y p1) height))
                  x2 (laser-to-screen (:x p2) width)
                  y2 (- height (laser-to-screen (:y p2) height))
                  r (bit-and (:r p2) 0xFF)
                  g (bit-and (:g p2) 0xFF)
                  b (bit-and (:b p2) 0xFF)]
              (.setStroke gc (Color/rgb r g b))
              (.strokeLine gc x1 y1 x2 y2))))
        
        ;; Draw points
        (doseq [pt points]
          (when (point-visible? pt)
            (let [x (laser-to-screen (:x pt) width)
                  y (- height (laser-to-screen (:y pt) height))
                  r (bit-and (:r pt) 0xFF)
                  g (bit-and (:g pt) 0xFF)
                  b (bit-and (:b pt) 0xFF)]
              (.setFill gc (Color/rgb r g b))
              (.fillOval gc (- x 2) (- y 2) 4 4))))))))

;; ============================================================================
;; Animation Timer Management
;; ============================================================================

(defonce ^:private !timers (atom {}))

(defn- create-animation-timer
  "Create an AnimationTimer that renders frames at ~30fps."
  [canvas-id ^Canvas canvas get-frame-fn]
  (let [last-time (atom 0)
        timer (proxy [AnimationTimer] []
                (handle [now]
                  (let [elapsed (- now @last-time)]
                    ;; Limit to ~30fps (33ms = 33,000,000 ns)
                    (when (> elapsed 33000000)
                      (reset! last-time now)
                      (let [gc (.getGraphicsContext2D canvas)
                            w (.getWidth canvas)
                            h (.getHeight canvas)
                            frame (get-frame-fn)]
                        (render-frame gc frame w h))))))]
    (swap! !timers assoc canvas-id timer)
    timer))

(defn- stop-animation-timer
  "Stop and remove an animation timer."
  [canvas-id]
  (when-let [timer (get @!timers canvas-id)]
    (.stop timer)
    (swap! !timers dissoc canvas-id)))

;; ============================================================================
;; cljfx Lifecycle for Laser Canvas
;; ============================================================================

(def laser-canvas-props
  "Property definitions for the laser canvas."
  (merge
   fx/region-props
   (fx/make-ext-with-props
    {:width (prop/make
             (mutator/setter #(.setWidth ^Canvas %1 %2))
             lifecycle/scalar)
     :height (prop/make
              (mutator/setter #(.setHeight ^Canvas %1 %2))
              lifecycle/scalar)})))

(defn- create-laser-canvas
  "Create a Canvas node for laser rendering."
  [{:keys [width height]}]
  (let [canvas (Canvas. (or width 350) (or height 350))]
    ;; Initial clear
    (let [gc (.getGraphicsContext2D canvas)]
      (render-frame gc nil (or width 350) (or height 350)))
    canvas))

(def laser-canvas-lifecycle
  "Lifecycle for managing a laser preview canvas."
  (lifecycle/annotate
   (reify lifecycle/Lifecycle
     (create [_ {:keys [width height id] :as desc} opts]
       (let [canvas (create-laser-canvas desc)]
         {:node canvas
          :id (or id (gensym "canvas-"))}))
     
     (advance [_ component {:keys [width height frame get-frame-fn] :as desc} opts]
       (let [{:keys [node id]} component
             ^Canvas canvas node]
         ;; Update size if changed
         (when width (.setWidth canvas width))
         (when height (.setHeight canvas height))
         
         ;; Handle static frame vs animated
         (cond
           ;; Static frame provided
           frame
           (do
             (stop-animation-timer id)
             (let [gc (.getGraphicsContext2D canvas)
                   w (.getWidth canvas)
                   h (.getHeight canvas)]
               (render-frame gc frame w h)))
           
           ;; Animated with get-frame-fn
           get-frame-fn
           (when-not (get @!timers id)
             (let [timer (create-animation-timer id canvas get-frame-fn)]
               (.start timer)))
           
           ;; No frame - clear
           :else
           (do
             (stop-animation-timer id)
             (let [gc (.getGraphicsContext2D canvas)
                   w (.getWidth canvas)
                   h (.getHeight canvas)]
               (render-frame gc nil w h))))
         
         component))
     
     (delete [_ {:keys [node id]} opts]
       (stop-animation-timer id)))
   :laser-canvas))

;; ============================================================================
;; cljfx Extension Component
;; ============================================================================

(defn laser-canvas
  "cljfx component for rendering laser frames.
   
   Props:
   - :width - Canvas width in pixels (default 350)
   - :height - Canvas height in pixels (default 350)
   - :frame - Static frame to render (optional)
   - :get-frame-fn - Function that returns current frame for animation (optional)
   - :id - Unique ID for timer management (optional)
   
   If :get-frame-fn is provided, the canvas will animate at ~30fps.
   If :frame is provided, it renders that static frame.
   If neither is provided, shows empty preview."
  [{:keys [desc]}]
  {:fx/type fx/ext-instance-factory
   :create (fn []
             (let [canvas (create-laser-canvas desc)]
               canvas))})

;; ============================================================================
;; Utility Functions
;; ============================================================================

(defn render-static-frame
  "Render a static frame to a canvas (for thumbnails etc.)."
  [^Canvas canvas frame]
  (let [gc (.getGraphicsContext2D canvas)
        w (.getWidth canvas)
        h (.getHeight canvas)]
    (render-frame gc frame w h)))

(defn clear-canvas
  "Clear a canvas to the background color."
  [^Canvas canvas]
  (let [gc (.getGraphicsContext2D canvas)
        w (.getWidth canvas)
        h (.getHeight canvas)]
    (render-frame gc nil w h)))
