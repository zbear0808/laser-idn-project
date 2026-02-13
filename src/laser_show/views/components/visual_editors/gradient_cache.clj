(ns laser-show.views.components.visual-editors.gradient-cache
  "Pre-rendered gradient images for efficient canvas rendering.
   
   Gradients are computed once and cached in module-level atoms.
   Subsequent renders use fast drawImage calls instead of per-pixel fillRect."
  (:require [laser-show.animation.colors :as colors]
            [laser-show.common.util :as u])
  (:import [javafx.scene.image WritableImage PixelWriter]
           [javafx.scene.paint Color]
           [javafx.scene.canvas GraphicsContext]))

(defonce !hsv-gradient (atom nil))
(defonce !oklab-gradient (atom nil))

(defn- create-hsv-gradient-image
  "Pre-render a 1-row HSV hue gradient as a WritableImage."
  ^WritableImage [^long width]
  (let [img (WritableImage. width 1)
        pw (.getPixelWriter img)]
    (dotimes [x width]
      (let [hue (* (/ (double x) (double width)) 360.0)
            [r g b] (colors/hsv->normalized hue 1.0 1.0)]
        (.setColor pw x 0 (Color/color r g b 1.0))))
    img))

(defn- create-oklab-gradient-image
  "Pre-render a 1-row Oklab hue gradient as a WritableImage."
  ^WritableImage [^long width lightness chroma]
  (let [img (WritableImage. width 1)
        pw (.getPixelWriter img)]
    (dotimes [x width]
      (let [hue (* (/ (double x) (double width)) 360.0)
            [L a b-ok] (colors/oklch->oklab lightness chroma hue)
            [r g b] (colors/oklab->rgb L a b-ok)]
        (.setColor pw x 0 (Color/color (u/clamp r 0.0 1.0)
                                       (u/clamp g 0.0 1.0)
                                       (u/clamp b 0.0 1.0)
                                       1.0))))
    img))

(defn get-hsv-gradient!
  "Get or create the cached HSV gradient image for given width."
  ^WritableImage [^long width]
  (or @!hsv-gradient
      (let [img (create-hsv-gradient-image width)]
        (reset! !hsv-gradient img)
        img)))

(defn get-oklab-gradient!
  "Get or create the cached Oklab gradient image for given width.
   Uses fixed L=0.70 C=0.16 for perceptually uniform hue wheel."
  ^WritableImage [^long width]
  (or @!oklab-gradient
      (let [img (create-oklab-gradient-image width 0.70 0.16)]
        (reset! !oklab-gradient img)
        img)))

(defn draw-gradient-strip!
  "Draw a cached gradient image onto the graphics context, stretched to fill the target rect."
  [^GraphicsContext gc ^WritableImage img x y w h]
  (.drawImage gc img (double x) (double y) (double w) (double h)))

(defn draw-shifted-gradient-strip!
  "Draw a cyclically shifted gradient strip using two drawImage calls.
   
   Parameters:
   - gc: GraphicsContext to draw on
   - img: Source gradient image
   - x, y, w, h: Destination rectangle
   - shift-degrees: How many degrees to shift (positive = shift right)"
  [^GraphicsContext gc ^WritableImage img x y w h shift-degrees]
  (let [x (double x)
        y (double y)
        w (double w)
        h (double h)
        shift-degrees (double shift-degrees)
        img-width (double (.getWidth img))
        pixel-offset (mod (/ (* shift-degrees img-width) 360.0) img-width)
        pixel-offset (mod (+ pixel-offset img-width) img-width)
        right-portion-width (- img-width pixel-offset)
        left-portion-width pixel-offset
        scale-x (/ w img-width)]
    (when (pos? right-portion-width)
      (.drawImage gc img
                  pixel-offset 0.0 right-portion-width 1.0
                  x y (* right-portion-width scale-x) h))
    (when (pos? left-portion-width)
      (.drawImage gc img
                  0.0 0.0 left-portion-width 1.0
                  (+ x (* right-portion-width scale-x)) y (* left-portion-width scale-x) h))))

(defn reset-caches!
  "Reset gradient caches. Useful during development when changing color math."
  []
  (reset! !hsv-gradient nil)
  (reset! !oklab-gradient nil))
