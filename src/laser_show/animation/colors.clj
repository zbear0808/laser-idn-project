(ns laser-show.animation.colors
  "ILDA color utilities and point color manipulation functions.
   
   ILDA/IDN supports high bit-depth colors (16-bit per channel).
   This module provides:
   - High bit-depth color representation (16-bit per channel, 0-65535)
   - Standard 8-bit color representation (0-255) for UI display
   - Conversion functions between bit depths
   - Color manipulation functions for laser points")


;; Bit Depth Constants


(def ^:const bit-depth-8 8)
(def ^:const bit-depth-16 16)
(def ^:const max-8bit 255)
(def ^:const max-16bit 65535)



;; Standard 8-bit Colors (for UI display)
;; Values range from 0 to 255


(def red [255 0 0])
(def green [0 255 0])
(def blue [0 0 255])
(def white [255 255 255])
(def black [0 0 0])

(def cyan [0 255 255])
(def magenta [255 0 255])
(def yellow [255 255 0])

(def orange [255 128 0])
(def pink [255 128 192])
(def purple [128 0 255])
(def lime [128 255 0])

(def warm-white [255 240 220])
(def cool-white [220 240 255])

(def colors
  "Map of color names to 8-bit RGB vectors."
  {:red red
   :green green
   :blue blue
   :white white
   :black black
   :cyan cyan
   :magenta magenta
   :yellow yellow
   :orange orange
   :pink pink
   :purple purple
   :lime lime
   :warm-white warm-white
   :cool-white cool-white})


;; Bit Depth Conversion


(defn convert-channel
  "Convert a single color channel between bit depths."
  [value from-max to-max]
  (int (Math/round (* (/ (double value) from-max) to-max))))

(defn ilda->8bit
  "Convert a 16-bit ILDA color [r g b] to 8-bit [r g b].
   Input: values 0-65535, Output: values 0-255"
  [[r g b]]
  [(convert-channel r max-16bit max-8bit)
   (convert-channel g max-16bit max-8bit)
   (convert-channel b max-16bit max-8bit)])

(defn rgb8->ilda
  "Convert an 8-bit color [r g b] to 16-bit ILDA [r g b].
   Input: values 0-255, Output: values 0-65535"
  [[r g b]]
  [(convert-channel r max-8bit max-16bit)
   (convert-channel g max-8bit max-16bit)
   (convert-channel b max-8bit max-16bit)])

(defn ilda->normalized
  "Convert a 16-bit ILDA color to normalized floats [0.0-1.0]."
  [[r g b]]
  [(/ (double r) max-16bit)
   (/ (double g) max-16bit)
   (/ (double b) max-16bit)])

(defn normalized->ilda
  "Convert normalized floats [0.0-1.0] to 16-bit ILDA color."
  [[r g b]]
  [(int (* r max-16bit))
   (int (* g max-16bit))
   (int (* b max-16bit))])

(defn normalized->8bit
  "Convert normalized floats [0.0-1.0] to 8-bit color."
  [[r g b]]
  [(int (* r max-8bit))
   (int (* g max-8bit))
   (int (* b max-8bit))])

(defn rgb8->normalized
  "Convert 8-bit color to normalized floats [0.0-1.0]."
  [[r g b]]
  [(/ (double r) max-8bit)
   (/ (double g) max-8bit)
   (/ (double b) max-8bit)])


;; ILDA Color Record (for type safety and clarity)


(defrecord ILDAColor [^int r ^int g ^int b ^int bit-depth])

(defn make-ilda-color
  "Create an ILDA color with explicit bit depth.
   Default is 16-bit."
  ([r g b]
   (make-ilda-color r g b bit-depth-16))
  ([r g b bit-depth]
   (->ILDAColor r g b bit-depth)))

(defn ilda-color->vec
  "Convert ILDAColor record to [r g b] vector."
  [^ILDAColor color]
  [(:r color) (:g color) (:b color)])

(defn ilda-color->8bit
  "Convert ILDAColor to 8-bit [r g b] vector for display."
  [^ILDAColor color]
  (if (= (:bit-depth color) bit-depth-8)
    [(:r color) (:g color) (:b color)]
    (ilda->8bit [(:r color) (:g color) (:b color)])))


;; HSV Color Conversion (8-bit)


(defn hsv->rgb
  "Convert HSV to 8-bit RGB. h in [0, 360], s and v in [0, 1].
   Returns [r g b] with values in [0, 255]."
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
    [(int (* max-8bit (+ r' m)))
     (int (* max-8bit (+ g' m)))
     (int (* max-8bit (+ b' m)))]))

(defn rgb->hsv
  "Convert 8-bit RGB to HSV. r, g, b in [0, 255].
   Returns [h s v] with h in [0, 360], s and v in [0, 1]."
  [r g b]
  (let [r' (/ r (double max-8bit))
        g' (/ g (double max-8bit))
        b' (/ b (double max-8bit))
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


;; HSV Color Conversion (16-bit ILDA)


(defn hsv->ilda
  "Convert HSV to 16-bit ILDA RGB. h in [0, 360], s and v in [0, 1].
   Returns [r g b] with values in [0, 65535]."
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
    [(int (* max-16bit (+ r' m)))
     (int (* max-16bit (+ g' m)))
     (int (* max-16bit (+ b' m)))]))

(defn ilda->hsv
  "Convert 16-bit ILDA RGB to HSV. r, g, b in [0, 65535].
   Returns [h s v] with h in [0, 360], s and v in [0, 1]."
  [r g b]
  (let [r' (/ r (double max-16bit))
        g' (/ g (double max-16bit))
        b' (/ b (double max-16bit))
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


;; Rainbow Color Generation (8-bit)


(defn rainbow
  "Get a rainbow color based on position (0.0 to 1.0).
   Returns 8-bit [r g b] vector (0-255)."
  [position]
  (hsv->rgb (* position 360) 1.0 1.0))

(defn rainbow-shifted
  "Get a rainbow color with a hue offset.
   position: 0.0 to 1.0
   offset: hue offset in degrees (0-360)
   Returns 8-bit [r g b] vector (0-255)."
  [position offset]
  (hsv->rgb (+ (* position 360) offset) 1.0 1.0))


;; Rainbow Color Generation (16-bit ILDA)


(defn rainbow-ilda
  "Get a rainbow color based on position (0.0 to 1.0).
   Returns 16-bit ILDA [r g b] vector (0-65535)."
  [position]
  (hsv->ilda (* position 360) 1.0 1.0))

(defn rainbow-ilda-shifted
  "Get a rainbow color with a hue offset.
   position: 0.0 to 1.0
   offset: hue offset in degrees (0-360)
   Returns 16-bit ILDA [r g b] vector (0-65535)."
  [position offset]
  (hsv->ilda (+ (* position 360) offset) 1.0 1.0))


;; Color Interpolation


(defn lerp-color
  "Linear interpolation between two colors.
   t should be in [0, 1]."
  [[r1 g1 b1] [r2 g2 b2] t]
  [(int (+ r1 (* (- r2 r1) t)))
   (int (+ g1 (* (- g2 g1) t)))
   (int (+ b1 (* (- b2 b1) t)))])

(defn gradient
  "Generate a gradient between two colors.
   Returns a function that takes position (0.0 to 1.0) and returns [r g b]."
  [color1 color2]
  (fn [t]
    (lerp-color color1 color2 t)))

(defn multi-gradient
  "Generate a gradient through multiple colors.
   colors: vector of [r g b] colors
   Returns a function that takes position (0.0 to 1.0) and returns [r g b]."
  [colors]
  (let [n (count colors)
        segments (dec n)]
    (fn [t]
      (if (<= t 0) (first colors)
          (if (>= t 1) (last colors)
              (let [scaled (* t segments)
                    idx (int scaled)
                    local-t (- scaled idx)]
                (lerp-color (nth colors idx) (nth colors (inc idx)) local-t)))))))


;; Point Color Manipulation


(defn recolor-point
  "Recolor a single point with a new [r g b] color.
   Preserves x, y, and intensity."
  [point [r g b]]
  (assoc point :r (unchecked-byte r) :g (unchecked-byte g) :b (unchecked-byte b)))

(defn recolor-points
  "Recolor all points in a collection with a single [r g b] color."
  [points color]
  (map #(recolor-point % color) points))

(defn rainbow-by-index
  "Apply rainbow colors to points based on their index in the sequence.
   offset: optional hue offset (0-360, default 0)
   Returns a new sequence of points with rainbow colors."
  ([points]
   (rainbow-by-index points 0))
  ([points offset]
   (let [n (count points)]
     (map-indexed
      (fn [i point]
        (let [t (/ i (max 1 (dec n)))
              [r g b] (rainbow-shifted t offset)]
          (recolor-point point [r g b])))
      points))))

(defn rainbow-by-position
  "Apply rainbow colors to points based on their x position.
   offset: optional hue offset (0-360, default 0)
   Returns a new sequence of points with rainbow colors."
  ([points]
   (rainbow-by-position points 0))
  ([points offset]
   (map (fn [point]
          (let [x (:x point)
                t (/ (+ x 32768) 65536.0)
                [r g b] (rainbow-shifted t offset)]
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
          (let [x (/ (:x point) 32767.0)
                y (/ (:y point) 32767.0)
                angle (Math/atan2 y x)
                t (/ (+ angle Math/PI) (* 2 Math/PI))
                [r g b] (rainbow-shifted t offset)]
            (recolor-point point [r g b])))
        points)))

(defn gradient-by-index
  "Apply a gradient to points based on their index.
   gradient-fn: function that takes t (0-1) and returns [r g b]"
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
   gradient-fn: function that takes t (0-1) and returns [r g b]"
  [points gradient-fn]
  (map (fn [point]
         (let [x (:x point)
               t (/ (+ x 32768) 65536.0)
               [r g b] (gradient-fn t)]
           (recolor-point point [r g b])))
       points))


;; Color Effects


(defn fade-points
  "Fade all points toward black by a factor (0.0 = no change, 1.0 = black)."
  [points factor]
  (let [f (- 1.0 factor)]
    (map (fn [point]
           (let [r (int (* (bit-and (:r point) 0xFF) f))
                 g (int (* (bit-and (:g point) 0xFF) f))
                 b (int (* (bit-and (:b point) 0xFF) f))]
             (recolor-point point [r g b])))
         points)))

(defn brighten-points
  "Brighten all points toward white by a factor (0.0 = no change, 1.0 = white)."
  [points factor]
  (map (fn [point]
         (let [r (bit-and (:r point) 0xFF)
               g (bit-and (:g point) 0xFF)
               b (bit-and (:b point) 0xFF)
               nr (int (+ r (* (- 255 r) factor)))
               ng (int (+ g (* (- 255 g) factor)))
               nb (int (+ b (* (- 255 b) factor)))]
           (recolor-point point [nr ng nb])))
       points))

(defn saturate-points
  "Adjust saturation of all points.
   factor > 1.0 increases saturation, < 1.0 decreases."
  [points factor]
  (map (fn [point]
         (let [r (bit-and (:r point) 0xFF)
               g (bit-and (:g point) 0xFF)
               b (bit-and (:b point) 0xFF)
               [h s v] (rgb->hsv r g b)
               new-s (min 1.0 (max 0.0 (* s factor)))
               [nr ng nb] (hsv->rgb h new-s v)]
           (recolor-point point [nr ng nb])))
       points))

(defn shift-hue-points
  "Shift the hue of all points by a given amount (in degrees)."
  [points degrees]
  (map (fn [point]
         (let [r (bit-and (:r point) 0xFF)
               g (bit-and (:g point) 0xFF)
               b (bit-and (:b point) 0xFF)
               [h s v] (rgb->hsv r g b)
               new-h (mod (+ h degrees) 360)
               [nr ng nb] (hsv->rgb new-h s v)]
           (recolor-point point [nr ng nb])))
       points))


;; Animated Color Effects (time-based)


(defn rainbow-cycle
  "Get a rainbow color that cycles over time.
   time-ms: current time in milliseconds
   cycle-duration-ms: duration of one full rainbow cycle"
  [time-ms cycle-duration-ms]
  (let [t (/ (mod time-ms cycle-duration-ms) cycle-duration-ms)]
    (rainbow t)))

(defn pulse-color
  "Pulse a color's brightness over time.
   color: base [r g b] color
   time-ms: current time in milliseconds
   pulse-duration-ms: duration of one pulse cycle
   min-brightness: minimum brightness (0-1, default 0.3)
   max-brightness: maximum brightness (0-1, default 1.0)"
  ([color time-ms pulse-duration-ms]
   (pulse-color color time-ms pulse-duration-ms 0.3 1.0))
  ([color time-ms pulse-duration-ms min-brightness max-brightness]
   (let [t (/ (mod time-ms pulse-duration-ms) pulse-duration-ms)
         brightness (+ min-brightness
                       (* (- max-brightness min-brightness)
                          (/ (+ 1 (Math/sin (* t 2 Math/PI))) 2)))
         [r g b] color]
     [(int (* r brightness))
      (int (* g brightness))
      (int (* b brightness))])))
