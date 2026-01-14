(ns laser-show.animation.effects.calibration
  "Calibration effects for projector-specific corrections.
   These are static effects applied at the projector level to compensate
   for hardware differences between laser projectors."
  (:require [laser-show.animation.effects :as effects]
            [laser-show.animation.effects.common :as common]
            [laser-show.animation.effects.curves :as curves]))

(defn- rgb-curves-xf [time-ms bpm params ctx]
  (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
        ;; Get control points with defaults
        r-points (or (:r-curve-points resolved) [[0 0] [255 255]])
        g-points (or (:g-curve-points resolved) [[0 0] [255 255]])
        b-points (or (:b-curve-points resolved) [[0 0] [255 255]])
        ;; Generate LUTs
        r-lut (curves/generate-curve-lut r-points)
        g-lut (curves/generate-curve-lut g-points)
        b-lut (curves/generate-curve-lut b-points)]
    (map (fn [{:keys [r g b] :as pt}]
           (assoc pt
             :r (int (common/clamp-byte (nth r-lut (min 255 (max 0 r)))))
             :g (int (common/clamp-byte (nth g-lut (min 255 (max 0 g)))))
             :b (int (common/clamp-byte (nth b-lut (min 255 (max 0 b))))))))))

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
                :default [[0 0] [255 255]]}
               {:key :g-curve-points
                :label "Green Curve"
                :type :curve-points
                :default [[0 0] [255 255]]}
               {:key :b-curve-points
                :label "Blue Curve"
                :type :curve-points
                :default [[0 0] [255 255]]}]
  :apply-transducer rgb-curves-xf})
