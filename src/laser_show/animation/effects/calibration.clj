(ns laser-show.animation.effects.calibration
  "Calibration effects for projector-specific corrections.
   These are static effects applied at the projector level to compensate
   for hardware differences between laser projectors.
   These effects mutate frames in place for maximum performance.
   
   NOTE: All curve points use NORMALIZED values (0.0-1.0)."
  (:require [laser-show.animation.effects :as effects]
            [laser-show.animation.effects.curves :as curves]
            [laser-show.animation.types :as t]))

(set! *unchecked-math* :warn-on-boxed)


;; ========== RGB Curves Effect ==========

(defn- rgb-curves-fn!
  "Apply RGB curve correction in place using LUTs."
  [^"[[D" frame time-ms bpm params ctx]
  (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
        ;; Get control points with defaults (normalized 0.0-1.0)
        r-points (or (:r-curve-points resolved) [[0.0 0.0] [1.0 1.0]])
        g-points (or (:g-curve-points resolved) [[0.0 0.0] [1.0 1.0]])
        b-points (or (:b-curve-points resolved) [[0.0 0.0] [1.0 1.0]])
        ;; Generate LUTs (returns 256-entry vector of normalized values)
        r-lut (curves/generate-curve-lut r-points)
        g-lut (curves/generate-curve-lut g-points)
        b-lut (curves/generate-curve-lut b-points)
        n (alength frame)]
    (dotimes [i n]
      (let [r (double (t/aget2d frame i t/R))
            g (double (t/aget2d frame i t/G))
            b (double (t/aget2d frame i t/B))
            ;; r, g, b are normalized 0.0-1.0, convert to LUT index
            r-idx (min 255 (max 0 (int (* r 255.0))))
            g-idx (min 255 (max 0 (int (* g 255.0))))
            b-idx (min 255 (max 0 (int (* b 255.0))))
            new-r (double (max 0.0 (min 1.0 (double (nth r-lut r-idx)))))
            new-g (double (max 0.0 (min 1.0 (double (nth g-lut g-idx)))))
            new-b (double (max 0.0 (min 1.0 (double (nth b-lut b-idx)))))]
        (t/aset2d frame i t/R new-r)
        (t/aset2d frame i t/G new-g)
        (t/aset2d frame i t/B new-b))))
  frame)

(effects/register-effect!
 {:id :rgb-curves
  :name "RGB Curves"
  :category :calibration
  :timing :static
  :ui-hints {:renderer :rgb-curves
             :default-mode :visual}
  :parameters [{:key :r-curve-points
                :label "Red Curve"
                :type :curve-points
                :default [[0.0 0.0] [1.0 1.0]]}
               {:key :g-curve-points
                :label "Green Curve"
                :type :curve-points
                :default [[0.0 0.0] [1.0 1.0]]}
               {:key :b-curve-points
                :label "Blue Curve"
                :type :curve-points
                :default [[0.0 0.0] [1.0 1.0]]}]
  :apply-fn! rgb-curves-fn!})


;; ========== Axis Transform Effect ==========

(defn- axis-transform-fn!
  "Apply axis mirroring and swap in place."
  [^"[[D" frame time-ms bpm params ctx]
  (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
        mirror-x? (:mirror-x? resolved)
        mirror-y? (:mirror-y? resolved)
        swap-axes? (:swap-axes? resolved)
        n (alength frame)]
    (dotimes [i n]
      (let [x (double (t/aget2d frame i t/X))
            y (double (t/aget2d frame i t/Y))
            ;; Apply mirroring first
            x' (if mirror-x? (- x) x)
            y' (if mirror-y? (- y) y)
            ;; Then swap if needed
            [final-x final-y] (if swap-axes? [y' x'] [x' y'])]
        (t/aset2d frame i t/X (double final-x))
        (t/aset2d frame i t/Y (double final-y)))))
  frame)

(effects/register-effect!
 {:id :axis-transform
  :name "Axis Transform"
  :category :calibration
  :timing :static
  :parameters [{:key :mirror-x?
                :label "Mirror X"
                :type :bool
                :default false}
               {:key :mirror-y?
                :label "Mirror Y"
                :type :bool
                :default false}
               {:key :swap-axes?
                :label "Swap Axes"
                :type :bool
                :default false}]
  :apply-fn! axis-transform-fn!})
