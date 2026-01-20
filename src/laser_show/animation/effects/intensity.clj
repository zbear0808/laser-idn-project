(ns laser-show.animation.effects.intensity
  "Intensity effects for laser frames.
   Includes intensity control, blackout, and threshold effects.
   These effects mutate frames in place for maximum performance.
   
   NOTE: All color values are NORMALIZED (0.0-1.0), not 8-bit (0-255).
   This provides full precision for intensity calculations.
   
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

(set! *unchecked-math* :warn-on-boxed)


;; ========== Intensity Effect ==========

(defn- intensity-fn!
  "Scale brightness of all points in place."
  [^"[[D" frame time-ms bpm params ctx]
  (if (mod/any-param-requires-per-point? params)
    ;; Per-point path - enables spatial brightness patterns!
    (let [n (alength frame)]
      (dotimes [i n]
        (let [x (double (t/aget2d frame i t/X))
              y (double (t/aget2d frame i t/Y))
              resolved (effects/resolve-params-for-point params time-ms bpm x y i n (:timing-ctx ctx))
              amount (double (:amount resolved))
              r (double (t/aget2d frame i t/R))
              g (double (t/aget2d frame i t/G))
              b (double (t/aget2d frame i t/B))]
          (t/aset2d frame i t/R (Math/max 0.0 (Math/min 1.0 (* r amount))))
          (t/aset2d frame i t/G (Math/max 0.0 (Math/min 1.0 (* g amount))))
          (t/aset2d frame i t/B (Math/max 0.0 (Math/min 1.0 (* b amount)))))))
    ;; Global path
    (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
          amount (double (:amount resolved))
          n (alength frame)]
      (dotimes [i n]
        (let [r (double (t/aget2d frame i t/R))
              g (double (t/aget2d frame i t/G))
              b (double (t/aget2d frame i t/B))]
          (t/aset2d frame i t/R (Math/max 0.0 (Math/min 1.0 (* r amount))))
          (t/aset2d frame i t/G (Math/max 0.0 (Math/min 1.0 (* g amount))))
          (t/aset2d frame i t/B (Math/max 0.0 (Math/min 1.0 (* b amount))))))))
  frame)

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
  :apply-fn! intensity-fn!})


;; ========== Blackout Effect ==========

(defn- blackout-fn!
  "Set all colors to zero when enabled, in place."
  [^"[[D" frame time-ms bpm params ctx]
  (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
        enabled (:enabled resolved)]
    (when enabled
      (let [n (alength frame)]
        (dotimes [i n]
          (t/aset2d frame i t/R (double 0.0))
          (t/aset2d frame i t/G (double 0.0))
          (t/aset2d frame i t/B (double 0.0))))))
  frame)

(effects/register-effect!
 {:id :blackout
  :name "Blackout"
  :category :intensity
  :timing :static
  :parameters [{:key :enabled
                :label "Enabled"
                :type :bool
                :default false}]
  :apply-fn! blackout-fn!})


;; ========== Threshold Effect ==========

(defn- threshold-fn!
  "Cut colors below threshold to black, in place."
  [^"[[D" frame time-ms bpm params ctx]
  (if (mod/any-param-requires-per-point? params)
    ;; Per-point path
    (let [n (alength frame)]
      (dotimes [i n]
        (let [x (double (t/aget2d frame i t/X))
              y (double (t/aget2d frame i t/Y))
              resolved (effects/resolve-params-for-point params time-ms bpm x y i n (:timing-ctx ctx))
              threshold (double (:threshold resolved))
              r (double (t/aget2d frame i t/R))
              g (double (t/aget2d frame i t/G))
              b (double (t/aget2d frame i t/B))
              max-val (Math/max r (Math/max g b))]
          (when (< max-val threshold)
            (t/aset2d frame i t/R (double 0.0))
            (t/aset2d frame i t/G (double 0.0))
            (t/aset2d frame i t/B (double 0.0))))))
    ;; Global path
    (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
          threshold (double (:threshold resolved))
          n (alength frame)]
      (dotimes [i n]
        (let [r (double (t/aget2d frame i t/R))
              g (double (t/aget2d frame i t/G))
              b (double (t/aget2d frame i t/B))
              max-val (Math/max r (Math/max g b))]
          (when (< max-val threshold)
            (t/aset2d frame i t/R (double 0.0))
            (t/aset2d frame i t/G (double 0.0))
            (t/aset2d frame i t/B (double 0.0)))))))
  frame)

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
  :apply-fn! threshold-fn!})
