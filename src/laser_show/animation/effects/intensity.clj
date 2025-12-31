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
   {:effect-id :intensity :params {:amount (mod/beat-decay 2.0 1.0 :exp)}}
   
   Per-point modulation (NEW!):
   {:effect-id :intensity :params {:amount (mod/position-radial-mod 1.0 0.3)}}  ; Radial fade
   {:effect-id :intensity :params {:amount (mod/position-wave 0.5 1.0 :x 4.0)}}  ; Wave pattern"
  (:require [laser-show.animation.effects :as effects]
            [laser-show.animation.effects.common :as common]
            [laser-show.animation.modulation :as mod]))


;; Intensity Effect (replaces dim/brighten)


(defn- apply-intensity [frame time-ms bpm raw-params]
  ;; Check if any params use per-point modulators
  (if (mod/any-param-requires-per-point? raw-params)
    ;; Per-point path - enables spatial brightness patterns! (uses legacy function for modulation support)
    (effects/transform-colors-per-point
      frame time-ms bpm raw-params
      (fn [_idx _cnt _x _y r g b {:keys [amount]}]
        [(common/clamp-byte (* r amount))
         (common/clamp-byte (* g amount))
         (common/clamp-byte (* b amount))]))
    ;; Global path using new transform-point-full
    (let [context (mod/make-context {:time-ms time-ms :bpm bpm})
          resolved-params (mod/resolve-params raw-params context)
          amount (:amount resolved-params)]
      (effects/transform-point-full
        frame
        (fn [{:keys [r g b] :as pt}]
          (assoc pt
            :r (int (common/clamp-byte (* r amount)))
            :g (int (common/clamp-byte (* g amount)))
            :b (int (common/clamp-byte (* b amount)))))))))

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
  :apply-fn apply-intensity})


;; Blackout Effect


(defn- apply-blackout [frame _time-ms _bpm {:keys [enabled]}]
  (if enabled
    (effects/transform-point-full
     frame
     (fn [pt]
       (assoc pt :r 0 :g 0 :b 0)))
    frame))

(effects/register-effect!
 {:id :blackout
  :name "Blackout"
  :category :intensity
  :timing :static
  :parameters [{:key :enabled
                :label "Enabled"
                :type :bool
                :default false}]
  :apply-fn apply-blackout})


;; Threshold Effect (cut low intensities)
;; NOTE: This effect can now delete points below threshold using nil return


(defn- apply-threshold [frame time-ms bpm raw-params]
  ;; Check if any params use per-point modulators
  (if (mod/any-param-requires-per-point? raw-params)
    ;; Per-point path (uses legacy function for modulation support)
    (effects/transform-colors-per-point
      frame time-ms bpm raw-params
      (fn [_idx _cnt _x _y r g b {:keys [threshold]}]
        (let [max-val (max r g b)]
          (if (< max-val threshold)
            [0 0 0]
            [r g b]))))
    ;; Global path using new transform-point-full
    (let [context (mod/make-context {:time-ms time-ms :bpm bpm})
          resolved-params (mod/resolve-params raw-params context)
          threshold (:threshold resolved-params)]
      (effects/transform-point-full
        frame
        (fn [{:keys [r g b] :as pt}]
          (let [max-val (max r g b)]
            (if (< max-val threshold)
              (assoc pt :r 0 :g 0 :b 0)
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
  :apply-fn apply-threshold})


;; Gamma Correction Effect


(defn- apply-gamma [frame time-ms bpm raw-params]
  ;; Check if any params use per-point modulators
  (if (mod/any-param-requires-per-point? raw-params)
    ;; Per-point path (uses legacy function for modulation support)
    (effects/transform-colors-per-point
      frame time-ms bpm raw-params
      (fn [_idx _cnt _x _y r g b {:keys [gamma]}]
        (let [inv-gamma (/ 1.0 gamma)]
          [(common/clamp-byte (* 255.0 (Math/pow (/ r 255.0) inv-gamma)))
           (common/clamp-byte (* 255.0 (Math/pow (/ g 255.0) inv-gamma)))
           (common/clamp-byte (* 255.0 (Math/pow (/ b 255.0) inv-gamma)))])))
    ;; Global path using new transform-point-full
    (let [context (mod/make-context {:time-ms time-ms :bpm bpm})
          resolved-params (mod/resolve-params raw-params context)
          gamma (:gamma resolved-params)
          inv-gamma (/ 1.0 gamma)]
      (effects/transform-point-full
        frame
        (fn [{:keys [r g b] :as pt}]
          (assoc pt
            :r (int (common/clamp-byte (* 255.0 (Math/pow (/ r 255.0) inv-gamma))))
            :g (int (common/clamp-byte (* 255.0 (Math/pow (/ g 255.0) inv-gamma))))
            :b (int (common/clamp-byte (* 255.0 (Math/pow (/ b 255.0) inv-gamma))))))))))

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
  :apply-fn apply-gamma})
