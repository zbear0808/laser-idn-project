(ns laser-show.animation.effects.calibration
  "Calibration effects for projector-specific corrections.
   These are static effects applied at the projector level to compensate
   for hardware differences between laser projectors."
  (:require [laser-show.animation.effects :as fx]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn clamp-byte [v]
  (max 0 (min 255 (int v))))

;; ============================================================================
;; RGB Balance Calibration
;; ============================================================================

(defn- apply-rgb-calibration [frame _time-ms _bpm {:keys [r-gain g-gain b-gain]}]
  (fx/transform-colors
   frame
   (fn [[r g b]]
     [(clamp-byte (* r r-gain))
      (clamp-byte (* g g-gain))
      (clamp-byte (* b b-gain))])))

(fx/register-effect!
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
  :apply-fn apply-rgb-calibration})

;; ============================================================================
;; Per-Channel Gamma Correction
;; ============================================================================

(defn- apply-gamma-correction [frame _time-ms _bpm {:keys [r-gamma g-gamma b-gamma]}]
  (let [r-inv (/ 1.0 r-gamma)
        g-inv (/ 1.0 g-gamma)
        b-inv (/ 1.0 b-gamma)]
    (fx/transform-colors
     frame
     (fn [[r g b]]
       [(clamp-byte (* 255.0 (Math/pow (/ r 255.0) r-inv)))
        (clamp-byte (* 255.0 (Math/pow (/ g 255.0) g-inv)))
        (clamp-byte (* 255.0 (Math/pow (/ b 255.0) b-inv)))]))))

(fx/register-effect!
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
  :apply-fn apply-gamma-correction})

;; ============================================================================
;; Brightness Calibration
;; ============================================================================

(defn- apply-brightness-calibration [frame _time-ms _bpm {:keys [global-brightness min-threshold]}]
  (fx/transform-colors
   frame
   (fn [[r g b]]
     (let [max-val (max r g b)]
       (if (< max-val min-threshold)
         [0 0 0]
         [(clamp-byte (* r global-brightness))
          (clamp-byte (* g global-brightness))
          (clamp-byte (* b global-brightness))])))))

(fx/register-effect!
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
  :apply-fn apply-brightness-calibration})

;; ============================================================================
;; Color Temperature Adjustment
;; ============================================================================

(defn- kelvin-to-rgb
  "Convert color temperature in Kelvin to RGB multipliers.
   Based on Tanner Helland's algorithm."
  [kelvin]
  (let [temp (/ kelvin 100.0)]
    (if (<= temp 66)
      ;; Warm temperatures
      [(* 255 1.0)
       (clamp-byte (- (* 99.4708 (Math/log temp)) 161.1195))
       (if (<= temp 19)
         0
         (clamp-byte (- (* 138.5177 (Math/log (- temp 10))) 305.0447)))]
      ;; Cool temperatures
      [(clamp-byte (* 329.698 (Math/pow (- temp 60) -0.1332)))
       (clamp-byte (* 288.122 (Math/pow (- temp 60) -0.0755)))
       (* 255 1.0)])))

(defn- apply-color-temperature [frame _time-ms _bpm {:keys [kelvin]}]
  (let [[kr kg kb] (kelvin-to-rgb kelvin)
        r-mult (/ kr 255.0)
        g-mult (/ kg 255.0)
        b-mult (/ kb 255.0)]
    (fx/transform-colors
     frame
     (fn [[r g b]]
       [(clamp-byte (* r r-mult))
        (clamp-byte (* g g-mult))
        (clamp-byte (* b b-mult))]))))

(fx/register-effect!
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
  :apply-fn apply-color-temperature})

;; ============================================================================
;; Color Matrix Transform
;; ============================================================================

(defn- apply-color-matrix [frame _time-ms _bpm {:keys [m00 m01 m02 m10 m11 m12 m20 m21 m22
                                                       offset-r offset-g offset-b]}]
  (fx/transform-colors
   frame
   (fn [[r g b]]
     [(clamp-byte (+ (* r m00) (* g m01) (* b m02) offset-r))
      (clamp-byte (+ (* r m10) (* g m11) (* b m12) offset-g))
      (clamp-byte (+ (* r m20) (* g m21) (* b m22) offset-b))])))

(fx/register-effect!
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
  :apply-fn apply-color-matrix})

;; ============================================================================
;; White Balance
;; ============================================================================

(defn- apply-white-balance [frame _time-ms _bpm {:keys [ref-r ref-g ref-b
                                                        measured-r measured-g measured-b]}]
  (let [r-mult (if (pos? measured-r) (/ ref-r measured-r) 1.0)
        g-mult (if (pos? measured-g) (/ ref-g measured-g) 1.0)
        b-mult (if (pos? measured-b) (/ ref-b measured-b) 1.0)]
    (fx/transform-colors
     frame
     (fn [[r g b]]
       [(clamp-byte (* r r-mult))
        (clamp-byte (* g g-mult))
        (clamp-byte (* b b-mult))]))))

(fx/register-effect!
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
  :apply-fn apply-white-balance})

;; ============================================================================
;; Projector Position Offset
;; ============================================================================

(defn- apply-projector-offset [frame _time-ms _bpm {:keys [x-offset y-offset]}]
  (fx/transform-positions
   frame
   (fn [[x y]]
     [(+ x x-offset)
      (+ y y-offset)])))

(fx/register-effect!
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
  :apply-fn apply-projector-offset})

;; ============================================================================
;; Projector Scale
;; ============================================================================

(defn- apply-projector-scale [frame _time-ms _bpm {:keys [x-scale y-scale]}]
  (fx/transform-positions
   frame
   (fn [[x y]]
     [(* x x-scale)
      (* y y-scale)])))

(fx/register-effect!
 {:id :projector-scale
  :name "Projector Scale"
  :category :calibration
  :timing :static
  :parameters [{:key :x-scale
                :label "X Scale"
                :type :float
                :default 1.0
                :min 0.1
                :max 2.0}
               {:key :y-scale
                :label "Y Scale"
                :type :float
                :default 1.0
                :min 0.1
                :max 2.0}]
  :apply-fn apply-projector-scale})

;; ============================================================================
;; Per-Channel Color Curves (simplified LUT)
;; ============================================================================

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

(defn- apply-color-curves [frame _time-ms _bpm {:keys [r-curve g-curve b-curve]}]
  (let [default-curve [0 32 64 96 128 160 192 224 255]]
    (fx/transform-colors
     frame
     (fn [[r g b]]
       (let [r-input (/ r 255.0)
             g-input (/ g 255.0)
             b-input (/ b 255.0)]
         [(clamp-byte (interpolate-curve (or r-curve default-curve) r-input))
          (clamp-byte (interpolate-curve (or g-curve default-curve) g-input))
          (clamp-byte (interpolate-curve (or b-curve default-curve) b-input))])))))

(fx/register-effect!
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
  :apply-fn apply-color-curves})

;; ============================================================================
;; Scan Rate Limiter (for safety)
;; ============================================================================

(defn- point-distance [p1 p2]
  (let [x1 (/ (:x p1) 32767.0)
        y1 (/ (:y p1) 32767.0)
        x2 (/ (:x p2) 32767.0)
        y2 (/ (:y p2) 32767.0)]
    (Math/sqrt (+ (* (- x2 x1) (- x2 x1))
                  (* (- y2 y1) (- y2 y1))))))

(defn- apply-scan-rate-limit [frame _time-ms _bpm {:keys [max-jump-distance]}]
  (let [points (:points frame)]
    (if (< (count points) 2)
      frame
      (let [limited-points
            (loop [result [(first points)]
                   remaining (rest points)]
              (if (empty? remaining)
                result
                (let [prev (last result)
                      curr (first remaining)
                      distance (point-distance prev curr)]
                  (if (> distance max-jump-distance)
                    (let [num-interp (int (Math/ceil (/ distance max-jump-distance)))
                          interp-points
                          (for [i (range 1 (inc num-interp))]
                            (let [t (/ i (double num-interp))
                                  x (+ (:x prev) (* t (- (:x curr) (:x prev))))
                                  y (+ (:y prev) (* t (- (:y curr) (:y prev))))]
                              (assoc curr
                                     :x (short (Math/round x))
                                     :y (short (Math/round y))
                                     :r (unchecked-byte 0)
                                     :g (unchecked-byte 0)
                                     :b (unchecked-byte 0))))]
                      (recur (into result interp-points) (rest remaining)))
                    (recur (conj result curr) (rest remaining))))))]
        (assoc frame :points (vec limited-points))))))

(fx/register-effect!
 {:id :scan-rate-limit
  :name "Scan Rate Limit"
  :category :calibration
  :timing :static
  :parameters [{:key :max-jump-distance
                :label "Max Jump Distance"
                :type :float
                :default 0.5
                :min 0.1
                :max 2.0}]
  :apply-fn apply-scan-rate-limit})

;; ============================================================================
;; Axis Flip (for projector mounting variations)
;; ============================================================================

(defn- apply-axis-flip [frame _time-ms _bpm {:keys [flip-x flip-y]}]
  (if (and (not flip-x) (not flip-y))
    frame
    (fx/transform-positions
     frame
     (fn [[x y]]
       [(if flip-x (- x) x)
        (if flip-y (- y) y)]))))

(fx/register-effect!
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
  :apply-fn apply-axis-flip})

;; ============================================================================
;; Rotation Offset (for projector mounting angle)
;; ============================================================================

(defn- apply-rotation-offset [frame _time-ms _bpm {:keys [angle]}]
  (if (zero? angle)
    frame
    (let [radians (Math/toRadians angle)]
      (fx/transform-positions
       frame
       (fn [[x y]]
         (let [cos-a (Math/cos radians)
               sin-a (Math/sin radians)]
           [(- (* x cos-a) (* y sin-a))
            (+ (* x sin-a) (* y cos-a))]))))))

(fx/register-effect!
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
  :apply-fn apply-rotation-offset})
