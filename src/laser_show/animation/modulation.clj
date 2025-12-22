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
  "Wrap a function as a modulator with metadata.
   The config map stores the original parameters for UI reconstruction."
  ([f description]
   (make-modulator f description nil))
  ([f description config]
   (with-meta f {::modulator true
                 ::description description
                 ::config config})))

(defn get-modulator-config
  "Extract the configuration from a modulator function.
   Returns nil if not a modulator or no config available."
  [modulator]
  (when (modulator? modulator)
    (::config (meta modulator))))

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
   (let [min-v (double min-val)
         max-v (double max-val)
         freq (double frequency)
         offset (double phase-offset)]
     (make-modulator
      (fn [{:keys [time-ms bpm]}]
        (let [phase (+ (time/time->beat-phase (double time-ms) (double bpm)) offset)]
          (time/oscillate min-v max-v (* phase freq) :sine)))
      (str "sine(" min-val "-" max-val " @" frequency "x)")
      {:type :sine :min min-val :max max-val :freq frequency :phase phase-offset}))))

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
   (let [min-v (double min-val)
         max-v (double max-val)
         freq (double frequency)
         offset (double phase-offset)]
     (make-modulator
      (fn [{:keys [time-ms bpm]}]
        (let [phase (+ (time/time->beat-phase (double time-ms) (double bpm)) offset)]
          (time/oscillate min-v max-v (* phase freq) :triangle)))
      (str "triangle(" min-val "-" max-val " @" frequency "x)")
      {:type :triangle :min min-val :max max-val :freq frequency :phase phase-offset}))))

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
   (let [min-v (double min-val)
         max-v (double max-val)
         freq (double frequency)
         offset (double phase-offset)]
     (make-modulator
      (fn [{:keys [time-ms bpm]}]
        (let [phase (+ (time/time->beat-phase (double time-ms) (double bpm)) offset)]
          (time/oscillate min-v max-v (* phase freq) :sawtooth)))
      (str "sawtooth(" min-val "-" max-val " @" frequency "x)")
      {:type :sawtooth :min min-val :max max-val :freq frequency :phase phase-offset}))))

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
   (let [min-v (double min-val)
         max-v (double max-val)
         freq (double frequency)
         duty (double duty-cycle)
         offset (double phase-offset)]
     (make-modulator
      (fn [{:keys [time-ms bpm]}]
        (let [phase (+ (time/time->beat-phase (double time-ms) (double bpm)) offset)
              cycle-phase (mod (* phase freq) 1.0)]
          (if (< cycle-phase duty) max-v min-v)))
      (str "square(" min-val "-" max-val " @" frequency "x)")
      {:type :square :min min-val :max max-val :freq frequency :phase phase-offset}))))

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
  (let [min-v (double min-val)
        max-v (double max-val)
        freq (double frequency-hz)]
    (make-modulator
     (fn [{:keys [time-ms]}]
       (let [phase (time/time->phase (double time-ms) freq)]
         (time/oscillate min-v max-v phase :sine)))
     (str "sine-hz(" min-val "-" max-val " @" frequency-hz "Hz)")
     nil)))

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
   (let [min-v (double min-val)
         max-v (double max-val)
         freq (double frequency-hz)
         duty (double duty-cycle)]
     (make-modulator
      (fn [{:keys [time-ms]}]
        (let [phase (time/time->phase (double time-ms) freq)]
          (if (< phase duty) max-v min-v)))
      (str "square-hz(" min-val "-" max-val " @" frequency-hz "Hz)")
      nil))))

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
   (let [start-v (double start-val)
         end-v (double end-val)
         duration (double duration-ms)
         trigger (double trigger-time)
         range-v (- end-v start-v)]
     (make-modulator
      (fn [{:keys [time-ms]}]
        (let [elapsed (- (double time-ms) trigger)
              progress (min 1.0 (/ elapsed duration))]
          (+ start-v (* progress range-v))))
      (str "linear-decay(" start-val "->" end-val " over " duration-ms "ms)")
      nil))))

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
   (let [start-v (double start-val)
         end-v (double end-val)
         half-life (double half-life-ms)
         trigger (double trigger-time)
         range-v (- start-v end-v)
         ln2 (Math/log 2.0)]
     (make-modulator
      (fn [{:keys [time-ms]}]
        (let [elapsed (- (double time-ms) trigger)
              decay-factor (Math/exp (- (/ (* elapsed ln2) half-life)))]
          (+ end-v (* decay-factor range-v))))
      (str "exp-decay(" start-val "->" end-val " t½=" half-life-ms "ms)")
      nil))))

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
   (let [start-v (double start-val)
         end-v (double end-val)
         range-v (- end-v start-v)
         range-exp (- start-v end-v)]
     (make-modulator
      (fn [{:keys [time-ms bpm]}]
        (let [phase (time/time->beat-phase (double time-ms) (double bpm))]
          (case decay-type
            :exp (let [decay-factor (Math/exp (* (- phase) 3.0))]
                   (+ end-v (* decay-factor range-exp)))
            ;; :linear is default
            (+ start-v (* phase range-v)))))
      (str "beat-decay(" start-val "->" end-val " " decay-type ")")
      {:type :beat-decay :min end-val :max start-val :freq 1.0 :phase 0.0}))))

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
  (let [min-v (double min-val)
        max-v (double max-val)
        range-v (- max-v min-v)]
    (make-modulator
     (fn [{:keys [midi-state]}]
       (let [cc-val (double (get-in midi-state [[channel cc]] 0))]
         (+ min-v (* (/ cc-val 127.0) range-v))))
     (str "midi(" channel ":" cc " " min-val "-" max-val ")")
     nil)))

(defn osc-mod
  "Create an OSC parameter modulator.
   Maps OSC value (assumed 0.0-1.0) to the specified range.
   
   Parameters:
   - path: OSC address path
   - min-val: Minimum output value
   - max-val: Maximum output value"
  [path min-val max-val]
  (let [min-v (double min-val)
        max-v (double max-val)
        range-v (- max-v min-v)]
    (make-modulator
     (fn [{:keys [osc-state]}]
       (let [osc-val (double (get osc-state path 0.0))]
         (+ min-v (* osc-val range-v))))
     (str "osc(" path " " min-val "-" max-val ")")
     nil)))

;; ============================================================================
;; Point/Position-Based Modulators
;; ============================================================================

;; These modulators depend on point index or position rather than time.
;; They require additional context: :point-index, :point-count, :x, :y

(defn point-index-mod
  "Create a modulator based on point index in the frame.
   Maps point index (0 to n-1) to the specified range.
   
   Parameters:
   - min-val: Value at first point
   - max-val: Value at last point
   - wrap?: If true, wraps around; if false, clamps (default false)"
  ([min-val max-val]
   (point-index-mod min-val max-val false))
  ([min-val max-val wrap?]
   (let [min-v (double min-val)
         max-v (double max-val)
         range-v (- max-v min-v)]
     (make-modulator
      (fn [{:keys [point-index point-count]}]
        (if (and point-index point-count (pos? point-count))
          (let [t (/ (double point-index) (max 1.0 (dec (double point-count))))]
            (+ min-v (* (if wrap? (mod t 1.0) t) range-v)))
          min-v))
      (str "point-index(" min-val "-" max-val (when wrap? " wrap") ")")
      nil))))

(defn point-index-wave
  "Create a wave modulator based on point index.
   Creates wave patterns along the point sequence.
   
   Parameters:
   - min-val: Minimum value
   - max-val: Maximum value
   - cycles: Number of wave cycles across all points (default 1.0)
   - wave-type: :sine, :triangle, :sawtooth, :square (default :sine)"
  ([min-val max-val]
   (point-index-wave min-val max-val 1.0))
  ([min-val max-val cycles]
   (point-index-wave min-val max-val cycles :sine))
  ([min-val max-val cycles wave-type]
   (let [min-v (double min-val)
         max-v (double max-val)
         cyc (double cycles)]
     (make-modulator
      (fn [{:keys [point-index point-count]}]
        (if (and point-index point-count (pos? point-count))
          (let [t (/ (double point-index) (max 1.0 (double point-count)))
                phase (* t cyc)]
            (time/oscillate min-v max-v phase wave-type))
          min-v))
      (str "point-wave(" min-val "-" max-val " " cycles "x " wave-type ")")
      nil))))

(defn position-x-mod
  "Create a modulator based on X position.
   Maps X coordinate (-1.0 to 1.0) to the specified range.
   
   Parameters:
   - min-val: Value at X=-1
   - max-val: Value at X=1"
  [min-val max-val]
  (let [min-v (double min-val)
        max-v (double max-val)
        range-v (- max-v min-v)]
    (make-modulator
     (fn [{:keys [x]}]
       (if x
         (let [t (/ (+ (double x) 1.0) 2.0)]  ; normalize -1..1 to 0..1
           (+ min-v (* t range-v)))
         min-v))
     (str "pos-x(" min-val "-" max-val ")")
     nil)))

(defn position-y-mod
  "Create a modulator based on Y position.
   Maps Y coordinate (-1.0 to 1.0) to the specified range.
   
   Parameters:
   - min-val: Value at Y=-1
   - max-val: Value at Y=1"
  [min-val max-val]
  (let [min-v (double min-val)
        max-v (double max-val)
        range-v (- max-v min-v)]
    (make-modulator
     (fn [{:keys [y]}]
       (if y
         (let [t (/ (+ (double y) 1.0) 2.0)]  ; normalize -1..1 to 0..1
           (+ min-v (* t range-v)))
         min-v))
     (str "pos-y(" min-val "-" max-val ")")
     nil)))

(defn position-radial-mod
  "Create a modulator based on distance from center.
   Maps distance from origin (0.0 to ~1.41) to the specified range.
   
   Parameters:
   - min-val: Value at center
   - max-val: Value at edge
   - normalize?: If true, normalizes to 0-1 range (default true)"
  ([min-val max-val]
   (position-radial-mod min-val max-val true))
  ([min-val max-val normalize?]
   (let [min-v (double min-val)
         max-v (double max-val)
         range-v (- max-v min-v)
         max-dist (if normalize? (Math/sqrt 2.0) 1.0)]
     (make-modulator
      (fn [{:keys [x y]}]
        (if (and x y)
          (let [dist (Math/sqrt (+ (* (double x) (double x)) 
                                   (* (double y) (double y))))
                t (min 1.0 (/ dist max-dist))]
            (+ min-v (* t range-v)))
          min-v))
      (str "pos-radial(" min-val "-" max-val ")")
      nil))))

(defn position-angle-mod
  "Create a modulator based on angle from center.
   Maps angle (0 to 2π) to the specified range.
   
   Parameters:
   - min-val: Value at angle 0 (positive X axis)
   - max-val: Value at angle 2π"
  [min-val max-val]
  (let [min-v (double min-val)
        max-v (double max-val)
        range-v (- max-v min-v)]
    (make-modulator
     (fn [{:keys [x y]}]
       (if (and x y)
         (let [angle (Math/atan2 (double y) (double x))
               t (/ (+ angle Math/PI) (* 2.0 Math/PI))]  ; normalize -π..π to 0..1
           (+ min-v (* t range-v)))
         min-v))
     (str "pos-angle(" min-val "-" max-val ")")
     nil)))

(defn position-wave
  "Create a wave pattern based on position.
   Creates spatial wave patterns.
   
   Parameters:
   - min-val: Minimum value
   - max-val: Maximum value
   - axis: :x, :y, :radial, or :angle
   - frequency: Wave frequency (cycles per unit, default 1.0)
   - wave-type: :sine, :triangle, :sawtooth, :square (default :sine)"
  ([min-val max-val axis]
   (position-wave min-val max-val axis 1.0))
  ([min-val max-val axis frequency]
   (position-wave min-val max-val axis frequency :sine))
  ([min-val max-val axis frequency wave-type]
   (let [min-v (double min-val)
         max-v (double max-val)
         freq (double frequency)]
     (make-modulator
      (fn [{:keys [x y]}]
        (if (and x y)
          (let [pos-val (case axis
                          :x (double x)
                          :y (double y)
                          :radial (Math/sqrt (+ (* (double x) (double x))
                                                (* (double y) (double y))))
                          :angle (/ (+ (Math/atan2 (double y) (double x)) Math/PI)
                                    (* 2.0 Math/PI)))
                phase (* pos-val freq)]
            (time/oscillate min-v max-v phase wave-type))
          min-v))
      (str "pos-wave(" axis " " min-val "-" max-val " " frequency "x " wave-type ")")
      nil))))

;; ============================================================================
;; Animated Position Modulators (combine position + time)
;; ============================================================================

(defn position-scroll
  "Create a scrolling pattern based on position + time.
   The pattern moves across the shape.
   
   Parameters:
   - min-val: Minimum value
   - max-val: Maximum value
   - axis: :x or :y
   - speed: Scroll speed (units per beat, default 1.0)
   - wave-type: :sine, :triangle, :sawtooth, :square (default :sine)"
  ([min-val max-val axis]
   (position-scroll min-val max-val axis 1.0))
  ([min-val max-val axis speed]
   (position-scroll min-val max-val axis speed :sine))
  ([min-val max-val axis speed wave-type]
   (let [min-v (double min-val)
         max-v (double max-val)
         spd (double speed)]
     (make-modulator
      (fn [{:keys [x y time-ms bpm]}]
        (if (and x y time-ms bpm)
          (let [pos-val (case axis :x (double x) :y (double y))
                time-offset (* (time/time->beat-phase (double time-ms) (double bpm)) spd)
                phase (+ pos-val time-offset)]
            (time/oscillate min-v max-v phase wave-type))
          min-v))
      (str "pos-scroll(" axis " " min-val "-" max-val " @" speed "x " wave-type ")")))))

(defn rainbow-hue
  "Create a rainbow hue pattern based on position + time.
   Specifically for hue values (0-360 range).
   
   Parameters:
   - axis: :x, :y, :radial, or :angle
   - speed: Rotation speed in degrees per second (default 60.0)"
  ([axis]
   (rainbow-hue axis 60.0))
  ([axis speed]
   (let [spd (double speed)]
     (make-modulator
      (fn [{:keys [x y time-ms]}]
        (if (and x y time-ms)
          (let [position (case axis
                           :x (/ (+ (double x) 1.0) 2.0)
                           :y (/ (+ (double y) 1.0) 2.0)
                           :radial (Math/sqrt (+ (* (double x) (double x))
                                                 (* (double y) (double y))))
                           :angle (/ (+ (Math/atan2 (double y) (double x)) Math/PI)
                                     (* 2.0 Math/PI)))
                time-offset (mod (* (/ (double time-ms) 1000.0) spd) 360.0)]
            (mod (+ (* position 360.0) time-offset) 360.0))
          0.0))
      (str "rainbow-hue(" axis " @" speed "°/s)")))))

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
   (let [min-v (double min-val)
         max-v (double max-val)
         range-v (- max-v min-v)
         rate (double changes-per-beat)]
     (make-modulator
      (fn [{:keys [time-ms bpm]}]
        (let [beats (time/ms->beats (double time-ms) (double bpm))
              seed (long (* beats rate))
              rng (java.util.Random. seed)
              t (.nextDouble ^java.util.Random rng)]
          (+ min-v (* t range-v))))
      (str "random(" min-val "-" max-val " @" changes-per-beat "x)")))))

(defn step-mod
  "Create a stepped modulator.
   Cycles through a sequence of values.
   
   Parameters:
   - values: Vector of values to cycle through
   - steps-per-beat: How many values per beat (default 1.0)"
  ([values]
   (step-mod values 1.0))
  ([values steps-per-beat]
   (let [rate (double steps-per-beat)
         cnt (count values)]
     (make-modulator
      (fn [{:keys [time-ms bpm]}]
        (let [beats (time/ms->beats (double time-ms) (double bpm))
              idx (mod (long (* beats rate)) cnt)]
          (nth values idx)))
      (str "step(" cnt " values @" steps-per-beat "x)")))))

;; ============================================================================
;; Modulator Combinators
;; ============================================================================

(defn add-mod
  "Add two modulators together."
  [mod-a mod-b]
  (make-modulator
   (fn [context]
     (+ (double (resolve-param mod-a context))
        (double (resolve-param mod-b context))))
   "add"))

(defn mult-mod
  "Multiply two modulators together."
  [mod-a mod-b]
  (make-modulator
   (fn [context]
     (* (double (resolve-param mod-a context))
        (double (resolve-param mod-b context))))
   "mult"))

(defn clamp-mod
  "Clamp a modulator's output to a range."
  [mod-source min-val max-val]
  (let [min-v (double min-val)
        max-v (double max-val)]
    (make-modulator
     (fn [context]
       (let [v (double (resolve-param mod-source context))]
         (max min-v (min max-v v))))
     (str "clamp(" min-val "-" max-val ")"))))

(defn invert-mod
  "Invert a modulator (1.0 - value, assuming 0-1 range)."
  [mod-source]
  (make-modulator
   (fn [context]
     (- 1.0 (double (resolve-param mod-source context))))
   "invert"))

;; ============================================================================
;; Preset Modulators
;; ============================================================================

(def presets
  "Common modulator presets."
  {;; === Time-based modulators ===
   
   ;; Gentle pulsing (1 cycle per beat, 70-100% range)
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
   :wobble (sine-mod 0.9 1.1 4.0)
   
   ;; === Position-based modulators ===
   
   ;; Fade across X axis (left to right)
   :fade-x (position-x-mod 0.0 1.0)
   
   ;; Fade across Y axis (bottom to top)
   :fade-y (position-y-mod 0.0 1.0)
   
   ;; Radial fade from center
   :fade-radial (position-radial-mod 1.0 0.0)
   
   ;; Radial glow (bright center, dim edges)
   :glow-center (position-radial-mod 1.0 0.3)
   
   ;; Rainbow across X (hue values 0-360)
   :rainbow-x (rainbow-hue :x 60.0)
   
   ;; Rainbow across Y (hue values 0-360)
   :rainbow-y (rainbow-hue :y 60.0)
   
   ;; Rainbow by angle (hue values 0-360)
   :rainbow-angle (rainbow-hue :angle 60.0)
   
   ;; === Point-index modulators ===
   
   ;; Fade along drawing path
   :fade-path (point-index-mod 0.0 1.0)
   
   ;; Wave along drawing path
   :wave-path (point-index-wave 0.3 1.0 2.0)})

(defn preset
  "Get a preset modulator by keyword.
   Returns the modulator or nil if not found."
  [preset-key]
  (get presets preset-key))
