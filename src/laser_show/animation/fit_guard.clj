(ns laser-show.animation.fit-guard
  "The fit guard (plans/2026-11-19-zones/01 §8, 05 §4): below its minimum
   fps a laser draws fewer points, then dims if it may. Pure.

   A laser's settings are [:projectors id :fit], read through settings: the
   routing (routing.rig/routing) puts them under each laser's :outputs, so an
   engine and the preview read the same ones for a tick.")

(def defaults
  "A laser's fit settings when it has none (D-18): a minimum of 20 fps, fewer
   points below it but never under 60% of them, and no dimming unless it is
   turned on, then never under half the brightness."
  {:min-fps 20 :reduce-points? true :point-floor 0.6 :dim? false :dim-floor 0.5})

(defn- clamped
  "v clamped to lo-hi, or default unless v is a finite number."
  [v lo hi default]
  (if (and (number? v) (Double/isFinite (double v)))
    (max lo (min hi v))
    default))

(defn settings
  "A laser's :fit over the defaults, each clamped to its range: :min-fps
   5-60, :point-floor 0.3-1.0, :dim-floor 0.2-1.0. A value of the wrong type
   takes its default, so a bad :fit can't stop the routing."
  [fit]
  (let [fit (if (map? fit) fit {})
        flag (fn [k] (let [v (get fit k)] (if (boolean? v) v (get defaults k))))
        min-fps (clamped (:min-fps fit) 5 60 (:min-fps defaults))]
    {:min-fps (if (integer? min-fps) (long min-fps) (double min-fps))
     :reduce-points? (flag :reduce-points?)
     :point-floor (double (clamped (:point-floor fit) 0.3 1.0 (:point-floor defaults)))
     :dim? (flag :dim?)
     :dim-floor (double (clamped (:dim-floor fit) 0.2 1.0 (:dim-floor defaults)))}))
