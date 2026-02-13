(ns laser-show.views.components.visual-editors.hue-canvas
  "Horizontal gradient canvas for hue selection (0-360°).
   
   Features:
   - Horizontal hue gradient strip from 0° to 360°
   - Draggable indicator showing current hue value
   - Click anywhere to jump to that hue
   - Keyboard arrow keys for fine adjustment (±1° or ±10° with Shift)
   
   Usage:
   {:fx/type hue-canvas
    :fx/key [unique-id]
    :hue 180.0
    :on-hue-change {:event/type :chain/update-param ...}}"
  (:require [cljfx.api :as fx]
            [laser-show.events.core :as events]
            [laser-show.views.components.visual-editors.gradient-cache :as gc]
            [laser-show.common.util :as u])
  (:import [javafx.scene.canvas Canvas]
           [javafx.scene.input MouseButton KeyEvent KeyCode]
           [javafx.scene.paint Color]
           [javafx.scene.text Font]
           [javafx.event EventHandler]))


;; Drawing Functions


(defn- draw-set-hue-gradient!
  "Draw a horizontal hue gradient on the canvas for set-hue effect.
   The gradient covers the full 0 to 360 degree range."
  [^Canvas canvas width height current-hue]
  (let [gc (.getGraphicsContext2D canvas)
        w (double width)
        h (double height)
        gradient-height (- h 30)
        gradient-top 0.0]
    (.clearRect gc 0 0 w h)

    ;; Draw cached hue gradient
    (let [gradient (gc/get-hsv-gradient! (int w))]
      (gc/draw-gradient-strip! gc gradient 0 gradient-top w gradient-height))

    ;; Draw border around gradient
    (.setStroke gc (Color/web "#555555"))
    (.setLineWidth gc 1.0)
    (.strokeRect gc 0 gradient-top w gradient-height)

    ;; Calculate indicator position (current-hue from 0 to 360)
    (let [indicator-x (* (/ current-hue 360.0) w)
          indicator-top (+ gradient-top gradient-height)
          triangle-height 10.0
          triangle-half-width 6.0]

      ;; Draw indicator triangle pointing up
      (.setFill gc Color/WHITE)
      (.beginPath gc)
      (.moveTo gc indicator-x indicator-top)
      (.lineTo gc (- indicator-x triangle-half-width) (+ indicator-top triangle-height))
      (.lineTo gc (+ indicator-x triangle-half-width) (+ indicator-top triangle-height))
      (.closePath gc)
      (.fill gc)

      ;; Draw indicator outline
      (.setStroke gc Color/BLACK)
      (.setLineWidth gc 1.0)
      (.beginPath gc)
      (.moveTo gc indicator-x indicator-top)
      (.lineTo gc (- indicator-x triangle-half-width) (+ indicator-top triangle-height))
      (.lineTo gc (+ indicator-x triangle-half-width) (+ indicator-top triangle-height))
      (.closePath gc)
      (.stroke gc)

      ;; Draw vertical line through gradient at indicator position
      (.setStroke gc Color/WHITE)
      (.setLineWidth gc 2.0)
      (.strokeLine gc indicator-x gradient-top indicator-x gradient-height)
      (.setStroke gc Color/BLACK)
      (.setLineWidth gc 1.0)
      (.strokeLine gc (dec indicator-x) gradient-top (dec indicator-x) gradient-height)
      (.strokeLine gc (inc indicator-x) gradient-top (inc indicator-x) gradient-height)

      ;; Draw degree label below triangle
      (.setFill gc Color/WHITE)
      (.setFont gc (Font. "Monospace" 10))
      (let [label-text (format "%.0f°" current-hue)
            label-width (* (count label-text) 6)
            label-x (max 2 (min (- w label-width 2) (- indicator-x (/ label-width 2))))]
        (.fillText gc label-text label-x (+ indicator-top triangle-height 12))))))


;; Angle Calculations


(defn- clamp-hue
  "Clamp hue to 0 to 360 range."
  [hue]
  (u/clamp hue 0.0 360.0))


;; Main Canvas Component


;; Hardcoded dimensions for hue strip
(def ^:const hue-strip-width 420)
(def ^:const hue-strip-height 60)

(defn hue-canvas
  "Horizontal gradient canvas for hue selection.
   
   Props:
   - :hue - Current hue in degrees (0 to 360)
   - :on-hue-change - Event map to dispatch when hue changes (nil = disabled/read-only)"
  [{:keys [hue on-hue-change]
   :or {hue 0.0}}]
  
  {:fx/type fx/ext-on-instance-lifecycle
   :on-created
   (fn [^Canvas canvas]
     (let [gc (.getGraphicsContext2D canvas)
           
           ;; Internal state
           dragging? (atom false)
           hue-atom (atom (or hue 0.0))
           mouse-over? (atom false)
           
           fine-step 1.0    ; 1° for arrow keys
           coarse-step 10.0 ; 10° for Shift+arrow keys
           
           scene-filter (atom nil)
           
           ;; Render function
           render! (fn []
                     (draw-set-hue-gradient! canvas hue-strip-width hue-strip-height @hue-atom))
           
           ;; Mouse to hue conversion
           mouse-to-hue (fn [mx]
                          (let [w (double hue-strip-width)
                                hue-val (-> (/ mx w)
                                           (* 360.0)
                                           (max 0.0)
                                           (min 360.0))]
                            hue-val))
           
           ;; Arrow key handler
           handle-arrow-key! (fn [^KeyEvent e]
                               (when (and @mouse-over? on-hue-change)
                                 (let [code (.getCode e)
                                       shift? (.isShiftDown e)
                                       step (if shift? coarse-step fine-step)]
                                   (when (or (= code KeyCode/LEFT)
                                             (= code KeyCode/RIGHT))
                                     (let [delta (if (= code KeyCode/RIGHT) step (- step))
                                           new-hue (clamp-hue (+ @hue-atom delta))]
                                       (reset! hue-atom new-hue)
                                       (render!)
                                       (when on-hue-change
                                         (events/dispatch! (assoc on-hue-change
                                                                  :param-key :hue
                                                                  :value new-hue)))
                                       (.consume e))))))]
       
       ;; Mouse pressed - start drag or click
       (.setOnMousePressed
        canvas
        (reify EventHandler
          (handle [_ e]
            (when (and (= (.getButton e) MouseButton/PRIMARY) on-hue-change)
              (reset! dragging? true)
              (let [mx (.getX e)
                    new-hue (mouse-to-hue mx)]
                (reset! hue-atom new-hue)
                (render!)
                (events/dispatch! (assoc on-hue-change
                                         :param-key :hue
                                         :value new-hue)))))))
       
       ;; Mouse dragged - update hue
       (.setOnMouseDragged
        canvas
        (reify EventHandler
          (handle [_ e]
            (when (and @dragging? on-hue-change)
              (let [mx (.getX e)
                    new-hue (mouse-to-hue mx)]
                (reset! hue-atom new-hue)
                (render!)
                (events/dispatch! (assoc on-hue-change
                                         :param-key :hue
                                         :value new-hue)))))))
       
       ;; Mouse released - end drag
       (.setOnMouseReleased
        canvas
        (reify EventHandler
          (handle [_ e]
            (reset! dragging? false))))
       
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
       (.setStyle canvas (if on-hue-change
                          "-fx-cursor: crosshair;"
                          "-fx-cursor: default;"))))
   
   :desc {:fx/type :canvas
          :width hue-strip-width
          :height hue-strip-height}})
