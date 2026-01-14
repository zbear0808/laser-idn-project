(ns laser-show.animation.effects.intensity
  "Intensity effects for laser frames.
   Includes intensity control, blackout, threshold, and gamma effects.
   
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
            [laser-show.animation.effects.common :as common]
            [laser-show.animation.modulation :as mod]))


;; Intensity Effect (replaces dim/brighten)


(defn- intensity-xf [time-ms bpm params ctx]
  ;; Check if any params use per-point modulators
  (if (mod/any-param-requires-per-point? params)
    ;; Per-point path - enables spatial brightness patterns!
    (map (fn [{:keys [x y r g b idx count] :as pt}]
           (let [resolved (effects/resolve-params-for-point params time-ms bpm x y idx count (:timing-ctx ctx))
                 amount (:amount resolved)]
             (assoc pt
               :r (common/clamp-normalized (* r amount))
               :g (common/clamp-normalized (* g amount))
               :b (common/clamp-normalized (* b amount))))))
    ;; Global path
    (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
          amount (:amount resolved)]
      (map (fn [{:keys [r g b] :as pt}]
             (assoc pt
               :r (common/clamp-normalized (* r amount))
               :g (common/clamp-normalized (* g amount))
               :b (common/clamp-normalized (* b amount))))))))

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
             (assoc pt :r 0.0 :g 0.0 :b 0.0)))
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
    ;; Per-point path
    (map (fn [{:keys [x y r g b idx count] :as pt}]
           (let [resolved (effects/resolve-params-for-point params time-ms bpm x y idx count (:timing-ctx ctx))
                 ;; Convert threshold from 0-255 to 0.0-1.0
                 threshold (/ (:threshold resolved) 255.0)
                 max-val (max r g b)]
             (if (< max-val threshold)
               (assoc pt :r 0.0 :g 0.0 :b 0.0)
               pt))))
    ;; Global path
    (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
          ;; Convert threshold from 0-255 to 0.0-1.0
          threshold (/ (:threshold resolved) 255.0)]
      (map (fn [{:keys [r g b] :as pt}]
             (let [max-val (max r g b)]
               (if (< max-val threshold)
                 (assoc pt :r 0.0 :g 0.0 :b 0.0)
                 pt)))))))

(effects/register-effect!
 {:id :threshold
  :name "Threshold"
  :category :intensity
  :timing :static
  :parameters [{:key :threshold
                :label "Threshold"
                :type :int
                :default 10
                :min 0
                :max 255}]
  :apply-transducer threshold-xf})


;; Gamma Correction Effect


(defn- gamma-xf [time-ms bpm params ctx]
  ;; Check if any params use per-point modulators
  (if (mod/any-param-requires-per-point? params)
    ;; Per-point path
    (map (fn [{:keys [x y r g b idx count] :as pt}]
           (let [resolved (effects/resolve-params-for-point params time-ms bpm x y idx count (:timing-ctx ctx))
                 gamma (:gamma resolved)
                 inv-gamma (/ 1.0 gamma)]
             (assoc pt
               :r (common/clamp-normalized (Math/pow r inv-gamma))
               :g (common/clamp-normalized (Math/pow g inv-gamma))
               :b (common/clamp-normalized (Math/pow b inv-gamma))))))
    ;; Global path
    (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
          gamma (:gamma resolved)
          inv-gamma (/ 1.0 gamma)]
      (map (fn [{:keys [r g b] :as pt}]
             (assoc pt
               :r (common/clamp-normalized (Math/pow r inv-gamma))
               :g (common/clamp-normalized (Math/pow g inv-gamma))
               :b (common/clamp-normalized (Math/pow b inv-gamma))))))))

(effects/register-effect!
 {:id :gamma
  :name "Gamma"
  :category :intensity
  :timing :static
  :parameters [{:key :gamma
                :label "Gamma"
                :type :float
                :default 2.2
                :min 0.5
                :max 4.0}]
  :apply-transducer gamma-xf})
