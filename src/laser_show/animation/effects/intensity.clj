(ns laser-show.animation.effects.intensity
  "Intensity effects for laser frames.
   Includes dim, brighten, fade, pulse, strobe, and blackout effects.
   
   All effects support both static values and modulators:
   ;; Static
   {:effect-id :dim :params {:amount 0.5}}
   
   ;; Modulated (using modulation.clj)
   (require '[laser-show.animation.modulation :as mod])
   {:effect-id :dim :params {:amount (mod/sine-mod 0.3 1.0 2.0)}}
   
   NOTE: The old :pulse and :breathe effects are deprecated.
   Use :dim or :brighten with modulators instead for the same functionality."
  (:require [laser-show.animation.effects :as fx]
            [laser-show.animation.time :as time]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn clamp-byte [v]
  (max 0 (min 255 (int v))))

(defn- is-blanked? [r g b]
  (and (zero? r) (zero? g) (zero? b)))

;; ============================================================================
;; Dim Effect
;; ============================================================================

(defn- apply-dim [frame _time-ms _bpm {:keys [amount]}]
  (fx/transform-colors
   frame
   (fn [[r g b]]
     [(clamp-byte (* r amount))
      (clamp-byte (* g amount))
      (clamp-byte (* b amount))])))

(fx/register-effect!
 {:id :dim
  :name "Dim"
  :category :intensity
  :timing :static
  :parameters [{:key :amount
                :label "Amount"
                :type :float
                :default 0.5
                :min 0.0
                :max 1.0}]
  :apply-fn apply-dim})

;; ============================================================================
;; Brighten Effect
;; ============================================================================

(defn- apply-brighten [frame _time-ms _bpm {:keys [amount]}]
  (fx/transform-colors
   frame
   (fn [[r g b]]
     [(clamp-byte (* r amount))
      (clamp-byte (* g amount))
      (clamp-byte (* b amount))])))

(fx/register-effect!
 {:id :brighten
  :name "Brighten"
  :category :intensity
  :timing :static
  :parameters [{:key :amount
                :label "Amount"
                :type :float
                :default 1.5
                :min 1.0
                :max 3.0}]
  :apply-fn apply-brighten})

;; ============================================================================
;; Fade In Effect
;; ============================================================================

(defn- apply-fade-in [frame time-ms _bpm {:keys [duration]}]
  (let [progress (min 1.0 (/ time-ms duration))
        multiplier progress]
    (fx/transform-colors
     frame
     (fn [[r g b]]
       [(clamp-byte (* r multiplier))
        (clamp-byte (* g multiplier))
        (clamp-byte (* b multiplier))]))))

(fx/register-effect!
 {:id :fade-in
  :name "Fade In"
  :category :intensity
  :timing :seconds
  :parameters [{:key :duration
                :label "Duration (ms)"
                :type :float
                :default 2000.0
                :min 100.0
                :max 30000.0}]
  :apply-fn apply-fade-in})

;; ============================================================================
;; Fade Out Effect
;; ============================================================================

(defn- apply-fade-out [frame time-ms _bpm {:keys [duration]}]
  (let [progress (min 1.0 (/ time-ms duration))
        multiplier (- 1.0 progress)]
    (fx/transform-colors
     frame
     (fn [[r g b]]
       [(clamp-byte (* r multiplier))
        (clamp-byte (* g multiplier))
        (clamp-byte (* b multiplier))]))))

(fx/register-effect!
 {:id :fade-out
  :name "Fade Out"
  :category :intensity
  :timing :seconds
  :parameters [{:key :duration
                :label "Duration (ms)"
                :type :float
                :default 2000.0
                :min 100.0
                :max 30000.0}]
  :apply-fn apply-fade-out})

;; ============================================================================
;; Strobe Effect
;; ============================================================================

(defn- apply-strobe [frame time-ms bpm {:keys [frequency duty-cycle]}]
  (let [phase (time/time->beat-phase time-ms bpm)
        cycle-phase (mod (* phase frequency) 1.0)
        is-on? (< cycle-phase duty-cycle)
        multiplier (if is-on? 1.0 0.0)]
    (fx/transform-colors
     frame
     (fn [[r g b]]
       [(clamp-byte (* r multiplier))
        (clamp-byte (* g multiplier))
        (clamp-byte (* b multiplier))]))))

(fx/register-effect!
 {:id :strobe
  :name "Strobe"
  :category :intensity
  :timing :bpm
  :parameters [{:key :frequency
                :label "Frequency (flashes/beat)"
                :type :float
                :default 4.0
                :min 0.5
                :max 32.0}
               {:key :duty-cycle
                :label "Duty Cycle"
                :type :float
                :default 0.5
                :min 0.05
                :max 0.95}]
  :apply-fn apply-strobe})

;; ============================================================================
;; Strobe (time-based, not BPM-synced)
;; ============================================================================

(defn- apply-strobe-hz [frame time-ms _bpm {:keys [frequency duty-cycle]}]
  (let [phase (time/time->phase time-ms frequency)
        is-on? (< phase duty-cycle)
        multiplier (if is-on? 1.0 0.0)]
    (fx/transform-colors
     frame
     (fn [[r g b]]
       [(clamp-byte (* r multiplier))
        (clamp-byte (* g multiplier))
        (clamp-byte (* b multiplier))]))))

(fx/register-effect!
 {:id :strobe-hz
  :name "Strobe (Hz)"
  :category :intensity
  :timing :seconds
  :parameters [{:key :frequency
                :label "Frequency (Hz)"
                :type :float
                :default 10.0
                :min 0.5
                :max 30.0}
               {:key :duty-cycle
                :label "Duty Cycle"
                :type :float
                :default 0.5
                :min 0.05
                :max 0.95}]
  :apply-fn apply-strobe-hz})

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
       [(clamp-byte (* 255.0 (Math/pow (/ r 255.0) inv-gamma)))
        (clamp-byte (* 255.0 (Math/pow (/ g 255.0) inv-gamma)))
        (clamp-byte (* 255.0 (Math/pow (/ b 255.0) inv-gamma)))]))))

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

;; ============================================================================
;; Flicker Effect (random intensity variation)
;; ============================================================================

(defn- apply-flicker [frame time-ms _bpm {:keys [amount speed]}]
  (let [noise-seed (int (/ time-ms (/ 1000.0 speed)))
        random (java.util.Random. noise-seed)
        flicker-amount (+ (- 1.0 amount) (* (.nextDouble random) amount 2.0))]
    (fx/transform-colors
     frame
     (fn [[r g b]]
       [(clamp-byte (* r flicker-amount))
        (clamp-byte (* g flicker-amount))
        (clamp-byte (* b flicker-amount))]))))

(fx/register-effect!
 {:id :flicker
  :name "Flicker"
  :category :intensity
  :timing :seconds
  :parameters [{:key :amount
                :label "Amount"
                :type :float
                :default 0.2
                :min 0.0
                :max 0.5}
               {:key :speed
                :label "Speed"
                :type :float
                :default 20.0
                :min 1.0
                :max 60.0}]
  :apply-fn apply-flicker})

;; ============================================================================
;; Point Fade Effect (based on point index)
;; ============================================================================

(defn- apply-point-fade [frame time-ms _bpm {:keys [fade-start fade-end direction]}]
  (let [num-points (count (:points frame))
        time-offset (mod (/ time-ms 1000.0) 1.0)]
    (fx/transform-points-indexed
     frame
     (fn [idx point]
       (let [r (bit-and (:r point) 0xFF)
             g (bit-and (:g point) 0xFF)
             b (bit-and (:b point) 0xFF)]
         (if (is-blanked? r g b)
           point
           (let [position (/ idx (max 1 (dec num-points)))
                 adjusted-pos (case direction
                                :forward (mod (+ position time-offset) 1.0)
                                :backward (mod (- position time-offset) 1.0)
                                :static position)
                 fade-range (- fade-end fade-start)
                 intensity (cond
                             (< adjusted-pos fade-start) 0.0
                             (> adjusted-pos fade-end) 1.0
                             (zero? fade-range) 1.0
                             :else (/ (- adjusted-pos fade-start) fade-range))]
             (assoc point
                    :r (unchecked-byte (clamp-byte (* r intensity)))
                    :g (unchecked-byte (clamp-byte (* g intensity)))
                    :b (unchecked-byte (clamp-byte (* b intensity)))))))))))

(fx/register-effect!
 {:id :point-fade
  :name "Point Fade"
  :category :intensity
  :timing :seconds
  :parameters [{:key :fade-start
                :label "Fade Start"
                :type :float
                :default 0.0
                :min 0.0
                :max 1.0}
               {:key :fade-end
                :label "Fade End"
                :type :float
                :default 1.0
                :min 0.0
                :max 1.0}
               {:key :direction
                :label "Direction"
                :type :choice
                :default :static
                :choices [:static :forward :backward]}]
  :apply-fn apply-point-fade})

;; ============================================================================
;; Beat Flash Effect
;; ============================================================================

(defn- apply-beat-flash [frame time-ms bpm {:keys [flash-intensity decay-time]}]
  (let [ms-per-beat (time/bpm->ms-per-beat bpm)
        beat-progress (mod time-ms ms-per-beat)
        decay-progress (/ beat-progress decay-time)
        intensity (if (< decay-progress 1.0)
                    (+ 1.0 (* (- flash-intensity 1.0) (- 1.0 decay-progress)))
                    1.0)]
    (fx/transform-colors
     frame
     (fn [[r g b]]
       [(clamp-byte (* r intensity))
        (clamp-byte (* g intensity))
        (clamp-byte (* b intensity))]))))

(fx/register-effect!
 {:id :beat-flash
  :name "Beat Flash"
  :category :intensity
  :timing :bpm
  :parameters [{:key :flash-intensity
                :label "Flash Intensity"
                :type :float
                :default 2.0
                :min 1.0
                :max 5.0}
               {:key :decay-time
                :label "Decay Time (ms)"
                :type :float
                :default 100.0
                :min 10.0
                :max 500.0}]
  :apply-fn apply-beat-flash})
