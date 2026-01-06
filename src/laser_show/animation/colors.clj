(ns laser-show.animation.colors
  "Color utilities and point color manipulation functions.
   
   This module uses NORMALIZED COLOR VALUES (0.0 to 1.0) internally.
   All color operations work with normalized floats for maximum precision.
   
   Provides:
   - Normalized color constants (0.0-1.0 per channel)
   - Conversion functions between normalized, 8-bit, and 16-bit formats (for IDN output)
   - HSV color space conversions (using normalized values)
   - Color manipulation functions for laser points
   - Rainbow and gradient generators"
  (:require [laser-show.common.util :as u]))



(def ^:const bit-depth-8 8)
(def ^:const bit-depth-16 16)
(def ^:const max-8bit 255)
(def ^:const max-16bit 65535)

(defn normalized->8bit
  "Convert normalized [r g b] (0.0-1.0) to 8-bit [r g b] (0-255)."
  [[r g b]]
  [(int (* (double r) max-8bit))
   (int (* (double g) max-8bit))
   (int (* (double b) max-8bit))])

(defn color-8bit->normalized
  "Convert 8-bit [r g b] (0-255) to normalized [r g b] (0.0-1.0)."
  [[r g b]]
  [(/ (double r) max-8bit)
   (/ (double g) max-8bit)
   (/ (double b) max-8bit)])


;; Normalized <-> 16-bit conversions

(defn normalized->16bit
  "Convert normalized [r g b] (0.0-1.0) to 16-bit [r g b] (0-65535)."
  [[r g b]]
  [(int (* (double r) max-16bit))
   (int (* (double g) max-16bit))
   (int (* (double b) max-16bit))])

(defn color-16bit->normalized
  "Convert 16-bit [r g b] (0-65535) to normalized [r g b] (0.0-1.0)."
  [[r g b]]
  [(/ (double r) max-16bit)
   (/ (double g) max-16bit)
   (/ (double b) max-16bit)])

;;; ============================================================
;;; HSV Color Conversion (Normalized Output)
;;; ============================================================


(defn hsv->normalized
  "Convert HSV to normalized RGB (0.0-1.0).
   h: hue in degrees [0, 360]
   s: saturation [0, 1]
   v: value/brightness [0, 1]
   Returns: [r g b] with values in [0.0, 1.0]"
  [h s v]
  (let [h (double (mod h 360))
        s (double s)
        v (double v)
        c (* v s)
        x (* c (- 1.0 (Math/abs (- (mod (/ h 60.0) 2.0) 1.0))))
        m (- v c)
        [r' g' b'] (cond
                     (< h 60.0)  [c x 0.0]
                     (< h 120.0) [x c 0.0]
                     (< h 180.0) [0.0 c x]
                     (< h 240.0) [0.0 x c]
                     (< h 300.0) [x 0.0 c]
                     :else       [c 0.0 x])]
    [(+ r' m)
     (+ g' m)
     (+ b' m)]))

(defn normalized->hsv
  "Convert normalized RGB to HSV.
   r, g, b: color values in [0.0, 1.0]
   Returns: [h s v] with h in [0, 360], s and v in [0, 1]"
  [r g b]
  (let [r' (double r)
        g' (double g)
        b' (double b)
        cmax (max r' g' b')
        cmin (min r' g' b')
        delta (- cmax cmin)
        h (cond
            (zero? delta) 0
            (= cmax r') (* 60 (mod (/ (- g' b') delta) 6))
            (= cmax g') (* 60 (+ (/ (- b' r') delta) 2))
            :else (* 60 (+ (/ (- r' g') delta) 4)))
        s (if (zero? cmax) 0 (/ delta cmax))
        v cmax]
    [(if (neg? h) (+ h 360) h) s v]))



;;; ============================================================
;;; Rainbow Color Generation (Normalized)
;;; ============================================================


(defn rainbow-normalized
  "Get a rainbow color based on position (0.0 to 1.0).
   Returns normalized [r g b] vector (0.0-1.0)."
  [position]
  (hsv->normalized (* position 360) 1.0 1.0))

;;; ============================================================
;;; Color Interpolation (Normalized)
;;; ============================================================


(defn lerp-color-normalized
  "Linear interpolation between two normalized colors.
   t should be in [0, 1]."
  [[r1 g1 b1] [r2 g2 b2] t]
  [(+ r1 (* (- r2 r1) t))
   (+ g1 (* (- g2 g1) t))
   (+ b1 (* (- b2 b1) t))])
