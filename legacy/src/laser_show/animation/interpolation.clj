(ns laser-show.animation.interpolation
  "Shared interpolation infrastructure for keyframe systems.
   
   Provides interpolation curves and functions for both time-based and
   spatial keyframes. Supports linear, exponential, and step interpolation modes.")

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(def ^:private exp-power 2.5)

(def interpolation-fns
  "Registry of interpolation functions.
   Each function transforms a linear t value (0.0-1.0) to a curved t value."
  {:linear   (fn ^double [^double t] t)
   :exp-decay (fn ^double [^double t]
                (- 1.0 (Math/pow (- 1.0 t) exp-power)))
   :exp-grow (fn ^double [^double t]
               (Math/pow t exp-power))
   :step     (fn ^double [^double t]
               (if (>= t 1.0) 1.0 0.0))})

(defn apply-interpolation
  "Apply interpolation curve to linear t value.
   Falls back to linear for unknown or nil modes."
  ^double [^double t mode]
  (let [interp-fn (get interpolation-fns mode (:linear interpolation-fns))]
    (interp-fn t)))

(defn interpolate-value
  "Interpolate between two single numeric values."
  ^double [v1 v2 ^double t]
  (let [v1' (double v1)
        v2' (double v2)]
    (+ (* (- 1.0 t) v1') (* t v2'))))

(defn interpolate-params
  "Interpolate between two parameter maps.
   Applies interpolation curve before computing values."
  ([params1 params2 t]
   (interpolate-params params1 params2 t :linear))
  ([params1 params2 ^double t interpolation]
   (let [curved-t (apply-interpolation t interpolation)]
     (into {}
           (mapv (fn [[k v1]]
                   (let [v2 (get params2 k v1)]
                     [k (if (and (number? v1) (number? v2))
                          (interpolate-value v1 v2 curved-t)
                          v1)]))
                 params1)))))
