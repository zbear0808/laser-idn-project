(ns laser-show.animation.effects.calibration
  "Calibration effects for projector-specific corrections.
   These are static effects applied at the projector level to compensate
   for hardware differences between laser projectors."
  (:require [laser-show.animation.effects :as effects]
            [laser-show.animation.effects.common :as common]))



;; RGB Balance Calibration


(defn- rgb-calibration-xf [time-ms bpm params _ctx]
  (let [resolved (effects/resolve-params-global params time-ms bpm)
        r-gain (:r-gain resolved)
        g-gain (:g-gain resolved)
        b-gain (:b-gain resolved)]
    (map (fn [{:keys [r g b] :as pt}]
           (assoc pt
             :r (int (common/clamp-byte (* r r-gain)))
             :g (int (common/clamp-byte (* g g-gain)))
             :b (int (common/clamp-byte (* b b-gain))))))))

(effects/register-effect!
 {:id :rgb-calibration
  :name "RGB Calibration"
  :category :calibration
  :timing :static
  :parameters [{:key :r-gain
                :label "Red Gain"
                :type :float
                :default 1.0
                :min 0.0
                :max 2.0}
               {:key :g-gain
                :label "Green Gain"
                :type :float
                :default 1.0
                :min 0.0
                :max 2.0}
               {:key :b-gain
                :label "Blue Gain"
                :type :float
                :default 1.0
                :min 0.0
                :max 2.0}]
  :apply-transducer rgb-calibration-xf})


;; Per-Channel Gamma Correction


(defn- gamma-correction-xf [time-ms bpm params _ctx]
  (let [resolved (effects/resolve-params-global params time-ms bpm)
        r-inv (/ 1.0 (:r-gamma resolved))
        g-inv (/ 1.0 (:g-gamma resolved))
        b-inv (/ 1.0 (:b-gamma resolved))]
    (map (fn [{:keys [r g b] :as pt}]
           (assoc pt
             :r (int (common/clamp-byte (* 255.0 (Math/pow (/ r 255.0) r-inv))))
             :g (int (common/clamp-byte (* 255.0 (Math/pow (/ g 255.0) g-inv))))
             :b (int (common/clamp-byte (* 255.0 (Math/pow (/ b 255.0) b-inv)))))))))

(effects/register-effect!
 {:id :gamma-correction
  :name "Gamma Correction"
  :category :calibration
  :timing :static
  :parameters [{:key :r-gamma
                :label "Red Gamma"
                :type :float
                :default 2.2
                :min 0.5
                :max 4.0}
               {:key :g-gamma
                :label "Green Gamma"
                :type :float
                :default 2.2
                :min 0.5
                :max 4.0}
               {:key :b-gamma
                :label "Blue Gamma"
                :type :float
                :default 2.2
                :min 0.5
                :max 4.0}]
  :apply-transducer gamma-correction-xf})


;; Brightness Calibration


(defn- brightness-calibration-xf [time-ms bpm params _ctx]
  (let [resolved (effects/resolve-params-global params time-ms bpm)
        global-brightness (:global-brightness resolved)
        min-threshold (:min-threshold resolved)]
    (map (fn [{:keys [r g b] :as pt}]
           (let [max-val (max r g b)]
             (if (< max-val min-threshold)
               (assoc pt :r 0 :g 0 :b 0)
               (assoc pt
                 :r (int (common/clamp-byte (* r global-brightness)))
                 :g (int (common/clamp-byte (* g global-brightness)))
                 :b (int (common/clamp-byte (* b global-brightness))))))))))

(effects/register-effect!
 {:id :brightness-calibration
  :name "Brightness Calibration"
  :category :calibration
  :timing :static
  :parameters [{:key :global-brightness
                :label "Global Brightness"
                :type :float
                :default 1.0
                :min 0.0
                :max 2.0}
               {:key :min-threshold
                :label "Min Threshold"
                :type :int
                :default 5
                :min 0
                :max 50}]
  :apply-transducer brightness-calibration-xf})


;; Color Temperature Adjustment


(defn- kelvin-to-rgb
  "Convert color temperature in Kelvin to RGB multipliers.
   Based on Tanner Helland's algorithm."
  [kelvin]
  (let [temp (/ kelvin 100.0)]
    (if (<= temp 66)
      ;; Warm temperatures
      [(* 255 1.0)
       (common/clamp-byte (- (* 99.4708 (Math/log temp)) 161.1195))
       (if (<= temp 19)
         0
         (common/clamp-byte (- (* 138.5177 (Math/log (- temp 10))) 305.0447)))]
      ;; Cool temperatures
      [(common/clamp-byte (* 329.698 (Math/pow (- temp 60) -0.1332)))
       (common/clamp-byte (* 288.122 (Math/pow (- temp 60) -0.0755)))
       (* 255 1.0)])))

(defn- color-temperature-xf [time-ms bpm params _ctx]
  (let [resolved (effects/resolve-params-global params time-ms bpm)
        kelvin (:kelvin resolved)
        [kr kg kb] (kelvin-to-rgb kelvin)
        r-mult (/ kr 255.0)
        g-mult (/ kg 255.0)
        b-mult (/ kb 255.0)]
    (map (fn [{:keys [r g b] :as pt}]
           (assoc pt
             :r (int (common/clamp-byte (* r r-mult)))
             :g (int (common/clamp-byte (* g g-mult)))
             :b (int (common/clamp-byte (* b b-mult))))))))

(effects/register-effect!
 {:id :color-temperature
  :name "Color Temperature"
  :category :calibration
  :timing :static
  :parameters [{:key :kelvin
                :label "Temperature (K)"
                :type :int
                :default 6500
                :min 2000
                :max 10000}]
  :apply-transducer color-temperature-xf})


;; Color Matrix Transform


(defn- color-matrix-xf [time-ms bpm params _ctx]
  (let [resolved (effects/resolve-params-global params time-ms bpm)
        m00 (:m00 resolved) m01 (:m01 resolved) m02 (:m02 resolved)
        m10 (:m10 resolved) m11 (:m11 resolved) m12 (:m12 resolved)
        m20 (:m20 resolved) m21 (:m21 resolved) m22 (:m22 resolved)
        offset-r (:offset-r resolved)
        offset-g (:offset-g resolved)
        offset-b (:offset-b resolved)]
    (map (fn [{:keys [r g b] :as pt}]
           (assoc pt
             :r (int (common/clamp-byte (+ (* r m00) (* g m01) (* b m02) offset-r)))
             :g (int (common/clamp-byte (+ (* r m10) (* g m11) (* b m12) offset-g)))
             :b (int (common/clamp-byte (+ (* r m20) (* g m21) (* b m22) offset-b))))))))

(effects/register-effect!
 {:id :color-matrix
  :name "Color Matrix"
  :category :calibration
  :timing :static
  :parameters [{:key :m00 :label "R→R" :type :float :default 1.0 :min -2.0 :max 2.0}
               {:key :m01 :label "G→R" :type :float :default 0.0 :min -2.0 :max 2.0}
               {:key :m02 :label "B→R" :type :float :default 0.0 :min -2.0 :max 2.0}
               {:key :m10 :label "R→G" :type :float :default 0.0 :min -2.0 :max 2.0}
               {:key :m11 :label "G→G" :type :float :default 1.0 :min -2.0 :max 2.0}
               {:key :m12 :label "B→G" :type :float :default 0.0 :min -2.0 :max 2.0}
               {:key :m20 :label "R→B" :type :float :default 0.0 :min -2.0 :max 2.0}
               {:key :m21 :label "G→B" :type :float :default 0.0 :min -2.0 :max 2.0}
               {:key :m22 :label "B→B" :type :float :default 1.0 :min -2.0 :max 2.0}
               {:key :offset-r :label "R Offset" :type :int :default 0 :min -128 :max 128}
               {:key :offset-g :label "G Offset" :type :int :default 0 :min -128 :max 128}
               {:key :offset-b :label "B Offset" :type :int :default 0 :min -128 :max 128}]
  :apply-transducer color-matrix-xf})


;; White Balance


(defn- white-balance-xf [time-ms bpm params _ctx]
  (let [resolved (effects/resolve-params-global params time-ms bpm)
        ref-r (:ref-r resolved) ref-g (:ref-g resolved) ref-b (:ref-b resolved)
        measured-r (:measured-r resolved)
        measured-g (:measured-g resolved)
        measured-b (:measured-b resolved)
        r-mult (if (pos? measured-r) (/ ref-r measured-r) 1.0)
        g-mult (if (pos? measured-g) (/ ref-g measured-g) 1.0)
        b-mult (if (pos? measured-b) (/ ref-b measured-b) 1.0)]
    (map (fn [{:keys [r g b] :as pt}]
           (assoc pt
             :r (int (common/clamp-byte (* r r-mult)))
             :g (int (common/clamp-byte (* g g-mult)))
             :b (int (common/clamp-byte (* b b-mult))))))))

(effects/register-effect!
 {:id :white-balance
  :name "White Balance"
  :category :calibration
  :timing :static
  :parameters [{:key :ref-r :label "Reference R" :type :int :default 255 :min 0 :max 255}
               {:key :ref-g :label "Reference G" :type :int :default 255 :min 0 :max 255}
               {:key :ref-b :label "Reference B" :type :int :default 255 :min 0 :max 255}
               {:key :measured-r :label "Measured R" :type :int :default 255 :min 1 :max 255}
               {:key :measured-g :label "Measured G" :type :int :default 255 :min 1 :max 255}
               {:key :measured-b :label "Measured B" :type :int :default 255 :min 1 :max 255}]
  :apply-transducer white-balance-xf})


;; Projector Position Offset


(defn- projector-offset-xf [time-ms bpm params _ctx]
  (let [resolved (effects/resolve-params-global params time-ms bpm)
        x-offset (:x-offset resolved)
        y-offset (:y-offset resolved)]
    (map (fn [{:keys [x y] :as pt}]
           (assoc pt
             :x (+ x x-offset)
             :y (+ y y-offset))))))

(effects/register-effect!
 {:id :projector-offset
  :name "Projector Offset"
  :category :calibration
  :timing :static
  :parameters [{:key :x-offset
                :label "X Offset"
                :type :float
                :default 0.0
                :min -1.0
                :max 1.0}
               {:key :y-offset
                :label "Y Offset"
                :type :float
                :default 0.0
                :min -1.0
                :max 1.0}]
  :apply-transducer projector-offset-xf})


;; Projector Scale


(defn- projector-scale-xf [time-ms bpm params _ctx]
  (let [resolved (effects/resolve-params-global params time-ms bpm)
        x-scale (:x-scale resolved)
        y-scale (:y-scale resolved)]
    (map (fn [{:keys [x y] :as pt}]
           (assoc pt
             :x (* x x-scale)
             :y (* y y-scale))))))

(effects/register-effect!
 {:id :projector-scale
  :name "Projector Scale"
  :category :calibration
  :timing :static
  :parameters [{:key :x-scale
                :label "X Scale"
                :type :float
                :default 1.0
                :min -2.0
                :max 2.0}
               {:key :y-scale
                :label "Y Scale"
                :type :float
                :default 1.0
                :min -2.0
                :max 2.0}]
  :apply-transducer projector-scale-xf})


;; Per-Channel Color Curves (simplified LUT)


(defn- interpolate-curve
  "Interpolate a value using a simplified curve definition.
   curve is a vector of output values at regular input intervals."
  [curve input]
  (let [n (count curve)
        scaled (* input (dec n))
        idx (int scaled)
        frac (- scaled idx)]
    (if (>= idx (dec n))
      (last curve)
      (let [v0 (nth curve idx)
            v1 (nth curve (inc idx))]
        (+ v0 (* frac (- v1 v0)))))))

(defn- color-curves-xf [time-ms bpm params _ctx]
  (let [resolved (effects/resolve-params-global params time-ms bpm)
        default-curve [0 32 64 96 128 160 192 224 255]
        r-curve (or (:r-curve resolved) default-curve)
        g-curve (or (:g-curve resolved) default-curve)
        b-curve (or (:b-curve resolved) default-curve)]
    (map (fn [{:keys [r g b] :as pt}]
           (let [r-input (/ r 255.0)
                 g-input (/ g 255.0)
                 b-input (/ b 255.0)]
             (assoc pt
               :r (int (common/clamp-byte (interpolate-curve r-curve r-input)))
               :g (int (common/clamp-byte (interpolate-curve g-curve g-input)))
               :b (int (common/clamp-byte (interpolate-curve b-curve b-input)))))))))

(effects/register-effect!
 {:id :color-curves
  :name "Color Curves"
  :category :calibration
  :timing :static
  :parameters [{:key :r-curve
                :label "Red Curve"
                :type :curve
                :default [0 32 64 96 128 160 192 224 255]}
               {:key :g-curve
                :label "Green Curve"
                :type :curve
                :default [0 32 64 96 128 160 192 224 255]}
               {:key :b-curve
                :label "Blue Curve"
                :type :curve
                :default [0 32 64 96 128 160 192 224 255]}]
  :apply-transducer color-curves-xf})


;; Axis Flip (for projector mounting variations)


(defn- axis-flip-xf [time-ms bpm params _ctx]
  (let [resolved (effects/resolve-params-global params time-ms bpm)
        flip-x (:flip-x resolved)
        flip-y (:flip-y resolved)]
    (if (and (not flip-x) (not flip-y))
      (map identity)
      (map (fn [{:keys [x y] :as pt}]
             (assoc pt
               :x (if flip-x (- x) x)
               :y (if flip-y (- y) y)))))))

(effects/register-effect!
 {:id :axis-flip
  :name "Axis Flip"
  :category :calibration
  :timing :static
  :parameters [{:key :flip-x
                :label "Flip X"
                :type :bool
                :default false}
               {:key :flip-y
                :label "Flip Y"
                :type :bool
                :default false}]
  :apply-transducer axis-flip-xf})


;; Rotation Offset (for projector mounting angle)


(defn- rotation-offset-xf [time-ms bpm params _ctx]
  (let [resolved (effects/resolve-params-global params time-ms bpm)
        angle (:angle resolved)]
    (if (zero? angle)
      (map identity)
      (let [radians (Math/toRadians angle)
            cos-a (Math/cos radians)
            sin-a (Math/sin radians)]
        (map (fn [{:keys [x y] :as pt}]
               (assoc pt
                 :x (- (* x cos-a) (* y sin-a))
                 :y (+ (* x sin-a) (* y cos-a)))))))))

(effects/register-effect!
 {:id :rotation-offset
  :name "Rotation Offset"
  :category :calibration
  :timing :static
  :parameters [{:key :angle
                :label "Angle (degrees)"
                :type :float
                :default 0.0
                :min -180.0
                :max 180.0}]
  :apply-transducer rotation-offset-xf})
