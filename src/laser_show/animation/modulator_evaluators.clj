(ns laser-show.animation.modulator-evaluators
  "Modulator evaluation implementations.
   
   Contains all eval-* functions for different modulator types.
   The modulator-evaluators map is used by modulators.clj during
   registration to look up evaluator functions.
   
   For runtime evaluation, use laser-show.animation.modulation/evaluate-modulator
   which looks up evaluators via the registry (reg/get-evaluator).
   
   Each evaluator takes [config context] and returns a numeric value."
  (:require
   [clojure.edn :as edn]
   [laser-show.animation.time :as time]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)


;; Wave Modulators


(defn- eval-sine
  "Evaluate sine wave modulator.
   In once mode, completes once-periods cycles then holds at the final position."
  [{:keys [min max period phase loop-mode once-periods time-unit]
    :or {min 0.0 max 1.0 period 1.0 phase 0.0 loop-mode :loop once-periods 1.0 time-unit :beats}}
   {:keys [time-ms bpm trigger-time] :as context}]
  (if (= loop-mode :once)
    ;; Once mode: complete once-periods cycles then hold at final position
    (let [num-cycles (double (or once-periods 1.0))
          total-duration (* (double period) num-cycles)
          progress (double (time/calculate-once-progress (or time-ms 0) trigger-time total-duration (or time-unit :beats) (or bpm 120.0)))
          ;; Calculate total phase progression (0 to num-cycles)
          total-phase (+ (* progress num-cycles) (double phase))
          ;; Extract cycle position (0.0-1.0) for oscillate
          cycle-phase (mod total-phase 1.0)
          ;; If we've completed all cycles, hold at the final position
          ;; For sine, final position at end of last cycle is at phase 0.0 (which is max)
          final-phase (if (>= progress 1.0)
                        (mod (+ num-cycles (double phase)) 1.0)
                        cycle-phase)]
      (time/oscillate (double min) (double max) final-phase :sine))
    ;; Loop mode: use standard phase calculation
    (let [p (time/calculate-modulator-phase context period phase :loop period (or time-unit :beats))]
      (time/oscillate (double min) (double max) p :sine))))

(defn- eval-triangle
  "Evaluate triangle wave modulator.
   In once mode, completes once-periods cycles then holds at the final position."
  [{:keys [min max period phase loop-mode once-periods time-unit]
    :or {min 0.0 max 1.0 period 1.0 phase 0.0 loop-mode :loop once-periods 1.0 time-unit :beats}}
   {:keys [time-ms bpm trigger-time] :as context}]
  (if (= loop-mode :once)
    (let [num-cycles (double (or once-periods 1.0))
          total-duration (* (double period) num-cycles)
          progress (double (time/calculate-once-progress (or time-ms 0) trigger-time total-duration (or time-unit :beats) (or bpm 120.0)))
          ;; Calculate total phase progression (0 to num-cycles)
          total-phase (+ (* progress num-cycles) (double phase))
          ;; Extract cycle position (0.0-1.0) for oscillate
          cycle-phase (mod total-phase 1.0)
          ;; If we've completed all cycles, hold at the final position
          final-phase (if (>= progress 1.0)
                        (mod (+ num-cycles (double phase)) 1.0)
                        cycle-phase)]
      (time/oscillate (double min) (double max) final-phase :triangle))
    (let [p (time/calculate-modulator-phase context period phase :loop period (or time-unit :beats))]
      (time/oscillate (double min) (double max) p :triangle))))

(defn- eval-sawtooth
  "Evaluate sawtooth wave modulator.
   In once mode, completes once-periods cycles then holds at the final position.
   Uses effective-beats for smooth BPM-change animation in loop mode."
  [{:keys [min max period phase loop-mode once-periods time-unit]
    :or {min 0.0 max 1.0 period 1.0 phase 0.0 loop-mode :loop once-periods 1.0 time-unit :beats}}
   {:keys [time-ms bpm trigger-time] :as context}]
  (if (= loop-mode :once)
    (let [num-cycles (double (or once-periods 1.0))
          total-duration (* (double period) num-cycles)
          progress (double (time/calculate-once-progress (or time-ms 0) trigger-time total-duration (or time-unit :beats) (or bpm 120.0)))
          ;; Calculate total phase progression (0 to num-cycles)
          total-phase (+ (* progress num-cycles) (double phase))
          ;; Extract cycle position (0.0-1.0) for oscillate
          ;; If we're at the end of a cycle (phase close to integer > 0), use 0.9999
          cycle-phase (let [raw-phase (double (mod total-phase 1.0))]
                        (if (and (< raw-phase 0.001) (>= total-phase 0.999))
                          0.9999
                          raw-phase))
          ;; If we've completed all cycles, hold at the final position
          final-phase (if (>= progress 1.0)
                        ;; Calculate exact final phase, use 0.9999 if it wraps to 0
                        (let [end-phase (double (mod (+ num-cycles (double phase)) 1.0))]
                          (if (< end-phase 0.001) 0.9999 end-phase))
                        cycle-phase)]
      (time/oscillate (double min) (double max) final-phase :sawtooth))
    (let [p (time/calculate-modulator-phase context period phase :loop period (or time-unit :beats))]
      (time/oscillate (double min) (double max) p :sawtooth))))

(defn- eval-square
  "Evaluate square wave modulator.
   In once mode, completes once-periods cycles then holds at the final position.
   Uses effective-beats for smooth BPM-change animation in loop mode."
  [{:keys [min max period duty-cycle phase loop-mode once-periods time-unit]
    :or {min 0.0 max 1.0 period 1.0 duty-cycle 0.5 phase 0.0 loop-mode :loop once-periods 1.0 time-unit :beats}}
   {:keys [time-ms bpm trigger-time] :as context}]
  (let [square-fn (fn [^double p]
                    (let [cycle-phase (double (mod p 1.0))]
                      (if (< cycle-phase (double duty-cycle))
                        (double max)
                        (double min))))]
    (if (= loop-mode :once)
      (let [num-cycles (double (or once-periods 1.0))
            total-duration (* (double period) num-cycles)
            progress (double (time/calculate-once-progress (or time-ms 0) trigger-time total-duration (or time-unit :beats) (or bpm 120.0)))
            ;; Calculate total phase progression (0 to num-cycles)
            total-phase (+ (* progress num-cycles) (double phase))
            ;; If we've completed all cycles, hold at the final position
            final-phase (if (>= progress 1.0)
                          ;; Calculate exact final phase, use 0.9999 if it wraps to 0
                          (let [end-phase (double (mod (+ num-cycles (double phase)) 1.0))]
                            (if (< end-phase 0.001) 0.9999 end-phase))
                          ;; During animation, use total_phase directly (square-fn will mod it)
                          total-phase)]
        (square-fn final-phase))
      (let [p (time/calculate-modulator-phase context period phase :loop period (or time-unit :beats))]
        (square-fn p)))))


;; Decay Modulators


(defn- eval-exp-decay
  "Evaluate exponential decay (beat-synced).
   Uses effective-beats for smooth BPM-change animation."
  [{:keys [min max decay-type]
    :or {min 0.0 max 1.0 decay-type :linear}}
   context]
  (let [beats (time/get-beats-from-context context)
        phase (double (mod beats 1.0))
        start-v (double max)
        end-v (double min)]
    (case decay-type
      :exp (let [range-exp (- start-v end-v)
                 decay-factor (Math/exp (* (- phase) 3.0))]
             (+ end-v (* decay-factor range-exp)))
      ;; :linear is default
      (let [range-v (- end-v start-v)]
        (+ start-v (* phase range-v))))))


;; Random and Step Modulators


(defn- eval-random
  "Evaluate random modulator.
   In once mode, generates random values through once-periods cycles then holds at the final position.
   Uses effective-beats for smooth BPM-change animation in loop mode."
  [{:keys [min max period changes-per-beat loop-mode once-periods time-unit]
    :or {min 0.0 max 1.0 period 1.0 changes-per-beat 1.0 loop-mode :loop once-periods 1.0 time-unit :beats}}
   {:keys [time-ms bpm trigger-time] :as context}]
  (let [random-fn (fn [^double p]
                    (let [changes-in-period (double (or changes-per-beat (/ 1.0 (double period))))
                          seed (long (* p changes-in-period))
                          rng (java.util.Random. seed)
                          t (.nextDouble ^java.util.Random rng)
                          range-v (- (double max) (double min))]
                      (+ (double min) (* t range-v))))]
    (if (= loop-mode :once)
      ;; Once mode: complete once-periods cycles then hold at final position
      (let [num-cycles (double (or once-periods 1.0))
            total-duration (* (double period) num-cycles)
            progress (double (time/calculate-once-progress (or time-ms 0) trigger-time total-duration (or time-unit :beats) (or bpm 120.0)))
            ;; Calculate total phase progression (0 to num-cycles)
            total-phase (* progress num-cycles)
            ;; If we've completed all cycles, hold at the final position
            final-phase (if (>= progress 1.0)
                          num-cycles
                          total-phase)]
        (random-fn final-phase))
      ;; Loop mode: use standard phase calculation
      (let [p (time/calculate-modulator-phase context period 0.0 :loop period (or time-unit :beats))]
        (random-fn p)))))

(defn- parse-step-values
  "Parse step values - handles both vectors and EDN strings."
  [values]
  (cond
    (vector? values) values
    (string? values) (try
                       (let [parsed (edn/read-string values)]
                         (if (vector? parsed) parsed [0 1]))
                       (catch Exception _ [0 1]))
    :else [0 1]))

(defn- eval-step
  "Evaluate step modulator.
   In once mode, steps through values once-periods times then holds at the final position.
   Uses effective-beats for smooth BPM-change animation in loop mode."
  [{:keys [values period steps-per-beat loop-mode once-periods time-unit]
    :or {values [0 1] period 1.0 steps-per-beat 1.0 loop-mode :loop once-periods 1.0 time-unit :beats}}
   {:keys [time-ms bpm trigger-time] :as context}]
  (let [parsed-values (parse-step-values values)
        num-values (count parsed-values)
        step-fn (fn [^double p]
                  (let [idx (mod (long (* p (double steps-per-beat))) num-values)]
                    (nth parsed-values idx)))]
    (if (= loop-mode :once)
      ;; Once mode: complete once-periods cycles then hold at final position
      (let [num-cycles (double (or once-periods 1.0))
            total-duration (* (double period) num-cycles)
            progress (double (time/calculate-once-progress (or time-ms 0) trigger-time total-duration (or time-unit :beats) (or bpm 120.0)))]
        (if (>= progress 1.0)
          ;; Completed: return the last value
          (last parsed-values)
          ;; In progress: calculate step based on phase
          (let [total-phase (* progress num-cycles)
                idx (mod (long (* total-phase (double steps-per-beat))) num-values)]
            (nth parsed-values idx))))
      ;; Loop mode: use standard phase calculation
      (let [p (time/calculate-modulator-phase context period 0.0 :loop period (or time-unit :beats))]
        (step-fn p)))))


;; External Input Modulators (MIDI/OSC)


(defn- eval-midi
  "Evaluate MIDI CC modulator."
  [{:keys [channel cc min max]
    :or {channel 1 cc 1 min 0.0 max 1.0}}
   {:keys [midi-state]}]
  (let [cc-val (double (get-in midi-state [[channel cc]] 0))
        range-v (- (double max) (double min))]
    (+ (double min) (* (/ cc-val 127.0) range-v))))

(defn- eval-osc
  "Evaluate OSC parameter modulator."
  [{:keys [path min max]
    :or {path "/control" min 0.0 max 1.0}}
   {:keys [osc-state]}]
  (let [osc-val (double (get osc-state path 0.0))
        range-v (- (double max) (double min))]
    (+ (double min) (* osc-val range-v))))


;; Per-Point Modulators


(defn- eval-point-index
  "Evaluate point index modulator."
  [{:keys [min max wrap?]
    :or {min 0.0 max 1.0 wrap? false}}
   {:keys [point-index point-count]}]
  (if (and point-index point-count (pos? (double point-count)))
    (let [t (/ (double point-index) (clojure.core/max 1.0 (dec (double point-count))))
          range-v (- (double max) (double min))]
      (+ (double min) (* (if wrap? (double (mod t 1.0)) t) range-v)))
    (double min)))

(defn- eval-pos-x
  "Evaluate position X modulator."
  [{:keys [min max] :or {min 0.0 max 1.0}}
   {:keys [x]}]
  (if x
    (let [t (/ (+ (double x) 1.0) 2.0)  ; normalize -1..1 to 0..1
          range-v (- (double max) (double min))]
      (+ (double min) (* t range-v)))
    (double min)))

(defn- eval-pos-y
  "Evaluate position Y modulator."
  [{:keys [min max] :or {min 0.0 max 1.0}}
   {:keys [y]}]
  (if y
    (let [t (/ (+ (double y) 1.0) 2.0)
          range-v (- (double max) (double min))]
      (+ (double min) (* t range-v)))
    (double min)))

(defn- eval-radial
  "Evaluate position radial modulator."
  [{:keys [min max normalize?]
    :or {min 0.0 max 1.0 normalize? true}}
   {:keys [x y]}]
  (if (and x y)
    (let [dist (Math/sqrt (+ (* (double x) (double x))
                             (* (double y) (double y))))
          max-dist (if normalize? (Math/sqrt 2.0) 1.0)
          t (clojure.core/min 1.0 (/ dist max-dist))
          range-v (- (double max) (double min))]
      (+ (double min) (* t range-v)))
    (double min)))


;; Modulator Evaluators Registry


(def modulator-evaluators
  "Map of modulator type keywords to their evaluation functions.
   Each function takes [config context] and returns a value."
  {:sine         eval-sine
   :triangle     eval-triangle
   :sawtooth     eval-sawtooth
   :square       eval-square
   :exp-decay    eval-exp-decay
   :random       eval-random
   :step         eval-step
   :midi         eval-midi
   :osc          eval-osc
   :point-index  eval-point-index
   :pos-x        eval-pos-x
   :pos-y        eval-pos-y
   :radial       eval-radial})

;; Note: The modulator-evaluators map is used by modulators.clj to access
;; evaluator functions during modulator registration.
;; For runtime evaluation, use laser-show.animation.modulation/evaluate-modulator
;; which looks up evaluators via the registry (reg/get-evaluator).
