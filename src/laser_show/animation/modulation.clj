(ns laser-show.animation.modulation
  "Parameter modulation system for effects.
   Provides modulator functions that can be used as effect parameters.
   
   Usage:
   ;; Static value (backward compatible)
   {:effect-id :scale :params {:x-scale 1.5}}
   
   ;; Modulated value
   {:effect-id :scale :params {:x-scale (sine-mod 0.8 1.2 2.0)}}
   
   ;; MIDI controlled
   {:effect-id :scale :params {:x-scale (midi-mod 1 7 0.5 2.0)}}"
  (:require [laser-show.animation.time :as time]))

;; ============================================================================
;; Modulation Context
;; ============================================================================

(defn make-context
  "Create a modulation context for parameter resolution.
   
   Parameters:
   - time-ms: Current time in milliseconds
   - bpm: Current BPM
   - midi-state: Map of {[channel cc] -> value} (optional)
   - osc-state: Map of {path -> value} (optional)"
  [{:keys [time-ms bpm midi-state osc-state]
    :or {midi-state {} osc-state {}}}]
  {:time-ms time-ms
   :bpm bpm
   :midi-state midi-state
   :osc-state osc-state})

;; ============================================================================
;; Parameter Resolution
;; ============================================================================

(defn modulator?
  "Check if a value is a modulator function."
  [x]
  (and (fn? x) (::modulator (meta x))))

(defn resolve-param
  "Resolve a parameter value.
   If the param is a modulator function, call it with context.
   If it's a static value, return it as-is."
  [param context]
  (if (modulator? param)
    (param context)
    param))

(defn resolve-params
  "Resolve all parameters in a params map."
  [params context]
  (into {}
        (map (fn [[k v]] [k (resolve-param v context)]))
        params))

;; ============================================================================
;; Modulator Constructor Helper
;; ============================================================================

(defn- make-modulator
  "Wrap a function as a modulator with metadata."
  [f description]
  (with-meta f {::modulator true
                ::description description}))

;; ============================================================================
;; Waveform Modulators (BPM-synced)
;; ============================================================================

(defn sine-mod
  "Create a sine wave modulator (BPM-synced).
   
   Parameters:
   - min-val: Minimum value
   - max-val: Maximum value
   - frequency: Cycles per beat (default 1.0)
   - phase-offset: Phase offset 0.0-1.0 (default 0.0)"
  ([min-val max-val]
   (sine-mod min-val max-val 1.0))
  ([min-val max-val frequency]
   (sine-mod min-val max-val frequency 0.0))
  ([min-val max-val frequency phase-offset]
   (make-modulator
    (fn [{:keys [time-ms bpm]}]
      (let [phase (+ (time/time->beat-phase time-ms bpm) phase-offset)]
        (time/oscillate min-val max-val (* phase frequency) :sine)))
    (str "sine(" min-val "-" max-val " @" frequency "x)"))))

(defn triangle-mod
  "Create a triangle wave modulator (BPM-synced).
   
   Parameters:
   - min-val: Minimum value
   - max-val: Maximum value
   - frequency: Cycles per beat (default 1.0)
   - phase-offset: Phase offset 0.0-1.0 (default 0.0)"
  ([min-val max-val]
   (triangle-mod min-val max-val 1.0))
  ([min-val max-val frequency]
   (triangle-mod min-val max-val frequency 0.0))
  ([min-val max-val frequency phase-offset]
   (make-modulator
    (fn [{:keys [time-ms bpm]}]
      (let [phase (+ (time/time->beat-phase time-ms bpm) phase-offset)]
        (time/oscillate min-val max-val (* phase frequency) :triangle)))
    (str "triangle(" min-val "-" max-val " @" frequency "x)"))))

(defn sawtooth-mod
  "Create a sawtooth wave modulator (BPM-synced).
   Ramps from min to max, then resets.
   
   Parameters:
   - min-val: Minimum value
   - max-val: Maximum value
   - frequency: Cycles per beat (default 1.0)
   - phase-offset: Phase offset 0.0-1.0 (default 0.0)"
  ([min-val max-val]
   (sawtooth-mod min-val max-val 1.0))
  ([min-val max-val frequency]
   (sawtooth-mod min-val max-val frequency 0.0))
  ([min-val max-val frequency phase-offset]
   (make-modulator
    (fn [{:keys [time-ms bpm]}]
      (let [phase (+ (time/time->beat-phase time-ms bpm) phase-offset)]
        (time/oscillate min-val max-val (* phase frequency) :sawtooth)))
    (str "sawtooth(" min-val "-" max-val " @" frequency "x)"))))

(defn square-mod
  "Create a square wave modulator (BPM-synced).
   Alternates between min and max values.
   
   Parameters:
   - min-val: Minimum value (off state)
   - max-val: Maximum value (on state)
   - frequency: Cycles per beat (default 1.0)
   - duty-cycle: On-time ratio 0.0-1.0 (default 0.5)
   - phase-offset: Phase offset 0.0-1.0 (default 0.0)"
  ([min-val max-val]
   (square-mod min-val max-val 1.0))
  ([min-val max-val frequency]
   (square-mod min-val max-val frequency 0.5))
  ([min-val max-val frequency duty-cycle]
   (square-mod min-val max-val frequency duty-cycle 0.0))
  ([min-val max-val frequency duty-cycle phase-offset]
   (make-modulator
    (fn [{:keys [time-ms bpm]}]
      (let [phase (+ (time/time->beat-phase time-ms bpm) phase-offset)
            cycle-phase (mod (* phase frequency) 1.0)]
        (if (< cycle-phase duty-cycle) max-val min-val)))
    (str "square(" min-val "-" max-val " @" frequency "x)"))))

;; ============================================================================
;; Time-based Modulators (Hz, not BPM-synced)
;; ============================================================================

(defn sine-hz
  "Create a sine wave modulator at fixed Hz frequency.
   
   Parameters:
   - min-val: Minimum value
   - max-val: Maximum value
   - frequency-hz: Cycles per second"
  [min-val max-val frequency-hz]
  (make-modulator
   (fn [{:keys [time-ms]}]
     (let [phase (time/time->phase time-ms frequency-hz)]
       (time/oscillate min-val max-val phase :sine)))
   (str "sine-hz(" min-val "-" max-val " @" frequency-hz "Hz)")))

(defn square-hz
  "Create a square wave modulator at fixed Hz frequency.
   
   Parameters:
   - min-val: Minimum value (off state)
   - max-val: Maximum value (on state)
   - frequency-hz: Cycles per second
   - duty-cycle: On-time ratio 0.0-1.0 (default 0.5)"
  ([min-val max-val frequency-hz]
   (square-hz min-val max-val frequency-hz 0.5))
  ([min-val max-val frequency-hz duty-cycle]
   (make-modulator
    (fn [{:keys [time-ms]}]
      (let [phase (time/time->phase time-ms frequency-hz)]
        (if (< phase duty-cycle) max-val min-val)))
    (str "square-hz(" min-val "-" max-val " @" frequency-hz "Hz)"))))

;; ============================================================================
;; One-shot Modulators (Decay/Envelope)
;; ============================================================================

(defn linear-decay
  "Create a linear decay modulator.
   Decays from start-val to end-val over duration.
   
   Parameters:
   - start-val: Starting value
   - end-val: Ending value
   - duration-ms: Duration in milliseconds
   - trigger-time: Time when decay started (default 0)"
  ([start-val end-val duration-ms]
   (linear-decay start-val end-val duration-ms 0))
  ([start-val end-val duration-ms trigger-time]
   (make-modulator
    (fn [{:keys [time-ms]}]
      (let [elapsed (- time-ms trigger-time)
            progress (min 1.0 (/ elapsed duration-ms))]
        (+ start-val (* progress (- end-val start-val)))))
    (str "linear-decay(" start-val "->" end-val " over " duration-ms "ms)"))))

(defn exp-decay
  "Create an exponential decay modulator.
   Decays exponentially from start-val toward end-val.
   
   Parameters:
   - start-val: Starting value
   - end-val: Ending value (asymptote)
   - half-life-ms: Time for value to decay halfway
   - trigger-time: Time when decay started (default 0)"
  ([start-val end-val half-life-ms]
   (exp-decay start-val end-val half-life-ms 0))
  ([start-val end-val half-life-ms trigger-time]
   (make-modulator
    (fn [{:keys [time-ms]}]
      (let [elapsed (- time-ms trigger-time)
            decay-factor (Math/exp (- (/ (* elapsed (Math/log 2)) half-life-ms)))]
        (+ end-val (* decay-factor (- start-val end-val)))))
    (str "exp-decay(" start-val "->" end-val " t½=" half-life-ms "ms)"))))

(defn beat-decay
  "Create a decay that resets on each beat.
   Useful for beat-synced intensity effects.
   
   Parameters:
   - start-val: Value at beat start
   - end-val: Value at beat end
   - decay-type: :linear or :exp (default :linear)"
  ([start-val end-val]
   (beat-decay start-val end-val :linear))
  ([start-val end-val decay-type]
   (make-modulator
    (fn [{:keys [time-ms bpm]}]
      (let [phase (time/time->beat-phase time-ms bpm)]
        (case decay-type
          :exp (let [decay-factor (Math/exp (- (* phase 3)))]
                 (+ end-val (* decay-factor (- start-val end-val))))
          ;; :linear is default
          (+ start-val (* phase (- end-val start-val))))))
    (str "beat-decay(" start-val "->" end-val " " decay-type ")"))))

;; ============================================================================
;; External Control Modulators
;; ============================================================================

(defn midi-mod
  "Create a MIDI CC modulator.
   Maps MIDI CC value (0-127) to the specified range.
   
   Parameters:
   - channel: MIDI channel (1-16)
   - cc: CC number (0-127)
   - min-val: Minimum output value (when CC=0)
   - max-val: Maximum output value (when CC=127)"
  [channel cc min-val max-val]
  (make-modulator
   (fn [{:keys [midi-state]}]
     (let [cc-val (get-in midi-state [[channel cc]] 0)]
       (+ min-val (* (/ cc-val 127.0) (- max-val min-val)))))
   (str "midi(" channel ":" cc " " min-val "-" max-val ")")))

(defn osc-mod
  "Create an OSC parameter modulator.
   Maps OSC value (assumed 0.0-1.0) to the specified range.
   
   Parameters:
   - path: OSC address path
   - min-val: Minimum output value
   - max-val: Maximum output value"
  [path min-val max-val]
  (make-modulator
   (fn [{:keys [osc-state]}]
     (let [osc-val (get osc-state path 0.0)]
       (+ min-val (* osc-val (- max-val min-val)))))
   (str "osc(" path " " min-val "-" max-val ")")))

;; ============================================================================
;; Utility Modulators
;; ============================================================================

(defn constant
  "Create a constant value modulator.
   Useful for making static values explicit."
  [value]
  (make-modulator
   (fn [_context] value)
   (str "constant(" value ")")))

(defn random-mod
  "Create a random modulator.
   Generates a new random value at the specified rate.
   
   Parameters:
   - min-val: Minimum value
   - max-val: Maximum value
   - changes-per-beat: How often to change value (default 1.0)"
  ([min-val max-val]
   (random-mod min-val max-val 1.0))
  ([min-val max-val changes-per-beat]
   (make-modulator
    (fn [{:keys [time-ms bpm]}]
      (let [beats (time/ms->beats time-ms bpm)
            seed (int (* beats changes-per-beat))
            rng (java.util.Random. seed)
            t (.nextDouble rng)]
        (+ min-val (* t (- max-val min-val)))))
    (str "random(" min-val "-" max-val " @" changes-per-beat "x)"))))

(defn step-mod
  "Create a stepped modulator.
   Cycles through a sequence of values.
   
   Parameters:
   - values: Vector of values to cycle through
   - steps-per-beat: How many values per beat (default 1.0)"
  ([values]
   (step-mod values 1.0))
  ([values steps-per-beat]
   (make-modulator
    (fn [{:keys [time-ms bpm]}]
      (let [beats (time/ms->beats time-ms bpm)
            idx (mod (int (* beats steps-per-beat)) (count values))]
        (nth values idx)))
    (str "step(" (count values) " values @" steps-per-beat "x)"))))

;; ============================================================================
;; Modulator Combinators
;; ============================================================================

(defn add-mod
  "Add two modulators together."
  [mod-a mod-b]
  (make-modulator
   (fn [context]
     (+ (resolve-param mod-a context)
        (resolve-param mod-b context)))
   "add"))

(defn mult-mod
  "Multiply two modulators together."
  [mod-a mod-b]
  (make-modulator
   (fn [context]
     (* (resolve-param mod-a context)
        (resolve-param mod-b context)))
   "mult"))

(defn clamp-mod
  "Clamp a modulator's output to a range."
  [mod-source min-val max-val]
  (make-modulator
   (fn [context]
     (let [v (resolve-param mod-source context)]
       (max min-val (min max-val v))))
   (str "clamp(" min-val "-" max-val ")")))

(defn invert-mod
  "Invert a modulator (1.0 - value, assuming 0-1 range)."
  [mod-source]
  (make-modulator
   (fn [context]
     (- 1.0 (resolve-param mod-source context)))
   "invert"))

;; ============================================================================
;; Preset Modulators
;; ============================================================================

(def presets
  "Common modulator presets."
  {;; Gentle pulsing (1 cycle per beat, 70-100% range)
   :gentle-pulse (sine-mod 0.7 1.0 1.0)
   
   ;; Strong pulsing (2 cycles per beat, 30-100% range)
   :strong-pulse (sine-mod 0.3 1.0 2.0)
   
   ;; Slow breathing (1 cycle per 4 beats)
   :breathe (sine-mod 0.5 1.0 0.25)
   
   ;; 4x strobe (4 flashes per beat)
   :strobe-4x (square-mod 0.0 1.0 4.0 0.1)
   
   ;; 8x strobe (8 flashes per beat)
   :strobe-8x (square-mod 0.0 1.0 8.0 0.1)
   
   ;; Beat flash (bright on beat, decay)
   :beat-flash (beat-decay 2.0 1.0 :exp)
   
   ;; Ramp up (sawtooth from 0 to 1)
   :ramp-up (sawtooth-mod 0.0 1.0 1.0)
   
   ;; Ramp down (sawtooth from 1 to 0)
   :ramp-down (sawtooth-mod 1.0 0.0 1.0)
   
   ;; Wobble (fast small oscillation)
   :wobble (sine-mod 0.9 1.1 4.0)})

(defn preset
  "Get a preset modulator by keyword.
   Returns the modulator or nil if not found."
  [preset-key]
  (get presets preset-key))
