(ns laser-show.animation.effects.intensity
  "Intensity effects for laser frames.
   Includes intensity control, blackout, and threshold effects.
   
   NOTE: All color values are NORMALIZED (0.0-1.0), not 8-bit (0-255).
   This provides full precision for intensity calculations.
   
   Points are 5-element vectors: [x y r g b]
   - x, y: position in [-1.0, 1.0]
   - r, g, b: color intensity in [0.0, 1.0]
   
   All effects support both static values and modulators:
   ;; Static
   {:effect-id :intensity :params {:amount 0.5}}
   
   ;; Modulated (modulator config map)
   {:effect-id :intensity :params {:amount {:type :sine :min 0.3 :max 1.0 :period 2.0}}}
   
   ;; Strobe:
   {:effect-id :intensity :params {:amount {:type :square :min 0.0 :max 1.0 :period 0.25}}}
   
   Per-point modulation (radial fade, wave pattern):
   {:effect-id :intensity :params {:amount {:type :radial :min 1.0 :max 0.3}}}
   {:effect-id :intensity :params {:amount {:type :pos-wave :axis :x :min 0.5 :max 1.0 :frequency 4.0}}}
   
   Implementation uses make-param-resolver for efficient per-point handling:
   - Static values: resolved once, no per-point overhead
   - Global modulators: resolved once, cached
   - Per-point modulators: evaluated per-point with (fn [x y idx] -> value)"
  (:require [laser-show.animation.effects :as effects]
            [laser-show.animation.types :as t]))
  
  (set! *warn-on-reflection* true)
  (set! *unchecked-math* :warn-on-boxed)
  
  
  ;; Intensity Effect (replaces dim/brighten)
  
  
  (defn- intensity-xf [time-ms bpm params ctx]
    (let [get-amount (effects/make-param-resolver :amount params time-ms bpm ctx)]
      (map-indexed
       (fn [idx pt]
         (let [x (pt t/X) y (pt t/Y)
               amount (double (get-amount x y idx))
               r (double (pt t/R)) g (double (pt t/G)) b (double (pt t/B))]
           (t/update-point-rgb pt (* r amount) (* g amount) (* b amount)))))))

(effects/register-effect!
 {:id :intensity
  :name "Intensity"
  :category :intensity
  :timing :static
  :parameters [{:key :amount
                :label "Amount"
                :type :float
                :default 1.0
                :min 0.0
                :max 3.0}]
  :apply-transducer intensity-xf})


;; Blackout Effect


(defn- blackout-xf [time-ms bpm params ctx]
  (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
        enabled (:enabled resolved)]
    (if enabled
      (map (fn [pt]
             (t/update-point-rgb pt 0.0 0.0 0.0)))
      (map identity))))

(effects/register-effect!
 {:id :blackout
  :name "Blackout"
  :category :intensity
  :timing :static
  :parameters [{:key :enabled
                :label "Enabled"
                :type :bool
                :default false}]
  :apply-transducer blackout-xf})


;; Threshold Effect (cut low intensities)


(defn- threshold-xf [time-ms bpm params ctx]
  (let [get-threshold (effects/make-param-resolver :threshold params time-ms bpm ctx)]
    (map-indexed
     (fn [idx pt]
       (let [x (pt t/X) y (pt t/Y)
             r (double (pt t/R)) g (double (pt t/G)) b (double (pt t/B))
             threshold (double (get-threshold x y idx))
             max-val (Math/max (Math/max r g) b)]
         (if (< max-val threshold)
           (t/update-point-rgb pt 0.0 0.0 0.0)
           pt))))))

(effects/register-effect!
 {:id :threshold
  :name "Threshold"
  :category :intensity
  :timing :static
  :parameters [{:key :threshold
                :label "Threshold"
                :type :float
                :default 0.04
                :min 0.0
                :max 1.0}]
  :apply-transducer threshold-xf})
