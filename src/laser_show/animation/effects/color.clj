(ns laser-show.animation.effects.color
  "Color effects for laser frames.
   Includes hue shift, saturation, set hue, color filter, color replace, set color, and more.
   
   Note: For grayscale effect, use :saturation with amount=0.
   For brightness control, use :intensity effect from intensity.clj.
   
   All effects support both static values and modulators:
   ;; Static
   {:effect-id :hue-shift :params {:degrees 30}}
   
   ;; Modulated (using modulation.clj)
   (require '[laser-show.animation.modulation :as mod])
   {:effect-id :hue-shift :params {:degrees (mod/sine-mod -30 30 2.0)}}
   
   For animated hue rotation, use:
   {:effect-id :hue-shift :params {:degrees (mod/sawtooth-mod 0 360 1.0)}}"
  (:require [laser-show.animation.effects :as fx]
            [laser-show.animation.effects.common :as common]
            [laser-show.animation.colors :as colors]))

;; ============================================================================
;; Hue Shift Effect
;; ============================================================================

(defn- apply-hue-shift [frame _time-ms _bpm {:keys [degrees]}]
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
  :apply-fn apply-hue-shift})

;; ============================================================================
;; Saturation Effect
;; ============================================================================

(defn- apply-saturation [frame _time-ms _bpm {:keys [amount]}]
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
  :apply-fn apply-saturation})

;; ============================================================================
;; Color Filter Effect
;; ============================================================================

(defn- apply-color-filter [frame _time-ms _bpm {:keys [r-mult g-mult b-mult]}]
  (fx/transform-colors
   frame
   (fn [[r g b]]
     [(common/clamp-byte (* r r-mult))
      (common/clamp-byte (* g g-mult))
      (common/clamp-byte (* b b-mult))])))

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
;; Set Hue Effect (sets all points to specific hue, preserving saturation/value)
;; ============================================================================

(defn- apply-set-hue [frame _time-ms _bpm {:keys [hue]}]
  (fx/transform-colors
   frame
   (fn [[r g b]]
     (let [[_h s v] (colors/rgb->hsv r g b)]
       ;; Only apply to non-black points with some saturation
       (if (and (pos? v) (or (pos? r) (pos? g) (pos? b)))
         (colors/hsv->rgb hue s v)
         [r g b])))))

(fx/register-effect!
 {:id :set-hue
  :name "Set Hue"
  :category :color
  :timing :static
  :parameters [{:key :hue
                :label "Hue"
                :type :float
                :default 0.0
                :min 0.0
                :max 360.0}]
  :apply-fn apply-set-hue})

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

;; ============================================================================
;; Set Color Effect (replaces color of ALL points)
;; ============================================================================

(defn- apply-set-color [frame _time-ms _bpm {:keys [red green blue]}]
  (fx/transform-colors
   frame
   (fn [[r g b]]
     (if (or (pos? r) (pos? g) (pos? b))
       [red green blue]
       [0 0 0]))))

(fx/register-effect!
 {:id :set-color
  :name "Set Color"
  :category :color
  :timing :static
  :parameters [{:key :red
                :label "Red"
                :type :int
                :default 255
                :min 0
                :max 255}
               {:key :green
                :label "Green"
                :type :int
                :default 255
                :min 0
                :max 255}
               {:key :blue
                :label "Blue"
                :type :int
                :default 255
                :min 0
                :max 255}]
  :apply-fn apply-set-color})

;; ============================================================================
;; SPECIAL EFFECTS
;; These are more complex effects kept for specific use cases.
;; Consider using modulators with basic effects for more flexibility.
;; ============================================================================

;; ============================================================================
;; Rainbow Position Effect (Special)
;; Creates a rainbow effect based on point position.
;; For more control, use hue-shift with rainbow-hue modulator.
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
  :name "Rainbow Position (Special)"
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
