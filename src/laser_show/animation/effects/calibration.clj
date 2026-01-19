(ns laser-show.animation.effects.calibration
  "Calibration effects for projector-specific corrections.
   These are static effects applied at the projector level to compensate
   for hardware differences between laser projectors.
   
   NOTE: All curve points use NORMALIZED values (0.0-1.0)."
  (:require [laser-show.animation.effects :as effects]
            [laser-show.animation.effects.common :as common]
            [laser-show.animation.effects.curves :as curves]))

(defn- rgb-curves-xf [time-ms bpm params ctx]
  (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
        ;; Get control points with defaults (normalized 0.0-1.0)
        r-points (or (:r-curve-points resolved) [[0.0 0.0] [1.0 1.0]])
        g-points (or (:g-curve-points resolved) [[0.0 0.0] [1.0 1.0]])
        b-points (or (:b-curve-points resolved) [[0.0 0.0] [1.0 1.0]])
        ;; Generate LUTs (returns 256-entry vector of normalized values)
        r-lut (curves/generate-curve-lut r-points)
        g-lut (curves/generate-curve-lut g-points)
        b-lut (curves/generate-curve-lut b-points)]
    (map (fn [{:keys [r g b] :as pt}]
           ;; r, g, b are normalized 0.0-1.0, convert to LUT index
           (let [r-idx (min 255 (max 0 (int (* r 255))))
                 g-idx (min 255 (max 0 (int (* g 255))))
                 b-idx (min 255 (max 0 (int (* b 255))))]
             (assoc pt
               :r (common/clamp-normalized (nth r-lut r-idx))
               :g (common/clamp-normalized (nth g-lut g-idx))
               :b (common/clamp-normalized (nth b-lut b-idx))))))))

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
  :apply-transducer rgb-curves-xf})


;; Axis Transform Effect (mirror and swap axes)


(defn- axis-transform-xf [time-ms bpm params ctx]
 (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
       mirror-x? (:mirror-x? resolved)
       mirror-y? (:mirror-y? resolved)
       swap-axes? (:swap-axes? resolved)]
   (map (fn [{:keys [x y] :as pt}]
          (let [;; Apply mirroring first
                x' (if mirror-x? (- x) x)
                y' (if mirror-y? (- y) y)
                ;; Then swap if needed
                [final-x final-y] (if swap-axes? [y' x'] [x' y'])]
            (assoc pt :x final-x :y final-y))))))

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
  :apply-transducer axis-transform-xf})
