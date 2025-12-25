(ns laser-show.ui-fx.views.preview
  "Laser preview panel component."
  (:require [cljfx.api :as fx]
            [laser-show.ui-fx.styles :as styles]
            [laser-show.ui-fx.subs :as subs]
            [laser-show.animation.presets :as presets]
            [laser-show.animation.types :as t]
            [laser-show.state.atoms :as state])
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
  "Check if a point is visible (not blanked)."
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
;; Animation State (global)
;; ============================================================================

(defonce ^:private !preview-state
  (atom {:animation nil
         :start-time 0
         :running? false
         :timer nil
         :canvas nil
         :current-preset-id nil}))

(defn- stop-preview-animation!
  "Stop the current preview animation."
  []
  (when-let [timer (:timer @!preview-state)]
    (.stop timer))
  (swap! !preview-state assoc :running? false :timer nil))

(defn- start-preview-animation!
  "Start animating a preview."
  [animation]
  (when-let [canvas (:canvas @!preview-state)]
    (stop-preview-animation!)
    (let [start-time (System/currentTimeMillis)
          last-time (atom 0)
          timer (proxy [AnimationTimer] []
                  (handle [now]
                    (let [elapsed (- now @last-time)]
                      ;; ~30fps
                      (when (> elapsed 33000000)
                        (reset! last-time now)
                        (let [time-ms (- (System/currentTimeMillis) start-time)
                              frame (t/get-frame animation time-ms)
                              gc (.getGraphicsContext2D canvas)
                              w (.getWidth canvas)
                              h (.getHeight canvas)]
                          (render-frame gc frame w h))))))]
      (swap! !preview-state assoc
             :animation animation
             :start-time start-time
             :running? true
             :timer timer)
      (.start timer))))

;; ============================================================================
;; Preview Update Logic
;; ============================================================================

(defn update-preview-for-preset!
  "Update the preview to show a specific preset."
  [preset-id]
  (let [current (:current-preset-id @!preview-state)]
    (when (not= preset-id current)
      (swap! !preview-state assoc :current-preset-id preset-id)
      (if preset-id
        (when-let [animation (presets/create-animation-from-preset preset-id)]
          (start-preview-animation! animation))
        (do
          (stop-preview-animation!)
          (when-let [canvas (:canvas @!preview-state)]
            (let [gc (.getGraphicsContext2D canvas)
                  w (.getWidth canvas)
                  h (.getHeight canvas)]
              (render-frame gc nil w h))))))))

(defn- check-and-update-preview!
  "Check current state and update preview if needed."
  []
  (let [active-cell (state/get-active-cell)
        playing? (state/playing?)]
    (if (and playing? active-cell)
      (let [[col row] active-cell
            cell (state/get-cell col row)
            preset-id (:preset-id cell)]
        (update-preview-for-preset! preset-id))
      ;; Also check selected preset in UI
      (let [selected-preset (state/get-selected-preset)]
        (update-preview-for-preset! selected-preset)))))

;; ============================================================================
;; State Watcher
;; ============================================================================

(defonce ^:private preview-watcher-installed? (atom false))

(defn- install-preview-watcher!
  "Install watchers on state atoms to update preview."
  []
  (when-not @preview-watcher-installed?
    (add-watch state/!playback :preview-update
               (fn [_ _ _ _] (check-and-update-preview!)))
    (add-watch state/!grid :preview-update
               (fn [_ _ _ _] (check-and-update-preview!)))
    (add-watch state/!ui :preview-update
               (fn [_ _ old new]
                 (when (not= (:selected-preset old) (:selected-preset new))
                   (check-and-update-preview!))))
    (reset! preview-watcher-installed? true)))

;; ============================================================================
;; Preview Canvas Component
;; ============================================================================

(defn preview-canvas
  "A canvas component for the preview panel.
   Uses ext-on-instance-lifecycle to manage the Canvas."
  [{:keys [width height]}]
  {:fx/type fx/ext-on-instance-lifecycle
   :on-created (fn [^Canvas canvas]
                 ;; Store canvas reference and install watchers
                 (swap! !preview-state assoc :canvas canvas)
                 (install-preview-watcher!)
                 ;; Initial render
                 (let [gc (.getGraphicsContext2D canvas)]
                   (render-frame gc nil width height))
                 ;; Check if we should start animation
                 (check-and-update-preview!))
   :on-deleted (fn [^Canvas _canvas]
                 ;; Stop animation when canvas is removed
                 (stop-preview-animation!)
                 (swap! !preview-state assoc :canvas nil))
   :desc {:fx/type :canvas
          :width width
          :height height}})

;; ============================================================================
;; Preview Panel Component
;; ============================================================================

(defn preview-panel
  "Laser preview panel.
   Displays the currently playing animation.
   
   Props:
   - :width - Panel width
   - :height - Panel height"
  [{:keys [width height]
    :or {width (:preview-width styles/dimensions)
         height (:preview-height styles/dimensions)}}]
  
  {:fx/type :stack-pane
   :style (str "-fx-background-color: " (:preview-background styles/colors) ";"
              "-fx-border-color: " (:border styles/colors) ";"
              "-fx-border-width: 1;")
   :style-class ["preview-panel"]
   :pref-width width
   :pref-height height
   :min-width width
   :min-height height
   :children [{:fx/type preview-canvas
               :width width
               :height height}]})

;; ============================================================================
;; Preview Panel with Title
;; ============================================================================

(defn preview-panel-titled
  "Preview panel with a title header.
   
   Props:
   - :title - Title text
   - :width - Panel width
   - :height - Panel height"
  [{:keys [title width height]
    :or {title "Preview"
         width (:preview-width styles/dimensions)
         height (:preview-height styles/dimensions)}}]
  
  {:fx/type :v-box
   :style (str "-fx-background-color: " (:surface styles/colors) ";")
   :children [{:fx/type :label
               :text title
               :style (str "-fx-text-fill: " (:text-secondary styles/colors) ";"
                          "-fx-font-size: 11px;"
                          "-fx-padding: 4 8;")}
              {:fx/type preview-panel
               :width width
               :height height}]})

;; ============================================================================
;; API for External Control
;; ============================================================================

(defn update-preview!
  "Update the preview to show a specific preset or animation.
   Call this when the active cell changes."
  [preset-id]
  (update-preview-for-preset! preset-id))

(defn render-static-frame!
  "Render a single static frame to the preview canvas."
  [frame]
  (stop-preview-animation!)
  (when-let [canvas (:canvas @!preview-state)]
    (let [gc (.getGraphicsContext2D canvas)
          w (.getWidth canvas)
          h (.getHeight canvas)]
      (render-frame gc frame w h))))

(defn refresh-preview!
  "Force refresh the preview based on current state."
  []
  (check-and-update-preview!))
