(ns laser-show.animation.effects.color
  "Color effects for laser frames.
   Includes hue shift, saturation, brightness, color filters, and color chase effects."
  (:require [laser-show.animation.effects :as fx]
            [laser-show.animation.time :as time]
            [laser-show.animation.colors :as colors]))

;; ============================================================================
;; Color Space Helpers
;; ============================================================================

(defn clamp-byte [v]
  (max 0 (min 255 (int v))))

;; ============================================================================
;; Hue Effects
;; ============================================================================

(defn- apply-hue-shift-static [frame _time-ms _bpm {:keys [degrees]}]
  (fx/transform-colors
   frame
   (fn [[r g b]]
     (let [[h s v] (colors/rgb->hsv r g b)
           new-h (mod (+ h degrees) 360)]
       (colors/hsv->rgb new-h s v)))))

(fx/register-effect!
 {:id :hue-shift
  :name "Hue Shift"
  :category :color
  :timing :static
  :parameters [{:key :degrees
                :label "Degrees"
                :type :float
                :default 0.0
                :min -180.0
                :max 180.0}]
  :apply-fn apply-hue-shift-static})

(defn- apply-hue-rotate [frame time-ms _bpm {:keys [speed]}]
  (let [elapsed-sec (/ time-ms 1000.0)
        degrees (mod (* elapsed-sec speed) 360)]
    (fx/transform-colors
     frame
     (fn [[r g b]]
       (let [[h s v] (colors/rgb->hsv r g b)
             new-h (mod (+ h degrees) 360)]
         (colors/hsv->rgb new-h s v))))))

(fx/register-effect!
 {:id :hue-rotate
  :name "Hue Rotate"
  :category :color
  :timing :seconds
  :parameters [{:key :speed
                :label "Speed (deg/sec)"
                :type :float
                :default 60.0
                :min 0.0
                :max 720.0}]
  :apply-fn apply-hue-rotate})

(defn- apply-hue-pulse [frame time-ms bpm {:keys [amount frequency]}]
  (let [phase (time/time->beat-phase time-ms bpm)
        shift (time/oscillate (- amount) amount (* phase frequency) :sine)]
    (fx/transform-colors
     frame
     (fn [[r g b]]
       (let [[h s v] (colors/rgb->hsv r g b)
             new-h (mod (+ h shift) 360)]
         (colors/hsv->rgb new-h s v))))))

(fx/register-effect!
 {:id :hue-pulse
  :name "Hue Pulse"
  :category :color
  :timing :bpm
  :parameters [{:key :amount
                :label "Amount (degrees)"
                :type :float
                :default 30.0
                :min 0.0
                :max 180.0}
               {:key :frequency
                :label "Frequency (cycles/beat)"
                :type :float
                :default 1.0
                :min 0.1
                :max 16.0}]
  :apply-fn apply-hue-pulse})

;; ============================================================================
;; Saturation Effects
;; ============================================================================

(defn- apply-saturation-static [frame _time-ms _bpm {:keys [amount]}]
  (fx/transform-colors
   frame
   (fn [[r g b]]
     (let [[h s v] (colors/rgb->hsv r g b)
           new-s (max 0.0 (min 1.0 (* s amount)))]
       (colors/hsv->rgb h new-s v)))))

(fx/register-effect!
 {:id :saturation
  :name "Saturation"
  :category :color
  :timing :static
  :parameters [{:key :amount
                :label "Amount"
                :type :float
                :default 1.0
                :min 0.0
                :max 2.0}]
  :apply-fn apply-saturation-static})

(defn- apply-saturation-pulse [frame time-ms bpm {:keys [min-sat max-sat frequency]}]
  (let [phase (time/time->beat-phase time-ms bpm)
        sat-mult (time/oscillate min-sat max-sat (* phase frequency) :sine)]
    (fx/transform-colors
     frame
     (fn [[r g b]]
       (let [[h s v] (colors/rgb->hsv r g b)
             new-s (max 0.0 (min 1.0 (* s sat-mult)))]
         (colors/hsv->rgb h new-s v))))))

(fx/register-effect!
 {:id :saturation-pulse
  :name "Saturation Pulse"
  :category :color
  :timing :bpm
  :parameters [{:key :min-sat
                :label "Min Saturation"
                :type :float
                :default 0.3
                :min 0.0
                :max 2.0}
               {:key :max-sat
                :label "Max Saturation"
                :type :float
                :default 1.0
                :min 0.0
                :max 2.0}
               {:key :frequency
                :label "Frequency (cycles/beat)"
                :type :float
                :default 1.0
                :min 0.1
                :max 16.0}]
  :apply-fn apply-saturation-pulse})

;; ============================================================================
;; Brightness Effects
;; ============================================================================

(defn- apply-brightness-static [frame _time-ms _bpm {:keys [amount]}]
  (fx/transform-colors
   frame
   (fn [[r g b]]
     [(clamp-byte (* r amount))
      (clamp-byte (* g amount))
      (clamp-byte (* b amount))])))

(fx/register-effect!
 {:id :brightness
  :name "Brightness"
  :category :color
  :timing :static
  :parameters [{:key :amount
                :label "Amount"
                :type :float
                :default 1.0
                :min 0.0
                :max 2.0}]
  :apply-fn apply-brightness-static})

;; ============================================================================
;; Color Filter Effects
;; ============================================================================

(defn- apply-color-filter [frame _time-ms _bpm {:keys [r-mult g-mult b-mult]}]
  (fx/transform-colors
   frame
   (fn [[r g b]]
     [(clamp-byte (* r r-mult))
      (clamp-byte (* g g-mult))
      (clamp-byte (* b b-mult))])))

(fx/register-effect!
 {:id :color-filter
  :name "Color Filter"
  :category :color
  :timing :static
  :parameters [{:key :r-mult
                :label "Red Multiplier"
                :type :float
                :default 1.0
                :min 0.0
                :max 2.0}
               {:key :g-mult
                :label "Green Multiplier"
                :type :float
                :default 1.0
                :min 0.0
                :max 2.0}
               {:key :b-mult
                :label "Blue Multiplier"
                :type :float
                :default 1.0
                :min 0.0
                :max 2.0}]
  :apply-fn apply-color-filter})

(defn- apply-tint [frame _time-ms _bpm {:keys [tint-r tint-g tint-b strength]}]
  (fx/transform-colors
   frame
   (fn [[r g b]]
     (let [inv-str (- 1.0 strength)]
       [(clamp-byte (+ (* r inv-str) (* tint-r strength)))
        (clamp-byte (+ (* g inv-str) (* tint-g strength)))
        (clamp-byte (+ (* b inv-str) (* tint-b strength)))]))))

(fx/register-effect!
 {:id :tint
  :name "Tint"
  :category :color
  :timing :static
  :parameters [{:key :tint-r
                :label "Tint Red"
                :type :int
                :default 255
                :min 0
                :max 255}
               {:key :tint-g
                :label "Tint Green"
                :type :int
                :default 255
                :min 0
                :max 255}
               {:key :tint-b
                :label "Tint Blue"
                :type :int
                :default 255
                :min 0
                :max 255}
               {:key :strength
                :label "Strength"
                :type :float
                :default 0.0
                :min 0.0
                :max 1.0}]
  :apply-fn apply-tint})

;; ============================================================================
;; Invert Effect
;; ============================================================================

(defn- apply-invert [frame _time-ms _bpm _params]
  (fx/transform-colors
   frame
   (fn [[r g b]]
     [(- 255 r)
      (- 255 g)
      (- 255 b)])))

(fx/register-effect!
 {:id :invert
  :name "Invert Colors"
  :category :color
  :timing :static
  :parameters []
  :apply-fn apply-invert})

;; ============================================================================
;; Grayscale Effect
;; ============================================================================

(defn- apply-grayscale [frame _time-ms _bpm {:keys [amount]}]
  (fx/transform-colors
   frame
   (fn [[r g b]]
     (let [gray (int (+ (* r 0.299) (* g 0.587) (* b 0.114)))
           inv-amt (- 1.0 amount)]
       [(clamp-byte (+ (* r inv-amt) (* gray amount)))
        (clamp-byte (+ (* g inv-amt) (* gray amount)))
        (clamp-byte (+ (* b inv-amt) (* gray amount)))]))))

(fx/register-effect!
 {:id :grayscale
  :name "Grayscale"
  :category :color
  :timing :static
  :parameters [{:key :amount
                :label "Amount"
                :type :float
                :default 1.0
                :min 0.0
                :max 1.0}]
  :apply-fn apply-grayscale})

;; ============================================================================
;; Color Chase Effects
;; ============================================================================

(def default-chase-colors
  [[255 0 0] [0 255 0] [0 0 255]])

(defn- apply-color-chase [frame time-ms bpm {:keys [color-list speed]}]
  (let [colors-to-use (or color-list default-chase-colors)
        num-colors (count colors-to-use)
        beats (time/ms->beats time-ms bpm)
        color-index (mod (int (* beats speed)) num-colors)
        [tr tg tb] (nth colors-to-use color-index)]
    (fx/transform-colors
     frame
     (fn [[r g b]]
       (let [gray (/ (+ r g b) 3.0)
             factor (/ gray 255.0)]
         [(clamp-byte (* tr factor))
          (clamp-byte (* tg factor))
          (clamp-byte (* tb factor))])))))

(fx/register-effect!
 {:id :color-chase
  :name "Color Chase"
  :category :color
  :timing :bpm
  :parameters [{:key :color-list
                :label "Colors"
                :type :color-list
                :default [[255 0 0] [0 255 0] [0 0 255]]}
               {:key :speed
                :label "Speed (colors/beat)"
                :type :float
                :default 1.0
                :min 0.1
                :max 16.0}]
  :apply-fn apply-color-chase})

;; ============================================================================
;; Strobe Color Effect
;; ============================================================================

(defn- apply-strobe-color [frame time-ms bpm {:keys [color1-r color1-g color1-b
                                                     color2-r color2-g color2-b
                                                     frequency]}]
  (let [phase (time/time->beat-phase time-ms bpm)
        use-color1? (< (mod (* phase frequency) 1.0) 0.5)
        [tr tg tb] (if use-color1?
                     [color1-r color1-g color1-b]
                     [color2-r color2-g color2-b])]
    (fx/transform-colors
     frame
     (fn [[r g b]]
       (let [brightness (/ (max r g b) 255.0)]
         [(clamp-byte (* tr brightness))
          (clamp-byte (* tg brightness))
          (clamp-byte (* tb brightness))])))))

(fx/register-effect!
 {:id :strobe-color
  :name "Strobe Color"
  :category :color
  :timing :bpm
  :parameters [{:key :color1-r
                :label "Color 1 Red"
                :type :int
                :default 255
                :min 0
                :max 255}
               {:key :color1-g
                :label "Color 1 Green"
                :type :int
                :default 0
                :min 0
                :max 255}
               {:key :color1-b
                :label "Color 1 Blue"
                :type :int
                :default 0
                :min 0
                :max 255}
               {:key :color2-r
                :label "Color 2 Red"
                :type :int
                :default 0
                :min 0
                :max 255}
               {:key :color2-g
                :label "Color 2 Green"
                :type :int
                :default 0
                :min 0
                :max 255}
               {:key :color2-b
                :label "Color 2 Blue"
                :type :int
                :default 255
                :min 0
                :max 255}
               {:key :frequency
                :label "Frequency (changes/beat)"
                :type :float
                :default 4.0
                :min 0.5
                :max 32.0}]
  :apply-fn apply-strobe-color})

;; ============================================================================
;; Rainbow Effect (position-based)
;; ============================================================================

(defn- apply-rainbow-position [frame time-ms _bpm {:keys [speed axis]}]
  (let [time-offset (mod (* (/ time-ms 1000.0) speed) 360)]
    (fx/transform-points
     frame
     (fn [point]
       (let [x (/ (:x point) 32767.0)
             y (/ (:y point) 32767.0)
             r (bit-and (:r point) 0xFF)
             g (bit-and (:g point) 0xFF)
             b (bit-and (:b point) 0xFF)]
         (if (and (zero? r) (zero? g) (zero? b))
           point
           (let [position (case axis
                            :x (/ (+ x 1.0) 2.0)
                            :y (/ (+ y 1.0) 2.0)
                            :radial (Math/sqrt (+ (* x x) (* y y)))
                            :angle (/ (+ (Math/atan2 y x) Math/PI) (* 2.0 Math/PI)))
                 brightness (/ (max r g b) 255.0)
                 hue (mod (+ (* position 360.0) time-offset) 360.0)
                 [nr ng nb] (colors/hsv->rgb hue 1.0 brightness)]
             (assoc point
                    :r (unchecked-byte nr)
                    :g (unchecked-byte ng)
                    :b (unchecked-byte nb)))))))))

(fx/register-effect!
 {:id :rainbow-position
  :name "Rainbow Position"
  :category :color
  :timing :seconds
  :parameters [{:key :speed
                :label "Speed (deg/sec)"
                :type :float
                :default 60.0
                :min 0.0
                :max 360.0}
               {:key :axis
                :label "Axis"
                :type :choice
                :default :x
                :choices [:x :y :radial :angle]}]
  :apply-fn apply-rainbow-position})

;; ============================================================================
;; Contrast Effect
;; ============================================================================

(defn- apply-contrast [frame _time-ms _bpm {:keys [amount]}]
  (let [factor (/ (+ 259.0 (* 255.0 amount)) (+ 255.0 (* 259.0 amount)))]
    (fx/transform-colors
     frame
     (fn [[r g b]]
       [(clamp-byte (+ 128 (* factor (- r 128))))
        (clamp-byte (+ 128 (* factor (- g 128))))
        (clamp-byte (+ 128 (* factor (- b 128))))]))))

(fx/register-effect!
 {:id :contrast
  :name "Contrast"
  :category :color
  :timing :static
  :parameters [{:key :amount
                :label "Amount"
                :type :float
                :default 0.0
                :min -1.0
                :max 1.0}]
  :apply-fn apply-contrast})

;; ============================================================================
;; Color Replace Effect
;; ============================================================================

(defn- color-distance [r1 g1 b1 r2 g2 b2]
  (Math/sqrt (+ (* (- r1 r2) (- r1 r2))
                (* (- g1 g2) (- g1 g2))
                (* (- b1 b2) (- b1 b2)))))

(defn- apply-color-replace [frame _time-ms _bpm {:keys [from-r from-g from-b
                                                        to-r to-g to-b
                                                        tolerance]}]
  (fx/transform-colors
   frame
   (fn [[r g b]]
     (if (<= (color-distance r g b from-r from-g from-b) tolerance)
       [to-r to-g to-b]
       [r g b]))))

(fx/register-effect!
 {:id :color-replace
  :name "Color Replace"
  :category :color
  :timing :static
  :parameters [{:key :from-r
                :label "From Red"
                :type :int
                :default 255
                :min 0
                :max 255}
               {:key :from-g
                :label "From Green"
                :type :int
                :default 0
                :min 0
                :max 255}
               {:key :from-b
                :label "From Blue"
                :type :int
                :default 0
                :min 0
                :max 255}
               {:key :to-r
                :label "To Red"
                :type :int
                :default 0
                :min 0
                :max 255}
               {:key :to-g
                :label "To Green"
                :type :int
                :default 255
                :min 0
                :max 255}
               {:key :to-b
                :label "To Blue"
                :type :int
                :default 0
                :min 0
                :max 255}
               {:key :tolerance
                :label "Tolerance"
                :type :float
                :default 30.0
                :min 0.0
                :max 255.0}]
  :apply-fn apply-color-replace})
