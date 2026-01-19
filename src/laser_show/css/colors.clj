(ns laser-show.css.colors
  "Color manipulation utilities for deriving color variants.
   
   All functions work with hex color strings and return hex color strings.
   These utilities enable automatic generation of hover/active/disabled
   state variants from base colors, reducing the need for hardcoded color
   values throughout the application.
   
   Usage:
     (lighten \"#4CAF50\" 0.1)  ; 10% lighter
     (darken \"#4CAF50\" 0.1)   ; 10% darker
     (with-alpha \"#4CAF50\" 0.5) ; 50% opacity")


;; =============================================================================
;; Color Parsing and Conversion
;; =============================================================================

(defn parse-hex
  "Parse a hex color string to RGB components.
   Accepts formats: #RGB, #RRGGBB, #RRGGBBAA
   Returns {:r 0-255 :g 0-255 :b 0-255 :a 0-255}"
  [hex-str]
  (let [hex (if (= \# (first hex-str)) (subs hex-str 1) hex-str)
        len (count hex)]
    (case len
      3 {:r (Integer/parseInt (str (nth hex 0) (nth hex 0)) 16)
         :g (Integer/parseInt (str (nth hex 1) (nth hex 1)) 16)
         :b (Integer/parseInt (str (nth hex 2) (nth hex 2)) 16)
         :a 255}
      6 {:r (Integer/parseInt (subs hex 0 2) 16)
         :g (Integer/parseInt (subs hex 2 4) 16)
         :b (Integer/parseInt (subs hex 4 6) 16)
         :a 255}
      8 {:r (Integer/parseInt (subs hex 0 2) 16)
         :g (Integer/parseInt (subs hex 2 4) 16)
         :b (Integer/parseInt (subs hex 4 6) 16)
         :a (Integer/parseInt (subs hex 6 8) 16)}
      ;; Fallback for invalid input
      {:r 0 :g 0 :b 0 :a 255})))

(defn to-hex
  "Convert RGB(A) map to hex string.
   Input: {:r 0-255 :g 0-255 :b 0-255} or {:r :g :b :a}
   Returns: #RRGGBB or #RRGGBBAA if alpha is not 255"
  [{:keys [r g b a]}]
  (let [r (int (min 255 (max 0 r)))
        g (int (min 255 (max 0 g)))
        b (int (min 255 (max 0 b)))
        a (if a (int (min 255 (max 0 a))) 255)]
    (if (= a 255)
      (format "#%02X%02X%02X" r g b)
      (format "#%02X%02X%02X%02X" r g b a))))


;; =============================================================================
;; RGB <-> HSL Conversion (needed for proper lightness adjustment)
;; =============================================================================

(defn- rgb->hsl
  "Convert RGB to HSL color space.
   Input: {:r 0-255 :g 0-255 :b 0-255}
   Returns: {:h 0-360 :s 0-1 :l 0-1}"
  [{:keys [r g b]}]
  (let [r (/ r 255.0)
        g (/ g 255.0)
        b (/ b 255.0)
        max-c (max r g b)
        min-c (min r g b)
        l (/ (+ max-c min-c) 2.0)
        d (- max-c min-c)]
    (if (zero? d)
      {:h 0 :s 0 :l l}
      (let [s (if (> l 0.5)
                (/ d (- 2.0 max-c min-c))
                (/ d (+ max-c min-c)))
            h (cond
                (= max-c r) (mod (/ (- g b) d) 6)
                (= max-c g) (+ (/ (- b r) d) 2)
                :else       (+ (/ (- r g) d) 4))
            h (* h 60)]
        {:h (if (neg? h) (+ h 360) h)
         :s s
         :l l}))))

(defn- hue->rgb
  "Helper for HSL->RGB conversion"
  [p q t]
  (let [t (cond (< t 0) (+ t 1)
                (> t 1) (- t 1)
                :else t)]
    (cond
      (< t (/ 1 6.0)) (+ p (* (- q p) 6 t))
      (< t 0.5)       q
      (< t (/ 2 3.0)) (+ p (* (- q p) (- (/ 2 3.0) t) 6))
      :else           p)))

(defn- hsl->rgb
  "Convert HSL to RGB color space.
   Input: {:h 0-360 :s 0-1 :l 0-1}
   Returns: {:r 0-255 :g 0-255 :b 0-255}"
  [{:keys [h s l]}]
  (if (zero? s)
    (let [v (int (* l 255))]
      {:r v :g v :b v})
    (let [h (/ h 360.0)
          q (if (< l 0.5)
              (* l (+ 1 s))
              (- (+ l s) (* l s)))
          p (- (* 2 l) q)]
      {:r (int (* 255 (hue->rgb p q (+ h (/ 1 3.0)))))
       :g (int (* 255 (hue->rgb p q h)))
       :b (int (* 255 (hue->rgb p q (- h (/ 1 3.0)))))})))


;; =============================================================================
;; Brightness Adjustment Functions
;; =============================================================================

(defn lighten
  "Make a color lighter by the given amount.
   
   Parameters:
     hex-str - Hex color string (e.g., \"#4CAF50\")
     amount  - Amount to lighten, 0.0-1.0 (e.g., 0.1 = 10% lighter)
   
   Returns: Lightened hex color string"
  [hex-str amount]
  (let [rgb (parse-hex hex-str)
        hsl (rgb->hsl rgb)
        new-l (min 1.0 (+ (:l hsl) (* amount (- 1.0 (:l hsl)))))
        new-rgb (hsl->rgb (assoc hsl :l new-l))]
    (to-hex (assoc new-rgb :a (:a rgb)))))

(defn darken
  "Make a color darker by the given amount.
   
   Parameters:
     hex-str - Hex color string (e.g., \"#4CAF50\")
     amount  - Amount to darken, 0.0-1.0 (e.g., 0.1 = 10% darker)
   
   Returns: Darkened hex color string"
  [hex-str amount]
  (let [rgb (parse-hex hex-str)
        hsl (rgb->hsl rgb)
        new-l (max 0.0 (* (:l hsl) (- 1.0 amount)))
        new-rgb (hsl->rgb (assoc hsl :l new-l))]
    (to-hex (assoc new-rgb :a (:a rgb)))))


;; =============================================================================
;; Alpha/Transparency Functions
;; =============================================================================

(defn with-alpha
  "Add or modify the alpha channel of a color.
   
   Parameters:
     hex-str - Hex color string (e.g., \"#4CAF50\")
     alpha   - Alpha value, 0.0-1.0 (0 = transparent, 1 = opaque)
   
   Returns: Hex color string with alpha (#RRGGBBAA)"
  [hex-str alpha]
  (let [rgb (parse-hex hex-str)
        a (int (* alpha 255))]
    (to-hex (assoc rgb :a a))))


(defn desaturate
  "Reduce the saturation of a color (make it more gray).
   Useful for disabled states.
   
   Parameters:
     hex-str - Hex color string (e.g., \"#4CAF50\")
     amount  - Amount to desaturate, 0.0-1.0 (1.0 = fully grayscale)
   
   Returns: Desaturated hex color string"
  [hex-str amount]
  (let [rgb (parse-hex hex-str)
        hsl (rgb->hsl rgb)
        new-s (max 0.0 (* (:s hsl) (- 1.0 amount)))
        new-rgb (hsl->rgb (assoc hsl :s new-s))]
    (to-hex (assoc new-rgb :a (:a rgb)))))

