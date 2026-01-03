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


;; Bit Depth Constants


(def ^:const bit-depth-8 8)
(def ^:const bit-depth-16 16)
(def ^:const max-8bit 255)
(def ^:const max-16bit 65535)


;;; ============================================================
;;; Normalized Color Constants (PRIMARY - 0.0 to 1.0)
;;; ============================================================


(def red-n [1.0 0.0 0.0])
(def green-n [0.0 1.0 0.0])
(def blue-n [0.0 0.0 1.0])
(def white-n [1.0 1.0 1.0])
(def black-n [0.0 0.0 0.0])

(def cyan-n [0.0 1.0 1.0])
(def magenta-n [1.0 0.0 1.0])
(def yellow-n [1.0 1.0 0.0])

(def orange-n [1.0 0.5 0.0])
(def pink-n [1.0 0.5 0.75])
(def purple-n [0.5 0.0 1.0])
(def lime-n [0.5 1.0 0.0])

(def warm-white-n [1.0 0.94 0.86])
(def cool-white-n [0.86 0.94 1.0])

(def colors-normalized
  "Map of color names to normalized RGB vectors (0.0-1.0)."
  {:red red-n
   :green green-n
   :blue blue-n
   :white white-n
   :black black-n
   :cyan cyan-n
   :magenta magenta-n
   :yellow yellow-n
   :orange orange-n
   :pink pink-n
   :purple purple-n
   :lime lime-n
   :warm-white warm-white-n
   :cool-white cool-white-n})



;;; ============================================================
;;; Bit Depth Conversion Functions
;;; ============================================================


(defn convert-channel
  "Convert a single color channel between bit depths."
  [value from-max to-max]
  (int (Math/round (* (/ (double value) from-max) to-max))))


;; Normalized <-> 8-bit conversions

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

(defn rainbow-normalized-shifted
  "Get a rainbow color with a hue offset.
   position: 0.0 to 1.0
   offset: hue offset in degrees (0-360)
   Returns normalized [r g b] vector (0.0-1.0)."
  [position offset]
  (hsv->normalized (+ (* position 360) offset) 1.0 1.0))



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

(defn gradient-normalized
  "Generate a gradient between two normalized colors.
   Returns a function that takes position (0.0 to 1.0) and returns normalized [r g b]."
  [color1 color2]
  (fn [t]
    (lerp-color-normalized color1 color2 t)))

(defn multi-gradient-normalized
  "Generate a gradient through multiple normalized colors.
   colors: vector of normalized [r g b] colors
   Returns a function that takes position (0.0 to 1.0) and returns normalized [r g b]."
  [colors]
  (let [n (count colors)
        segments (dec n)]
    (fn [t]
      (if (<= t 0) (first colors)
          (if (>= t 1) (last colors)
              (let [scaled (* t segments)
                    idx (int scaled)
                    local-t (- scaled idx)]
                (lerp-color-normalized (nth colors idx) (nth colors (inc idx)) local-t)))))))



;;; ============================================================
;;; Point Color Manipulation (Normalized)
;;; ============================================================


(defn recolor-point
  "Recolor a single point with a new normalized [r g b] color.
   Preserves x, y coordinates.
   Color values must be normalized (0.0-1.0)."
  [point [r g b]]
  (assoc point :r r :g g :b b))

(defn recolor-points
  "Recolor all points in a collection with a single [r g b] color."
  [points color]
  (map #(recolor-point % color) points))

(defn rainbow-by-index
  "Apply rainbow colors to points based on their index in the sequence.
   offset: optional hue offset (0-360, default 0)
   Returns a new sequence of points with normalized rainbow colors."
  ([points]
   (rainbow-by-index points 0))
  ([points offset]
   (let [n (count points)]
     (map-indexed
      (fn [i point]
        (let [t (/ i (max 1 (dec n)))
              [r g b] (rainbow-normalized-shifted t offset)]
          (recolor-point point [r g b])))
      points))))

(defn rainbow-by-position
  "Apply rainbow colors to points based on their x position.
   offset: optional hue offset (0-360, default 0)
   Returns a new sequence of points with normalized rainbow colors."
  ([points]
   (rainbow-by-position points 0))
  ([points offset]
   (map (fn [point]
          (let [x (:x point)
                ;; x is already normalized (-1.0 to 1.0), convert to 0-1 range
                t (/ (+ x 1.0) 2.0)
                [r g b] (rainbow-normalized-shifted t offset)]
            (recolor-point point [r g b])))
        points)))

(defn rainbow-by-position-2d
  "Apply rainbow colors to points based on both x and y position.
   Uses angle from center to determine hue.
   offset: optional hue offset (0-360, default 0)"
  ([points]
   (rainbow-by-position-2d points 0))
  ([points offset]
   (map (fn [point]
          (let [x (:x point)  ; Already normalized
                y (:y point)  ; Already normalized
                angle (Math/atan2 y x)
                t (/ (+ angle Math/PI) (* 2 Math/PI))
                [r g b] (rainbow-normalized-shifted t offset)]
            (recolor-point point [r g b])))
        points)))

(defn gradient-by-index
  "Apply a gradient to points based on their index.
   gradient-fn: function that takes t (0-1) and returns normalized [r g b]"
  [points gradient-fn]
  (let [n (count points)]
    (map-indexed
     (fn [i point]
       (let [t (/ i (max 1 (dec n)))
             [r g b] (gradient-fn t)]
         (recolor-point point [r g b])))
     points)))

(defn gradient-by-position
  "Apply a gradient to points based on their x position.
   gradient-fn: function that takes t (0-1) and returns normalized [r g b]"
  [points gradient-fn]
  (map (fn [point]
         (let [x (:x point)  ; Already normalized (-1.0 to 1.0)
               t (/ (+ x 1.0) 2.0)
               [r g b] (gradient-fn t)]
           (recolor-point point [r g b])))
       points))


;;; ============================================================
;;; Color Effects (Normalized)
;;; ============================================================


(defn fade-points
  "Fade all points toward black by a factor (0.0 = no change, 1.0 = black).
   Works with normalized color values."
  [points factor]
  (let [f (- 1.0 factor)]
    (map (fn [point]
           (assoc point
             :r (* (:r point) f)
             :g (* (:g point) f)
             :b (* (:b point) f)))
         points)))

(defn brighten-points
  "Brighten all points toward white by a factor (0.0 = no change, 1.0 = white).
   Works with normalized color values."
  [points factor]
  (map (fn [point]
         (let [r (:r point)
               g (:g point)
               b (:b point)]
           (assoc point
             :r (+ r (* (- 1.0 r) factor))
             :g (+ g (* (- 1.0 g) factor))
             :b (+ b (* (- 1.0 b) factor)))))
       points))

(defn saturate-points
  "Adjust saturation of all points.
   factor > 1.0 increases saturation, < 1.0 decreases.
   Works with normalized color values."
  [points factor]
  (map (fn [point]
         (let [r (:r point)
               g (:g point)
               b (:b point)
               [h s v] (normalized->hsv r g b)
               new-s (u/clamp (* s factor) 0.0 1.0)
               [nr ng nb] (hsv->normalized h new-s v)]
           (assoc point :r nr :g ng :b nb)))
       points))

(defn shift-hue-points
  "Shift the hue of all points by a given amount (in degrees).
   Works with normalized color values."
  [points degrees]
  (map (fn [point]
         (let [r (:r point)
               g (:g point)
               b (:b point)
               [h s v] (normalized->hsv r g b)
               new-h (mod (+ h degrees) 360)
               [nr ng nb] (hsv->normalized new-h s v)]
           (assoc point :r nr :g ng :b nb)))
       points))


;;; ============================================================
;;; Animated Color Effects (time-based)
;;; ============================================================


(defn rainbow-cycle
  "Get a rainbow color that cycles over time.
   time-ms: current time in milliseconds
   cycle-duration-ms: duration of one full rainbow cycle
   Returns normalized [r g b]."
  [time-ms cycle-duration-ms]
  (let [t (/ (mod time-ms cycle-duration-ms) cycle-duration-ms)]
    (rainbow-normalized t)))

(defn pulse-color
  "Pulse a color's brightness over time.
   color: base normalized [r g b] color
   time-ms: current time in milliseconds
   pulse-duration-ms: duration of one pulse cycle
   min-brightness: minimum brightness (0-1, default 0.3)
   max-brightness: maximum brightness (0-1, default 1.0)
   Returns normalized [r g b]."
  ([color time-ms pulse-duration-ms]
   (pulse-color color time-ms pulse-duration-ms 0.3 1.0))
  ([color time-ms pulse-duration-ms min-brightness max-brightness]
   (let [t (/ (mod time-ms pulse-duration-ms) pulse-duration-ms)
         brightness (+ min-brightness
                       (* (- max-brightness min-brightness)
                          (/ (+ 1 (Math/sin (* t 2 Math/PI))) 2)))
         [r g b] color]
     [(* r brightness)
      (* g brightness)
      (* b brightness)])))


