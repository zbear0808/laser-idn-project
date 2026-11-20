(ns laser-show.animation.effects.points
  "Effects that change how many points a frame has (plans/2026-11-19-zones/12).

   Decimate thins a frame to a target fps at a given scan rate: a frame over
   its budget, ⌊kpps · 1000 / fps⌋ points, loses points from its lit runs,
   spread evenly along each (fit-guard/thin), and never one the fit guard
   keeps: a blanked point, a lit run's first or last point, a dwell point or
   a corner (fit-guard/keep-mask). A frame of mostly such anchors can stay
   over its budget. Unlike the guard it has no floor: the target is the
   user's.

   A corner and a run's ends are found from their neighbours, so Decimate
   needs the whole frame: its transducer buffers the points and emits the
   ones it keeps when the pass completes. Effects after it in a chain get the
   thinned frame, but the frame context made for the chain, so a per-point
   modulator after it reads the count from before. Best last in its chain:
   fewer points, keeping blanking, corners and line ends."
  (:require [laser-show.animation.effects :as effects]
            [laser-show.animation.fit-guard :as fit-guard]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)


;; Decimate

(def ^:private kpps-param
  {:key :kpps :label "Scan rate (kpps)" :type :int :default 25 :min 1 :max 100})

(def ^:private fps-param
  {:key :fps :label "Target fps" :type :int :default 30 :min 5 :max 120})

(defn- in-range
  "v clamped to param's :min-:max (a modulator can go past a slider's
   ends), or param's :default unless v is a finite number."
  ^double [v param]
  (if (and (number? v) (Double/isFinite (double v)))
    (Math/max (double (:min param)) (Math/min (double (:max param)) (double v)))
    (double (:default param))))

(defn decimate
  "frame with the points over its budget at kpps and fps, ⌊kpps · 1000 /
   fps⌋, taken out of its lit runs (fit-guard/thin): never a point
   fit-guard/keep-mask keeps, so a frame of anchors can stay over the
   budget. Always a subsequence of frame; under the budget, frame itself."
  [frame kpps fps]
  (let [n (count frame)
        budget (long (fit-guard/budget (* 1000.0 (double kpps)) fps))]
    (if (<= n budget)
      frame
      (fit-guard/thin frame (- n budget)))))

(defn- decimate-xf
  "Decimate's transducer: :kpps and :fps resolved once for the frame. Each
   pass buffers its points and, when it completes, emits the ones decimate
   keeps, as partition-all emits its last partition; an effect after it
   that returns reduced stops the emitting. The buffer is made for each
   pass, so a chain that falls back to one effect at a time
   (effects/apply-effect-chain) starts it again with the whole frame."
  [time-ms bpm params ctx]
  (let [{:keys [kpps fps]} (effects/resolve-params-global params time-ms bpm ctx)
        kpps (in-range kpps kpps-param)
        fps (in-range fps fps-param)]
    (fn [rf]
      (let [points (volatile! (transient []))]
        (fn
          ([] (rf))
          ([result]
           (let [frame (persistent! @points)]
             (vreset! points (transient []))
             (rf (reduce rf result (decimate frame kpps fps)))))
          ([result point]
           (vswap! points conj! point)
           result))))))

(effects/register-effect!
 {:id :decimate
  :name "Decimate"
  :category #{:shape :calibration}  ;; In the bank's Shape tab and a laser's Add Effect menu
  :timing :static
  :parameters [kpps-param fps-param]
  :apply-transducer decimate-xf})
