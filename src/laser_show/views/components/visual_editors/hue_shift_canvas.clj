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
  (:require [laser-show.animation.colors :as colors]
            [laser-show.common.util :as u]
            [laser-show.views.components.visual-editors.canvas-interaction :as ci]
            [laser-show.views.components.visual-editors.gradient-cache :as grad])
  (:import [javafx.scene.canvas Canvas]
           [javafx.scene.input MouseButton KeyCode]
           [javafx.scene.paint Color]
           [javafx.scene.text Font]))


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
   - :gradient-key - :hsv or :oklab, determines which cached gradient to use (default: :hsv)
   - :on-degrees-change - Event map to dispatch when degrees changes (nil = disabled/read-only)"
  [{:keys [degrees gradient-key on-degrees-change]
    :or {degrees 0.0
         gradient-key :hsv}}]

  (ci/interactive-canvas
   {:width  hue-shift-strip-width
    :height hue-shift-strip-height
    :initial-state (or degrees 0.0)
    :cursor (if on-degrees-change "ew-resize" "default")

    :render!
    (fn [^Canvas canvas state _drag-info]
      (draw-hue-shift-strips! canvas hue-shift-strip-width hue-shift-strip-height
                              state gradient-key))

    :on-press
    (fn [mx _my button _state _drag-info]
      (when (and (= button MouseButton/PRIMARY) on-degrees-change)
        {:drag-start true
         :drag-updates {:last-x mx}}))

    :on-drag
    (fn [mx _my state drag-info]
      (when on-degrees-change
        (let [last-x (or (:last-x drag-info) mx)
              dx (- mx last-x)
              degree-delta (- (* (/ dx (double hue-shift-strip-width)) 360.0))
              new-degrees (+ state degree-delta)]
          {:state new-degrees
           :dispatch (assoc on-degrees-change
                            :param-key :degrees
                            :value new-degrees)
           :drag-updates {:last-x mx}})))

    :on-key
    (fn [key-code shift? state _drag-info]
      (when on-degrees-change
        (let [step (if shift? 10.0 1.0)]
          (when (or (= key-code KeyCode/LEFT)
                    (= key-code KeyCode/RIGHT))
            (let [delta (if (= key-code KeyCode/RIGHT) step (- step))
                  new-degrees (+ state delta)]
              {:state new-degrees
               :dispatch (assoc on-degrees-change
                                :param-key :degrees
                                :value new-degrees)
               :consumed? true})))))}))
