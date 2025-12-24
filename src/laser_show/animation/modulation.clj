(ns laser-show.animation.modulation
  "Parameter modulation system for effects.
   
   Modulators are represented as pure data configs that can be serialized to EDN.
   The modulator functions are created at runtime when parameters are resolved.
   
   Usage:
   ;; Static value
   {:effect-id :scale :params {:x-scale 1.5}}
   
   ;; Modulated value (pure data config - serializable!)
   {:effect-id :scale :params {:x-scale {:type :sine :min 0.8 :max 1.2 :period 0.5}}}
   
   ;; MIDI controlled
   {:effect-id :scale :params {:x-scale {:type :midi :channel 1 :cc 7 :min 0.5 :max 2.0}}}"
  (:require [laser-show.animation.time :as time]))

;; ============================================================================
;; Modulation Context
;; ============================================================================

(defn make-context
  "Create a modulation context for parameter resolution.
   
   Parameters:
   - time-ms: Current time in milliseconds
   - bpm: Current BPM
   - trigger-time: Time when the cue/effect was triggered (optional, for once-mode modulators)
   - midi-state: Map of {[channel cc] -> value} (optional)
   - osc-state: Map of {path -> value} (optional)"
  [{:keys [time-ms bpm trigger-time midi-state osc-state]
    :or {midi-state {} osc-state {}}}]
  {:time-ms time-ms
   :bpm bpm
   :trigger-time trigger-time
   :midi-state midi-state
   :osc-state osc-state})

;; ============================================================================
;; Parameter Resolution
;; ============================================================================

(defn modulator?
  "Check if a value is a modulator function."
  [x]
  (and (fn? x) (::modulator (meta x))))

(defn modulator-config?
  "Check if a value is a modulator config map (pure data representation).
   Modulator configs are maps with a :type key that matches a known modulator type."
  [x]
  (and (map? x)
       (contains? x :type)
       (contains? #{:sine :triangle :sawtooth :square :beat-decay :exp-decay :random
                    :pos-x :pos-y :radial :angle :point-index :point-wave :pos-wave
                    :pos-scroll :rainbow-hue :midi :osc :constant}
                  (:type x))))

;; Forward declaration - will be defined after modulator constructors
(declare create-modulator-from-config)

(defn resolve-param
  "Resolve a parameter value.
   - If the param is a modulator config map (pure data), create the function and call it.
   - If the param is a modulator function, call it directly.
   - If it's a static value (number, string, etc.), return it as-is.
   
   Note: For storage/serialization, always use config maps (pure data).
   Modulator functions are only used internally (combinators, presets, tests)."
  [param context]
  (cond
    (modulator-config? param) ((create-modulator-from-config param) context)
    (modulator? param) (param context)
    :else param))

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
   The config map stores the original parameters for UI reconstruction.
   The per-point? flag indicates whether the modulator requires per-point context
   (x, y, point-index, point-count)."
  ([f description]
   (make-modulator f description nil false))
  ([f description config]
   (make-modulator f description config false))
  ([f description config per-point?]
   (with-meta f {::modulator true
                 ::description description
                 ::config config
                 ::per-point per-point?})))

(defn get-modulator-config
  "Extract the configuration from a modulator function.
   Returns nil if not a modulator or no config available."
  [modulator]
  (when (modulator? modulator)
    (::config (meta modulator))))

(defn requires-per-point-context?
  "Check if a modulator requires per-point context (x, y, point-index, point-count).
   Returns true if the modulator has the ::per-point metadata flag set."
  [modulator]
  (when (modulator? modulator)
    (::per-point (meta modulator))))

(defn any-param-requires-per-point?
  "Check if any parameter in a params map requires per-point context.
   Recursively checks all values, including nested maps and collections."
  [params]
  (cond
    (modulator? params)
    (requires-per-point-context? params)
    
    (map? params)
    (some any-param-requires-per-point? (vals params))
    
    (coll? params)
    (some any-param-requires-per-point? params)
    
    :else
    false))

;; ============================================================================
;; Period/Frequency Conversion
;; ============================================================================

(defn- period->frequency
  "Convert period (beats per cycle) to frequency (cycles per beat).
   Period of 0 is treated as infinite frequency (returns a large number)."
  ^double [^double period]
  (if (zero? period)
    1000000.0  ; effectively instant
    (/ 1.0 period)))

;; ============================================================================
;; Once-Mode Helper Functions
;; ============================================================================

(defn- calculate-once-progress
  "Calculate progress (0.0 to 1.0) for once-mode modulators.
   
   Parameters:
   - time-ms: Current time in milliseconds
   - trigger-time: Time when modulator was triggered (falls back to 0 if nil)
   - duration: Duration value
   - time-unit: :beats or :seconds
   - bpm: Current BPM (required for :beats time-unit)
   
   Returns: Progress value clamped to 0.0-1.0"
  [time-ms trigger-time duration time-unit bpm]
  (let [start-time (double (or trigger-time 0.0))
        elapsed (- time-ms start-time)
        duration-ms (if (= time-unit :seconds)
                      (* duration 1000.0)
                      (time/beats->ms duration bpm))]
    (if (pos? duration-ms)
      (min 1.0 (max 0.0 (/ elapsed duration-ms)))
      1.0)))

(defn- resolve-trigger-time
  "Resolve trigger time from either a fixed value or an atom reference.
   Returns the trigger time as a number, or nil if not available."
  [trigger-source]
  (cond
    ;; It's an atom - deref it to get current value
    (instance? clojure.lang.IDeref trigger-source) @trigger-source
    ;; It's a number - use directly
    (number? trigger-source) trigger-source
    ;; Nil or other - return nil
    :else nil))

(defn- calculate-modulator-phase
  "Calculate the phase for a modulator based on loop-mode and timing settings.
   
   Parameters:
   - context: Modulation context with :time-ms, :bpm, and optionally :trigger-time
   - period: Beats per cycle (converted to frequency internally)
   - phase-offset: Phase offset (0.0-1.0)
   - loop-mode: :loop or :once
   - duration: Duration for once-mode
   - time-unit: :beats or :seconds
   - trigger-override: Optional trigger time (can be a number OR an atom containing a number)
                       to use instead of context's trigger-time
   
   Returns: Phase value for oscillation
   
   Both loop and once modes now use relative time (elapsed since trigger).
   This ensures retriggering always starts from phase 0.
   
   IMPORTANT: We use raw beat count (ms->beats) NOT beat-phase (mod 1.0).
   The mod operation must happen AFTER frequency multiplication, not before.
   Otherwise periods > 1.0 (slow oscillations over multiple beats) will jump
   at every beat boundary."
  ([context period phase-offset loop-mode duration time-unit]
   (calculate-modulator-phase context period phase-offset loop-mode duration time-unit nil))
  ([{:keys [time-ms bpm trigger-time]} period phase-offset loop-mode duration time-unit trigger-override]
   (let [time-ms (double time-ms)
         bpm (double bpm)
         frequency (period->frequency (double period))
         ;; Resolve trigger-override (may be atom or value), fall back to context's trigger-time
         effective-trigger-time (or (resolve-trigger-time trigger-override) trigger-time)
         ;; Calculate elapsed time from trigger (or from 0 if no trigger-time)
         start-time (double (or effective-trigger-time 0.0))
         elapsed (- time-ms start-time)]
     (if (= loop-mode :once)
       ;; Once mode: single cycle (or frequency cycles) over duration
       (let [progress (calculate-once-progress time-ms effective-trigger-time duration time-unit bpm)]
         (+ (* progress frequency) phase-offset))
       ;; Loop mode: continuous cycling based on ELAPSED time (relative, not global)
       ;; Use raw beat count - the oscillate function handles normalization via mod
       ;; Phase offset is added AFTER frequency multiplication for correct behavior
       (let [beats (time/ms->beats elapsed bpm)]
         (+ (* beats frequency) phase-offset))))))

;; ============================================================================
;; Waveform Modulators (BPM-synced, with optional once-mode)
;; ============================================================================

(defn sine-mod
  "Create a sine wave modulator (BPM-synced by default, or time-based with once mode).
   
   Parameters:
   - min-val: Minimum value
   - max-val: Maximum value
   - period: Beats per cycle in loop mode, or cycles over duration in once mode (default 1.0)
   - phase-offset: Phase offset 0.0-1.0 (default 0.0)
   - loop-mode: :loop (continuous, default) or :once (single run then hold)
   - duration: Duration for once mode (in beats or seconds)
   - time-unit: :beats (default) or :seconds
   
   The trigger time for once-mode modulators comes from the modulation context,
   which is provided when effects are applied (see effects.clj apply-effect)."
  ([min-val max-val]
   (sine-mod min-val max-val 1.0))
  ([min-val max-val period]
   (sine-mod min-val max-val period 0.0))
  ([min-val max-val period phase-offset]
   (sine-mod min-val max-val period phase-offset :loop 1.0 :beats))
  ([min-val max-val period phase-offset loop-mode duration time-unit]
   (let [min-v (double min-val)
         max-v (double max-val)
         per (double period)
         offset (double phase-offset)
         dur (double duration)
         tunit time-unit]
     (make-modulator
      (fn [context]
        (let [phase (calculate-modulator-phase context per offset loop-mode dur tunit)]
          (time/oscillate min-v max-v phase :sine)))
      (str "sine(" min-val "-" max-val " @" period " beats"
           (when (= loop-mode :once) (str " once:" duration (name tunit))) ")")
      {:type :sine :min min-val :max max-val :period period :phase phase-offset
       :loop-mode (or loop-mode :loop) :duration dur :time-unit tunit}))))

(defn triangle-mod
  "Create a triangle wave modulator (BPM-synced by default, or time-based with once mode).
   
   Parameters:
   - min-val: Minimum value
   - max-val: Maximum value
   - period: Beats per cycle in loop mode, or cycles over duration in once mode (default 1.0)
   - phase-offset: Phase offset 0.0-1.0 (default 0.0)
   - loop-mode: :loop (continuous, default) or :once (single run then hold)
   - duration: Duration for once mode (in beats or seconds)
   - time-unit: :beats (default) or :seconds
   
   The trigger time for once-mode modulators comes from the modulation context,
   which is provided when effects are applied (see effects.clj apply-effect)."
  ([min-val max-val]
   (triangle-mod min-val max-val 1.0))
  ([min-val max-val period]
   (triangle-mod min-val max-val period 0.0))
  ([min-val max-val period phase-offset]
   (triangle-mod min-val max-val period phase-offset :loop 1.0 :beats))
  ([min-val max-val period phase-offset loop-mode duration time-unit]
   (let [min-v (double min-val)
         max-v (double max-val)
         per (double period)
         offset (double phase-offset)
         dur (double duration)
         tunit time-unit]
     (make-modulator
      (fn [context]
        (let [phase (calculate-modulator-phase context per offset loop-mode dur tunit)]
          (time/oscillate min-v max-v phase :triangle)))
      (str "triangle(" min-val "-" max-val " @" period " beats"
           (when (= loop-mode :once) (str " once:" duration (name tunit))) ")")
      {:type :triangle :min min-val :max max-val :period period :phase phase-offset
       :loop-mode (or loop-mode :loop) :duration dur :time-unit tunit}))))

(defn sawtooth-mod
  "Create a sawtooth wave modulator (BPM-synced).
   Ramps from min to max, then resets.
   
   Parameters:
   - min-val: Minimum value
   - max-val: Maximum value
   - period: Beats per cycle (default 1.0)
   - phase-offset: Phase offset 0.0-1.0 (default 0.0)"
  ([min-val max-val]
   (sawtooth-mod min-val max-val 1.0))
  ([min-val max-val period]
   (sawtooth-mod min-val max-val period 0.0))
  ([min-val max-val period phase-offset]
   (let [min-v (double min-val)
         max-v (double max-val)
         freq (period->frequency (double period))
         offset (double phase-offset)]
     (make-modulator
      (fn [{:keys [time-ms bpm]}]
        ;; Use raw beat count, not beat-phase (which applies mod 1.0 prematurely)
        ;; Phase offset is added AFTER frequency multiplication
        (let [beats (time/ms->beats (double time-ms) (double bpm))
              phase (+ (* beats freq) offset)]
          (time/oscillate min-v max-v phase :sawtooth)))
      (str "sawtooth(" min-val "-" max-val " @" period " beats)")
      {:type :sawtooth :min min-val :max max-val :period period :phase phase-offset}))))

(defn square-mod
  "Create a square wave modulator (BPM-synced).
   Alternates between min and max values.
   
   Parameters:
   - min-val: Minimum value (off state)
   - max-val: Maximum value (on state)
   - period: Beats per cycle (default 1.0)
   - duty-cycle: On-time ratio 0.0-1.0 (default 0.5)
   - phase-offset: Phase offset 0.0-1.0 (default 0.0)"
  ([min-val max-val]
   (square-mod min-val max-val 1.0))
  ([min-val max-val period]
   (square-mod min-val max-val period 0.5))
  ([min-val max-val period duty-cycle]
   (square-mod min-val max-val period duty-cycle 0.0))
  ([min-val max-val period duty-cycle phase-offset]
   (let [min-v (double min-val)
         max-v (double max-val)
         freq (period->frequency (double period))
         duty (double duty-cycle)
         offset (double phase-offset)]
     (make-modulator
      (fn [{:keys [time-ms bpm]}]
        ;; Use raw beat count, not beat-phase (which applies mod 1.0 prematurely)
        ;; Phase offset is added AFTER frequency multiplication
        (let [beats (time/ms->beats (double time-ms) (double bpm))
              cycle-phase (mod (+ (* beats freq) offset) 1.0)]
          (if (< cycle-phase duty) max-v min-v)))
      (str "square(" min-val "-" max-val " @" period " beats)")
      {:type :square :min min-val :max max-val :period period :phase phase-offset}))))

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

(defn halflife-decay
  "Create a half-life based exponential decay modulator.
   Decays exponentially from start-val toward end-val based on half-life.
   
   Parameters:
   - start-val: Starting value
   - end-val: Ending value (asymptote)
   - half-life-ms: Time for value to decay halfway
   - trigger-time: Time when decay started (default 0)"
  ([start-val end-val half-life-ms]
   (halflife-decay start-val end-val half-life-ms 0))
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
      (str "halflife-decay(" start-val "->" end-val " t½=" half-life-ms "ms)")
      nil))))

(defn exp-decay
  "Create an exponential decay modulator.
   Useful for beat-synced intensity effects.
   
   NOTE: This was previously named 'beat-decay' but renamed to 'exp-decay'
   to be timing-mode agnostic (works with both beats and seconds modes).
   
   Parameters:
   - start-val: Value at cycle start
   - end-val: Value at cycle end
   - decay-type: :linear or :exp (default :linear)"
  ([start-val end-val]
   (exp-decay start-val end-val :linear))
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
      (str "exp-decay(" start-val "->" end-val " " decay-type ")")
      {:type :exp-decay :min end-val :max start-val :period-beats 1.0 :phase-beats 0.0}))))

;; Backward compatibility alias
(def beat-decay exp-decay)

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
      nil
      true))))  ; per-point modulator

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
      nil
      true))))  ; per-point modulator

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
     nil
     true)))  ; per-point modulator

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
     nil
     true)))  ; per-point modulator

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
      nil
      true))))  ; per-point modulator

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
     nil
     true)))  ; per-point modulator

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
      nil
      true))))  ; per-point modulator

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
      (str "pos-scroll(" axis " " min-val "-" max-val " @" speed "x " wave-type ")")
      nil
      true))))  ; per-point modulator

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
      (str "rainbow-hue(" axis " @" speed "°/s)")
      nil
      true))))  ; per-point modulator

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
   
   ;; Gentle pulsing (1 beat period, 70-100% range)
   :gentle-pulse (sine-mod 0.7 1.0 1.0)

   ;; Strong pulsing (0.5 beat period, 30-100% range)
   :strong-pulse (sine-mod 0.3 1.0 0.5)

   ;; Slow breathing (4 beat period)
   :breathe (sine-mod 0.5 1.0 4.0)

   ;; 4x strobe (0.25 beat period)
   :strobe-4x (square-mod 0.0 1.0 0.25 0.1)

   ;; 8x strobe (0.125 beat period)
   :strobe-8x (square-mod 0.0 1.0 0.125 0.1)

   ;; Beat flash (bright on beat, decay)
   :beat-flash (beat-decay 2.0 1.0 :exp)

   ;; Ramp up (sawtooth from 0 to 1)
   :ramp-up (sawtooth-mod 0.0 1.0 1.0)

   ;; Ramp down (sawtooth from 1 to 0)
   :ramp-down (sawtooth-mod 1.0 0.0 1.0)

   ;; Wobble (fast small oscillation, 0.25 beat period)
   :wobble (sine-mod 0.9 1.1 0.25)
   
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

;; ============================================================================
;; Create Modulator from Config (Pure Data -> Function)
;; ============================================================================

(defn create-modulator-from-config
  "Create a modulator function from a pure data config map.
   
   This is the inverse of storing modulator configs - it takes a config map
   and returns the corresponding modulator function.
   
   Config map format:
   {:type :sine      ;; Modulator type (required)
    :min 0.0         ;; Min value
    :max 1.0         ;; Max value  
    :period 1.0      ;; Period (beats per cycle)
    :phase 0.0       ;; Phase offset
    :loop-mode :loop ;; Loop mode (:loop or :once)
    :duration 2.0    ;; Duration for once mode
    :time-unit :beats ;; Time unit for once mode
    ... other type-specific params}
   
   Returns: Modulator function"
  [{:keys [type min max period phase axis speed wave-type cycles
           channel cc path value wrap? loop-mode duration time-unit]
    :or {min 0.0
         max 1.0
         period 1.0
         phase 0.0
         loop-mode :loop
         duration 2.0
         time-unit :beats
         cycles 1.0
         speed 1.0}
    :as config}]
  (case type
    ;; Time-based waveform modulators
    :sine (sine-mod min max period phase loop-mode duration time-unit)
    :triangle (triangle-mod min max period phase loop-mode duration time-unit)
    :sawtooth (sawtooth-mod min max period phase)
    :square (square-mod min max period)
  
    ;; Decay/envelope modulators
    :beat-decay (beat-decay max min)
    :exp-decay (exp-decay max min)
    :random (random-mod min max period)
  
    ;; Position-based modulators
    :pos-x (position-x-mod min max)
    :pos-y (position-y-mod min max)
    :radial (position-radial-mod min max)
    :angle (position-angle-mod min max)
    :point-index (point-index-mod min max (boolean wrap?))
    :point-wave (point-index-wave min max cycles (get config :wave-type :sine))
    :pos-wave (position-wave min max (get config :axis :x) (period->frequency period) (get config :wave-type :sine))
  
    ;; Animated position modulators
    :pos-scroll (position-scroll min max (get config :axis :x) speed (get config :wave-type :sine))
    :rainbow-hue (rainbow-hue (get config :axis :x) speed)
  
    ;; Control modulators
    :midi (midi-mod (get config :channel 1) (get config :cc 1) min max)
    :osc (osc-mod (get config :path "/control") min max)
    :constant (constant (or value min))
  
    ;; Default fallback to sine
    (sine-mod min max period phase)))
