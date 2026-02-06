(ns laser-show.animation.colors
  "Color utilities and point color manipulation functions.
   
   This module uses NORMALIZED COLOR VALUES (0.0 to 1.0) internally.
   All color operations work with normalized floats for maximum precision.
   
   Provides:
   - Normalized color constants (0.0-1.0 per channel)
   - Conversion functions between normalized, 8-bit, and 16-bit formats (for IDN output)
   - HSV color space conversions (using normalized values)
   - Color manipulation functions for laser points
   - Rainbow and gradient generators")

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)



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


;;; HSV Color Conversion (Normalized Output)



(defn hsv->normalized
  "Convert HSV to normalized RGB (0.0-1.0).
   h: hue in degrees [0, 360]
   s: saturation [0, 1]
   v: value/brightness [0, 1]
   Returns: [r g b] with values in [0.0, 1.0]"
  [h s v]
  (let [h' (double (mod (double h) 360.0))
        s' (double s)
        v' (double v)
        c (* v' s')
        x (* c (- 1.0 (Math/abs (- (double (mod (/ h' 60.0) 2.0)) 1.0))))
        m (- v' c)
        [r' g' b'] (cond
                     (< h' 60.0)  [c x 0.0]
                     (< h' 120.0) [x c 0.0]
                     (< h' 180.0) [0.0 c x]
                     (< h' 240.0) [0.0 x c]
                     (< h' 300.0) [x 0.0 c]
                     :else        [c 0.0 x])
        r'' (double r')
        g'' (double g')
        b'' (double b')]
    [(+ r'' m)
     (+ g'' m)
     (+ b'' m)]))

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
        h (double (cond
                    (zero? delta) 0.0
                    (= cmax r') (* 60.0 (double (mod (/ (- g' b') delta) 6.0)))
                    (= cmax g') (* 60.0 (+ (/ (- b' r') delta) 2.0))
                    :else (* 60.0 (+ (/ (- r' g') delta) 4.0))))
        s (if (zero? cmax) 0.0 (/ delta cmax))
        v cmax]
    [(if (neg? h) (+ h 360.0) h) s v]))




;;; Rainbow Color Generation (Normalized)



(defn rainbow-normalized
  "Get a rainbow color based on position (0.0 to 1.0).
   Returns normalized [r g b] vector (0.0-1.0)."
  [position]
  (hsv->normalized (* (double position) 360.0) 1.0 1.0))


;;; Color Interpolation (Normalized)



(defn lerp-color-normalized
  "Linear interpolation between two normalized colors.
   t should be in [0, 1]."
  [[r1 g1 b1] [r2 g2 b2] t]
  (let [r1' (double r1) g1' (double g1) b1' (double b1)
        r2' (double r2) g2' (double g2) b2' (double b2)
        t' (double t)]
    [(+ r1' (* (- r2' r1') t'))
     (+ g1' (* (- g2' g1') t'))
     (+ b1' (* (- b2' b1') t'))]))



;;; Oklab Color Space Conversions
;;; Perceptually uniform color space by Björn Ottosson



(defn srgb->linear
  "Convert sRGB component to linear RGB."
  ^double [^double c]
  (if (<= c 0.04045)
    (/ c 12.92)
    (Math/pow (/ (+ c 0.055) 1.055) 2.4)))

(defn linear->srgb
  "Convert linear RGB component to sRGB."
  ^double [^double c]
  (if (<= c 0.0031308)
    (* c 12.92)
    (- (* 1.055 (Math/pow c (/ 1.0 2.4))) 0.055)))

(defn rgb->oklab
  "Convert normalized RGB to Oklab.
   r, g, b: normalized sRGB values in [0.0, 1.0]
   Returns: [L a b]"
  [r g b]
  (let [r' (srgb->linear r)
        g' (srgb->linear g)
        b' (srgb->linear b)
        l (+ (* 0.4122214708 r') (* 0.5363325363 g') (* 0.0514459929 b'))
        m (+ (* 0.2119034982 r') (* 0.6806995451 g') (* 0.1073969566 b'))
        s (+ (* 0.0883024619 r') (* 0.2817188376 g') (* 0.6299787005 b'))
        l_ (Math/cbrt l)
        m_ (Math/cbrt m)
        s_ (Math/cbrt s)]
    [(+ (* 0.2104542553 l_) (* 0.7936177850 m_) (* -0.0040720468 s_))
     (+ (* 1.9779984951 l_) (* -2.4285922050 m_) (* 0.4505937099 s_))
     (+ (* 0.0259040371 l_) (* 0.7827717662 m_) (* -0.8086757660 s_))]))

(defn oklab->rgb
  "Convert Oklab to normalized RGB.
   L, a, b: Oklab coordinates
   Returns: [r g b] normalized sRGB values in [0.0, 1.0]"
  [L a b]
  (let [l_ (+ (double L) (* 0.3963377774 (double a)) (* 0.2158037573 (double b)))
        m_ (- (double L) (* 0.1055613458 (double a)) (* 0.0638541728 (double b)))
        s_ (- (double L) (* 0.0894841775 (double a)) (* 1.2914855480 (double b)))
        l (* l_ l_ l_)
        m (* m_ m_ m_)
        s (* s_ s_ s_)
        r (+ (* 4.0767416621 l) (* -3.3077115913 m) (* 0.2309699292 s))
        g (+ (* -1.2684380046 l) (* 2.6097574011 m) (* -0.3413193965 s))
        b (+ (* -0.0041960863 l) (* -0.7034186147 m) (* 1.7076147010 s))]
    [(linear->srgb r)
     (linear->srgb g)
     (linear->srgb b)]))

(defn oklab->oklch
  "Convert Oklab to Oklch (Lightness, Chroma, Hue).
   L, a, b: Oklab coordinates
   Returns: [L C h] where h is in degrees [0, 360]"
  [L a b]
  (let [C (Math/sqrt (+ (* (double a) (double a)) (* (double b) (double b))))
        h (Math/toDegrees (Math/atan2 (double b) (double a)))]
    [L C (if (neg? h) (+ h 360.0) h)]))

(defn oklch->oklab
  "Convert Oklch to Oklab.
   L, C: Lightness and Chroma
   h: Hue in degrees [0, 360]
   Returns: [L a b]"
  [L C h]
  (let [h-rad (Math/toRadians (double h))]
    [L (* (double C) (Math/cos h-rad)) (* (double C) (Math/sin h-rad))]))
