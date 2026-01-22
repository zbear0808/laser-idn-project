(ns laser-show.animation.effects.shape
  "Shape effects - geometric transformations for laser frames.
   Includes scale, translate, rotate, corner-pin, and distortion effects.
   These effects can be used at any level (cue, zone group, zone, projector).
   
   Note: Scale supports negative values for mirroring (e.g., x-scale -1 = mirror X).
   
   All effects support both static values and modulators:
   ;; Static
   {:effect-id :scale :params {:x-scale 1.5 :y-scale 1.5}}
   
   ;; Modulated (using modulation.clj)
   (require '[laser-show.animation.modulation :as mod])
   {:effect-id :scale :params {:x-scale (mod/sine-mod 0.8 1.2 2.0)
                               :y-scale (mod/sine-mod 0.8 1.2 2.0)}}
   
   For animated rotation, use modulators:
   {:effect-id :rotation :params {:angle (mod/sawtooth-mod 0 360 1.0)}}
   
   Points are 5-element vectors [x y r g b]. Access via t/X, t/Y, t/R, t/G, t/B.
   Use t/update-point-xy for position updates."
  (:require [laser-show.animation.effects :as effects]
            [laser-show.animation.types :as t]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)


;; Scale Effect


(defn- scale-xf [time-ms bpm params ctx]
  (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
        x-scale (double (:x-scale resolved))
        y-scale (double (:y-scale resolved))]
    (map (fn [pt]
           (let [px (double (pt t/X))
                 py (double (pt t/Y))]
             (t/update-point-xy pt
               (* px x-scale)
               (* py y-scale)))))))

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
                :max 5.0}
               {:key :uniform?
                :label "Uniform Scale"
                :type :boolean
                :default false}]
  :ui-hints {:renderer :scale-2d
             :params [:x-scale :y-scale :uniform?]
             :default-mode :visual}
  :apply-transducer scale-xf})


;; Translate Effect


(defn- translate-xf [time-ms bpm params ctx]
  (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
        dx (double (:x resolved))
        dy (double (:y resolved))]
    (map (fn [pt]
           (let [px (double (pt t/X))
                 py (double (pt t/Y))]
             (t/update-point-xy pt
               (+ px dx)
               (+ py dy)))))))

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
  :apply-transducer translate-xf})


;; Rotation Effect


(defn- rotation-xf [time-ms bpm params ctx]
  (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
        angle (double (:angle resolved))
        radians (Math/toRadians angle)
        cos-a (Math/cos radians)
        sin-a (Math/sin radians)]
    (map (fn [pt]
           (let [x (double (pt t/X))
                 y (double (pt t/Y))]
             (t/update-point-xy pt
               (- (* x cos-a) (* y sin-a))
               (+ (* x sin-a) (* y cos-a))))))))

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
  :ui-hints {:renderer :rotation-dial
             :params [:angle]
             :default-mode :visual}
  :apply-transducer rotation-xf})



;; Pinch/Bulge Effect


(defn- pinch-bulge-xf [time-ms bpm params ctx]
  (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
        amount (double (:amount resolved))]
    (map (fn [pt]
           (let [x (double (pt t/X))
                 y (double (pt t/Y))
                 distance (Math/sqrt (+ (* x x) (* y y)))
                 factor (if (zero? distance)
                          1.0
                          (Math/pow distance amount))]
             (t/update-point-xy pt (* x factor) (* y factor)))))))

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
  :apply-transducer pinch-bulge-xf})


;; Corner Pin Effect (4-corner perspective/bilinear transform)


(defn- corner-pin-xf
  "Maps the unit square [-1,1]x[-1,1] to a quadrilateral defined by four corners.
   Uses bilinear interpolation for the mapping.
   
   Corner positions:
   - tl (top-left): maps from (-1, 1)
   - tr (top-right): maps from (1, 1)
   - bl (bottom-left): maps from (-1, -1)
   - br (bottom-right): maps from (1, -1)"
  [time-ms bpm params ctx]
  (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
        tl-x (double (:tl-x resolved)) tl-y (double (:tl-y resolved))
        tr-x (double (:tr-x resolved)) tr-y (double (:tr-y resolved))
        bl-x (double (:bl-x resolved)) bl-y (double (:bl-y resolved))
        br-x (double (:br-x resolved)) br-y (double (:br-y resolved))]
    (map (fn [pt]
           (let [x (double (pt t/X))
                 y (double (pt t/Y))
                 ;; Convert from [-1,1] to [0,1] for interpolation
                 u (/ (+ x 1.0) 2.0)  ; 0 at left, 1 at right
                 v (/ (+ y 1.0) 2.0)  ; 0 at bottom, 1 at top
                 ;; Bilinear interpolation
                 ;; P = (1-u)(1-v)*BL + u*(1-v)*BR + (1-u)*v*TL + u*v*TR
                 one-minus-u (- 1.0 u)
                 one-minus-v (- 1.0 v)
                 new-x (+ (* one-minus-u one-minus-v bl-x)
                          (* u one-minus-v br-x)
                          (* one-minus-u v tl-x)
                          (* u v tr-x))
                 new-y (+ (* one-minus-u one-minus-v bl-y)
                          (* u one-minus-v br-y)
                          (* one-minus-u v tl-y)
                          (* u v tr-y))]
             (t/update-point-xy pt new-x new-y))))))

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
  :apply-transducer corner-pin-xf})


;; Lens Distortion Effect (barrel/pincushion)


(defn- lens-distortion-xf [time-ms bpm params ctx]
  (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
        k1 (double (:k1 resolved))
        k2 (double (:k2 resolved))]
    (map (fn [pt]
           (let [x (double (pt t/X))
                 y (double (pt t/Y))
                 r-sq (+ (* x x) (* y y))
                 factor (+ 1.0 (* k1 r-sq) (* k2 r-sq r-sq))]
             (t/update-point-xy pt (* x factor) (* y factor)))))))

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
  :apply-transducer lens-distortion-xf})


;; SPECIAL EFFECTS
;; These are more complex effects kept for specific use cases.
;; Consider using modulators with basic effects for more flexibility.



;; Wave Distortion Effect (Special)


(defn- wave-distort-xf [time-ms bpm params ctx]
  (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
        amplitude (double (:amplitude resolved))
        frequency (double (:frequency resolved))
        axis (:axis resolved)
        speed (double (:speed resolved))
        time-offset (* (/ (double time-ms) 1000.0) speed)]
    (map (fn [pt]
           (let [x (double (pt t/X))
                 y (double (pt t/Y))]
             (case axis
               :x (t/update-point-xy pt x (+ y (* amplitude (Math/sin (* 2.0 Math/PI (+ (* x frequency) time-offset))))))
               :y (t/update-point-xy pt (+ x (* amplitude (Math/sin (* 2.0 Math/PI (+ (* y frequency) time-offset))))) y)
               pt))))))

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
  :apply-transducer wave-distort-xf})
