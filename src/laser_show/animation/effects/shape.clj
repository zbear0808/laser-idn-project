(ns laser-show.animation.effects.shape
  "Shape effects - geometric transformations for laser frames.
   Includes scale, rotate, offset, mirror, viewport, and distortion effects.
   These effects can be used at any level (cue, zone group, zone, projector).
   
   All effects support both static values and modulators:
   ;; Static
   {:effect-id :scale :params {:x-scale 1.5 :y-scale 1.5}}
   
   ;; Modulated (using modulation.clj)
   (require '[laser-show.animation.modulation :as mod])
   {:effect-id :scale :params {:x-scale (mod/sine-mod 0.8 1.2 2.0)
                               :y-scale (mod/sine-mod 0.8 1.2 2.0)}}
   
   NOTE: The old :scale-oscillate and :offset-oscillate effects are deprecated.
   Use :scale or :offset with modulators instead for the same functionality."
  (:require [laser-show.animation.effects :as fx]
            [laser-show.animation.time :as time]))

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
;; Scale Effects
;; ============================================================================

(defn- apply-scale-static [frame _time-ms _bpm {:keys [x-scale y-scale]}]
  (fx/transform-positions
   frame
   (fn [[x y]]
     [(* x x-scale)
      (* y y-scale)])))

(fx/register-effect!
 {:id :scale
  :name "Scale"
  :category :shape
  :timing :static
  :parameters [{:key :x-scale
                :label "X Scale"
                :type :float
                :default 1.0
                :min 0.0
                :max 5.0}
               {:key :y-scale
                :label "Y Scale"
                :type :float
                :default 1.0
                :min 0.0
                :max 5.0}]
  :apply-fn apply-scale-static})

(defn- apply-scale-uniform [frame _time-ms _bpm {:keys [scale]}]
  (fx/transform-positions
   frame
   (fn [[x y]]
     [(* x scale)
      (* y scale)])))

(fx/register-effect!
 {:id :scale-uniform
  :name "Scale Uniform"
  :category :shape
  :timing :static
  :parameters [{:key :scale
                :label "Scale"
                :type :float
                :default 1.0
                :min 0.0
                :max 5.0}]
  :apply-fn apply-scale-uniform})

;; ============================================================================
;; Offset (Translation) Effects
;; ============================================================================

(defn- apply-offset-static [frame _time-ms _bpm {:keys [x-offset y-offset]}]
  (fx/transform-positions
   frame
   (fn [[x y]]
     [(+ x x-offset)
      (+ y y-offset)])))

(fx/register-effect!
 {:id :offset
  :name "Offset"
  :category :shape
  :timing :static
  :parameters [{:key :x-offset
                :label "X Offset"
                :type :float
                :default 0.0
                :min -2.0
                :max 2.0}
               {:key :y-offset
                :label "Y Offset"
                :type :float
                :default 0.0
                :min -2.0
                :max 2.0}]
  :apply-fn apply-offset-static})

;; ============================================================================
;; Rotation Effects
;; ============================================================================

(defn- rotate-point [[x y] angle]
  (let [cos-a (Math/cos angle)
        sin-a (Math/sin angle)]
    [(- (* x cos-a) (* y sin-a))
     (+ (* x sin-a) (* y cos-a))]))

(defn- apply-rotation-static [frame _time-ms _bpm {:keys [angle]}]
  (let [radians (Math/toRadians angle)]
    (fx/transform-positions
     frame
     (fn [[x y]]
       (rotate-point [x y] radians)))))

(fx/register-effect!
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
  :apply-fn apply-rotation-static})

(defn- apply-rotation-continuous [frame time-ms _bpm {:keys [speed]}]
  (let [angle (Math/toRadians (* (/ time-ms 1000.0) speed))]
    (fx/transform-positions
     frame
     (fn [[x y]]
       (rotate-point [x y] angle)))))

(fx/register-effect!
 {:id :rotation-continuous
  :name "Rotation Continuous"
  :category :shape
  :timing :seconds
  :parameters [{:key :speed
                :label "Speed (deg/sec)"
                :type :float
                :default 45.0
                :min -720.0
                :max 720.0}]
  :apply-fn apply-rotation-continuous})

(defn- apply-rotation-beat-sync [frame time-ms bpm {:keys [degrees-per-beat]}]
  (let [beats (time/ms->beats time-ms bpm)
        angle (Math/toRadians (* beats degrees-per-beat))]
    (fx/transform-positions
     frame
     (fn [[x y]]
       (rotate-point [x y] angle)))))

(fx/register-effect!
 {:id :rotation-beat-sync
  :name "Rotation Beat Sync"
  :category :shape
  :timing :bpm
  :parameters [{:key :degrees-per-beat
                :label "Degrees/Beat"
                :type :float
                :default 90.0
                :min -360.0
                :max 360.0}]
  :apply-fn apply-rotation-beat-sync})

;; ============================================================================
;; Mirror Effects
;; ============================================================================

(defn- apply-mirror-x [frame _time-ms _bpm _params]
  (fx/transform-positions
   frame
   (fn [[x y]]
     [(- x) y])))

(fx/register-effect!
 {:id :mirror-x
  :name "Mirror X"
  :category :shape
  :timing :static
  :parameters []
  :apply-fn apply-mirror-x})

(defn- apply-mirror-y [frame _time-ms _bpm _params]
  (fx/transform-positions
   frame
   (fn [[x y]]
     [x (- y)])))

(fx/register-effect!
 {:id :mirror-y
  :name "Mirror Y"
  :category :shape
  :timing :static
  :parameters []
  :apply-fn apply-mirror-y})

(defn- apply-mirror-diagonal [frame _time-ms _bpm _params]
  (fx/transform-positions
   frame
   (fn [[x y]]
     [y x])))

(fx/register-effect!
 {:id :mirror-diagonal
  :name "Mirror Diagonal"
  :category :shape
  :timing :static
  :parameters []
  :apply-fn apply-mirror-diagonal})

;; ============================================================================
;; Viewport Effects
;; ============================================================================

(defn- apply-viewport [frame _time-ms _bpm {:keys [x-min x-max y-min y-max]}]
  (let [viewport-width (- x-max x-min)
        viewport-height (- y-max y-min)]
    (fx/transform-positions
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

(fx/register-effect!
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
;; Shear Effects
;; ============================================================================

(defn- apply-shear [frame _time-ms _bpm {:keys [x-factor y-factor]}]
  (fx/transform-positions
   frame
   (fn [[x y]]
     [(+ x (* y x-factor))
      (+ y (* x y-factor))])))

(fx/register-effect!
 {:id :shear
  :name "Shear"
  :category :shape
  :timing :static
  :parameters [{:key :x-factor
                :label "X Factor"
                :type :float
                :default 0.0
                :min -2.0
                :max 2.0}
               {:key :y-factor
                :label "Y Factor"
                :type :float
                :default 0.0
                :min -2.0
                :max 2.0}]
  :apply-fn apply-shear})

;; ============================================================================
;; Wave Distortion Effects
;; ============================================================================

(defn- apply-wave-distort [frame time-ms _bpm {:keys [amplitude frequency axis speed]}]
  (let [time-offset (* (/ time-ms 1000.0) speed)]
    (fx/transform-positions
     frame
     (fn [[x y]]
       (case axis
         :x [x (+ y (* amplitude (Math/sin (* 2.0 Math/PI (+ (* x frequency) time-offset)))))]
         :y [(+ x (* amplitude (Math/sin (* 2.0 Math/PI (+ (* y frequency) time-offset))))) y]
         [x y])))))

(fx/register-effect!
 {:id :wave-distort
  :name "Wave Distort"
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

;; ============================================================================
;; Spiral Distortion Effect
;; ============================================================================

(defn- apply-spiral [frame time-ms bpm {:keys [amount frequency]}]
  (let [phase (time/time->beat-phase time-ms bpm)
        spiral-phase (* phase frequency)]
    (fx/transform-positions
     frame
     (fn [[x y]]
       (let [distance (Math/sqrt (+ (* x x) (* y y)))
             angle (Math/atan2 y x)
             twist (* amount distance spiral-phase Math/PI)
             new-angle (+ angle twist)]
         [(* distance (Math/cos new-angle))
          (* distance (Math/sin new-angle))])))))

(fx/register-effect!
 {:id :spiral
  :name "Spiral"
  :category :shape
  :timing :bpm
  :parameters [{:key :amount
                :label "Amount"
                :type :float
                :default 0.5
                :min -2.0
                :max 2.0}
               {:key :frequency
                :label "Frequency (cycles/beat)"
                :type :float
                :default 0.5
                :min 0.1
                :max 4.0}]
  :apply-fn apply-spiral})

;; ============================================================================
;; Pinch/Bulge Effect
;; ============================================================================

(defn- apply-pinch-bulge [frame _time-ms _bpm {:keys [amount]}]
  (fx/transform-positions
   frame
   (fn [[x y]]
     (let [distance (Math/sqrt (+ (* x x) (* y y)))
           factor (if (zero? distance)
                    1.0
                    (Math/pow distance amount))]
       [(* x factor)
        (* y factor)]))))

(fx/register-effect!
 {:id :pinch-bulge
  :name "Pinch/Bulge"
  :category :shape
  :timing :static
  :parameters [{:key :amount
                :label "Amount"
                :type :float
                :default 1.0
                :min 0.1
                :max 3.0}]
  :apply-fn apply-pinch-bulge})

;; ============================================================================
;; Keystone Correction Effect (for projector calibration)
;; ============================================================================

(defn- apply-keystone [frame _time-ms _bpm {:keys [top-width bottom-width left-height right-height]}]
  (fx/transform-positions
   frame
   (fn [[x y]]
     (let [t (/ (+ y 1.0) 2.0)
           width-factor (+ (* (- 1.0 t) bottom-width) (* t top-width))
           s (/ (+ x 1.0) 2.0)
           height-factor (+ (* (- 1.0 s) left-height) (* s right-height))
           new-x (* x width-factor)
           new-y (* y height-factor)]
       [new-x new-y]))))

(fx/register-effect!
 {:id :keystone
  :name "Keystone"
  :category :shape
  :timing :static
  :parameters [{:key :top-width
                :label "Top Width"
                :type :float
                :default 1.0
                :min 0.5
                :max 1.5}
               {:key :bottom-width
                :label "Bottom Width"
                :type :float
                :default 1.0
                :min 0.5
                :max 1.5}
               {:key :left-height
                :label "Left Height"
                :type :float
                :default 1.0
                :min 0.5
                :max 1.5}
               {:key :right-height
                :label "Right Height"
                :type :float
                :default 1.0
                :min 0.5
                :max 1.5}]
  :apply-fn apply-keystone})

;; ============================================================================
;; Lens Distortion Effect (barrel/pincushion)
;; ============================================================================

(defn- apply-lens-distortion [frame _time-ms _bpm {:keys [k1 k2]}]
  (fx/transform-positions
   frame
   (fn [[x y]]
     (let [r-sq (+ (* x x) (* y y))
           factor (+ 1.0 (* k1 r-sq) (* k2 r-sq r-sq))]
       [(* x factor)
        (* y factor)]))))

(fx/register-effect!
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
