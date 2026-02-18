(ns laser-show.animation.effects.shape
  (:require [laser-show.animation.effects :as effects]
            [laser-show.animation.types :as t]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)


(defn- scale-xf [time-ms bpm params ctx]
  (let [get-x-scale (effects/make-param-resolver :x-scale params time-ms bpm ctx)
        get-y-scale (effects/make-param-resolver :y-scale params time-ms bpm ctx)]
    (map-indexed
     (fn [idx pt]
       (let [px (double (pt t/X))
             py (double (pt t/Y))
             x-scale (double (get-x-scale px py idx))
             y-scale (double (get-y-scale px py idx))]
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

(defn- translate-xf [time-ms bpm params ctx]
  (let [get-x (effects/make-param-resolver :x params time-ms bpm ctx)
        get-y (effects/make-param-resolver :y params time-ms bpm ctx)]
    (map-indexed
     (fn [idx pt]
       (let [px (double (pt t/X))
             py (double (pt t/Y))
             dx (double (get-x px py idx))
             dy (double (get-y px py idx))]
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

(defn- rotation-xf [time-ms bpm params ctx]
  (let [get-angle (effects/make-param-resolver :angle params time-ms bpm ctx)]
    (map-indexed
     (fn [idx pt]
       (let [x (double (pt t/X))
             y (double (pt t/Y))
             angle (double (get-angle x y idx))
             radians (Math/toRadians angle)
             cos-a (Math/cos radians)
             sin-a (Math/sin radians)]
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


(defn- corner-pin-xf
  "Maps the unit square [-1,1]x[-1,1] to a quadrilateral defined by four corners.
   Uses bilinear interpolation for the mapping.
   
   Corner positions:
   - tl (top-left): maps from (-1, 1)
   - tr (top-right): maps from (1, 1)
   - bl (bottom-left): maps from (-1, -1)
   - br (bottom-right): maps from (1, -1)"
  [time-ms bpm params ctx]
  (let [get-tl-x (effects/make-param-resolver :tl-x params time-ms bpm ctx)
        get-tl-y (effects/make-param-resolver :tl-y params time-ms bpm ctx)
        get-tr-x (effects/make-param-resolver :tr-x params time-ms bpm ctx)
        get-tr-y (effects/make-param-resolver :tr-y params time-ms bpm ctx)
        get-bl-x (effects/make-param-resolver :bl-x params time-ms bpm ctx)
        get-bl-y (effects/make-param-resolver :bl-y params time-ms bpm ctx)
        get-br-x (effects/make-param-resolver :br-x params time-ms bpm ctx)
        get-br-y (effects/make-param-resolver :br-y params time-ms bpm ctx)]
    (map-indexed
     (fn [idx pt]
       (let [x (double (pt t/X))
             y (double (pt t/Y))
             tl-x (double (get-tl-x x y idx)) tl-y (double (get-tl-y x y idx))
             tr-x (double (get-tr-x x y idx)) tr-y (double (get-tr-y x y idx))
             bl-x (double (get-bl-x x y idx)) bl-y (double (get-bl-y x y idx))
             br-x (double (get-br-x x y idx)) br-y (double (get-br-y x y idx))
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


(defn- lens-distortion-xf [time-ms bpm params ctx]
  (let [get-k1 (effects/make-param-resolver :k1 params time-ms bpm ctx)
        get-k2 (effects/make-param-resolver :k2 params time-ms bpm ctx)]
    (map-indexed
     (fn [idx pt]
       (let [x (double (pt t/X))
             y (double (pt t/Y))
             k1 (double (get-k1 x y idx))
             k2 (double (get-k2 x y idx))
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
