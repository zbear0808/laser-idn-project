(ns laser-show.animation.effects.shape
  "Shape effects - geometric transformations for laser frames.
   Includes scale, translate, rotate, corner-pin, and distortion effects.
   These effects mutate frames in place for maximum performance.
   
   Note: Scale supports negative values for mirroring (e.g., x-scale -1 = mirror X).
   
   All effects support both static values and modulators:
   ;; Static
   {:effect-id :scale :params {:x-scale 1.5 :y-scale 1.5}}
   
   ;; Modulated (using modulation.clj)
   (require '[laser-show.animation.modulation :as mod])
   {:effect-id :scale :params {:x-scale (mod/sine-mod 0.8 1.2 2.0)
                               :y-scale (mod/sine-mod 0.8 1.2 2.0)}}
   
   For animated rotation, use modulators:
   {:effect-id :rotation :params {:angle (mod/sawtooth-mod 0 360 1.0)}}"
  (:require [laser-show.animation.effects :as effects]
            [laser-show.animation.types :as t]))

(set! *unchecked-math* :warn-on-boxed)

;; ========== Scale Effect ==========

(defn- scale-fn!
  "Apply scale transform in place."
  [^"[[D" frame _time-ms bpm params ctx]
  (let [resolved (effects/resolve-params-global params _time-ms bpm ctx)
        x-scale (double (:x-scale resolved))
        y-scale (double (:y-scale resolved))
        n (alength frame)]
    (dotimes [i n]
      (t/aset2d frame i t/X (* (double (t/aget2d frame i t/X)) x-scale))
      (t/aset2d frame i t/Y (* (double (t/aget2d frame i t/Y)) y-scale))))
  frame)

(effects/register-effect!
 {:id :scale
  :name "Scale"
  :category :shape
  :timing :static
  :parameters [{:key :x-scale
                :label "X Scale"
                :type :float
                :default 1.0
                :min -5.0
                :max 5.0}
               {:key :y-scale
                :label "Y Scale"
                :type :float
                :default 1.0
                :min -5.0
                :max 5.0}]
  :apply-fn! scale-fn!})


;; ========== Translate Effect ==========

(defn- translate-fn!
  "Apply translation in place."
  [^"[[D" frame _time-ms bpm params ctx]
  (let [resolved (effects/resolve-params-global params _time-ms bpm ctx)
        dx (double (:x resolved))
        dy (double (:y resolved))
        n (alength frame)]
    (dotimes [i n]
      (t/aset2d frame i t/X (+ (double (t/aget2d frame i t/X)) dx))
      (t/aset2d frame i t/Y (+ (double (t/aget2d frame i t/Y)) dy))))
  frame)

(effects/register-effect!
 {:id :translate
  :name "Translate"
  :category :shape
  :timing :static
  :parameters [{:key :x
                :label "X"
                :type :float
                :default 0.0
                :min -2.0
                :max 2.0}
               {:key :y
                :label "Y"
                :type :float
                :default 0.0
                :min -2.0
                :max 2.0}]
  :ui-hints {:renderer :spatial-2d
             :params [:x :y]
             :default-mode :visual}
  :apply-fn! translate-fn!})


;; ========== Rotation Effect ==========

(defn- rotation-fn!
  "Apply rotation around origin in place. Angle in degrees."
  [^"[[D" frame _time-ms bpm params ctx]
  (let [resolved (effects/resolve-params-global params _time-ms bpm ctx)
        angle (double (:angle resolved))
        radians (Math/toRadians angle)
        cos-a (double (Math/cos radians))
        sin-a (double (Math/sin radians))
        n (alength frame)]
    (dotimes [i n]
      (let [x (double (t/aget2d frame i t/X))
            y (double (t/aget2d frame i t/Y))
            new-x (- (* x cos-a) (* y sin-a))
            new-y (+ (* x sin-a) (* y cos-a))]
        (t/aset2d frame i t/X new-x)
        (t/aset2d frame i t/Y new-y))))
  frame)

(effects/register-effect!
 {:id :rotation
  :name "Rotation"
  :category :shape
  :timing :static
  :parameters [{:key :angle
                :label "Angle (degrees)"
                :type :float
                :default 0.0
                :min -360.0
                :max 360.0}]
  :apply-fn! rotation-fn!})


;; ========== Pinch/Bulge Effect ==========

(defn- pinch-bulge-fn!
  "Apply pinch/bulge distortion in place."
  [^"[[D" frame _time-ms bpm params ctx]
  (let [resolved (effects/resolve-params-global params _time-ms bpm ctx)
        amount (double (:amount resolved))
        n (alength frame)]
    (dotimes [i n]
      (let [x (double (t/aget2d frame i t/X))
            y (double (t/aget2d frame i t/Y))
            distance (Math/sqrt (+ (* x x) (* y y)))
            factor (if (zero? distance)
                     1.0
                     (Math/pow distance amount))]
        (t/aset2d frame i t/X (double (* x factor)))
        (t/aset2d frame i t/Y (double (* y factor))))))
  frame)

(effects/register-effect!
 {:id :pinch-bulge
  :name "Pinch/Bulge"
  :category :shape
  :timing :static
  :parameters [{:key :amount
                :label "Amount"
                :type :float
                :default 1.0
                :min -3.0
                :max 3.0}]
  :apply-fn! pinch-bulge-fn!})


;; ========== Corner Pin Effect ==========

(defn- corner-pin-fn!
  "Maps the unit square [-1,1]x[-1,1] to a quadrilateral defined by four corners.
   Uses bilinear interpolation for the mapping. Mutates in place.
   
   Corner positions:
   - tl (top-left): maps from (-1, 1)
   - tr (top-right): maps from (1, 1)
   - bl (bottom-left): maps from (-1, -1)
   - br (bottom-right): maps from (1, -1)"
  [^"[[D" frame _time-ms bpm params ctx]
  (let [resolved (effects/resolve-params-global params _time-ms bpm ctx)
        tl-x (double (:tl-x resolved)) tl-y (double (:tl-y resolved))
        tr-x (double (:tr-x resolved)) tr-y (double (:tr-y resolved))
        bl-x (double (:bl-x resolved)) bl-y (double (:bl-y resolved))
        br-x (double (:br-x resolved)) br-y (double (:br-y resolved))
        n (alength frame)]
    (dotimes [i n]
      (let [x (double (t/aget2d frame i t/X))
            y (double (t/aget2d frame i t/Y))
            ;; Convert from [-1,1] to [0,1] for interpolation
            u (/ (+ x 1.0) 2.0)  ; 0 at left, 1 at right
            v (/ (+ y 1.0) 2.0)  ; 0 at bottom, 1 at top
            ;; Bilinear interpolation
            ;; P = (1-u)(1-v)*BL + u*(1-v)*BR + (1-u)*v*TL + u*v*TR
            one-minus-u (- (double 1.0) u)
            one-minus-v (- (double 1.0) v)
            new-x (+ (* one-minus-u one-minus-v bl-x)
                     (* u one-minus-v br-x)
                     (* one-minus-u v tl-x)
                     (* u v tr-x))
            new-y (+ (* one-minus-u one-minus-v bl-y)
                     (* u one-minus-v br-y)
                     (* one-minus-u v tl-y)
                     (* u v tr-y))]
        (t/aset2d frame i t/X new-x)
        (t/aset2d frame i t/Y new-y))))
  frame)

(effects/register-effect!
 {:id :corner-pin
  :name "Corner Pin"
  :category #{:shape :calibration}  ;; Available in both shape effects and projector calibration
  :timing :static
  :parameters [{:key :tl-x
                :label "Top-Left X"
                :type :float
                :default -1.0
                :min -2.0
                :max 2.0}
               {:key :tl-y
                :label "Top-Left Y"
                :type :float
                :default 1.0
                :min -2.0
                :max 2.0}
               {:key :tr-x
                :label "Top-Right X"
                :type :float
                :default 1.0
                :min -2.0
                :max 2.0}
               {:key :tr-y
                :label "Top-Right Y"
                :type :float
                :default 1.0
                :min -2.0
                :max 2.0}
               {:key :bl-x
                :label "Bottom-Left X"
                :type :float
                :default -1.0
                :min -2.0
                :max 2.0}
               {:key :bl-y
                :label "Bottom-Left Y"
                :type :float
                :default -1.0
                :min -2.0
                :max 2.0}
               {:key :br-x
                :label "Bottom-Right X"
                :type :float
                :default 1.0
                :min -2.0
                :max 2.0}
               {:key :br-y
                :label "Bottom-Right Y"
                :type :float
                :default -1.0
                :min -2.0
                :max 2.0}]
  :ui-hints {:renderer :corner-pin-2d
             :corners {:tl [:tl-x :tl-y]
                       :tr [:tr-x :tr-y]
                       :bl [:bl-x :bl-y]
                       :br [:br-x :br-y]}
             :default-mode :visual}
  :apply-fn! corner-pin-fn!})


;; ========== Lens Distortion Effect ==========

(defn- lens-distortion-fn!
  "Apply lens distortion (barrel/pincushion) in place."
  [^"[[D" frame _time-ms bpm params ctx]
  (let [resolved (effects/resolve-params-global params _time-ms bpm ctx)
        k1 (double (:k1 resolved))
        k2 (double (:k2 resolved))
        n (alength frame)]
    (dotimes [i n]
      (let [x (double (t/aget2d frame i t/X))
            y (double (t/aget2d frame i t/Y))
            r-sq (+ (* x x) (* y y))
            factor (+ 1.0 (* k1 r-sq) (* k2 r-sq r-sq))]
        (t/aset2d frame i t/X (double (* x factor)))
        (t/aset2d frame i t/Y (double (* y factor))))))
  frame)

(effects/register-effect!
 {:id :lens-distortion
  :name "Lens Distortion"
  :category :shape
  :timing :static
  :parameters [{:key :k1
                :label "K1 (radial)"
                :type :float
                :default 0.0
                :min -1.0
                :max 1.0}
               {:key :k2
                :label "K2 (radial)"
                :type :float
                :default 0.0
                :min -1.0
                :max 1.0}]
  :apply-fn! lens-distortion-fn!})


;; ========== SPECIAL EFFECTS ==========
;; These are more complex effects kept for specific use cases.
;; Consider using modulators with basic effects for more flexibility.


;; Wave Distortion Effect (Special)

(defn- wave-distort-fn!
  "Apply wave distortion in place."
  [^"[[D" frame time-ms bpm params ctx]
  (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
        amplitude (double (:amplitude resolved))
        frequency (double (:frequency resolved))
        axis (:axis resolved)
        speed (double (:speed resolved))
        time-offset (double (* (/ (double time-ms) (double 1000.0)) speed))
        two-pi (double (* 2.0 Math/PI))
        n (alength frame)]
    (dotimes [i n]
      (case axis
        :x (let [x (double (t/aget2d frame i t/X))
                 y (double (t/aget2d frame i t/Y))
                 offset (* amplitude (Math/sin (* two-pi (+ (* x frequency) time-offset))))]
             (t/aset2d frame i t/Y (+ y offset)))
        :y (let [x (double (t/aget2d frame i t/X))
                 y (double (t/aget2d frame i t/Y))
                 offset (* amplitude (Math/sin (* two-pi (+ (* y frequency) time-offset))))]
             (t/aset2d frame i t/X (+ x offset)))
        nil)))
  frame)

(effects/register-effect!
 {:id :wave-distort
  :name "Wave Distort (Special)"
  :category :shape
  :timing :seconds
  :parameters [{:key :amplitude
                :label "Amplitude"
                :type :float
                :default 0.1
                :min 0.0
                :max 1.0}
               {:key :frequency
                :label "Frequency"
                :type :float
                :default 2.0
                :min 0.1
                :max 10.0}
               {:key :axis
                :label "Axis"
                :type :choice
                :default :x
                :choices [:x :y]}
               {:key :speed
                :label "Speed"
                :type :float
                :default 2.0
                :min 0.0
                :max 10.0}]
  :apply-fn! wave-distort-fn!})
