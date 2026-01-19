(ns laser-show.animation.effects.color
  "Color effects for laser frames.
   Includes hue shift, saturation, set hue, color filter, color replace, set color, and more.
   These effects mutate frames in place for maximum performance.
   
   NOTE: All color values are NORMALIZED (0.0-1.0), not 8-bit (0-255).
   This provides full precision for color calculations.
   
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
   {:effect-id :set-hue :params {:hue (mod/rainbow-hue :x 60.0)}}  ; Animated rainbow"
  (:require [laser-show.animation.effects :as effects]
            [laser-show.animation.colors :as colors]
            [laser-show.animation.modulation :as mod]
            [laser-show.animation.types :as t]))

(set! *unchecked-math* :warn-on-boxed)

;; Helper to check if point is blanked
(defn- point-blanked?
  "Check if point at index is blanked (all colors near zero)."
  [^"[[D" frame ^long idx]
  (let [epsilon (double 1e-6)
        r (double (aget frame idx t/R))
        g (double (aget frame idx t/G))
        b (double (aget frame idx t/B))]
    (and (< r epsilon)
         (< g epsilon)
         (< b epsilon))))


;; ========== Hue Shift Effect ==========

(defn- hue-shift-fn!
  "Shift hue of all points in place."
  [^"[[D" frame time-ms bpm params ctx]
  (let [n (alength frame)
        timing-ctx (:timing-ctx ctx)]
    (if (mod/any-param-requires-per-point? params)
      ;; Per-point path - create base context once
      (let [base-ctx (effects/make-base-context-for-frame time-ms bpm n timing-ctx)]
        (dotimes [i n]
          (when-not (point-blanked? frame i)
            (let [x (aget frame i t/X)
                  y (aget frame i t/Y)
                  r (aget frame i t/R)
                  g (aget frame i t/G)
                  b (aget frame i t/B)
                  resolved (effects/resolve-params-for-point-fast params base-ctx x y i)
                  degrees (double (:degrees resolved))
                  [h s v] (colors/normalized->hsv r g b)
                  new-h (mod (+ (double h) degrees) 360.0)
                  [nr ng nb] (colors/hsv->normalized new-h s v)]
              (aset-double frame i t/R nr)
              (aset-double frame i t/G ng)
              (aset-double frame i t/B nb)))))
      ;; Global path - resolve params once before loop
      (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
            degrees (double (:degrees resolved))]
        (dotimes [i n]
          (when-not (point-blanked? frame i)
            (let [r (aget frame i t/R)
                  g (aget frame i t/G)
                  b (aget frame i t/B)
                  [h s v] (colors/normalized->hsv r g b)
                  new-h (mod (+ (double h) degrees) 360.0)
                  [nr ng nb] (colors/hsv->normalized new-h s v)]
              (aset-double frame i t/R nr)
              (aset-double frame i t/G ng)
              (aset-double frame i t/B nb)))))))
  frame)

(effects/register-effect!
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
  :apply-fn! hue-shift-fn!})


;; ========== Saturation Effect ==========

(defn- saturation-fn!
  "Adjust saturation of all points in place."
  [^"[[D" frame time-ms bpm params ctx]
  (let [n (alength frame)
        timing-ctx (:timing-ctx ctx)]
    (if (mod/any-param-requires-per-point? params)
      ;; Per-point path - create base context once
      (let [base-ctx (effects/make-base-context-for-frame time-ms bpm n timing-ctx)]
        (dotimes [i n]
          (when-not (point-blanked? frame i)
            (let [x (aget frame i t/X)
                  y (aget frame i t/Y)
                  r (aget frame i t/R)
                  g (aget frame i t/G)
                  b (aget frame i t/B)
                  resolved (effects/resolve-params-for-point-fast params base-ctx x y i)
                  amount (double (:amount resolved))
                  [h s v] (colors/normalized->hsv r g b)
                  new-s (max 0.0 (min 1.0 (* (double s) amount)))
                  [nr ng nb] (colors/hsv->normalized h new-s v)]
              (aset-double frame i t/R nr)
              (aset-double frame i t/G ng)
              (aset-double frame i t/B nb)))))
      ;; Global path - resolve params once before loop
      (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
            amount (double (:amount resolved))]
        (dotimes [i n]
          (when-not (point-blanked? frame i)
            (let [r (aget frame i t/R)
                  g (aget frame i t/G)
                  b (aget frame i t/B)
                  [h s v] (colors/normalized->hsv r g b)
                  new-s (max 0.0 (min 1.0 (* (double s) amount)))
                  [nr ng nb] (colors/hsv->normalized h new-s v)]
              (aset-double frame i t/R nr)
              (aset-double frame i t/G ng)
              (aset-double frame i t/B nb)))))))
  frame)

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
  :apply-fn! saturation-fn!})


;; ========== Color Filter Effect ==========

(defn- color-filter-fn!
  "Apply RGB multipliers to all points in place."
  [^"[[D" frame time-ms bpm params ctx]
  (let [n (alength frame)
        timing-ctx (:timing-ctx ctx)]
    (if (mod/any-param-requires-per-point? params)
      ;; Per-point path - create base context once
      (let [base-ctx (effects/make-base-context-for-frame time-ms bpm n timing-ctx)]
        (dotimes [i n]
          (let [x (aget frame i t/X)
                y (aget frame i t/Y)
                resolved (effects/resolve-params-for-point-fast params base-ctx x y i)
                r-mult (double (:r-mult resolved))
                g-mult (double (:g-mult resolved))
                b-mult (double (:b-mult resolved))
                r (double (aget frame i t/R))
                g (double (aget frame i t/G))
                b (double (aget frame i t/B))]
            (aset-double frame i t/R (max 0.0 (min 1.0 (* r r-mult))))
            (aset-double frame i t/G (max 0.0 (min 1.0 (* g g-mult))))
            (aset-double frame i t/B (max 0.0 (min 1.0 (* b b-mult)))))))
      ;; Global path - resolve params once before loop
      (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
            r-mult (double (:r-mult resolved))
            g-mult (double (:g-mult resolved))
            b-mult (double (:b-mult resolved))]
        (dotimes [i n]
          (let [r (double (aget frame i t/R))
                g (double (aget frame i t/G))
                b (double (aget frame i t/B))]
            (aset-double frame i t/R (max 0.0 (min 1.0 (* r r-mult))))
            (aset-double frame i t/G (max 0.0 (min 1.0 (* g g-mult))))
            (aset-double frame i t/B (max 0.0 (min 1.0 (* b b-mult)))))))))
  frame)

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
  :apply-fn! color-filter-fn!})


;; ========== Set Hue Effect ==========

(defn- set-hue-fn!
  "Set hue of all points, preserving saturation/value, in place."
  [^"[[D" frame time-ms bpm params ctx]
  (let [n (alength frame)
        timing-ctx (:timing-ctx ctx)]
    (if (mod/any-param-requires-per-point? params)
      ;; Per-point path - create base context once, enables rainbow gradients!
      (let [base-ctx (effects/make-base-context-for-frame time-ms bpm n timing-ctx)]
        (dotimes [i n]
          (when-not (point-blanked? frame i)
            (let [r (aget frame i t/R)
                  g (aget frame i t/G)
                  b (aget frame i t/B)
                  [_h s v] (colors/normalized->hsv r g b)]
              ;; Only apply to non-black points with some value
              (when (pos? (double v))
                (let [x (aget frame i t/X)
                      y (aget frame i t/Y)
                      resolved (effects/resolve-params-for-point-fast params base-ctx x y i)
                      hue (double (:hue resolved))
                      [nr ng nb] (colors/hsv->normalized hue s v)]
                  (aset-double frame i t/R nr)
                  (aset-double frame i t/G ng)
                  (aset-double frame i t/B nb)))))))
      ;; Global path - resolve params once before loop
      (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
            hue (double (:hue resolved))]
        (dotimes [i n]
          (when-not (point-blanked? frame i)
            (let [r (aget frame i t/R)
                  g (aget frame i t/G)
                  b (aget frame i t/B)
                  [_h s v] (colors/normalized->hsv r g b)]
              ;; Only apply to non-black points with some value
              (when (pos? (double v))
                (let [[nr ng nb] (colors/hsv->normalized hue s v)]
                  (aset-double frame i t/R nr)
                  (aset-double frame i t/G ng)
                  (aset-double frame i t/B nb)))))))))
  frame)

(effects/register-effect!
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
  :apply-fn! set-hue-fn!})


;; ========== Color Replace Effect ==========

(defn- color-distance-normalized
  "Calculate color distance with normalized values (0.0-1.0)."
  [r1 g1 b1 r2 g2 b2]
  (let [r1 (double r1) g1 (double g1) b1 (double b1)
        r2 (double r2) g2 (double g2) b2 (double b2)]
    (Math/sqrt (+ (* (- r1 r2) (- r1 r2))
                  (* (- g1 g2) (- g1 g2))
                  (* (- b1 b2) (- b1 b2))))))

(defn- color-replace-fn!
  "Replace colors within tolerance in place."
  [^"[[D" frame time-ms bpm params ctx]
  (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
        from-r (double (:from-r resolved))
        from-g (double (:from-g resolved))
        from-b (double (:from-b resolved))
        to-r (double (:to-r resolved))
        to-g (double (:to-g resolved))
        to-b (double (:to-b resolved))
        tolerance (double (:tolerance resolved))
        n (alength frame)]
    (dotimes [i n]
      (let [r (aget frame i t/R)
            g (aget frame i t/G)
            b (aget frame i t/B)
            distance (double (color-distance-normalized r g b from-r from-g from-b))]
        (when (<= distance tolerance)
          (aset-double frame i t/R to-r)
          (aset-double frame i t/G to-g)
          (aset-double frame i t/B to-b)))))
  frame)

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
  :apply-fn! color-replace-fn!})


;; ========== Set Color Effect ==========

(defn- set-color-fn!
  "Set color of ALL non-blanked points in place."
  [^"[[D" frame time-ms bpm params ctx]
  (let [n (alength frame)
        timing-ctx (:timing-ctx ctx)]
    (if (mod/any-param-requires-per-point? params)
      ;; Per-point path - create base context once, enables position-based color gradients
      (let [base-ctx (effects/make-base-context-for-frame time-ms bpm n timing-ctx)]
        (dotimes [i n]
          (when-not (point-blanked? frame i)
            (let [x (aget frame i t/X)
                  y (aget frame i t/Y)
                  resolved (effects/resolve-params-for-point-fast params base-ctx x y i)
                  red (double (:red resolved))
                  green (double (:green resolved))
                  blue (double (:blue resolved))]
              (aset-double frame i t/R red)
              (aset-double frame i t/G green)
              (aset-double frame i t/B blue)))))
      ;; Global path - resolve params once before loop
      (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
            red (double (:red resolved))
            green (double (:green resolved))
            blue (double (:blue resolved))]
        (dotimes [i n]
          (when-not (point-blanked? frame i)
            (aset-double frame i t/R red)
            (aset-double frame i t/G green)
            (aset-double frame i t/B blue))))))
  frame)

(effects/register-effect!
 {:id :set-color
  :name "Set Color"
  :category :color
  :timing :static
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
  :apply-fn! set-color-fn!})


;; ========== SPECIAL EFFECTS ==========
;; These are more complex effects kept for specific use cases.
;; Consider using modulators with basic effects for more flexibility.


;; Rainbow Position Effect (Special)

(defn- rainbow-position-fn!
  "Apply rainbow effect based on point position in place."
  [^"[[D" frame time-ms bpm params ctx]
  (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
        speed (double (:speed resolved))
        axis (:axis resolved)
        time-ms-d (double time-ms)
        time-offset (double (mod (* (/ time-ms-d 1000.0) speed) 360.0))
        n (alength frame)]
    (dotimes [i n]
      (when-not (point-blanked? frame i)
        (let [x (double (aget frame i t/X))
              y (double (aget frame i t/Y))
              r (double (aget frame i t/R))
              g (double (aget frame i t/G))
              b (double (aget frame i t/B))
              position (double (case axis
                                 :x (/ (+ x 1.0) 2.0)
                                 :y (/ (+ y 1.0) 2.0)
                                 :radial (Math/sqrt (+ (* x x) (* y y)))
                                 :angle (/ (+ (Math/atan2 y x) Math/PI) (* 2.0 Math/PI))
                                 0.5))
              ;; Use current brightness (value from HSV)
              brightness (double (max r (max g b)))
              hue (mod (+ (* position 360.0) time-offset) 360.0)
              [nr ng nb] (colors/hsv->normalized hue 1.0 brightness)]
          (aset-double frame i t/R nr)
          (aset-double frame i t/G ng)
          (aset-double frame i t/B nb)))))
  frame)

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
  :apply-fn! rainbow-position-fn!})
