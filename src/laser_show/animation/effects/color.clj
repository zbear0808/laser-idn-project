(ns laser-show.animation.effects.color
  "Color effects for laser frames.
   Includes hue shift, saturation, set hue, color filter, color replace, set color, and more.
   
   NOTE: All color values are NORMALIZED (0.0-1.0), not 8-bit (0-255).
   This provides full precision for color calculations.
   
   Note: For grayscale effect, use :saturation with amount=0.
   For brightness control, use :intensity effect from intensity.clj.
   
   All effects support both static values and modulators:
   ;; Static
   {:effect-id :hue-shift :params {:degrees 30}}
   
   ;; Modulated (using modulation.clj)
   (require '[laser-show.animation.modulation :as mod])
   {:effect-id :hue-shift :params {:degrees (mod/sine-mod -30 30 2.0)}}
   
   For animated hue rotation, use:
   {:effect-id :hue-shift :params {:degrees (mod/sawtooth-mod 0 360 1.0)}}
   
   Per-point modulation:
   {:effect-id :set-hue :params {:hue (mod/position-x-mod 0 360)}}  ; Rainbow gradient
   {:effect-id :set-hue :params {:hue (mod/rainbow-hue :x 60.0)}}  ; Animated rainbow
   
   Points are 5-element vectors [x y r g b]. Access via t/X, t/Y, t/R, t/G, t/B.
   Use t/update-point-rgb for color updates."
  (:require [laser-show.animation.effects :as effects]
            [laser-show.animation.effects.common :as common]
            [laser-show.animation.colors :as colors]
            [laser-show.animation.modulation :as mod]
            [laser-show.animation.types :as t]))


;; Hue Shift Effect


(defn- hue-shift-xf [time-ms bpm params ctx]
  ;; Check if any params use per-point modulators
  (if (mod/any-param-requires-per-point? params)
    ;; Per-point path - use map-indexed to get idx
    (let [point-count (:point-count ctx)]
      (map-indexed
       (fn [idx pt]
         (if (t/blanked? pt)
           pt  ;; Skip blanked points
           (let [x (pt t/X) y (pt t/Y)
                 r (pt t/R) g (pt t/G) b (pt t/B)
                 resolved (effects/resolve-params-for-point params time-ms bpm x y idx point-count (:timing-ctx ctx))
                 degrees (:degrees resolved)
                 [h s v] (colors/normalized->hsv r g b)
                 new-h (mod (+ h degrees) 360)
                 [nr ng nb] (colors/hsv->normalized new-h s v)]
             (t/update-point-rgb pt nr ng nb))))))
    ;; Global path
    (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
          degrees (:degrees resolved)]
      (map (fn [pt]
             (if (t/blanked? pt)
               pt  ;; Skip blanked points
               (let [r (pt t/R) g (pt t/G) b (pt t/B)
                     [h s v] (colors/normalized->hsv r g b)
                     new-h (mod (+ h degrees) 360)
                     [nr ng nb] (colors/hsv->normalized new-h s v)]
                 (t/update-point-rgb pt nr ng nb))))))))

(effects/register-effect!
 {:id :hue-shift
  :name "Hue Shift"
  :category :color
  :timing :static
  :ui-hints {:renderer :hue-shift-strip
             :default-mode :visual}
  :parameters [{:key :degrees
                :label "Degrees"
                :type :float
                :default 0.0
                :min -180.0
                :max 180.0}]
  :apply-transducer hue-shift-xf})


;; Saturation Effect


(defn- saturation-xf [time-ms bpm params ctx]
  ;; Check if any params use per-point modulators
  (if (mod/any-param-requires-per-point? params)
    ;; Per-point path - use map-indexed to get idx
    (let [point-count (:point-count ctx)]
      (map-indexed
       (fn [idx pt]
         (if (t/blanked? pt)
           pt  ;; Skip blanked points
           (let [x (pt t/X) y (pt t/Y)
                 r (pt t/R) g (pt t/G) b (pt t/B)
                 resolved (effects/resolve-params-for-point params time-ms bpm x y idx point-count (:timing-ctx ctx))
                 amount (:amount resolved)
                 [h s v] (colors/normalized->hsv r g b)
                 new-s (common/clamp-normalized (* s amount))
                 [nr ng nb] (colors/hsv->normalized h new-s v)]
             (t/update-point-rgb pt nr ng nb))))))
    ;; Global path
    (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
          amount (:amount resolved)]
      (map (fn [pt]
             (if (t/blanked? pt)
               pt  ;; Skip blanked points
               (let [r (pt t/R) g (pt t/G) b (pt t/B)
                     [h s v] (colors/normalized->hsv r g b)
                     new-s (common/clamp-normalized (* s amount))
                     [nr ng nb] (colors/hsv->normalized h new-s v)]
                 (t/update-point-rgb pt nr ng nb))))))))

(effects/register-effect!
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
  :apply-transducer saturation-xf})


;; Color Filter Effect


(defn- color-filter-xf [time-ms bpm params ctx]
  ;; Check if any params use per-point modulators
  (if (mod/any-param-requires-per-point? params)
    ;; Per-point path - use map-indexed to get idx
    (let [point-count (:point-count ctx)]
      (map-indexed
       (fn [idx pt]
         (let [x (pt t/X) y (pt t/Y)
               r (pt t/R) g (pt t/G) b (pt t/B)
               resolved (effects/resolve-params-for-point params time-ms bpm x y idx point-count (:timing-ctx ctx))
               r-mult (:r-mult resolved)
               g-mult (:g-mult resolved)
               b-mult (:b-mult resolved)]
           (t/update-point-rgb pt
             (common/clamp-normalized (* r r-mult))
             (common/clamp-normalized (* g g-mult))
             (common/clamp-normalized (* b b-mult)))))))
    ;; Global path
    (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
          r-mult (:r-mult resolved)
          g-mult (:g-mult resolved)
          b-mult (:b-mult resolved)]
      (map (fn [pt]
             (let [r (pt t/R) g (pt t/G) b (pt t/B)]
               (t/update-point-rgb pt
                 (common/clamp-normalized (* r r-mult))
                 (common/clamp-normalized (* g g-mult))
                 (common/clamp-normalized (* b b-mult)))))))))

(effects/register-effect!
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
  :apply-transducer color-filter-xf})


;; Set Hue Effect (sets all points to specific hue, preserving saturation/value)


(defn- set-hue-xf [time-ms bpm params ctx]
  ;; Check if any params use per-point modulators
  (if (mod/any-param-requires-per-point? params)
    ;; Per-point path - enables rainbow gradients! Use map-indexed to get idx
    (let [point-count (:point-count ctx)]
      (map-indexed
       (fn [idx pt]
         (let [r (pt t/R) g (pt t/G) b (pt t/B)
               [_h s v] (colors/normalized->hsv r g b)]
           ;; Only apply to non-black points with some saturation
           (if (and (pos? v) (not (t/blanked? pt)))
             (let [x (pt t/X) y (pt t/Y)
                   resolved (effects/resolve-params-for-point params time-ms bpm x y idx point-count (:timing-ctx ctx))
                   hue (:hue resolved)
                   [nr ng nb] (colors/hsv->normalized hue s v)]
               (t/update-point-rgb pt nr ng nb))
             pt)))))
    ;; Global path
    (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
          hue (:hue resolved)]
      (map (fn [pt]
             (let [r (pt t/R) g (pt t/G) b (pt t/B)
                   [_h s v] (colors/normalized->hsv r g b)]
               ;; Only apply to non-black points with some saturation
               (if (and (pos? v) (not (t/blanked? pt)))
                 (let [[nr ng nb] (colors/hsv->normalized hue s v)]
                   (t/update-point-rgb pt nr ng nb))
                 pt)))))))

(effects/register-effect!
 {:id :set-hue
  :name "Set Hue"
  :category :color
  :timing :static
  :ui-hints {:renderer :hue-slider
             :default-mode :visual}
  :parameters [{:key :hue
                :label "Hue"
                :type :float
                :default 0.0
                :min 0.0
                :max 360.0}]
  :apply-transducer set-hue-xf})


;; Color Replace Effect


(defn- color-distance-normalized
  "Calculate color distance with normalized values (0.0-1.0)."
  [r1 g1 b1 r2 g2 b2]
  (Math/sqrt (+ (* (- r1 r2) (- r1 r2))
                (* (- g1 g2) (- g1 g2))
                (* (- b1 b2) (- b1 b2)))))

(defn- color-replace-xf [time-ms bpm params ctx]
  (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
        ;; All values are already normalized 0.0-1.0
        from-r (:from-r resolved)
        from-g (:from-g resolved)
        from-b (:from-b resolved)
        to-r (:to-r resolved)
        to-g (:to-g resolved)
        to-b (:to-b resolved)
        ;; Tolerance in normalized space (max ~1.73 for full RGB distance)
        tolerance (:tolerance resolved)]
    (map (fn [pt]
           (let [r (pt t/R) g (pt t/G) b (pt t/B)]
             (if (<= (color-distance-normalized r g b from-r from-g from-b) tolerance)
               (t/update-point-rgb pt to-r to-g to-b)
               pt))))))

(effects/register-effect!
 {:id :color-replace
  :name "Color Replace"
  :category :color
  :timing :static
  :parameters [{:key :from-r
                :label "From Red"
                :type :float
                :default 1.0
                :min 0.0
                :max 1.0}
               {:key :from-g
                :label "From Green"
                :type :float
                :default 0.0
                :min 0.0
                :max 1.0}
               {:key :from-b
                :label "From Blue"
                :type :float
                :default 0.0
                :min 0.0
                :max 1.0}
               {:key :to-r
                :label "To Red"
                :type :float
                :default 0.0
                :min 0.0
                :max 1.0}
               {:key :to-g
                :label "To Green"
                :type :float
                :default 1.0
                :min 0.0
                :max 1.0}
               {:key :to-b
                :label "To Blue"
                :type :float
                :default 0.0
                :min 0.0
                :max 1.0}
               {:key :tolerance
                :label "Tolerance"
                :type :float
                :default 0.12
                :min 0.0
                :max 1.73}]
  :apply-transducer color-replace-xf})


;; Set Color Effect (replaces color of ALL points)


(defn- set-color-xf [time-ms bpm params ctx]
  ;; Check if any params use per-point modulators (for position-based color gradients)
  (if (mod/any-param-requires-per-point? params)
    ;; Per-point path - supports position-based modulators on individual color channels
    ;; Use map-indexed to get idx
    (let [point-count (:point-count ctx)]
      (map-indexed
       (fn [idx pt]
         (if (t/blanked? pt)
           pt
           (let [x (pt t/X) y (pt t/Y)
                 resolved (effects/resolve-params-for-point params time-ms bpm x y idx point-count (:timing-ctx ctx))
                 red (:red resolved)
                 green (:green resolved)
                 blue (:blue resolved)]
             (t/update-point-rgb pt red green blue))))))
    ;; Global path - no per-point modulators
    (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
          red (:red resolved)
          green (:green resolved)
          blue (:blue resolved)]
      (map (fn [pt]
             (if (t/blanked? pt)
               pt
               (t/update-point-rgb pt red green blue)))))))

(effects/register-effect!
 {:id :set-color
  :name "Set Color"
  :category :color
  :timing :static
  :ui-hints {:renderer :set-color-picker
             :default-mode :visual}
  :parameters [{:key :red
                :label "Red"
                :type :float
                :default 1.0
                :min 0.0
                :max 1.0}
               {:key :green
                :label "Green"
                :type :float
                :default 1.0
                :min 0.0
                :max 1.0}
               {:key :blue
                :label "Blue"
                :type :float
                :default 1.0
                :min 0.0
                :max 1.0}]
  :apply-transducer set-color-xf})


;; SPECIAL EFFECTS
;; These are more complex effects kept for specific use cases.
;; Consider using modulators with basic effects for more flexibility.



;; Rainbow Position Effect (Special)
;; Creates a rainbow effect based on point position.
;; For more control, use hue-shift with rainbow-hue modulator.


(defn- rainbow-position-xf [time-ms bpm params ctx]
  (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
        speed (:speed resolved)
        axis (:axis resolved)
        time-offset (mod (* (/ time-ms 1000.0) speed) 360)]
    (map (fn [pt]
           (if (t/blanked? pt)
             pt
             (let [x (pt t/X) y (pt t/Y)
                   r (pt t/R) g (pt t/G) b (pt t/B)
                   position (case axis
                              :x (/ (+ x 1.0) 2.0)
                              :y (/ (+ y 1.0) 2.0)
                              :radial (Math/sqrt (+ (* x x) (* y y)))
                              :angle (/ (+ (Math/atan2 y x) Math/PI) (* 2.0 Math/PI)))
                   ;; Use current brightness (value from HSV)
                   brightness (max r g b)
                   hue (mod (+ (* position 360.0) time-offset) 360.0)
                   [nr ng nb] (colors/hsv->normalized hue 1.0 brightness)]
               (t/update-point-rgb pt nr ng nb)))))))

(effects/register-effect!
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
  :apply-transducer rainbow-position-xf})
