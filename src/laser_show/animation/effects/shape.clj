(ns laser-show.animation.effects.shape
  "Shape effects - geometric transformations for laser frames.
   Includes scale, translate, rotate, viewport, corner-pin, and distortion effects.
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
   {:effect-id :rotation :params {:angle (mod/sawtooth-mod 0 360 1.0)}}"
  (:require [laser-show.animation.effects :as effects]))

;; ============================================================================
;; Transformation Helpers
;; ============================================================================

(defn clamp [v min-v max-v]
  (max min-v (min max-v v)))

(defn normalize-coord [coord]
  (/ coord 32767.0))

(defn denormalize-coord [coord]
  (short (Math/round (* (clamp coord -1.0 1.0) 32767.0))))

;; ============================================================================
;; Scale Effect
;; ============================================================================

(defn- apply-scale [frame _time-ms _bpm {:keys [x-scale y-scale]}]
  (effects/transform-positions
   frame
   (fn [[x y]]
     [(* x x-scale)
      (* y y-scale)])))

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
  :apply-fn apply-scale})

;; ============================================================================
;; Translate Effect
;; ============================================================================

(defn- apply-translate [frame _time-ms _bpm {:keys [x y]}]
  (effects/transform-positions
   frame
   (fn [[px py]]
     [(+ px x)
      (+ py y)])))

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
  :apply-fn apply-translate})

;; ============================================================================
;; Rotation Effect
;; ============================================================================

(defn- rotate-point [[x y] angle]
  (let [cos-a (Math/cos angle)
        sin-a (Math/sin angle)]
    [(- (* x cos-a) (* y sin-a))
     (+ (* x sin-a) (* y cos-a))]))

(defn- apply-rotation [frame _time-ms _bpm {:keys [angle]}]
  (let [radians (Math/toRadians angle)]
    (effects/transform-positions
     frame
     (fn [[x y]]
       (rotate-point [x y] radians)))))

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
  :apply-fn apply-rotation})


;; ============================================================================
;; Viewport Effect
;; ============================================================================

(defn- apply-viewport [frame _time-ms _bpm {:keys [x-min x-max y-min y-max]}]
  (let [viewport-width (- x-max x-min)
        viewport-height (- y-max y-min)]
    (effects/transform-positions
     frame
     (fn [[x y]]
       (let [in-viewport? (and (>= x x-min) (<= x x-max)
                               (>= y y-min) (<= y y-max))]
         (if in-viewport?
           [(if (zero? viewport-width)
              0.0
              (- (* 2.0 (/ (- x x-min) viewport-width)) 1.0))
            (if (zero? viewport-height)
              0.0
              (- (* 2.0 (/ (- y y-min) viewport-height)) 1.0))]
           [0.0 0.0]))))))

(effects/register-effect!
 {:id :viewport
  :name "Viewport"
  :category :shape
  :timing :static
  :parameters [{:key :x-min
                :label "X Min"
                :type :float
                :default -1.0
                :min -1.0
                :max 1.0}
               {:key :x-max
                :label "X Max"
                :type :float
                :default 1.0
                :min -1.0
                :max 1.0}
               {:key :y-min
                :label "Y Min"
                :type :float
                :default -1.0
                :min -1.0
                :max 1.0}
               {:key :y-max
                :label "Y Max"
                :type :float
                :default 1.0
                :min -1.0
                :max 1.0}]
  :apply-fn apply-viewport})

;; ============================================================================
;; Pinch/Bulge Effect
;; ============================================================================

(defn- apply-pinch-bulge [frame _time-ms _bpm {:keys [amount]}]
  (effects/transform-positions
   frame
   (fn [[x y]]
     (let [distance (Math/sqrt (+ (* x x) (* y y)))
           factor (if (zero? distance)
                    1.0
                    (Math/pow distance amount))]
       [(* x factor)
        (* y factor)]))))

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
  :apply-fn apply-pinch-bulge})

;; ============================================================================
;; Corner Pin Effect (4-corner perspective/bilinear transform)
;; ============================================================================

(defn- apply-corner-pin
  "Maps the unit square [-1,1]x[-1,1] to a quadrilateral defined by four corners.
   Uses bilinear interpolation for the mapping.
   
   Corner positions:
   - tl (top-left): maps from (-1, 1)
   - tr (top-right): maps from (1, 1)
   - bl (bottom-left): maps from (-1, -1)
   - br (bottom-right): maps from (1, -1)"
  [frame _time-ms _bpm {:keys [tl-x tl-y tr-x tr-y bl-x bl-y br-x br-y]}]
  (effects/transform-positions
   frame
   (fn [[x y]]
     ;; Convert from [-1,1] to [0,1] for interpolation
     (let [u (/ (+ x 1.0) 2.0)  ; 0 at left, 1 at right
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
       [new-x new-y]))))

(effects/register-effect!
 {:id :corner-pin
  :name "Corner Pin"
  :category :shape
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
  :apply-fn apply-corner-pin})

;; ============================================================================
;; Lens Distortion Effect (barrel/pincushion)
;; ============================================================================

(defn- apply-lens-distortion [frame _time-ms _bpm {:keys [k1 k2]}]
  (effects/transform-positions
   frame
   (fn [[x y]]
     (let [r-sq (+ (* x x) (* y y))
           factor (+ 1.0 (* k1 r-sq) (* k2 r-sq r-sq))]
       [(* x factor)
        (* y factor)]))))

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
  :apply-fn apply-lens-distortion})

;; ============================================================================
;; SPECIAL EFFECTS
;; These are more complex effects kept for specific use cases.
;; Consider using modulators with basic effects for more flexibility.
;; ============================================================================

;; ============================================================================
;; Wave Distortion Effect (Special)
;; ============================================================================

(defn- apply-wave-distort [frame time-ms _bpm {:keys [amplitude frequency axis speed]}]
  (let [time-offset (* (/ time-ms 1000.0) speed)]
    (effects/transform-positions
     frame
     (fn [[x y]]
       (case axis
         :x [x (+ y (* amplitude (Math/sin (* 2.0 Math/PI (+ (* x frequency) time-offset)))))]
         :y [(+ x (* amplitude (Math/sin (* 2.0 Math/PI (+ (* y frequency) time-offset))))) y]
         [x y])))))

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
  :apply-fn apply-wave-distort})
