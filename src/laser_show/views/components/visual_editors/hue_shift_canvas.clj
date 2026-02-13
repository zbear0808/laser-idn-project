(ns laser-show.views.components.visual-editors.hue-shift-canvas
  "Two-strip hue transformation canvas for hue shift visualization.
   
   Features:
   - Top strip: Static input hue gradient (0° to 360°)
   - Bottom strip: Shifted output hue gradient
   - Drag left/right to adjust shift amount
   - Keyboard arrow keys for fine adjustment (±1° or ±10° with Shift)
   - Supports infinite looping in both directions
   
   Usage:
   {:fx/type hue-shift-canvas
    :fx/key [unique-id]
    :degrees 45.0
    :gradient-key :hsv
    :on-degrees-change {:event/type :chain/update-param ...}}"
  (:require [cljfx.api :as fx]
            [laser-show.animation.colors :as colors]
            [laser-show.common.util :as u]
            [laser-show.events.core :as events]
            [laser-show.views.components.visual-editors.gradient-cache :as grad])
  (:import [javafx.scene.canvas Canvas]
           [javafx.scene.input MouseButton KeyEvent KeyCode]
           [javafx.scene.paint Color]
           [javafx.scene.text Font]
           [javafx.event EventHandler]))


;; Drawing Functions


(defn hsv-hue->rgb
  "HSV color function: hue-degrees -> [r g b] at full saturation/value."
  [hue-degrees]
  (colors/hsv->normalized hue-degrees 1.0 1.0))

(defn oklab-hue->rgb
  "Oklab color function: hue-degrees -> [r g b] at fixed L=0.70 C=0.16."
  [hue-degrees]
  (let [[L a b-ok] (colors/oklch->oklab 0.70 0.16 hue-degrees)
        [r g b] (colors/oklab->rgb L a b-ok)]
    [(u/clamp r 0.0 1.0)
     (u/clamp g 0.0 1.0)
     (u/clamp b 0.0 1.0)]))

(defn- draw-hue-shift-strips!
  "Draw two hue strips showing input→output transformation using cached gradients.
   Top strip: Static hue gradient (input) with label on right
   Bottom strip: Shifted hue gradient (output) with label on right
   gradient-key: :hsv or :oklab, determines which cached gradient to use"
  [^Canvas canvas width height shift-degrees gradient-key]
  (let [gc (.getGraphicsContext2D canvas)
        w (double width)
        h (double height)
        label-width 50.0
        strip-width (- w label-width 4)
        strip-height (/ (- h 30) 2.0)
        gap 6.0
        input-top 0.0
        output-top (+ strip-height gap)
        label-y (+ output-top strip-height 16)
        gradient (case gradient-key
                   :hsv (grad/get-hsv-gradient! (int strip-width))
                   :oklab (grad/get-oklab-gradient! (int strip-width)))]
    (.clearRect gc 0 0 w h)

    ;; Input gradient (cached)
    (grad/draw-gradient-strip! gc gradient 0 input-top strip-width strip-height)

    (.setStroke gc (Color/web "#555555"))
    (.setLineWidth gc 1.0)
    (.strokeRect gc 0 input-top strip-width strip-height)

    (.setFill gc (Color/web "#808080"))
    (.setFont gc (Font. "System" 10))
    (.fillText gc "INPUT" (+ strip-width 6) (+ input-top (/ strip-height 2) 4))

    ;; Output gradient (shifted, cached)
    (grad/draw-shifted-gradient-strip! gc gradient 0 output-top strip-width strip-height shift-degrees)

    (.setStroke gc (Color/web "#555555"))
    (.setLineWidth gc 1.0)
    (.strokeRect gc 0 output-top strip-width strip-height)

    (.setFill gc (Color/web "#808080"))
    (.fillText gc "OUTPUT" (+ strip-width 6) (+ output-top (/ strip-height 2) 4))

    ;; Shift amount label
    (.setFill gc Color/WHITE)
    (.setFont gc (Font. "Monospace" 11))
    (let [display-degrees (let [normalized (mod (+ shift-degrees 180.0 36000.0) 360.0)]
                            (- normalized 180.0))
          label-text (format "Shift: %+.0f°" display-degrees)
          text-width (* (count label-text) 7)]
      (.fillText gc label-text (- (/ strip-width 2) (/ text-width 2)) label-y))))


;; Main Canvas Component

;; Hardcoded dimensions for hue shift strips
(def ^:const hue-shift-strip-width 420)
(def ^:const hue-shift-strip-height 100)

(defn hue-shift-canvas
  "Two-strip hue transformation canvas for hue shift visualization.
   
   Props:
   - :degrees - Current shift in degrees (supports infinite looping)
   - :color-fn - (fn [hue-degrees] -> [r g b]) color mapping function (default: hsv-hue->rgb, kept for API compat)
   - :gradient-key - :hsv or :oklab, determines which cached gradient to use (default: :hsv)
   - :on-degrees-change - Event map to dispatch when degrees changes (nil = disabled/read-only)"
  [{:keys [degrees color-fn gradient-key on-degrees-change]
    :or {degrees 0.0
         color-fn hsv-hue->rgb
         gradient-key :hsv}}]
  
  {:fx/type fx/ext-on-instance-lifecycle
   :on-created
   (fn [^Canvas canvas]
     (let [gc (.getGraphicsContext2D canvas)
           
           ;; Internal state
           dragging? (atom false)
           last-x (atom nil)
           degrees-atom (atom (or degrees 0.0))
           mouse-over? (atom false)
           
           fine-step 1.0    ; 1° for arrow keys
           coarse-step 10.0 ; 10° for Shift+arrow keys
           
           scene-filter (atom nil)
           
           render! (fn []
                     (draw-hue-shift-strips! canvas hue-shift-strip-width hue-shift-strip-height @degrees-atom gradient-key))
           
           ;; Arrow key handler
           handle-arrow-key! (fn [^KeyEvent e]
                               (when (and @mouse-over? on-degrees-change)
                                 (let [code (.getCode e)
                                       shift? (.isShiftDown e)
                                       step (if shift? coarse-step fine-step)]
                                   (when (or (= code KeyCode/LEFT)
                                             (= code KeyCode/RIGHT))
                                     (let [delta (if (= code KeyCode/RIGHT) step (- step))
                                           new-degrees (+ @degrees-atom delta)]
                                       (reset! degrees-atom new-degrees)
                                       (render!)
                                       (when on-degrees-change
                                         (events/dispatch! (assoc on-degrees-change
                                                                  :param-key :degrees
                                                                  :value new-degrees)))
                                       (.consume e))))))]
       
       ;; Mouse pressed - start drag
       (.setOnMousePressed
        canvas
        (reify EventHandler
          (handle [_ e]
            (when (and (= (.getButton e) MouseButton/PRIMARY) on-degrees-change)
              (reset! dragging? true)
              (reset! last-x (.getX e))))))
       
       ;; Mouse dragged - update shift
       (.setOnMouseDragged
        canvas
        (reify EventHandler
          (handle [_ e]
            (when (and @dragging? on-degrees-change)
              (let [x (.getX e)
                    dx (- x (or @last-x x))
                    w (double hue-shift-strip-width)
                    degree-delta (- (* (/ dx w) 360.0))
                    new-degrees (+ @degrees-atom degree-delta)]
                (reset! last-x x)
                (reset! degrees-atom new-degrees)
                (render!)
                (when on-degrees-change
                  (events/dispatch! (assoc on-degrees-change
                                           :param-key :degrees
                                           :value new-degrees))))))))
       
       ;; Mouse released - end drag
       (.setOnMouseReleased
        canvas
        (reify EventHandler
          (handle [_ e]
            (reset! dragging? false)
            (reset! last-x nil))))
       
       ;; Mouse entered - track mouse over and register key filter
       (.setOnMouseEntered
        canvas
        (reify EventHandler
          (handle [_ e]
            (reset! mouse-over? true)
            (when-let [scene (.getScene canvas)]
              (when-not @scene-filter
                (let [filter (reify EventHandler
                               (handle [_ e]
                                 (when (instance? KeyEvent e)
                                   (handle-arrow-key! e))))]
                  (reset! scene-filter filter)
                  (.addEventFilter scene KeyEvent/KEY_PRESSED filter)))))))
       
       ;; Mouse exited - clear mouse over state
       (.setOnMouseExited
        canvas
        (reify EventHandler
          (handle [_ e]
            (reset! mouse-over? false))))
       
       ;; Initial render
       (render!)
       (.setFocusTraversable canvas true)
       
       ;; Set cursor style based on whether disabled
       (.setStyle canvas (if on-degrees-change
                           "-fx-cursor: ew-resize;"
                           "-fx-cursor: default;"))))
   
   :desc {:fx/type :canvas
          :width hue-shift-strip-width
          :height hue-shift-strip-height}})
