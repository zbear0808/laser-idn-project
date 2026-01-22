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
   
   ;; Modulated (using modulation.clj)
   (require '[laser-show.animation.modulation :as mod])
   {:effect-id :intensity :params {:amount (mod/sine-mod 0.3 1.0 2.0)}}
   
   For strobe effects, use:
   {:effect-id :intensity :params {:amount (mod/square-mod 0.0 1.0 4.0)}}
   
   For fade in/out, use:
   {:effect-id :intensity :params {:amount (mod/linear-decay 0.0 1.0 2000)}}
   
   For beat-synced flash, use:
   {:effect-id :intensity :params {:amount (mod/beat-decay 2.0 1.0 :exp)}}
   
   Per-point modulation:
   {:effect-id :intensity :params {:amount (mod/position-radial-mod 1.0 0.3)}}  ; Radial fade
   {:effect-id :intensity :params {:amount (mod/position-wave 0.5 1.0 :x 4.0)}}  ; Wave pattern"
  (:require [laser-show.animation.effects :as effects]
            [laser-show.animation.modulation :as mod]
            [laser-show.animation.types :as t]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)


;; Intensity Effect (replaces dim/brighten)


(defn- intensity-xf [time-ms bpm params ctx]
  ;; Check if any params use per-point modulators
  (if (mod/any-param-requires-per-point? params)
    ;; Per-point path - enables spatial brightness patterns! Use map-indexed to get idx
    (let [point-count (:point-count ctx)]
      (map-indexed
       (fn [idx pt]
         (let [x (double (pt t/X)) y (double (pt t/Y))
               r (double (pt t/R)) g (double (pt t/G)) b (double (pt t/B))
               resolved (effects/resolve-params-for-point params time-ms bpm x y idx point-count (:timing-ctx ctx))
               amount (double (:amount resolved))]
           (t/update-point-rgb pt
             (* r amount)
             (* g amount)
             (* b amount))))))
    ;; Global path
    (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
          amount (double (:amount resolved))]
      (map (fn [pt]
             (let [r (double (pt t/R)) g (double (pt t/G)) b (double (pt t/B))]
               (t/update-point-rgb pt
                 (* r amount)
                 (* g amount)
                 (* b amount))))))))

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
  ;; Check if any params use per-point modulators
  (if (mod/any-param-requires-per-point? params)
    ;; Per-point path - use map-indexed to get idx
    (let [point-count (:point-count ctx)]
      (map-indexed
       (fn [idx pt]
         (let [x (double (pt t/X)) y (double (pt t/Y))
               r (double (pt t/R)) g (double (pt t/G)) b (double (pt t/B))
               resolved (effects/resolve-params-for-point params time-ms bpm x y idx point-count (:timing-ctx ctx))
               ;; Threshold already normalized 0.0-1.0
               threshold (double (:threshold resolved))
               max-val (Math/max (Math/max r g) b)]
           (if (< max-val threshold)
             (t/update-point-rgb pt 0.0 0.0 0.0)
             pt)))))
    ;; Global path
    (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
          ;; Threshold already normalized 0.0-1.0
          threshold (double (:threshold resolved))]
      (map (fn [pt]
             (let [r (double (pt t/R)) g (double (pt t/G)) b (double (pt t/B))
                   max-val (Math/max (Math/max r g) b)]
               (if (< max-val threshold)
                 (t/update-point-rgb pt 0.0 0.0 0.0)
                 pt)))))))

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
