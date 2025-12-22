(ns laser-show.animation.effects.intensity
  "Intensity effects for laser frames.
   Includes intensity control, blackout, threshold, and gamma effects.
   
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
   {:effect-id :intensity :params {:amount (mod/beat-decay 2.0 1.0 :exp)}}"
  (:require [laser-show.animation.effects :as fx]
            [laser-show.animation.effects.common :as common]))

;; ============================================================================
;; Intensity Effect (replaces dim/brighten)
;; ============================================================================

(defn- apply-intensity [frame _time-ms _bpm {:keys [amount]}]
  (fx/transform-colors
   frame
   (fn [[r g b]]
     [(common/clamp-byte (* r amount))
      (common/clamp-byte (* g amount))
      (common/clamp-byte (* b amount))])))

(fx/register-effect!
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
  :apply-fn apply-intensity})

;; ============================================================================
;; Blackout Effect
;; ============================================================================

(defn- apply-blackout [frame _time-ms _bpm {:keys [enabled]}]
  (if enabled
    (fx/transform-colors
     frame
     (fn [[_r _g _b]]
       [0 0 0]))
    frame))

(fx/register-effect!
 {:id :blackout
  :name "Blackout"
  :category :intensity
  :timing :static
  :parameters [{:key :enabled
                :label "Enabled"
                :type :bool
                :default false}]
  :apply-fn apply-blackout})

;; ============================================================================
;; Threshold Effect (cut low intensities)
;; ============================================================================

(defn- apply-threshold [frame _time-ms _bpm {:keys [threshold]}]
  (fx/transform-colors
   frame
   (fn [[r g b]]
     (let [max-val (max r g b)]
       (if (< max-val threshold)
         [0 0 0]
         [r g b])))))

(fx/register-effect!
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
  :apply-fn apply-threshold})

;; ============================================================================
;; Gamma Correction Effect
;; ============================================================================

(defn- apply-gamma [frame _time-ms _bpm {:keys [gamma]}]
  (let [inv-gamma (/ 1.0 gamma)]
    (fx/transform-colors
     frame
     (fn [[r g b]]
       [(common/clamp-byte (* 255.0 (Math/pow (/ r 255.0) inv-gamma)))
        (common/clamp-byte (* 255.0 (Math/pow (/ g 255.0) inv-gamma)))
        (common/clamp-byte (* 255.0 (Math/pow (/ b 255.0) inv-gamma)))]))))

(fx/register-effect!
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
  :apply-fn apply-gamma})
